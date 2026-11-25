/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.compose.animation.scene

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** A full-content swipe with independent distance and fling commit thresholds. */
class ThresholdSwipeDistance(
    private val positionalThreshold: Dp,
    private val minimumFlingDistance: Dp,
    private val velocityThreshold: Dp,
    private val swipeDistance: Dp? = null,
    internal val velocityProjectionMillis: Int? = null,
    internal val edgeResistance: Float = 0f,
    internal val settleSpec: AnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 250f),
) : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromContent: ContentKey,
        toContent: ContentKey,
        orientation: Orientation,
    ): Float =
        swipeDistance?.toPx()
            ?: with(DefaultSwipeDistance) { absoluteDistance(fromContent, toContent, orientation) }

    internal fun resistedOffset(offset: Float, distance: Float): Float {
        if (velocityProjectionMillis == null) return offset
        val lower = minOf(0f, distance)
        val upper = maxOf(0f, distance)
        return when {
            offset < lower -> lower + (offset - lower) * edgeResistance
            offset > upper -> upper + (offset - upper) * edgeResistance
            else -> offset
        }
    }

    internal fun shouldCommit(
        density: Density,
        offset: Float,
        distance: Float,
        velocity: Float,
    ): Boolean =
        with(density) {
            velocityProjectionMillis?.let { projectionMillis ->
                if (distance == 0f) return@with false
                val projectedProgress =
                    offset / distance +
                        (velocity / distance).coerceIn(-12f, 12f) * projectionMillis / 1000f
                return@with projectedProgress >=
                    positionalThreshold.toPx() / kotlin.math.abs(distance)
            }
            // Normalize both directions so reversing towards the source always cancels the swipe.
            val direction = if (distance < 0f) -1f else 1f
            val displacement = offset * direction
            val speed = velocity * direction
            displacement >= 0f &&
                speed >= 0f &&
                (displacement >= positionalThreshold.toPx() ||
                    (displacement >= minimumFlingDistance.toPx() &&
                        speed >= velocityThreshold.toPx()))
        }
}
