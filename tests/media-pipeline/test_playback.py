#!/usr/bin/env python3
"""P5 protected-playback browser smoke against the disposable Compose stack.

Run from the repository root:

    python tests/media-pipeline/test_playback.py -v

The scenario drives the real vertical slice *and* the real browser client: a managed movie is
created, a synthetic video plus artwork are uploaded through the private multipart path, the
RabbitMQ worker produces READY HLS, publication promotes the validated artwork, the backend issues a
version-pinned playback grant, and then the production React build served by the production Nginx
configuration loads `/watch/{movieId}` in headless Chrome. The gateway runs the real Worker bundle
against the Compose MinIO delivery bucket, so the assertions cover what a viewer's browser really
does:

- the Nginx CSP names the configured media origin in `connect-src` (otherwise the browser blocks the
  manifest before any request leaves the page);
- the bundle resolves the backend's root-relative HLS path onto that media origin;
- HLS.js sends the media bearer header to the gateway and not to the API origin;
- the authenticated manifest and at least one segment are authorized and decoded (`readyState`,
  advancing `currentTime`, a decoded frame);
- an unauthenticated request to the same canonical manifest is refused with 401 before any storage
  read.

Requires Docker, Python packages requests/boto3/websocket-client/Pillow, and a Chrome/Edge binary.
Node is used to run the gateway bundle. See README.md.
"""

import base64
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid

import boto3
import requests
import websocket
from botocore.config import Config

PROJECT = "media-playback-test"
BACKEND = "http://127.0.0.1:18080"
WEB = "http://127.0.0.1:18081"
MINIO = "http://127.0.0.1:19000"
GATEWAY_PORT = 8899
GATEWAY = "http://127.0.0.1:%d" % GATEWAY_PORT
CDP_PORT = 9222
SOURCE_BUCKET = "media-source"
DELIVERY_BUCKET = "media-delivery"
ACCESS_KEY = "minioadmin"
SECRET_KEY = "minioadmin"
MEDIA_KID = "p5-local-kid"
MEDIA_ISSUER = "lvfast-media-backend"
MEDIA_AUDIENCE = "lvfast-media"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COMPOSE_FILE = os.path.join(REPO_ROOT, "tests", "media-pipeline", "compose.p5.yml")
GATEWAY_DIR = os.path.join(REPO_ROOT, "media-gateway")
FFMPEG_IMAGE = "jrottenberg/ffmpeg:7-alpine"


def run(cmd, **kwargs):
    # Container build logs are UTF-8; the Windows default code page would otherwise mangle them.
    kwargs.setdefault("encoding", "utf-8")
    kwargs.setdefault("errors", "replace")
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def compose(*args):
    return run(["docker", "compose", "-p", PROJECT, "-f", COMPOSE_FILE] + list(args))


def psql(sql):
    result = run([
        "docker", "compose", "-p", PROJECT, "exec", "-T", "postgres",
        "psql", "-U", "media_streaming", "-d", "media_streaming", "-t", "-A", "-c", sql,
    ])
    if result.returncode != 0:
        raise RuntimeError("psql failed: " + result.stderr)
    return result.stdout.strip()


def s3_client():
    return boto3.client(
        "s3",
        endpoint_url=MINIO,
        region_name="us-east-1",
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
        config=Config(s3={"addressing_style": "path"}),
    )


def find_chrome():
    candidates = [
        shutil.which("chrome"),
        shutil.which("google-chrome"),
        shutil.which("chromium"),
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/usr/bin/google-chrome",
    ]
    for candidate in candidates:
        if candidate and os.path.exists(candidate):
            return candidate
    raise RuntimeError("No Chrome or Edge binary found for the browser smoke")


