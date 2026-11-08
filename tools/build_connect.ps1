# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Build dist\pico-connect.apk from the P4U image APK.
#
# Chain (each step is a separate tool so it can be re-run in isolation):
#   1. repack: private sharedUserId, targetSdk 33, loader shim, real loader,
#      libc++, streaming-server string patch
#   2. VR intent-filter on StreamingGameActivity (fullscreen immersive)
#   3. <queries> package visibility for the PICO account providers
#   4. resizeableActivity on the 2D activities (Meta 2D porting guidance)
#   5. <layout defaultWidth/defaultHeight> panel sizes
#   6. zipalign + apksigner
#
#   powershell -ExecutionPolicy Bypass -File tools\build_connect.ps1
param(
    [int]$MainWidth = 1163,
    [int]$MainHeight = 727,
    [int]$DeskWidth = 1574,
    [int]$DeskHeight = 942,
    [switch]$Install
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$b = Join-Path $Root "build\pico_connect"
$bt = Join-Path $Root "tools\android-sdk\build-tools\34.0.0"
$src = Join-Path $Root "extracted\p4u\PICOConnect.apk"

function Step($n) { Write-Host "== $n" -ForegroundColor Cyan }

Step "1 repack"
python (Join-Path $Root "tools\repack_app.py") --apk $src --out "$b\s1.apk" --target-sdk 33 `
  --replace-so "lib/arm64-v8a/libpico_openxr_loader.so=$b\libpico_openxr_loader_shim.so" `
  --add-so "lib/arm64-v8a/libopenxr_loader_real.so=$Root\rootfs\q3\system_ext\lib64\libopenxr_loader.so" `
  --add-so "lib/arm64-v8a/libc++.so=$b\libc++.so" `
  --patch-so-string "lib/arm64-v8a/libstreaming_server.so=XR_EXT_eye_gaze_interaction=XR_EXT_performance_settings" | Out-Null

Step "2 VR category"
python (Join-Path $Root "tools\add_vr_category.py") --apk "$b\s1.apk" --out "$b\s2.apk" `
  --activity "com.picoxr.streaming.deviceservice.StreamingGameActivity" | Out-Null

Step "3 package visibility"
python (Join-Path $Root "tools\add_visibility.py") --apk "$b\s2.apk" --out "$b\s3.apk" `
  --package com.picovr.vrusercenter --provider com.picovr.vrusercenter.userinfo `
  --package com.bytedance.pico.matrix --provider com.bytedance.pico.matrix.account | Out-Null

Step "4 resizeable activities"
$acts = @(
  "com.picovr.picostreamassistant.ui.MainActivity",
  "com.picovr.picostreamassistant.ui.DesktopActivity",
  "com.picovr.picostreamassistant.ui.SplashActivity",
  "com.picovr.picostreamassistant.ui.ControllerRemindActivity",
  "com.picoxr.streaming.deviceservice.ui.activity.StreamAuthorityActivity",
  "com.picoxr.streaming.deviceservice.ui.activity.SwiftCalibratingTipsActivity"
)
$cur = "$b\s3.apk"
$i = 0
foreach ($a in $acts) {
  $i++
  $next = "$b\s3_$i.apk"
  python (Join-Path $Root "tools\set_manifest_attr.py") --apk $cur --out $next `
    --tag activity --match-attr name --match-value $a --set resizeableActivity --value true | Out-Null
  $cur = $next
}

Step "5 panel sizes"
python (Join-Path $Root "tools\add_layout_size.py") --apk $cur --out "$b\s4.apk" `
  --activity "com.picovr.picostreamassistant.ui.MainActivity" --width $MainWidth --height $MainHeight | Out-Null
python (Join-Path $Root "tools\add_layout_size.py") --apk "$b\s4.apk" --out "$b\s5.apk" `
  --activity "com.picovr.picostreamassistant.ui.DesktopActivity" --width $DeskWidth --height $DeskHeight | Out-Null

Step "6 sign"
$aligned = "$b\aligned.apk"
$out = Join-Path $Root "dist\pico-connect.apk"
Remove-Item -Force $aligned, $out -ErrorAction SilentlyContinue
& "$bt\zipalign.exe" -f -p 4 "$b\s5.apk" $aligned 2>&1 | Out-Null
& "$bt\apksigner.bat" sign --ks (Join-Path $Root "testapp\debug.keystore") `
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey `
  --out $out $aligned 2>&1 | Out-Null

$size = (Get-Item $out).Length
Write-Host ("built {0}  ({1:N0} bytes)  main={2}x{3}dp desktop={4}x{5}dp" -f `
  $out, $size, $MainWidth, $MainHeight, $DeskWidth, $DeskHeight) -ForegroundColor Green

if ($Install) {
    $adb = if ($env:ADB) { $env:ADB } else { "adb" }
    Step "install"
    & $adb install -r $out
    & $adb shell am force-stop com.picovr.picostreamassistant
    & $adb shell am start -n com.picovr.picostreamassistant/com.picovr.picostreamassistant.ui.MainActivity
}
