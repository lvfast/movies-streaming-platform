import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from urllib.parse import unquote
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
BANNED = (
    "github.com/lvfast/media-streaming-platform",
    "lvfast.site",
    "compose.prod.yml",
    "infra/cloudflare",
    "infra/prod-host-ops",
    "task8-acceptance",
)
MARKDOWN_FILES = (
    Path("README.md"),
    Path("docs/architecture.md"),
    Path("docs/runbooks/local-acceptance.md"),
)
POLICY_TEST = Path("scripts/tests/test_repository_policy.py")
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")


def tracked_files():
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    for name in result.stdout.decode().split("\0"):
        if name and not name.startswith("docs/superpowers/"):
            path = Path(name)
            if path != POLICY_TEST:
                yield ROOT / path


def banned_token_violations(paths):
    violations = []
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        folded = text.casefold()
        for banned in BANNED:
            if banned.casefold() in folded:
                violations.append(f"{path.relative_to(ROOT)}: {banned}")
    return violations


def markdown_target(raw_target):
    target = raw_target.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    else:
        target = target.split(maxsplit=1)[0]
    return unquote(target.split("#", maxsplit=1)[0])


def broken_markdown_links():
    broken_links = []
    for relative_path in MARKDOWN_FILES:
        path = ROOT / relative_path
        for match in MARKDOWN_LINK.finditer(path.read_text(encoding="utf-8")):
            raw_target = match.group(1)
            target = markdown_target(raw_target)
            if not target or target.lower().startswith(("http:", "https:", "mailto:")):
                continue
            resolved = ROOT / target.lstrip("/") if target.startswith("/") else path.parent / target
            if not resolved.exists():
                broken_links.append(f"{relative_path}: {raw_target}")
    return broken_links


class RepositoryPolicyTest(unittest.TestCase):
    def test_tracked_files_do_not_reference_inherited_repository_assets(self):
        violations = banned_token_violations(tracked_files())
        self.assertEqual([], violations, "Banned repository coupling:\n" + "\n".join(violations))

    def test_banned_token_scan_rejects_mixed_case_occurrences(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            path = Path(directory) / "mixed-case.txt"
            path.write_text("HTTPS://GITHUB.COM/LvFaSt/MeDiA-StReAmInG-PlAtFoRm", encoding="utf-8")

            self.assertEqual(
                [f"{path.relative_to(ROOT)}: github.com/lvfast/media-streaming-platform"],
                banned_token_violations((path,)),
            )

    def test_nginx_proxy_replaces_untrusted_client_address_headers(self):
        nginx = (ROOT / "frontend/nginx.conf.template").read_text(encoding="utf-8")
        api_location = nginx.split("location /api/ {", maxsplit=1)[1].split("}", maxsplit=1)[0]

        self.assertIn("proxy_set_header X-Forwarded-For $remote_addr;", api_location)
        self.assertIn('proxy_set_header Forwarded "";', api_location)
        self.assertIn('proxy_set_header X-Real-IP "";', api_location)
        self.assertNotIn("$proxy_add_x_forwarded_for", api_location)

    def test_nginx_csp_names_the_declared_storage_origin_for_browser_uploads(self):
        nginx = (ROOT / "frontend/nginx.conf.template").read_text(encoding="utf-8")
        self.assertIn('set $storage_origin "${MEDIA_STORAGE_ORIGIN}";', nginx)
        self.assertIn("connect-src 'self' $media_origin $storage_origin;", nginx)

    def test_public_markdown_relative_links_exist(self):
        broken_links = broken_markdown_links()
        self.assertEqual([], broken_links, "Broken relative Markdown links:\n" + "\n".join(broken_links))

    def test_external_markdown_targets_are_classified_after_normalization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readme = root / "README.md"
            readme.write_text(
                "[angle](<https://example.com/a b>)\n"
                "[whitespace](  https://example.com/path)\n"
                "[mail](<mailto:dev@example.com>)\n",
                encoding="utf-8",
            )
            with patch(f"{__name__}.ROOT", root), patch(
                f"{__name__}.MARKDOWN_FILES", (Path("README.md"),)
            ):
                self.assertEqual([], broken_markdown_links())


if __name__ == "__main__":
    unittest.main()
