#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Repack a PICO system APK so it can be installed on Quest in /data.

Two transformations, both minimal:

  1. android:sharedUserId="android.uid.system" -> a private 18-char id, patched
     in place inside the binary AndroidManifest.xml string pool (same byte
     length, so every other offset stays valid). Without this, PackageManager
     refuses the install: the platform signature is Meta's, not PICO's.

  2. optionally replace one bundled .so (used to swap PICO's private OpenXR
     loader for Quest's Khronos loader).

Usage:
  python tools/repack_app.py --apk <in.apk> --out <out.apk> \
      [--shared-uid com.picoxr.connect] \
      [--replace-so lib/arm64-v8a/libpico_openxr_loader.so=<loader.so>]
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile

OLD_UID = "android.uid.system"


def patch_binary_manifest(data: bytes, new_uid: str) -> bytes:
    """Replace the sharedUserId string in a binary XML resource, in place."""
    if len(new_uid) != len(OLD_UID):
        raise SystemExit(f"replacement must be exactly {len(OLD_UID)} chars: {new_uid!r}")
    patched = False
    for enc, encode in (("utf-16-le", lambda s: s.encode("utf-16-le")),
                        ("utf-8", lambda s: s.encode("utf-8"))):
        old_b = encode(OLD_UID)
        new_b = encode(new_uid)
        n = data.count(old_b)
        if n:
            data = data.replace(old_b, new_b)
            print(f"  patched {n} occurrence(s) of sharedUserId ({enc})")
            patched = True
    if not patched:
        print("  warning: sharedUserId string not found (already patched?)")
    return data



def patch_target_sdk(data: bytes, new_sdk: int) -> bytes:
    """Rewrite android:targetSdkVersion inside a binary XML manifest, in place.

    Android 14 enforces foreground-service *types* only for targetSdk 34+. PICO
    Connect starts a foreground service without declaring a type, which throws
    MissingForegroundServiceTypeException; lowering the declared target to 33
    restores the Android 13 behaviour without touching any component.
    """
    import struct
    data = bytearray(data)

    def u16(off):
        return struct.unpack_from("<H", data, off)[0]

    def u32(off):
        return struct.unpack_from("<I", data, off)[0]

    def put_u32(off, value):
        struct.pack_into("<I", data, off, value)

    if u16(0) != 0x0003:
        print("  warning: not a binary XML manifest")
        return data

    # Walk chunks; only RES_XML_START_ELEMENT (0x0102) carries attributes.
    off = u16(2)  # XML header size
    total = len(data)
    patched = 0
    while off + 8 <= total:
        ctype = u16(off)
        csize = u32(off + 4)
        if csize < 8 or off + csize > total:
            break
        if ctype == 0x0102:
            # chunk hdr(8) + line/comment(8) + ns/name(8)
            attr_start = u16(off + 24)
            attr_size = u16(off + 26)
            attr_count = u16(off + 28)
            base = off + 16 + attr_start
            for i in range(attr_count):
                a = base + i * attr_size
                if a + 20 > total:
                    break
                # typedValue starts 12 bytes in: size(2) res0(1) dataType(1) data(4)
                data_type = data[a + 15]
                value_off = a + 16
                if data_type in (0x10, 0x11) and u32(value_off) == 34:
                    # Only rewrite if the attribute is targetSdkVersion: it is the
                    # only int attribute whose value is 34 in this manifest.
                    put_u32(value_off, new_sdk)
                    patched += 1
        off += csize
    print(f"  patched targetSdkVersion -> {new_sdk} ({patched} attribute(s))")
    return bytes(data)
def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--shared-uid", default="com.picoxr.connect")
    ap.add_argument("--replace-so", default=None,
                    help="ZIP_ENTRY=PATH, e.g. lib/arm64-v8a/libpico_openxr_loader.so=foo.so")
    ap.add_argument("--target-sdk", type=int, default=None,
                    help="rewrite android:targetSdkVersion, e.g. 33")
    ap.add_argument("--patch-so-string", action="append", default=[],
                    metavar="ZIP_ENTRY=OLD=NEW",
                    help="replace an equal-length ASCII string inside a native "
                         "library, e.g. lib/arm64-v8a/libx.so=XR_A=XR_B")
    ap.add_argument("--add-so", action="append", default=[],
                    metavar="ZIP_ENTRY=PATH",
                    help="add an extra native library, e.g. "
                         "lib/arm64-v8a/libc++.so=libc++.so (repeatable)")
    args = ap.parse_args(argv)

    so_entry = None
    so_path = None
    if args.replace_so:
        so_entry, so_path = args.replace_so.split("=", 1)
        if not os.path.exists(so_path):
            print("missing replacement .so:", so_path, file=sys.stderr)
            return 1
        so_bytes = open(so_path, "rb").read()
        print(f"replacing {so_entry} with {so_path} ({len(so_bytes)} bytes)")

    so_patches = {}
    for spec in args.patch_so_string:
        entry, rest = spec.split("=", 1)
        old, new = rest.split("=", 1)
        if len(old) != len(new):
            print(f"error: replacement must match length ({len(old)} != {len(new)})",
                  file=sys.stderr)
            return 1
        so_patches.setdefault(entry, []).append((old.encode(), new.encode()))

    extras = []
    for spec in args.add_so:
        entry, path = spec.split("=", 1)
        if not os.path.exists(path):
            print("missing extra .so:", path, file=sys.stderr)
            return 1
        extras.append((entry, open(path, "rb").read()))
        print(f"adding {entry} from {path} ({len(extras[-1][1])} bytes)")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)

    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
        args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6
    ) as dst:
        replaced = 0
        for info in src.infolist():
            if info.is_dir():
                continue
            if info.filename == "AndroidManifest.xml":
                data = patch_binary_manifest(src.read(info), args.shared_uid)
                if args.target_sdk is not None:
                    data = patch_target_sdk(data, args.target_sdk)
                dst.writestr(info, data)
            elif so_entry and info.filename == so_entry:
                dst.writestr(info, so_bytes)
                replaced += 1
            elif info.filename in so_patches:
                data = src.read(info)
                for old, new in so_patches[info.filename]:
                    n = data.count(old)
                    data = data.replace(old, new)
                    print(f"  {info.filename}: replaced {n}x {old.decode()}")
                dst.writestr(info, data)
            else:
                dst.writestr(info, src.read(info.filename))
        if so_entry and replaced != 1:
            print(f"error: {so_entry} not found in APK", file=sys.stderr)
            return 1
        existing = {i.filename for i in src.infolist()}
        for entry, data in extras:
            if entry in existing:
                print(f"warning: {entry} already exists, skipped", file=sys.stderr)
                continue
            # Native libraries must stay uncompressed so they can be mmapped.
            zi = zipfile.ZipInfo(entry)
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            dst.writestr(zi, data)
            print(f"  added {entry} ({len(data)} bytes, stored)")

    print("wrote", args.out, os.path.getsize(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
