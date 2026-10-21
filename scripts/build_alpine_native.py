"""Build ARM64 Android PRoot from pinned source, never from downloaded executables.

Reference: Agora build-proot.sh at 3e7b0c8c89d591c3cd58a4bc6824861223576c1d.
Source archives and local patches are published alongside APK artifacts.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / ".build-alpine"
PROOT = "a7147b50d708567dbf884d21e0f0c8698bba0d10"
AGORA = "3e7b0c8c89d591c3cd58a4bc6824861223576c1d"
TALLOC = {"config.h": "ab08660987ab6164eb19ba21cb90111cd13695d0", "replace.h": "2acd79d3798c7960a3f8039a819dca0e3b48bc53",
          "talloc.c": "3a93a82e456c5616ffea6c9d9dc20facd1072152", "talloc.h": "eef1a701b1f3b473fe537cf3f2e6bbca04b424e9"}


def run(*args, cwd=BUILD, env=None):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def main():
    BUILD.mkdir(exist_ok=True)
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", str(Path(os.environ["ANDROID_HOME"]) / "ndk/28.2.13676358")))
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    cc = str(tools / "aarch64-linux-android26-clang")
    source = BUILD / "proot"
    if not (source / ".git").exists():
        run("git", "clone", "--no-checkout", "https://github.com/termux/proot.git", str(source))
    run("git", "checkout", "--detach", PROOT, cwd=source)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    assert actual == PROOT
    talloc = BUILD / "talloc"
    talloc.mkdir(exist_ok=True)
    for name, expected in TALLOC.items():
        content = urllib.request.urlopen(f"https://raw.githubusercontent.com/newo-ether/Agora/{AGORA}/thirdparty/talloc/{name}", timeout=60).read()
        assert hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest() == expected, name
        (talloc / name).write_bytes(content)
    # Keep the original and patched sources, including upstream licences, available to recipients.
    shutil.copy2(ROOT / "app/src/main/assets/licenses/LGPL-3.txt", talloc / "COPYING.LESSER")
    out = BUILD / "src"
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(source / "src", out)
    for feature in ("process_vm", "seccomp_filter"):
        (out / f".check_{feature}.c").write_text("int main(void) { return 0; }\n")
    ashmem = out / "extension/ashmem_memfd/ashmem_memfd.c"
    ashmem.write_text("#include <string.h>\n" + ashmem.read_text())
    # NDK cross-build feature probes check compilation, not execution on the host.
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"], CC=cc,
               CPPFLAGS=f"-I{talloc} -DSYS_SECCOMP=1", LDFLAGS=f"-L{BUILD}", SOURCE_DATE_EPOCH="0")
    run(cc, "-fPIC", "-O2", "-shared", "-Wl,-soname,libtalloc.so", "-o", str(BUILD / "libtalloc.so"),
        "talloc.c", "-DHAVE_CONFIG_H", "-I.", cwd=talloc)
    # Use a portable symbol parser instead of the upstream gawk-specific loader-info.awk.
    (out / "loader/loader-info.awk").write_text('''function hex(s, i,n) { n=0; s=tolower(s); for(i=1;i<=length(s);i++) n=n*16+index("0123456789abcdef",substr(s,i,1))-1; return n }
$NF=="pokedata_workaround" { p=hex($2) }
$NF=="_start" { s=hex($2) }
END { print "#include <unistd.h>"; print "const ssize_t offset_to_pokedata_workaround=" p-s ";" }
''')
    run("make", "-j2", "CROSS_COMPILE=aarch64-linux-android26-", "PROOT_UNBUNDLE_LOADER=loader-out", "GIT=/bin/true", "proot", cwd=out, env=env)
    destination = ROOT / "app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    for src, name in [(out / "proot", "libproot_exec.so"), (out / "loader/loader", "libproot_loader.so"), (BUILD / "libtalloc.so", "libtalloc.so")]:
        run(str(tools / "llvm-strip"), "--strip-all", str(src), "-o", str(destination / name))
    run(cc, "-O2", "-fPIE", "-pie", str(ROOT / "app/src/main/cpp/process_group.c"), "-o", str(destination / "libilmar_process.so"))
    with tarfile.open(BUILD / "native-corresponding-source.tar.gz", "w:gz") as archive:
        archive.add(source, "proot", filter=lambda info: None if "/.git" in info.name else info)
        archive.add(out, "proot-patched-src")
        archive.add(talloc, "talloc")
        archive.add(ROOT / "scripts/build_alpine_native.py", "build_alpine_native.py")
        archive.add(ROOT / "app/src/main/cpp/process_group.c", "process_group.c")
    print("native_build", {"proot": PROOT, "talloc_source": AGORA, "abi": "arm64-v8a", "ndk": str(ndk)})


if __name__ == "__main__":
    main()
