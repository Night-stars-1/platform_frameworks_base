/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.wm.shell.windowdecor

import android.animation.ValueAnimator
import android.graphics.Path
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE
import kotlin.math.min

/** Drives the tablet dots and the native bar-to-menu animation on a reversible timeline. */
internal class TabletHandleMenuMotion(
    private val menu: View,
    private val handle: HandleImageButton,
    private val renderMenu: (Float, Float) -> Unit,
) {
    private var animation: ValueAnimator? = null
    private var request = 0
    private var handlePose = 0f
    private var headerPose = 0f
    private var bodyPose = 0f
    private var completion: (() -> Unit)? = null
    private var closing = false

    init {
        menu.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}

                override fun onViewDetachedFromWindow(view: View) {
                    ++request
                    cancelAnimation()
                    completion = null
                    restoreHandle()
                }
            }
        )
    }

    fun animate(expand: Boolean, after: () -> Unit) {
        val id = ++request
        cancelAnimation()
        completion = after
        closing = !expand
        if (
            !expand &&
                handle.tabletMenuAnimationOwner != null &&
                handle.tabletMenuAnimationOwner !== this
        ) {
            finishSupersededMenu()
            return
        }
        if (expand) {
            handle.tabletMenuAnimationOwner
                ?.takeIf { it !== this }
                ?.let { previous ->
                    // The controller can replace the menu while its close animation is still
                    // running.
                    handlePose = previous.handlePose
                    headerPose = previous.headerPose
                    bodyPose = previous.bodyPose
                }
        }
        handle.tabletMenuAnimationOwner = this
        if (!expand && handlePose == 0f && headerPose == 0f && bodyPose == 0f) {
            menu.alpha = 0f
            restoreHandle()
            completion = null
            after()
            return
        }
        if (!menu.isLaidOut) menu.alpha = 0f
        menu.isVisible = true
        // Wait for native pill sizes before applying the bar-to-menu handoff.
        menu.doOnLayout {
            if (id != request) return@doOnLayout
            if (handle.tabletMenuAnimationOwner !== this) {
                finishSupersededMenu()
                return@doOnLayout
            }
            val fromHandle = handlePose
            val fromHeader = headerPose
            val fromBody = bodyPose
            val duration = if (expand) OPEN_DURATION else CLOSE_DURATION
            render()
            animation =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    this.duration = (duration * DURATION_SCALE).toLong()
                    interpolator = LinearInterpolator()
                    addUpdateListener { animator ->
                        // A newly opened menu owns the caption, even while an older menu is
                        // closing.
                        if (handle.tabletMenuAnimationOwner !== this@TabletHandleMenuMotion) {
                            finishSupersededMenu()
                            return@addUpdateListener
                        }
                        val elapsed = (animator.animatedValue as Float) * duration
                        handlePose =
                            pose(
                                fromHandle,
                                expand,
                                elapsed,
                                if (expand) HANDLE_OPEN_DURATION else CLOSE_DURATION,
                                if (expand) EMPHASIZED else HANDLE_CLOSE_CURVE,
                            )
                        headerPose =
                            pose(
                                fromHeader,
                                expand,
                                elapsed,
                                if (expand) OPEN_DURATION else HEADER_CLOSE_DURATION,
                                if (expand) HEADER_OPEN_CURVE else EMPHASIZED,
                            )
                        bodyPose =
                            pose(
                                fromBody,
                                expand,
                                elapsed,
                                if (expand) OPEN_DURATION else HEADER_CLOSE_DURATION,
                                if (expand) EMPHASIZED_DECELERATE else EMPHASIZED,
                            )
                        render()
                    }
                    doOnEnd {
                        animation = null
                        if (!expand) restoreHandle()
                        val callback = completion
                        completion = null
                        callback?.invoke()
                    }
                }
            animation?.start()
        }
    }

    private fun cancelAnimation() {
        animation?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        animation = null
    }

    private fun finishSupersededMenu() {
        ++request
        cancelAnimation()
        menu.alpha = 0f
        val callback = completion
        completion = null
        if (closing) callback?.invoke()
    }

    private fun restoreHandle() {
        if (handle.tabletMenuAnimationOwner !== this) return
        handle.tabletMenuAnimationOwner = null
        handle.tabletJoinProgress = 0f
        handle.scaleX = 1f
        handle.scaleY = 1f
        handle.translationX = 0f
        handle.translationY = 0f
        handle.isVisible = true
    }

    private fun render() {
        if (handle.tabletMenuAnimationOwner !== this || !menu.isLaidOut) return
        val progress = min(handlePose, headerPose).coerceIn(0f, 1f)
        handle.tabletJoinProgress = (progress / JOIN_FRACTION).coerceIn(0f, 1f)
        val menuProgress = ((progress - JOIN_FRACTION) / (1 - JOIN_FRACTION)).coerceIn(0f, 1f)
        renderMenu(menuProgress, min(menuProgress, bodyPose))
    }

    private fun pose(
        from: Float,
        expand: Boolean,
        elapsed: Float,
        duration: Long,
        curve: Interpolator,
    ): Float {
        val fraction = (elapsed / duration).coerceIn(0f, 1f)
        // Traverse the curve backwards on close, rather than subtracting its forward value.
        return if (expand) mix(from, 1f, curve.getInterpolation(fraction))
        else from * curve.getInterpolation(1 - fraction)
    }

    private fun mix(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

    companion object {
        private const val DURATION_SCALE = 1.15f
        private const val JOIN_FRACTION = 0.19f
        const val DOT_SIZE = 6f
        const val DOT_SPACING = 12f
        const val LINE_WIDTH = 34f
        const val LINE_HEIGHT = 5f
        private const val HANDLE_OPEN_DURATION = 520L
        private const val OPEN_DURATION = 600L
        private const val CLOSE_DURATION = 600L
        private const val HEADER_CLOSE_DURATION = 340L
        private val HANDLE_CLOSE_CURVE =
            PathInterpolator(
                Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(0.05f, 0f, 0.166666f, 0.129f, 0.166666f, 0.4f)
                    cubicTo(0.226f, 1f, 0.226f, 1f, 1f, 1f)
                }
            )
        private val HEADER_OPEN_CURVE =
            PathInterpolator(
                Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(0.115f, 0.004f, 0.166666f, 0.166f, 0.166666f, 0.4f)
                    cubicTo(0.166666f, 0.845f, 0.243f, 1f, 1f, 1f)
                }
            )
    }
}