def generate_artwork(kind):
    """A small real JPEG; artwork validation checks the container, not the pixel budget."""
    from PIL import Image

    image = Image.new("RGB", (8, 8), (200, 40, 40) if kind == "POSTER" else (20, 40, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG")
    return buffer.getvalue()


def free_port(port):
    """Kill a listener left behind by an interrupted earlier run so the harness owns the port."""
    if os.name == "nt":
        result = run([
            "powershell", "-NoProfile", "-Command",
            "(Get-NetTCPConnection -LocalPort %d -State Listen -ErrorAction SilentlyContinue | "
            "Select-Object -ExpandProperty OwningProcess) -join ','" % port,
        ])
        pids = {value.strip() for value in result.stdout.replace("\r", "").split(",") if value.strip().isdigit()}
        for pid in pids:
            # /T also removes the npx/wrangler supervisor that would otherwise restart the listener.
            run(["taskkill", "/F", "/T", "/PID", pid])
    else:
        result = run(["bash", "-c", "lsof -ti tcp:%d || true" % port])
        for pid in {line.strip() for line in result.stdout.splitlines() if line.strip().isdigit()}:
            try:
                os.kill(int(pid), 9)
            except OSError:
                pass
    time.sleep(0.5)


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


class Browser:
    """Minimal Chrome DevTools Protocol session used to drive the real application."""

    def __init__(self, chrome):
        self.chrome = chrome

    def __enter__(self):
        profile = tempfile.mkdtemp(prefix="p5-chrome-")
        self.profile = profile
        self.process = subprocess.Popen(
            [
                self.chrome,
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--autoplay-policy=no-user-gesture-required",
                "--user-data-dir=" + profile,
                "--remote-debugging-port=%d" % CDP_PORT,
                "--remote-allow-origins=*",
                "about:blank",
            ],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        target = wait_for_debug_target(30)
        self.connection = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=20)
        self.sequence = 0
        self.send("Page.enable")
        self.send("Runtime.enable")
        return self

    def __exit__(self, *exc_info):
        try:
            self.connection.close()
        finally:
            self.process.kill()
            time.sleep(0.5)
            shutil.rmtree(self.profile, ignore_errors=True)

    def send(self, method, params=None):
        self.sequence += 1
        message_id = self.sequence
        self.connection.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))
        deadline = time.time() + 15
        while time.time() < deadline:
            message = json.loads(self.connection.recv())
            if message.get("id") == message_id:
                if "error" in message:
                    raise RuntimeError("%s failed: %s" % (method, message["error"]))
                return message.get("result", {})
        raise RuntimeError("%s did not answer" % method)

    def evaluate(self, expression):
        result = self.send("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": True,
        })
        return result.get("result", {}).get("value")

    def navigate(self, url):
        self.send("Page.navigate", {"url": url})


def wait_for_debug_target(timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % CDP_PORT, timeout=2) as response:
                targets = json.loads(response.read().decode("utf-8"))
            for target in targets:
                if target.get("type") == "page":
                    return target
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError("Chrome did not expose a debugging target within %ss" % timeout)


