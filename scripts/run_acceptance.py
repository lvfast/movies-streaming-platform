#!/usr/bin/env python3
"""Local HTTP E2E + k6 acceptance. Dry-run by default; --apply creates a disposable Docker project."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROJECT_PREFIX = "local-acceptance-"
IMAGE_TAG = "acceptance"
DEFAULT_OUTPUT = ROOT / "artifacts/acceptance"
K6_IMAGE = "grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d"
DEPENDENCY_IMAGES = {
    "postgres": "postgres:17-alpine@sha256:c7526c0f6c3f30260a563d7bcf8ad778effac59a44f8ffa86678c35418338609",
    "redis": "redis:7.4-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf",
    "jwt-keygen": "alpine/openssl:3.5.4@sha256:42c7389ef077aed0eb4e96d0abbd094083d701bbaff1313073b061c0c9cd8278",
}


def run(args, *, env=None, timeout=1800):
    result = subprocess.run(args, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")
    return result.stdout


def check_project(project):
    if not re.fullmatch(rf"{re.escape(PROJECT_PREFIX)}[0-9a-f]{{10}}", project):
        raise ValueError("Refusing a project outside the disposable acceptance namespace")


def run_container(args, name, env):
    if not re.fullmatch(rf"{re.escape(PROJECT_PREFIX)}[0-9a-f]{{10}}-(api|load)", name):
        raise ValueError("Refusing a container outside the disposable acceptance namespace")
    try:
        return run(["docker", "--context", "default", "run", "--rm", "--name", name, *args], env=env)
    finally:
        # A killed Docker CLI need not stop its container. Remove this exact owned
        # container even on timeout/interrupt, before Compose removes the networks.
        result = subprocess.run(["docker", "--context", "default", "rm", "-f", name], env=env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
        if result.returncode and "No such container" not in result.stderr:
            raise RuntimeError(f"Could not clean up {name}: {result.stderr}")


def isolate(model, project):
    check_project(project)
    model["name"] = project
    model["networks"] = {name: {"internal": True} for name in model["networks"]}
    model["volumes"] = {name: {} for name in model["volumes"]}
    for name, service in model["services"].items():
        service.pop("build", None)
        service.pop("ports", None)
        if name in {"backend", "frontend"}:
            service["image"] = f"media-streaming-{name}:{IMAGE_TAG}"
        elif name in DEPENDENCY_IMAGES:
            service["image"] = DEPENDENCY_IMAGES[name]
    return model


def run_stack(dc, tests):
    try:
        dc("up", "-d", "--wait", "--wait-timeout", "180")
        tests()
    finally:
        dc("down", "--volumes", "--remove-orphans")


def execute(apply=False, quick=False, output=DEFAULT_OUTPUT):
    if not apply:
        print("DRY-RUN: build current images -> fresh internal-only Compose project -> HTTP E2E -> "
              + ("2 VUs/15s (not acceptance)" if quick else "20 VUs/10m")
              + " -> remove only disposable project/volumes. No production connections.")
        return
    project = PROJECT_PREFIX + uuid.uuid4().hex[:10]
    check_project(project)
    output = output.resolve() / project
    output.mkdir(parents=True)
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP", "PROGRAMDATA", "PROGRAMFILES", "DOCKER_CONFIG"}}
    env.update(POSTGRES_PASSWORD="acceptance-local-only", PUBLIC_BASE_URL="http://frontend:8080",
               MEDIA_BASE_URL="http://frontend:8080/media", SECURE_COOKIE="false", REFRESH_COOKIE_NAME="refresh_token")
    for component in ("backend", "frontend"):
        print(f"Building {component} from current workspace", flush=True)
        run(["docker", "--context", "default", "build", "-f", str(ROOT / component / "Dockerfile"),
             "-t", f"media-streaming-{component}:{IMAGE_TAG}", str(ROOT)], env=env)
    raw = run(["docker", "--context", "default", "compose", "--env-file", str(ROOT / ".env.example"),
               "-f", str(ROOT / "compose.yml"), "-f", str(ROOT / "compose.local.yml"), "config", "--format", "json"], env=env)
    model = isolate(json.loads(raw), project)
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        config = Path(directory) / "compose.json"
        config.write_text(json.dumps(model), encoding="utf-8")
        def dc(*parts):
            return run(["docker", "--context", "default", "compose", "--project-name", project, "-f", str(config), *parts], env=env)
        def tests():
            count = dc("exec", "-T", "postgres", "psql", "-U", "media_streaming", "-d", "media_streaming",
                       "-Atc", "SELECT count(*) FROM movie;").strip()
            if count != "20":
                raise RuntimeError(f"Expected exactly 20 seeded movies, got {count!r}")
            print("Verified exactly 20 seeded movies", flush=True)
            for script in ("api", "load"):
                print(f"Running {script}: " + ("quick" if quick else "full acceptance"), flush=True)
                result = run_container(["--network", f"{project}_edge",
                              "--memory", "512m", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                              "--user", "0:0", "-e", f"PROFILE={'quick' if quick else 'acceptance'}",
                              "-v", f"{ROOT / 'tests/acceptance'}:/tests:ro", "-v", f"{output}:/reports",
                              K6_IMAGE, "run", "--quiet", "--address", "127.0.0.1:6565", f"/tests/{script}.js"], f"{project}-{script}", env)
                print(result, end="", flush=True)
        run_stack(dc, tests)
    print(f"PASS {'quick checks (not full load acceptance)' if quick else '20-user/10-minute local acceptance'}; reports: {output}", flush=True)
    print(f"Removed only {project} and its volumes", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="run locally using disposable Docker resources")
    parser.add_argument("--quick", action="store_true", help="2 VUs/15 seconds, not the full acceptance criterion")
    args = parser.parse_args()
    execute(apply=args.apply, quick=args.quick)
