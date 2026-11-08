/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.text.TextUtils;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;

/**
 * Persistent island preferences.
 *
 * <p>Two levels of configuration are stored:
 *
 * <ul>
 *   <li>a global enable flag, and
 *   <li>per-application overrides, defaulting to "allowed" when the user never touched them.
 * </ul>
 *
 * <p>State is kept in device-protected storage of the current user so it survives direct boot and
 * is never readable or writable by unprivileged applications. Every mutation goes through this
 * class; nothing outside SystemUI can flip these switches (see {@link IslandPermissionProvider},
 * which only answers read-only queries).
 */
@SysUISingleton
public final class IslandPreferences {

    /**
     * Read-only capability flag installed by the ROM. It describes whether the island engine is
     * part of the build at all, it is not a user preference and is never written by SystemUI.
     */
    public static final String PROPERTY_FEATURE = "ro.uwu.feature.island";

    /**
     * System setting advertised to Xiaomi notification protocol clients. Only written while the
     * engine is actually running (see {@link IslandStartable#start()}).
     */
    public static final String SETTING_PROTOCOL = "notification_focus_protocol";
    public static final int PROTOCOL_VERSION = 3;

    private static final String PREFS_FILE = "island_settings";
    private static final String KEY_ENABLED = "island_enabled";
    private static final String KEY_APP_PREFIX = "app_enabled:";

    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    /**
     * Storage of the current user. Not final: the injected instance is a singleton while SystemUI
     * keeps running across a foreground user change, so the storage has to be rebound.
     */
    private SharedPreferences mPrefs;

    /**
     * Strongly held reference to the preference change listener. {@code SharedPreferences} keeps
     * listeners weakly, so a field is required for the callback to survive.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefChangeListener =
            (prefs, key) -> notifyChanged();

    /** Receives a callback whenever any island preference changes. Always called on main. */
    public interface Listener {
        void onIslandPreferencesChanged();
    }

    /** One row of the per-application configuration list. */
    public static final class AppEntry {
        public final String packageName;
        public final CharSequence label;
        public final boolean enabled;
        /** True when the user explicitly chose a value for this package. */
        public final boolean explicit;

        AppEntry(String packageName, CharSequence label, boolean enabled, boolean explicit) {
            this.packageName = packageName;
            this.label = label;
            this.enabled = enabled;
            this.explicit = explicit;
        }
    }

    @Inject
    public IslandPreferences(@Application Context context) {
        mPrefs = preferences(context);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefChangeListener);
    }

    private static SharedPreferences preferences(Context context) {
        Context storage = context;
        try {
            storage = context.createDeviceProtectedStorageContext();
        } catch (UnsupportedOperationException e) {
            // Device protected storage is unavailable on this build; the credential protected
            // storage of the same user is an acceptable fallback.
        }
        return storage.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Rebinds storage to the foreground user and moves the preference change listener with it.
     *
     * <p>The injected instance is a singleton, and SystemUI keeps running when the foreground user
     * changes, so without this the new user's island would read the previous user's configuration
     * and the settings screen would toggle values that do not apply. Callers are the startable
     * (once at start, then on every user change). The provider and the settings activity build their
     * own instance from their own user context and never call this.
     */
    public void onUserChanged(Context userContext) {
        if (userContext == null) {
            return;
        }
        SharedPreferences next = preferences(userContext);
        if (next == mPrefs) {
            return;
        }
        mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefChangeListener);
        mPrefs = next;
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefChangeListener);
        notifyChanged();
    }

    /** Whether this ROM contains the island engine at all. Read-only capability property. */
    public static boolean isFeatureInstalled() {
        return SystemProperties.getBoolean(PROPERTY_FEATURE, false);
    }

    /** Global island switch, combined with the ROM capability flag. */
    public boolean isEnabled() {
        return isFeatureInstalled() && mPrefs.getBoolean(KEY_ENABLED, true);
    }

    /** Raw global switch, ignoring the capability flag. Used by the settings UI. */
    public boolean isGloballyEnabledRaw() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        notifyChanged();
    }

    /** Per-application switch; defaults to allowed. */
    public boolean isPackageAllowed(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        return mPrefs.getBoolean(KEY_APP_PREFIX + packageName, true);
    }

    /** True when the user explicitly configured this package. */
    public boolean isPackageConfigured(String packageName) {
        return !TextUtils.isEmpty(packageName) && mPrefs.contains(KEY_APP_PREFIX + packageName);
    }

    public void setPackageAllowed(String packageName, boolean allowed) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        mPrefs.edit().putBoolean(KEY_APP_PREFIX + packageName, allowed).apply();
        notifyChanged();
    }

    /**
     * Packages the user explicitly configured. Used by the settings UI so a disabled package stays
     * reachable even when it currently has no launcher entry.
     */
    public List<String> getConfiguredPackages() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(KEY_APP_PREFIX) && entry.getValue() instanceof Boolean) {
                result.add(key.substring(KEY_APP_PREFIX.length()));
            }
        }
        Collections.sort(result);
        return result;
    }

    public void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Listener listener : mListeners) {
            listener.onIslandPreferencesChanged();
        }
    }
}