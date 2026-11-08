#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
# Overlay the PICO OpenXR loader shim onto installed PICO apps.
#
# PICO XR APKs link PICO's loader (libpico_openxr_loader.so, some builds name it
# libopenxr_loader.so) and expect PICO's runtime behind it. On Horizon OS that
# file is Meta's loader, which cannot satisfy PICO's assumptions (eye order, eye
# poses, interaction-profile paths, battery action). Repacking every APK is not
# desirable, so this module bind-mounts the shim over the already-installed copy
# inside the app's lib dir. The APK stays byte-identical.
#
# The shim dlopen()s libopenxr_loader_real.so (Meta's loader) from the same
# directory, and needs libc++ as well.

MODDIR=${0%/*}
FILES="$MODDIR/files"
LOG="$MODDIR/apply.log"

log() { echo "$(date +%H:%M:%S) $*" >> "$LOG"; }

SHIM="$FILES/libpico_openxr_loader.so"
[ -f "$SHIM" ] || { echo "shim missing" > "$LOG"; exit 1; }

: > "$LOG"
log "applying"

is_pico() {
    case "$1" in
        com.picovr.*|com.pico.*|com.picoxr.*|com.pvr.*|com.pxr.*|com.bytedance.pico.*) return 0 ;;
    esac
    return 1
}

count=0
for pkg in $(pm list packages 2>/dev/null | sed 's/^package://'); do
    is_pico "$pkg" || continue
    apk=$(pm path "$pkg" 2>/dev/null | head -1 | sed 's/^package://')
    [ -n "$apk" ] || continue
    dir=$(dirname "$apk")/lib/arm64
    [ -d "$dir" ] || continue

    hit=0
    for name in libpico_openxr_loader.so libopenxr_loader.so; do
        [ -f "$dir/$name" ] || continue
        if mount --bind "$SHIM" "$dir/$name" 2>/dev/null; then
            hit=1
        else
            log "bind $name failed for $pkg"
        fi
    done

    if [ "$hit" = "1" ]; then
        for extra in libopenxr_loader_real.so libc++.so; do
            src="$FILES/$extra"
            [ -f "$src" ] || continue
            dst="$dir/$extra"
            [ -f "$dst" ] || : > "$dst"
            mount --bind "$src" "$dst" 2>/dev/null || log "bind $extra failed for $pkg"
        done
        count=$((count + 1))
        log "overlaid $pkg"
    fi
done
log "done, $count package(s)"
