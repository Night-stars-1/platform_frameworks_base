/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.android.systemui.compose.ComposeInitializer

/** Window/input host only; the backdrop and every icon are drawn by Compose. */
class MomentArcView(
    context: Context,
    private val isLeft: Boolean,
    private val startInAllApps: Boolean = false,
) : FrameLayout(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var hasAnimated = false
    private var selectedChildIndex = -1
    private var lastVibratedIndex = -1
    private var isTouching = false
    private val uiState = MomentArcUiState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var morePending = false
    private var moreHoldX = 0f
    private var moreHoldY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val openMore = Runnable {
        morePending = false
        if (isTouching && selectedChildIndex == MORE_INDEX && !isExiting) {
            val x = lastTouchX
            val y = lastTouchY
            if (showAllApps(continueGesture = true, x = x, y = y)) {
                vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            } else dismissListener?.invoke()
        }
    }
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var gestureVisitedPanel = false
    private val isExiting get() = uiState.exiting
    private var initialTouchX = -1f
    private var initialTouchY = -1f
    private var hasInitialTouchPoint = false
    private var iconLaunchListener: ((Int) -> Unit)? = null
    private var dismissListener: (() -> Unit)? = null
    private var allAppsOpenedListener: (() -> Unit)? = null
    private val pendingTouchCoordinates = ArrayDeque<TouchCoordinates>()
    private val icons get() = uiState.icons
    private var geometryReady = false
    private val composeView = ComposeView(context)
    var hasDirectInput = startInAllApps
        private set
    private var appsLoader: (suspend () -> MomentArcApps)? = null
    private var appLaunchListener: ((MomentArcTarget) -> Unit)? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { handleBack() }

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            MomentArcContent(
                uiState,
                loadApps = { checkNotNull(appsLoader).invoke() },
                onLaunch = { appLaunchListener?.invoke(it) },
                onBack = ::handleBack,
                onDismiss = { if (!uiState.launchingApp) dismissListener?.invoke() },
                directInput = { hasDirectInput },
                onArcTouch = ::dispatchTouchCoordinates,
                onGestureSelection = {
                    vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                },
            )
        }
    }

    fun addIcon(drawable: Drawable?, circularClip: Boolean = true) {
        check(!hasAnimated) { "MomentArc icons must be supplied before layout" }
        icons.add(MomentArcUiState.Icon(drawable, circularClip))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ComposeInitializer.onAttachedToWindow(this)
        if (startInAllApps) registerBackHandling()
    }

    fun setOnIconLaunchListener(listener: (Int) -> Unit) { iconLaunchListener = listener }
    fun setOnDismissListener(listener: () -> Unit) { dismissListener = listener }
    fun setOnAllAppsOpenedListener(listener: () -> Unit) { allAppsOpenedListener = listener }
    internal fun setAppsLoader(loader: suspend () -> MomentArcApps) { appsLoader = loader }
    internal fun setOnAppLaunchListener(listener: (MomentArcTarget) -> Unit) { appLaunchListener = listener }
    fun invalidateApps() { uiState.invalidateApps() }

    fun showAllApps(continueGesture: Boolean = false, x: Float = 0f, y: Float = 0f): Boolean {
        if (isExiting || uiState.panelGeometry == null) return false
        if (!hasDirectInput) {
            val params = layoutParams as? WindowManager.LayoutParams ?: return false
            val oldFlags = params.flags
            params.flags = params.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
            try {
                windowManager.updateViewLayout(this, params)
            } catch (e: Exception) {
                params.flags = oldFlags
                Log.w("MomentArc", "Failed to transfer Arc window input", e)
                return false
            }
            hasDirectInput = true
            hasInitialTouchPoint = false
            pendingTouchCoordinates.clear()
            registerBackHandling()
        }
        cancelMoreHold()
        if (continueGesture) {
            gestureVisitedPanel = uiState.panelGeometry?.bounds?.contains(x.toInt(), y.toInt()) == true
            uiState.startContinuousGesture(x, y)
        }
        uiState.openAllApps(fromHandle = startInAllApps)
        allAppsOpenedListener?.invoke()
        return true
    }

    val acceptsGestureBroadcast get() = !hasDirectInput || uiState.continuousGesture

    private fun registerBackHandling() {
        isFocusableInTouchMode = true
        requestFocus()
        if (backDispatcher == null) {
            backDispatcher = findOnBackInvokedDispatcher()
            backDispatcher?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback,
            )
        }
    }

    private fun handleBack() {
        if (isExiting || uiState.launchingApp) return
        if (startInAllApps) {
            dismissListener?.invoke()
            return
        }
        if (uiState.allAppsRequested) {
            cancelMoreHold()
            resetTouchState()
            uiState.returnToArc()
        } else if (!uiState.panelVisible) dismissListener?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hasDirectInput && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateApps()
        requestLayout()
    }

    fun setInitialTouchPoint(x: Float, y: Float) {
        initialTouchX = x
        initialTouchY = y
        hasInitialTouchPoint = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Supply pixel sizes before Compose measures, avoiding a zero-sized first frame.
        val arcGeometry = currentGeometry()
        uiState.updateGeometry(arcGeometry)
        val metrics = windowManager.currentWindowMetrics
        uiState.panelGeometry = MomentArcPanelGeometry.calculate(
            metrics.bounds.width(), metrics.bounds.height(),
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()),
            resources.displayMetrics.density,
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()),
        )
        // Direct entry is not a corner gesture. Request the panel before Compose's first
        // measurement; never rely on a later input broadcast or mutate WM flags in onLayout.
        if (startInAllApps && !hasAnimated) uiState.openAllApps(fromHandle = true)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (icons.isEmpty()) return
        geometryReady = true
        if (!hasAnimated) {
            uiState.ready = true
            hasAnimated = true
            if (startInAllApps) {
                allAppsOpenedListener?.invoke()
                Log.i("MomentArc", "All-apps panel laid out: ${uiState.panelGeometry?.bounds}")
            }
        }
        while (pendingTouchCoordinates.isNotEmpty()) {
            pendingTouchCoordinates.removeFirst().let {
                processTouchCoordinates(it.x, it.y, it.isUp, it.isCancelled)
            }
        }
    }

    fun dispatchTouchCoordinates(x: Float, y: Float, isUp: Boolean, isCancelled: Boolean) {
        if (isExiting) return
        if (uiState.continuousGesture) {
            if (isUp || isCancelled) {
                val inside = uiState.panelGeometry?.bounds?.contains(x.toInt(), y.toInt()) == true
                val app = if (isCancelled || !uiState.panelInteractive || !inside) null
                    else uiState.selectionAt(x, y)
                uiState.endContinuousGesture()
                resetTouchState()
                val onHandle = uiState.panelGeometry?.handleTouchBounds?.contains(x.toInt(), y.toInt()) == true
                if (!isCancelled && !inside && (gestureVisitedPanel || onHandle)) dismissListener?.invoke()
                if (app != null) appLaunchListener?.invoke(app.target)
                gestureVisitedPanel = false
            } else {
                if (uiState.panelGeometry?.bounds?.contains(x.toInt(), y.toInt()) == true) {
                    gestureVisitedPanel = true
                }
                uiState.moveContinuousGesture(x, y)
            }
            return
        }
        if (hasDirectInput && uiState.panelVisible) return
        if (isCancelled) {
            pendingTouchCoordinates.clear()
            cancelMoreHold()
            cancelTouch()
            return
        }
        if (!geometryReady) {
            pendingTouchCoordinates.addLast(TouchCoordinates(x, y, isUp, isCancelled))
            return
        }
        processTouchCoordinates(x, y, isUp, isCancelled)
    }

    fun animateExit(onEnd: () -> Unit) {
        uiState.exit(currentGeometry()) {
            if (isAttachedToWindow) onEnd()
        }
    }

    fun animateAppLaunch(onEnd: () -> Unit): Boolean = uiState.animateAppLaunch {
        if (isAttachedToWindow) onEnd()
    }

    override fun onDetachedFromWindow() {
        cancelMoreHold()
        uiState.endContinuousGesture()
        gestureVisitedPanel = false
        backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
        backDispatcher = null
        pendingTouchCoordinates.clear()
        resetTouchState()
        geometryReady = false
        composeView.disposeComposition()
        ComposeInitializer.onDetachedFromWindow(this)
        super.onDetachedFromWindow()
    }

    private fun processTouchCoordinates(
        x: Float,
        y: Float,
        isUp: Boolean,
        isCancelled: Boolean,
    ) {
        if (isCancelled) {
            cancelMoreHold()
            cancelTouch()
            return
        }
        if (isUp) cancelMoreHold()
        else {
            lastTouchX = x
            lastTouchY = y
        }
        when {
            !isTouching && !isUp -> {
                isTouching = true
                updateSelectedIcon(x, y)
                if (selectedChildIndex < 0 && hasInitialTouchPoint) {
                    checkIconAlongPath(initialTouchX, initialTouchY, x, y)
                }
            }
            isTouching && !isUp -> updateSelectedIcon(x, y)
            isUp -> finishTouch()
        }
    }

    private fun finishTouch() {
        if (selectedChildIndex in icons.indices) {
            iconLaunchListener?.invoke(selectedChildIndex)
        } else {
            dismissListener?.invoke()
        }
        resetTouchState()
    }

    private fun cancelTouch() {
        resetTouchState()
        dismissListener?.invoke()
    }

    private fun checkIconAlongPath(startX: Float, startY: Float, endX: Float, endY: Float) {
        for (step in 1 until PATH_STEPS) {
            val ratio = step.toFloat() / PATH_STEPS
            val x = startX + (endX - startX) * ratio
            val y = startY + (endY - startY) * ratio
            for (index in icons.indices) {
                val child = icons[index]
                if (child.drawable != null && x >= child.left && x <= child.right &&
                    y >= child.top && y <= child.bottom
                ) {
                    select(index)
                    return
                }
            }
        }
    }

    private fun updateSelectedIcon(x: Float, y: Float) {
        var selected = -1
        for (index in icons.indices) {
            val child = icons[index]
            if (child.drawable != null && x >= child.left && x <= child.right &&
                y >= child.top && y <= child.bottom
            ) {
                selected = index
                break
            }
        }
        select(selected)
    }

    private fun select(index: Int) {
        if (selectedChildIndex != index) {
            cancelMoreHold()
            selectedChildIndex = index
            uiState.select(index)
            if (index == MORE_INDEX && isTouching && !uiState.panelVisible &&
                icons[MORE_INDEX].bounds.contains(lastTouchX.toInt(), lastTouchY.toInt())
            ) scheduleMoreHold(lastTouchX, lastTouchY)
        } else if (index == MORE_INDEX && !uiState.panelVisible &&
            icons[MORE_INDEX].bounds.contains(lastTouchX.toInt(), lastTouchY.toInt())
        ) {
            if (!morePending) scheduleMoreHold(lastTouchX, lastTouchY)
            else if (kotlin.math.hypot(lastTouchX - moreHoldX, lastTouchY - moreHoldY) > touchSlop) {
                mainHandler.removeCallbacks(openMore)
                scheduleMoreHold(lastTouchX, lastTouchY)
            }
        }
        if (index >= 0 && index != lastVibratedIndex) {
            vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            lastVibratedIndex = index
        }
    }

    private fun resetTouchState() {
        isTouching = false
        selectedChildIndex = -1
        lastVibratedIndex = -1
    }

    private fun cancelMoreHold() {
        if (morePending) mainHandler.removeCallbacks(openMore)
        morePending = false
    }

    private fun scheduleMoreHold(x: Float, y: Float) {
        moreHoldX = x
        moreHoldY = y
        morePending = true
        mainHandler.postDelayed(openMore, MORE_HOLD_MS)
    }

    private fun currentGeometry(): MomentArcGeometry {
        val metrics = windowManager.currentWindowMetrics
        return MomentArcGeometry(metrics.bounds.width(), metrics.bounds.height(),
            metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom, isLeft)
    }

    private data class TouchCoordinates(
        val x: Float,
        val y: Float,
        val isUp: Boolean,
        val isCancelled: Boolean,
    )

    companion object {
        private const val PATH_STEPS = 5
        private const val MORE_INDEX = MomentArcGeometry.INNER_COUNT - 1
        private const val MORE_HOLD_MS = 180L

        fun createLayoutParams(directInput: Boolean = false) =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (directInput) {
                    flags = flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
                }
                title = "MomentArc"
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags =
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                        WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION or
                        WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                accessibilityTitle = " "
            }
    }
}
