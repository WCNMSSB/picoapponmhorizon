#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Report the facts picoify.ps1 needs about an APK, as JSON.

Uses aapt2 (badging + xmltree) so no manifest parsing is reimplemented here.

  python tools/apk_probe.py --apk app.apk --aapt2 path/to/aapt2.exe
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import zipfile


def run(exe, *args):
    out = subprocess.run([exe, *args], capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    return out.stdout or ""


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--aapt2", required=True)
    args = ap.parse_args(argv)

    badging = run(args.aapt2, "dump", "badging", args.apk)
    tree = run(args.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", args.apk)

    pkg = re.search(r"package: name='([^']+)'", badging)
    target = re.search(r"targetSdkVersion:'(\d+)'", badging)
    launch = re.search(r"launchable-activity: name='([^']+)'", badging)
    shared = re.search(r'sharedUserId\(0x0101000b\)="([^"]+)"', tree)

    # activities that already declare an immersive category
    vr_activities = []
    current = None
    for line in tree.splitlines():
        m = re.search(r'name\(0x01010003\)="(com\.[^"]+Activity[^"]*)"', line)
        if m and line.strip().startswith("A:"):
            current = m.group(1)
        if "com.oculus.intent.category.VR" in line and current:
            vr_activities.append(current)

    with zipfile.ZipFile(args.apk) as z:
        names = z.namelist()
    libs = [n for n in names if n.startswith("lib/arm64-v8a/")]
    has_loader = any(n.endswith("/libopenxr_loader.so") for n in names)
    has_pxr = any("libpxr_api" in n or "libPxrPlatform" in n for n in names)
    abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/")})

    print(json.dumps({
        "package": pkg.group(1) if pkg else None,
        "targetSdk": int(target.group(1)) if target else None,
        "launchableActivity": launch.group(1) if launch else None,
        "sharedUserId": shared.group(1) if shared else None,
        "vrActivities": sorted(set(vr_activities)),
        "hasOpenXrLoader": has_loader,
        "hasPxrApi": has_pxr,
        "abis": abis,
        "arm64Libs": libs[:200],
    }, indent=2))


if __name__ == "__main__":
    main()
