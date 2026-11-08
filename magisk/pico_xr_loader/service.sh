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
#
# Idempotent. A bind-mounted file reports the inode of its source, so comparing
# dev:inode of the target with the shim tells us whether the overlay is already
# in place. (Comparing the mount source path does not work: /proc/mounts reports
# the underlying block device for file binds.) Duplicate layers left by an older
# non-idempotent version are peeled off first.

MODDIR=${0%/*}
FILES="$MODDIR/files"
LOG="$MODDIR/apply.log"

log() { echo "$(date +%H:%M:%S) $*" >> "$LOG" 2>/dev/null; }

SHIM="$FILES/libpico_openxr_loader.so"
[ -f "$SHIM" ] || { echo "shim missing" > "$LOG" 2>/dev/null; exit 1; }
SHIM_ID=$(stat -c %d:%i "$SHIM" 2>/dev/null)

: > "$LOG" 2>/dev/null
log "applying"

is_pico() {
    case "$1" in
        com.picovr.*|com.pico.*|com.picoxr.*|com.pvr.*|com.pxr.*|com.bytedance.pico.*) return 0 ;;
    esac
    return 1
}

layers_of() {
    grep -c " $1 " /proc/mounts 2>/dev/null || echo 0
}

already_overlaid() {
    [ -n "$SHIM_ID" ] || return 1
    [ "$(stat -c %d:%i "$1" 2>/dev/null)" = "$SHIM_ID" ]
}

# Peel duplicate layers until at most one remains.
peel() {
    target="$1"
    guard=0
    while [ "$(layers_of "$target")" -gt 1 ] && [ "$guard" -lt 200 ]; do
        umount "$target" 2>/dev/null || umount -l "$target" 2>/dev/null || break
        guard=$((guard + 1))
    done
}

overlay_one() {
    target="$1"
    pkg="$2"
    peel "$target"
    if already_overlaid "$target"; then
        return 0
    fi
    if mount --bind "$SHIM" "$target" 2>/dev/null; then
        log "overlaid $pkg"
        return 0
    fi
    log "bind failed for $pkg"
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
        overlay_one "$dir/$name" "$pkg" && hit=1
    done

    if [ "$hit" = "1" ]; then
        for extra in libopenxr_loader_real.so libc++.so; do
            src="$FILES/$extra"
            [ -f "$src" ] || continue
            dst="$dir/$extra"
            [ -f "$dst" ] || : > "$dst"
            [ "$(layers_of "$dst")" -ge 1 ] && continue
            mount --bind "$src" "$dst" 2>/dev/null || log "bind $extra failed for $pkg"
        done
        count=$((count + 1))
    fi
done
log "done, $count package(s)"
