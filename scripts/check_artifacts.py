#!/usr/bin/env python3
"""Verify executable manifests and the read-only CLI packaging boundary."""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def check_jar(name, main_class, review):
    path = ROOT / "target" / name
    if not path.is_file():
        raise ValueError(f"Missing {path}; run mvn package first")
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
        if f"Main-Class: {main_class}" not in manifest.splitlines():
            raise ValueError(f"Wrong entry point in {name}")
        classes = {n for n in names if n.endswith(".class")}
        if "dev/dupelens/Main.class" not in classes:
            raise ValueError(f"Scanner missing from {name}")
        if any(n.endswith("Test.class") for n in classes):
            raise ValueError(f"Test classes leaked into {name}")
        if review:
            if "dev/dupelens/Review.class" not in classes:
                raise ValueError("Review entry point missing")
        elif any(n != "dev/dupelens/Main.class" and not n.startswith("dev/dupelens/Main$") for n in classes):
            raise ValueError("CLI JAR includes classes outside the read-only Main boundary")
        print(f"Verified {name}: {main_class}, {len(classes)} application classes.")


def main():
    try:
        check_jar("dupelens.jar", "dev.dupelens.Main", False)
        check_jar("dupelens-review.jar", "dev.dupelens.Review", True)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
