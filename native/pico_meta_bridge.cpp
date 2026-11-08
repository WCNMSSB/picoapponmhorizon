// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
//
/*
 * libpico_meta_bridge.so
 *
 * Experimental OpenXR runtime facade for running PICO/standard OpenXR
 * applications on Meta Quest 3 without replacing Horizon OS.
 *
 * Design:
 *   PICO app's libopenxr_loader.so
 *        -> this library (runtime ABI)
 *        -> Meta Quest OpenXR runtime ("backend")
 *
 * The facade deliberately forwards opaque XrInstance/XrSession/XrSwapchain
 * handles to the backend. It does not implement a compositor or a tracking
 * stack; Quest's existing runtime remains the only owner of the hardware.
 *
 * It is NOT a port of PICO's libpxrruntime.so and it does not implement the
 * PICO proprietary APIs. Unsupported XR_PICO_ / XR_BD_ extension requests are
 * filtered or rejected and logged.
 *
 * Build: see compat/build_android.sh
 */
#define LOG_TAG "PicoMetaBridge"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include <openxr/openxr.h>
#include <openxr/openxr_loader_negotiation.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef PICO_BRIDGE_DEFAULT_BACKEND
#define PICO_BRIDGE_DEFAULT_BACKEND \
    "/system/lib64/libmeta_xrloader.so"
#endif

