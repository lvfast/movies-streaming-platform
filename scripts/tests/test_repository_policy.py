import re
import subprocess
import unittest
from pathlib import Path
from urllib.parse import unquote


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


def markdown_target(raw_target):
    target = raw_target.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    else:
        target = target.split(maxsplit=1)[0]
    return unquote(target.split("#", maxsplit=1)[0])


class RepositoryPolicyTest(unittest.TestCase):
    def test_tracked_files_do_not_reference_inherited_repository_assets(self):
        violations = []
        for path in tracked_files():
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for banned in BANNED:
                if banned in text:
                    violations.append(f"{path.relative_to(ROOT)}: {banned}")

        self.assertEqual([], violations, "Banned repository coupling:\n" + "\n".join(violations))

    def test_public_markdown_relative_links_exist(self):
        broken_links = []
        for relative_path in MARKDOWN_FILES:
            path = ROOT / relative_path
            for match in MARKDOWN_LINK.finditer(path.read_text(encoding="utf-8")):
                raw_target = match.group(1)
                if raw_target.startswith("#") or raw_target.lower().startswith(
                    ("http:", "https:", "mailto:")
                ):
                    continue
                target = markdown_target(raw_target)
                resolved = ROOT / target.lstrip("/") if target.startswith("/") else path.parent / target
                if not resolved.exists():
                    broken_links.append(f"{relative_path}: {raw_target}")

        self.assertEqual([], broken_links, "Broken relative Markdown links:\n" + "\n".join(broken_links))


if __name__ == "__main__":
    unittest.main()
