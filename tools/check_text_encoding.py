"""Reject invalid UTF-8 and common double-decoding damage in tracked text files."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {
    ".kt", ".kts", ".xml", ".md", ".py", ".yml", ".yaml", ".txt",
    ".json", ".properties", ".toml", ".html", ".css", ".js", ".sh", ".bat",
}
# UTF-8 punctuation/Latin letters accidentally read as Windows-1252 or Latin-1.
# Escapes keep this checker from matching its own source.
DAMAGE = re.compile(
    r"\ufffd|\u00e2(?:\u20ac|\u0080)[\u0080-\u00bf\u2018-\u203a]"
    r"|[\u00c2\u00c3][\u0080-\u00bf]"
)


def check() -> int:
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT
    ).decode("utf-8").split("\0")
    failures = []
    checked = 0
    for name in sorted(set(paths)):
        path = ROOT / name
        if not name or path.suffix.lower() not in EXTENSIONS or not path.is_file():
            continue
        checked += 1
        try:
            text = path.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError:
            failures.append(f"{name}: invalid UTF-8")
            continue
        for line, value in enumerate(text.splitlines(), 1):
            if DAMAGE.search(value):
                # Do not echo source text: a bad line could contain a credential.
                failures.append(f"{name}:{line}: corrupted text")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Text encoding OK ({checked} files).")
    return 0


if __name__ == "__main__":
    sys.exit(check())
