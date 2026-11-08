# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Temporarily isolate the PICO Connect driver inside SteamVR.
#
# "SteamVR initialization failed! No headset detected." with PICO Connect is
# usually not a PICO problem: SteamVR has several HMD drivers registered
# (oculus / vrlink / Virtual Desktop / iVRy / VIVE ...) and one of them claims
# the headset slot before the "pico" driver gets a chance.
#
#   powershell -ExecutionPolicy Bypass -File steamvr_isolate_pico.ps1
#       -> shows what would change, touches nothing
#
#   powershell -ExecutionPolicy Bypass -File steamvr_isolate_pico.ps1 -Apply
#       -> backs up steamvr.vrsettings and disables every driver except pico,
#          gamepad and lighthouse
#
#   powershell -ExecutionPolicy Bypass -File steamvr_isolate_pico.ps1 -Restore
#       -> puts the newest backup back
param(
    [switch]$Apply,
    [switch]$Restore
)
$ErrorActionPreference = "Stop"

$config = "E:\STEAM\config\steamvr.vrsettings"
if (-not (Test-Path $config)) {
    $found = Get-ChildItem "E:\STEAM\config" -Filter "steamvr.vrsettings" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($found) { $config = $found.FullName }
}
if (-not $config -or -not (Test-Path $config)) {
    Write-Host "steamvr.vrsettings not found (looked in E:\STEAM\config)" -ForegroundColor Red
    exit 1
}
Write-Host "config: $config"

if ($Restore) {
    $bak = Get-ChildItem (Split-Path $config) -Filter "steamvr.vrsettings.pico-bak-*" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $bak) {
        Write-Host "no backup found" -ForegroundColor Red
        exit 1
    }
    Copy-Item $bak.FullName $config -Force
    Write-Host "restored from $($bak.Name)" -ForegroundColor Green
    exit 0
}

# Drivers that must keep working.
$keep = @("pico", "gamepad", "lighthouse")

function Set-Enable($root, [string]$name, [bool]$value) {
    # The section may be absent, null, or carry other keys but no "enable".
    if ($null -eq $root.$name -or $root.$name -isnot [pscustomobject]) {
        $root | Add-Member -NotePropertyName $name -NotePropertyValue ([pscustomobject]@{}) -Force
    }
    $child = $root.$name
    $child | Add-Member -NotePropertyName "enable" -NotePropertyValue $value -Force
}

$raw = Get-Content $config -Raw | ConvertFrom-Json

Write-Host ""
Write-Host "current driver sections:" -ForegroundColor Cyan
$targets = @()
foreach ($p in $raw.PSObject.Properties) {
    if ($p.Name -notlike "driver_*") { continue }
    $dname = $p.Name.Substring(7)
    $cur = "(not set)"
    if ($p.Value -and $p.Value.PSObject.Properties.Name -contains "enable") { $cur = $p.Value.enable }
    $skip = $keep -contains $dname.ToLower()
    $mark = if ($skip) { "keep" } else { "disable" }
    Write-Host ("   {0,-22} enable={1,-10} -> {2}" -f $p.Name, $cur, $mark)
    if (-not $skip) { $targets += $dname }
}

# Built-in drivers live in SteamVR\drivers\ and never show up in
# steamvr.vrsettings until something writes them, so they have to be added
# explicitly. driver_oculus matters most: it is the one OVRServer_x64 backs.
$builtin = @("oculus", "htc", "prism", "deckard", "frame_hmd", "slimevr")
foreach ($b in $builtin) {
    if ($keep -notcontains $b -and $targets -notcontains $b) {
        $targets += $b
        Write-Host ("   {0,-22} enable=(absent)  -> disable (built-in)" -f "driver_$b")
    }
}

if ($targets.Count -eq 0) {
    Write-Host "nothing to disable" -ForegroundColor Yellow
}

if (-not $Apply) {
    Write-Host ""
    Write-Host "dry run only. re-run with -Apply to change, -Restore to undo." -ForegroundColor Cyan
    exit 0
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backup = "$config.pico-bak-$stamp"
Copy-Item $config $backup -Force
Write-Host ""
Write-Host "backup: $backup" -ForegroundColor Green

foreach ($d in $targets) {
    Set-Enable $raw "driver_$d" $false
}
Set-Enable $raw "driver_pico" $true

$raw | ConvertTo-Json -Depth 20 | Set-Content $config -Encoding UTF8
Write-Host ""
Write-Host "disabled: $($targets -join ', ')" -ForegroundColor Green
Write-Host "kept:     $($keep -join ', ')" -ForegroundColor Green
Write-Host ""
Write-Host "done. now:"
Write-Host "  1) quit SteamVR completely (vrserver / vrmonitor / vrdashboard / steamvr)"
Write-Host "  2) end OVRServer_x64.exe (Meta Horizon Link runtime) - it fights for the headset"
Write-Host "  3) start VR again from PICO Connect"
Write-Host ""
Write-Host "undo with: powershell -ExecutionPolicy Bypass -File $($MyInvocation.MyCommand.Path) -Restore"