namespace {

std::mutex g_lock;
void *g_backend = nullptr;
PFN_xrGetInstanceProcAddr g_backend_gipa = nullptr;
PFN_xrNegotiateLoaderRuntimeInterface g_backend_negotiate = nullptr;
PFN_xrInitializeLoaderKHR g_backend_init = nullptr;
XrVersion g_backend_api = XR_MAKE_VERSION(1, 0, 0);
bool g_backend_ready = false;
bool g_backend_is_loader = false;
bool g_initialized = false;
bool g_in_create_instance = false;

std::string get_prop(const char *name, const char *fallback) {
    char value[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(name, value);
    if (n > 0) {
        return std::string(value, static_cast<size_t>(n));
    }
    return std::string(fallback);
}

std::string backend_path() {
    return get_prop("persist.pico.xr.compat.backend",
                    PICO_BRIDGE_DEFAULT_BACKEND);
}

/*
 * Additive-runtime guard.
 *
 * This facade is selected per application process by pointing XR_RUNTIME_JSON
 * at a manifest that names this library. The backend is Meta's own loader,
 * which honours the same environment variable; if we left it pointing at our
 * own manifest the loader would re-enter this facade forever.
 *
 * The manifest may live anywhere (the LSPosed helper writes it into the target
 * app's private files dir, the optional Magisk module ships one under
 * /system/etc/openxr/pico/), so instead of matching a fixed path we read the
 * file and check whether it points at us.
 *
 * We only ever *read* the /odm manifest through the normal loader; nothing
 * under /odm is modified.
 */
constexpr const char *kSelfMarker = "libpico_meta_bridge";

const char *system_runtime_manifest() {
#if defined(__arm__)
    return "/odm/etc/openxr/1/active_runtime.armv7a-vfp.json";
#else
    return "/odm/etc/openxr/1/active_runtime.aarch64.json";
#endif
}

bool manifest_names_self(const char *path) {
    FILE *f = fopen(path, "re");
    if (f == nullptr) {
        return false;
    }
    char buf[2048] = {0};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    return n > 0 && strstr(buf, kSelfMarker) != nullptr;
}

void pin_backend_to_system_manifest() {
    const char *env = getenv("XR_RUNTIME_JSON");
    if (env == nullptr || !manifest_names_self(env)) {
        return;
    }
    const char *target = system_runtime_manifest();
    setenv("XR_RUNTIME_JSON", target, 1);
    LOGI("XR_RUNTIME_JSON named this facade; pinned backend to %s", target);
}

bool should_strip_extension(const char *name) {
    if (!name) {
        return false;
    }
    // PICO/ByteDance-proprietary extensions have no Meta implementation.
    // Keeping them in XrInstanceCreateInfo would make the Meta runtime fail
    // the whole instance creation. They are stripped and logged; apps that
    // absolutely require them must be handled by a dedicated translator.
    static const char *prefixes[] = {
        "XR_PICO_", "XR_PXR_", "XR_BD_", "XR_BYTEDANCE_",
        "XR_BYTEDANCE_", "XR_ULTRA_",
    };
    for (const char *prefix : prefixes) {
        if (strncmp(name, prefix, strlen(prefix)) == 0) {
            return true;
        }
    }
    return false;
}

PFN_xrVoidFunction backend_proc(const char *name) {
    if (!g_backend_gipa) {
        return nullptr;
    }
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = g_backend_gipa(XR_NULL_HANDLE, name, &fn);
    if (XR_FAILED(r) || fn == nullptr) {
        LOGW("backend has no %s (result=%d)", name, (int)r);
        return nullptr;
    }
    return fn;
}

bool load_backend_locked() {
    if (g_backend_ready) {
        return true;
    }
    if (g_backend) {
        return false;
    }

    // If this process was routed here through XR_RUNTIME_JSON, re-point that
    // variable at Horizon OS's own manifest first, otherwise the Meta loader
    // backend would resolve back into this facade.
    pin_backend_to_system_manifest();

    std::string path = backend_path();
    LOGI("loading Meta OpenXR backend: %s", path.c_str());
    g_backend = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g_backend) {
        LOGE("backend dlopen failed: %s", dlerror());
        return false;
    }

    g_backend_negotiate = reinterpret_cast<PFN_xrNegotiateLoaderRuntimeInterface>(
        dlsym(g_backend, "xrNegotiateLoaderRuntimeInterface"));
    if (!g_backend_negotiate) {
        /*
         * A Quest system loader (libopenxr_loader.so) exports the client-side
         * xr* API but not xrNegotiateLoaderRuntimeInterface. Treat it as a
         * loader backend: the facade is a runtime to PICO's loader and a
         * client to Meta's loader. This avoids reimplementing Meta's APEX
         * runtime discovery.
         */
        PFN_xrGetInstanceProcAddr loader_gipa =
            reinterpret_cast<PFN_xrGetInstanceProcAddr>(
                dlsym(g_backend, "xrGetInstanceProcAddr"));
        if (!loader_gipa) {
            LOGE("backend is neither a runtime nor a loader: %s", dlerror());
            dlclose(g_backend);
            g_backend = nullptr;
            return false;
        }
        g_backend_is_loader = true;
        g_backend_gipa = loader_gipa;
        g_backend_api = XR_MAKE_VERSION(1, 0, 32);
        g_backend_init = reinterpret_cast<PFN_xrInitializeLoaderKHR>(
            backend_proc("xrInitializeLoaderKHR"));
        g_backend_ready = true;
        LOGI("loader backend ready: gipa=%p init=%p",
             (void *)g_backend_gipa, (void *)g_backend_init);
        return true;
    }

    XrNegotiateLoaderInfo loader = {};
    loader.structType = XR_LOADER_INTERFACE_STRUCT_LOADER_INFO;
    loader.structVersion = XR_LOADER_INFO_STRUCT_VERSION;
    loader.structSize = sizeof(loader);
    loader.minInterfaceVersion = 1;
    loader.maxInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loader.minApiVersion = XR_MAKE_VERSION(1, 0, 0);
    loader.maxApiVersion = XR_MAKE_VERSION(1, 0, 32);

    XrNegotiateRuntimeRequest request = {};
    request.structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    request.structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    request.structSize = sizeof(request);

    XrResult r = g_backend_negotiate(&loader, &request);
    if (XR_FAILED(r) || request.getInstanceProcAddr == nullptr) {
        LOGE("backend negotiation failed: XrResult=%d gipa=%p", (int)r,
             (void *)request.getInstanceProcAddr);
        dlclose(g_backend);
        g_backend = nullptr;
        g_backend_negotiate = nullptr;
        return false;
    }

    g_backend_gipa = request.getInstanceProcAddr;
    g_backend_api = request.runtimeApiVersion;
    g_backend_is_loader = false;
    g_backend_init = reinterpret_cast<PFN_xrInitializeLoaderKHR>(
        backend_proc("xrInitializeLoaderKHR"));
    g_backend_ready = true;
    LOGI("backend ready: interface=%u api=%llu gipa=%p init=%p",
         request.runtimeInterfaceVersion,
         (unsigned long long)request.runtimeApiVersion,
         (void *)g_backend_gipa, (void *)g_backend_init);
    return true;
}

XRAPI_ATTR XrResult XRAPI_CALL bridge_create_instance(const XrInstanceCreateInfo *info,
                                XrInstance *instance) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!load_backend_locked()) {
        return XR_ERROR_RUNTIME_FAILURE;
    }
    if (!info || !instance) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (g_in_create_instance) {
        return XR_ERROR_RUNTIME_FAILURE;
    }
    g_in_create_instance = true;

    std::vector<const char *> extensions;
    extensions.reserve(info->enabledExtensionCount);
    for (uint32_t i = 0; i < info->enabledExtensionCount; ++i) {
        const char *name = info->enabledExtensionNames[i];
        if (should_strip_extension(name)) {
            LOGW("stripping unsupported PICO extension %s", name);
            continue;
        }
        extensions.push_back(name);
    }

    XrInstanceCreateInfo copy = *info;
    copy.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    copy.enabledExtensionNames = extensions.empty() ? nullptr : extensions.data();

    auto create = reinterpret_cast<PFN_xrCreateInstance>(
        backend_proc("xrCreateInstance"));
    XrResult r = create ? create(&copy, instance) : XR_ERROR_FUNCTION_UNSUPPORTED;
    g_in_create_instance = false;
    LOGI("xrCreateInstance -> %d instance=%p (extensions requested=%u kept=%u)",
         (int)r, (void *)(instance ? *instance : XR_NULL_HANDLE),
         info->enabledExtensionCount, copy.enabledExtensionCount);
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL bridge_get_instance_proc_addr(XrInstance instance, const char *name,
                                       PFN_xrVoidFunction *function) {
    if (!name || !function) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *function = nullptr;

    if (strcmp(name, "xrGetInstanceProcAddr") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(
            &bridge_get_instance_proc_addr);
        return XR_SUCCESS;
    }
    if (strcmp(name, "xrCreateInstance") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(&bridge_create_instance);
        return XR_SUCCESS;
    }
    if (strcmp(name, "xrNegotiateLoaderRuntimeInterface") == 0) {
        *function = nullptr;
        return XR_ERROR_FUNCTION_UNSUPPORTED;
    }

    std::lock_guard<std::mutex> guard(g_lock);
    if (!load_backend_locked()) {
        return XR_ERROR_RUNTIME_FAILURE;
    }
    XrResult r = g_backend_gipa(instance, name, function);
    if (XR_FAILED(r) || *function == nullptr) {
        // Do not spam for every optional extension query.
        if (strncmp(name, "xr", 2) == 0) {
            LOGW("backend GIPA(%s) -> %d ptr=%p", name, (int)r,
                 (void *)*function);
        }
        return XR_FAILED(r) ? r : XR_ERROR_FUNCTION_UNSUPPORTED;
    }
    return XR_SUCCESS;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL
bridge_initialize_loader(const XrLoaderInitInfoBaseHeaderKHR *info) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!load_backend_locked()) {
        return XR_ERROR_RUNTIME_FAILURE;
    }
    if (g_initialized) {
        return XR_SUCCESS;
    }
    if (!g_backend_init) {
        LOGW("backend exposes no xrInitializeLoaderKHR; continuing");
        g_initialized = true;
        return XR_SUCCESS;
    }
    XrResult r = g_backend_init(info);
    LOGI("backend xrInitializeLoaderKHR -> %d", (int)r);
    if (XR_SUCCEEDED(r)) {
        g_initialized = true;
    }
    return r;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL
