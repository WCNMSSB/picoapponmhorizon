// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Per-process PICO OS identity.
 *
 * PICO titles commonly gate features on {@code ro.build.display.id} (PICO's OS
 * version, 5.14.4 on P4U), {@code Build.BRAND}/{@code MODEL}, and the
 * {@code ro.pico.*}/{@code ro.pvr.*} properties. On Quest those are Horizon OS
 * values, so an otherwise portable title either refuses to start or takes a
 * degraded path.
 *
 * The table comes from the P4U 5.14.4 images (see assets/picoos.properties) and
 * is applied ONLY inside a process that was routed to the PICO lane. Nothing is
 * written to a system property: values are substituted at read time, so Quest
 * apps in other processes are unaffected.
 *
 * Read paths covered:
 *   - android.os.SystemProperties.get/getInt/getLong/getBoolean  (Java callers)
 *   - android.os.Build static fields (BRAND/MODEL/... and VERSION.*)
 *
 * Not covered (deliberately): native {@code __system_property_get}. PxrApi only
 * reads one property natively ({@code persist.pvr.mrc_enable}, covered here for
 * Java callers) so a libc hook is not needed yet.
 */
final class PicoOsSpoof {
    private static final String TAG = "PicoOsSpoof";
    private static final String ASSET = "picoos.properties";

    private static final Map<String, String> PROPS = new HashMap<>();
    private static final Set<String> SEEN = new HashSet<>();
    private static boolean sInstalled;

    private PicoOsSpoof() {
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    static boolean install(Application app, ClassLoader cl) {
        if (sInstalled) {
            return true;
        }
        try {
            InputStream in = app.getAssets().open(ASSET);
            Properties p = new Properties();
            try {
                p.load(in);
            } finally {
                in.close();
            }
            for (String name : p.stringPropertyNames()) {
                PROPS.put(name, p.getProperty(name));
            }
        } catch (Throwable t) {
            log("cannot load " + ASSET + ": " + t);
            return false;
        }
        if (PROPS.isEmpty()) {
            log("empty property table");
            return false;
        }
        installSystemPropertyHooks(cl);
        applyBuildFields();
        sInstalled = true;
        log("installed " + PROPS.size() + " properties for " + app.getPackageName());
        return true;
    }

    private static String spoof(String key) {
        return key == null ? null : PROPS.get(key);
    }

    /** Log reads we did not cover, so the table can be extended from real data. */
    private static void note(String key) {
        if (key == null || !SEEN.add(key)) {
            return;
        }
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (k.contains("pico") || k.contains("pvr") || k.contains("sparrow")
                || k.contains("display.id") || k.contains("product.model")
                || k.contains("product.brand")) {
            log("unspoofed read: " + key);
        }
    }

    private static void installSystemPropertyHooks(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String v = spoof((String) param.args[0]);
                            if (v != null) {
                                param.setResult(v);
                            } else {
                                note((String) param.args[0]);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String v = spoof((String) param.args[0]);
                            if (v != null) {
                                param.setResult(v);
                            } else {
                                note((String) param.args[0]);
                            }
                        }
                    });
            hookInt(cl, "getInt");
            hookLong(cl, "getLong");
            hookBoolean(cl, "getBoolean");
        } catch (Throwable t) {
            log("SystemProperties hook failed: " + t);
        }
    }

    private static void hookInt(ClassLoader cl, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    method, String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String v = spoof((String) param.args[0]);
                            if (v != null) {
                                try {
                                    param.setResult(Integer.parseInt(v.trim()));
                                } catch (NumberFormatException ignored) {
                                    // not an int: let the real value through
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            log(method + " hook failed: " + t);
        }
    }

    private static void hookLong(ClassLoader cl, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    method, String.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String v = spoof((String) param.args[0]);
                            if (v != null) {
                                try {
                                    param.setResult(Long.parseLong(v.trim()));
                                } catch (NumberFormatException ignored) {
                                    // not a long
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            log(method + " hook failed: " + t);
        }
    }

    private static void hookBoolean(ClassLoader cl, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    method, String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String v = spoof((String) param.args[0]);
                            if (v != null) {
                                String t = v.trim();
                                param.setResult("1".equals(t) || "true".equalsIgnoreCase(t)
                                        || "y".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t));
                            }
                        }
                    });
        } catch (Throwable t) {
            log(method + " hook failed: " + t);
        }
    }

    private static void setField(Class<?> cls, String name, String value) {
        if (value == null) {
            // Not in the per-process table: either handled by system.prop or
            // deliberately left alone. Keep the real value.
            return;
        }
        try {
            XposedHelpers.setStaticObjectField(cls, name, value);
        } catch (Throwable t) {
            log("cannot set " + cls.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void applyBuildFields() {
        setField(Build.class, "BRAND", PROPS.get("ro.product.brand"));
        setField(Build.class, "MANUFACTURER", PROPS.get("ro.product.manufacturer"));
        setField(Build.class, "MODEL", PROPS.get("ro.product.model"));
        setField(Build.class, "DEVICE", PROPS.get("ro.product.device"));
        setField(Build.class, "PRODUCT", PROPS.get("ro.product.name"));
        setField(Build.class, "BOARD", PROPS.get("ro.product.board"));
        setField(Build.class, "FINGERPRINT", PROPS.get("ro.build.fingerprint"));
        setField(Build.class, "ID", PROPS.get("ro.build.id"));
        setField(Build.class, "DISPLAY", PROPS.get("ro.build.display.id"));
        setField(Build.class, "TYPE", PROPS.get("ro.build.type"));
        setField(Build.class, "TAGS", PROPS.get("ro.build.tags"));
        setField(Build.class, "FLAVOR", PROPS.get("ro.build.flavor"));
        setField(Build.class, "HOST", PROPS.get("ro.build.host"));
        setField(Build.class, "USER", PROPS.get("ro.build.user"));
        try {
            setField(Build.VERSION.class, "INCREMENTAL",
                    PROPS.get("ro.build.version.incremental"));
            setField(Build.VERSION.class, "SECURITY_PATCH",
                    PROPS.get("ro.build.version.security_patch"));
            setField(Build.VERSION.class, "RELEASE",
                    PROPS.get("ro.build.version.release"));
        } catch (Throwable t) {
            log("Build.VERSION fields failed: " + t);
        }
    }
}
