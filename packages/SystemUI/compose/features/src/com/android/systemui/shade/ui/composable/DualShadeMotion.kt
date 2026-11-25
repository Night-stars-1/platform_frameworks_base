/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import android.content.Context
import android.graphics.Matrix
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.systemui.Prefs
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive

/** Parameters exported from the approved uwu-qs-motion-v1 demo. */
object DualShadeMotion {
    val TileEasing = CubicBezierEasing(0.24f, 1.02f, 0.02f, 1.22f)
    val NotificationEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ToggleEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val TileDuration = 520
    const val NotificationDuration = 340
    const val ToggleDuration = 320
    const val InitialScale = 0.92f
    val TileSpacing = 8.dp
}

val LocalDualShadeMotion = staticCompositionLocalOf<DualShadeMotionState?> { null }
val LocalDualShadePaging = staticCompositionLocalOf { false }

/** Owns drawing progress; tile state and notification layout remain owned by their controllers. */
class DualShadeMotionState(private val overlay: OverlayKey, initial: Float) {
    class Part internal constructor(internal var rank: Float, initial: Float) {
        var progress by mutableFloatStateOf(initial)
            internal set

        internal var from = initial
        internal var target = initial
        internal var start = 0f
        internal var animating = false
    }

    private val parts = linkedMapOf<Any, Part>()
    private var initialProgress = initial
    private var target = initial
    private var clockMillis = 0f
    private var settling = false
    private var settleStart = 0f
    private val notifications = overlay == Overlays.NotificationsShade
    internal val direction
        get() = if (notifications) -1f else 1f

    private val duration
        get() =
            if (notifications) DualShadeMotion.NotificationDuration
            else DualShadeMotion.TileDuration

    private val stagger
        get() = if (notifications) 28f else 14f

    private val easing
        get() =
            if (notifications) DualShadeMotion.NotificationEasing else DualShadeMotion.TileEasing

    internal var updateRows: (() -> Unit)? = null

    internal fun part(key: Any, rank: Float): Part =
        parts
            .getOrPut(key) {
                Part(rank, initialProgress).also {
                    if (settling) {
                        schedule(it, target, settleStart)
                        advance(it)
                    }
                }
            }
            .also { it.rank = rank }

    internal fun remove(key: Any) {
        parts.remove(key)
    }

    private fun schedule(part: Part, to: Float, start: Float) {
        part.from = part.progress
        part.target = to
        // Offscreen rows must not accumulate seconds of delay in a long notification or tile list.
        val rank = minOf(part.rank, if (notifications) 5f else 7.24f)
        part.start = start + rank * stagger
        part.animating = part.from != to
    }

    private fun settle(to: Float) {
        target = to
        settleStart = clockMillis
        settling = true
        parts.values.forEach { schedule(it, to, settleStart) }
    }

    private fun advance(part: Part) {
        if (!part.animating) return
        val fraction = ((clockMillis - part.start) / duration).coerceIn(0f, 1f)
        part.progress = part.from + (part.target - part.from) * easing.transform(fraction)
        if (fraction == 1f) part.animating = false
    }

    private fun snap(to: Float) {
        initialProgress = to
        target = to
        settling = false
        parts.values.forEach {
            it.progress = to
            it.animating = false
        }
        updateRows?.invoke()
    }

    private data class Signal(
        val transition: TransitionState.Transition?,
        val dragging: Boolean,
        val visible: Boolean,
        val unrelatedTransition: Boolean,
    )

    internal suspend fun observe(readState: () -> TransitionState) {
        var previousNanos = 0L
        snapshotFlow {
                val state = readState()
                val transition =
                    (state as? TransitionState.Transition)?.takeIf {
                        it.isTransitioningBetween(
                            Overlays.NotificationsShade,
                            Overlays.QuickSettingsShade,
                        )
                    }
                Signal(
                    transition,
                    transition?.isUserInputOngoing == true,
                    overlay in state.currentOverlays,
                    state is TransitionState.Transition && transition == null,
                )
            }
            .collectLatest { signal ->
                val transition = signal.transition
                if (signal.unrelatedTransition) {
                    snap(1f)
                    previousNanos = 0L
                    return@collectLatest
                }
                if (signal.dragging) settling = false
                else if (transition != null) settle(if (signal.visible) 1f else 0f)
                else if (!settling || target != (if (signal.visible) 1f else 0f)) {
                    snap(if (signal.visible) 1f else 0f)
                    previousNanos = 0L
                    return@collectLatest
                }

                while (currentCoroutineContext().isActive) {
                    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                    if (scale == 0f) {
                        snap(if (signal.visible) 1f else 0f)
                        break
                    }
                    val now = withFrameNanos { it }
                    val dt =
                        if (previousNanos == 0L) 0f else (now - previousNanos) / 1_000_000f / scale
                    previousNanos = now
                    clockMillis += dt
                    if (signal.dragging && transition != null) {
                        val p =
                            if (transition.toContent == overlay) transition.progress
                            else 1f - transition.progress
                        initialProgress = p.coerceIn(-0.2f, 1.2f)
                        parts.values.forEach { part ->
                            val lag = 24f + minOf(80f, part.rank * stagger)
                            part.progress +=
                                (initialProgress - part.progress) * (1f - exp(-dt / lag))
                            part.animating = false
                        }
                        updateRows?.invoke()
                    } else {
                        parts.values.forEach(::advance)
                        updateRows?.invoke()
                        if (parts.values.none { it.animating } && transition == null) {
                            initialProgress = target
                            settling = false
                            previousNanos = 0L
                            break
                        }
                    }
                }
            }
    }
}

