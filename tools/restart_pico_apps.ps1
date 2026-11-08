# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Restart every PICO app so it re-loads the LSPosed hooks.
#
# Why this is needed: LSPosed injects at process spawn. Restarting lspd (which
# tools\deploy_lsp.ps1 and tools\enable_pico_app.ps1 both do) does not re-inject
# into processes that are already running. Those processes then continue without
# the hooks and, on their next launch, die with
#
#   java.lang.SecurityException: getSerial: The uid 10203 does not meet the
#   requirements to access device identifiers.
#
# i.e. PICO Connect starts up and immediately crashes. Force-stopping the app is
# enough: the next launch spawns a fresh process and gets the hooks again.
#
#   powershell -ExecutionPolicy Bypass -File tools\restart_pico_apps.ps1
param(
    [switch]$AlsoLaunch
)
$ErrorActionPreference = "Continue"
$adb = if ($env:ADB) { $env:ADB } else { "adb" }

$packages = (& $adb shell "pm list packages" 2>&1) |
    ForEach-Object { $_ -replace "package:", "" } |
    Where-Object { $_ -match "^com\.(picovr|pico|picoxr|pvr|pxr)\." -or $_ -match "^com\.bytedance\.pico\." }

Write-Host "== PICO packages"
$packages | ForEach-Object { "   $_" }

Write-Host "== force-stop"
foreach ($p in $packages) {
    & $adb shell "am force-stop $p" 2>&1 | Out-Null
    Write-Host "   stopped $p"
}

if ($AlsoLaunch) {
    Write-Host "== relaunch"
    foreach ($p in @("com.picovr.picostreamassistant", "com.picovr.vrusercenter")) {
        if ($packages -contains $p) {
            & $adb shell "monkey -p $p -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
            Write-Host "   launched $p"
        }
    }
}

Write-Host ""
Write-Host "Next launch of each app spawns a fresh process with the hooks loaded." -ForegroundColor Cyan
Write-Host "Verify with:  adb logcat | Select-String PicoSerial" -ForegroundColor DarkGray
