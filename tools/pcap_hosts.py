#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Extract DNS question names and TLS ClientHello SNI from a pcap file,
without external dependencies.

Android's `tcpdump -i any` writes Linux cooked (SLL/SLL2) link-layer frames, so
both that and plain Ethernet are handled.
"""
from __future__ import annotations

import struct
import sys
from collections import Counter

MAGICS = {
    b"\xd4\xc3\xb2\xa1": ("<", 4),
    b"\xa1\xb2\xc3\xd4": (">", 4),
    b"\x4d\x3c\xb2\xa1": ("<", 6),
    b"\xa1\xb2\x3c\x4d": (">", 6),
}


def read_pcap(path):
    with open(path, "rb") as f:
        head = f.read(24)
        magic = head[:4]
        if magic not in MAGICS:
            raise SystemExit(f"unknown pcap magic {magic!r}")
        endian, _ = MAGICS[magic]
        linktype = struct.unpack(endian + "I", head[20:24])[0]
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                break
            ts, tus, incl, orig = struct.unpack(endian + "IIII", rec)
            data = f.read(incl)
            if len(data) < incl:
                break
            yield linktype, data


def l3_payload(linktype, data):
    if linktype == 1:  # Ethernet
        if len(data) < 14:
            return None
        ethertype = struct.unpack(">H", data[12:14])[0]
        off = 14
        while ethertype in (0x8100, 0x88A8):
            ethertype = struct.unpack(">H", data[off + 2:off + 4])[0]
            off += 4
        return data[off:] if ethertype == 0x0800 else None
    if linktype in (113, 276):  # SLL / SLL2
        if linktype == 113:
            if len(data) < 16:
                return None
            proto = struct.unpack(">H", data[14:16])[0]
            off = 16
        else:
            if len(data) < 20:
                return None
            proto = struct.unpack(">H", data[0:2])[0]
            off = 20
        return data[off:] if proto == 0x0800 else None
    if linktype == 101:  # raw IP
        return data
    return None


def parse_ip(pkt):
    if len(pkt) < 20:
        return None
    ver = pkt[0] >> 4
    if ver != 4:
        return None
    ihl = (pkt[0] & 0x0F) * 4
    proto = pkt[9]
    total = struct.unpack(">H", pkt[2:4])[0]
    return proto, pkt[ihl:total]


def dns_name(payload):
    if len(payload) < 12:
        return None
    qdcount = struct.unpack(">H", payload[4:6])[0]
    if qdcount < 1:
        return None
    off = 12
    labels = []
    while off < len(payload):
        n = payload[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0:
            break
        off += 1
        labels.append(payload[off:off + n].decode("ascii", "replace"))
        off += n
    name = ".".join(labels)
    return name if name else None


def tls_sni(payload):
    if len(payload) < 6 or payload[0] != 0x16 or payload[1] != 0x03:
        return None
    # TLS record header (5) then handshake header (4)
    if len(payload) < 9 or payload[5] != 0x01:
        return None
    p = 9
    p += 2 + 32  # version + random
    if p >= len(payload):
        return None
    sid_len = payload[p]
    p += 1 + sid_len
    if p + 2 > len(payload):
        return None
    cs_len = struct.unpack(">H", payload[p:p + 2])[0]
    p += 2 + cs_len
    if p >= len(payload):
        return None
    comp_len = payload[p]
    p += 1 + comp_len
    if p + 2 > len(payload):
        return None
    ext_total = struct.unpack(">H", payload[p:p + 2])[0]
    p += 2
    end = min(len(payload), p + ext_total)
    while p + 4 <= end:
        etype, elen = struct.unpack(">HH", payload[p:p + 4])
        p += 4
        if etype == 0x00:  # server_name
            if p + 5 > end:
                return None
            # list len (2), type (1), name len (2)
            nlen = struct.unpack(">H", payload[p + 3:p + 5])[0]
            return payload[p + 5:p + 5 + nlen].decode("ascii", "replace")
        p += elen
    return None


def main(path):
    dns = Counter()
    sni = Counter()
    packets = 0
    for linktype, data in read_pcap(path):
        packets += 1
        pkt = l3_payload(linktype, data)
        if not pkt:
            continue
        parsed = parse_ip(pkt)
        if not parsed:
            continue
        proto, body = parsed
        if proto == 17 and len(body) > 8:  # UDP
            dport = struct.unpack(">H", body[2:4])[0]
            if dport == 53:
                name = dns_name(body[8:])
                if name:
                    dns[name] += 1
        elif proto == 6 and len(body) > 20:  # TCP
            dport = struct.unpack(">H", body[2:4])[0]
            doff = (body[12] >> 4) * 4
            if dport == 443:
                name = tls_sni(body[doff:])
                if name:
                    sni[name] += 1

    print(f"packets: {packets}")
    print(f"dns names: {len(dns)}  tls sni: {len(sni)}")
    merged = Counter()
    merged.update(dns)
    merged.update(sni)
    interesting = sorted(h for h in merged if any(
        k in h.lower() for k in ("pico", "pvr", "bytedance", "matrix", "device", "account")))
    print("\n== interesting hosts ==")
    for h in interesting:
        print(f"  {merged[h]:6d}  {h}  (dns={dns.get(h,0)} sni={sni.get(h,0)})")
    print("\n== all hosts (top 40) ==")
    for h, c in merged.most_common(40):
        print(f"  {c:6d}  {h}")


if __name__ == "__main__":
    main(sys.argv[1])
