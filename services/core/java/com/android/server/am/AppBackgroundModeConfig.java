/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

final class AppBackgroundModeConfig {
    static final int MODE_DEFAULT = Settings.Secure.UWU_APP_BACKGROUND_MODE_DEFAULT;
    static final int MODE_TOMBSTONE = Settings.Secure.UWU_APP_BACKGROUND_MODE_TOMBSTONE;
    static final int MODE_FULL = Settings.Secure.UWU_APP_BACKGROUND_MODE_FULL;
    static final int MODE_AUTO = Settings.Secure.UWU_APP_BACKGROUND_MODE_AUTO;

    static final int FREEZER_BACKEND_AUTO =
            Settings.Global.UWU_APP_BACKGROUND_FREEZER_BACKEND_AUTO;
    static final int FREEZER_BACKEND_CGROUP1 =
            Settings.Global.UWU_APP_BACKGROUND_FREEZER_BACKEND_CGROUP1;
    static final int FREEZER_BACKEND_CGROUP2 =
            Settings.Global.UWU_APP_BACKGROUND_FREEZER_BACKEND_CGROUP2;
    static final int FREEZER_BACKEND_HYBRID =
            Settings.Global.UWU_APP_BACKGROUND_FREEZER_BACKEND_HYBRID;
    static final int FREEZER_BACKEND_NONE = -1;

    static final int CGROUP_LAYOUT_NONE = 0;
    static final int CGROUP_LAYOUT_V1 = 1;
    static final int CGROUP_LAYOUT_V2 = 2;
    static final int CGROUP_LAYOUT_HYBRID = 3;

    static final long FREEZE_DELAY_MS = 3_000L;
    static final long AUDIO_STOP_FREEZE_DELAY_MS = 6_000L;
    static final long BINDER_RECOVERY_RETRY_DELAY_MS = 1_000L;

    static final class ParseResult {
        final ArrayMap<String, Integer> modes;
        final String normalized;
        final boolean changed;

        ParseResult(ArrayMap<String, Integer> modes, String normalized, boolean changed) {
            this.modes = modes;
            this.normalized = normalized;
            this.changed = changed;
        }
    }

    private AppBackgroundModeConfig() {}

    static ParseResult parse(@Nullable String value, @NonNull Predicate<String> packageAllowed) {
        final TreeMap<String, Integer> sorted = new TreeMap<>();
        boolean malformed = false;
        if (value != null && !value.isBlank()) {
            try {
                final JSONObject object = new JSONObject(value);
                final Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    final String packageName = keys.next();
                    int mode = object.optInt(packageName, MODE_DEFAULT);
                    if (mode == MODE_AUTO) {
                        mode = MODE_TOMBSTONE;
                        malformed = true;
                    }
                    if ((mode == MODE_TOMBSTONE || mode == MODE_FULL || mode == MODE_AUTO)
                            && packageAllowed.test(packageName)) {
                        sorted.put(packageName, mode);
                    } else {
                        malformed = true;
                    }
                }
            } catch (JSONException e) {
                malformed = true;
            }
        }

