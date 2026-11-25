/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.windowdecor

import android.animation.ValueAnimator
import android.annotation.DimenRes
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.ImageButton
import com.android.wm.shell.R
import com.android.wm.shell.common.split.SplitScreenUtils
import com.android.wm.shell.windowdecor.extension.getDimensionPixelSize
import kotlin.math.min

/**
 * [ImageButton] for the handle at the top of fullscreen apps. Has custom hover and press handling
 * to grow the handle on hover enter and shrink the handle on hover exit and press.
 */
class HandleImageButton(context: Context?, attrs: AttributeSet?) : ImageButton(context, attrs) {
    private val handleAnimator = ValueAnimator()
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotsPath = Path()

    private val displayResources =
        this.context.display?.let { this.context.createDisplayContext(it).resources } ?: resources

    // Use the display configuration: a narrow split on a tablet is still a tablet.
    internal val usesTabletMenuMotion: Boolean
        get() = SplitScreenUtils.isLargeScreen(displayResources)

    internal var tabletMenuAnimationOwner: TabletHandleMenuMotion? = null
    internal var tabletJoinProgress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    internal val tabletHandleColor: Int
        get() {
            val color = context.getColor(com.android.internal.R.color.materialColorOnSurface)
            val dark =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                Color.argb(
                    (Color.alpha(color) * 0.68f).toInt(),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color),
                )
            } else color
        }

    override fun onDraw(canvas: Canvas) {
        if (!usesTabletMenuMotion) {
            super.onDraw(canvas)
            return
        }
        val density = resources.displayMetrics.density
        val dot = TabletHandleMenuMotion.DOT_SIZE * density
        val line = TabletHandleMenuMotion.LINE_WIDTH * density
        val lineHeight = TabletHandleMenuMotion.LINE_HEIGHT * density
        val height = dot + (lineHeight - dot) * tabletJoinProgress
        val startSpan = TabletHandleMenuMotion.DOT_SPACING * density
        val endSpan = min(line / 2 - dot / 2, startSpan)
        val span = startSpan + (endSpan - startSpan) * tabletJoinProgress
        val centerWidth = dot + (line - dot) * tabletJoinProgress
        val radius = height / 2
        val cx = width / 2f
        val top = (this.height - height) / 2f
        dotsPath.rewind()
        dotsPath.addRoundRect(
            cx - span - dot / 2,
            top,
            cx - span + dot / 2,
            top + height,
            radius,
            radius,
            Path.Direction.CW,
        )
        dotsPath.addRoundRect(
            cx - centerWidth / 2,
            top,
            cx + centerWidth / 2,
            top + height,
            radius,
            radius,
            Path.Direction.CW,
        )
        dotsPath.addRoundRect(
            cx + span - dot / 2,
            top,
            cx + span + dot / 2,
            top + height,
            radius,
            radius,
            Path.Direction.CW,
        )
        // One fill composites overlaps before applying the dark-theme translucency.
        dotsPaint.color = tabletHandleColor
        canvas.drawPath(dotsPath, dotsPaint)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }

    /** Final horizontal padding for hover enter. */
    private val HANDLE_HOVER_ENTER_PADDING =
        loadDimensionPixelSize(
            R.dimen.desktop_mode_fullscreen_decor_caption_horizontal_padding_hovered
        )
    /** Final horizontal padding for press down. */
    private val HANDLE_PRESS_DOWN_PADDING =
        loadDimensionPixelSize(
            R.dimen.desktop_mode_fullscreen_decor_caption_horizontal_padding_touched
        )
    /** Default horizontal padding. */
    private val HANDLE_DEFAULT_PADDING =
        loadDimensionPixelSize(
            R.dimen.desktop_mode_fullscreen_decor_caption_horizontal_padding_default
        )

    override fun onHoverChanged(hovered: Boolean) {
        super.onHoverChanged(hovered)
        if (hovered) {
            animateHandle(HANDLE_HOVER_ANIM_DURATION, HANDLE_HOVER_ENTER_PADDING)
        } else {
            if (!isPressed) {
                animateHandle(HANDLE_HOVER_ANIM_DURATION, HANDLE_DEFAULT_PADDING)
            }
        }
    }

    override fun setPressed(pressed: Boolean) {
        if (isPressed != pressed) {
            super.setPressed(pressed)
            if (pressed) {
                animateHandle(HANDLE_PRESS_ANIM_DURATION, HANDLE_PRESS_DOWN_PADDING)
            } else {
                animateHandle(HANDLE_PRESS_ANIM_DURATION, HANDLE_DEFAULT_PADDING)
            }
        }
    }

    private fun animateHandle(duration: Long, endPadding: Int) {
        if (usesTabletMenuMotion) return
        if (handleAnimator.isRunning) {
            handleAnimator.cancel()
            handleAnimator.removeAllListeners()
        }
        handleAnimator.duration = duration
        handleAnimator.setIntValues(paddingLeft, endPadding)
        handleAnimator.addUpdateListener { animator ->
            val padding = animator.animatedValue as Int
            setPadding(padding, paddingTop, padding, paddingBottom)
        }
        handleAnimator.start()
    }

    private fun loadDimensionPixelSize(@DimenRes resourceId: Int): Int {
        return context.resources.getDimensionPixelSize(resourceId, 0)
    }

    companion object {
        /** The duration of animations related to hover state. */
        private const val HANDLE_HOVER_ANIM_DURATION = 300L
        /** The duration of animations related to pressed state. */
        private const val HANDLE_PRESS_ANIM_DURATION = 200L
    }
}
