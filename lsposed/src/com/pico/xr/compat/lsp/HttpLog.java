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
 * Logs the HTTP requests PICO apps make, so it can be established whether the
 * headset reports itself (serial / model) to PICO's account backend.
 *
 * The phone app tells the user to "log into the same PICO account on the
 * headset to connect the device", i.e. the cloud learns about the headset during
 * login. The headset is logged in yet the phone app still lists no device, so
 * either the registration call never happens or the server rejects the spoofed
 * serial.
 *
 * Only the request line, headers and simple form bodies are logged; response
 * bodies are not touched. Enabled with persist.pico.http.log=1.
 */
final class HttpLog {
    private static final String TAG = "PicoHttp";
    private static boolean sInstalled;
    private static int sCount;

    private HttpLog() {
    }

    private static boolean enabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return "1".equals(get.invoke(null, "persist.pico.http.log", "0"));
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
            Class<?> builder = XposedHelpers.findClass("okhttp3.Request$Builder", cl);
            XposedBridge.hookAllMethods(builder, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sCount > 120) {
                        return;
                    }
                    Object req = param.getResult();
                    if (req == null) {
                        return;
                    }
                    try {
                        Object url = XposedHelpers.callMethod(req, "url");
                        Object method = XposedHelpers.callMethod(req, "method");
                        Object body = XposedHelpers.callMethod(req, "body");
                        StringBuilder sb = new StringBuilder();
                        sb.append(++sCount).append(' ')
                          .append(method).append(' ').append(url);
                        if (body != null) {
                            try {
                                Object ct = XposedHelpers.callMethod(body, "contentType");
                                sb.append(" body=").append(ct);
                            } catch (Throwable ignored) {
                            }
                            try {
                                // okhttp3.FormBody: log the pairs (serial numbers live here)
                                Object size = XposedHelpers.callMethod(body, "size");
                                if (size instanceof Integer) {
                                    int n = (Integer) size;
                                    for (int i = 0; i < n; ++i) {
                                        Object k = XposedHelpers.callMethod(body, "name", i);
                                        Object v = XposedHelpers.callMethod(body, "value", i);
                                        sb.append(' ').append(k).append('=').append(v);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        Log.i(TAG, sb.toString());
                    } catch (Throwable t) {
                        Log.i(TAG, "log failed: " + t);
                    }
                }
            });
            Log.i(TAG, "okhttp logging installed");
        } catch (Throwable t) {
            Log.i(TAG, "okhttp hook failed: " + t);
        }

        try {
            // Some requests bypass Retrofit and go through plain URL connections.
            XposedHelpers.findAndHookMethod(
                "java.net.URL", cl, "openConnection", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sCount > 200) {
                            return;
                        }
                        try {
                            Log.i(TAG, "url.openConnection " + param.thisObject);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        } catch (Throwable ignored) {
        }
    }
}
