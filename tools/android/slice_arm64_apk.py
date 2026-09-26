#!/usr/bin/env python3
"""Create an arm64-only APK from a universal APK before alignment and signing."""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    source = args.source.resolve()
    destination = args.destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(source, "r") as input_apk, zipfile.ZipFile(
        destination,
        "w",
        allowZip64=True,
    ) as output_apk:
        for info in input_apk.infolist():
            if info.filename.startswith("lib/") and not info.filename.startswith(
                "lib/arm64-v8a/"
            ):
                continue
            output_apk.writestr(info, input_apk.read(info.filename))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
