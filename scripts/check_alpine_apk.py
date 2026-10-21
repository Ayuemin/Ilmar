"""Reject an APK missing executable ARM64 runtime inputs or corresponding licence notices."""
import json
from pathlib import Path
import struct
import sys
import zipfile

path = Path(sys.argv[1])
required = ["libproot_exec.so", "libproot_loader.so", "libtalloc.so", "libilmar_process.so"]
with zipfile.ZipFile(path) as archive:
    for name in required:
        data = archive.read("lib/arm64-v8a/" + name)
        assert data[:4] == b"\x7fELF" and data[4] == 2 and struct.unpack_from("<H", data, 18)[0] == 183, name
        # Validate every PT_LOAD alignment; NDK r28 targets 16 KiB Android pages.
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        for i in range(count):
            p = offset + i * size
            if struct.unpack_from("<I", data, p)[0] == 1:
                alignment = struct.unpack_from("<Q", data, p + 48)[0]
                assert alignment >= 16384, (name, alignment)
    for name in ["PROot-COPYING.txt", "GPL-3.txt", "LGPL-3.txt", "NOTICE-Alpine.txt"]:
        assert len(archive.read("assets/licenses/" + name)) > 1000
print(json.dumps({"alpine_apk": str(path), "abi": "arm64-v8a", "binaries": required, "elf_load_alignment": ">=16384", "licenses": "present"}))
