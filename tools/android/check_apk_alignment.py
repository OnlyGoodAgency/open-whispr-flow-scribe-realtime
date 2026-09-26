"""Check 64-bit native ELF LOAD segments and uncompressed APK entry alignment.

Run against the final signed APK, including any APK repackaged for sharing:
    python tools/android/check_apk_alignment.py path/to/app.apk

This is a packaging check, not a replacement for runtime testing on a 16 KB device.
See https://developer.android.com/guide/practices/page-sizes
"""

import argparse
from pathlib import Path
import struct
import zipfile

PAGE_SIZE = 16384
ABIS = {"arm64-v8a", "x86_64"}


def check_apk(path: Path) -> tuple[int, list[str]]:
    errors = []
    count = 0
    with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
        for entry in apk.infolist():
            parts = entry.filename.split("/")
            if (len(parts) != 3 or parts[0] != "lib" or parts[1] not in ABIS
                    or not parts[2].endswith(".so")):
                continue
            count += 1
            data = apk.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                errors.append(f"{entry.filename}: expected little-endian ELF64")
                continue
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phcount = struct.unpack_from("<HH", data, 54)
            if phsize < 56 or not phcount or phoff + phsize * phcount > len(data):
                errors.append(f"{entry.filename}: invalid ELF program headers")
                continue
            loads = 0
            for index in range(phcount):
                kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
                    "<IIQQQQQQ", data, phoff + index * phsize,
                )
                if kind != 1:  # PT_LOAD
                    continue
                loads += 1
                if alignment < PAGE_SIZE or (address - offset) % PAGE_SIZE:
                    errors.append(
                        f"{entry.filename}: LOAD segment {index} is not 16 KB aligned "
                        f"(alignment={alignment}); rebuild the library",
                    )
            if not loads:
                errors.append(f"{entry.filename}: no ELF LOAD segments")

            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                name_length, extra_length = struct.unpack_from("<HH", header, 26)
                offset = entry.header_offset + 30 + name_length + extra_length
                if offset % PAGE_SIZE:
                    errors.append(
                        f"{entry.filename}: APK data offset {offset} is not 16 KB aligned; "
                        "use zipalign -P 16 before signing",
                    )
    if not count:
        errors.append("No 64-bit native libraries found in APK")
    return count, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        count, errors = check_apk(args.apk)
    except (OSError, zipfile.BadZipFile, struct.error) as error:
        print(f"FAIL: {error}")
        return 1
    for error in errors:
        print(f"FAIL: {error}")
    if errors:
        return 1
    print(f"PASS: {count} native libraries have 16 KB ELF LOAD and APK ZIP alignment")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
