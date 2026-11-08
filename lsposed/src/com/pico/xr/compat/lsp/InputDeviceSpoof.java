// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the Horizon input devices look like PICO's to PICO Connect.
 *
 * DesktopGLSurfaceView.onTouchEvent() starts with a hard-coded whitelist:
 *
 *     int id = event.getDeviceId();
 *     if (id != 99999 && id != 100000 && id != 100001
 *             && id != 100002 && id != 100003 && id != 100004) {
 *         return true;              // consumed and silently ignored
 *     }
 *
 * Those ids are PICO's virtual pointer devices. Horizon OS reports the
 * controller ray as 0x100002 / 0x100004 (1048578 / 1048580), so every touch is
 * dropped before it reaches the code that forwards mouse events to the PC:
 * the cursor moves (handled elsewhere) but clicking does nothing.
 *
 * This hook maps the Horizon pointer range onto 100000 so the app processes the
 * events. persist.pico.input.deviceid=0 disables it.
 */
final class InputDeviceSpoof {
    private static final String TAG = "PicoInputDev";
    private static final int PICO_POINTER_ID = 100000;
    private static final int QUEST_POINTER_MIN = 0x100000;
    private static final int QUEST_POINTER_MAX = 0x10FFFF;
    private static boolean sInstalled;
    private static boolean sLogged;

    private InputDeviceSpoof() {
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return !"0".equals(get.invoke(null, "persist.pico.input.deviceid", "1"));
        } catch (Throwable t) {
            return true;
        }
    }

    static void install(ClassLoader cl) {
        if (sInstalled || !enabled()) {
            return;
        }
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.MotionEvent", cl, "getDeviceId", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (!(r instanceof Integer)) {
                            return;
                        }
                        int id = (Integer) r;
                        if (id >= QUEST_POINTER_MIN && id <= QUEST_POINTER_MAX) {
                            param.setResult(PICO_POINTER_ID);
                            if (!sLogged) {
                                sLogged = true;
                                Log.i(TAG, "pointer device " + id + " -> " + PICO_POINTER_ID);
                                XposedBridge.log(TAG + ": pointer device " + id
                                    + " -> " + PICO_POINTER_ID);
                            }
                        }
                    }
                });
            Log.i(TAG, "input device spoof installed");
        } catch (Throwable t) {
            Log.i(TAG, "input device spoof failed: " + t);
        }
    }
}
