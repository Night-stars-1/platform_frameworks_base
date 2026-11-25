/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Arc browser and Moment launch frames share the same safe-area center. */
internal data class MomentArcPanelGeometry(
    val bounds: Rect,
    val scale: Float,
    val cornerRadius: Float,
    val gridBounds: Rect,
    val launchBounds: Rect,
    val handleTouchBounds: Rect,
    val handleVisualBounds: RectF,
    val animateLaunch: Boolean,
) {
    companion object {
        fun calculate(
            width: Int, height: Int, bars: Insets, density: Float, safeInsets: Insets = bars,
        ): MomentArcPanelGeometry {
            val landscape = width > height
            val scale = if (landscape) .40f else .80f
            val canvasWidth = min(width, height)
            val canvasHeight = max(width, height)
            val margin = (5 * density).roundToInt()
            val safe = Rect(
                safeInsets.left + margin, safeInsets.top + margin,
                max(safeInsets.left + margin + 1, width - safeInsets.right - margin),
                max(safeInsets.top + margin + 1, height - safeInsets.bottom - margin),
            )
            fun frame(frameScale: Float): Rect {
                val frameWidth = (canvasWidth * frameScale).roundToInt().coerceIn(1, safe.width())
                // Reserve a full-size handle target below the surface, including in landscape.
                val maxHeight = max(1, safe.height() - (48 * density).roundToInt())
                val frameHeight = ((canvasHeight - bars.top - bars.bottom) * frameScale)
                    .roundToInt().coerceIn(1, maxHeight)
                val left = safe.left + (safe.width() - frameWidth) / 2
                val top = safe.top + min(
                    (safe.height() - frameHeight) / 2,
                    max(0, safe.height() - frameHeight - (48 * density).roundToInt()),
                )
                return Rect(left, top, left + frameWidth, top + frameHeight)
            }
            val bounds = frame(scale)
            val launchBounds = frame(if (landscape) scale else .55f)
            val footer = (16 * density * scale).roundToInt()
            val left = bounds.left
            val top = bounds.top
            val gridTop = min(bounds.bottom, top + (56 * density * scale).roundToInt())
            val gridBottom = max(gridTop, bounds.bottom - footer)
            val handleHeight = (bounds.width() * .015f).coerceIn(2 * density, 5 * density)
            val handleY = bounds.bottom + 9 * density
            val handleWidth = bounds.width() / 3f
            val handleTouchWidth = max(bounds.width() / 2f, 100 * density)
                .coerceAtMost(safe.width().toFloat())
            val handleTouchBounds = Rect(
                (bounds.exactCenterX() - handleTouchWidth / 2f).roundToInt(),
                (handleY - 16 * density).roundToInt(),
                (bounds.exactCenterX() + handleTouchWidth / 2f).roundToInt(),
                min(safe.bottom, (handleY + 16 * density).roundToInt()),
            )
            return MomentArcPanelGeometry(
                bounds, scale, 28 * density * scale,
                Rect(left, gridTop, bounds.right, gridBottom), launchBounds,
                handleTouchBounds,
                RectF(bounds.exactCenterX() - handleWidth / 2f, handleY - handleHeight / 2f,
                    bounds.exactCenterX() + handleWidth / 2f, handleY + handleHeight / 2f),
                !landscape,
            )
        }
    }
}
