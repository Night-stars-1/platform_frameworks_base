/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.cos
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** UI state and animations. All animation work uses the composition's frame clock and lifetime. */
internal class MomentArcUiState {
    val icons = ArrayList<Icon>()
    var backdropAlpha by mutableIntStateOf(0)
        private set
    var ready by mutableStateOf(false)
    var exiting by mutableStateOf(false)
        private set
    private var selectionVersion by mutableIntStateOf(0)
    private var selectedIndex = -1
    private var geometry: MomentArcGeometry? = null
    private var exitCallback: (() -> Unit)? = null
    private var introJob: Job? = null
    private var selectionJob: Job? = null
    private var panelJob: Job? = null
    private var launchJob: Job? = null
    private var launchCallback: (() -> Unit)? = null
    var panelGeometry by mutableStateOf<MomentArcPanelGeometry?>(null)
    var panelSource by mutableStateOf(RectF())
        private set
    var allAppsRequested by mutableStateOf(false)
        private set
    var panelProgress by mutableFloatStateOf(0f)
        private set
    var panelContentAlpha by mutableFloatStateOf(0f)
        private set
    var panelExitAlpha by mutableFloatStateOf(1f)
        private set
    var launchingApp by mutableStateOf(false)
        private set
    var launchProgress by mutableFloatStateOf(0f)
        private set
    var appsVersion by mutableIntStateOf(0)
        private set
    var apps by mutableStateOf<MomentArcApps?>(null)
    var appsLoading by mutableStateOf(false)
    var appsLoadFailed by mutableStateOf(false)
    var continuousGesture by mutableStateOf(false)
        private set
    var gestureX by mutableFloatStateOf(0f)
        private set
    var gestureY by mutableFloatStateOf(0f)
        private set
    var gestureSelection by mutableStateOf<MomentArcApp?>(null)
        private set
    private var gestureSelectionBounds: RectF? = null
    val panelVisible by derivedStateOf { allAppsRequested || panelProgress > 0f }
    val panelInteractive by derivedStateOf {
        allAppsRequested && panelProgress >= 1f && !exiting && !launchingApp
    }

    fun invalidateApps() { appsVersion++ }

    fun startContinuousGesture(x: Float, y: Float) {
        continuousGesture = true
        setGestureSelection(null)
        moveContinuousGesture(x, y)
        val icon = icons.getOrNull(MomentArcGeometry.INNER_COUNT - 1)
        val half = (icon?.width ?: 0) / 2f
        panelSource = RectF(x - half, y - half, x + half, y + half)
    }

    fun moveContinuousGesture(x: Float, y: Float) {
        gestureX = x
        gestureY = y
    }

    fun setGestureSelection(app: MomentArcApp?, bounds: RectF? = null): Boolean {
        if (!continuousGesture) return false
        val changed = gestureSelection?.key != app?.key
        gestureSelection = app
        gestureSelectionBounds = bounds
        return changed && app != null
    }

    fun selectionAt(x: Float, y: Float): MomentArcApp? =
        gestureSelection?.takeIf { gestureSelectionBounds?.contains(x, y) == true }

    fun endContinuousGesture(): MomentArcApp? {
        val selection = gestureSelection
        continuousGesture = false
        gestureSelection = null
        gestureSelectionBounds = null
        return selection
    }

