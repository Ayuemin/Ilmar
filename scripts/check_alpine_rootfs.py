"""Check the official ARM64 Alpine release and expose the archive to JVM extraction tests."""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

VERSION = "3.24.2"
url = f"https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-{VERSION}-aarch64.tar.gz"
expected = urllib.request.urlopen(url + ".sha256", timeout=60).read().decode().split()[0]
data = urllib.request.urlopen(url, timeout=90).read(16 * 1024 * 1024 + 1)
assert len(data) <= 16 * 1024 * 1024
actual = hashlib.sha256(data).hexdigest()
assert actual == expected == "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
path = Path("/tmp/ilmar-alpine-rootfs.tar.gz")
path.write_bytes(data)
with tarfile.open(path) as archive:
    types = {}
    for member in archive:
        key = member.type.decode("ascii", errors="replace")
        types[key] = types.get(key, 0) + 1
print(json.dumps({"alpine_rootfs": {"version": VERSION, "url": url, "sha256": actual, "bytes": len(data), "tar_types": types}}))
