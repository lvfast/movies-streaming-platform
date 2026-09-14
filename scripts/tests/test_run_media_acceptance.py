import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import run_media_acceptance as runner


def playwright_report(*, ok=True, steps=None):
    result = {
        "status": "passed" if ok else "failed",
        "duration": 1234,
        "steps": steps if steps is not None else [
            {"title": "step 1: register account and grant ADMIN", "status": "passed", "duration": 100},
        ],
    }
    if not ok:
        result["error"] = {"message": "expect(received).toBe(expected)"}
    return {
        "config": {"version": "1.55.0"},
        "suites": [{
            "title": "admin-media-journey.spec.ts",
            "specs": [{
                "title": "complete admin and viewer journey",
                "ok": ok,
                "tests": [{"results": [result]}],
            }],
            "suites": [{
                "title": "nested",
                "specs": [{
                    "title": "viewer plays protected HLS",
                    "ok": True,
                    "tests": [{"results": [{"status": "passed", "duration": 50, "steps": []}]}],
                }],
            }],
        }],
    }


class MediaAcceptanceRunnerTest(unittest.TestCase):
    def test_default_dry_run_does_not_call_docker_or_create_reports(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, "run") as run:
            output = Path(directory) / "absent"
            self.assertEqual(0, runner.execute(apply=False, grep=None, output=output))
            run.assert_not_called()
            self.assertFalse(output.exists())

    def test_default_output_is_under_media_acceptance_artifacts(self):
        self.assertEqual(runner.DEFAULT_OUTPUT, runner.ROOT / "artifacts/media-acceptance")

    def test_public_ownership_constants(self):
        self.assertEqual("media-acceptance-", runner.PROJECT_PREFIX)
        self.assertEqual(
            ("compose.yml", "compose.local.yml", "compose.media.local.yml"),
            runner.COMPOSE_FILES,
        )

    def test_rejects_unsafe_project_before_cleanup_can_be_constructed(self):
        for name in (
            "media-acceptance",
            "media-acceptance-",
            "media-acceptance-../",
            "media-acceptance-ABCDEF0123",
            "media-acceptance-123456789",
            "lvfast",
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runner.check_project(name)

    def test_project_namespace_accepts_only_owned_hex_names(self):
        runner.check_project("media-acceptance-0123456789")

    def test_disposable_environment_presigns_browser_storage_at_the_loopback_endpoint(self):
        env = runner.disposable_environment(
            "http://127.0.0.1:18081", "http://127.0.0.1:18190",
            "http://127.0.0.1:18191", "media-acceptance-0123456789")
        self.assertEqual("http://127.0.0.1:18190", env["S3_BROWSER_ENDPOINT"])
        self.assertEqual("http://127.0.0.1:18190", env["MEDIA_STORAGE_ORIGIN"])
        self.assertEqual("http://127.0.0.1:18191", env["MEDIA_ORIGIN"])
        self.assertEqual("http://127.0.0.1:18191", env["VITE_MEDIA_BASE_URL"])
        self.assertEqual("media-acceptance-0123456789", env["MEDIA_SIGNING_KEY_ID"])

    def test_minio_cors_environment_allows_uploads_only_from_the_web_origin(self):
        env = runner.minio_cors_environment("http://127.0.0.1:18081")
        self.assertEqual("http://127.0.0.1:18081", env["MINIO_API_CORS_ALLOW_ORIGIN"])
        self.assertIn("PUT", env["MINIO_API_CORS_ALLOW_METHODS"].split(","))
        self.assertIn("OPTIONS", env["MINIO_API_CORS_ALLOW_METHODS"].split(","))
        self.assertNotIn("*", env["MINIO_API_CORS_ALLOW_ORIGIN"])

    def test_create_buckets_publishes_anonymous_read_only_for_the_delivery_bucket(self):
        calls = {}

        class FakeClient:
            exceptions = SimpleNamespace(BucketAlreadyOwnedByYou=Exception)

            def create_bucket(self, Bucket): pass

            def put_bucket_policy(self, Bucket, Policy):
                calls["policy"] = (Bucket, Policy)

        runner.create_buckets(
            "http://127.0.0.1:18190", "media-source", "media-delivery", client=FakeClient())
        bucket, policy = calls["policy"]
        self.assertEqual("media-delivery", bucket)
        self.assertEqual("s3:GetObject", json.loads(policy)["Statement"][0]["Action"][0])

    def test_model_removes_host_access_and_uses_only_owned_resources(self):
        model = {
            "name": "existing",
            "services": {
                "backend": {"image": "old", "build": {}, "ports": [{"published": "8080"}]},
                "transcoder": {"image": "old", "build": {}},
                "frontend": {"image": "old", "ports": [{"published": "8080"}]},
                "minio": {"image": "minio:latest", "ports": [{"published": "9000"}]},
                "postgres": {"image": "postgres:17-alpine"},
            },
            "networks": {"application": {"name": "existing", "internal": True}, "edge": {"name": "existing"}},
            "volumes": {"postgres-data": {"name": "existing"}},
        }
        result = runner.isolate(
            model,
            "media-acceptance-0123456789",
            ports={"frontend": (8080, 18181), "minio": (9000, 18190)},
            environment={"backend": {"MEDIA_SIGNING_KEY_ID": "acceptance-kid"}},
        )
        self.assertEqual("media-acceptance-0123456789", result["name"])
        self.assertEqual({"postgres-data": {}}, result["volumes"])
        self.assertNotIn("name", result["networks"]["application"])
        self.assertTrue(result["networks"]["application"]["internal"])
        self.assertNotIn("build", result["services"]["backend"])
        self.assertEqual(
            "media-acceptance-backend:0123456789", result["services"]["backend"]["image"])
        self.assertEqual(
            "media-acceptance-transcoder:0123456789", result["services"]["transcoder"]["image"])
        self.assertEqual(
            "media-acceptance-frontend:0123456789", result["services"]["frontend"]["image"])
        self.assertNotIn("ports", result["services"]["backend"])
        self.assertNotIn("ports", result["services"]["postgres"])
        self.assertEqual("18181", result["services"]["frontend"]["ports"][0]["published"])
        self.assertEqual(8080, result["services"]["frontend"]["ports"][0]["target"])
        self.assertEqual("127.0.0.1", result["services"]["frontend"]["ports"][0]["host_ip"])
        self.assertEqual("18190", result["services"]["minio"]["ports"][0]["published"])
        self.assertEqual(
            "acceptance-kid",
            result["services"]["backend"]["environment"]["MEDIA_SIGNING_KEY_ID"],
        )

    def test_cleanup_runs_on_failure_and_has_no_global_prune(self):
        commands = []

        def dc(*args):
            commands.append(args)
            if args[0] == "up":
                raise RuntimeError("unhealthy")

        with self.assertRaisesRegex(RuntimeError, "unhealthy"):
            runner.run_stack(dc, lambda: None)
        self.assertEqual(("down", "--volumes", "--remove-orphans"), commands[-1])
        self.assertNotIn("prune", str(commands))

    def test_redacts_tokens_signed_urls_and_credentials_recursively(self):
        payload = {
            "authenticated": True,
            "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signaturepart",
            "login": {
                "headers": {"Authorization": "Bearer abcdef0123456789"},
                "password": "SuperSecret123!",
                "credentials": {"accessKey": "minioadmin", "secretKey": "minioadmin"},
            },
            "manifestUrl": "http://127.0.0.1:9000/media-delivery/hls/a.m3u8?X-Amz-Signature=deadbeef1234&X-Amz-Credential=abc",
            "items": [
                {"url": "https://s3.example/bucket/key?sig=AAaaBBbbCCcc"},
                "2026-09-13T00:00:00Z",
            ],
            "movieId": "0e7c2f24-1111-2222-3333-444455556666",
        }
        cleaned = runner.redact(payload)
        serialized = json.dumps(cleaned)
        self.assertNotIn("eyJ", serialized)
        self.assertNotIn("Bearer abcdef", serialized)
        self.assertNotIn("SuperSecret123!", serialized)
        self.assertNotIn("minioadmin", serialized)
        self.assertNotIn("X-Amz-Signature=", serialized)
        self.assertNotIn("sig=AAaaBBbb", serialized)
        self.assertNotIn("://user:pass@", serialized)
        self.assertTrue(cleaned["authenticated"])
        self.assertEqual("0e7c2f24-1111-2222-3333-444455556666", cleaned["movieId"])
        runner.assert_redacted(cleaned)

    def test_assert_redacted_rejects_a_leaked_secret(self):
        with self.assertRaises(ValueError):
            runner.assert_redacted({"note": "Bearer abcdef0123456789"})
        with self.assertRaises(ValueError):
            runner.assert_redacted({"mediaToken": "raw-value"})

    def test_summarize_collects_journey_tests_steps_and_counts(self):
        summary = runner.summarize(playwright_report())
        self.assertEqual(2, summary["total"])
        self.assertEqual(2, summary["passed"])
        self.assertEqual(0, summary["failed"])
        titles = [item["title"] for item in summary["tests"]]
        self.assertEqual(["complete admin and viewer journey", "viewer plays protected HLS"], titles)
        first = summary["tests"][0]
        self.assertEqual("passed", first["status"])
        self.assertEqual(1234, first["durationMs"])
        self.assertEqual("step 1: register account and grant ADMIN", first["steps"][0]["title"])

    def test_summarize_reports_failure_and_error_without_secrets(self):
        summary = runner.summarize(playwright_report(ok=False))
        self.assertEqual(1, summary["failed"])
        self.assertEqual("failed", summary["tests"][0]["status"])

    def test_playwright_command_passes_the_config_and_optional_grep(self):
        command = runner.playwright_command("playwright", grep="step 7")
        self.assertEqual("test", command[1])
        self.assertIn(str(runner.ROOT / "frontend" / "playwright.config.ts"), command)
        self.assertEqual("step 7", command[command.index("--grep") + 1])
        self.assertNotIn("--grep", runner.playwright_command("playwright"))

    def test_provider_checks_are_not_run_with_a_reason_by_default(self):
        checks = runner.provider_checks()
        self.assertTrue(checks)
        for check in checks:
            self.assertEqual("NOT_RUN", check["status"])
            self.assertTrue(check["reason"])

    def test_browser_channel_override_wins_over_installed_browser_detection(self):
        with patch.dict("os.environ", {"MEDIA_ACCEPTANCE_BROWSER_CHANNEL": "chrome"}):
            self.assertEqual("chrome", runner.detect_browser_channel())


if __name__ == "__main__":
    unittest.main()
