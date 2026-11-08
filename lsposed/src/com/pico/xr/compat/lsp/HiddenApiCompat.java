// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Lifts ART hidden-API enforcement for the target process.
 *
 * PICO apps were system apps (uid 1000) and therefore exempt, so they freely
 * touch @hide framework members. com.picovr.picostreamassistant does it from
 * *native* code:
 *
 *   FindClass("com/google/android/gles_jni/EGLContextImpl")
 *   GetFieldID(..., "mEGLContext", "J")     // BLOCKED for normal apps
 *   GetLongField(...)                       // fid == null -> SIGABRT
 *
 * VMRuntime.setHiddenApiExemptions("L") disables the check for the whole
 * process, which also covers JNI lookups that consult the exemption list.
 */
final class HiddenApiCompat {
    private static final String TAG = "PicoHiddenApi";
    private static boolean sInstalled;

    private HiddenApiCompat() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    static void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            java.lang.reflect.Method set = vmRuntime.getMethod(
                "setHiddenApiExemptions", String[].class);
            set.invoke(runtime, (Object) new String[]{"L"});
            log("hidden API exemptions installed");
        } catch (Throwable t) {
            log("setHiddenApiExemptions failed: " + t);
        }
    }
}
