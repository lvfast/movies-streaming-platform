import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import run_acceptance as runner


class AcceptanceRunnerTest(unittest.TestCase):
    def test_interrupted_container_is_removed_by_its_exact_owned_name(self):
        with patch.object(runner, "run", side_effect=TimeoutError("interrupted")), \
                patch.object(runner.subprocess, "run") as cleanup:
            cleanup.return_value.returncode = 0
            with self.assertRaises(TimeoutError):
                runner.run_container([], "local-acceptance-1234567890-api", {})
            self.assertEqual(["docker", "--context", "default", "rm", "-f", "local-acceptance-1234567890-api"],
                             cleanup.call_args.args[0])

    def test_subprocess_output_is_decoded_as_utf8(self):
        self.assertEqual('✓', runner.run([sys.executable, '-c', "import sys; sys.stdout.buffer.write(bytes([226,156,147]))"]))

    def test_default_dry_run_does_not_call_docker_or_create_reports(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, "run") as run:
            output = Path(directory) / "absent"
            runner.execute(apply=False, quick=False, output=output)
            run.assert_not_called()
            self.assertFalse(output.exists())

    def test_model_removes_host_access_and_uses_only_owned_resources(self):
        model = {"name": "existing", "services": {
            "backend": {"image": "old", "build": {}, "environment": {}},
            "frontend": {"image": "old", "ports": [{"published": "8080"}]},
            "postgres": {"image": "postgres:17-alpine"},
        }, "networks": {"application": {"name": "existing"}, "edge": {}},
            "volumes": {"postgres-data": {"name": "existing"}}}
        result = runner.isolate(model, "local-acceptance-1234567890")
        self.assertEqual({"postgres-data": {}}, result["volumes"])
        self.assertTrue(all(n == {"internal": True} for n in result["networks"].values()))
        self.assertNotIn("ports", result["services"]["frontend"])
        self.assertNotIn("build", result["services"]["backend"])
        self.assertEqual("media-streaming-backend:acceptance", result["services"]["backend"]["image"])
        self.assertEqual("media-streaming-frontend:acceptance", result["services"]["frontend"]["image"])
        self.assertIn("@sha256:", result["services"]["postgres"]["image"])

    def test_rejects_unsafe_project_before_cleanup_can_be_constructed(self):
        for name in ("lvfast", "media-streaming-platform", "local-acceptance-../", "local-acceptance-"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runner.check_project(name)

    def test_default_output_is_under_acceptance_artifacts(self):
        with patch.object(runner, "run") as run:
            runner.execute(apply=False, quick=False)
            run.assert_not_called()
        self.assertEqual(runner.DEFAULT_OUTPUT, runner.ROOT / "artifacts/acceptance")

    def test_acceptance_public_constants(self):
        self.assertEqual("local-acceptance-", runner.PROJECT_PREFIX)
        self.assertEqual("acceptance", runner.IMAGE_TAG)

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


if __name__ == "__main__":
    unittest.main()
