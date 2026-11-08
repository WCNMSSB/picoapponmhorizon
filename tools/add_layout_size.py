#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Insert a <layout android:defaultWidth/defaultHeight> child into an activity of
a binary AndroidManifest.xml.

Horizon OS sizes 2D panels from this element:

    <activity android:name=".MainActivity">
        <layout
            android:defaultWidth="1024dp"
            android:defaultHeight="640dp" />
    </activity>

Sizes are dimension values ("1024dp"), encoded as TYPE_DIMENSION with unit dp
and radix 0 (integer dp).

Usage:
  python tools/add_layout_size.py --apk in.apk --out out.apk \
      --activity com.example/.MainActivity --width 1024 --height 640
"""
from __future__ import annotations

import argparse
import struct
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
UTF8_FLAG = 0x00000100
TYPE_DIMENSION = 0x05
TYPE_STRING = 0x03
NO_RAW = 0xFFFFFFFF

# Res_value complex units
UNIT_DP = 1


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def p16(v):
    return struct.pack("<H", v)


def p32(v):
    return struct.pack("<I", v)


def read_pool(data, off):
    count = u32(data, off + 8)
    flags = u32(data, off + 16)
    strings_start = u32(data, off + 20)
    utf8 = bool(flags & UTF8_FLAG)
    strings = []
    base = off + strings_start
    for i in range(count):
        pos = base + u32(data, off + 28 + 4 * i)
        if utf8:
            n = data[pos]
            pos += 1
            if n & 0x80:
                pos += 1
            m = data[pos]
            pos += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[pos]
                pos += 1
            strings.append(data[pos:pos + m].decode("utf-8", "replace"))
        else:
            n = u16(data, pos)
            pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | u16(data, pos)
                pos += 2
            strings.append(data[pos:pos + 2 * n].decode("utf-16-le", "replace"))
    return strings, utf8


def build_pool(strings, utf8):
    count = len(strings)
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        if utf8:
            b = s.encode("utf-8")
            n = len(b)
            blob += bytes([n]) if n < 0x80 else bytes([0x80 | (n >> 8), n & 0xFF])
            blob += b + b"\x00"
        else:
            b = s.encode("utf-16-le")
            n = len(b) // 2
            blob += p16(n) if n < 0x8000 else p16(0x8000 | (n >> 16)) + p16(n & 0xFFFF)
            blob += b + b"\x00\x00"
    while len(blob) % 4:
        blob += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * count
    size = strings_start + len(blob)
    out = bytearray()
    out += p16(RES_STRING_POOL) + p16(header_size) + p32(size)
    out += p32(count) + p32(0) + p32(0x100 if utf8 else 0)
    out += p32(strings_start) + p32(0)
    for o in offsets:
        out += p32(o)
    out += blob
    return bytes(out)


def dp_value(dp: int) -> int:
    """Encode an integer dp value as a Res_value complex dimension."""
    return ((dp & 0xFFFFFF) << 8) | (0 << 4) | UNIT_DP


def make_element(name_idx, attrs):
    """attrs: list of (ns_idx, name_idx, res0, data_type, data)."""
    body = bytearray()
    body += p32(0xFFFFFFFF) + p32(0xFFFFFFFF)   # line, comment
    body += p32(0xFFFFFFFF) + p32(name_idx)     # ns, name
    body += p16(20) + p16(20) + p16(len(attrs))  # attrStart, attrSize, attrCount
    body += p16(0) + p16(0) + p16(0)
    for ns, nm, raw, dtype, data in attrs:
        body += p32(ns) + p32(nm) + p32(raw)
        body += p16(8) + bytes([0]) + bytes([dtype]) + p32(data)
    return p16(RES_XML_START_ELEMENT) + p16(16) + p32(8 + len(body)) + bytes(body)


def make_end(name_idx):
    body = p32(0xFFFFFFFF) + p32(0xFFFFFFFF) + p32(0xFFFFFFFF) + p32(name_idx)
    return p16(RES_XML_END_ELEMENT) + p16(16) + p32(8 + len(body)) + bytes(body)


def patch(data: bytes, activity: str, width: int, height: int) -> bytes:
    data = bytearray(data)
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")

    off = u16(data, 2)
    pool_off = None
    pool_size = 0
    strings = None
    utf8 = False
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            strings, utf8 = read_pool(data, off)
            pool_off, pool_size = off, csize
            break
        off += csize
    if strings is None:
        raise SystemExit("string pool not found")

    needed = [ANDROID_NS, "layout", "defaultWidth", "defaultHeight", "activity", "name"]
    missing = [s for s in needed if s not in strings]
    if missing:
        new_pool = build_pool(strings + missing, utf8)
        out = bytearray()
        out += data[:pool_off]
        out += new_pool
        out += data[pool_off + pool_size:]
        struct.pack_into("<I", out, 4, len(out))
        data = bytearray(out)
        strings = strings + missing

    idx = {s: i for i, s in enumerate(strings)}
    short = activity.split("/")[-1]

    off = u16(data, 2)
    depth = 0
    act_depth = None
    act_name_idx = None
    insert_at = None
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_XML_START_ELEMENT:
            depth += 1
            name = u32(data, off + 20)
            if (act_depth is None and name < len(strings)
                    and strings[name] == "activity"):
                a_start = u16(data, off + 24)
                a_size = u16(data, off + 26)
                a_count = u16(data, off + 28)
                base = off + 16 + a_start
                for i in range(a_count):
                    a = base + i * a_size
                    aname = u32(data, a + 4)
                    araw = u32(data, a + 8)
                    if (aname < len(strings) and strings[aname] == "name"
                            and araw < len(strings)):
                        value = strings[araw]
                        if value == activity or value.endswith(short):
                            act_depth = depth
                            act_name_idx = name
                        break
        elif ctype == RES_XML_END_ELEMENT:
            if act_depth is not None and depth == act_depth and insert_at is None:
                insert_at = off
                break
            depth -= 1
        off += csize

    if insert_at is None:
        raise SystemExit(f"activity {activity} not found")

    ns_uri = idx[ANDROID_NS]
    subtree = make_element(idx["layout"], [
        (ns_uri, idx["defaultWidth"], NO_RAW, TYPE_DIMENSION, dp_value(width)),
        (ns_uri, idx["defaultHeight"], NO_RAW, TYPE_DIMENSION, dp_value(height)),
    ]) + make_end(idx["layout"])

    out = bytearray()
    out += data[:insert_at]
    out += subtree
    out += data[insert_at:]
    struct.pack_into("<I", out, 4, len(out))
    print(f"  <layout> {width}x{height} dp -> {activity}")
    return bytes(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--activity", required=True)
    ap.add_argument("--width", type=int, required=True)
    ap.add_argument("--height", type=int, required=True)
    args = ap.parse_args(argv)
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data = patch(data, args.activity, args.width, args.height)
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
