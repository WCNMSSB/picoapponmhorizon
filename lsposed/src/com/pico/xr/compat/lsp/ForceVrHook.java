// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes Horizon's shell treat PICO packages as VR applications, without touching
 * their manifests.
 *
 * com.oculus.vrshell decides whether a package is a VR app in an obfuscated
 * helper (X.007.A06 / X.007.A08 on this build). The decision reads the activity
 * categories:
 *
 *   com.oculus.intent.category.2D   -> panel app
 *   com.oculus.intent.category.VR   -> immersive
 *   org.khronos.openxr.intent.category.IMMERSIVE_HMD -> immersive
 *
 * PICO apps declare none of these (PICO's own shell recognises them another
 * way), so on Horizon OS they open in a 2D panel and can never create an XR
 * session. Returning true for PICO packages restores the intended behaviour
 * without repacking the APKs.
 *
 * Installed only in com.oculus.vrshell. persist.pico.vrshell.forcevr=0 disables.
 */
final class ForceVrHook {
    private static final String TAG = "PicoForceVr";
    private static boolean sInstalled;
    private static int sLogged;

    private ForceVrHook() {
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return !"0".equals(get.invoke(null, "persist.pico.vrshell.forcevr", "1"));
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isPico(String pkg) {
        if (pkg == null) {
            return false;
        }
        return pkg.startsWith("com.picovr.")
            || pkg.startsWith("com.pico.")
            || pkg.startsWith("com.picoxr.")
            || pkg.startsWith("com.pvr.")
            || pkg.startsWith("com.pxr.")
            || pkg.startsWith("com.bytedance.pico.");
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    static void install(ClassLoader cl) {
        if (sInstalled || !enabled()) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> holder = XposedHelpers.findClass("X.007", cl);
            int hooked = 0;
            for (Method m : holder.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();
                // A06(PackageManager, String) -> boolean isVrApplication
                // A08(PackageManager, String category, String pkg, String cls) -> boolean
                boolean two = "A06".equals(name) && params.length == 2
                        && params[1] == String.class && m.getReturnType() == boolean.class;
                boolean four = "A08".equals(name) && params.length == 4
                        && params[1] == String.class && m.getReturnType() == boolean.class;
                if (!two && !four) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof Boolean) || (Boolean) res) {
                            return;
                        }
                        String pkg = param.args.length == 2
                            ? (String) param.args[1]
                            : (String) param.args[2];
                        if (!isPico(pkg)) {
                            return;
                        }
                        param.setResult(Boolean.TRUE);
                        if (sLogged < 10) {
                            ++sLogged;
                            log("force VR: " + pkg);
                        }
                    }
                });
                ++hooked;
            }
            log("shell VR decision hooked (" + hooked + " methods)");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }
}
