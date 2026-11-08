// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import java.io.DataOutputStream;
import java.util.List;

/**
 * Where the VR rules live.
 *
 * System properties cannot hold them: Android caps a property value at
 * PROP_VALUE_MAX (92 bytes), and a couple of "pkg/activity.Class" entries blow
 * past that immediately —
 *
 *   Value '...' is too long, 144 bytes vs a max of 92
 *
 * So the rules go into a small world-readable file under /data/local/tmp, which
 * both this app and the hook inside com.oculus.vrshell can read. Writing it
 * needs root, hence the same `su` dance as before.
 *
 * File format, one rule per line:
 *
 *   P com.picovr.picostreamassistant                       (whole app)
 *   A com.picovr.store/com.picovr.store.MainActivity       (single activity)
 */
final class RuleStore {
    static final String PATH = "/data/local/tmp/pico_xr_rules.conf";

    private RuleStore() {
    }

    /** Serialises rules and writes them through su. Returns true on success. */
    static boolean save(List<String> rules) {
        StringBuilder sb = new StringBuilder();
        for (String rule : rules) {
            if (rule != null && !rule.isEmpty()) {
                sb.append(rule).append('\n');
            }
        }
        String content = sb.toString();
        String[] candidates = {"/product/bin/su", "su", "/system/bin/su",
                               "/system/xbin/su", "/debug_ramdisk/su"};
        for (String su : candidates) {
            if (write(su, content)) {
                return true;
            }
        }
        return false;
    }

    private static boolean write(String su, String content) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    su, "-c", "cat > " + PATH + " && chmod 644 " + PATH});
            DataOutputStream out = new DataOutputStream(p.getOutputStream());
            out.writeBytes(content);
            out.flush();
            out.close();
            p.waitFor();
        } catch (Throwable t) {
            if (p != null) {
                p.destroy();
            }
            return false;
        }
        // Verify by reading the file back as this (non-root) process.
        String current = readAll();
        return current != null && current.trim().equals(content.trim());
    }

    /** Raw file contents, or null when it cannot be read. */
    static String readAll() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(PATH);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            in.close();
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