@Composable
fun ContentScope.DualShadeMotionContent(content: @Composable () -> Unit) {
    val overlay = contentKey as? OverlayKey
    if (overlay != Overlays.NotificationsShade && overlay != Overlays.QuickSettingsShade) {
        content()
        return
    }
    val motion =
        remember(layoutState, overlay) {
            val transition = layoutState.transitionState as? TransitionState.Transition
            val entering =
                transition?.isTransitioningBetween(
                    Overlays.NotificationsShade,
                    Overlays.QuickSettingsShade,
                ) == true && transition.toContent == overlay
            DualShadeMotionState(checkNotNull(overlay), if (entering) 0f else 1f)
        }
    LaunchedEffectWithLifecycle(motion) { motion.observe { layoutState.transitionState } }
    CompositionLocalProvider(LocalDualShadeMotion provides motion, content = content)
}

@Composable
fun Modifier.dualShadePart(rank: Float, key: Any = rank): Modifier {
    val motion = LocalDualShadeMotion.current ?: return this
    // Each composition owns its registration, including overlapping scene compositions.
    val partKey = remember(key) { Any() }
    val part = remember(motion, partKey, rank) { motion.part(partKey, rank) }
    DisposableEffect(motion, partKey) { onDispose { motion.remove(partKey) } }
    return graphicsLayer {
        val hidden = 1f - part.progress
        translationX = motion.direction * hidden * 44.dp.toPx()
        translationY = hidden * 12.dp.toPx()
        scaleX = DualShadeMotion.InitialScale + (1f - DualShadeMotion.InitialScale) * part.progress
        scaleY = scaleX
    }
}

@Composable
fun Modifier.dualShadePress(interactionSource: InteractionSource): Modifier {
    if (LocalDualShadeMotion.current == null) return this
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    scale.animateTo(0.92f, tween(90, easing = DualShadeMotion.ToggleEasing))
                is PressInteraction.Release,
                is PressInteraction.Cancel ->
                    scale.animateTo(1f, tween(230, easing = DualShadeMotion.ToggleEasing))
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Consume only unhandled horizontal drags, after sliders and buttons have seen the event. */
@Composable
fun Modifier.dualShadeControlGesture(): Modifier {
    if (LocalDualShadeMotion.current == null || LocalDualShadePaging.current) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var horizontal: Boolean? = null
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - down.position
                if (horizontal == null && delta.getDistance() > viewConfiguration.touchSlop) {
                    horizontal = abs(delta.x) > abs(delta.y)
                }
                if (horizontal == true) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
fun DualShadeNotificationRows(stack: NotificationStackScrollLayout?) {
    val motion = LocalDualShadeMotion.current
    DisposableEffect(stack, motion) {
        if (stack != null && motion != null) {
            val density = stack.resources.displayMetrics.density
            val matrix = Matrix()
            val rows = linkedMapOf<View, DualShadeMotionState.Part>()
            val transformed = mutableSetOf<View>()
            fun reset(child: View) {
                if (transformed.remove(child)) child.setAnimationMatrix(null)
            }
            stack.setDualShadeRowTransform { child, rank ->
                if (rank < 0) {
                    reset(child)
                    rows.remove(child)?.let { motion.remove(child) }
                    return@setDualShadeRowTransform
                }
                val part = motion.part(child, rank.toFloat())
                rows[child] = part
                val hidden = 1f - part.progress
                if (abs(hidden) < 0.0001f) {
                    reset(child)
                } else if (
                    child.animation == null &&
                        (child in transformed || child.animationMatrix == null)
                ) {
                    val scale = 0.92f + 0.08f * part.progress
                    matrix.setScale(scale, scale, child.width / 2f, child.height / 2f)
                    matrix.postTranslate(-hidden * 44f * density, hidden * 12f * density)
                    child.setAnimationMatrix(matrix)
                    transformed.add(child)
                }
            }
            motion.updateRows = {
                val iterator = rows.keys.iterator()
                while (iterator.hasNext()) {
                    val child = iterator.next()
                    if (child.parent !== stack) {
                        reset(child)
                        motion.remove(child)
                        iterator.remove()
                    }
                }
                stack.updateDualShadeRowTransforms()
            }
            motion.updateRows?.invoke()
            onDispose {
                motion.updateRows = null
                stack.setDualShadeRowTransform(null)
                transformed.forEach { it.setAnimationMatrix(null) }
                rows.keys.forEach { motion.remove(it) }
            }
        } else {
            onDispose {}
        }
    }
}

/** Keep scroll independently from the temporary overlay composition. */
@Composable
fun rememberDualShadeScrollState(
    session: SaveableSession? = null,
    @Prefs.Key key: String,
): ScrollState {
    val context: Context = LocalContext.current
    val state =
        if (session != null) {
            session.rememberSaveableSession(saver = ScrollState.Saver, key = key) {
                ScrollState(Prefs.getInt(context, key, 0).coerceAtLeast(0))
            }
        } else
            remember(context, key) { ScrollState(Prefs.getInt(context, key, 0).coerceAtLeast(0)) }
    LaunchedEffectWithLifecycle(state, context) {
        snapshotFlow { state.value.takeUnless { state.isScrollInProgress } }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { Prefs.putInt(context, key, it) }
    }
    DisposableEffect(state, context) { onDispose { Prefs.putInt(context, key, state.value) } }
    return state
}
