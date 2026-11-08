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
 * Makes Horizon's shell treat selected PICO activities as VR applications,
 * without touching their manifests.
 *
 * com.oculus.vrshell decides whether a package/activity is a VR app in an
 * obfuscated helper (X.007.A06 / X.007.A08 on this build). The decision reads
 * the activity categories:
 *
 *   com.oculus.intent.category.2D   -> panel app
 *   com.oculus.intent.category.VR   -> immersive
 *   org.khronos.openxr.intent.category.IMMERSIVE_HMD -> immersive
 *
 * PICO apps declare none of these (PICO's own shell recognises them another
 * way), so on Horizon OS they open in a 2D panel and can never create an XR
 * session.
 *
 * Only activities listed in persist.pico.vr.activities are forced, because
 * forcing an entire package breaks its 2D screens: PICO Connect's pairing /
 * library UI and the PICO Store are normal 2D panels and must stay panels.
 *
 * Installed only in com.oculus.vrshell. persist.pico.vrshell.forcevr=0 disables.
 */
final class ForceVrHook {
    private static final String TAG = "PicoForceVr";

    private static final String DEFAULT_ACTIVITIES =
        "com.picoxr.streaming.deviceservice.StreamingGameActivity";

    private static boolean sInstalled;
    private static int sLogged;
    private static String sActivities;
    private static String[] sActivityList;
    private static String sPackages;
    private static String[] sPackageList;

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

    /**
     * Rules come from /data/local/tmp/pico_xr_rules.conf ("P pkg" / "A pkg/class"
     * per line). System properties cannot be used: a property value is capped at
     * 92 bytes and one pkg/activity entry already exceeds that.
     */
    private static String[] rules(String prefix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String text = RuleStore.readAll();
        if (text != null) {
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.length() > 2 && line.startsWith(prefix + " ")) {
                    out.add(line.substring(2).trim());
                }
            }
        } else {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class, String.class);
                String key = "P ".equals(prefix) ? "persist.pico.vr.packages"
                                                 : "persist.pico.vr.activities";
                String value = (String) get.invoke(null, key, "");
                if (value != null && !value.isEmpty()) {
                    for (String s : value.split(",")) {
                        if (!s.trim().isEmpty()) {
                            out.add(s.trim());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out.toArray(new String[0]);
    }

    private static String[] packages() {
        return rules("P");
    }

    private static String[] activities() {
        return rules("A");
    }

    /**
     * Rules may be written as "activity.Class" or "pkg/activity.Class". The shell
     * passes whatever form it has, so compare on the class part and, when the
     * rule carries a package, require it to match too.
     */
    private static boolean isListedPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String entry : packages()) {
            if (entry.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isListedActivity(String pkg, String cls) {
        if (cls == null) {
            return false;
        }
        for (String entry : activities()) {
            if (entry.isEmpty()) {
                continue;
            }
            String entryPkg = null;
            String entryCls = entry;
            int slash = entry.indexOf('/');
            if (slash > 0) {
                entryPkg = entry.substring(0, slash);
                entryCls = entry.substring(slash + 1);
            }
            if (entryPkg != null && pkg != null && !entryPkg.equals(pkg)) {
                continue;
            }
            if (cls.equals(entryCls)) {
                return true;
            }
            if (cls.endsWith("/" + entryCls)) {
                return true;
            }
            if (entryCls.startsWith(".") ? cls.endsWith(entryCls) : cls.endsWith("." + entryCls)) {
                return true;
            }
            if (cls.equals("." + entryCls)) {
                return true;
            }
        }
        return false;
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
                // A08(PackageManager, String category, String pkg, String cls) -> boolean
                boolean four = "A08".equals(name) && params.length == 4
                        && params[1] == String.class && m.getReturnType() == boolean.class;
                if (!four) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof Boolean) || (Boolean) res) {
                            return;
                        }
                        String category = (String) param.args[1];
                        if (!"com.oculus.intent.category.VR".equals(category)) {
                            return;
                        }
                        String pkg = (String) param.args[2];
                        String cls = (String) param.args[3];
                        if (!isListedActivity(pkg, cls) && !isListedPackage(pkg)) {
                            return;
                        }
                        param.setResult(Boolean.TRUE);
                        if (sLogged < 10) {
                            ++sLogged;
                            log("force VR: " + cls);
                        }
                    }
                });
                ++hooked;
            }
            log("shell VR decision hooked (" + hooked + " method(s), "
                + activities().length + " activity rule(s), "
                + packages().length + " package rule(s))");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }
}
