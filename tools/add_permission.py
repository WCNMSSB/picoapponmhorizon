#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Insert a simple <uses-permission android:name="..."/> into a binary
AndroidManifest.xml.

PICO system apps assume system-only permissions. When such an app is installed
as a normal /data app it still performs the calls (e.g. Settings.Global writes)
and dies with SecurityException. `pm grant` cannot help unless the permission is
declared, so it has to be added to the manifest.

The element is appended as the last child of <manifest>; the string pool is
rebuilt with the new strings appended, so every existing string index stays
valid and no offsets besides the pool have to be re-indexed.

Usage:
  python tools/add_permission.py --apk in.apk --out out.apk \
      --permission android.permission.WRITE_SECURE_SETTINGS
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


class Pool:
    def __init__(self, data, off):
        self.off = off
        self.size = u32(data, off + 4)
        self.count = u32(data, off + 8)
        self.style_count = u32(data, off + 12)
        self.flags = u32(data, off + 16)
        self.strings_start = u32(data, off + 20)
        self.styles_start = u32(data, off + 24)
        self.utf8 = bool(self.flags & UTF8_FLAG)
        self.strings = []
        base = off + self.strings_start
        for i in range(self.count):
            pos = base + u32(data, off + 28 + 4 * i)
            self.strings.append(self._decode(data, pos))

    def _decode(self, data, pos):
        if self.utf8:
            n = data[pos]; pos += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[pos]; pos += 1
            m = data[pos]; pos += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[pos]; pos += 1
            return data[pos:pos + m].decode("utf-8", "replace")
        n = u16(data, pos); pos += 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | u16(data, pos); pos += 2
        return data[pos:pos + 2 * n].decode("utf-16-le", "replace")

    def build(self, extra, orig):
        strings = list(self.strings) + list(extra)
        count = len(strings)
        offsets = []
        blob = bytearray()
        for s in strings:
            offsets.append(len(blob))
            if self.utf8:
                b = s.encode("utf-8")
                u16len = len(b)  # ascii enough for manifests
                blob += bytes([u16len & 0xFF]) if u16len < 0x80 else bytes(
                    [0x80 | (u16len >> 8), u16len & 0xFF])
                blob += bytes([len(b) & 0xFF]) if len(b) < 0x80 else bytes(
                    [0x80 | (len(b) >> 8), len(b) & 0xFF])
                blob += b + b"\x00"
            else:
                b = s.encode("utf-16-le")
                n = len(b) // 2
                blob += p16(n) if n < 0x8000 else p16(0x8000 | (n >> 16)) + p16(n & 0xFFFF)
                blob += b + b"\x00\x00"
        while len(blob) % 4:
            blob += b"\x00"
        header_size = 28
        strings_start = header_size + 4 * count + 4 * self.style_count
        size = strings_start + len(blob)
        out = bytearray()
        out += p16(RES_STRING_POOL) + p16(header_size) + p32(size)
        out += p32(count) + p32(self.style_count) + p32(self.flags)
        out += p32(strings_start) + p32(self.styles_start)
        for o in offsets:
            out += p32(o)
        if self.style_count:
            base = self.off + header_size + 4 * self.count
            for i in range(self.style_count):
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


def patch(data: bytes, permission: str) -> bytes:
    orig = data
    data = bytearray(data)
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")
    off = u16(data, 2)
    pool = None
    pool_off = pool_size = 0
    root_end = None
    depth = 0
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = Pool(orig, off)
            pool_off, pool_size = off, csize
        elif ctype == RES_XML_START_ELEMENT:
            depth += 1
        elif ctype == RES_XML_END_ELEMENT:
            if depth == 1 and root_end is None:
                root_end = off
            depth -= 1
        off += csize
    if pool is None or root_end is None:
        raise SystemExit("manifest root not found")

    extra = [s for s in ("uses-permission", permission) if s not in pool.strings]
    idx = {s: i for i, s in enumerate(pool.strings)}
    base = len(pool.strings)
    for i, s in enumerate(extra):
        idx[s] = base + i

    new_pool = pool.build(extra, orig)
    delta = len(new_pool) - pool_size

    ns_uri = idx[ANDROID_NS]
    attr_name = idx["name"]
    value = idx[permission]
    line = 0
    subtree = start_element(line, 0xFFFFFFFF, idx["uses-permission"],
                            [(ns_uri, attr_name, value, value)])
    subtree += end_element(line, 0xFFFFFFFF, idx["uses-permission"])

    out = bytearray()
    out += data[:pool_off]
    out += new_pool
    out += data[pool_off + pool_size:]
    insert_at = root_end + delta
    out = out[:insert_at] + subtree + out[insert_at:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--permission", required=True)
    args = ap.parse_args(argv)
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data = patch(data, args.permission)
                print("  manifest patched with", args.permission)
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
