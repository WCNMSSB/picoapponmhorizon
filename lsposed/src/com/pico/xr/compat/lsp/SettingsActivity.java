// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
//
// This file is part of picoapponmhorizon.
package com.pico.xr.compat.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * App list: pick which PICO apps Horizon should open in VR.
 *
 * The choice is stored in persist.pico.vr.packages (comma separated package
 * names), which the hook inside com.oculus.vrshell reads. Writing a persist.*
 * property needs root, so saving goes through `su -c setprop`.
 *
 * A finer per-activity list still exists (persist.pico.vr.activities) for apps
 * whose VR entry point is not the launcher activity, e.g. PICO Connect's
 * com.picoxr.streaming.deviceservice.StreamingGameActivity.
 *
 * Forcing a purely 2D app (the PICO Store, for instance) makes it open immersive
 * and its layout can break; that is the user's choice here.
 *
 * Material 3 look with framework views only: pulling com.google.android.material
 * would drag in appcompat and its whole resource tree, which this gradle-less
 * build cannot merge.
 *
 * Note: no anonymous inner classes. d8 in build-tools 34 crashes with an
 * internal NPE on the MethodParameters attribute javac emits for them.
 */
public class SettingsActivity extends Activity implements View.OnClickListener,
        CompoundButton.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {

    private static final String PREFS = "pico_compat";
    private static final String KEY_PACKAGES = "vr_packages";
    private static final String KEY_LANG = "lang";
    private static final String PROP_PACKAGES = "persist.pico.vr.packages";

    private static final int BG = 0xFF1C1B1F;
    private static final int SURFACE = 0xFF211F26;
    private static final int ON_SURFACE = 0xFFE6E1E5;
    private static final int ON_SURFACE_VARIANT = 0xFFCAC4D0;
    private static final int PRIMARY = 0xFFD0BCFF;
    private static final int ON_PRIMARY = 0xFF381E72;
    private static final int OUTLINE = 0xFF49454F;

    /** Opens the activity picker for one package. */
    private static final class RowClick implements View.OnClickListener {
        private final SettingsActivity mActivity;
        private final String mPkg;

        RowClick(SettingsActivity activity, String pkg) {
            mActivity = activity;
            mPkg = pkg;
        }

        @Override
        public void onClick(View v) {
            Intent intent = new Intent(mActivity, ActivityPickerActivity.class);
            intent.putExtra("pkg", mPkg);
            mActivity.startActivity(intent);
        }
    }

    /** One row of the list. */
    private static final class AppEntry {
        String pkg;
        String label;
        String version;
        Drawable icon;
        boolean hasXr;
    }

    private final List<AppEntry> mApps = new ArrayList<>();
    private final List<Switch> mSwitches = new ArrayList<>();
    private LinearLayout mList;
    private TextView mSummary;
    private Spinner mLang;
    private boolean mZh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mZh = !"en".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "zh"));
        buildUi();
        loadApps();
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

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextColor(ON_SURFACE);
        title.setTextSize(20f);
        title.setText(t("PICO 应用（Meta Horizon）", "PICO apps on Meta Horizon"));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mLang = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"简体中文", "English"});
        mLang.setAdapter(adapter);
        mLang.setSelection(mZh ? 0 : 1);
        mLang.setOnItemSelectedListener(this);
        header.addView(mLang, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header);

        TextView hint = new TextView(this);
        hint.setTextColor(ON_SURFACE_VARIANT);
        hint.setTextSize(13f);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        hint.setText(t("勾选的应用会以 VR 全屏打开；未勾选的保持普通面板。\n保存需要 root（Magisk）。",
                "Tick an app to open it in VR; unticked apps stay normal panels.\n"
                        + "Saving needs root (Magisk)."));
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
    }

    @Override
    public void onClick(View v) {
        save();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        updateSummary();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        boolean zh = position == 0;
        if (zh == mZh) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LANG, zh ? "zh" : "en").apply();
        recreate();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    private boolean isPico(String pkg) {
        return pkg.startsWith("com.picovr.")
            || pkg.startsWith("com.pico.")
            || pkg.startsWith("com.picoxr.")
            || pkg.startsWith("com.pvr.")
            || pkg.startsWith("com.pxr.")
            || pkg.startsWith("com.bytedance.pico.");
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<AppEntry> apps = new ArrayList<>();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            return;
        }
        for (PackageInfo pi : packages) {
            if (!isPico(pi.packageName)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pi.packageName;
            e.version = pi.versionName == null ? "" : pi.versionName;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
                e.label = ai.loadLabel(pm).toString();
                e.icon = ai.loadIcon(pm);
                String[] libs = new java.io.File(ai.nativeLibraryDir).list();
                if (libs != null) {
                    for (String lib : libs) {
                        if (lib.contains("openxr") || lib.contains("pxr_api")
                                || lib.contains("PxrPlatform")) {
                            e.hasXr = true;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                e.label = e.pkg;
            }
            apps.add(e);
        }
        // Plain insertion sort: a Comparator implementation made d8 crash.
        for (int i = 1; i < apps.size(); ++i) {
            AppEntry cur = apps.get(i);
            int j = i - 1;
            while (j >= 0 && apps.get(j).label.compareToIgnoreCase(cur.label) > 0) {
                apps.set(j + 1, apps.get(j));
                --j;
            }
            apps.set(j + 1, cur);
        }
        mApps.clear();
        mApps.addAll(apps);
        fillList();
    }

    private void fillList() {
        float density = getResources().getDisplayMetrics().density;
        int iconSize = (int) (40 * density);
        int gap = (int) (12 * density);

        for (AppEntry app : mApps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (8 * density), 0, (int) (8 * density));

            ImageView icon = new ImageView(this);
            if (app.icon != null) {
                icon.setImageDrawable(app.icon);
            } else {
                icon.setBackground(rounded(OUTLINE, 8 * density));
            }
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.rightMargin = gap;
            row.addView(icon, iconParams);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(this);
            name.setText(app.hasXr ? app.label + "  [XR]" : app.label);
            name.setTextColor(ON_SURFACE);
            name.setTextSize(15f);
            texts.addView(name);

            TextView sub = new TextView(this);
            sub.setText(app.pkg + (app.version.isEmpty() ? "" : "  " + app.version));
            sub.setTextColor(ON_SURFACE_VARIANT);
            sub.setTextSize(11f);
            texts.addView(sub);

            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(this);
            sw.setTag(app.pkg);
            sw.setOnCheckedChangeListener(this);
            row.addView(sw, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mSwitches.add(sw);

            // Tapping the row opens the per-activity picker for this app.
            TextView chevron = new TextView(this);
            chevron.setText("›");
            chevron.setTextSize(22f);
            chevron.setTextColor(ON_SURFACE_VARIANT);
            chevron.setPadding(gap / 2, 0, 0, 0);
            row.addView(chevron);
            row.setOnClickListener(new RowClick(this, app.pkg));

            mList.addView(row);

            View divider = new View(this);
            divider.setBackgroundColor(OUTLINE);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (0.5 * density));
            dp.leftMargin = iconSize + gap;
            mList.addView(divider, dp);
        }

        if (mApps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("没找到 PICO 应用", "No PICO apps found"));
            empty.setTextColor(ON_SURFACE_VARIANT);
            mList.addView(empty);
        }
    }

    private void loadSelection() {
        java.util.List<String> selected = new java.util.ArrayList<>();
        String text = RuleStore.readAll();
        if (text != null) {
            for (String line : text.split("\\n")) {
                line = line.trim();
                if (line.startsWith("P ")) {
                    selected.add(line.substring(2).trim());
                }
            }
        }
        for (Switch sw : mSwitches) {
            sw.setChecked(selected.contains((String) sw.getTag()));
        }
    }

    private String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
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
            mSummary.setText(n == 0 ? "尚未选择任何应用" : ("已选择 " + n + " 个应用"));
        } else {
            mSummary.setText(n == 0 ? "No app selected"
                    : (n + (n == 1 ? " app selected" : " apps selected")));
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Switch sw : mSwitches) {
            if (sw.isChecked()) {
                sb.append("P ").append((String) sw.getTag()).append('\n');
            }
        }
        String value = sb.toString();
        new Thread(new SaveTask(this, value)).start();
    }

    /** Runs `su -c setprop` off the UI thread. */
    private static final class SaveTask implements Runnable {
        private final SettingsActivity mActivity;
        private final String mValue;

        SaveTask(SettingsActivity activity, String value) {
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

    /** Shows the result on the UI thread. */
    private static final class ToastTask implements Runnable {
        private final SettingsActivity mActivity;
        private final boolean mOk;

        ToastTask(SettingsActivity activity, boolean ok) {
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