class PlaybackSmokeTest(unittest.TestCase):
    """The production player, served by the production Nginx config, plays protected HLS."""

    token = None
    user_id = None
    movie_id = None
    version_id = None
    attempt_id = None
    work_dir = None
    gateway = None
    browser = None
    healthy_requests = None

    @classmethod
    def setUpClass(cls):
        docker = run(["docker", "version", "--format", "{{.Server.Version}}"])
        if docker.returncode != 0:
            raise unittest.SkipTest("Docker is not available: " + docker.stderr.strip())

        cls.work_dir = tempfile.mkdtemp(prefix="p5-playback-")
        # Image builds are slow; P5_SKIP_BUILD=1 reuses a stack that is already running.
        if os.environ.get("P5_SKIP_BUILD") != "1":
            built = compose("build")
            if built.returncode != 0:
                raise RuntimeError("compose build failed: " + built.stderr[-4000:])
            up = compose("up", "-d")
            if up.returncode != 0:
                raise RuntimeError("compose up failed: " + up.stderr[-4000:])
        cls._wait_for_stack()
        cls._create_buckets()
        cls._generate_video()
        cls._start_gateway()
        cls._register_and_login_in_browser()
        cls._publish_managed_movie()

    @classmethod
    def tearDownClass(cls):
        if cls.browser:
            cls.browser.__exit__()
            cls.browser = None
        if cls.gateway and cls.gateway.poll() is None:
            cls.gateway.kill()
            cls.gateway.wait(timeout=30)
        if getattr(cls, "_gateway_log", None):
            cls._gateway_log.close()
        compose("down", "-v")
        free_port(GATEWAY_PORT)
        if cls.work_dir and os.path.isdir(cls.work_dir):
            shutil.rmtree(cls.work_dir, ignore_errors=True)

    @classmethod
    def _wait_for_stack(cls):
        wait_for_http(BACKEND + "/actuator/health/liveness", 240)
        # Nginx proxies /api to the backend, so an answer here proves both containers are serving.
        wait_for_http(WEB + "/healthz", 120)

    @classmethod
    def _create_buckets(cls):
        client = s3_client()
        for bucket in (SOURCE_BUCKET, DELIVERY_BUCKET):
            try:
                client.create_bucket(Bucket=bucket)
            except client.exceptions.BucketAlreadyOwnedByYou:
                pass
        # The local gateway runs outside the Compose network and reads the delivery bucket over plain
        # HTTP, so that bucket allows anonymous reads in this disposable stack only. It holds nothing
        # but synthetic smoke media, and the gateway still authorizes every protected path itself.
        client.put_bucket_policy(Bucket=DELIVERY_BUCKET, Policy=json.dumps({
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {"AWS": ["*"]},
                "Action": ["s3:GetObject"],
                "Resource": ["arn:aws:s3:::%s/*" % DELIVERY_BUCKET],
            }],
        }))

    @classmethod
    def _generate_video(cls):
        source = os.path.join(cls.work_dir, "source.mp4")
        command = [
            "docker", "run", "--rm",
            "-v", "%s:/work" % cls.work_dir,
            "-w", "/work",
            FFMPEG_IMAGE,
            "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=24:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
            "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "64k", "-shortest", "-movflags", "+faststart",
            "source.mp4",
        ]
        result = run(command)
        if result.returncode != 0 or not os.path.exists(source):
            raise RuntimeError("fixture generation failed: " + result.stderr[-2000:])

    @classmethod
    def _register_and_login_in_browser(cls):
        """Log in from the page itself so the httpOnly refresh cookie lands in the browser."""
        cls.browser = Browser(find_chrome()).__enter__()
        cls.username = "p5smoke_" + uuid.uuid4().hex[:8]
        cls.browser.navigate(WEB + "/")
        wait_until(cls.browser, "document.readyState === 'complete'", 60, "the web app to load")

        script = """
        (async () => {
          const response = await fetch('http://127.0.0.1:18081/api/v1/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(%s),
          });
          return JSON.stringify({ status: response.status, body: await response.json() });
        })()
        """ % json.dumps({"username": cls.username, "password": "LongEnough9X"})
        registered = json.loads(cls.browser.evaluate(script))
        if registered["status"] != 201:
            raise RuntimeError("browser registration failed: %s" % registered)

        cls.user_id = registered["body"]["user"]["id"]
        psql("insert into user_role(user_id, role) values ('%s', 'ADMIN') on conflict do nothing"
              % cls.user_id)

        login_script = """
        (async () => {
          const response = await fetch('http://127.0.0.1:18081/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(%s),
          });
          return JSON.stringify({ status: response.status, body: await response.json() });
        })()
        """ % json.dumps({"username": cls.username, "password": "LongEnough9X"})
        logged_in = json.loads(cls.browser.evaluate(login_script))
        if logged_in["status"] != 200:
            raise RuntimeError("browser login failed: %s" % logged_in)
        cls.token = logged_in["body"]["accessToken"]

    @classmethod
    def _headers(cls, extra=None):
        headers = {"Authorization": "Bearer " + cls.token}
        if extra:
            headers.update(extra)
        return headers

    @classmethod
    def _start_gateway(cls):
        free_port(GATEWAY_PORT)
        public_pem = compose("exec", "-T", "backend", "cat", "/run/secrets/media-public.pem")
        if public_pem.returncode != 0:
            raise RuntimeError("could not read the media public key: " + public_pem.stderr)
        jwk = public_jwk(public_pem.stdout)
        runner = os.path.join(REPO_ROOT, "media-gateway", "p5-gateway-node.mjs")
        if not os.path.exists(runner):
            raise RuntimeError("the local media gateway runner is missing at " + runner)
        node = shutil.which("node")
        cls._gateway_log = open(os.path.join(cls.work_dir, "gateway.log"), "w", encoding="utf-8")
        # The runner executes the real gateway bundle with an HTTP server and an R2-compatible
        # delivery bucket backed by the Compose MinIO delivery bucket, so the browser exercises the
        # production authorization and delivery code paths instead of a stub. ALLOWED_ORIGINS is the
        # exact web origin, because the browser must be able to read the gateway response.
        environment = dict(os.environ)
        environment["AWS_ENDPOINT_URL_S3"] = MINIO
        cls.gateway = subprocess.Popen(
            [node, runner, DELIVERY_BUCKET, ACCESS_KEY, SECRET_KEY, str(GATEWAY_PORT),
             MEDIA_ISSUER, MEDIA_AUDIENCE, WEB, json.dumps(jwk)],
            cwd=GATEWAY_DIR, stdout=cls._gateway_log, stderr=subprocess.STDOUT, env=environment,
        )
        deadline = time.time() + 120
        while time.time() < deadline:
            if cls.gateway.poll() is not None:
                raise RuntimeError("the media gateway exited early: " + cls._gateway_tail())
            try:
                requests.get(GATEWAY + "/__smoke/requests", timeout=10)
                return
            except requests.RequestException:
                time.sleep(1)
        raise RuntimeError("the media gateway did not answer within 120s: " + cls._gateway_tail())

    @classmethod
    def _gateway_tail(cls):
        try:
            cls._gateway_log.flush()
            with open(os.path.join(cls.work_dir, "gateway.log"), encoding="utf-8", errors="replace") as handle:
                return handle.read()[-1500:]
        except Exception:
            return "(no gateway log)"

    @classmethod
    def _fingerprint(cls, path):
        size = os.path.getsize(path)
        block = 1024 * 1024
        digest = __import__("hashlib").sha256()
        digest.update(str(size).encode("utf-8"))
        digest.update(b"\n")
        with open(path, "rb") as handle:
            digest.update(handle.read(block))
            if size > block:
                handle.seek(max(0, size - block))
                digest.update(handle.read(block))
        return "sha256:" + digest.hexdigest()

    @classmethod
    def _upload(cls, movie_id, kind, path, content_type):
        size = os.path.getsize(path)
        created = requests.post(
            WEB + "/api/v1/admin/movies/%s/uploads" % movie_id,
            headers=cls._headers({"Idempotency-Key": "p5smoke-upload-" + uuid.uuid4().hex}),
            json={
                "kind": kind,
                "fileName": os.path.basename(path),
                "contentType": content_type,
                "sizeBytes": size,
                "resumeFingerprint": cls._fingerprint(path),
            },
            timeout=30,
        )
        if created.status_code != 201:
            raise RuntimeError("upload creation failed: %s %s" % (created.status_code, created.text))
        session = created.json()
        upload_id = session["id"]
        part_size = session["partSizeBytes"]
        total_parts = session["totalParts"]

        signed = requests.post(
            WEB + "/api/v1/admin/uploads/%s/part-urls" % upload_id,
            headers=cls._headers(),
            json={"partNumbers": list(range(1, total_parts + 1))},
            timeout=30,
        )
        if signed.status_code != 200:
            raise RuntimeError("part signing failed: %s %s" % (signed.status_code, signed.text))
        urls = {item["partNumber"]: item["url"] for item in signed.json()["items"]}

        with open(path, "rb") as handle:
            for number in range(1, total_parts + 1):
                chunk = handle.read(part_size)
                uploaded = requests.put(urls[number], data=chunk, timeout=60)
                if uploaded.status_code not in (200, 204):
                    raise RuntimeError("part %d upload failed: %s" % (number, uploaded.status_code))

        completed = requests.post(
            WEB + "/api/v1/admin/uploads/%s/complete" % upload_id,
            headers=cls._headers({"Idempotency-Key": "p5smoke-complete-" + uuid.uuid4().hex}),
            timeout=60,
        )
        if completed.status_code != 200:
            raise RuntimeError("upload completion failed: %s %s" % (completed.status_code, completed.text))
        return completed.json()

    @classmethod
    def _movie(cls, slug):
        created = requests.post(
            WEB + "/api/v1/admin/movies",
            headers=cls._headers({"Idempotency-Key": "p5smoke-movie-" + uuid.uuid4().hex}),
            json={
                "title": "P5 Smoke",
                "slug": slug,
                "synopsis": "Protected playback smoke.",
                "releaseYear": 2026,
                "maturityRating": "PG",
                "genreIds": [int(psql("select id from genre order by id limit 1"))],
                "featured": False,
            },
            timeout=20,
        )
        if created.status_code != 201:
            raise RuntimeError("movie creation failed: %s %s" % (created.status_code, created.text))
        return created.json()["id"]

    @classmethod
    def _if_match(cls, movie_id):
        """The publish/activate commands require the exact current movie revision."""
        revision = psql("select revision from movie where id = '%s'" % movie_id)
        if not revision:
            raise RuntimeError("could not read the revision of movie %s" % movie_id)
        return '"%s"' % revision

    @classmethod
    def _wait_for_assets(cls, movie_id, timeout=300, required=2):
        """Wait until `required` artwork artifacts of this movie are READY."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            assets = requests.get(
                WEB + "/api/v1/admin/movies/%s/assets" % movie_id,
                headers=cls._headers(), timeout=20,
            ).json()
            ready = [item for item in assets["items"] if item["state"] == "READY"]
            failed = [item for item in assets["items"] if item["state"] == "FAILED"]
            if failed:
                raise RuntimeError("an artwork job failed: %s" % json.dumps(failed))
            if len(ready) >= required:
                return ready
            time.sleep(3)
        raise RuntimeError("artwork did not become READY within %ss" % timeout)

    @classmethod
    def _wait_for_ready_version(cls, movie_id, timeout=420):
        """Wait until the movie has a READY media version and return its id."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            versions = requests.get(
                WEB + "/api/v1/admin/movies/%s/versions" % movie_id,
                headers=cls._headers(), timeout=20,
            ).json()
            ready = [item for item in versions["items"] if item["state"] == "READY"]
            failed = [item for item in versions["items"] if item["state"] == "FAILED"]
            if failed:
                raise RuntimeError("a processing job failed: %s" % json.dumps(failed))
            if ready:
                return ready[0]["id"]
            time.sleep(3)
        raise RuntimeError("the media version did not become READY within %ss" % timeout)

    @classmethod
    def _publish_managed_movie(cls):
        slug = "p5-smoke-" + uuid.uuid4().hex[:8]
        movie_id = cls._movie(slug)
        cls.movie_id = movie_id

        poster = os.path.join(cls.work_dir, "poster.jpg")
        backdrop = os.path.join(cls.work_dir, "backdrop.jpg")
        with open(poster, "wb") as handle:
            handle.write(generate_artwork("POSTER"))
        with open(backdrop, "wb") as handle:
            handle.write(generate_artwork("BACKDROP"))
        cls._upload(movie_id, "POSTER", poster, "image/jpeg")
        cls._upload(movie_id, "BACKDROP", backdrop, "image/jpeg")
        assets = cls._wait_for_assets(movie_id, timeout=300, required=2)

        video = cls._upload(movie_id, "VIDEO", os.path.join(cls.work_dir, "source.mp4"), "video/mp4")
        if not video.get("jobId"):
            raise RuntimeError("video upload did not create a processing job")
        cls.version_id = cls._wait_for_ready_version(movie_id, timeout=420)

        poster_asset = next(item["id"] for item in assets if item["kind"] == "POSTER")
        backdrop_asset = next(item["id"] for item in assets if item["kind"] == "BACKDROP")
        published = requests.post(
            WEB + "/api/v1/admin/movies/%s/publish" % movie_id,
            headers=cls._headers({
                "Idempotency-Key": "p5smoke-publish-" + uuid.uuid4().hex,
                "If-Match": cls._if_match(movie_id),
            }),
            json={
                "mediaVersionId": cls.version_id,
                "posterAssetId": poster_asset,
                "backdropAssetId": backdrop_asset,
            },
            timeout=60,
        )
        if published.status_code != 200:
            raise RuntimeError("publish failed: %s %s" % (published.status_code, published.text))
        cls.attempt_id = psql(
            "select split_part(output_prefix, '/', 4) from media_job_attempt "
            "where job_id = (select id from media_job where media_version_id = '%s' "
            "and state = 'SUCCEEDED' order by attempt_number desc limit 1)" % cls.version_id
        )
        if not cls.attempt_id:
            raise RuntimeError("could not resolve the immutable attempt prefix")

    @classmethod
    def _playback_grant(cls):
        response = requests.get(
            WEB + "/api/v1/movies/%s/playback" % cls.movie_id,
            headers=cls._headers(), timeout=20,
        )
        if response.status_code != 200:
            raise RuntimeError("playback grant failed: %s %s" % (response.status_code, response.text))
        return response.json()

    @classmethod
    def _gateway_requests(cls):
        return requests.get(GATEWAY + "/__smoke/requests", timeout=20).json()

    @classmethod
    def _built_scripts(cls):
        """
        Every built JavaScript asset that is reachable over HTTP.

        Nginx serves no directory index, so the entry page names only the eagerly loaded bundle. The
        lazily loaded chunks (the player among them) are discovered by following the relative import
        specifiers the built code contains, which keeps this check off the filesystem entirely.
        """
        page = requests.get(WEB + "/", timeout=20).text
        pending = [
            "/assets/" + name
            for name in re.findall(r'/assets/([^"/]+\.js)', page)
        ]
        found = []
        while pending:
            script = pending.pop()
            if script in found:
                continue
            found.append(script)
            source = requests.get(WEB + script, timeout=30).text
            for name in re.findall(r'["\'`]\./([A-Za-z0-9_.-]+\.js)["\'`]', source):
                candidate = "/assets/" + name
                if candidate not in found:
                    pending.append(candidate)
        return sorted(found)

    def test_production_player_decodes_protected_hls_through_nginx_csp(self):
        # The grant is read through Nginx so the API path the browser uses is the one under test.
        grant = self._playback_grant()
        self.assertIn("sessionId", grant, "a managed movie must answer a version-pinned grant")
        self.assertEqual(self.version_id, grant["mediaVersionId"])
        manifest_path = grant["manifestUrl"]
        self.assertTrue(
            manifest_path.startswith("/hls/%s/%s/%s/" % (self.movie_id, self.version_id, self.attempt_id)),
            "the backend must answer a root-relative HLS path: %s" % manifest_path,
        )

        # 1. The production CSP must allow the media origin, or the browser blocks the manifest
        #    before a single request reaches the gateway.
        page = requests.get(WEB + "/", timeout=20)
        csp = page.headers.get("Content-Security-Policy", "")
        connect_src = next(
            (directive for directive in csp.split(";") if directive.strip().startswith("connect-src")),
            "",
        )
        self.assertIn(GATEWAY, connect_src,
                      "connect-src must name the media origin, got: %r" % csp)

        # 2. The production bundle must have been built against that same origin. The manifest
        #    resolution lives in a lazily loaded chunk, so the built assets are listed and every
        #    script is inspected instead of only the entry the page loads eagerly.
        built = self._built_scripts()
        self.assertTrue(built, "the image must serve its built JavaScript assets")
        embedded = [
            script for script in built
            if GATEWAY in requests.get(WEB + script, timeout=30).text
        ]
        self.assertTrue(embedded,
                        "no built bundle embeds the configured media origin %s (checked %d assets)"
                        % (GATEWAY, len(built)))

        # 3. Drive the real player route and let the app fetch its own grant.
        self.browser.navigate(WEB + "/watch/" + self.movie_id)
        wait_until(self.browser, "document.querySelector('video') !== null", 60,
                   "the player to render")
        state = wait_for_playback(self.browser, 90)
        self.assertGreaterEqual(state.get("readyState", 0), 3,
                                "the production player must have decoded the protected HLS: %s" % state)
        self.assertGreater(state.get("currentTime", 0), 0.2,
                           "playback must have advanced: %s" % state)
        self.assertGreater(state.get("videoWidth", 0), 0, "a decoded frame must exist: %s" % state)

        # 4. The browser sent the media bearer header to the gateway for the manifest and at least
        #    one segment. The gateway answers 200 only for a verified token bound to that prefix.
        #    Preflights are excluded on purpose: a CORS preflight never carries the token.
        started = [entry for entry in self._gateway_requests()
                   if entry["path"].startswith("/hls/%s/%s/%s/" % (
                       self.movie_id, self.version_id, self.attempt_id))
                   and entry["method"] in ("GET", "HEAD")]
        preflights = [entry for entry in self._gateway_requests()
                      if entry["path"].startswith("/hls/%s/%s/%s/" % (
                          self.movie_id, self.version_id, self.attempt_id))
                      and entry["method"] == "OPTIONS"]
        manifest_requests = [entry for entry in started if entry["path"].endswith("index.m3u8")]
        segment_requests = [entry for entry in started if entry["path"].endswith(".ts")]
        self.assertTrue(manifest_requests, "the player must request the manifest from the gateway")
        self.assertTrue(all(entry["authorized"] for entry in manifest_requests),
                        "every manifest request must carry the media bearer header: %s" % manifest_requests)
        self.assertTrue(all(entry["status"] == 200 for entry in manifest_requests),
                        "the gateway must authorize the manifest: %s" % manifest_requests)
        self.assertTrue(segment_requests, "the player must request at least one segment")
        self.assertTrue(all(entry["authorized"] for entry in segment_requests),
                        "every segment request must carry the media bearer header: %s" % segment_requests)
        self.assertGreaterEqual(len([entry for entry in segment_requests if entry["status"] == 200]), 1,
                                "at least one segment must be authorized: %s" % segment_requests)
        self.assertTrue(all(entry["origin"] == WEB for entry in manifest_requests),
                        "the media request origin must be the web app: %s" % manifest_requests)
        self.assertTrue(preflights,
                        "the cross-origin media request must have raised a CORS preflight")
        self.assertTrue(all(entry["status"] == 204 for entry in preflights),
                        "the preflight must be answered from the origin allowlist: %s" % preflights)
        self.assertTrue(all(not entry["authorized"] for entry in preflights),
                        "a preflight must never carry the media bearer header: %s" % preflights)

        # 5. The media token must never be offered to the API origin, and the gateway must refuse the
        #    same canonical manifest without a token.
        self.assertIsNone(
            self.browser.evaluate(
                "window.sessionStorage.getItem('mediaToken')"
                " ?? window.localStorage.getItem('mediaToken')"
                " ?? window.localStorage.getItem('accessToken')"),
            "no token may be persisted in browser storage",
        )
        unauthorized = requests.get(
            GATEWAY + "/hls/%s/%s/%s/index.m3u8" % (self.movie_id, self.version_id, self.attempt_id),
            timeout=20,
        )
        self.assertEqual(unauthorized.status_code, 401,
                         "an unauthenticated canonical request must be refused")
        refused = [entry for entry in self._gateway_requests()
                   if entry["path"].endswith("index.m3u8") and not entry["authorized"]
                   and entry["method"] in ("GET", "HEAD")]
        self.assertTrue(refused, "the unauthenticated probe must be recorded by the gateway")
        self.assertTrue(all(entry["status"] == 401 for entry in refused),
                        "an unauthenticated manifest request must never be served: %s" % refused)


