/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.uwuaosp.compose.framework.theme.SettingsDimension
import org.uwuaosp.compose.framework.theme.SettingsTheme
import org.uwuaosp.compose.widget.app.AppListEmpty
import org.uwuaosp.compose.widget.app.AppListLoading
import org.uwuaosp.compose.widget.preference.SettingsSectionHeader
import org.uwuaosp.compose.widget.preference.SettingsSectionSpacer
import org.uwuaosp.compose.widget.preference.SwitchPreferenceRow
import org.uwuaosp.compose.widget.scaffold.SettingsCollapsingAppBarScaffold
import org.uwuaosp.compose.widget.ui.SettingsContent

/**
 * Island settings screen.
 *
 * Reachable from uwuAOSP Plus (uwuSettingsExt), and always available even when no notification is
 * currently eligible for the island. It is the only place that mutates island preferences; the
 * exported query provider is read-only, so an unprivileged application can never change island
 * behaviour.
 */
class IslandSettingsActivity : ComponentActivity() {

    private lateinit var preferences: IslandPreferences

    /** Bumped on every preference change so the screen re-reads [preferences]. */
    private val preferencesVersion = mutableIntStateOf(0)

    private val preferenceListener = IslandPreferences.Listener { preferencesVersion.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = IslandPreferences(this)
        preferences.addListener(preferenceListener)
        enableEdgeToEdge()
        setContent {
            SettingsTheme {
                IslandSettingsScreen(
                    preferences = preferences,
                    preferencesVersion = preferencesVersion.intValue,
                    onNavigateUp = ::finish,
                )
            }
        }
    }

    override fun onDestroy() {
        preferences.removeListener(preferenceListener)
        super.onDestroy()
    }
}

private const val TAG = "IslandSettingsActivity"
private const val APP_ICON_SIZE_DP = 40

private class IslandAppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

@Composable
private fun IslandSettingsScreen(
    preferences: IslandPreferences,
    preferencesVersion: Int,
    onNavigateUp: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<IslandAppEntry>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context, preferences) }
    }

    val globallyEnabled = remember(preferencesVersion) { preferences.isGloballyEnabledRaw() }

    SettingsCollapsingAppBarScaffold(
        title = "灵动岛",
        showBackButton = true,
        onNavigateUp = onNavigateUp,
        startCollapsed = true,
        expandedToolbarHeight = 179.dp,
    ) { padding ->
        SettingsContent {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding =
                    PaddingValues(
                        start = SettingsDimension.pagePadding,
                        end = SettingsDimension.pagePadding,
                        bottom = SettingsDimension.pageBottomPadding,
                    ),
            ) {
                item(key = "global") {
                    SwitchPreferenceRow(
                        title = "启用灵动岛",
                        summary = "",
                        showSummary = false,
                        checked = globallyEnabled,
                        onCheckedChange = preferences::setEnabled,
                    )
                }
                item(key = "apps_header") {
                    SettingsSectionSpacer()
                    SettingsSectionHeader(title = "按应用控制")
                }
                val loaded = apps
                when {
                    loaded == null -> item(key = "loading") { AppListLoading() }
                    loaded.isEmpty() -> item(key = "empty") { AppListEmpty(text = "没有可显示的应用") }
                    else ->
                        items(count = loaded.size, key = { loaded[it].packageName }) { index ->
                            val app = loaded[index]
                            val allowed =
                                remember(preferencesVersion, app.packageName) {
                                    preferences.isPackageAllowed(app.packageName)
                                }
                            Column {
                                if (index > 0) {
                                    Spacer(modifier = Modifier.height(SettingsDimension.groupSpacing))
                                }
                                SwitchPreferenceRow(
                                    title = app.label,
                                    summary = app.packageName,
                                    checked = allowed,
                                    enabled = globallyEnabled,
                                    iconContent = {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier =
                                                Modifier.size(APP_ICON_SIZE_DP.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                        )
                                    },
                                    index = index,
                                    itemCount = loaded.size,
                                    onCheckedChange = { value ->
                                        preferences.setPackageAllowed(app.packageName, value)
                                    },
                                )
                            }
                        }
                }
            }
        }
    }
}

/**
 * Lists every user visible application plus any package the user already configured, so a disabled
 * package never becomes unreachable. Runs on a background thread.
 */
private fun loadApps(context: Context, preferences: IslandPreferences): List<IslandAppEntry> {
    val packageManager = context.packageManager
    val iconSize =
        (APP_ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    fun entry(packageName: String, label: CharSequence, icon: Drawable) =
        IslandAppEntry(
            packageName = packageName,
            label = label.toString(),
            icon = icon.toBitmap(iconSize, iconSize).asImageBitmap(),
        )

    val rows = LinkedHashMap<String, IslandAppEntry>()
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    for (info in packageManager.queryIntentActivities(launcher, 0)) {
        val packageName = info.activityInfo?.packageName
        if (packageName.isNullOrEmpty() || packageName in rows) continue
        rows[packageName] =
            entry(packageName, info.loadLabel(packageManager), info.loadIcon(packageManager))
    }
    for (packageName in preferences.configuredPackages) {
        if (packageName in rows) continue
        val applicationInfo =
            try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Configured package is no longer installed: $packageName")
                null
            }
        rows[packageName] =
            entry(
                packageName,
                applicationInfo?.loadLabel(packageManager) ?: packageName,
                applicationInfo?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon,
            )
    }
    return rows.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}