bridge_negotiate(const XrNegotiateLoaderInfo *loader_info,
                 XrNegotiateRuntimeRequest *runtime_request) {
    if (!loader_info || !runtime_request) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::lock_guard<std::mutex> guard(g_lock);
    if (!load_backend_locked()) {
        return XR_ERROR_RUNTIME_FAILURE;
    }
    runtime_request->structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtime_request->structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtime_request->structSize = sizeof(*runtime_request);
    runtime_request->runtimeInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    // Report the backend's API version, but never exceed the loader's max.
    runtime_request->runtimeApiVersion = std::min(g_backend_api,
                                                  loader_info->maxApiVersion);
    runtime_request->getInstanceProcAddr = &bridge_get_instance_proc_addr;
    LOGI("runtime negotiation: loaderApi=%llu backendApi=%llu reported=%llu",
         (unsigned long long)loader_info->maxApiVersion,
         (unsigned long long)g_backend_api,
         (unsigned long long)runtime_request->runtimeApiVersion);
    return XR_SUCCESS;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult
XRAPI_CALL xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo *loaderInfo,
    XrNegotiateRuntimeRequest *runtimeRequest) {
    return bridge_negotiate(loaderInfo, runtimeRequest);
}

extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult
XRAPI_CALL xrInitializeLoaderKHR(
    const XrLoaderInitInfoBaseHeaderKHR *loaderInitInfo) {
    return bridge_initialize_loader(loaderInitInfo);
}

/*
 * A loader may call xrGetInstanceProcAddr on the runtime before an instance
 * exists. Export it as well, although the runtime ABI normally obtains it from
 * xrNegotiateLoaderRuntimeInterface.
 */
extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult
XRAPI_CALL xrGetInstanceProcAddr(XrInstance instance, const char *name,
                                 PFN_xrVoidFunction *function) {
    return bridge_get_instance_proc_addr(instance, name, function);
}

