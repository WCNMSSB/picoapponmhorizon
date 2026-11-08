// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.util.Log;
import android.view.MotionEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Logs the touch stream PICO Connect receives, so a "cursor moves but clicking
 * does nothing" symptom can be traced to the event level.
 *
 * Disabled unless persist.pico.debug.touch=1. Never changes behaviour.
 */
final class TouchDebug {
    private static final String TAG = "PicoTouch";
    private static boolean sInstalled;

    private TouchDebug() {
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return "1".equals(get.invoke(null, "persist.pico.debug.touch", "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void install(ClassLoader cl) {
        if (sInstalled || !enabled()) {
            return;
        }
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "dispatchTouchEvent",
                MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        MotionEvent e = (MotionEvent) param.args[0];
                        if (e == null) {
                            return;
                        }
                        Log.i(TAG, "action=" + e.getActionMasked()
                            + " x=" + (int) e.getX() + " y=" + (int) e.getY()
                            + " src=0x" + Integer.toHexString(e.getSource())
                            + " tool=" + e.getToolType(0)
                            + " dev=" + e.getDeviceId()
                            + " btn=0x" + Integer.toHexString(e.getButtonState())
                            + " edge=" + e.getEdgeFlags());
                    }
                });
            Log.i(TAG, "touch debug installed");

            /*
             * Which View actually consumes the touch, and what does it return?
             * PICO Connect's desktop surface handles input somewhere in the view
             * tree; without this the click goes missing between Activity and
             * native code.
             */
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.view.View", cl), "dispatchTouchEvent",
                new XC_MethodHook() {
                    private int n;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        MotionEvent e = (MotionEvent) param.args[0];
                        if (e == null || n > 60) {
                            return;
                        }
                        int a = e.getActionMasked();
                        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP) {
                            return;
                        }
                        ++n;
                        Log.i(TAG, "view " + param.thisObject.getClass().getName()
                            + " action=" + a + " handled=" + param.getResult()
                            + " btn=0x" + Integer.toHexString(e.getButtonState()));
                    }
                });
            Log.i(TAG, "view touch probe installed");
        } catch (Throwable t) {
            Log.i(TAG, "touch debug failed: " + t);
        }
    }
}