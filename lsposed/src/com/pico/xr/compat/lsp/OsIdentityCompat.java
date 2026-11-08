// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import android.app.Application;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Applies the full PICO device identity to PICO apps, not just the serial.
 *
 * PICO's account backend checks more than Build.getSerial(): brand, model,
 * device, fingerprint and the PICO OS version all travel with the login request.
 * With only the serial spoofed the app still reports "Quest 3 / oculus / eureka /
 * Android 14", and after a device-identity change the backend started answering
 * the login with -100007.
 *
 * PicoOsSpoof already knows how to rewrite those fields from assets/
 * picoos.properties; it used to run only on the native-router path, which is
 * disabled. This hook runs it whenever a PICO app's Application is created.
 *
 * persist.pico.osspoof=0 disables it.
 */
final class OsIdentityCompat {
    private static final String TAG = "PicoOsId";

    private static String mModulePath;

    private OsIdentityCompat() {
    }

    static void setModulePath(String path) {
        mModulePath = path;
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return !"0".equals(get.invoke(null, "persist.pico.osspoof", "1"));
        } catch (Throwable t) {
            return true;
        }
    }

    static void install(ClassLoader cl, String modulePath) {
        mModulePath = modulePath;
        if (!enabled()) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", cl, "callApplicationOnCreate",
                Application.class, new AppCreated());
            Log.i(TAG, "os identity hook installed");
        } catch (Throwable t) {
            Log.i(TAG, "install failed: " + t);
        }
    }

    /** Runs once per process, right after Application.onCreate(). */
    private static final class AppCreated extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object app = param.args[0];
                if (!(app instanceof Application)) {
                    return;
                }
                boolean ok = PicoOsSpoof.install((Application) app,
                        ((Application) app).getClassLoader(), mModulePath);
                Log.i(TAG, "pico identity applied=" + ok);
                XposedBridge.log(TAG + ": identity applied=" + ok);
            } catch (Throwable t) {
                Log.i(TAG, "apply failed: " + t);
            }
        }
    }
}
