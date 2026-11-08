#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Set a boolean or string android: attribute on one element of a binary
AndroidManifest.xml, adding the attribute (and any missing pool strings) when
it is not present yet.

Used for PICO port work, e.g.:
  * making com.picovr.vrusercenter's userinfo provider exported
    (on PICO every app shared android.uid.system, so the provider never had to
    be exported; with per-app uids on Horizon OS it is unreachable otherwise)
  * declaring 2D activities as resizeable, per Meta's 2D porting guidance

Usage:
  python tools/set_manifest_attr.py --apk in.apk --out out.apk \
      --tag provider --match-attr authorities --match-value com.foo.bar \
      --set exported --value true
  python tools/set_manifest_attr.py --apk in.apk --out out.apk \
      --tag activity --match-attr name --match-value com.foo.Main \
      --set configChanges --value "orientation|screenSize" --string
"""
from __future__ import annotations

import argparse
import struct
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
UTF8_FLAG = 0x00000100
TYPE_STRING = 0x03
TYPE_INT_BOOLEAN = 0x12
NO_RAW = 0xFFFFFFFF


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
    return strings


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


def ensure_strings(data, needed):
    """Append missing strings to the pool, keeping all later offsets valid."""
    data = bytearray(data)
    off = u16(data, 2)
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            strings = read_pool(data, off)
            missing = [s for s in needed if s not in strings]
            if not missing:
                return bytes(data)
            utf8 = bool(u32(data, off + 16) & UTF8_FLAG)
            new_pool = build_pool(strings + missing, utf8)
            out = bytearray()
            out += data[:off]
            out += new_pool
            out += data[off + csize:]
            struct.pack_into("<I", out, 4, len(out))
            return bytes(out)
        off += csize
    raise SystemExit("string pool not found")


def patch(data: bytes, tag: str, match_attr: str, match_value: str,
          set_attr: str, value: str, is_string: bool) -> bytes:
    data = ensure_strings(data, [ANDROID_NS, set_attr] +
                          ([value] if is_string else []))
    data = bytearray(data)
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")

    off = u16(data, 2)
    strings = None
    target = None
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            strings = read_pool(data, off)
        elif ctype == RES_XML_START_ELEMENT and strings:
            name = u32(data, off + 20)
            if name < len(strings) and strings[name] == tag:
                a_start = u16(data, off + 24)
                a_size = u16(data, off + 26)
                a_count = u16(data, off + 28)
                base = off + 16 + a_start
                for i in range(a_count):
                    a = base + i * a_size
                    aname = u32(data, a + 4)
                    araw = u32(data, a + 8)
                    if (aname < len(strings) and strings[aname] == match_attr
                            and araw < len(strings) and strings[araw] == match_value):
                        target = (off, a_start, a_size, a_count, base)
                        break
                if target:
                    break
        off += csize

    if target is None:
        raise SystemExit(f"element <{tag} {match_attr}={match_value}> not found")

    off, a_start, a_size, a_count, base = target
    ns_uri = strings.index(ANDROID_NS)
    if is_string:
        data_type = TYPE_STRING
        data_value = strings.index(value)
        raw_value = data_value
    else:
        data_type = TYPE_INT_BOOLEAN
        data_value = 1 if value.lower() in ("true", "1") else 0
        raw_value = NO_RAW

    for i in range(a_count):
        a = base + i * a_size
        aname = u32(data, a + 4)
        if aname < len(strings) and strings[aname] == set_attr:
            struct.pack_into("<I", data, a + 8, raw_value)
            struct.pack_into("<I", data, a + 16, data_value)
            data[a + 15] = data_type
            print(f"  updated {set_attr}={value}")
            return bytes(data)

    set_idx = strings.index(set_attr)
    chunk_end = off + 16 + a_start + a_count * a_size
    chunk = bytearray(data[off:chunk_end])
    struct.pack_into("<H", chunk, 16 + 12, a_count + 1)
    if a_size != 20:
        raise SystemExit(f"unexpected attribute size {a_size}")
    chunk += p32(ns_uri) + p32(set_idx) + p32(raw_value)
    chunk += p16(8) + bytes([0]) + bytes([data_type]) + p32(data_value)
    struct.pack_into("<I", chunk, 4, len(chunk))

    out = bytearray()
    out += data[:off]
    out += chunk
    out += data[chunk_end:]
    struct.pack_into("<I", out, 4, len(out))
    print(f"  added {set_attr}={value} to <{tag} {match_attr}={match_value}>")
    return bytes(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--match-attr", required=True)
    ap.add_argument("--match-value", required=True)
    ap.add_argument("--set", dest="set_attr", required=True)
    ap.add_argument("--value", required=True)
    ap.add_argument("--string", action="store_true",
                    help="store the value as a string instead of a boolean")
    args = ap.parse_args(argv)
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data = patch(data, args.tag, args.match_attr, args.match_value,
                             args.set_attr, args.value, args.string)
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