    fun openAllApps(fromHandle: Boolean = false) {
        if (exiting || allAppsRequested || panelGeometry == null) return
        introJob?.cancel()
        selectionJob?.cancel()
        if (fromHandle && panelProgress == 0f) {
            panelSource = RectF(checkNotNull(panelGeometry).handleVisualBounds)
            backdropAlpha = 128
        } else if (panelProgress == 0f && !continuousGesture) {
            val icon = icons.getOrNull(MomentArcGeometry.INNER_COUNT - 1) ?: return
            val cx = icon.left + icon.width / 2f + icon.translationX
            val cy = icon.top + icon.height / 2f + icon.translationY
            val halfW = icon.width * icon.scaleX / 2f
            val halfH = icon.height * icon.scaleY / 2f
            panelSource = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        appsVersion++
        allAppsRequested = true
    }

    fun returnToArc() {
        if (exiting || launchingApp) return
        // Restore the steady circle beneath the reverse morph, not its old unfinished intro pose.
        icons.forEach {
            it.translationX = 0f
            it.translationY = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        allAppsRequested = false
        appsLoading = false
        endContinuousGesture()
    }

    fun animateAppLaunch(onEnd: () -> Unit): Boolean {
        if (!panelInteractive || panelGeometry?.animateLaunch != true) return false
        endContinuousGesture()
        launchCallback = onEnd
        launchingApp = true
        return true
    }

    fun updateGeometry(value: MomentArcGeometry) {
        geometry = value
        icons.forEachIndexed { index, icon ->
            icon.bounds = value.bounds(index)
            if (!ready && !exiting) {
                icon.translationX = value.centerX - (icon.left + icon.width / 2f)
                icon.translationY = value.centerY - (icon.top + icon.height / 2f)
            }
        }
    }

    fun select(index: Int) {
        selectedIndex = index
        selectionVersion++
    }

    fun exit(value: MomentArcGeometry, onEnd: () -> Unit) {
        if (exiting) return
        geometry = value
        exitCallback = onEnd
        appsLoading = false
        launchCallback = null
        endContinuousGesture()
        exiting = true
    }

    @Composable
    fun Animations() {
        LaunchedEffect(ready) {
            if (!ready || exiting || allAppsRequested) return@LaunchedEffect
            introJob = currentCoroutineContext()[Job]
            runIntro()
        }
        LaunchedEffect(selectionVersion) {
            if (selectionVersion == 0 || exiting || panelVisible) return@LaunchedEffect
            selectionJob = currentCoroutineContext()[Job]
            val starts = icons.map { it.scaleX to it.scaleY }
            val selected = selectedIndex
            animate(0f, 1f, animationSpec = tween(100, easing = STANDARD_EASING)) { f, _ ->
                icons.forEachIndexed { index, icon ->
                    if (icon.drawable != null) {
                        val target = if (index == selected) 1.15f else 1f
                        icon.scaleX = lerp(starts[index].first, target, f)
                        icon.scaleY = lerp(starts[index].second, target, f)
                    }
                }
            }
        }
        LaunchedEffect(allAppsRequested) {
            if (exiting || (!allAppsRequested && panelProgress == 0f)) return@LaunchedEffect
            panelJob = currentCoroutineContext()[Job]
            val start = panelProgress
            val startAlpha = panelContentAlpha
            val target = if (allAppsRequested) 1f else 0f
            val easing = if (allAppsRequested) PANEL_EASING else PANEL_REVERSE_EASING
            animate(0f, 300f, animationSpec = tween(300, easing = LinearEasing)) { ms, _ ->
                panelProgress = lerp(start, target, easing.transform((ms / 300f).coerceIn(0f, 1f)))
                panelContentAlpha = lerp(startAlpha, target,
                    PANEL_EASING.transform((ms / 200f).coerceIn(0f, 1f)))
            }
        }
        LaunchedEffect(launchingApp) {
            if (!launchingApp || exiting) return@LaunchedEffect
            launchJob = currentCoroutineContext()[Job]
            panelJob?.cancel()
            animate(0f, 1f, animationSpec = tween(260, easing = LinearEasing)) { f, _ ->
                panelContentAlpha = (1f - f / .35f).coerceIn(0f, 1f)
                launchProgress = PANEL_EASING.transform(((f - .15f) / .85f).coerceIn(0f, 1f))
            }
            launchCallback?.invoke()
            launchCallback = null
        }
        LaunchedEffect(exiting) {
            if (!exiting) return@LaunchedEffect
            // Exit owns the pose: don't let a still-running intro/selection overwrite it.
            introJob?.cancel()
            selectionJob?.cancel()
            panelJob?.cancel()
            launchJob?.cancel()
            runExit()
            exitCallback?.invoke()
            exitCallback = null
        }
    }

    private suspend fun runIntro() {
        val visible = icons.withIndex().filter { it.value.drawable != null }
        val starts = visible.map { it.value.translationX to it.value.translationY }
        val duration = maxOf(180L, visible.maxOfOrNull {
            MomentArcGeometry.introDuration(it.index)
        } ?: 0L).toInt()
        // One Compose animation drives all icons, preserving each original slot's duration.
        animate(0f, duration.toFloat(), animationSpec = tween(duration, easing = LinearEasing)) { ms, _ ->
            backdropAlpha = (128 * STANDARD_EASING.transform((ms / 180f).coerceIn(0f, 1f))).toInt()
            visible.forEachIndexed { i, entry ->
                val f = INTRO_EASING.transform(
                    (ms / MomentArcGeometry.introDuration(entry.index)).coerceIn(0f, 1f)
                )
                val icon = entry.value
                icon.translationX = lerp(starts[i].first, 0f, f)
                icon.translationY = lerp(starts[i].second, 0f, f)
                icon.scaleX = lerp(.8f, 1f, f)
                icon.scaleY = lerp(.8f, 1f, f)
                icon.alpha = f
            }
        }
    }

    private suspend fun runExit() {
        val target = checkNotNull(geometry)
        val visible = icons.filter { it.drawable != null }
        val starts = visible.map { Pose(it.translationX, it.translationY, it.scaleX, it.scaleY, it.alpha) }
        val background = backdropAlpha
        val panelAlpha = panelContentAlpha
        animate(0f, 1f, animationSpec = tween(150, easing = EXIT_EASING)) { f, _ ->
            backdropAlpha = background + ((0 - background) * f).toInt()
            panelExitAlpha = 1f - f
            panelContentAlpha = lerp(panelAlpha, 0f, f)
            visible.forEachIndexed { i, icon ->
                val start = starts[i]
                icon.translationX = lerp(start.x, target.centerX - (icon.left + icon.width / 2f), f)
                icon.translationY = lerp(start.y, target.centerY - (icon.top + icon.height / 2f), f)
                icon.scaleX = lerp(start.scaleX, .8f, f)
                icon.scaleY = lerp(start.scaleY, .8f, f)
                icon.alpha = lerp(start.alpha, 0f, f)
            }
        }
    }

    class Icon(val drawable: Drawable?, val circularClip: Boolean) {
        val fitMatrix = Matrix()
        val source = RectF()
        val destination = RectF()
        var bounds by mutableStateOf(Rect())
        var translationX by mutableFloatStateOf(0f)
        var translationY by mutableFloatStateOf(0f)
        var scaleX by mutableFloatStateOf(.8f)
        var scaleY by mutableFloatStateOf(.8f)
        var alpha by mutableFloatStateOf(0f)
        val left get() = bounds.left
        val top get() = bounds.top
        val right get() = bounds.right
        val bottom get() = bounds.bottom
        val width get() = bounds.width()
        val height get() = bounds.height()
    }

    private data class Pose(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)

    companion object {
        // Exact equivalents of the original platform interpolators, not Compose defaults.
        internal val STANDARD_EASING = Easing { ((cos((it + 1) * Math.PI) / 2) + .5).toFloat() }
        internal val INTRO_EASING = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }
        internal val EXIT_EASING = Easing { it * it }
        internal val PANEL_EASING = CubicBezierEasing(.3f, 0f, .1f, 1f)
        internal val PANEL_REVERSE_EASING = Easing { 1f - PANEL_EASING.transform(1f - it) }

        private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
    }
}
