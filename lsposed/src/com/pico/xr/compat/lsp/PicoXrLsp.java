// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
package com.pico.xr.compat.lsp;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Diagnostic/assistance LSPosed module for PICO XR apps on Quest 3.
 *
 * It does NOT implement an XR runtime. It only:
 *  - logs package/native ABI/library loads;
 *  - preloads libpico_xr_inject.so (which preloads the OpenXR facade);
 *  - grants PICO-prefixed permission checks to the apps selected in LSPosed.
 *
 * Scope is controlled by LSPosed: only select the PICO app(s) you are testing.
 */
public class PicoXrLsp implements IXposedHookLoadPackage {
    private static final String TAG = "PicoXrLsp";
    private static final String INJECT_PATH = "/system/lib64/libpico_xr_inject.so";
    private static boolean sInjected = false;
    private static final java.util.Set<String> sLogged = new java.util.HashSet<>();

    /** Diagnostic only. Off by default: hooking System.loadLibrary breaks hardened apps. */
    private static final boolean ENABLE_LOAD_LOGGING = false;

    /* Mirrors ROUTE_* in lspapp/jni/pico_xr_inject.cpp. */
    private static final int ROUTE_NOT_PICO = 0;
    private static final int ROUTE_OK = 1;

    /**
     * Implemented in libpico_xr_inject.so. Decides whether this process belongs
     * to a PICO app (package namespace or shipped PxrApi natives) and, if so,
     * writes a runtime manifest into this app's private files dir, points
     * XR_RUNTIME_JSON at it and preloads the facade. Quest apps get
     * ROUTE_NOT_PICO and are left completely untouched.
     */
    private static native int routePico(String pkg, String nativeDir, String filesDir);

