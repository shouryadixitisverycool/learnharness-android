#!/usr/bin/env python3
"""Bundle the existing production server and checksum-pinned Android runtime."""
import base64
import hashlib
import json
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
        copy_library(name, folder / "package" / binary, destination)
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
        "JS/native package versions and sources: package-lock.json in the source archive.\n"
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