        final ArrayMap<String, Integer> modes = new ArrayMap<>(sorted.size());
        final JSONObject normalizedObject = new JSONObject();
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            modes.put(entry.getKey(), entry.getValue());
            try {
                normalizedObject.put(entry.getKey(), entry.getValue());
            } catch (JSONException impossible) {
                throw new AssertionError(impossible);
            }
        }
        final String normalized = sorted.isEmpty() ? null : normalizedObject.toString();
        final boolean changed = malformed || !equalNullable(value, normalized);
        return new ParseResult(modes, normalized, changed);
    }

    @VisibleForTesting
    static int resolveUidMode(int... packageModes) {
        if (packageModes == null || packageModes.length == 0) {
            return MODE_DEFAULT;
        }
        boolean allManagedByFreezer = true;
        boolean hasTombstone = false;
        for (int mode : packageModes) {
            if (mode == MODE_FULL) {
                return MODE_FULL;
            }
            if (mode == MODE_TOMBSTONE) {
                hasTombstone = true;
            } else if (mode != MODE_AUTO) {
                allManagedByFreezer = false;
            }
        }
        if (!allManagedByFreezer) {
            return MODE_DEFAULT;
        }
        return hasTombstone ? MODE_TOMBSTONE : MODE_AUTO;
    }

    @VisibleForTesting
    static boolean shouldIgnoreTaskRemoval(boolean enabled, int mode) {
        return enabled && (mode == MODE_TOMBSTONE || mode == MODE_FULL || mode == MODE_AUTO);
    }

    @VisibleForTesting
    static int detectCgroupLayout(@Nullable String mountInfo) {
        if (mountInfo == null || mountInfo.isBlank()) {
            return CGROUP_LAYOUT_NONE;
        }
        boolean cgroup1Freezer = false;
        boolean cgroup2 = false;
        for (String line : mountInfo.split("\\n")) {
            final int separator = line.indexOf(" - ");
            if (separator < 0) {
                continue;
            }
            final String mount = line.substring(0, separator);
            final String filesystem = line.substring(separator + 3);
            if (filesystem.startsWith("cgroup2 ")) {
                cgroup2 = true;
            } else if (filesystem.startsWith("cgroup ")
                    && (filesystem.contains("freezer") || mount.contains("/freezer"))) {
                cgroup1Freezer = true;
            }
        }
        if (cgroup1Freezer && cgroup2) {
            return CGROUP_LAYOUT_HYBRID;
        }
        if (cgroup1Freezer) {
            return CGROUP_LAYOUT_V1;
        }
        return cgroup2 ? CGROUP_LAYOUT_V2 : CGROUP_LAYOUT_NONE;
    }

    @VisibleForTesting
    static int normalizeFreezerBackend(int backend) {
        switch (backend) {
            case FREEZER_BACKEND_AUTO:
            case FREEZER_BACKEND_CGROUP1:
            case FREEZER_BACKEND_CGROUP2:
            case FREEZER_BACKEND_HYBRID:
                return backend;
            default:
                return FREEZER_BACKEND_AUTO;
        }
    }

    @VisibleForTesting
    static int resolveFreezerBackend(int requested, int layout, boolean freezerAvailable) {
        if (!freezerAvailable || layout == CGROUP_LAYOUT_NONE) {
            return FREEZER_BACKEND_NONE;
        }
        requested = normalizeFreezerBackend(requested);
        if (requested == FREEZER_BACKEND_CGROUP1
                && (layout == CGROUP_LAYOUT_V1 || layout == CGROUP_LAYOUT_HYBRID)) {
            return FREEZER_BACKEND_CGROUP1;
        }
        if (requested == FREEZER_BACKEND_CGROUP2
                && (layout == CGROUP_LAYOUT_V2 || layout == CGROUP_LAYOUT_HYBRID)) {
            return FREEZER_BACKEND_CGROUP2;
        }
        if (requested == FREEZER_BACKEND_HYBRID && layout == CGROUP_LAYOUT_HYBRID) {
            return FREEZER_BACKEND_HYBRID;
        }
        if (layout == CGROUP_LAYOUT_HYBRID) {
            return FREEZER_BACKEND_HYBRID;
        }
        return layout == CGROUP_LAYOUT_V1
                ? FREEZER_BACKEND_CGROUP1 : FREEZER_BACKEND_CGROUP2;
    }

    @VisibleForTesting
    static boolean isCoreApplication(@Nullable ApplicationInfo info, boolean criticalPackage) {
        return info == null || !UserHandle.isApp(info.uid)
                || (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0 || criticalPackage;
    }

    private static boolean equalNullable(@Nullable String first, @Nullable String second) {
        if (first == null) {
            return second == null;
        }
        return first.equals(second);
    }
}
