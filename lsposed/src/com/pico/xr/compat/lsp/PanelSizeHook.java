// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Optional launch-size override for PICO Connect's 2D panels.
 *
 * Meta's 2D porting guidance (hz-android-2d-porting / panel-layout) says panels
 * are user-resizable, the default is 1024x640 dp, and apps must not hard-code a
 * pixel size. Horizon OS therefore opens the panel at its own default, and the
 * user can drag the edges. That is the default behaviour here.
 *
 * Setting persist.pico.panel.enforce=1 re-enables the old forced launch size,
 * which is useful only if a particular PICO activity insists on a fixed layout.
 * The two sizes are given in dp and converted with the panel density:
 *
 *   persist.pico.panel.main     default 1024x640   (connection screen)
 *   persist.pico.panel.desktop  default 1024x640   (desktop stream)
 */
final class PanelSizeHook {
    private static final String TAG = "PicoPanelSize";
    private static final String PKG = "com.picovr.picostreamassistant";
    private static boolean sInstalled;

    private PanelSizeHook() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        if (!"1".equals(prop("persist.pico.panel.enforce", "0"))) {
            log("panel size override disabled (persist.pico.panel.enforce=0)");
            return;
        }
        try {
            Class<?> impl = XposedHelpers.findClass("android.app.ContextImpl", cl);
            XposedBridge.hookAllMethods(impl, "startActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    apply(param);
                }
            });
            log("ContextImpl.startActivity hook installed");

            /*
             * Activity.startActivity()/startActivityForResult() bypass ContextImpl
             * and funnel through Instrumentation.execStartActivity(), which is
             * what PICO Connect actually uses to open DesktopActivity.
             */
            Class<?> instr = XposedHelpers.findClass(
                "android.app.Instrumentation", cl);
            XposedBridge.hookAllMethods(instr, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    apply(param);
                }
            });
            log("Instrumentation.execStartActivity hook installed");
        } catch (Throwable t) {
            log("hook failed: " + t);
        }
    }

    private static void apply(XC_MethodHook.MethodHookParam param) {
        try {
            Intent intent = null;
            int bundleIndex = -1;
            for (int i = 0; i < param.args.length; i++) {
                if (param.args[i] instanceof Intent) {
                    intent = (Intent) param.args[i];
                } else if (param.args[i] instanceof Bundle) {
                    bundleIndex = i;
                }
            }
            if (intent == null) {
                return;
            }
            ComponentName cn = intent.getComponent();
            if (cn == null || !PKG.equals(cn.getPackageName())) {
                return;
            }
            String activity = cn.getClassName();
            String size;
            if (activity.endsWith("DesktopActivity")) {
                size = prop("persist.pico.panel.desktop", "1024x640");
            } else if (activity.endsWith("MainActivity")) {
                size = prop("persist.pico.panel.main", "1024x640");
            } else {
                return;
            }
            if (size == null || size.isEmpty()) {
                return;
            }
            String[] wh = size.toLowerCase().split("x");
            float density = 2.0f;
            try {
                density = Resources.getSystem().getDisplayMetrics().density;
            } catch (Throwable ignored) {
            }
            int w = Math.round(Integer.parseInt(wh[0].trim()) * density);
            int h = Math.round(Integer.parseInt(wh[1].trim()) * density);
            if (w <= 0 || h <= 0) {
                return;
            }
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(new Rect(0, 0, w, h));
            Bundle out = options.toBundle();
            if (bundleIndex >= 0 && param.args[bundleIndex] instanceof Bundle) {
                out.putAll((Bundle) param.args[bundleIndex]);
            }
            if (bundleIndex >= 0) {
                param.args[bundleIndex] = out;
            }
            log("panel " + activity + " -> " + w + "x" + h + " px");
        } catch (Throwable t) {
            log("apply failed: " + t);
        }
    }

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get =
                sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
