"""Repository-level guards for the local-only live smoke gate."""

import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
TOOL_ROOT = pathlib.Path(__file__).resolve().parents[1]
DOCS = REPO_ROOT / "docs" / "local-live-smoke.md"


class LocalOnlyGuardTests(unittest.TestCase):
    def test_no_ci_workflow_references_the_live_smoke_gate(self):
        workflows = REPO_ROOT / ".github" / "workflows"
        offenders = []
        for path in sorted(workflows.glob("*.yml")) + sorted(workflows.glob("*.yaml")):
            text = path.read_text(encoding="utf-8", errors="replace")
            if "live-smoke" in text or "live_smoke" in text:
                offenders.append(path.name)
        self.assertEqual(offenders, [], "the live smoke gate must stay outside CI workflows")

    def test_tool_drives_maestro_only_through_mcp(self):
        orchestrate = (TOOL_ROOT / "livesmoke" / "orchestrate.py").read_text(encoding="utf-8")
        self.assertIn('"mcp", "--no-viewer"', orchestrate)
        pattern = re.compile(r'["\']maestro["\'][^\n]*["\']test["\']')
        for path in sorted(TOOL_ROOT.rglob("*.py")):
            if "tests" in path.parts:
                continue
            self.assertIsNone(pattern.search(path.read_text(encoding="utf-8")),
                              "maestro test invocation in %s" % path)

    def test_sources_contain_no_credential_values(self):
        suspicious = re.compile(r"OPENCODE_GO_API_KEY\s*=\s*[\"'][^\"']+[\"']")
        for path in sorted(TOOL_ROOT.rglob("*.py")):
            if "tests" in path.parts:
                continue
            text = path.read_text(encoding="utf-8")
            self.assertIsNone(suspicious.search(text), "credential literal in %s" % path)

    def test_public_docs_avoid_private_identity(self):
        for path in (DOCS, TOOL_ROOT / "README.md"):
            self.assertTrue(path.is_file(), "missing %s" % path)
            text = path.read_text(encoding="utf-8")
            for forbidden in ("/home/", "stella", "local-server", "Gvetri", "ssh://"):
                self.assertNotIn(forbidden, text, "private detail %r in %s" % (forbidden, path.name))


if __name__ == "__main__":
    unittest.main()
