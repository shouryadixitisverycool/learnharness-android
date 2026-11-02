#!/usr/bin/env python3
"""Bundle the existing production server and checksum-pinned Android runtime."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
CACHE = ROOT / "runtime/cache"
NATIVE = ROOT / "app/src/main/jniLibs/arm64-v8a"
ASSETS = ROOT / "app/src/main/assets"
BASE = "https://packages.termux.dev/apt/termux-main/"
RESVG_REV = "9ca058462ac529120c8cc84ddcd6fef644cc5406"
RESVG_SHA256 = "7ce8697451237577d473361aa688917a48cba00c4a8f3302a833455c9c2013fa"


def run(*args, **kwargs):
    return subprocess.check_output(args, **kwargs).decode().strip()


def download(url, file, algorithm, expected):
    if not file.exists():
        temporary = file.with_suffix(".part")
        urllib.request.urlretrieve(url, temporary)
        temporary.rename(file)
    if hashlib.new(algorithm, file.read_bytes()).digest() != expected:
        raise RuntimeError(f"Checksum mismatch: {file.name}")


def needed(file):
    return re.findall(r"\(NEEDED\).*\[(.*?)\]", run("readelf", "-d", str(file)))


def build_resvg():
    archive = CACHE / "resvg-js.tar.gz"
    download(
        f"https://codeload.github.com/yisibl/resvg-js/tar.gz/{RESVG_REV}",
        archive,
        "sha256",
        bytes.fromhex(RESVG_SHA256),
    )
    source = CACHE / f"resvg-js-source-{RESVG_REV[:12]}"
    if not (source / "Cargo.toml").is_file():
        source.mkdir(exist_ok=True)
        subprocess.run(["bsdtar", "-xf", str(archive), "-C", str(source), "--strip-components", "1"], check=True)
    shutil.copyfile(ROOT / "runtime/resvg-Cargo.lock", source / "Cargo.lock")

    if not os.environ.get("ANDROID_HOME"):
        raise RuntimeError("Set ANDROID_HOME to the Android SDK directory")
    sdk = Path(os.environ["ANDROID_HOME"])
    ndk = next((Path(value) for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT") if (value := os.environ.get(key))), None)
    if ndk is None:
        installed = sorted((sdk / "ndk").glob("*"), reverse=True)
        if not installed:
            raise RuntimeError("Install Android NDK 27 or newer to build the 16 KB aligned resvg library")
        ndk = installed[0]
    linker = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
    if not linker.is_file():
        raise RuntimeError(f"Android NDK linker is missing: {linker}")
    if not shutil.which("rustup") or not shutil.which("cargo"):
        raise RuntimeError("Install Rust with rustup and cargo to build the 16 KB aligned resvg library")
    subprocess.run(["rustup", "toolchain", "install", "1.99.1", "--profile", "minimal", "--target", "aarch64-linux-android"], check=True)
    env = os.environ.copy()
    env.update({
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER": str(linker),
        "CC_aarch64_linux_android": str(linker),
        "CXX_aarch64_linux_android": str(linker.parent / "aarch64-linux-android24-clang++"),
        "AR_aarch64_linux_android": str(linker.parent / "llvm-ar"),
        "AR_aarch64-linux-android": str(linker.parent / "llvm-ar"),
        "RUSTFLAGS": (env.get("RUSTFLAGS", "") + " -C link-arg=-Wl,-z,max-page-size=16384").strip(),
    })
    subprocess.run(["cargo", "+1.99.1", "build", "--release", "--locked", "--target", "aarch64-linux-android"], cwd=source, env=env, check=True)
    binary = source / "target/aarch64-linux-android/release/libresvg_js.so"
    if not binary.is_file():
        raise RuntimeError("resvg Android build did not produce libresvg_js.so")
    return binary


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    if NATIVE.exists():
        shutil.rmtree(NATIVE)
    NATIVE.mkdir(parents=True)
    unpacked = CACHE / "termux"
    if unpacked.exists():
        shutil.rmtree(unpacked)
    unpacked.mkdir()
    packages = json.loads((ROOT / "runtime/termux-lock.json").read_text())
    for name, package in packages.items():
        file = CACHE / (name + ".deb")
        download(BASE + package["Filename"], file, "sha256", bytes.fromhex(package["SHA256"]))
        member = next(x for x in run("ar", "t", str(file)).splitlines() if x.startswith("data.tar."))
        data = subprocess.check_output(["ar", "p", str(file), member])
        subprocess.run(["bsdtar", "-xf", "-", "-C", str(unpacked)], input=data, check=True)
    prefix = unpacked / "data/data/com.termux/files/usr"
    libraries = prefix / "lib"
    # Android extracts lib*.so from the APK into an executable, read-only directory.
    mapping = {}

    def copy_library(name, source=None, destination=None):
        if name in mapping:
            return
        source = source or libraries / name
        if not source.exists():  # Android's public libc/libm/libdl/liblog.
            if name not in {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}:
                raise RuntimeError(f"Missing native dependency: {name}")
            return
        destination = destination or re.sub(r"\.so(?:\..*)?$", ".so", name)
        mapping[name] = destination
        shutil.copyfile(source, NATIVE / destination)
        for dependency in needed(source):
            copy_library(dependency)

    copy_library("node", prefix / "bin/node", "libnode.so")
    lock = json.loads((REPO / "package-lock.json").read_text())["packages"]
    platform_packages = [
        ("@napi-rs/canvas-android-arm64", "skia.android-arm64.node", "liblh_canvas.so"),
        ("@resvg/resvg-js-android-arm64", "resvgjs.android-arm64.node", "liblh_resvg.so"),
    ]
    resvg_binary = build_resvg()
    standalone = REPO / ".next/standalone"
    if not (standalone / "server.js").is_file():
        raise RuntimeError("Run npm run build and node scripts/build-server.mjs first")
    for name, binary, destination in platform_packages:
        package = lock["node_modules/" + name]
        algorithm, expected = package["integrity"].split("-", 1)
        file = CACHE / (name.split("/")[-1] + ".tgz")
        download(package["resolved"], file, algorithm, base64.b64decode(expected))
        folder = CACHE / name.split("/")[-1]
        folder.mkdir(exist_ok=True)
        subprocess.run(["bsdtar", "-xf", str(file), "-C", str(folder)], check=True)
        native = resvg_binary if name == "@resvg/resvg-js-android-arm64" else folder / "package" / binary
        copy_library(name, native, destination)
        target = standalone / "node_modules" / name
        target.mkdir(parents=True, exist_ok=True)
        metadata = json.loads((folder / "package/package.json").read_text())
        metadata["main"] = "binding.cjs"
        (target / "package.json").write_text(json.dumps(metadata))
        (target / "binding.cjs").write_text(f"process.dlopen(module, require('node:path').join(process.env.LH_NATIVE_DIR, '{destination}'));\n")
        for license_file in (folder / "package").glob("*LICENSE*"):
            shutil.copyfile(license_file, target / license_file.name)
    for file in NATIVE.iterdir():
        for dependency in needed(file):
            if dependency in mapping and mapping[dependency] != dependency:
                run("patchelf", "--page-size", "16384", "--replace-needed", dependency, mapping[dependency], str(file))
        run("patchelf", "--page-size", "16384", "--set-rpath", "$ORIGIN", str(file))
        if file.name != "libnode.so":
            run("patchelf", "--page-size", "16384", "--set-soname", file.name, str(file))
        file.chmod(0o755)

    shutil.copyfile(ROOT / "runtime/entry.cjs", standalone / "entry.cjs")
    links = {}
    for file in standalone.rglob("*"):
        if file.is_symlink():
            if not file.resolve().is_relative_to(standalone) or not file.exists():
                raise RuntimeError(f"Invalid standalone symlink: {file}")
            links[str(file.relative_to(standalone))] = str(file.readlink())
    (standalone / "android-symlinks.json").write_text(json.dumps(links))
    shutil.copyfile(prefix / "etc/tls/cert.pem", standalone / "cert.pem")
    shutil.copyfile(REPO / "LICENSE", standalone / "LICENSE")
    notices = standalone / "android-notices"
    if notices.exists():
        shutil.rmtree(notices)
    shutil.copytree(prefix / "share/doc", notices / "termux/doc", symlinks=True)
    shutil.copytree(prefix / "share/LICENSES", notices / "termux/LICENSES")
    shutil.copyfile(ROOT / "runtime/termux-lock.json", notices / "termux-lock.json")
    (notices / "SOURCES.txt").write_text(
        "LearnHarness: https://github.com/amosblomqvist/learnharness (AGPL-3.0)\n"
        "Android port source accompanies the APK.\n"
        "Termux build recipes and patches: https://github.com/termux/termux-packages\n"
        "Exact binaries and SHA-256 checksums: termux-lock.json\n"
        "Node.js: https://nodejs.org/dist/v26.13.0/\n"
        f"resvg source: https://github.com/yisibl/resvg-js/tree/{RESVG_REV} (MPL-2.0); Cargo.lock accompanies the source.\n"
        "Canvas source/version: package-lock.json in the source archive.\n"
    )
    # Restore license notices the Next file tracer may omit.
    for package_json in standalone.glob("node_modules/**/package.json"):
        relative = package_json.parent.relative_to(standalone)
        original = REPO / relative
        if original.is_dir():
            for file in original.iterdir():
                if file.is_file() and file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
                    shutil.copyfile(file, package_json.parent / file.name)
    output = ASSETS / "server.zip"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(standalone.rglob("*")):
            if not file.is_file():
                continue
            relative = file.relative_to(standalone)
            if relative.parts[0] == "android":
                raise RuntimeError("Run node scripts/build-server.mjs to remove Android build artifacts")
            if relative.parts[0] == "data" or (len(relative.parts) == 1 and (file.name == "auth.json" or "credentials.json" in file.name or file.name.startswith(".env"))):
                raise RuntimeError(f"Refusing private data in APK: {relative}")
            # Build-host ELF bindings must never execute on the phone.
            if file.suffix == ".node" or re.search(r"\.so(?:\.\d+)*$", file.name) or file.read_bytes()[:4] == b"\x7fELF":
                continue
            archive.write(file, relative)
    (ASSETS / "runtime-id.txt").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "\n")
    print(f"Android server: {output.stat().st_size // 1024 // 1024} MiB; {len(list(NATIVE.iterdir()))} native files")


if __name__ == "__main__":
    main()
