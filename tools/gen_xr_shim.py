#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Generate an OpenXR loader shim that forwards to the real loader but can
reorder the two eyes in xrEndFrame.

Why: PICO Connect was built against PICO's runtime, whose projection views are
ordered differently from Meta's. With the real Horizon OS loader the left/right
images end up swapped. The app links directly against
libpico_openxr_loader.so (DT_NEEDED), so replacing that file is the only place
the swap can be applied without touching the app's own code.

Diagnostics are always compiled in: swapchain creation parameters, the runtime's
recommended per-eye sizes, and the first frames' projection view details.

Runtime switch:  persist.pico.xr.eyeswap = none | views | images | fov
"""
from __future__ import annotations

import re
import sys

SYMS = [s.strip() for s in open(sys.argv[1]).read().split() if s.strip()]
HDR = sys.argv[2]
OUT = sys.argv[3]

src = open(HDR, encoding="utf-8", errors="replace").read()

decl_re = re.compile(
    r"XRAPI_ATTR\s+XrResult\s+XRAPI_CALL\s+(xr\w+)\s*\((.*?)\)\s*;", re.S)

decls = {}
for m in decl_re.finditer(src):
    decls[m.group(1)] = " ".join(m.group(2).split())

missing = [s for s in SYMS if s not in decls]
if missing:
    print("missing prototypes:", missing, file=sys.stderr)


def split_params(text):
    if not text.strip() or text.strip() == "void":
        return []
    out, depth, cur = [], 0, ""
    for ch in text:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def param_name(decl):
    m = re.search(r"(\w+)\s*(\[[^\]]*\])?$", decl.strip())
    return m.group(1) if m else "arg"


def strip_default(decl):
    return re.sub(r"\s*=\s*[^,]+$", "", decl)


SPECIAL = {"xrEndFrame", "xrCreateSwapchain", "xrEnumerateViewConfigurationViews", "xrLocateViews",
           "xrSuggestInteractionProfileBindings", "xrCreateInstance",
           "xrSyncActions", "xrGetActionStatePose", "xrGetActionStateBoolean",
           "xrCreateAction", "xrGetActionStateFloat", "xrGetInstanceProcAddr",
           "xrLocateSpace", "xrAttachSessionActionSets"}

out = []
out.append('#include <dlfcn.h>')
out.append('#include <string.h>')
out.append('#include <sys/system_properties.h>')
out.append('#include <android/log.h>\n#include <stdlib.h>')
out.append('#include "openxr/openxr.h"')
out.append('')
out.append('#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "XrSwapShim", __VA_ARGS__)')
out.append('#define REAL_LIB "libopenxr_loader_real.so"')
out.append('static void *g_real;')
out.append('static void *real_proc(const char *name) {')
out.append('    if (!g_real) g_real = dlopen(REAL_LIB, RTLD_NOW | RTLD_LOCAL);')
out.append('    if (!g_real) { LOGI("dlopen(%s) failed: %s", REAL_LIB, dlerror()); return 0; }')
out.append('    return dlsym(g_real, name);')
out.append('}')
out.append('')

for s in SYMS:
    if s in SPECIAL:
        continue
    decl = decls.get(s)
    if not decl:
        continue
    params = split_params(decl)
    names = [param_name(p) for p in params]
    types = [strip_default(p) for p in params]
    sig = ", ".join(types) if types else "void"
    out.append(f"typedef XrResult (XRAPI_CALL *FN_{s})({sig});")
    out.append(f"XRAPI_ATTR XrResult XRAPI_CALL {s}({sig}) {{")
    out.append(f'    static FN_{s} fn;')
    out.append(f'    if (!fn) fn = (FN_{s})real_proc("{s}");')
    out.append('    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;')
    out.append(f'    return fn({", ".join(names)});')
    out.append('}')
    out.append('')

out.append(r'''
/* ------------------------------------------------------------------ */
/* diagnostics: swapchain creation and the runtime's recommended views */
/* ------------------------------------------------------------------ */
XRAPI_ATTR XrResult XRAPI_CALL xrCreateSwapchain(XrSession session,
                                                 const XrSwapchainCreateInfo *ci,
                                                 XrSwapchain *swapchain) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrSwapchainCreateInfo *, XrSwapchain *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrCreateSwapchain");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, ci, swapchain);
    LOGI("xrCreateSwapchain -> %d handle=%llu %ux%u fmt=%lld array=%u samples=%u usage=0x%llx",
         (int)r, (unsigned long long)(swapchain ? *swapchain : 0),
         ci ? ci->width : 0, ci ? ci->height : 0,
         ci ? (long long)ci->format : -1, ci ? ci->arraySize : 0,
         ci ? ci->sampleCount : 0, ci ? (unsigned long long)ci->usageFlags : 0);
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrEnumerateViewConfigurationViews(
        XrInstance instance, XrSystemId systemId, XrViewConfigurationType type,
        uint32_t viewCapacityInput, uint32_t *viewCountOutput,
        XrViewConfigurationView *views) {
    typedef XrResult (XRAPI_CALL *FN)(XrInstance, XrSystemId, XrViewConfigurationType,
                                      uint32_t, uint32_t *, XrViewConfigurationView *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrEnumerateViewConfigurationViews");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(instance, systemId, type, viewCapacityInput, viewCountOutput, views);
    LOGI("xrEnumerateViewConfigurationViews -> %d count=%u cap=%u", (int)r,
         viewCountOutput ? *viewCountOutput : 0, viewCapacityInput);
    if (views && viewCapacityInput) {
        for (uint32_t i = 0; i < viewCapacityInput && i < 2; ++i) {
            LOGI("  recommended[%u] %ux%u (max %ux%u) samples=%u", i,
                 views[i].recommendedImageRectWidth, views[i].recommendedImageRectHeight,
                 views[i].maxImageRectWidth, views[i].maxImageRectHeight,
                 views[i].recommendedSwapchainSampleCount);
        }
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* controller battery: PICO exposes /input/battery/value, Meta does not */
/* ------------------------------------------------------------------ */
static XrInstance g_instance;
#define MAX_ACTIONS 128
static XrAction g_actions[MAX_ACTIONS];
/* actions that PICO binds to /input/battery/value, recorded at suggest time */
static XrAction g_batt_left = XR_NULL_HANDLE;
static XrAction g_batt_right = XR_NULL_HANDLE;
static XrAction g_batt_other = XR_NULL_HANDLE;
static char g_action_names[MAX_ACTIONS][64];
static int g_action_count;

static const char *action_name(XrAction action) {
    for (int i = 0; i < g_action_count; ++i) {
        if (g_actions[i] == action) {
            return g_action_names[i];
        }
    }
    return NULL;
}

XRAPI_ATTR XrResult XRAPI_CALL xrCreateAction(XrActionSet actionSet,
                                              const XrActionCreateInfo *createInfo,
                                              XrAction *action) {
    typedef XrResult (XRAPI_CALL *FN)(XrActionSet, const XrActionCreateInfo *, XrAction *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrCreateAction");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(actionSet, createInfo, action);
    if (XR_SUCCEEDED(r) && action && createInfo && g_action_count < MAX_ACTIONS) {
        g_actions[g_action_count] = *action;
        strncpy(g_action_names[g_action_count], createInfo->actionName, 63);
        g_action_names[g_action_count][63] = 0;
        ++g_action_count;
        static int logged;
        if (logged < 40) {
            ++logged;
            LOGI("action '%s' type=%d", createInfo->actionName, (int)createInfo->actionType);
        }
    }
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrGetActionStateFloat(XrSession session,
                                                     const XrActionStateGetInfo *getInfo,
                                                     XrActionStateFloat *state) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrActionStateGetInfo *,
                                      XrActionStateFloat *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrGetActionStateFloat");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, getInfo, state);
    if (!getInfo || !state) {
        return r;
    }
    const char *name = action_name(getInfo->action);
    int is_battery = (getInfo->action == g_batt_left) || (getInfo->action == g_batt_right) ||
                     (getInfo->action == g_batt_other) || (name && strstr(name, "battery"));
    static int probe;
    if (probe < 12) {
        ++probe;
        LOGI("float action %p name=%s battery=%d", (void *)getInfo->action,
             name ? name : "?", is_battery);
    }
    if (!is_battery) {
        return r;
    }
    /* /user/hand/<hand>/input/battery/value cannot be bound on Touch, so the action
     * is never active. Streaming only needs a percentage for its UI. */
    const char *key = "persist.pico.battery.hmd";
    if (getInfo->action == g_batt_left) {
        key = "persist.pico.battery.left";
    } else if (getInfo->action == g_batt_right) {
        key = "persist.pico.battery.right";
    } else if (name && strstr(name, "left")) {
        key = "persist.pico.battery.left";
    } else if (name && strstr(name, "right")) {
        key = "persist.pico.battery.right";
    } else if (getInfo->subactionPath != XR_NULL_PATH) {
        /* one action, two hands: the hand comes from the subaction path */
        typedef XrResult (XRAPI_CALL *PFS)(XrInstance, XrPath, uint32_t, uint32_t *, char *);
        static PFS to_string;
        if (!to_string) to_string = (PFS)real_proc("xrPathToString");
        char path[256] = {0};
        uint32_t len = 0;
        if (to_string && g_instance != XR_NULL_HANDLE &&
            XR_SUCCEEDED(to_string(g_instance, getInfo->subactionPath, sizeof(path), &len, path))) {
            if (strstr(path, "left")) {
                key = "persist.pico.battery.left";
            } else if (strstr(path, "right")) {
                key = "persist.pico.battery.right";
            }
        }
    }
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get(key, value);
    float level = value[0] ? (float)atof(value) : 0.85f;
    if (level > 1.0f) {
        level = 1.0f;
    }
    state->currentState = level;
    state->isActive = XR_TRUE;
    state->changedSinceLastSync = XR_FALSE;
    static int logged;
    if (logged < 6) {
        ++logged;
        LOGI("battery '%s' -> %.2f (%s)", name, level, key);
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* input diagnostics: is the app polling actions at all?               */
/* ------------------------------------------------------------------ */
static int g_sync_count;
static int g_pose_calls;

XRAPI_ATTR XrResult XRAPI_CALL xrAttachSessionActionSets(
        XrSession session, const XrSessionActionSetsAttachInfo *info) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrSessionActionSetsAttachInfo *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrAttachSessionActionSets");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, info);
    LOGI("xrAttachSessionActionSets -> %d (%u sets)", (int)r, info ? info->countActionSets : 0);
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrSyncActions(XrSession session,
                                             const XrActionsSyncInfo *syncInfo) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrActionsSyncInfo *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrSyncActions");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, syncInfo);
    ++g_sync_count;
    if (g_sync_count <= 3 || r != XR_SUCCESS) {
        LOGI("xrSyncActions #%d -> %d (%u sets)", g_sync_count, (int)r,
             syncInfo ? syncInfo->countActiveActionSets : 0);
    }
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrGetActionStatePose(XrSession session,
                                                    const XrActionStateGetInfo *getInfo,
                                                    XrActionStatePose *state) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrActionStateGetInfo *, XrActionStatePose *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrGetActionStatePose");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, getInfo, state);
    ++g_pose_calls;
    if (g_pose_calls <= 6) {
        LOGI("xrGetActionStatePose #%d -> %d active=%d", g_pose_calls, (int)r,
             state ? (int)state->isActive : -1);
    }
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrGetActionStateBoolean(XrSession session,
                                                       const XrActionStateGetInfo *getInfo,
                                                       XrActionStateBoolean *state) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrActionStateGetInfo *, XrActionStateBoolean *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrGetActionStateBoolean");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, getInfo, state);
    static int n;
    if (++n <= 6) {
        LOGI("xrGetActionStateBoolean #%d -> %d current=%d", n, (int)r,
             state ? (int)state->currentState : -1);
    }
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL xrLocateSpace(XrSpace space, XrSpace baseSpace,
                                             XrTime time, XrSpaceLocation *location) {
    typedef XrResult (XRAPI_CALL *FN)(XrSpace, XrSpace, XrTime, XrSpaceLocation *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrLocateSpace");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(space, baseSpace, time, location);
    static int n;
    if (++n <= 6) {
        LOGI("xrLocateSpace #%d -> %d flags=0x%llx pos=(%.3f,%.3f,%.3f)", n, (int)r,
             location ? (unsigned long long)location->locationFlags : 0,
             location ? location->pose.position.x : 0.0f,
             location ? location->pose.position.y : 0.0f,
             location ? location->pose.position.z : 0.0f);
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* controller bindings: rewrite PICO profiles to Oculus Touch          */
/* ------------------------------------------------------------------ */
static XrInstance g_instance;

XRAPI_ATTR XrResult XRAPI_CALL xrCreateInstance(const XrInstanceCreateInfo *createInfo,
                                                XrInstance *instance) {
    typedef XrResult (XRAPI_CALL *FN)(const XrInstanceCreateInfo *, XrInstance *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrCreateInstance");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(createInfo, instance);
    if (XR_SUCCEEDED(r) && instance) {
        g_instance = *instance;
    }
    return r;
}

#define TOUCH_PROFILE "/interaction_profiles/oculus/touch_controller"

XRAPI_ATTR XrResult XRAPI_CALL xrSuggestInteractionProfileBindings(
        XrInstance instance, const XrInteractionProfileSuggestedBinding *suggested) {
    typedef XrResult (XRAPI_CALL *FN)(XrInstance, const XrInteractionProfileSuggestedBinding *);
    typedef XrResult (XRAPI_CALL *PFS)(XrInstance, XrPath, uint32_t, uint32_t *, char *);
    typedef XrResult (XRAPI_CALL *PFP)(XrInstance, const char *, XrPath *);
    static FN fn;
    static PFS path_to_string;
    static PFP string_to_path;
    if (!fn) fn = (FN)real_proc("xrSuggestInteractionProfileBindings");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    if (!path_to_string) path_to_string = (PFS)real_proc("xrPathToString");
    if (!string_to_path) string_to_path = (PFP)real_proc("xrStringToPath");
    if (!suggested || !path_to_string || !string_to_path) {
        return fn(instance, suggested);
    }

    char buf[256] = {0};
    uint32_t len = 0;
    if (XR_FAILED(path_to_string(instance, suggested->interactionProfile,
                                 sizeof(buf), &len, buf))) {
        return fn(instance, suggested);
    }

    XrInteractionProfileSuggestedBinding copy = *suggested;
    if (strstr(buf, "/interaction_profiles/bytedance/") ||
        strstr(buf, "/interaction_profiles/pico") ||
        strstr(buf, "pico_controller")) {
        XrPath touch = XR_NULL_PATH;
        if (XR_SUCCEEDED(string_to_path(instance, TOUCH_PROFILE, &touch)) &&
            touch != XR_NULL_PATH) {
            copy.interactionProfile = touch;
            LOGI("profile %s -> %s (%u bindings)", buf, TOUCH_PROFILE,
                 suggested->countSuggestedBindings);
            /* Remember which actions PICO binds to a battery component: Touch has
             * no such input, so those bindings get dropped below and the action
             * would stay inactive forever. */
            if (suggested->suggestedBindings) {
                for (uint32_t i = 0; i < suggested->countSuggestedBindings; ++i) {
                    char pb[256] = {0};
                    uint32_t plen = 0;
                    if (XR_FAILED(path_to_string(instance,
                            suggested->suggestedBindings[i].binding,
                            sizeof(pb), &plen, pb))) {
                        continue;
                    }
                    if (!strstr(pb, "battery")) {
                        continue;
                    }
                    XrAction a = suggested->suggestedBindings[i].action;
                    if (strstr(pb, "left")) {
                        g_batt_left = a;
                    } else if (strstr(pb, "right")) {
                        g_batt_right = a;
                    } else {
                        g_batt_other = a;
                    }
                    LOGI("battery binding %s -> action %p", pb, (void *)a);
                }
            }
            /* dump the component paths once so unsupported ones can be spotted */
            static int dumped;
            if (!dumped && suggested->suggestedBindings) {
                dumped = 1;
                for (uint32_t i = 0; i < suggested->countSuggestedBindings && i < 40; ++i) {
                    char pb[256] = {0};
                    uint32_t plen = 0;
                    if (XR_SUCCEEDED(path_to_string(instance,
                            suggested->suggestedBindings[i].binding,
                            sizeof(pb), &plen, pb))) {
                        LOGI("  binding[%u] %s", i, pb);
                    }
                }
            }
            XrResult rr = fn(instance, &copy);
            LOGI("  suggest -> %d", (int)rr);
            if (XR_SUCCEEDED(rr)) {
                return rr;
            }

            /*
             * Meta rejects the whole suggestion if any single component path is
             * not bindable (e.g. /input/system/click is reserved, and Touch has
             * no /input/battery/value). Test each binding on its own and submit
             * only the accepted ones.
             */
            enum { MAXKEEP = 256 };
            static XrActionSuggestedBinding kept[MAXKEEP];
            uint32_t n = suggested->countSuggestedBindings;
            uint32_t nk = 0;
            uint32_t rejected = 0;
            for (uint32_t i = 0; i < n && nk < MAXKEEP; ++i) {
                XrInteractionProfileSuggestedBinding one = copy;
                one.suggestedBindings = &suggested->suggestedBindings[i];
                one.countSuggestedBindings = 1;
                if (XR_SUCCEEDED(fn(instance, &one))) {
                    kept[nk++] = suggested->suggestedBindings[i];
                } else {
                    ++rejected;
                    char pb[256] = {0};
                    uint32_t plen = 0;
                    if (rejected <= 8 && path_to_string(instance,
                            suggested->suggestedBindings[i].binding,
                            sizeof(pb), &plen, pb) == XR_SUCCESS) {
                        LOGI("  dropped unbindable %s", pb);
                    }
                }
            }
            if (nk == 0) {
                LOGI("  no bindable components for %s", TOUCH_PROFILE);
                return rr;
            }
            copy.suggestedBindings = kept;
            copy.countSuggestedBindings = nk;
            rr = fn(instance, &copy);
            LOGI("  filtered %u/%u bindings, suggest -> %d", nk, n, (int)rr);
            return rr;
        }
    }
    static int once;
    if (once < 6) { ++once; LOGI("profile kept: %s", buf); }
    return fn(instance, suggested);
}

/* ------------------------------------------------------------------ */
static XrView g_eye_left;
static XrView g_eye_right;
static int g_eye_valid;

XRAPI_ATTR XrResult XRAPI_CALL xrLocateViews(XrSession session,
                                             const XrViewLocateInfo *locateInfo,
                                             XrViewState *viewState, uint32_t capacity,
                                             uint32_t *countOutput, XrView *views) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrViewLocateInfo *, XrViewState *,
                                      uint32_t, uint32_t *, XrView *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrLocateViews");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = fn(session, locateInfo, viewState, capacity, countOutput, views);
    /* PRIMARY_STEREO is specified as [left, right] */
    if (r == XR_SUCCESS && views && countOutput && *countOutput >= 2) {
        g_eye_left = views[0];
        g_eye_right = views[1];
        g_eye_valid = 1;
    }
    return r;
}

/* ------------------------------------------------------------------ */
/* xrEndFrame: optional eye fix-up, plus a one-shot dump of the views  */
/* ------------------------------------------------------------------ */
static int eyeswap_mode(void) {
    static int mode = -1;
    if (mode < 0) {
        char value[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.pico.xr.eyeswap", value);
        if (!strcmp(value, "none")) mode = 0;
        else if (!strcmp(value, "views")) mode = 1;
        else if (!strcmp(value, "images")) mode = 2;
        else if (!strcmp(value, "fov")) mode = 3;
        else if (!strcmp(value, "locate+images")) mode = 5;
        else if (!strcmp(value, "pose")) mode = 6;
        else if (!strcmp(value, "offset")) mode = 7;
        else if (!strcmp(value, "fill")) mode = 8;
        else if (!strcmp(value, "offset+images")) mode = 9;
        else if (!strcmp(value, "locate-live")) mode = 10;
        else mode = 4;   /* default: use the runtime's own eye poses/fovs */
        LOGI("eyeswap mode = %s (%d)", value[0] ? value : "default", mode);
    }
    return mode;
}

XRAPI_ATTR XrResult XRAPI_CALL xrEndFrame(XrSession session,
                                          const XrFrameEndInfo *frameEndInfo) {
    typedef XrResult (XRAPI_CALL *FN)(XrSession, const XrFrameEndInfo *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrEndFrame");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;

    const int mode = eyeswap_mode();

    /* Log whenever the submitted layer set changes: opening the SteamVR
     * dashboard or an overlay shows up here as a new layer (or a vendor layer
     * type Meta does not know, which would explain an invisible panel). */
    if (frameEndInfo && frameEndInfo->layers && frameEndInfo->layerCount <= 12) {
        static int last_n = -1;
        static XrStructureType last_t[12];
        int changed = (int)frameEndInfo->layerCount != last_n;
        for (uint32_t i = 0; !changed && i < frameEndInfo->layerCount; ++i) {
            const XrCompositionLayerBaseHeader *l = frameEndInfo->layers[i];
            if ((l ? l->type : 0) != last_t[i]) changed = 1;
        }
        if (changed) {
            last_n = (int)frameEndInfo->layerCount;
            for (uint32_t i = 0; i < frameEndInfo->layerCount; ++i) {
                const XrCompositionLayerBaseHeader *l = frameEndInfo->layers[i];
                last_t[i] = l ? l->type : 0;
                LOGI("layer set[%u] type=%d", i, (int)last_t[i]);
            }
        }
    }

    static int dumped;
    if (dumped < 2 && frameEndInfo && frameEndInfo->layers) {
        ++dumped;
        for (uint32_t li = 0; li < frameEndInfo->layerCount; ++li) {
            const XrCompositionLayerBaseHeader *l = frameEndInfo->layers[li];
            if (!l) continue;
            LOGI("frame layer[%u] type=%d", li, (int)l->type);
            if (l->type != XR_TYPE_COMPOSITION_LAYER_PROJECTION) continue;
            const XrCompositionLayerProjection *pp =
                (const XrCompositionLayerProjection *)l;
            LOGI("  projection viewCount=%u space=%llu", pp->viewCount,
                 (unsigned long long)pp->space);
            for (uint32_t vi = 0; vi < pp->viewCount && vi < 4; ++vi) {
                const XrCompositionLayerProjectionView *v = &pp->views[vi];
                LOGI("  view[%u] sc=%llu rect=(%d,%d %ux%u) arr=%u "
                     "pose=(%.3f,%.3f,%.3f) fov=(%.3f,%.3f,%.3f,%.3f)",
                     vi, (unsigned long long)v->subImage.swapchain,
                     v->subImage.imageRect.offset.x, v->subImage.imageRect.offset.y,
                     v->subImage.imageRect.extent.width,
                     v->subImage.imageRect.extent.height,
                     v->subImage.imageArrayIndex,
                     v->pose.position.x, v->pose.position.y, v->pose.position.z,
                     v->fov.angleLeft, v->fov.angleRight,
                     v->fov.angleUp, v->fov.angleDown);
            }
        }
    }

    if (mode == 0 || !frameEndInfo || frameEndInfo->layerCount == 0 ||
        frameEndInfo->layerCount > 8 || !frameEndInfo->layers) {
        return fn(session, frameEndInfo);
    }

    const XrCompositionLayerBaseHeader *layers[8];
    XrCompositionLayerProjection proj[8];
    XrCompositionLayerProjectionView views[8][2];
    int touched = 0;

    for (uint32_t i = 0; i < frameEndInfo->layerCount; ++i) {
        const XrCompositionLayerBaseHeader *l = frameEndInfo->layers[i];
        if (l && l->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
            const XrCompositionLayerProjection *p =
                (const XrCompositionLayerProjection *)l;
            if (p->viewCount == 2 && p->views) {
                proj[i] = *p;
                views[i][0] = p->views[0];
                views[i][1] = p->views[1];
                if (mode == 1) {            /* whole view, incl. pose/fov */
                    XrCompositionLayerProjectionView t = views[i][0];
                    views[i][0] = views[i][1];
                    views[i][1] = t;
                } else if (mode == 2) {     /* textures only */
                    XrSwapchainSubImage t = views[i][0].subImage;
                    views[i][0].subImage = views[i][1].subImage;
                    views[i][1].subImage = t;
                } else if (mode == 10) {
                    /* Ask the runtime for this frame's real eye poses (in the
                     * layer's own space) and use them. This keeps the headset's
                     * configured IPD and needs no guessed value.
                     * The app's fov is left alone because its texture was
                     * rendered for it. */
                    typedef XrResult (XRAPI_CALL *LF)(XrSession, const XrViewLocateInfo *,
                                                      XrViewState *, uint32_t, uint32_t *, XrView *);
                    static LF lf;
                    if (!lf) lf = (LF)real_proc("xrLocateViews");
                    if (lf) {
                        XrViewLocateInfo li;
                        memset(&li, 0, sizeof(li));
                        li.type = XR_TYPE_VIEW_LOCATE_INFO;
                        li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                        li.displayTime = frameEndInfo->displayTime;
                        li.space = p->space;
                        XrViewState vs;
                        memset(&vs, 0, sizeof(vs));
                        vs.type = XR_TYPE_VIEW_STATE;
                        XrView vv[2];
                        memset(vv, 0, sizeof(vv));
                        vv[0].type = XR_TYPE_VIEW;
                        vv[1].type = XR_TYPE_VIEW;
                        uint32_t cnt = 2;
                        if (XR_SUCCEEDED(lf(session, &li, &vs, 2, &cnt, vv)) && cnt >= 2) {
                            views[i][0].pose = vv[0].pose;
                            views[i][1].pose = vv[1].pose;
                            static int once;
                            if (once < 3) {
                                ++once;
                                LOGI("locate-live eye dx=%.4f dz=%.4f",
                                     vv[1].pose.position.x - vv[0].pose.position.x,
                                     vv[1].pose.position.z - vv[0].pose.position.z);
                            }
                        }
                    }
                } else if (mode == 8) {
                    /* fill the field of view: eye offset from the app's pose,
                     * projection from the runtime (wider than PICO's) */
                    static float ipd2 = -1.0f;
                    if (ipd2 < 0.0f) {
                        char v[PROP_VALUE_MAX] = {0};
                        __system_property_get("persist.pico.xr.ipd", v);
                        ipd2 = v[0] ? (float)atof(v) : 0.064f;
                    }
                    const XrPosef base2 = views[i][0].pose;
                    const float qx = base2.orientation.x, qy = base2.orientation.y;
                    const float qz = base2.orientation.z, qw = base2.orientation.w;
                    const float ax = 2.0f * (qx * qx + qw * qw) - 1.0f;
                    const float az = 2.0f * (qx * qz - qw * qy);
                    const float s2 = ipd2 * 0.5f;
                    views[i][0].pose = base2;
                    views[i][1].pose = base2;
                    views[i][0].pose.position.x = base2.position.x - s2 * ax;
                    views[i][0].pose.position.z = base2.position.z - s2 * az;
                    views[i][1].pose.position.x = base2.position.x + s2 * ax;
                    views[i][1].pose.position.z = base2.position.z + s2 * az;
                    if (g_eye_valid) {
                        views[i][0].fov = g_eye_left.fov;
                        views[i][1].fov = g_eye_right.fov;
                    }
                } else if (mode == 7 || mode == 9) {
                    /* The app supplies one head pose per frame and leaves PICO's
                     * runtime to split the eyes. Meta needs explicit per-eye
                     * poses, so offset the app's pose by half the IPD. */
                    /* read every frame so the value can be tuned live */
                    char v[PROP_VALUE_MAX] = {0};
                    __system_property_get("persist.pico.xr.ipd", v);
                    float ipd = v[0] ? (float)atof(v) : 0.055f;
                    const XrPosef base = views[i][0].pose;
                    const float hx = base.orientation.x, hy = base.orientation.y;
                    const float hz = base.orientation.z, hw = base.orientation.w;
                    /* rotate (dx,0,0) by the head quaternion */
                    float rx = 2.0f * (hx * hx + hw * hw) - 1.0f;
                    float corner = 2.0f * hw * hz;
                    float rz = 2.0f * (hx * hz - hw * hy);
                    float scale = ipd * 0.5f;
                    XrPosef left = base, right = base;
                    left.position.x = base.position.x - scale * rx;
                    left.position.z = base.position.z - scale * rz;
                    right.position.x = base.position.x + scale * rx;
                    right.position.z = base.position.z + scale * rz;
                    views[i][0].pose = left;
                    views[i][1].pose = right;
                    if (mode == 9) {
                        XrSwapchainSubImage t9 = views[i][0].subImage;
                        views[i][0].subImage = views[i][1].subImage;
                        views[i][1].subImage = t9;
                    }
                } else if (mode == 6 && g_eye_valid) {
                    /* Eye position from the runtime, projection (fov) from the
                     * app: the app rendered the image for its own fov, so only
                     * the eye offset may be replaced. */
                    views[i][0].pose = g_eye_left.pose;
                    views[i][1].pose = g_eye_right.pose;
                } else if (mode >= 4 && g_eye_valid) {
                    /* PICO apps repeat the head pose for both eyes; the runtime
                     * needs real per-eye pose and fov. */
                    views[i][0].pose = g_eye_left.pose;
                    views[i][0].fov = g_eye_left.fov;
                    views[i][1].pose = g_eye_right.pose;
                    views[i][1].fov = g_eye_right.fov;
                    if (mode == 5) {
                        XrSwapchainSubImage t2 = views[i][0].subImage;
                        views[i][0].subImage = views[i][1].subImage;
                        views[i][1].subImage = t2;
                    }
                } else if (mode == 3) {     /* mirror the fov inside each view */
                    for (int k = 0; k < 2; ++k) {
                        float l2 = views[i][k].fov.angleLeft;
                        float r2 = views[i][k].fov.angleRight;
                        views[i][k].fov.angleLeft = -r2;
                        views[i][k].fov.angleRight = -l2;
                    }
                }
                proj[i].views = views[i];
                layers[i] = (const XrCompositionLayerBaseHeader *)&proj[i];
                ++touched;
                continue;
            }
        }
        layers[i] = l;
    }

    if (touched > 0) {
        /* PICO apps render with a horizontal projection centre offset that
         * PICO's runtime learns through XR_BD_composition_layer_settings.
         * Meta has no such channel, so the two eye images end up displaced by a
         * large fixed amount. Shift each eye's frustum horizontally by the same
         * angle to bring them back together. Read every frame: tunable live. */
        char fs[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.pico.xr.fovshift", fs);
        const float shift = fs[0] ? (float)atof(fs) : 0.0f;
        char vs[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.pico.xr.fovvshift", vs);
        const float vshift = vs[0] ? (float)atof(vs) : 0.0f;
        char vm[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.pico.xr.vmode", vm);
        const int vm_differential = (vm[0] == '1');
        if (shift != 0.0f) {
            for (uint32_t i = 0; i < frameEndInfo->layerCount; ++i) {
                if (layers[i] != (const XrCompositionLayerBaseHeader *)&proj[i]) continue;
                const float s = (i == 0 ? shift : shift);
                views[i][0].fov.angleLeft += s;
                views[i][0].fov.angleRight += s;
                views[i][1].fov.angleLeft -= s;
                views[i][1].fov.angleRight -= s;
                views[i][0].fov.angleUp += vshift;
                views[i][0].fov.angleDown += vshift;
                if (vm_differential) {
                    /* one eye up, the other down: fixes a relative tilt */
                    views[i][1].fov.angleUp -= vshift;
                    views[i][1].fov.angleDown -= vshift;
                } else {
                    /* both eyes move together: fixes a common offset */
                    views[i][1].fov.angleUp += vshift;
                    views[i][1].fov.angleDown += vshift;
                }
            }
            static int once;
            if (once < 3) { ++once; LOGI("fovshift %.4f vshift %.4f diff=%d", shift, vshift, vm_differential); }
        }
    }

    if (touched == 0) {
        return fn(session, frameEndInfo);
    }
    XrFrameEndInfo info = *frameEndInfo;
    info.layers = layers;
    return fn(session, &info);
}

XRAPI_ATTR XrResult XRAPI_CALL xrGetInstanceProcAddr(
        XrInstance instance, const char *name, PFN_xrVoidFunction *function) {
    typedef XrResult (XRAPI_CALL *FN)(XrInstance, const char *, PFN_xrVoidFunction *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrGetInstanceProcAddr");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    if (name && function) {
        /*
         * The app resolves its entry points through this call. Delegating it to
         * the real loader hands out the real implementations and silently
         * bypasses every override in this file (eye fix-up, battery, live eye
         * poses). Answer with our own wrappers instead.
         */
        static const struct {
            const char *n;
            PFN_xrVoidFunction f;
        } table[] = {
/*__PROC_TABLE__*/
        };
        for (unsigned i = 0; i < sizeof(table) / sizeof(table[0]); ++i) {
            if (!strcmp(table[i].n, name)) {
                *function = table[i].f;
                static int hits;
                if (hits < 25) { ++hits; LOGI("gipa hit %s", name); }
                return XR_SUCCESS;
            }
        }
        static int misses;
        if (misses < 25) { ++misses; LOGI("gipa miss %s", name); }
    }
    return fn(instance, name, function);
}

XRAPI_ATTR XrResult XRAPI_CALL xrInitializeLoaderKHR(
        const XrLoaderInitInfoBaseHeaderKHR *info) {
    typedef XrResult (XRAPI_CALL *FN)(const XrLoaderInitInfoBaseHeaderKHR *);
    static FN fn;
    if (!fn) fn = (FN)real_proc("xrInitializeLoaderKHR");
    if (!fn) return XR_ERROR_FUNCTION_UNSUPPORTED;
    return fn(info);
}
''')

table_names = list(SYMS) + [s for s in sorted(SPECIAL) if s not in SYMS]
table = ",\n".join('            {"%s", (PFN_xrVoidFunction)%s}' % (s, s) for s in table_names)
text = "\n".join(out).replace("/*__PROC_TABLE__*/", table)
open(OUT, "w", encoding="utf-8").write(text)
print("generated", OUT, "trampolines:", len(SYMS) - len(SPECIAL & set(SYMS)))
