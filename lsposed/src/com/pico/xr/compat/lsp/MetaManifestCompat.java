// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes Meta's OpenXR runtime see the manifest entries a PICO APK does not carry.
 *
 * Meta gates extensions on the *caller's manifest*: before handing out
 * XR_EXT_eye_gaze_interaction it looks for
 * "horizonos.permission.EYE_TRACKING" in com.picovr.picostreamassistant, which
 * declares only the PICO name "com.picovr.permission.EYE_TRACKING". The
 * extension then disappears from xrEnumerateInstanceExtensionProperties, but
 * PICO Connect requests it unconditionally, so xrCreateInstance fails with -9
 * (XR_ERROR_EXTENSION_NOT_PRESENT) and the VR renderer aborts.
 *
 * Adding entries to the binary manifest is not practical, so the package
 * queries the runtime performs inside this process are answered with the Meta
 * names added.
 */
final class MetaManifestCompat {
    private static final String TAG = "PicoMetaManifest";

    private static final String[] EXTRA_PERMISSIONS = {
        "horizonos.permission.EYE_TRACKING",
        "horizonos.permission.USE_ANCHOR_API",
        "horizonos.permission.USE_SCENE",
    };

    private static final String[] EXTRA_FEATURES = {
        "oculus.software.eye_tracking",
        "oculus.software.face_tracking",
        "com.oculus.software.body_tracking",
        "com.oculus.feature.PASSTHROUGH",
        "com.oculus.feature.RENDER_MODEL",
    };

    private static boolean sInstalled;

    private MetaManifestCompat() {
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

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager", cl, "checkPermission",
                String.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String perm = (String) param.args[0];
                        if (isExtraPermission(perm)) {
                            param.setResult(PackageManager.PERMISSION_GRANTED);
                        }
                    }
                });
            log("checkPermission hook installed");
        } catch (Throwable t) {
            log("checkPermission hook failed: " + t);
        }

        /*
         * VrDriver's extension gate does not go through ApplicationPackageManager:
         * it calls IPackageManager through its own binder proxy, so the client
         * proxy inside this process is the layer that actually has to answer.
         */
        try {
            Class<?> proxy = XposedHelpers.findClass(
                "android.content.pm.IPackageManager$Stub$Proxy", cl);
            XposedBridge.hookAllMethods(proxy, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof PackageInfo) {
                        patch((PackageInfo) result);
                        logOnce("ipm:getPackageInfo patched");
                    }
                }
            });
            XposedBridge.hookAllMethods(proxy, "checkPermission", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg instanceof String && isExtraPermission((String) arg)) {
                            param.setResult(PackageManager.PERMISSION_GRANTED);
                            logOnce("ipm:checkPermission granted " + arg);
                            return;
                        }
                    }
                }
            });
            log("IPackageManager proxy hooks installed");
        } catch (Throwable t) {
            log("IPackageManager proxy hooks failed: " + t);
        }

        for (String method : new String[]{"getPackageInfo"}) {
            hookPackageInfo(cl, method, new Class<?>[]{String.class, int.class});
            try {
                hookPackageInfo(cl, method, new Class<?>[]{String.class,
                    PackageManager.PackageInfoFlags.class});
            } catch (Throwable t) {
                // PackageInfoFlags only exists on API 33+
            }
        }
    }

    private static void hookPackageInfo(ClassLoader cl, String method, Class<?>[] args) {
        try {
            Object[] params = new Object[args.length + 1];
            System.arraycopy(args, 0, params, 0, args.length);
            params[args.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof PackageInfo) {
                        patch((PackageInfo) result);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager",
                cl, method, params);
            log(method + "/" + args.length + " hook installed");
        } catch (Throwable t) {
            log(method + "/" + args.length + " hook failed: " + t);
        }
    }

    private static void patch(PackageInfo pi) {
        try {
            pi.requestedPermissions = mergeStrings(pi.requestedPermissions,
                EXTRA_PERMISSIONS);
            pi.reqFeatures = mergeFeatures(pi.reqFeatures, EXTRA_FEATURES);
        } catch (Throwable t) {
            log("patch failed: " + t);
        }
    }

    private static String[] mergeStrings(String[] current, String[] extra) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (current != null) {
            java.util.Collections.addAll(set, current);
        }
        for (String e : extra) {
            set.add(e);
        }
        return set.toArray(new String[0]);
    }

    private static FeatureInfo[] mergeFeatures(FeatureInfo[] current, String[] extra) {
        java.util.List<FeatureInfo> list = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (current != null) {
            for (FeatureInfo fi : current) {
                if (fi != null) {
                    list.add(fi);
                    names.add(fi.name);
                }
            }
        }
        for (String name : extra) {
            if (names.add(name)) {
                FeatureInfo fi = new FeatureInfo();
                fi.name = name;
                fi.reqGlEsVersion = 0;
                fi.flags = 0;
                list.add(fi);
            }
        }
        return list.toArray(new FeatureInfo[0]);
    }

    private static final java.util.Set<String> sLogged = new java.util.HashSet<>();

    private static void logOnce(String msg) {
        if (sLogged.add(msg)) {
            log(msg);
        }
    }

    private static boolean isExtraPermission(String perm) {
        if (perm == null) {
            return false;
        }
        for (String p : EXTRA_PERMISSIONS) {
            if (p.equals(perm)) {
                return true;
            }
        }
        return false;
    }
}