/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Matrix
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.min

@Composable
internal fun MomentArcContent(
    state: MomentArcUiState,
    loadApps: suspend () -> MomentArcApps,
    onLaunch: (MomentArcTarget) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    directInput: () -> Boolean,
    onArcTouch: (Float, Float, Boolean, Boolean) -> Unit,
    onGestureSelection: () -> Unit,
) {
    state.Animations()
    MomentArcLoadApps(state, loadApps)
    // Slots are immutable once attached; empty slots retain their original hit-test indices.
    val visibleIcons = remember(state) { state.icons.filter { it.drawable != null } }
    // Do not restart the pointer coroutine when the panel opens: a direct-input finger may
    // still be down after returning from the panel to the circle.
    Box(Modifier.fillMaxSize().pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!directInput() || state.exiting || state.continuousGesture) return@awaitEachGesture
            if (state.panelVisible) {
                val bounds = state.panelGeometry?.bounds
                val handle = state.panelGeometry?.handleTouchBounds
                if (handle?.contains(down.position.x.toInt(), down.position.y.toInt()) == true) {
                    // The handle owns this touch when interactive; ignore it while opening.
                    return@awaitEachGesture
                }
                val outside = bounds == null || !bounds.contains(down.position.x.toInt(), down.position.y.toInt())
                if (!outside && state.panelInteractive) return@awaitEachGesture
                down.consume()
                var released: Boolean
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    released = event.changes.none { it.pressed }
                } while (!released)
                if (outside) onDismiss()
            } else {
                onArcTouch(down.position.x, down.position.y, false, false)
                down.consume()
                var finished = false
                try {
                    var pressed: Boolean
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        pressed = change.pressed
                        finished = !pressed
                        onArcTouch(change.position.x, change.position.y, !pressed, false)
                        change.consume()
                    } while (pressed)
                } finally {
                    if (!finished) onArcTouch(down.position.x, down.position.y, false, true)
                }
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = state.backdropAlpha / 255f))
        }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                visibleIcons.forEach { icon ->
                    Canvas(Modifier.graphicsLayer {
                        translationX = icon.translationX
                        translationY = icon.translationY
                        scaleX = icon.scaleX
                        scaleY = icon.scaleY
                        alpha = icon.alpha * (1f - state.panelProgress)
                        shape = IconShape
                        clip = icon.circularClip
                    }) {
                        val drawable = icon.drawable ?: return@Canvas
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val saved = native.save()
                            val w = drawable.intrinsicWidth
                            val h = drawable.intrinsicHeight
                            if (w > 0 && h > 0) {
                                icon.source.set(0f, 0f, w.toFloat(), h.toFloat())
                                icon.destination.set(0f, 0f, size.width, size.height)
                                icon.fitMatrix.setRectToRect(
                                    icon.source, icon.destination, Matrix.ScaleToFit.CENTER
                                )
                                native.concat(icon.fitMatrix)
                                drawable.setBounds(0, 0, w, h)
                            } else {
                                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            }
                            drawable.draw(native)
                            native.restoreToCount(saved)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.mapIndexed { index, measurable ->
                val bounds = visibleIcons[index].bounds
                measurable.measure(Constraints.fixed(bounds.width(), bounds.height()))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val bounds = visibleIcons[index].bounds
                    // Absolute placement: RTL must not mirror the selected left/right side.
                    placeable.place(bounds.left, bounds.top)
                }
            }
        }
        if (state.panelVisible) {
            MomentArcAllAppsPanel(state, onLaunch, onGestureSelection, onBack, onDismiss)
        }
    }
}

/** Loading belongs to this presentation and user; closing, returning or refreshing cancels it. */
@Composable
internal fun MomentArcLoadApps(state: MomentArcUiState, loadApps: suspend () -> MomentArcApps) {
    LaunchedEffect(state.allAppsRequested, state.appsVersion, state.exiting) {
        if (!state.allAppsRequested || state.exiting) return@LaunchedEffect
        val version = state.appsVersion
        state.appsLoading = true
        state.appsLoadFailed = false
        try {
            val apps = loadApps()
            currentCoroutineContext().ensureActive()
            if (state.allAppsRequested && !state.exiting && state.appsVersion == version) state.apps = apps
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("MomentArc", "Failed to load all apps", error)
            if (state.allAppsRequested && !state.exiting && state.appsVersion == version) state.appsLoadFailed = true
        } finally {
            if (state.appsVersion == version) state.appsLoading = false
        }
    }
}

/** Matches the original integer-centered ImageView outline, including odd-sized bounds. */
private object IconShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val diameter = min(size.width.toInt(), size.height.toInt())
        val x = (size.width.toInt() - diameter) / 2
        val y = (size.height.toInt() - diameter) / 2
        return Outline.Rounded(
            RoundRect(
                x.toFloat(), y.toFloat(), (x + diameter).toFloat(), (y + diameter).toFloat(),
                CornerRadius(diameter / 2f),
            )
        )
    }
}
