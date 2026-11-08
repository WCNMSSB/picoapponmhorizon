# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# One-shot setup for PICO Connect on Quest 3.
#
# Reproduces everything that was discovered by hand:
#   * installs the repacked PICO Connect (XR loader shim, VR category,
#     <queries> visibility, private sharedUserId, targetSdk 33)
#   * grants the permissions PICO system apps assumed they had
#   * writes the persist.* tuning values used by the shim
#   * refreshes the LSPosed helper (its /data/app path changes on reinstall)
#
# Device must be rooted (open Singularity first) because pm grant /
# setprop persist.* need root.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup_pico_connect.ps1
param(
    [switch]$SkipInstall
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = if ($env:ADB) { $env:ADB } else { "adb" }
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

function Adb($args) { & $adb @args 2>&1 }
function Root($cmd) { & $adb shell su -c $cmd 2>&1 }

Write-Host "== device"
& $adb devices
$id = (Root "id") -join ""
if ($id -notmatch "uid=0") {
    Write-Host "no root: open Singularity on the headset first" -ForegroundColor Red
    exit 1
}

if (-not $SkipInstall) {
    Write-Host "== install PICO Connect"
    & $adb install -r (Join-Path $Root "dist\pico-connect.apk") 2>&1 | Select-Object -Last 1
}

Write-Host "== grants"
$targets = @(
    @("com.picovr.picostreamassistant", "android.permission.WRITE_SECURE_SETTINGS"),
    @("com.picovr.picostreamassistant", "android.permission.RECORD_AUDIO"),
    @("com.picovr.vrusercenter",        "android.permission.WRITE_SECURE_SETTINGS"),
    @("com.picovr.vrusercenter",        "android.permission.RECORD_AUDIO"),
    @("com.bytedance.pico.matrix",      "android.permission.WRITE_SECURE_SETTINGS"),
    @("com.picovr.store",               "android.permission.WRITE_SECURE_SETTINGS")
)
foreach ($t in $targets) {
    $pkg, $perm = $t
    $out = (Adb @("shell", "pm", "grant", $pkg, $perm)) -join " "
    if ($out -match "Error|Exception") {
        Write-Host ("   {0,-34} {1,-45} skipped" -f $pkg, $perm) -ForegroundColor DarkGray
    } else {
        Write-Host ("   {0,-34} {1,-45} ok" -f $pkg, $perm) -ForegroundColor Green
    }
}

Write-Host "== VR tuning (persist.*, survive reboot)"
$props = @(
    @("persist.pico.xr.eyeswap",   "locate-live"),
    @("persist.pico.xr.ipd",       "0.064"),
    @("persist.pico.xr.fovshift",  "0.13"),
    @("persist.pico.xr.fovvshift", "0.05"),
    @("persist.pico.xr.vmode",     "0"),
    @("persist.pico.panel.desktop", "1920x1080"),
    @("persist.pico.panel.main",    "1024x640")
)
foreach ($p in $props) {
    Root "setprop $($p[0]) $($p[1])" | Out-Null
    $v = (Adb @("shell", "getprop", $p[0])) -join ""
    Write-Host ("   {0,-28} = {1}" -f $p[0], $v.Trim())
}

Write-Host "== LSPosed helper"
& (Join-Path $Root "tools\deploy_lsp.ps1") 2>&1 | Select-Object -Last 3

Write-Host ""
Write-Host "done. reminder:" -ForegroundColor Cyan
Write-Host "  * after every reboot, open Singularity once (temporary root)"
Write-Host "  * LSPosed scope must include: com.picovr.picostreamassistant, com.picovr.vrusercenter,"
Write-Host "    com.bytedance.pico.matrix, com.picovr.store"
Write-Host "  * on the PC, run tools\fix_steamvr_pico.cmd once (SteamVR driver isolation)"
