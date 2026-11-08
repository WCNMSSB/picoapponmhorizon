#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Add package-visibility entries to an existing <queries> element of a binary
AndroidManifest.xml.

Why: PICO apps shared android.uid.system, and package-visibility filtering does
not apply between packages with the same uid. Once each app runs under its own
uid (Horizon OS), a targetSdk>=30 app can no longer see the PICO account
provider unless it declares it in <queries>. PICO Connect then logs
"Failed to find provider info for com.picovr.vrusercenter.userinfo" and shows
"not logged in".

Usage:
  python tools/add_visibility.py --apk in.apk --out out.apk \
      --package com.picovr.vrusercenter \
      --provider com.picovr.vrusercenter.userinfo \
      --package com.bytedance.pico.matrix \
      --provider com.bytedance.pico.matrix.account
"""
from __future__ import annotations

import argparse
import struct
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
UTF8_FLAG = 0x00000100
TYPE_STRING = 0x03


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
    style_count = u32(data, off + 12)
    flags = u32(data, off + 16)
    strings_start = u32(data, off + 20)
    styles_start = u32(data, off + 24)
    utf8 = bool(flags & UTF8_FLAG)
    strings = []
    base = off + strings_start
    for i in range(count):
        pos = base + u32(data, off + 28 + 4 * i)
        if utf8:
            n = data[pos]; pos += 1
            if n & 0x80:
                pos += 1
            m = data[pos]; pos += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[pos]; pos += 1
            strings.append(data[pos:pos + m].decode("utf-8", "replace"))
        else:
            n = u16(data, pos); pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | u16(data, pos); pos += 2
            strings.append(data[pos:pos + 2 * n].decode("utf-16-le", "replace"))
    return {
        "strings": strings, "count": count, "style_count": style_count,
        "flags": flags, "styles_start": styles_start, "utf8": utf8,
        "size": u32(data, off + 4),
    }


def build_pool(pool, extra, orig, off):
    strings = list(pool["strings"]) + list(extra)
    count = len(strings)
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        if pool["utf8"]:
            b = s.encode("utf-8")
            ln = len(b)
            blob += bytes([ln & 0xFF]) if ln < 0x80 else bytes([0x80 | (ln >> 8), ln & 0xFF])
            blob += bytes([ln & 0xFF]) if ln < 0x80 else bytes([0x80 | (ln >> 8), ln & 0xFF])
            blob += b + b"\x00"
        else:
            b = s.encode("utf-16-le")
            n = len(b) // 2
            blob += p16(n) if n < 0x8000 else p16(0x8000 | (n >> 16)) + p16(n & 0xFFFF)
            blob += b + b"\x00\x00"
    while len(blob) % 4:
        blob += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * count + 4 * pool["style_count"]
    size = strings_start + len(blob)
    out = bytearray()
    out += p16(RES_STRING_POOL) + p16(header_size) + p32(size)
    out += p32(count) + p32(pool["style_count"]) + p32(pool["flags"])
    out += p32(strings_start) + p32(pool["styles_start"])
    for o in offsets:
        out += p32(o)
    if pool["style_count"]:
        base = off + header_size + 4 * pool["count"]
        for i in range(pool["style_count"]):
            out += p32(u32(orig, base + 4 * i))
    out += blob
    return bytes(out)


def start_element(line, ns_idx, name_idx, attrs):
    body = bytearray()
    body += p32(line) + p32(0xFFFFFFFF)
    body += p32(ns_idx) + p32(name_idx)
    body += p16(20) + p16(20) + p16(len(attrs))
    body += p16(0) + p16(0) + p16(0)
    for (a_ns, a_name, a_raw, a_val) in attrs:
        body += p32(a_ns) + p32(a_name) + p32(a_raw)
        body += p16(8) + bytes([0]) + bytes([TYPE_STRING]) + p32(a_val)
    return p16(RES_XML_START_ELEMENT) + p16(16) + p32(8 + len(body)) + bytes(body)


def end_element(line, ns_idx, name_idx):
    body = p32(line) + p32(0xFFFFFFFF) + p32(ns_idx) + p32(name_idx)
    return p16(RES_XML_END_ELEMENT) + p16(16) + p32(8 + len(body)) + bytes(body)


def patch(data: bytes, packages, providers) -> bytes:
    orig = data
    data = bytearray(data)
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")
    off = u16(data, 2)
    pool = None
    pool_off = 0
    queries_end = None
    root_end = None
    depth = 0
    q_depth = None
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = read_pool(data, off)
            pool_off = off
        elif ctype == RES_XML_START_ELEMENT and pool:
            depth += 1
            name = u32(data, off + 20)
            if q_depth is None and name < pool["count"] and pool["strings"][name] == "queries":
                q_depth = depth
        elif ctype == RES_XML_END_ELEMENT:
            if q_depth is not None and depth == q_depth and queries_end is None:
                queries_end = off
            if depth == 1:
                root_end = off
            depth -= 1
        off += csize
    if pool is None:
        raise SystemExit("string pool not found")
    create = queries_end is None

    wanted = list(packages) + list(providers)
    extra = [s for s in (["package", "provider", "name", "authorities"]
                         + (["queries"] if create else []) + wanted)
             if s not in pool["strings"]]
    idx = {s: i for i, s in enumerate(pool["strings"])}
    base = len(pool["strings"])
    for i, s in enumerate(extra):
        idx[s] = base + i

    new_pool = build_pool(pool, extra, orig, pool_off)
    delta = len(new_pool) - pool["size"]

    ns_uri = idx[ANDROID_NS]
    line = 0
    subtree = b""
    if create:
        # no <queries> yet: build one and append it to <manifest>
        subtree += start_element(line, 0xFFFFFFFF, idx["queries"], [])
    for p in packages:
        v = idx[p]
        subtree += start_element(line, 0xFFFFFFFF, idx["package"],
                                 [(ns_uri, idx["name"], v, v)])
        subtree += end_element(line, 0xFFFFFFFF, idx["package"])
    for a in providers:
        v = idx[a]
        subtree += start_element(line, 0xFFFFFFFF, idx["provider"],
                                 [(ns_uri, idx["authorities"], v, v)])
        subtree += end_element(line, 0xFFFFFFFF, idx["provider"])
    if create:
        subtree += end_element(line, 0xFFFFFFFF, idx["queries"])

    out = bytearray()
    out += data[:pool_off]
    out += new_pool
    out += data[pool_off + pool["size"]:]
    if create:
        # reuse root_end: the last END_ELEMENT at depth 1 found while scanning
        insert_at = root_end + delta
    else:
        insert_at = queries_end + delta
    out = out[:insert_at] + subtree + out[insert_at:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--package", action="append", default=[])
    ap.add_argument("--provider", action="append", default=[])
    args = ap.parse_args(argv)
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data = patch(data, args.package, args.provider)
                print(f"  queries += {args.package} {args.provider}")
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
