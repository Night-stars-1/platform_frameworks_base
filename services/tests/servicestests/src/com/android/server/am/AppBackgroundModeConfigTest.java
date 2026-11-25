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

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ApplicationInfo;
import android.os.Process;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class AppBackgroundModeConfigTest {
    @Test
    public void parse_normalizesAndSortsAllowedModes() {
        final AppBackgroundModeConfig.ParseResult result = AppBackgroundModeConfig.parse(
                "{\"z.package\":2,\"a.package\":1,\"auto.package\":3}", packageName -> true);

        assertThat(result.modes).containsExactly(
                "a.package", AppBackgroundModeConfig.MODE_TOMBSTONE,
                "auto.package", AppBackgroundModeConfig.MODE_TOMBSTONE,
                "z.package", AppBackgroundModeConfig.MODE_FULL).inOrder();
        assertThat(result.normalized)
                .isEqualTo("{\"a.package\":1,\"auto.package\":1,\"z.package\":2}");
        assertThat(result.changed).isTrue();
    }

    @Test
    public void parse_dropsMalformedAndDisallowedEntries() {
        final AppBackgroundModeConfig.ParseResult result = AppBackgroundModeConfig.parse(
                "{\"allowed\":1,\"default\":0,\"invalid\":7,\"blocked\":2}",
                packageName -> !packageName.equals("blocked"));

        assertThat(result.modes).containsExactly(
                "allowed", AppBackgroundModeConfig.MODE_TOMBSTONE);
        assertThat(result.normalized).isEqualTo("{\"allowed\":1}");
        assertThat(result.changed).isTrue();
    }

    @Test
    public void parse_invalidJsonReturnsEmptyConfig() {
        final AppBackgroundModeConfig.ParseResult result = AppBackgroundModeConfig.parse(
                "not-json", packageName -> true);

        assertThat(result.modes).isEmpty();
        assertThat(result.normalized).isNull();
        assertThat(result.changed).isTrue();
    }

    @Test
    public void resolveUidMode_usesSafestSharedUidMode() {
        assertThat(AppBackgroundModeConfig.resolveUidMode()).isEqualTo(
                AppBackgroundModeConfig.MODE_DEFAULT);
        assertThat(AppBackgroundModeConfig.resolveUidMode(
                AppBackgroundModeConfig.MODE_TOMBSTONE,
                AppBackgroundModeConfig.MODE_TOMBSTONE)).isEqualTo(
                AppBackgroundModeConfig.MODE_TOMBSTONE);
        assertThat(AppBackgroundModeConfig.resolveUidMode(
                AppBackgroundModeConfig.MODE_TOMBSTONE,
                AppBackgroundModeConfig.MODE_DEFAULT)).isEqualTo(
                AppBackgroundModeConfig.MODE_DEFAULT);
        assertThat(AppBackgroundModeConfig.resolveUidMode(
                AppBackgroundModeConfig.MODE_DEFAULT,
                AppBackgroundModeConfig.MODE_FULL)).isEqualTo(
                AppBackgroundModeConfig.MODE_FULL);
        assertThat(AppBackgroundModeConfig.resolveUidMode(
                AppBackgroundModeConfig.MODE_AUTO,
                AppBackgroundModeConfig.MODE_AUTO)).isEqualTo(
                AppBackgroundModeConfig.MODE_AUTO);
        assertThat(AppBackgroundModeConfig.resolveUidMode(
                AppBackgroundModeConfig.MODE_TOMBSTONE,
                AppBackgroundModeConfig.MODE_AUTO)).isEqualTo(
                AppBackgroundModeConfig.MODE_TOMBSTONE);
    }

    @Test
    public void shouldIgnoreTaskRemoval_requiresEnabledManagedMode() {
        assertThat(AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                false, AppBackgroundModeConfig.MODE_FULL)).isFalse();
        assertThat(AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                true, AppBackgroundModeConfig.MODE_DEFAULT)).isFalse();
        assertThat(AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                true, AppBackgroundModeConfig.MODE_TOMBSTONE)).isTrue();
        assertThat(AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                true, AppBackgroundModeConfig.MODE_FULL)).isTrue();
        assertThat(AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                true, AppBackgroundModeConfig.MODE_AUTO)).isTrue();
    }

    @Test
    public void detectCgroupLayout_readsMountedHierarchies() {
        final String v1 = "31 23 0:27 / /dev/freezer rw - cgroup cgroup rw,freezer";
        final String v2 = "32 23 0:28 / /sys/fs/cgroup rw - cgroup2 cgroup2 rw";

        assertThat(AppBackgroundModeConfig.detectCgroupLayout(v1)).isEqualTo(
                AppBackgroundModeConfig.CGROUP_LAYOUT_V1);
        assertThat(AppBackgroundModeConfig.detectCgroupLayout(v2)).isEqualTo(
                AppBackgroundModeConfig.CGROUP_LAYOUT_V2);
        assertThat(AppBackgroundModeConfig.detectCgroupLayout(v1 + "\n" + v2)).isEqualTo(
                AppBackgroundModeConfig.CGROUP_LAYOUT_HYBRID);
    }

    @Test
    public void resolveFreezerBackend_fallsBackToAvailableHierarchy() {
        assertThat(AppBackgroundModeConfig.resolveFreezerBackend(
                AppBackgroundModeConfig.FREEZER_BACKEND_CGROUP1,
                AppBackgroundModeConfig.CGROUP_LAYOUT_V2, true)).isEqualTo(
                AppBackgroundModeConfig.FREEZER_BACKEND_CGROUP2);
        assertThat(AppBackgroundModeConfig.resolveFreezerBackend(
                AppBackgroundModeConfig.FREEZER_BACKEND_AUTO,
                AppBackgroundModeConfig.CGROUP_LAYOUT_HYBRID, true)).isEqualTo(
                AppBackgroundModeConfig.FREEZER_BACKEND_HYBRID);
        assertThat(AppBackgroundModeConfig.resolveFreezerBackend(
                AppBackgroundModeConfig.FREEZER_BACKEND_AUTO,
                AppBackgroundModeConfig.CGROUP_LAYOUT_V2, false)).isEqualTo(
                AppBackgroundModeConfig.FREEZER_BACKEND_NONE);
    }

    @Test
    public void isCoreApplication_blocksCorePersistentAndCriticalApps() {
        final ApplicationInfo app = new ApplicationInfo();
        app.uid = Process.FIRST_APPLICATION_UID;
        assertThat(AppBackgroundModeConfig.isCoreApplication(app, false)).isFalse();

        app.uid = Process.SYSTEM_UID;
        assertThat(AppBackgroundModeConfig.isCoreApplication(app, false)).isTrue();

        app.uid = Process.FIRST_APPLICATION_UID;
        app.flags = ApplicationInfo.FLAG_PERSISTENT;
        assertThat(AppBackgroundModeConfig.isCoreApplication(app, false)).isTrue();

        app.flags = 0;
        assertThat(AppBackgroundModeConfig.isCoreApplication(app, true)).isTrue();
        assertThat(AppBackgroundModeConfig.isCoreApplication(null, false)).isTrue();
    }
}
