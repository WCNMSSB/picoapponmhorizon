// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Swallows loads of PICO system libraries that cannot exist on Horizon OS.
 *
 * android.resourcemanager.Rm (PICO's resource manager) runs
 * System.loadLibrary("rms_jni") in its static initialiser. That library wants
 * libcutils/libbinder/libandroid_runtime and friends, which are Android private
 * libs outside the app's public-library namespace, so the load fails and takes
 * the whole process down before Connect finishes starting.
 *
 * Skipping the load lets the Java side come up; anything that actually needs the
 * native implementation will fail later, but by then the failure is local.
 *
 * Only installed in com.picovr.picostreamassistant.
 */
final class NativeLibCompat {
    private static final String TAG = "PicoNative";

    private static final String[] BLOCKED = {"rms_jni"};

    private static boolean sInstalled;

    private NativeLibCompat() {
    }

    static void install(ClassLoader cl, String pkg) {
        if (sInstalled || pkg == null || !pkg.startsWith("com.picovr.picostreamassistant")) {
            return;
        }
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(System.class, "loadLibrary",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            for (String blocked : BLOCKED) {
                                if (blocked.equals(name)) {
                                    param.setResult(null);
                                    Log.i(TAG, "skip loadLibrary(" + name + ")");
                                    return;
                                }
                            }
                        }
                    });
            log("native lib gate installed");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
