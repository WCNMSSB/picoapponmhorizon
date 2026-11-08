// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.app.Activity;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Turns touch DOWN/UP into the mouse button events PICO Connect expects.
 *
 * PICO Connect's desktop view reads mouse input in onGenericMotionEvent():
 * ACTION_HOVER_MOVE for the cursor and ACTION_BUTTON_PRESS / ACTION_BUTTON_RELEASE
 * for clicks (it checks isFromSource(SOURCE_MOUSE) and getButtonState()).
 *
 * Horizon OS delivers the controller ray as a touch stream instead:
 * ACTION_DOWN / ACTION_UP with source SOURCE_MOUSE and buttonState BUTTON_PRIMARY.
 * The cursor therefore moves but a click is never seen.
 *
 * This hook forwards a synthesized ACTION_BUTTON_PRESS/RELEASE through
 * dispatchGenericMotionEvent() just before the original DOWN/UP is processed.
 *
 * Enabled by default; persist.pico.touch.mousebtn=0 disables it.
 */
final class MouseButtonCompat {
    private static final String TAG = "PicoMouseBtn";
    private static boolean sInstalled;
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<>();

    private MouseButtonCompat() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return !"0".equals(get.invoke(null, "persist.pico.touch.mousebtn", "1"));
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
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.app.Activity", cl),
                "dispatchTouchEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(sBusy.get())) {
                            return;
                        }
                        Object self = param.thisObject;
                        if (!(self instanceof Activity) || param.args.length != 1
                                || !(param.args[0] instanceof MotionEvent)) {
                            return;
                        }
                        MotionEvent e = (MotionEvent) param.args[0];
                        int action = e.getActionMasked();
                        if (action != MotionEvent.ACTION_DOWN
                                && action != MotionEvent.ACTION_UP) {
                            return;
                        }
                        boolean down = action == MotionEvent.ACTION_DOWN;
                        Activity activity = (Activity) self;

                        try {
                            int button = down ? MotionEvent.BUTTON_PRIMARY : 0;
                            MotionEvent ev = obtainWithButton(e, down, button);
                            setHiddenInt(ev, "setActionButton", button);

                            sBusy.set(Boolean.TRUE);
                            boolean handled = false;
                            try {
                                handled = activity.dispatchGenericMotionEvent(ev);
                            } catch (Throwable ignored) {
                            }
                            if (!handled && activity.getWindow() != null) {
                                try {
                                    android.view.View decor = activity.getWindow().getDecorView();
                                    handled = decor.dispatchGenericMotionEvent(ev);
                                } catch (Throwable ignored) {
                                }
                            }
                            log("BUTTON_" + (down ? "PRESS" : "RELEASE")
                                + " handled=" + handled + " btn=" + button
                                + " x=" + (int) e.getX() + " y=" + (int) e.getY());
                            ev.recycle();
                        } catch (Throwable t) {
                            log("synthesize failed: " + t);
                        } finally {
                            sBusy.set(Boolean.FALSE);
                        }
                    }
                });
            log("mouse button compat installed");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    /**
     * Obtain a copy of {@code src} carrying the mouse button state. The public
     * MotionEvent.obtain overloads cannot set buttonState, and the 13-argument
     * one is hidden, so it is called reflectively (the process already runs with
     * hidden-API exemptions). Falls back to setButtonState on the copy.
     */
    private static MotionEvent obtainWithButton(MotionEvent src, boolean down, int button) {
        try {
            Method m = MotionEvent.class.getDeclaredMethod("obtain",
                long.class, long.class, int.class, float.class, float.class,
                int.class, int.class, float.class, float.class,
                int.class, int.class, int.class, int.class);
            m.setAccessible(true);
            Object ev = m.invoke(null, src.getDownTime(), src.getEventTime(),
                down ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE,
                src.getX(), src.getY(), src.getMetaState(), button,
                src.getXPrecision(), src.getYPrecision(), src.getDeviceId(),
                src.getEdgeFlags(), InputDevice.SOURCE_MOUSE, src.getFlags());
            if (ev instanceof MotionEvent) {
                setHiddenInt((MotionEvent) ev, "setButtonState", button);
                return (MotionEvent) ev;
            }
        } catch (Throwable ignored) {
        }
        MotionEvent ev = MotionEvent.obtain(src);
        ev.setAction(down ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        setHiddenInt(ev, "setButtonState", button);
        return ev;
    }
    private static void setHiddenInt(MotionEvent ev, String method, int value) {
        try {
            Method m = MotionEvent.class.getMethod(method, int.class);
            m.invoke(ev, value);
        } catch (Throwable ignored) {
            // hidden API or absent on this API level; the action alone may suffice
        }
    }
}
