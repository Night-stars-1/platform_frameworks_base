/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Island settings screen.
 *
 * <p>Reachable from uwuAOSP Plus (uwuSettingsExt), and always available even when no
 * notification is currently eligible for the island. It is the only place that mutates island
 * preferences; the exported query provider is read-only, so an unprivileged application can never
 * change island behaviour.
 */
public final class IslandSettingsActivity extends Activity {

    private static final String TAG = "IslandSettingsActivity";

    private IslandPreferences mPreferences;
    private Switch mGlobalSwitch;
    private TextView mStatusView;
    private AppListAdapter mAdapter;

    private final List<AppRow> mRows = new ArrayList<>();

    private final IslandPreferences.Listener mPreferenceListener = this::refresh;

    private static final class AppRow {
        final String packageName;
        final CharSequence label;
        boolean allowed;

        AppRow(String packageName, CharSequence label, boolean allowed) {
            this.packageName = packageName;
            this.label = label;
            this.allowed = allowed;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPreferences = new IslandPreferences(this);
        setContentView(buildContentView());
        mPreferences.addListener(mPreferenceListener);
        loadApps();
        refresh();
    }

    @Override
    protected void onDestroy() {
        mPreferences.removeListener(mPreferenceListener);
        super.onDestroy();
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("灵动岛");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        root.addView(title);

        mStatusView = new TextView(this);
        mStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mStatusView.setAlpha(0.7f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(6);
        root.addView(mStatusView, statusParams);

        LinearLayout globalRow = new LinearLayout(this);
        globalRow.setOrientation(LinearLayout.HORIZONTAL);
        globalRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams globalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        globalParams.topMargin = dp(20);

        TextView globalLabel = new TextView(this);
        globalLabel.setText("启用灵动岛");
        globalLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        globalRow.addView(globalLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mGlobalSwitch = new Switch(this);
        mGlobalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) {
                return;
            }
            mPreferences.setEnabled(isChecked);
        });
        globalRow.addView(mGlobalSwitch);
        root.addView(globalRow, globalParams);

        TextView appsTitle = new TextView(this);
        appsTitle.setText("按应用控制");
        appsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        appsTitle.setAlpha(0.7f);
        LinearLayout.LayoutParams appsTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        appsTitleParams.topMargin = dp(24);
        root.addView(appsTitle, appsTitleParams);

        ListView list = new ListView(this);
        mAdapter = new AppListAdapter();
        list.setAdapter(mAdapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(list, listParams);

        return root;
    }

    /**
     * Lists every user visible application plus any package the user already configured, so a
     * disabled package never becomes unreachable.
     */
    private void loadApps() {
        PackageManager packageManager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<AppRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : packageManager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null || TextUtils.isEmpty(info.activityInfo.packageName)) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (!seen.add(packageName)) {
                continue;
            }
            rows.add(new AppRow(packageName, info.loadLabel(packageManager), true));
        }
        for (String packageName : mPreferences.getConfiguredPackages()) {
            if (!seen.add(packageName)) {
                continue;
            }
            CharSequence label = packageName;
            try {
                label = packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Configured package is no longer installed: " + packageName);
            }
            rows.add(new AppRow(packageName, label, true));
        }
        Collections.sort(rows, Comparator.comparing((AppRow row) -> row.label.toString(),
                String.CASE_INSENSITIVE_ORDER));

        mRows.clear();
        mRows.addAll(rows);
    }

    private void refresh() {
        boolean installed = IslandPreferences.isFeatureInstalled();
        mGlobalSwitch.setChecked(mPreferences.isGloballyEnabledRaw());
        mGlobalSwitch.setEnabled(installed);
        mStatusView.setText(installed
                ? "引擎已安装 · 协议 " + IslandPreferences.PROTOCOL_VERSION
                : "引擎未安装（缺少 ro.uwu.feature.island）");
        for (AppRow row : mRows) {
            row.allowed = mPreferences.isPackageAllowed(row.packageName);
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private final class AppListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Switch row = convertView instanceof Switch ? (Switch) convertView : new Switch(parent.getContext());
            AppRow app = mRows.get(position);
            row.setOnCheckedChangeListener(null);
            row.setText(app.label);
            row.setChecked(app.allowed);
            row.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                mPreferences.setPackageAllowed(app.packageName, isChecked);
            });
            return row;
        }
    }
}