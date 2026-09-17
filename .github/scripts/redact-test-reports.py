"""Shared redaction rules for CI text reports; never runs tests."""
from pathlib import Path
import re
import sys


def redact_file(path, repository_root):
    try:
        raw_content = path.read_bytes()
    except OSError:
        raise SystemExit(1)
    if b"\x00" in raw_content:
        raise SystemExit(2)
    content = raw_content.decode("utf-8", errors="replace")

    sensitive_key = r"(?:authorization|token|password|secret|api[_-]?key|access[_-]?token|client[_-]?secret|private[_-]?key)"
    patterns = (
        (re.compile(r"(?i)(authorization\s*[:=]\s*)[^\r\n]+"), r"\1<redacted>"),
        (re.compile(r"(?i)\b((?:bearer|basic)\s+)[^\s,;\"']+"), r"\1<redacted>"),
        (
            re.compile(rf'''(?i)(["']?{sensitive_key}["']?\s*[:=]\s*)"(?:\\.|[^"\\\r\n])*"'''),
            r'\1"<redacted>"',
        ),
        (
            re.compile(rf"""(?i)([\"']?{sensitive_key}[\"']?\s*[:=]\s*)'[^'\r\n]*'"""),
            r"\1'<redacted>'",
        ),
        (
            re.compile(rf"(?i)([\"']?{sensitive_key}[\"']?\s*[=:]\s*)[^\"'\s,;]+"),
            r"\1<redacted>",
        ),
        (re.compile(r"https?://[^\s<>\"']+"), "<redacted-url>"),
        (re.compile(re.escape(repository_root)), "<workspace>"),
        (re.compile(r"/home/runner/work/[^\s]+"), "<runner-workspace>"),
    )
    for pattern, replacement in patterns:
        content = pattern.sub(replacement, content)
    try:
        path.write_text(content, encoding="utf-8")
    except OSError:
        raise SystemExit(3)


if __name__ == "__main__":
    if sys.argv[1] == "--file":
        redact_file(Path(sys.argv[2]), sys.argv[3])
    else:
        root = Path(sys.argv[1])
        for pattern in sys.argv[2:]:
            for path in root.glob(pattern):
                if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                    raise SystemExit("Report path escapes the workspace.")
                if path.is_file():
                    redact_file(path, str(root))
