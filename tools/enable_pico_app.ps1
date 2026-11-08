# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Make an already-installed PICO app usable on Horizon OS, without touching its
# APK.
#
#   powershell -ExecutionPolicy Bypass -File tools\enable_pico_app.ps1 -Package com.example.game
#
# What it does:
#   1. adds the package to this module's LSPosed scope (serial spoof, hidden API,
#      PICO permission facade, controller/pointer input fixes, force-VR)
#   2. grants the permissions PICO system apps assumed they had
#   3. re-applies the Magisk loader overlay (bind-mounts the OpenXR shim over the
#      installed libpico_openxr_loader.so / libopenxr_loader.so)
#   4. restarts the app so the hooks load
param(
    [Parameter(Mandatory = $true)][string]$Package,
    [switch]$NoRestart
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = if ($env:ADB) { $env:ADB } else { "adb" }
$py = if ($env:PYTHON) { $env:PYTHON } else { "python" }
$module = "com.pico.xr.compat.lsp"

function Su($cmd) { & $adb shell su -c $cmd 2>&1 }

Write-Host "== package check"
$installed = (& $adb shell "pm list packages $Package").Trim()
if ($installed -notmatch [regex]::Escape($Package)) {
    Write-Host "not installed: $Package" -ForegroundColor Red
    exit 1
}

Write-Host "== LSPosed scope"
$db = Join-Path $Root "build\scope.db"
Su "cp /data/adb/lspd/config/modules_config.db /sdcard/scope.db" | Out-Null
Su "chmod 666 /sdcard/scope.db" | Out-Null
& $adb pull /sdcard/scope.db $db 2>&1 | Out-Null
& $py (Join-Path $Root "tools\lsp_scope.py") --db $db --module $module --app $Package
& $adb push $db /data/local/tmp/scope.db 2>&1 | Out-Null
Su "killall lspd" | Out-Null
Start-Sleep -Milliseconds 500
Su "cp /data/local/tmp/scope.db /data/adb/lspd/config/modules_config.db" | Out-Null
Su "chmod 600 /data/adb/lspd/config/modules_config.db" | Out-Null
Su "chown root:root /data/adb/lspd/config/modules_config.db" | Out-Null
Su "sh /data/local/tmp/start_lspd.sh" | Out-Null
Start-Sleep -Seconds 3
Write-Host ("   lspd pid: " + ((& $adb shell pidof lspd) -join " "))

Write-Host "== permissions"
foreach ($perm in @("android.permission.WRITE_SECURE_SETTINGS",
                    "android.permission.RECORD_AUDIO",
                    "android.permission.CAMERA")) {
    $out = (& $adb shell "pm grant $Package $perm" 2>&1) -join " "
    if ($out -match "Error|Exception") {
        Write-Host ("   {0,-52} skipped" -f $perm) -ForegroundColor DarkGray
    } else {
        Write-Host ("   {0,-52} ok" -f $perm) -ForegroundColor Green
    }
}

Write-Host "== loader overlay"
$modPath = $null
foreach ($candidate in @("/data/adb/modules/pico_xr_loader",
                        "/data/adb/modules_update/pico_xr_loader")) {
    $found = Su "test -f $candidate/service.sh && echo yes"
    if (($found -join "") -match "yes") { $modPath = $candidate; break }
}
if ($modPath) {
    Write-Host "   module: $modPath"
    Su "sh $modPath/service.sh" | Out-Null
    $log = Su "cat $modPath/apply.log"
    $log | Where-Object { $_ -match [regex]::Escape($Package) -or $_ -match "done," } |
        Select-Object -Last 3 | ForEach-Object { "   $_" }
} else {
    Write-Host "   module not installed (run tools\install_loader_module.ps1)" -ForegroundColor Yellow
}

if (-not $NoRestart) {
    Write-Host "== restart"
    & $adb shell "am force-stop $Package"
    $launch = (& $adb shell "monkey -p $Package -c android.intent.category.LAUNCHER 1" 2>&1) -join " "
    Write-Host "   $launch"
}

Write-Host ""
Write-Host "If the app still opens as a 2D panel, check:" -ForegroundColor Cyan
Write-Host "   adb logcat | Select-String PicoForceVr   -> should mention the package"
Write-Host "If it crashes on com.pico.api.* / XRShell, it needs PICO's private services" -ForegroundColor DarkGray
Write-Host "and cannot run on Horizon OS at all."