def wait_until(browser, expression, timeout, description):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if browser.evaluate(expression) is True:
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for %s" % description)


def wait_for_playback(browser, timeout):
    """Poll the player element until it has buffered and advanced, or the page reports an error."""
    deadline = time.time() + timeout
    state = {}
    while time.time() < deadline:
        raw = browser.evaluate("""
        JSON.stringify((() => {
          const video = document.querySelector('video');
          if (!video) return { missing: true };
          const message = document.querySelector('.feedback, .error-state, [role="alert"]');
          return {
            readyState: video.readyState,
            currentTime: video.currentTime,
            videoWidth: video.videoWidth,
            paused: video.paused,
            error: video.error ? video.error.code : null,
            text: message ? message.textContent : null,
          };
        })())
        """)
        if raw:
            state = json.loads(raw)
        if state.get("readyState", 0) >= 3 and state.get("currentTime", 0) > 0.2:
            return state
        if state.get("error") or state.get("text"):
            return state
        time.sleep(1)
    return state


def public_jwk(pem_text):
    """Convert the backend's media public PEM into the RSA JWK shape the gateway expects."""
    from cryptography.hazmat.primitives import serialization

    key = serialization.load_pem_public_key(pem_text.encode("ascii"))
    numbers = key.public_numbers()
    modulus = numbers.n.to_bytes((numbers.n.bit_length() + 7) // 8, "big")
    exponent = numbers.e.to_bytes((numbers.e.bit_length() + 7) // 8, "big")
    return {
        "kty": "RSA",
        "kid": MEDIA_KID,
        "alg": "RS256",
        "use": "sig",
        "n": base64.urlsafe_b64encode(modulus).rstrip(b"=").decode("ascii"),
        "e": base64.urlsafe_b64encode(exponent).rstrip(b"=").decode("ascii"),
    }


if __name__ == "__main__":
    unittest.main(verbosity=2)
