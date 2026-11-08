// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Second level of the picker: which activities of one app should open in VR.
 *
 * Rules are written to persist.pico.vr.activities as "pkg/activity.Class"
 * entries; entries of other packages are preserved. This is what makes apps
 * whose VR entry point is not the launcher activity work, e.g. PICO Connect's
 * com.picoxr.streaming.deviceservice.StreamingGameActivity.
 */
public class ActivityPickerActivity extends Activity implements View.OnClickListener,
        CompoundButton.OnCheckedChangeListener {

    private static final String PREFS = "pico_compat";
    private static final String KEY_LANG = "lang";
    private static final String PROP_ACTIVITIES = "persist.pico.vr.activities";

    private static final int BG = 0xFF1C1B1F;
    private static final int SURFACE = 0xFF211F26;
    private static final int ON_SURFACE = 0xFFE6E1E5;
    private static final int ON_SURFACE_VARIANT = 0xFFCAC4D0;
    private static final int PRIMARY = 0xFFD0BCFF;
    private static final int ON_PRIMARY = 0xFF381E72;
    private static final int OUTLINE = 0xFF49454F;

    private final List<String> mClasses = new ArrayList<>();
    private final List<Switch> mSwitches = new ArrayList<>();
    private LinearLayout mList;
    private TextView mSummary;
    private String mPkg = "";
    private boolean mZh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mZh = !"en".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "zh"));
        Intent intent = getIntent();
        String pkg = intent == null ? null : intent.getStringExtra("pkg");
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }
        mPkg = pkg;

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setTextColor(ON_SURFACE);
        title.setTextSize(18f);
        title.setText(t("选择 Activity", "Choose activities"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setTextColor(ON_SURFACE_VARIANT);
        sub.setTextSize(12f);
        sub.setText(mPkg);
        root.addView(sub);

        TextView hint = new TextView(this);
        hint.setTextColor(ON_SURFACE_VARIANT);
        hint.setTextSize(13f);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        hint.setText(t("打开的应用会以 VR 全屏进入；不勾选的保持 2D 面板。",
                "Ticked activities open in VR; the rest stay 2D panels."));
        root.addView(hint);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(SURFACE, 24 * density));

        ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, pad / 2, pad, pad / 2);
        scroll.addView(mList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mSummary = new TextView(this);
        mSummary.setTextColor(ON_SURFACE_VARIANT);
        mSummary.setTextSize(12f);
        mSummary.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(mSummary);

        TextView save = new TextView(this);
        save.setTextColor(ON_PRIMARY);
        save.setTextSize(15f);
        save.setGravity(Gravity.CENTER);
        save.setBackground(rounded(PRIMARY, 24 * density));
        save.setPadding(pad, (int) (12 * density), pad, (int) (12 * density));
        save.setText(t("保存并应用", "Save and apply"));
        save.setOnClickListener(this);
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        loadActivities();
        loadSelection();
        updateSummary();
    }

    private String t(String zh, String en) {
        return mZh ? zh : en;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void loadActivities() {
        PackageManager pm = getPackageManager();
        float density = getResources().getDisplayMetrics().density;
        PackageInfo pi = null;
        try {
            pi = pm.getPackageInfo(mPkg, PackageManager.GET_ACTIVITIES);
        } catch (Throwable ignored) {
        }
        if (pi == null || pi.activities == null) {
            TextView empty = new TextView(this);
            empty.setText(t("这个应用没有可选的 Activity", "No activities found"));
            empty.setTextColor(ON_SURFACE_VARIANT);
            mList.addView(empty);
            return;
        }
        for (ActivityInfo ai : pi.activities) {
            if (ai.name == null) {
                continue;
            }
            mClasses.add(ai.name);
            Switch sw = new Switch(this);
            sw.setText(ai.name);
            sw.setTextColor(ON_SURFACE);
            sw.setTextSize(12f);
            sw.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            sw.setOnCheckedChangeListener(this);
            mList.addView(sw);
            mSwitches.add(sw);
        }
    }

    private void loadSelection() {
        java.util.List<String> selected = new java.util.ArrayList<>();
        String text = RuleStore.readAll();
        if (text != null) {
            for (String line : text.split("\\n")) {
                line = line.trim();
                if (!line.startsWith("A ")) {
                    continue;
                }
                String entry = line.substring(2).trim();
                int slash = entry.indexOf('/');
                if (slash > 0 && mPkg.equals(entry.substring(0, slash))) {
                    selected.add(entry.substring(slash + 1));
                }
            }
        }
        for (int i = 0; i < mSwitches.size(); ++i) {
            mSwitches.get(i).setChecked(selected.contains(mClasses.get(i)));
        }
    }

    private String readProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_ACTIVITIES, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void updateSummary() {
        int n = 0;
        for (Switch sw : mSwitches) {
            if (sw.isChecked()) {
                ++n;
            }
        }
        if (mZh) {
            mSummary.setText(n == 0 ? "未选择" : ("已选择 " + n + " 个 Activity"));
        } else {
            mSummary.setText(n == 0 ? "None selected"
                    : (n + (n == 1 ? " activity selected" : " activities selected")));
        }
    }

    @Override
    public void onClick(View v) {
        save();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        updateSummary();
    }

    private void save() {
        // Keep P rules and other packages' A rules, replace this package's A rules.
        StringBuilder sb = new StringBuilder();
        String text = RuleStore.readAll();
        if (text != null) {
            for (String line : text.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("A ")) {
                    String entry = line.substring(2).trim();
                    int slash = entry.indexOf('/');
                    String pkg = slash > 0 ? entry.substring(0, slash) : null;
                    if (pkg != null && pkg.equals(mPkg)) {
                        continue;
                    }
                }
                sb.append(line).append('\n');
            }
        }
        for (int i = 0; i < mSwitches.size(); ++i) {
            if (mSwitches.get(i).isChecked()) {
                sb.append("A ").append(mPkg).append('/').append(mClasses.get(i)).append('\n');
            }
        }
        String value = sb.toString();
        new Thread(new SaveTask(this, value)).start();
    }

    private static final class SaveTask implements Runnable {
        private final ActivityPickerActivity mActivity;
        private final String mValue;

        SaveTask(ActivityPickerActivity activity, String value) {
            mActivity = activity;
            mValue = value;
        }

        @Override
        public void run() {
            boolean ok = RuleStore.save(java.util.Arrays.asList(mValue.split("\n")));
            mActivity.runOnUiThread(new ToastTask(mActivity, ok));
        }

        /**
         * `su` is not on an app process' PATH on this device (it lives at
         * /product/bin/su), and Magisk must also have granted root to this uid.
         * Try the known locations, then verify by reading the property back.
         */
        private static boolean setProp(String key, String value) {
            String safe = value.replace("'", "");
            String[] candidates = {"/product/bin/su", "su", "/system/bin/su",
                                   "/system/xbin/su", "/debug_ramdisk/su"};
            for (String su : candidates) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                            su, "-c", "setprop " + key + " '" + safe + "'"});
                    p.waitFor();
                } catch (Throwable ignored) {
                    continue;
                }
                String now = readProp(key);
                android.util.Log.i("PicoSave", "su=" + su + " want=" + value
                        + " got=" + now);
                if (value.equals(now)) {
                    return true;
                }
            }
            return false;
        }

        private static String readProp(String key) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                return (String) sp.getMethod("get", String.class, String.class)
                        .invoke(null, key, "");
            } catch (Throwable t) {
                return "";
            }
        }
    }

    private static final class ToastTask implements Runnable {
        private final ActivityPickerActivity mActivity;
        private final boolean mOk;

        ToastTask(ActivityPickerActivity activity, boolean ok) {
            mActivity = activity;
            mOk = ok;
        }

        @Override
        public void run() {
            Toast.makeText(mActivity,
                    mOk ? (mActivity.mZh ? "已保存，重启应用后生效" : "Saved. Restart the app to apply.")
                        : (mActivity.mZh ? "保存失败：请在 Magisk 里允许本应用使用 root" : "Failed: root denied. Allow this app in Magisk."),
                    Toast.LENGTH_LONG).show();
        }
    }
}
