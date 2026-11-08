// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the PICO Motion Tracker app ("Swift", com.pvr.swift) believe the system
 * version is high enough.
 *
 * MainActivity.onCreate() asks SwiftRepo.isVersionSupported(1) and (2); when
 * both answer false it replaces the whole UI with the "system update required"
 * screen, so the trackers can never even be listed. The answer comes from
 * SwiftAdapter -> SwiftImpl, which compares ro.pui.build.version against
 * 0x226 (550 = "5.5.0") and reads ro.pxr.externalfunc; on Horizon OS neither
 * matches what the app expects.
 *
 * We report "supported" and let the app continue; whether trackers are actually
 * paired is a separate question the app handles itself.
 *
 * Installed only in com.pvr.swift. persist.pico.swift.unlock=0 disables.
 */
final class SwiftCompat {
    private static final String TAG = "PicoSwift";

    private static boolean sInstalled;

    private SwiftCompat() {
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return !"0".equals(get.invoke(null, "persist.pico.swift.unlock", "1"));
        } catch (Throwable t) {
            return true;
        }
    }

    static void install(ClassLoader cl, String pkg) {
        if (sInstalled || !enabled()) {
            return;
        }
        if (pkg == null || !pkg.startsWith("com.pvr.swift")) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> adapter = XposedHelpers.findClass("com.pvr.swift.sdk.SwiftAdapter", cl);

            // SwiftActivity picks the 1st- or 2nd-generation UI from this value:
            // anything other than 2 is routed to launchV1Home(). Without PICO's
            // tracker stack the SDK reports 1, so the app only ever offers the
            // first-generation flow. Default it to 2 (second generation) and let
            // persist.pico.swift.gen override it (1/2).
            XposedBridge.hookAllMethods(adapter, "getSwiftVersion", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(generation());
                    Log.i(TAG, "getSwiftVersion -> " + generation());
                }
            });

            XposedBridge.hookAllMethods(adapter, "isVersionSupported", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof Boolean && !(Boolean) param.getResult()) {
                        param.setResult(Boolean.TRUE);
                        Log.i(TAG, "isVersionSupported(" + param.args[0] + ") -> true");
                    }
                }
            });

            // FEAT_SWIFT lives in smartisanos.config.ProductConfig, a PICO OS
            // framework class that Horizon OS does not have, so the reflective
            // lookup marks the tracker unsupported. Force the feature flags on.
            try {
                Class<?> features = XposedHelpers.findClass("com.pvr.swift.feature.Features", cl);
                XposedBridge.hookAllMethods(features, "featureSupport", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                for (String name : new String[]{"swiftSupport", "isSwiftVersion"}) {
                    XposedBridge.hookAllMethods(features, name, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }

            // RomUtils answers the same question for feature gates.
            try {
                Class<?> rom = XposedHelpers.findClass("com.pvr.swift.util.RomUtils", cl);
                for (String name : new String[]{"is570RomOrHigher", "is590RomOrHigher"}) {
                    XposedBridge.hookAllMethods(rom, name, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.TRUE);
                        }
                    });
                }
                log("rom feature gates forced");
            } catch (Throwable ignored) {
            }

            // The generation-2 UI reads its state from PICO's configuration
            // service; on Horizon OS that service is absent, getConfig() returns
            // null and the fragments crash on Kotlin's non-null assertions
            // ("getWearMode() must not be null"). Answer with sane defaults.
            try {
                Class<?> utils = XposedHelpers.findClass("com.pvr.swift.utils.Utils", cl);
                XposedBridge.hookAllMethods(utils, "getConfig", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null) {
                            return;
                        }
                        param.setResult(defaultConfig((String) param.args[0]));
                    }
                });
            } catch (Throwable ignored) {
            }

            log("swift version gate hooked");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    /** Defaults for the settings the app would otherwise read back as null. */
    private static String defaultConfig(String key) {
        if (key == null) {
            return "";
        }
        if (key.contains("wear_mode") || key.contains("waist_position")) {
            return "1";
        }
        if (key.contains("gesture_enhancement")) {
            return "0";
        }
        if (key.contains("stillness_detection")) {
            return "0";
        }
        return "";
    }

    /** Tracker generation the app should present: 2 (second gen) unless overridden. */
    private static int generation() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, "persist.pico.swift.gen", "2");
            return "1".equals(v) ? 1 : 2;
        } catch (Throwable t) {
            return 2;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
