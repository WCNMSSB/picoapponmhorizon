// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.os.Build;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes {@code Build.getSerial()} work for apps that were system apps on PICO.
 *
 * PICO's own apps declare {@code android:sharedUserId="android.uid.system"} and
 * therefore can call {@code Build.getSerial()} without holding
 * READ_PRIVILEGED_PHONE_STATE. Installed on Quest as an ordinary /data app they
 * get uid 10xxx, so the call throws:
 *
 *   java.lang.SecurityException: getSerial: The uid 10203 does not meet the
 *   requirements to access device identifiers.
 *
 * which surfaces as ExceptionInInitializerError in a static initialiser and
 * kills the process at launch (seen with com.picovr.picostreamassistant 10.4.5,
 * DeviceUtil.<clinit>).
 *
 * Only the identifier is faked; no permission check is bypassed globally.
 */
final class SerialSpoof {
    private static final String TAG = "PicoSerial";
    /**
     * PICO encodes the model in the serial number. PICO Connect derives its
     * device type in DeviceUtil.c():
     *
     *   serial.charAt(1) != 'A'            -> UNSUPPORTED_DEVICE
     *   serial.charAt(2) == '9'            -> PICO4S_SERIES  ("PICO 4 Ultra")
     *   serial.charAt(2) == '8' && char3 in S..U or 'Z' -> PICO4S_SERIES
     *   serial.charAt(2) == '8' && char3 == 'A'         -> PICO4_PRO_SERIES
     *   serial.charAt(2) == '8' otherwise               -> PICO4_SERIES
     *   serial.charAt(2) == '7' && char3 in H..Y        -> NEO3_SERIES
     *
     * Anything else (including a readable "PICO4ULTRA..." string) falls back to
     * UNSUPPORTED_DEVICE, which is what made PICO Connect show "Unknown" and
     * refuse to bring up the SteamVR HMD.
     */
    private static final String FAKE_SERIAL = "PA8X2C3D4E5F6";
    private static boolean sInstalled;

    private SerialSpoof() {
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
            XposedHelpers.findAndHookMethod(Build.class, "getSerial", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(FAKE_SERIAL);
                }
            });
            log("Build.getSerial -> " + FAKE_SERIAL);
        } catch (Throwable t) {
            log("getSerial hook failed: " + t);
        }
        try {
            // Deprecated field, still read by older SDKs; harmless to set.
            XposedHelpers.setStaticObjectField(Build.class, "SERIAL", FAKE_SERIAL);
            log("Build.SERIAL -> " + FAKE_SERIAL);
        } catch (Throwable t) {
            log("SERIAL field failed: " + t);
        }
    }
}
