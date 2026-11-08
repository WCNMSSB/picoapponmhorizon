# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Deploy the LSPosed helper to the connected Quest and refresh LSPosed's cache.
#
# Why this exists: `adb install -r` puts the APK under a new /data/app path, but
# LSPosed keeps the old path in modules_config.db and then silently skips the
# module. This script reinstalls, rewrites that row, restarts lspd and verifies
# that exactly one daemon is running.
param(
    [string]$Apk = "dist\pico-xr-lsp.apk",
    [string]$Pkg = "com.pico.xr.compat.lsp"
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = if ($env:ADB) { $env:ADB } else { "adb" }
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

$apkPath = Join-Path $Root $Apk
Write-Host "== install $apkPath"
& $adb install -r $apkPath 2>&1 | Select-Object -Last 1

$cur = ((& $adb shell "pm path $Pkg") -replace 'package:', '').Trim()
Write-Host "== installed at $cur"
if (-not $cur) { throw "package not installed" }

$tmp = Join-Path $Root "build\lspd"
New-Item -ItemType Directory -Force $tmp | Out-Null
& $adb shell su -c "cp /data/adb/lspd/config/modules_config.db /sdcard/lspd_now.db" | Out-Null
& $adb shell su -c "chmod 666 /sdcard/lspd_now.db" | Out-Null
& $adb pull /sdcard/lspd_now.db "$tmp\now.db" 2>&1 | Out-Null

$py = Join-Path $tmp "patch_path.py"
@"
import sqlite3, sys
db, pkg, apk = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db)
cur = con.cursor()
cur.execute("update modules set apk_path=? where module_pkg_name=?", (apk, pkg))
if cur.rowcount == 0:
    cur.execute("insert into modules (module_pkg_name, apk_path) values (?,?)", (pkg, apk))
con.commit()
print("rows:", cur.execute("select * from modules where module_pkg_name=?", (pkg,)).fetchone())
con.close()
"@ | Set-Content -Encoding UTF8 $py
& python $py "$tmp\now.db" $Pkg $cur
& $adb push "$tmp\now.db" /sdcard/lspd_fixed.db 2>&1 | Out-Null

& $adb shell su -c "killall lspd" | Out-Null
Start-Sleep -Seconds 1
& $adb shell su -c "cp /sdcard/lspd_fixed.db /data/adb/lspd/config/modules_config.db"
& $adb shell su -c "rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm"
& $adb shell su -c "chmod 600 /data/adb/lspd/config/modules_config.db"
& $adb shell su -c "sh /data/local/tmp/start_lspd.sh"
Start-Sleep -Seconds 3
$pids = (& $adb shell "pidof lspd").Trim()
Write-Host "== lspd pids: $pids"
