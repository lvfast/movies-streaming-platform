#!/usr/bin/env python3
"""Admin media MVP integration acceptance.

Dry-run by default. With --apply the harness builds the workspace images, starts one disposable
Compose project named `media-acceptance-<10 hex>` (internal application network, only the web and
storage host ports this journey needs), creates the private buckets, generates synthetic fixtures,
runs the operator grant command inside the backend container, starts the real media gateway bundle
in Node, runs the Playwright journey in `frontend/e2e/admin-media-journey.spec.ts`, writes a
redacted report under `artifacts/media-acceptance/` and removes only that project.

The report contains versions, assertion results, durations and skips. It never contains a
credential, JWT, presigned URL or raw source data: `redact` runs over the whole report and
`assert_redacted` refuses to let a secret-like value reach disk.

Provider-only R2/Cloudflare checks cannot run from this harness; they are always listed as NOT_RUN
with a reason so a missing cloud environment can never become a false pass. The exact staging
commands live in docs/runbooks/admin-media-rollout.md.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid

import requests

ROOT = Path(__file__).resolve().parents[1]
PROJECT_PREFIX = "media-acceptance-"
DEFAULT_OUTPUT = ROOT / "artifacts/media-acceptance"
COMPOSE_FILES = ("compose.yml", "compose.local.yml", "compose.media.local.yml")
IMAGES = ("backend", "transcoder", "frontend")
FFMPEG_IMAGE = "jrottenberg/ffmpeg:7-alpine"
REDACTED = "[REDACTED]"

BUILD_TIMEOUT = 1800
UP_TIMEOUT = 600
GRANT_TIMEOUT = 300
JOURNEY_TIMEOUT = 1800
TEARDOWN_TIMEOUT = 300

SECRET_KEYS = {
    "password", "passwd", "secret", "credential", "credentials", "accesskey", "secretkey",
    "apikey", "privatekey", "signingkey", "authorization", "token", "accesstoken",
    "refreshtoken", "mediatoken", "clientsecret", "workercredential",
}
VALUE_PATTERNS = (
    re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}"),
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}"),
    re.compile(
        r"(?i)(x-amz-signature|x-amz-credential|x-amz-security-token|[?&](signature|sig|token|key))"
        r"=[^&\s\"']*"
    ),
    re.compile(r"://[^/\s:@\"']+:[^/\s:@\"']+@"),
)


def run(args, *, env=None, timeout=1800):
    """Run a command and return decoded stdout, raising with bounded, utf-8 output on failure."""
    result = subprocess.run(
        args, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )
    if result.returncode:
        raise RuntimeError(
            "Command failed (%d): %s\n%s\n%s"
            % (result.returncode, " ".join(map(str, args)), result.stdout[-4000:], result.stderr[-4000:])
        )
    return result.stdout


def check_project(project):
    """Only the disposable acceptance namespace may ever be addressed or removed."""
    if not re.fullmatch(rf"{re.escape(PROJECT_PREFIX)}[0-9a-f]{{10}}", project):
        raise ValueError("Refusing a project outside the disposable acceptance namespace")


def isolate(model, project, ports, environment=None):
    """Pure transform of a rendered Compose model into the disposable acceptance project.

    The project name, volume and network names are stripped so Compose owns and prefixes every
    resource. `build` is removed because the harness builds images itself, and only the services
    named in `ports` keep a host-published port, bound to loopback.
    """
    check_project(project)
    tag = project.removeprefix(PROJECT_PREFIX)
    model["name"] = project
    model["networks"] = {
        name: {key: value for key, value in spec.items() if key != "name"}
        for name, spec in model["networks"].items()
    }
    model["volumes"] = {name: {} for name in model["volumes"]}
    for name, service in model["services"].items():
        service.pop("build", None)
        service.pop("ports", None)
        if name in IMAGES:
            service["image"] = f"media-acceptance-{name}:{tag}"
        if name in ports:
            target, published = ports[name]
            service["ports"] = [{
                "host_ip": "127.0.0.1",
                "target": int(target),
                "published": str(published),
                "protocol": "tcp",
            }]
    if environment:
        for name, values in environment.items():
            model["services"][name].setdefault("environment", {}).update(values)
    return model


def run_stack(dc, tests):
    """Bring the project up, run the journey, and remove only that project on every path."""
    try:
        dc("up", "-d", "--wait", "--wait-timeout", "420")
        tests()
    finally:
        dc("down", "--volumes", "--remove-orphans")


def normalize_key(key):
    return re.sub(r"[^a-z0-9]", "", str(key).lower())


def redact(value):
    """Recursively replace secret-like keys and values with a fixed marker."""
    if isinstance(value, dict):
        cleaned = {}
        for key, item in value.items():
            if normalize_key(key) in SECRET_KEYS:
                cleaned[key] = item if item is None or isinstance(item, bool) else REDACTED
            else:
                cleaned[key] = redact(item)
        return cleaned
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, tuple):
        return [redact(item) for item in value]
    if isinstance(value, str):
        for pattern in VALUE_PATTERNS:
            value = pattern.sub(REDACTED, value)
        return value
    return value


def assert_redacted(value):
    """Refuse to publish a report that still carries a secret-like value or field."""
    serialized = json.dumps(value)
    for pattern in VALUE_PATTERNS:
        match = pattern.search(serialized)
        if match:
            raise ValueError(f"Unredacted secret-like value: {match.group(0)[:40]}")

    def walk(node):
        if isinstance(node, dict):
            for key, item in node.items():
                if normalize_key(key) in SECRET_KEYS and item not in (None, True, False, REDACTED):
                    raise ValueError(f"Unredacted secret-like field: {key}")
                walk(item)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(value)


def summarize(report):
    """Flatten a Playwright JSON report into journey tests with their named steps."""
    tests = []

    def visit(suite):
        for spec in suite.get("specs", []):
            results = [result for test in spec.get("tests", []) for result in test.get("results", [])]
            last = results[-1] if results else {}
            steps = [
                {
                    "title": step.get("title"),
                    "status": step.get("status"),
                    "durationMs": step.get("duration", 0),
                }
                for step in last.get("steps", [])
            ]
            status = "passed" if spec.get("ok") else last.get("status", "failed")
            tests.append({
                "title": spec.get("title"),
                "status": status,
                "durationMs": last.get("duration", 0),
                "steps": steps,
                "error": (last.get("error") or {}).get("message"),
            })
        for child in suite.get("suites", []):
            visit(child)

    for suite in report.get("suites", []):
        visit(suite)
    passed = sum(1 for item in tests if item["status"] == "passed")
    failed = sum(1 for item in tests if item["status"] not in ("passed", "skipped"))
    skipped = sum(1 for item in tests if item["status"] == "skipped")
    return {
        "total": len(tests),
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
        "tests": tests,
    }


def playwright_command(launcher, grep=None):
    command = [
        launcher, "test",
        "--config", str(ROOT / "frontend" / "playwright.config.ts"),
        "--reporter", "json",
    ]
    if grep:
        command += ["--grep", grep]
    return command


def provider_checks():
    """Provider-only checks are never silently skipped or marked as passed."""
    reason = (
        "Requires separately authorized Cloudflare R2/Worker access and staging credentials; "
        "see docs/runbooks/admin-media-rollout.md. Missing cloud access does not block local MVP "
        "completion."
    )
    return [
        {"name": "cloudflare-r2-source-and-delivery-buckets", "status": "NOT_RUN", "reason": reason},
        {"name": "cloudflare-worker-protected-hls-deployment", "status": "NOT_RUN", "reason": reason},
        {"name": "cloudflare-edge-cache-and-waf", "status": "NOT_RUN", "reason": reason},
    ]


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def wait_for_http(url, timeout, expected=200):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            response = requests.get(url, timeout=5)
            if response.status_code == expected:
                return response
            last = "HTTP %d" % response.status_code
        except requests.RequestException as error:
            last = str(error)
        time.sleep(1)
    raise RuntimeError("%s did not answer %d within %ss (%s)" % (url, expected, timeout, last))


def compose_environment(overrides):
    environment = dict(os.environ)
    environment.update(overrides)
    return environment


def disposable_environment(web_url, minio_url, gateway_url, kid):
    """Environment for the disposable project.

    Storage-facing values point at the acceptance MinIO container, but the browser reaches the
    published loopback port instead: `S3_BROWSER_ENDPOINT` makes the backend presign part URLs at
    the loopback MinIO endpoint and `MEDIA_STORAGE_ORIGIN` puts that same origin into the frontend
    CSP so the browser may actually PUT to them.
    """
    return compose_environment({
        "POSTGRES_PASSWORD": "acceptance-local-only",
        "RABBITMQ_USERNAME": "media",
        "RABBITMQ_PASSWORD": "acceptance-local-only",
        "MINIO_ROOT_USER": "minioadmin",
        "MINIO_ROOT_PASSWORD": "minioadmin",
        "S3_ACCESS_KEY": "minioadmin",
        "S3_SECRET_KEY": "minioadmin",
        "S3_BROWSER_ACCESS_KEY": "minioadmin",
        "S3_BROWSER_SECRET_KEY": "minioadmin",
        "S3_BROWSER_ENDPOINT": minio_url,
        "MEDIA_STORAGE_ORIGIN": minio_url,
        "MEDIA_WORKER_CREDENTIAL": "acceptance-worker-credential",
        "WORKER_ID": "worker-acceptance",
        "MEDIA_BASE_URL": "",
        "SECURE_COOKIE": "false",
        "REFRESH_COOKIE_NAME": "refresh_token",
        "VITE_MEDIA_BASE_URL": gateway_url,
        "MEDIA_ORIGIN": gateway_url,
        "MEDIA_SIGNING_KEY_ID": kid,
    })


def build_images(env, tag, gateway_origin):
    for component in IMAGES:
        print(f"Building {component} from the current workspace", flush=True)
        command = [
            "docker", "--context", "default", "build",
            "-f", str(ROOT / component / "Dockerfile"),
            "-t", f"media-acceptance-{component}:{tag}",
        ]
        if component == "frontend":
            command += ["--build-arg", f"VITE_MEDIA_BASE_URL={gateway_origin}"]
        command.append(str(ROOT))
        run(command, env=env, timeout=BUILD_TIMEOUT)


def create_buckets(minio_url, source_bucket, delivery_bucket, client=None):
    import boto3
    from botocore.config import Config

    if client is None:
        client = boto3.client(
            "s3",
            endpoint_url=minio_url,
            region_name="us-east-1",
            aws_access_key_id="minioadmin",
            aws_secret_access_key="minioadmin",
            config=Config(s3={"addressing_style": "path"}),
        )
    for bucket in (source_bucket, delivery_bucket):
        try:
            client.create_bucket(Bucket=bucket)
        except client.exceptions.BucketAlreadyOwnedByYou:
            pass
    # The gateway process on this machine reads delivery objects with no storage credential. This
    # anonymous read policy exists only in the disposable acceptance bucket, which holds synthetic
    # fixtures, and the gateway still authorizes every protected path itself.
    client.put_bucket_policy(Bucket=delivery_bucket, Policy=json.dumps({
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": {"AWS": ["*"]},
            "Action": ["s3:GetObject"],
            "Resource": [f"arn:aws:s3:::{delivery_bucket}/*"],
        }],
    }))


def minio_cors_environment(web_origin):
    """Server-level CORS for the disposable MinIO, scoped to the journey's web origin.

    MinIO has no bucket-level CORS API; its documented equivalent is the server environment. The
    production equivalent (Cloudflare R2 bucket CORS) is part of the rollout runbook.
    """
    return {
        "MINIO_API_CORS_ALLOW_ORIGIN": web_origin,
        "MINIO_API_CORS_ALLOW_METHODS": "PUT,HEAD,OPTIONS",
    }


def generate_fixtures(work_dir):
    work_dir = str(work_dir)
    fixtures = {
        "poster": os.path.join(work_dir, "poster.jpg"),
        "backdrop": os.path.join(work_dir, "backdrop.jpg"),
        "video": os.path.join(work_dir, "original.mp4"),
        "replacement": os.path.join(work_dir, "replacement.mp4"),
    }
    jobs = [
        (fixtures["poster"],
         ["-f", "lavfi", "-i", "color=c=0xC82828:s=600x900", "-frames:v", "1", "-q:v", "3",
          os.path.basename(fixtures["poster"])]),
        (fixtures["backdrop"],
         ["-f", "lavfi", "-i", "color=c=0x2846C8:s=1600x900", "-frames:v", "1", "-q:v", "3",
          os.path.basename(fixtures["backdrop"])]),
        (fixtures["video"],
         ["-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=20",
          "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
          "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-b:v", "9M",
          "-c:a", "aac", "-b:a", "96k", "-shortest", "-movflags", "+faststart",
          os.path.basename(fixtures["video"])]),
        (fixtures["replacement"],
         ["-f", "lavfi", "-i", "smptebars=size=960x540:rate=30:duration=8",
          "-f", "lavfi", "-i", "sine=frequency=660:duration=8",
          "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-b:v", "3M",
          "-c:a", "aac", "-b:a", "96k", "-shortest", "-movflags", "+faststart",
          os.path.basename(fixtures["replacement"])]),
    ]
    for path, arguments in jobs:
        run([
            "docker", "--context", "default", "run", "--rm",
            "-v", f"{work_dir}:/work", "-w", "/work", FFMPEG_IMAGE,
            "-hide_banner", "-loglevel", "error", *arguments,
        ], timeout=600)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            raise RuntimeError(f"Fixture generation produced no file at {path}")
    return fixtures


def register_user(web_url, username, password):
    response = requests.post(
        f"{web_url}/api/v1/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    )
    if response.status_code != 201:
        raise RuntimeError(f"Registration failed: {response.status_code} {response.text[:300]}")
    return response.json()


def login_user(web_url, username, password):
    response = requests.post(
        f"{web_url}/api/v1/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    if response.status_code != 200:
        raise RuntimeError(f"Login failed: {response.status_code} {response.text[:300]}")
    return response.json()


def public_jwk(pem_path, kid):
    node = shutil.which("node") or "node"
    script = (
        "const {createPublicKey}=require('node:crypto');const fs=require('fs');"
        "const jwk=createPublicKey(fs.readFileSync(process.argv[1],'utf8')).export({format:'jwk'});"
        "console.log(JSON.stringify({...jwk,kid:process.argv[2],alg:'RS256',use:'sig'}));"
    )
    return run([node, "-e", script, str(pem_path), kid], timeout=60).strip()


def detect_browser_channel():
    # CI runners install Chrome for Testing through Playwright instead of a system browser; the
    # channel can be pinned explicitly for those environments.
    override = os.environ.get("MEDIA_ACCEPTANCE_BROWSER_CHANNEL")
    if override:
        return override
    candidates = [
        ("chrome", shutil.which("chrome")),
        ("chrome", shutil.which("google-chrome")),
        ("chrome", r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
        ("chrome", r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
        ("msedge", shutil.which("msedge")),
        ("msedge", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
        ("msedge", r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
    ]
    for channel, path in candidates:
        if path and os.path.exists(path):
            return channel
    return "chromium"


def playwright_launcher():
    binary = "playwright.cmd" if os.name == "nt" else "playwright"
    candidate = ROOT / "frontend" / "node_modules" / ".bin" / binary
    if candidate.exists():
        return str(candidate)
    return shutil.which("playwright") or "playwright"


def collect_versions():
    versions = {"python": platform.python_version(), "platform": platform.platform()}
    probes = {
        "docker": ["docker", "version", "--format", "{{.Server.Version}}"],
        "compose": ["docker", "compose", "version", "--short"],
        "node": [shutil.which("node") or "node", "--version"],
        "playwright": [playwright_launcher(), "--version"],
        "browserChannel": None,
    }
    for name, command in probes.items():
        if command is None:
            continue
        try:
            versions[name] = run(command, timeout=60).strip().splitlines()[0]
        except Exception:
            versions[name] = "unavailable"
    return versions


def image_digest(image):
    try:
        return run([
            "docker", "--context", "default", "image", "inspect", image,
            "--format", "{{.Id}}",
        ], timeout=60).strip()
    except Exception:
        return "unavailable"


def write_report(path, report):
    report = redact(report)
    assert_redacted(report)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def execute(apply=False, grep=None, output=DEFAULT_OUTPUT):
    if not apply:
        print(
            "DRY-RUN: build backend/transcoder/frontend from the current workspace -> one "
            "disposable Compose project `media-acceptance-<10 hex>` with an internal application "
            "network and loopback-only web/storage ports -> private buckets, synthetic fixtures, "
            "operator ADMIN grant and the real gateway bundle -> Playwright journey -> redacted "
            "report under artifacts/media-acceptance -> remove only that project. "
            "No production connection is made. Re-run with --apply to execute."
        )
        return 0

    project = PROJECT_PREFIX + uuid.uuid4().hex[:10]
    check_project(project)
    tag = project.removeprefix(PROJECT_PREFIX)
    output = output.resolve() / project
    output.mkdir(parents=True)
    started_at = datetime.datetime.now(datetime.timezone.utc)
    report = {
        "schemaVersion": 1,
        "project": project,
        "startedAt": started_at.isoformat(),
        "status": "FAIL",
        "versions": collect_versions(),
        "images": {},
        "setup": {},
        "journey": None,
        "providerChecks": provider_checks(),
        "skips": [],
        "limitations": [
            "The media gateway runs as the real Worker bundle on Node against the local MinIO "
            "delivery bucket; workerd, the Cloudflare edge cache and real R2 are not exercised.",
            "The journey uses synthetic accounts and legally generated small fixtures in a "
            "disposable project; it performs no provider deployment and deletes no retained media.",
            "Provider-only R2/Cloudflare checks are NOT_RUN here; see "
            "docs/runbooks/admin-media-rollout.md for the staging commands.",
        ],
    }
    failure = None
    web_port, minio_port, gateway_port = free_port(), free_port(), free_port()
    web_url = f"http://127.0.0.1:{web_port}"
    minio_url = f"http://127.0.0.1:{minio_port}"
    gateway_url = f"http://127.0.0.1:{gateway_port}"
    kid = f"media-acceptance-{tag}"
    work_dir = Path(tempfile.mkdtemp(prefix=project))
    gateway_process = None
    gateway_log = None
    user_env = disposable_environment(web_url, minio_url, gateway_url, kid)
    config = output / "compose.json"
    dc = None
    try:
        print(f"Disposable project: {project}", flush=True)
        build_images(user_env, tag, gateway_url)
        raw = run([
            "docker", "--context", "default", "compose",
            "--env-file", str(ROOT / ".env.example"),
            "-f", str(ROOT / "compose.yml"),
            "-f", str(ROOT / "compose.local.yml"),
            "-f", str(ROOT / "compose.media.local.yml"),
            "config", "--format", "json",
        ], env=user_env, timeout=120)
        model = isolate(
            json.loads(raw),
            project,
            ports={"frontend": (8080, web_port), "minio": (9000, minio_port)},
            environment={
                "backend": {"MEDIA_SIGNING_KEY_ID": kid},
                "minio": minio_cors_environment(web_url),
            },
        )
        config.write_text(json.dumps(model), encoding="utf-8")

        def dc(*parts):
            return run([
                "docker", "--context", "default", "compose",
                "--project-name", project, "-f", str(config), *parts,
            ], env=user_env, timeout=TEARDOWN_TIMEOUT if parts and parts[0] == "down" else UP_TIMEOUT)

        def journey():
            report["setup"]["projectIsolation"] = (
                "internal application network; loopback-only web and storage ports"
            )
            wait_for_http(minio_url + "/minio/health/live", 120)
            create_buckets(minio_url, model["services"]["backend"]["environment"]["MEDIA_SOURCE_BUCKET"],
                           model["services"]["backend"]["environment"]["MEDIA_DELIVERY_BUCKET"])
            fixtures = generate_fixtures(work_dir)
            admin_username = "acceptance_admin_" + uuid.uuid4().hex[:8]
            viewer_username = "acceptance_viewer_" + uuid.uuid4().hex[:8]
            admin_password = uuid.uuid4().hex + "Aa1!"
            viewer_password = uuid.uuid4().hex + "Aa1!"
            register_user(web_url, admin_username, admin_password)
            register_user(web_url, viewer_username, viewer_password)
            grant = dc(
                "exec", "-T", "backend", "java", "-jar", "/app/application.jar",
                "--spring.main.web-application-type=none",
                "--app.catalog.import-enabled=false",
                f"--app.ops.roles=grant:{admin_username}",
            )
            if "grant" not in grant.lower() and "role" not in grant.lower():
                print(grant, flush=True)
            login = login_user(web_url, admin_username, admin_password)
            if "ADMIN" not in (login.get("user", {}).get("roles") or []):
                raise RuntimeError("The operator grant did not produce an ADMIN session")
            report["setup"]["operatorCommand"] = (
                "compose exec backend java -jar /app/application.jar "
                "--spring.main.web-application-type=none --app.catalog.import-enabled=false "
                f"--app.ops.roles=grant:{admin_username}"
            )
            report["setup"]["accounts"] = {
                "admin": admin_username,
                "viewer": viewer_username,
            }

            pem = dc("exec", "-T", "backend", "cat", "/run/secrets/media-public.pem")
            pem_path = work_dir / "media-public.pem"
            pem_path.write_text(pem, encoding="ascii")
            nonlocal gateway_process, gateway_log
            gateway_log = open(output / "gateway.log", "w", encoding="utf-8")
            gateway_process = subprocess.Popen(
                [
                    shutil.which("node") or "node",
                    str(ROOT / "media-gateway" / "p5-gateway-node.mjs"),
                    model["services"]["backend"]["environment"]["MEDIA_DELIVERY_BUCKET"],
                    "minioadmin", "minioadmin", str(gateway_port),
                    "lvfast-media-backend", "lvfast-media", web_url,
                    public_jwk(pem_path, kid),
                ],
                cwd=str(ROOT / "media-gateway"),
                stdout=gateway_log, stderr=subprocess.STDOUT,
                env=compose_environment({"AWS_ENDPOINT_URL_S3": minio_url}),
            )
            wait_for_http(gateway_url + "/__smoke/requests", 120)

            channel = detect_browser_channel()
            report["versions"]["browserChannel"] = channel
            play_env = compose_environment({
                "MEDIA_ACCEPTANCE_BASE_URL": web_url,
                "MEDIA_ACCEPTANCE_GATEWAY_URL": gateway_url,
                "MEDIA_ACCEPTANCE_FIXTURES": str(work_dir),
                "PLAYWRIGHT_JSON_OUTPUT_NAME": str(output / "playwright.json"),
                "MEDIA_ACCEPTANCE_BROWSER_CHANNEL": channel,
                "MEDIA_ACCEPTANCE_ADMIN_USERNAME": admin_username,
                "MEDIA_ACCEPTANCE_ADMIN_PASSWORD": admin_password,
                "MEDIA_ACCEPTANCE_VIEWER_USERNAME": viewer_username,
                "MEDIA_ACCEPTANCE_VIEWER_PASSWORD": viewer_password,
            })
            journey_started = time.time()
            print("Running the complete Playwright journey", flush=True)
            result = subprocess.run(
                playwright_command(playwright_launcher(), grep),
                cwd=str(ROOT / "frontend"), env=play_env,
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=JOURNEY_TIMEOUT,
            )
            (output / "playwright.log").write_text(
                (result.stdout or "") + (result.stderr or ""), encoding="utf-8", errors="replace")
            report["durationsMs"] = {"journey": int((time.time() - journey_started) * 1000)}
            playwright_report = output / "playwright.json"
            if playwright_report.exists():
                summary = summarize(json.loads(playwright_report.read_text(encoding="utf-8")))
            else:
                summary = {
                    "total": 0, "passed": 0, "failed": 1, "skipped": 0,
                    "tests": [{
                        "title": "Playwright journey",
                        "status": "failed",
                        "durationMs": 0,
                        "steps": [],
                        "error": f"Playwright wrote no report (exit {result.returncode})",
                    }],
                }
            report["journey"] = summary
            if result.returncode or summary["failed"] or summary["total"] == 0:
                raise RuntimeError(f"Journey failed (exit {result.returncode})")
            report["status"] = "PASS"

        run_stack(dc, journey)
    except Exception as error:
        failure = str(error)
        report["failure"] = failure
        print(f"FAIL: {failure}", flush=True)
    finally:
        if gateway_process and gateway_process.poll() is None:
            gateway_process.kill()
            try:
                gateway_process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
        if gateway_log:
            gateway_log.close()
        shutil.rmtree(work_dir, ignore_errors=True)
        try:
            if dc is not None:
                dc("down", "--volumes", "--remove-orphans")
        except Exception as cleanup_error:
            print(f"Cleanup warning: {cleanup_error}", flush=True)

    report["endedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    if report.get("durationsMs") and "journey" in report["durationsMs"]:
        report["durationsMs"]["total"] = int(
            (datetime.datetime.now(datetime.timezone.utc) - started_at).total_seconds() * 1000)
    report["images"] = {
        component: {
            "tag": f"media-acceptance-{component}:{tag}",
            "digest": image_digest(f"media-acceptance-{component}:{tag}"),
        }
        for component in IMAGES
    }
    write_report(output / "report.json", report)
    print(f"Report: {output / 'report.json'}", flush=True)
    if failure:
        return 1
    print(f"PASS: complete admin media journey; removed only {project}", flush=True)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apply", action="store_true",
                        help="run locally using a disposable Docker project")
    parser.add_argument("--grep", default=None,
                        help="Playwright title filter for a focused re-run (diagnostic only)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                        help="directory that receives one report folder per project")
    args = parser.parse_args(argv)
    return execute(apply=args.apply, grep=args.grep, output=args.output)


if __name__ == "__main__":
    sys.exit(main())
