#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Add <intent-filter><category android:name="..."/></intent-filter> to one
activity inside a binary AndroidManifest.xml, in place.

Why this exists
---------------
Horizon OS decides "VR app" vs "2D floating panel" from the *manifest*:
an activity is immersive only when its intent filter declares

    <category android:name="com.oculus.intent.category.VR" />

PICO apps carry the PICO equivalent instead (meta-data pvr.app.type="vr"),
which Horizon OS ignores, so com.picovr.picostreamassistant's
StreamingGameActivity is launched into a VolumetricWindow panel. Passing the
category on the Intent does not help either - it is an ActivityInfo decision.

What it does
------------
1. rebuilds the binary XML string pool with extra strings appended (existing
   indices stay valid, so nothing has to be re-indexed)
2. inserts a four-chunk intent-filter subtree before the activity's
   END_ELEMENT
3. fixes the XML chunk's total size

Usage:
  python tools/add_vr_category.py --apk in.apk --out out.apk \
      --activity com.picoxr.streaming.deviceservice.StreamingGameActivity \
      [--category com.oculus.intent.category.VR]
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
    """Decoded binary-XML string pool."""

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
            # utf16 length then utf8 byte length, each 1 or 2 bytes
            n = data[pos]
            pos += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[pos]
                pos += 1
            m = data[pos]
            pos += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[pos]
                pos += 1
            return data[pos:pos + m].decode("utf-8", "replace")
        n = u16(data, pos)
        pos += 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | u16(data, pos)
            pos += 2
        return data[pos:pos + 2 * n].decode("utf-16-le", "replace")

    def build(self, extra):
        strings = list(self.strings) + list(extra)
        count = len(strings)
        out = bytearray()
        offsets = []
        blob = bytearray()
        for s in strings:
            offsets.append(len(blob))
            if self.utf8:
                b = s.encode("utf-8")
                # utf16 length, utf8 length, bytes, NUL
                u16len = len(s.encode("utf-16-le")) // 2
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
        out += p16(RES_STRING_POOL) + p16(header_size) + p32(size)
        out += p32(count) + p32(self.style_count) + p32(self.flags)
        out += p32(strings_start) + p32(self.styles_start)
        for o in offsets:
            out += p32(o)
        if self.style_count:
            # keep the original style offsets verbatim; they are unused here
            base = self.off + header_size + 4 * self.count
            for i in range(self.style_count):
                out += p32(u32(ORIG[0], base + 4 * i))
        out += blob
        return bytes(out), count


ORIG = [b""]


def start_element(line, ns_idx, name_idx, attrs):
    """attrs: list of (ns_idx, name_idx, raw_value_idx, string_value_idx)"""
    body = bytearray()
    body += p32(line) + p32(0xFFFFFFFF)          # lineNumber, comment
    body += p32(ns_idx) + p32(name_idx)          # element ns, name
    body += p16(20) + p16(20) + p16(len(attrs))  # attrStart/attrSize/count
    body += p16(0) + p16(0) + p16(0)             # id/class/style index
    for (a_ns, a_name, a_raw, a_val) in attrs:
        body += p32(a_ns) + p32(a_name) + p32(a_raw)
        body += p16(8) + bytes([0]) + bytes([TYPE_STRING]) + p32(a_val)
    size = 8 + len(body)
    return p16(RES_XML_START_ELEMENT) + p16(16) + p32(size) + bytes(body)


def end_element(line, ns_idx, name_idx):
    body = p32(line) + p32(0xFFFFFFFF) + p32(ns_idx) + p32(name_idx)
    size = 8 + len(body)
    return p16(RES_XML_END_ELEMENT) + p16(16) + p32(size) + bytes(body)


def patch_manifest(data: bytes, activity: str, category: str) -> bytes:
    data = bytearray(data)
    ORIG[0] = bytes(data)
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")
    header_size = u16(data, 2)

    # walk chunks to find the string pool and the target activity
    off = header_size
    pool = None
    pool_off = pool_size = 0
    activity_start = activity_end = None
    depth = 0
    target_depth = None
    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = Pool(bytes(data), off)
            pool_off, pool_size = off, csize
        elif ctype == RES_XML_START_ELEMENT:
            depth += 1
            name_idx = u32(data, off + 20)
            aname = pool.strings[name_idx] if pool and name_idx < pool.count else ""
            if activity_start is None and aname == "activity":
                astart = u16(data, off + 24)
                acount = u16(data, off + 28)
                base = off + 16 + astart
                is_target = False
                for i in range(acount):
                    a = base + i * 20
                    nidx = u32(data, a + 4)
                    if pool and nidx < pool.count and pool.strings[nidx] == "name":
                        vidx = u32(data, a + 8)
                        if pool and vidx < pool.count and pool.strings[vidx] == activity:
                            is_target = True
                if is_target:
                    target_depth = depth
        elif ctype == RES_XML_END_ELEMENT:
            if activity_start is None and target_depth is not None and depth == target_depth:
                activity_start, activity_end = off, off
            depth -= 1
        off += csize

    if pool is None:
        raise SystemExit("string pool not found")
    if activity_end is None:
        raise SystemExit(f"activity not found: {activity}")

    # new strings (append only: existing indices stay valid)
    extra = []
    for s in ("intent-filter", category, "android.intent.action.MAIN"):
        if s not in pool.strings:
            extra.append(s)
    idx = {s: i for i, s in enumerate(pool.strings)}
    if extra:
        start = len(pool.strings)
        for i, s in enumerate(extra):
            idx[s] = start + i

    new_pool, _ = pool.build(extra)
    delta = len(new_pool) - pool_size

    # element chunks use the android namespace uri + "name" + category value
    ns_uri = idx[ANDROID_NS]
    attr_name = idx["name"]
    cat_value = idx[category]

    line = 0
    subtree = b""
    # PackageManager drops an intent filter that has no <action>, so a MAIN
    # action has to be part of the subtree or the category never registers.
    act_value = idx["android.intent.action.MAIN"]
    subtree += start_element(line, 0xFFFFFFFF, idx["intent-filter"], [])
    subtree += start_element(line, 0xFFFFFFFF, idx["action"],
                             [(ns_uri, attr_name, act_value, act_value)])
    subtree += end_element(line, 0xFFFFFFFF, idx["action"])
    subtree += start_element(line, 0xFFFFFFFF, idx["category"],
                             [(ns_uri, attr_name, cat_value, cat_value)])
    subtree += end_element(line, 0xFFFFFFFF, idx["category"])
    subtree += end_element(line, 0xFFFFFFFF, idx["intent-filter"])

    out = bytearray()
    out += data[:pool_off]
    out += new_pool
    out += data[pool_off + pool_size:]
    # positions after the pool shift by delta
    insert_at = activity_end + delta
    out = out[:insert_at] + subtree + out[insert_at:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--activity", required=True)
    ap.add_argument("--category", default="com.oculus.intent.category.VR")
    args = ap.parse_args(argv)

    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
        args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6
    ) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data = patch_manifest(data, args.activity, args.category)
                print(f"  patched manifest: {len(data)} bytes")
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
