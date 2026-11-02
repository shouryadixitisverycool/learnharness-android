#!/usr/bin/env python3
"""Reject Android packages missing runtime assets or unsafe native inputs."""
import argparse
import hashlib
import io
import os
from pathlib import PurePosixPath
import re
import struct
import zipfile

PAGE_SIZE = 16 * 1024
MACHINE_AARCH64 = 183


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise ValueError("native library is not a little-endian ELF64 file")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    machine, phoff, phentsize, phnum = header[2], header[5], header[9], header[10]
    if machine != MACHINE_AARCH64:
        raise ValueError(f"native library is not AArch64 (machine {machine})")
    if phentsize < 56 or phoff + phentsize * phnum > len(data):
        raise ValueError("invalid ELF program-header table")
    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type = struct.unpack_from("<I", data, offset)[0]
        if p_type == 1:  # PT_LOAD
            alignments.append(struct.unpack_from("<Q", data, offset + 48)[0])
    if not alignments or any(alignment < PAGE_SIZE for alignment in alignments):
        raise ValueError("every PT_LOAD segment must be aligned for 16 KB pages")
    return alignments


def verify_runtime(server_zip: bytes, runtime_id: bytes) -> None:
    if hashlib.sha256(server_zip).hexdigest().encode() != runtime_id.strip():
        raise ValueError("runtime-id does not match server.zip")
    with zipfile.ZipFile(io.BytesIO(server_zip)) as archive:
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"unsafe server path: {entry.filename}")
            if path.parts and path.parts[0] in {"data", "profile"}:
                raise ValueError("profile data must never be packaged")
            if path.name in {"auth.json", "credentials.json"} or path.name.startswith(".env"):
                raise ValueError(f"private file must never be packaged: {entry.filename}")
            with archive.open(entry) as file:
                is_elf = file.read(4) == b"\x7fELF"
            if entry.filename.endswith((".so", ".node")) or is_elf:
                raise ValueError(f"native code belongs in lib/arm64-v8a: {entry.filename}")


def verify_apk(apk_path: str, application_id: str) -> int:
    with zipfile.ZipFile(apk_path) as apk:
        bad = apk.testzip()
        if bad:
            raise ValueError(f"corrupt APK entry: {bad}")
        names = set(apk.namelist())
        required = {"assets/server.zip", "assets/runtime-id.txt"}
        missing = required - names
        if not any(name.startswith("res/drawable-nodpi") and name.endswith("/app_icon.png") for name in names):
            missing.add("res/drawable-nodpi*/app_icon.png")
        if missing:
            raise ValueError(f"missing APK entries: {', '.join(sorted(missing))}")
        native = sorted(name for name in names if name.startswith("lib/arm64-v8a/") and name.endswith(".so"))
        if not native or "lib/arm64-v8a/libnode.so" not in native:
            raise ValueError("ARM64 Node runtime is missing")
        for name in native:
            load_alignments(apk.read(name))
        verify_runtime(apk.read("assets/server.zip"), apk.read("assets/runtime-id.txt"))
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", application_id):
            raise ValueError("invalid expected application ID")
        return os.path.getsize(apk_path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--application-id", required=True)
    args = parser.parse_args()
    size = verify_apk(args.apk, args.application_id)
    print(f"Verified APK; native ELF alignment and private-data checks passed ({size / 1024 / 1024:.1f} MiB).")


if __name__ == "__main__":
    main()
