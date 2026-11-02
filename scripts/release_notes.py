#!/usr/bin/env python3
"""Print the exact version's changelog section, excluding Unreleased notes."""
from pathlib import Path
import re
import sys


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", sys.argv[1]):
        print("Usage: python scripts/release_notes.py <version>", file=sys.stderr)
        return 1
    version = sys.argv[1]
    changelog = (Path(__file__).resolve().parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")
    match = re.search(r"^## " + re.escape(version) + r"(?:[^\S\n]+[^\n]*)?\n(.*?)(?=^## |\Z)", changelog, re.M | re.S)
    if not match or not match.group(1).strip():
        print(f"Missing or empty changelog section for {version}", file=sys.stderr)
        return 1
    print(match.group(1).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
