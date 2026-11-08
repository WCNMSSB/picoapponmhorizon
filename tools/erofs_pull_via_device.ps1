# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Extract a single file out of an EROFS image using the headset's own kernel.
#
# Why: the Windows rust-fs-erofs build fails on images that use the EROFS
# 0padding feature ("malformed inode: LZ4 decompressed size mismatch"), which is
# exactly what the PICO 4 Ultra system.img uses. Android's kernel mounts EROFS
# natively, so the image is pushed to the headset, loop-mounted read-only, and
# the wanted file is copied out.
#
#   powershell -ExecutionPolicy Bypass -File tools\erofs_pull_via_device.ps1 `
#       -Image images\p4u\system.img `
#       -InnerPath /system/bin/picoprobeservice `
#       -OutDir build\from_image
param(
    [Parameter(Mandatory = $true)][string]$Image,
    [Parameter(Mandatory = $true)][string]$InnerPath,
    [string]$OutDir = "build\from_image"
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = if ($env:ADB) { $env:ADB } else { "adb" }
$imgLocal = (Resolve-Path (Join-Path $Root $Image)).Path

$name = [System.IO.Path]::GetFileName($imgLocal)
$remoteImg = "/data/local/tmp/$name"
$mnt = "/data/local/tmp/erofs_mnt"
$leaf = [System.IO.Path]::GetFileName($InnerPath)

Write-Host "== device check"
& $adb shell "su -c id" | Select-Object -First 1

Write-Host "== push $name"
& $adb push $imgLocal $remoteImg 2>&1 | Select-Object -Last 1

Write-Host "== loop mount"
& $adb shell su -c "mkdir -p $mnt"
$loop = (& $adb shell su -c "losetup -f --show $remoteImg 2>/dev/null").Trim()
if (-not $loop) {
    Write-Host "losetup failed" -ForegroundColor Red
    exit 1
}
Write-Host "   loop: $loop"
& $adb shell su -c "mount -t erofs -o ro $loop $mnt 2>&1" | Select-Object -First 2

Write-Host "== copy $InnerPath"
& $adb shell su -c "cp '$mnt$InnerPath' /sdcard/$leaf 2>&1"
& $adb shell su -c "chmod 666 /sdcard/$leaf 2>&1"

New-Item -ItemType Directory -Force -Path (Join-Path $Root $OutDir) | Out-Null
$localOut = Join-Path $Root (Join-Path $OutDir $leaf)
& $adb pull "/sdcard/$leaf" $localOut 2>&1 | Select-Object -Last 1
if (Test-Path $localOut) {
    Write-Host ("   got {0} ({1:N0} bytes)" -f $localOut, (Get-Item $localOut).Length) -ForegroundColor Green
}

Write-Host "== cleanup"
& $adb shell su -c "umount $mnt 2>&1"
& $adb shell su -c "losetup -d $loop 2>&1"
& $adb shell su -c "rm -f $remoteImg /sdcard/$leaf 2>&1"
& $adb shell su -c "rmdir $mnt 2>&1"
Write-Host "done"
