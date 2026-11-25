/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@SysUISingleton
class MomentArcController
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxLifetimeRunnable = Runnable { hide() }
    private var overlayView: MomentArcView? = null
    private var showingAllAppsOnly = false
    private val exitingViews = HashSet<MomentArcView>()
    private var lastWindowManagerErrorLogTime = Long.MIN_VALUE
    private var suppressedWindowManagerErrorCount = 0

    private val packageCallback = object : LauncherApps.Callback() {
        private fun refresh(user: UserHandle) {
            if (user == userTracker.userHandle) overlayView?.invalidateApps()
        }
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh(user)
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(user)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = refresh(user)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = refresh(user)
        override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) = refresh(user)
        override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) = refresh(user)
        override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) = refresh(user)
    }

    init {
        runCatching { launcherApps.registerCallback(packageCallback, mainHandler) }
            .onFailure { Log.w(TAG, "Failed to observe launcher package changes", it) }
    }

    fun showAllApps() = show(isLeft = true, allAppsOnly = true)

    fun onSettingsChanged() {
        if (!isMomentEnabled() || (!showingAllAppsOnly && !isMomentArcEnabled())) {
            hide(immediate = true)
        }
    }

    fun show(
        isLeft: Boolean,
        initialTouchX: Float = -1f,
        initialTouchY: Float = -1f,
        allAppsOnly: Boolean = false,
    ) {
        hide()
        fun enabled() = isMomentEnabled() && (allAppsOnly || isMomentArcEnabled())
        if (!enabled()) {
            Log.w(TAG, "Cannot show Moment UI: disabled, allAppsOnly=$allAppsOnly")
            return
        }

        val view = MomentArcView(context, isLeft, startInAllApps = allAppsOnly)
        val user = userTracker.userHandle
        val innerTargets = getTargetSlots(INNER_RING_TARGETS, INNER_MAX_ICONS - 1)
        val outerTargets = getTargetSlots(OUTER_RING_TARGETS, OUTER_MAX_ICONS)
        val displayedInnerTargets = ArrayList<MomentArcTarget?>()
        val displayedOuterTargets = ArrayList<MomentArcTarget?>()

        innerTargets.forEach { target ->
            val icon = target?.let(::loadTargetIcon)
            view.addIcon(icon)
            displayedInnerTargets.add(if (icon != null) target else null)
        }
        view.addIcon(context.getDrawable(R.drawable.ic_moment_arc_all_apps), circularClip = false)
        outerTargets.forEach { target ->
            val icon = target?.let(::loadTargetIcon)
            view.addIcon(icon)
            displayedOuterTargets.add(if (icon != null) target else null)
        }

        view.setOnIconLaunchListener { index ->
            if (!enabled() || user != userTracker.userHandle) {
                hide()
                return@setOnIconLaunchListener
            }
            when {
                index < INNER_MAX_ICONS - 1 -> displayedInnerTargets.getOrNull(index)?.let(::launch)
                index == INNER_MAX_ICONS - 1 -> {
                    if (!view.showAllApps()) hide()
                    return@setOnIconLaunchListener
                }
                else -> displayedOuterTargets.getOrNull(index - INNER_MAX_ICONS)?.let(::launch)
            }
            hide()
        }
        view.setOnAllAppsOpenedListener { mainHandler.removeCallbacks(maxLifetimeRunnable) }
        val configuredTargets = momentArcConfiguredTargets(innerTargets, outerTargets)
        view.setAppsLoader {
            withContext(backgroundDispatcher) {
                val userContext = context.createContextAsUser(user, 0)
                val pm = userContext.packageManager
                val arcApps = configuredTargets.mapNotNull { (slot, target) ->
                    currentCoroutineContext().ensureActive()
                    runCatching {
                        val packageName = when (target) {
                            is MomentArcTarget.App -> target.packageName
                            is MomentArcTarget.Shortcut -> target.packageName
                            is MomentArcTarget.Activity -> target.component.packageName
                        }
                        val app = pm.getApplicationInfo(packageName, 0)
                        if (!app.enabled) return@runCatching null
                        val shortcut = (target as? MomentArcTarget.Shortcut)?.let(::getShortcutInfo)
                        if (target is MomentArcTarget.Shortcut && (shortcut == null || !shortcut.isEnabled)) {
                            return@runCatching null
                        }
                        val label = shortcut?.shortLabel?.toString() ?: app.loadLabel(pm).toString()
                        val icon = if (shortcut != null) {
                            launcherApps.getShortcutBadgedIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
                                ?: app.loadIcon(pm)
                        } else app.loadIcon(pm)
                        MomentArcApp("arc:$slot", label, icon, target)
                    }.getOrNull()
                }
                MomentArcApps(
                    arcApps,
                    groupMomentArcApps(loadMomentArcApps(userContext, user), userContext.resources.configuration.locales[0]),
                )
            }
        }
        view.setOnAppLaunchListener { target ->
            if (overlayView === view && user == userTracker.userHandle && enabled()) {
                if (launch(target)) {
                    if (!view.animateAppLaunch {
                        if (overlayView === view) hide(immediate = true)
                    }) hide()
                }
            }
        }
        view.setOnDismissListener { hide() }
        if (initialTouchX >= 0f && initialTouchY >= 0f) {
            view.setInitialTouchPoint(initialTouchX, initialTouchY)
        }
        try {
            windowManager.addView(view, MomentArcView.createLayoutParams(directInput = allAppsOnly))
            overlayView = view
            showingAllAppsOnly = allAppsOnly
            if (allAppsOnly) Log.i(TAG, "Attached all-apps window for user=${user.identifier}")
            if (!allAppsOnly) mainHandler.postDelayed(maxLifetimeRunnable, MAX_LIFETIME_MS)
        } catch (e: Exception) {
            logWindowManagerFailure("Failed to add MomentArc view", e)
        }
    }

    fun onTouchCoordinates(x: Float, y: Float, isUp: Boolean, isCancelled: Boolean) {
        if (overlayView?.acceptsGestureBroadcast == false) return
        if (!isMomentArcEnabled() || isCancelled) {
            overlayView?.dispatchTouchCoordinates(x, y, false, true) ?: hide()
            return
        }
        overlayView?.dispatchTouchCoordinates(x, y, isUp, false)
    }

    fun hide(immediate: Boolean = false) {
        mainHandler.removeCallbacks(maxLifetimeRunnable)
        if (immediate) exitingViews.toList().forEach(::removeOverlay)
        val view = overlayView ?: return
        overlayView = null
        if (immediate) removeOverlay(view) else {
            exitingViews.add(view)
            view.animateExit { removeOverlay(view) }
        }
    }

    private fun removeOverlay(view: MomentArcView) {
        exitingViews.remove(view)
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            logWindowManagerFailure("Failed to remove MomentArc view", e)
        }
    }

    private fun isMomentEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.MOMENT_ENABLED,
            0,
            userTracker.userId,
        ) != 0

    fun isMomentArcEnabled(): Boolean {
        return isMomentEnabled() &&
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.MOMENT_ARC_GESTURE_ENABLED,
                1,
                userTracker.userId,
            ) != 0
    }

    private fun loadTargetIcon(target: MomentArcTarget): Drawable? =
        runCatching { loadIcon(target) }
            .onFailure { Log.w(TAG, "Failed to load icon for $target", it) }
            .getOrNull()

    private fun loadIcon(target: MomentArcTarget): Drawable {
        return when (target) {
            is MomentArcTarget.App -> fallbackAppIcon(target.packageName)
            is MomentArcTarget.Shortcut -> {
                val info = getShortcutInfo(target) ?: return fallbackAppIcon(target.packageName)
                launcherApps.getShortcutBadgedIconDrawable(
                    info,
                    context.resources.displayMetrics.densityDpi,
                ) ?: fallbackAppIcon(target.packageName)
            }
            is MomentArcTarget.Activity -> launcherApps.getActivityList(
                target.component.packageName, UserHandle.of(target.userId)
            ).first { it.componentName == target.component }.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        }
    }

    private fun fallbackAppIcon(packageName: String): Drawable {
        val packageManager = currentUserContext().packageManager
        return packageManager.getApplicationInfo(packageName, 0).loadIcon(packageManager)
    }

    private fun launch(target: MomentArcTarget): Boolean {
        val launched = when (target) {
            is MomentArcTarget.App -> launchPackage(target.packageName)
            is MomentArcTarget.Shortcut -> launchShortcut(target)
            is MomentArcTarget.Activity -> runCatching {
                check(target.userId == userTracker.userId)
                check(launcherApps.isActivityEnabled(target.component, UserHandle.of(target.userId)))
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(target.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                context.startActivityAsUser(intent, momentOptions().toBundle(), UserHandle.of(target.userId))
            }.onFailure { Log.w(TAG, "Failed to launch ${target.component}", it) }.isSuccess
        }
        if (!launched) Toast.makeText(context, R.string.moment_arc_launch_failed, Toast.LENGTH_SHORT).show()
        return launched
    }

    private fun launchPackage(packageName: String): Boolean {
        return runCatching {
            val intent = checkNotNull(currentUserContext().packageManager.getLaunchIntentForPackage(packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivityAsUser(intent, momentOptions().toBundle(), userTracker.userHandle)
        }.onFailure { Log.w(TAG, "Failed to launch package $packageName", it) }.isSuccess
    }

    private fun launchShortcut(target: MomentArcTarget.Shortcut): Boolean {
        if (target.userId != userTracker.userId) {
            Log.w(TAG, "Ignoring shortcut target for a non-current user")
            return false
        }
        val options = momentOptions().apply { setApplyMultipleTaskFlagForShortcut(true) }
        return runCatching {
            check(getShortcutInfo(target)?.isEnabled == true)
            launcherApps.startShortcut(
                target.packageName,
                target.shortcutId,
                null,
                options.toBundle(),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to launch shortcut ${target.packageName}/${target.shortcutId}", it)
        }.isSuccess
    }

    private fun momentOptions() =
        ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MOMENT)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }

    private fun getTargetSlots(key: String, slotCount: Int): List<MomentArcTarget?> {
        val entries = Settings.System.getStringForUser(context.contentResolver, key, userTracker.userId)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
        return List(slotCount) { index ->
            entries.getOrNull(index)?.takeUnless { it.trim() == EMPTY_TARGET }?.let(::parseTarget)
        }
    }

    private fun parseTarget(rawEntry: String): MomentArcTarget? {
        val entry = rawEntry.trim()
        if (entry.isBlank()) return null
        if (!entry.startsWith(APP_PREFIX) && !entry.startsWith(SHORTCUT_PREFIX)) {
            return MomentArcTarget.App(entry)
        }
        if (entry.startsWith(APP_PREFIX)) {
            return Uri.decode(entry.removePrefix(APP_PREFIX)).trim().takeIf(String::isNotBlank)
                ?.let(MomentArcTarget::App)
        }
        val parts = entry.split(FIELD_SEPARATOR, limit = 4)
        if (parts.size !in 3..4) return malformedShortcut(entry)
        val explicitUser = parts.size == 4
        val userId = if (explicitUser) parts[1].toIntOrNull() else userTracker.userId
        val packageName = Uri.decode(parts[if (explicitUser) 2 else 1]).trim()
        val shortcutId = Uri.decode(parts[if (explicitUser) 3 else 2]).trim()
        if (userId == null || packageName.isBlank() || shortcutId.isBlank()) {
            return malformedShortcut(entry)
        }
        if (userId != userTracker.userId) {
            Log.w(TAG, "Ignoring MomentArc shortcut target for a non-current user")
            return null
        }
        return MomentArcTarget.Shortcut(packageName, shortcutId, userId)
    }

    private fun malformedShortcut(entry: String): MomentArcTarget? {
        Log.w(TAG, "Ignoring malformed MomentArc shortcut entry: $entry")
        return null
    }

    private fun getShortcutInfo(target: MomentArcTarget.Shortcut): ShortcutInfo? =
        queryShortcut(target, LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED)
            ?: queryShortcut(target, LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS)

    private fun queryShortcut(target: MomentArcTarget.Shortcut, flags: Int): ShortcutInfo? =
        runCatching {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(target.packageName)
                    .setShortcutIds(listOf(target.shortcutId))
                    .setQueryFlags(flags),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to query shortcut ${target.packageName}/${target.shortcutId}", it)
        }.getOrNull()?.firstOrNull()

    private fun currentUserContext() = context.createContextAsUser(userTracker.userHandle, 0)

    @Synchronized
    private fun logWindowManagerFailure(message: String, error: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (lastWindowManagerErrorLogTime != Long.MIN_VALUE &&
            now - lastWindowManagerErrorLogTime < WINDOW_MANAGER_ERROR_LOG_INTERVAL_MS
        ) {
            suppressedWindowManagerErrorCount++
            return
        }
        val suffix = if (suppressedWindowManagerErrorCount > 0) {
            " ($suppressedWindowManagerErrorCount similar failures suppressed)"
        } else ""
        Log.w(TAG, message + suffix, error)
        lastWindowManagerErrorLogTime = now
        suppressedWindowManagerErrorCount = 0
    }

    private companion object {
        const val TAG = "MomentArc"
        const val WINDOW_MANAGER_ERROR_LOG_INTERVAL_MS = 30_000L
        const val MAX_LIFETIME_MS = 10_000L
        const val INNER_MAX_ICONS = MomentArcGeometry.INNER_COUNT
        const val OUTER_MAX_ICONS = MomentArcGeometry.OUTER_COUNT
        const val ENTRY_SEPARATOR = "|"
        const val APP_PREFIX = "app:"
        const val SHORTCUT_PREFIX = "shortcut:"
        const val EMPTY_TARGET = "empty:"
        const val FIELD_SEPARATOR = ":"
        const val INNER_RING_TARGETS = "moment_arc_selected_targets"
        const val OUTER_RING_TARGETS = "moment_arc_outer_ring_selected_targets"
    }
}
