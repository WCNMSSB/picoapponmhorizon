# Deploy the LSPosed helper to the connected Quest and refresh LSPosed's cache.
#
# Two things bite here, both learned the hard way:
#
# 1. `adb install -r` moves the APK to a new /data/app path while LSPosed keeps
#    the old one in modules_config.db; it then silently skips the module.
# 2. modules_config.db is in WAL mode. Copying only the .db loses whatever is in
#    modules_config.db-wal, which is where fresh scope edits live -- that is how
#    a scope entry can look present in the file yet never take effect.
#
# Also: right after lspd is killed, /data/adb/lspd/config briefly rejects reads
# while the directory is recreated, so the copy retries.
param(
    [string]$Apk = "dist\pico-xr-lsp.apk",
    [string]$Pkg = "com.pico.xr.compat.lsp"
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
$pyExe = "C:\Users\77456\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if (-not (Test-Path $pyExe)) { $pyExe = "python" }

$apkPath = Join-Path $Root $Apk
Write-Host "== install $apkPath"
& $adb install -r $apkPath 2>&1 | Select-Object -Last 1

$cur = ((& $adb shell "pm path $Pkg") -replace 'package:', '').Trim()
Write-Host "== installed at $cur"
if (-not $cur) { throw "package not installed" }

$tmp = Join-Path $Root "build\lspd"
New-Item -ItemType Directory -Force $tmp | Out-Null
Get-ChildItem $tmp -Filter "now.db*" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

& $adb shell su -c "killall lspd" | Out-Null
Start-Sleep -Seconds 2

$copied = $false
$size = "0"
for ($i = 1; $i -le 6 -and -not $copied; $i++) {
    & $adb shell su -c "dd if=/data/adb/lspd/config/modules_config.db of=/sdcard/lspd_db_main bs=64k 2>/dev/null; dd if=/data/adb/lspd/config/modules_config.db-wal of=/sdcard/lspd_db_wal bs=64k 2>/dev/null; chmod 666 /sdcard/lspd_db_* 2>/dev/null" | Out-Null
    $size = ((& $adb shell "stat -c %s /sdcard/lspd_db_main 2>/dev/null") | Out-String).Trim()
    if ($size -match '^\d+$' -and [int]$size -gt 0) { $copied = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $copied) { throw "could not read modules_config.db (permission flapped 6 times)" }
Write-Host "== database copied ($size bytes)"

& $adb pull /sdcard/lspd_db_main "$tmp\now.db" 2>&1 | Out-Null
& $adb pull /sdcard/lspd_db_wal "$tmp\now.db-wal" 2>&1 | Out-Null

$py = Join-Path $tmp "patch_path.py"
@"
import os, sqlite3, sys
db, pkg, apk = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db)
cur = con.cursor()
cur.execute("update modules set apk_path=? where module_pkg_name=?", (apk, pkg))
if cur.rowcount == 0:
    cur.execute("insert into modules (module_pkg_name, apk_path) values (?,?)", (pkg, apk))
con.commit()
cur.execute("pragma wal_checkpoint(TRUNCATE)")
con.close()
out = db + ".final"
if os.path.exists(out):
    os.remove(out)
con = sqlite3.connect(db)
con.execute("vacuum into '%s'" % out)
con.close()
print("scope rows:", sqlite3.connect(out).execute(
    "select count(*) from scope where module_pkg_name=?", (pkg,)).fetchone()[0])
"@ | Set-Content -Encoding UTF8 $py
& $pyExe $py "$tmp\now.db" $Pkg $cur
if (-not (Test-Path "$tmp\now.db.final")) { throw "database merge failed" }

& $adb push "$tmp\now.db.final" /data/local/tmp/lspd_fixed.db 2>&1 | Out-Null
& $adb shell su -c "cp /data/local/tmp/lspd_fixed.db /data/adb/lspd/config/modules_config.db"
& $adb shell su -c "rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm"
& $adb shell su -c "chmod 600 /data/adb/lspd/config/modules_config.db"
& $adb shell su -c "sh /data/local/tmp/start_lspd.sh"
Start-Sleep -Seconds 3
$pids = (& $adb shell "pidof lspd").Trim()
Write-Host "== lspd pids: $pids"
