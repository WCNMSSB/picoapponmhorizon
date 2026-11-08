# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Build the LSPosed module APK.
#
# Prerequisites:
#   * JDK 8+ on PATH (javac, jar, keytool)
#   * Android SDK with build-tools 34.0.0 and platform 34
#       set ANDROID_SDK_ROOT, or pass -AndroidSdk
#   * Xposed API jar (api-82.jar from the LSPosed/Xposed project)
#       set XPosed_API, or pass -XposedApi
#
#   powershell -ExecutionPolicy Bypass -File lsposed\build_apk.ps1
#
# Output: dist/pico-xr-lsp.apk
param(
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [string]$XposedApi  = $env:XPosed_API,
    [string]$Keystore   = "",
    [string]$Out        = "dist/pico-xr-lsp.apk"
)
$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (-not $AndroidSdk) { throw "set ANDROID_SDK_ROOT or pass -AndroidSdk" }
if (-not $XposedApi)  { throw "set XPosed_API or pass -XposedApi (path to api-82.jar)" }

$Platform = Join-Path $AndroidSdk "platforms\android-34\android.jar"
$Bt       = Join-Path $AndroidSdk "build-tools\34.0.0"
$Src      = Join-Path $Repo "lsposed\src"
$Manifest = Join-Path $Repo "lsposed\AndroidManifest.xml"
$Assets   = Join-Path $Repo "lsposed\assets"
$Res      = Join-Path $Repo "lsposed\res"
$Cls      = Join-Path $Repo "lsposed\build\classes"
$Dex      = Join-Path $Repo "lsposed\build\dex"
$Work     = Join-Path $Repo "lsposed\build"
$Dist     = Join-Path $Repo $Out

foreach ($p in @($Platform, $XposedApi, $Manifest, (Join-Path $Assets "xposed_init"))) {
    if (-not (Test-Path $p)) { throw "missing prerequisite: $p" }
}
if (-not $Keystore) { $Keystore = Join-Path $Work "debug.keystore" }
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force (Split-Path $Keystore) | Out-Null
    & keytool -genkeypair -keystore $Keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}

Remove-Item -Recurse -Force $Cls, $Dex -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Cls, $Dex, $Work | Out-Null

$javaFiles = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 8 -nowarn -encoding UTF-8 -cp "$Platform;$XposedApi" -d $Cls $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classJar = Join-Path $Work "classes.jar"
Remove-Item -Force $classJar -ErrorAction SilentlyContinue
& jar cf $classJar -C $Cls .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& (Join-Path $Bt "d8.bat") --lib $Platform --classpath $XposedApi --min-api 29 --output $Dex $classJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$unsigned = Join-Path $Work "base.apk"
Remove-Item -Force $unsigned -ErrorAction SilentlyContinue
$resZip = Join-Path $Work "res.zip"
Remove-Item -Force $resZip -ErrorAction SilentlyContinue
if (Test-Path $Res) {
    & (Join-Path $Bt "aapt2.exe") compile --dir $Res -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
}
& (Join-Path $Bt "aapt2.exe") link -o $unsigned -I $Platform `
    --manifest $Manifest -A $Assets $(if (Test-Path $resZip) { $resZip }) --min-sdk-version 29 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# Optional native payloads: the in-APK router. The module is fully functional
# without them (pure Java hooks); they only matter for persist.pico.injector=1.
$payload = @()
foreach ($pair in @(
        @{ Src = "lsposed\lib\arm64-v8a\libpico_xr_inject.so";   Dst = "lib/arm64-v8a/libpico_xr_inject.so" },
        @{ Src = "lsposed\lib\arm64-v8a\libpico_meta_bridge.so"; Dst = "lib/arm64-v8a/libpico_meta_bridge.so" })) {
    $full = Join-Path $Repo $pair.Src
    if (Test-Path $full) { $payload += "$full=$($pair.Dst)" }
}

@"
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    for pair in sys.argv[3:]:
        src, dst = pair.split('=', 1)
        z.write(src, dst)
print('added', dex, 'and', len(sys.argv) - 3, 'native lib(s)')
"@ | Set-Content -Encoding UTF8 (Join-Path $Work "_add.py")
& python (Join-Path $Work "_add.py") $unsigned (Join-Path $Dex "classes.dex") @payload
if ($LASTEXITCODE -ne 0) { throw "adding payload failed" }

$aligned = Join-Path $Work "aligned.apk"
Remove-Item -Force $aligned -ErrorAction SilentlyContinue
& (Join-Path $Bt "zipalign.exe") -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

New-Item -ItemType Directory -Force (Split-Path $Dist) | Out-Null
Remove-Item -Force $Dist -ErrorAction SilentlyContinue
& (Join-Path $Bt "apksigner.bat") sign --ks $Keystore --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey --out $Dist $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ("built {0} ({1:N0} bytes)" -f $Dist, (Get-Item $Dist).Length) -ForegroundColor Green