    /**
     * Load the router from this APK's own nativeLibraryDir first (works with no
     * Magisk module at all), then fall back to the optional /system copy.
     */
    private static boolean loadRouter() {
        try {
            System.loadLibrary("pico_xr_inject");
            log("router loaded from APK lib dir");
            return true;
        } catch (Throwable t) {
            log("loadLibrary(pico_xr_inject) failed: " + t);
        }
        try {
            System.load(INJECT_PATH);
            log("router loaded from " + INJECT_PATH);
            return true;
        } catch (Throwable t) {
            log("router unavailable: " + t);
            return false;
        }
    }

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /**
     * Standard Android permissions that a PICO system app gets for free because
     * it runs as uid 1000. They are NOT declared in its manifest (so they cannot
     * be granted with pm grant either), yet the code path refuses to continue
     * without them: com.picovr.picostreamassistant calls
     * PowerManager.newWakeLock() in MainActivity.setupWifiStrategy, which throws
     * "Neither user 10203 nor current process has android.permission.WAKE_LOCK".
     *
     * Only checked inside an app we already routed to, never globally.
     */
    private static final java.util.Set<String> SYSTEM_UID_PERMISSIONS =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "android.permission.WAKE_LOCK"));

    private static boolean isSystemUidPermission(String permission) {
        return permission != null && SYSTEM_UID_PERMISSIONS.contains(permission);
    }

    private static boolean isPicoPermission(String permission) {
        if (permission == null) {
            return false;
        }
        return permission.startsWith("com.picovr.")
            || permission.startsWith("com.pico.")
            || permission.startsWith("pico.permission.")
            || permission.startsWith("com.bytedance.pico.")
            || permission.startsWith("com.pvr.")
            || permission.startsWith("com.pvr.")
            || permission.startsWith("pico.permission")
            || permission.startsWith("com.bytedance.pico.")
            || permission.startsWith("com.picoxr.");
    }

    private static void grantIfPico(XC_MethodHook.MethodHookParam param) {
        Object first = param.args.length > 0 ? param.args[0] : null;
        if (first instanceof String
                && (isPicoPermission((String) first) || isSystemUidPermission((String) first))) {
            log("grant permission check: " + first);
            param.setResult(PackageManager.PERMISSION_GRANTED);
        }
    }

    private static void grantEnforce(XC_MethodHook.MethodHookParam param) {
        Object first = param.args.length > 0 ? param.args[0] : null;
        if (first instanceof String
                && (isPicoPermission((String) first) || isSystemUidPermission((String) first))) {
            log("bypass enforce: " + first);
            param.setResult(null);
        }
    }

    /**
     * WifiManager$WifiLock.acquire() is checked by wifi service against
     * android.permission.WAKE_LOCK. A PICO system app never declares it and
     * instead relies on uid 1000; installed as a normal app the call throws
     * from the system server, so no client-side checkPermission hook can help.
     * Neutralise the lock locally: streaming keeps running, the device may dim
     * during long sessions.
     */
    private static final class VoidNoop extends XC_MethodHook {
        private final String key;

        VoidNoop(String key) {
            this.key = key;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (logOnce(key)) {
                log(key + " neutralised");
            }
            param.setResult(null);
        }
    }

    private static void hookWifiLocks(ClassLoader cl) {
        String cls = "android.net.wifi.WifiManager$WifiLock";
        /* Hook every overload instead of an exact signature: Android 14 has
         * acquire()/release() plus acquire(int)/release(int), and findAndHookMethod
         * with the wrong signature throws NoSuchMethodError and loses the hook. */
        hookAllVoid(cls, cl, "acquire", "wifilock:acquire");
        hookAllVoid(cls, cl, "release", "wifilock:release");
        hookAllVoid(cls, cl, "setReferenceCounted", "wifilock:setReferenceCounted");
    }

    private static void hookAllVoid(String className, ClassLoader cl, String method,
                                    String key) {
        try {
            Class<?> type = XposedHelpers.findClass(className, cl);
            int hooked = 0;
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(method)) {
                    continue;
                }
                XposedBridge.hookMethod(m, new VoidNoop(key));
                ++hooked;
            }
            if (hooked == 0) {
                log("hook " + className + "." + method + ": no overload found");
            }
        } catch (Throwable t) {
            log("hook " + className + "." + method + " failed: " + t);
        }
    }

    /**
     * Android 14 requires a foregroundServiceType for targetSdk 34 apps. PICO
     * Connect starts AssistantService as a foreground service without one and
     * is killed by the system with MissingForegroundServiceTypeException. The
     * process still runs as a started service, which is enough to reach the UI.
     */
    private static void hookForegroundService(ClassLoader cl) {
        String cls = "android.app.Service";
        hookVoid(cls, cl, "startForeground", new Class<?>[]{int.class,
                android.app.Notification.class}, "fgs:startForeground(2)");
        hookVoid(cls, cl, "startForeground", new Class<?>[]{int.class,
                android.app.Notification.class, int.class}, "fgs:startForeground(3)");
        hookVoid(cls, cl, "stopForeground", new Class<?>[]{boolean.class},
                "fgs:stopForeground");
    }

    private static void hookVoid(String className, ClassLoader cl, String method,
                                 Class<?>[] args, String key) {
        try {
            Object[] params = new Object[args.length + 1];
            System.arraycopy(args, 0, params, 0, args.length);
            params[args.length] = new VoidNoop(key);
            XposedHelpers.findAndHookMethod(className, cl, method, params);
        } catch (Throwable t) {
            log("hook " + className + "." + method + "/" + args.length + " failed: " + t);
        }
    }

    private static boolean logOnce(String key) {
        return sLogged.add(key);
    }

    /**
     * PICO's framework.jar carries a private AIDL service, android.os.IPowerAdvisor
     * (binder name "poweradvisor"), and PICO Connect notifies it when a display
     * session starts/stops. Horizon OS has no such class, so resolving
     * android.os.IPowerAdvisor$Stub throws ClassNotFoundException from
     * PssDisplay.A -> DisplayManager.w1 -> DesktopActivity.startDisplay: the app
     * dies exactly when the PC side connects.
     *
     * The notifier (m3.j.a / m3.j.b / m3.j.d) is pure telemetry, so skipping it
     * removes the class resolution entirely. Names are obfuscated, hence the
     * defensive try/catch around every hook.
     */
    private static void hookPicoPowerAdvisor(ClassLoader cl) {
        Class<?> pssDisplay;
        try {
            pssDisplay = XposedHelpers.findClass(
                "com.picovr.picostreamassistant.display.PssDisplay", cl);
        } catch (Throwable t) {
            log("PssDisplay not present: " + t);
            return;
        }
        hookVoid("m3.j", cl, "a", new Class<?>[]{int.class, pssDisplay},
                "poweradvisor:m3.j.a");
        hookVoid("m3.j", cl, "b", new Class<?>[]{int.class}, "poweradvisor:m3.j.b");
        hookVoid("m3.j", cl, "d", new Class<?>[]{String.class, String.class,
                int.class, String.class}, "poweradvisor:m3.j.d");
    }

    /**
     * MainActivity.onCreate calls startService(AssistantService) before it is
     * resumed. PICO ran as uid 1000 and was exempt; as a normal app Android 14
     * throws BackgroundServiceStartNotAllowedException and the launch fails.
     *
     * With targetSdk lowered to 33 a plain foreground service needs no type, so
     * falling back to startForegroundService is legal and keeps the service
     * alive. If even that is refused the call is swallowed instead of killing
     * the process.
     */
    private static void hookStartService(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", cl, "startService",
                android.content.Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        android.content.Intent intent = (android.content.Intent) param.args[0];
                        try {
                            param.setResult(XposedBridge.invokeOriginalMethod(
                                param.method, param.thisObject, param.args));
                        } catch (Throwable t) {
                            log("startService refused: " + t);
                            try {
                                param.setResult(((android.content.Context) param.thisObject)
                                    .startForegroundService(intent));
                                log("fell back to startForegroundService: "
                                    + (intent == null ? "?" : intent.getComponent()));
                            } catch (Throwable t2) {
                                log("startForegroundService refused too: " + t2);
                                param.setResult(null);
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            log("startService hook failed: " + t);
        }
    }

    private static void hookPermissions(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", cl, "checkPermission",
                String.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        grantIfPico(param);
                    }
                });
        } catch (Throwable t) {
            log("checkPermission hook failed: " + t);
        }
        /*
         * PowerManager.newWakeLock() uses enforceCallingOrSelfPermission(), which
         * does not go through checkPermission(), so it needs its own hooks.
         */
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", cl,
                "enforceCallingOrSelfPermission", String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        grantEnforce(param);
                    }
                });
        } catch (Throwable t) {
            log("enforceCallingOrSelfPermission hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", cl,
                "enforcePermission", String.class, int.class, int.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        grantEnforce(param);
                    }
                });
        } catch (Throwable t) {
            log("enforcePermission hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", cl, "checkSelfPermission",
                String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        grantIfPico(param);
                    }
                });
        } catch (Throwable t) {
            log("checkSelfPermission hook failed: " + t);
        }
    }

    private static void hookLibraryLoads(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "loadLibrary", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("System.loadLibrary(" + param.args[0] + ")");
                    }
                });
        } catch (Throwable t) {
            log("loadLibrary hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "load", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("System.load(" + param.args[0] + ")");
                    }
                });
        } catch (Throwable t) {
            log("load hook failed: " + t);
        }
    }

    private static boolean isSystemPackage(String pkg) {
        if (pkg == null) {
            return true;
        }
        return pkg.equals("android")
            || pkg.startsWith("com.android.")
            || pkg.startsWith("com.oculus.")
            || pkg.startsWith("com.meta.")
            || pkg.startsWith("com.qualcomm.")
            || pkg.startsWith("vendor.")
            || pkg.startsWith("com.facebook.");
    }

    private static int route(Application app) {
        String pkg;
        String nativeDir;
        String filesDir;
        try {
            pkg = app.getPackageName();
            nativeDir = app.getApplicationInfo().nativeLibraryDir;
            // The manifest is written here: readable by this app only, so it can
            // never steer another process.
            filesDir = app.getFilesDir().getAbsolutePath();
        } catch (Throwable t) {
            log("route: app info failed: " + t);
            return ROUTE_NOT_PICO;
        }
        try {
            int r = routePico(pkg, nativeDir, filesDir);
            log("route result=" + r + " (" + (r == ROUTE_OK ? "PICO lane" : "untouched")
                + ") pkg=" + pkg);
            return r;
        } catch (Throwable t) {
            log("route: native call failed: " + t);
            return ROUTE_NOT_PICO;
        }
    }

    private static void inject(Application app) {
        if (sInjected) {
            return;
        }
        sInjected = true;
        try {
            log("app=" + app.getPackageName()
                + " nativeDir=" + app.getApplicationInfo().nativeLibraryDir
                + " abis=" + String.join(",", Build.SUPPORTED_ABIS)
                + " sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            log("app info failed: " + t);
        }
        /*
         * The native router only matters for apps that must be pointed at a
         * PICO runtime manifest. PICO Connect now ships its own OpenXR loader
         * shim inside the APK, so the injector is no longer needed and its
         * dlopen fails inside Android's linker namespaces:
         *   library "/system/lib64/libpico_xr_inject.so" ... not accessible
         * Keep it opt-in so the log stays clean.
         */
        if (!"1".equals(prop("persist.pico.injector", "0"))) {
            log("XR injector disabled (persist.pico.injector=0)");
            return;
        }
        if (!loadRouter()) {
            return;
        }
        int r = route(app);
        if (r == ROUTE_OK) {
            log("PICO app routed to the per-app PICO runtime manifest");
            // Only now, and only in this process, replace the PICO OS identity.
            PicoOsSpoof.install(app, app.getClassLoader());
        }
    }

    /**
     * Runs before Application.onCreate so that the process' XR loader has not
     * resolved a runtime manifest yet. Named (not anonymous) because d8 in
     * build-tools 34 crashes on the MethodParameters attribute javac emits for
     * anonymous inner classes.
     */
    private static final class AppCreateHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args[0] instanceof Application) {
                inject((Application) param.args[0]);
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        /* Deliberately before the system-package guard: the Horizon shell is a
         * system app, and it is the one that decides whether a package opens as
         * a VR app or as a 2D panel. */
        if ("com.oculus.vrshell".equals(lpp.packageName)) {
            ForceVrHook.install(lpp.classLoader);
            return;
        }
        if (isSystemPackage(lpp.packageName)) {
            return;
        }
        if (!lpp.isFirstApplication) {
            log("secondary process, no hooks: " + lpp.processName);
            return;
        }
        /*
         * Every PICO app was a system app on PICO and therefore used APIs that a
         * normal /data install cannot: Build.getSerial(), hidden framework
         * members, and manifest permissions that only exist on PICO. Apply the
         * identity-level fixes to the whole PICO namespace, not just to apps
         * that ship an XR runtime.
         */
        if (isPicoNamespace(lpp.packageName)) {
            TouchDebug.install(lpp.classLoader);
            MouseButtonCompat.install(lpp.classLoader);
            InputDeviceSpoof.install(lpp.classLoader);
            HttpLog.install(lpp.classLoader);
            SerialSpoof.install(lpp.classLoader);
            HiddenApiCompat.install();
            MetaManifestCompat.install(lpp.classLoader);
        }

        String nativeDir = lpp.appInfo != null ? lpp.appInfo.nativeLibraryDir : null;
        if (!hasXrSurface(nativeDir)) {
            /*
             * Nothing to route and nothing to translate. Install ZERO hooks:
             * hardened titles (Unity + ByteDance security SDK, e.g. com.pvr.swift
             * with libswift.so / libEncryptor.so / libsscronet.so) abort as soon
             * as System.loadLibrary or ContextImpl.checkPermission is hooked, so
             * touching them at all makes the app unusable in LSPosed scope.
             */
            log("no XR surface, zero hooks: " + lpp.packageName);
            return;
        }
        log("scoped package=" + lpp.packageName + " process=" + lpp.processName
                + " nativeDir=" + nativeDir);
        if (ENABLE_LOAD_LOGGING) {
            hookLibraryLoads(lpp.classLoader);
        }
        // Apps that were system apps on PICO call Build.getSerial() and die in a
        // static initialiser without it.
        SerialSpoof.install(lpp.classLoader);
        HiddenApiCompat.install();
        MetaManifestCompat.install(lpp.classLoader);
        PanelSizeHook.install(lpp.classLoader);
        hookWifiLocks(lpp.classLoader);
        hookStartService(lpp.classLoader);
        hookPicoPowerAdvisor(lpp.classLoader);
        hookPermissions(lpp.classLoader);
        try {
            XposedHelpers.findAndHookMethod(
                Instrumentation.class, "callApplicationOnCreate",
                Application.class, new AppCreateHook());
        } catch (Throwable t) {
            log("Application hook failed: " + t);
        }
    }

    /**
     * True only when the APK can actually consume an XR runtime: it ships the
     * OpenXR loader or PxrApi. Apps that do neither (plain Unity apps, PICO
     * system UI like com.pvr.swift) get no hook at all.
     */
    private static boolean isPicoNamespace(String pkg) {
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

    private static boolean hasXrSurface(String nativeDir) {
        if (nativeDir == null) {
            return false;
        }
        String[] names = new File(nativeDir).list();
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n.equals("libopenxr_loader.so")
                    || n.equals("libopenxr_loader_pico.so")
                    || n.equals("libpxr_api.so")
                    || n.equals("libPxrPlatform.so")
                    || n.contains("openxr")) {
                return true;
            }
        }
        return false;
    }
}
