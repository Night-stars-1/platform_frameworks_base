/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_MOMENT;
import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.server.wm.MomentGeometry.CORNER_TOUCH_SIZE_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_TOP_INSET_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_WIDTH_DP;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import com.android.internal.dynamicanimation.animation.SpringForce;

import com.android.server.UiThread;

final class MomentHandleWindow {

    private static final long LONG_PRESS_TIMEOUT_MS = 320;
    private static final long DOUBLE_TAP_TIMEOUT_MS = ViewConfiguration.getDoubleTapTimeout();
    private static final float COMPACT_MAGNET_MAX_X_VELOCITY = 2000f;
    private static final float COMPACT_FLING_UNSTUCK_MIN_VELOCITY = 4000f;
    private static final float COMPACT_STASH_VELOCITY_THRESHOLD = 18000f;
    private static final int COMPACT_STASH_OFFSET_DP = 32;
    private static final float COMPACT_MAGNET_STIFFNESS = MomentMotion.DEFAULT_SPATIAL.stiffness;
    private static final float COMPACT_CATCH_UP_STIFFNESS = 5000f;
    private static final float COMPACT_NO_BOUNCE = SpringForce.DAMPING_RATIO_NO_BOUNCY;

    private static final int RESIZE_EDGE_NONE = 0;
    private static final int RESIZE_EDGE_LEFT = 1;
    private static final int RESIZE_EDGE_TOP = 1 << 1;
    private static final int RESIZE_EDGE_RIGHT = 1 << 2;
    private static final int RESIZE_EDGE_BOTTOM = 1 << 3;

    private static final int MIUI_GESTURE_NONE = 0;
    private static final int MIUI_GESTURE_TOP_CANDIDATE = 1;
    private static final int MIUI_GESTURE_TOP_DRAG = 2;
    private static final int MIUI_GESTURE_BOTTOM_VERTICAL = 3;
    private static final int MIUI_SWIPE_DISTANCE_DP = 48;
    private static final float MIUI_SWIPE_VELOCITY_DP = 1000f;

    private final WindowManagerService mService;
    private final Task mTask;
    private final MomentHandleSurfaces mHandleSurfaces;
    private final Context mContext;
    private final int mDisplayId;
    private final Handler mHandler = new Handler(UiThread.getHandler().getLooper());
    private final LayoutParams mLayoutParams = new LayoutParams();

    private WindowManager mWindowManager;
    private HandleView mView;
    private MomentCompactDismissWindow mCompactDismissWindow;
    private boolean mAdded;
    private boolean mSurfaceLayerUpdateScheduled;
    private volatile boolean mDestroyed;
    private int mTaskOffsetX;
    private final Rect mLastWindowBounds = new Rect();

    MomentHandleWindow(WindowManagerService service, Task task,
            MomentHandleSurfaces handleSurfaces) {
        mService = service;
        mTask = task;
        mHandleSurfaces = handleSurfaces;
        final Context systemUiContext =
                ActivityThread.currentActivityThread().getSystemUiContext();
        final DisplayContent displayContent = task.getDisplayContent();
        mContext = displayContent != null && displayContent.getDisplay() != null
                ? systemUiContext.createDisplayContext(displayContent.getDisplay())
                : systemUiContext;
        mDisplayId = mContext.getDisplayId();
    }

    boolean isOnDisplay(int displayId) {
        return mDisplayId == displayId;
    }

    void showOrUpdate(Rect momentBounds, float topBarFraction, float bottomBarFraction,
            boolean compact, boolean interactionBlocked) {
        if (mDestroyed) {
            return;
        }
        final Rect bounds = new Rect(momentBounds);
        mHandler.post(() -> {
            if (mDestroyed) {
                return;
            }
            if (mAdded && mView != null && bounds.equals(mView.getMomentBounds())
                    && mView.hasBarFractions(topBarFraction, bottomBarFraction)
                    && compact == mView.isCompact()
                    && interactionBlocked == mView.isInteractionBlocked()) {
                updateSurfaceLayer();
                return;
            }
            if (mView == null) {
                mView = new HandleView(mContext.createWindowContext(TYPE_NAVIGATION_BAR_PANEL, null));
            }
            mView.setBarFractions(topBarFraction, bottomBarFraction);
            mView.setMomentBounds(bounds, compact, interactionBlocked);
            if (!mAdded) {
                addWindow();
            } else {
                updateWindowLayout(bounds);
                getWindowManager().updateViewLayout(mView, mLayoutParams);
                updateSurfaceLayer();
                mView.invalidate();
            }
        });
    }

    void onGestureStyleChanged() {
        if (mDestroyed) {
            return;
        }
        mHandler.post(() -> {
            if (!mDestroyed && mView != null) {
                mView.onGestureStyleChanged();
            }
        });
    }

    Rect getLastWindowBounds() {
        synchronized (mLastWindowBounds) {
            return new Rect(mLastWindowBounds);
        }
    }

    void destroy() {
        if (mDestroyed) {
            return;
        }
        mDestroyed = true;
        mHandler.postAtFrontOfQueue(() -> {
            if (mAdded && mView != null && getWindowManager() != null) {
                getWindowManager().removeViewImmediate(mView);
            }
            mAdded = false;
            mSurfaceLayerUpdateScheduled = false;
            mView = null;
            mHandleSurfaces.hideTop();
            if (mCompactDismissWindow != null) {
                mCompactDismissWindow.destroy();
                mCompactDismissWindow = null;
            }
        });
    }

    private void addWindow() {
        if (getWindowManager() == null || mView == null) {
            return;
        }
        mLayoutParams.type = TYPE_NAVIGATION_BAR_PANEL;
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mLayoutParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        mLayoutParams.setFitInsetsTypes(0);
        mLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        updateWindowLayout(mView.getMomentBounds());
        mLayoutParams.setTitle("MomentHandle#" + mTask.mTaskId);
        getWindowManager().addView(mView, mLayoutParams);
        mAdded = true;
        scheduleSurfaceLayerUpdate();
    }

    private void scheduleSurfaceLayerUpdate() {
        if (mView == null || mSurfaceLayerUpdateScheduled) {
            return;
        }
        mSurfaceLayerUpdateScheduled = true;
        final ViewTreeObserver observer = mView.getViewTreeObserver();
        observer.addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        mSurfaceLayerUpdateScheduled = false;
                        updateSurfaceLayer();
                        return true;
                    }
                });
    }

    private void updateSurfaceLayer() {
        if (!mAdded || mView == null || mView.getViewRootImpl() == null) {
            scheduleSurfaceLayerUpdate();
            return;
        }
        final SurfaceControl windowSurface = mView.getViewRootImpl().getSurfaceControl();
        final SurfaceControl taskSurface = mTask.mSurfaceControl;
        if (windowSurface == null || !windowSurface.isValid()
                || taskSurface == null || !taskSurface.isValid()) {
            scheduleSurfaceLayerUpdate();
            return;
        }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setRelativeLayer(windowSurface, taskSurface, 3).apply();
        }
    }

    private void updateWindowLayout(Rect momentBounds) {
        final int cornerInset = dpToPx(CORNER_TOUCH_SIZE_DP) / 2;
        final int topInset = dpToPx(HANDLE_MENU_TOP_INSET_DP);
        final int windowWidth = Math.max(momentBounds.width() + cornerInset * 2,
                dpToPx(HANDLE_MENU_WIDTH_DP));
        final Rect bottomTouch = new Rect();
        MomentGeometry.getBottomHandleTouchBounds(momentBounds,
                mView != null ? mView.getBottomBarFraction() : 0f,
                mContext.getResources().getDisplayMetrics().density, bottomTouch);
        mLayoutParams.x = momentBounds.centerX() - windowWidth / 2;
        mLayoutParams.y = momentBounds.top - topInset;
        mLayoutParams.width = Math.max(1, windowWidth);
        mLayoutParams.height = Math.max(1, momentBounds.height() + topInset
                + Math.max(cornerInset, bottomTouch.bottom - momentBounds.bottom));
        final int taskOffsetX = momentBounds.left - mLayoutParams.x;
        if (mView != null) {
            mView.setTaskOffsetX(taskOffsetX);
        } else {
            mTaskOffsetX = taskOffsetX;
        }
        synchronized (mLastWindowBounds) {
            mLastWindowBounds.set(mLayoutParams.x, mLayoutParams.y,
                    mLayoutParams.x + mLayoutParams.width,
                    mLayoutParams.y + mLayoutParams.height);
        }
    }

    private WindowManager getWindowManager() {
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
        return mWindowManager;
    }

    private int dpToPx(int dp) {
        return (int) (dp * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class HandleView extends FrameLayout
            implements ViewTreeObserver.OnComputeInternalInsetsListener {

        private final Rect mMomentBounds = new Rect();
        private final Rect mTmpLocalTaskBounds = new Rect();
        private final Rect mTmpTopHandleBounds = new Rect();
        private final Rect mTmpBottomHandleBounds = new Rect();
        private final RectF mTmpHandleBounds = new RectF();
        private float mTopBarFraction;
        private float mBottomBarFraction;
        private final int mTouchSlop;
        private final int mDoubleTapSlopSquared;
        private final int mMaximumFlingVelocity;
        private final Vibrator mVibrator;
        private final VibrationAttributes mVibrationAttributes =
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);

        private boolean mCloseTriggered;
        private boolean mTopHandlePressed;
        private boolean mDragging;
        private boolean mResizing;
        private boolean mResizeUsingHorizontalAxis;
        private boolean mCompact;
        private boolean mInteractionBlocked;
        private boolean mCompactMagnetized;
        private boolean mCompactSpringingToTouch;
        private boolean mCompactDismissing;
        private int mCompactStashedSide;
        private float mCompactDragCenterX;
        private float mCompactDragCenterY;
        private final MomentCompactMotionRunner mCompactMotion;
        private final MomentDragMotion mDragMotion;
        private VelocityTracker mCompactVelocityTracker;
        private float mCompactVelocityX;
        private float mCompactVelocityY;
        private boolean mExceededTouchSlop;
        private int mResizeEdges = RESIZE_EDGE_NONE;
        private int mMiuiGesture = MIUI_GESTURE_NONE;
        private boolean mGestureAborted;
        private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        private int mDownRawX;
        private int mDownRawY;
        private int mStartCenterX;
        private int mStartCenterY;
        private int mResizeFixedX;
        private int mResizeFixedY;
        private float mResizeStartVectorX;
        private float mResizeStartVectorY;
        private float mResizeStartScale;
        private long mLastTapUpTime;
        private int mLastTapRawX;
        private int mLastTapRawY;
        private final Rect mStartMomentBounds = new Rect();
        private final MomentHandleMenuController mHandleMenu;

        private final Runnable mSingleTapRunnable = () -> {
            mLastTapUpTime = 0;
            mService.mMomentController.performMomentBack(mTask);
        };
        private final Runnable mLongPressRunnable = () -> {
            if (mCompact || mInteractionBlocked || mDragging || mResizing
                    || mExceededTouchSlop || mCloseTriggered) {
                return;
            }
            mCloseTriggered = true;
            removeCallbacks(mSingleTapRunnable);
            mLastTapUpTime = 0;
            mService.mMomentController.closeMomentTask(mTask.mTaskId);
        };

        HandleView(Context context) {
            super(context);
            setClipChildren(false);
            final ViewConfiguration configuration = ViewConfiguration.get(context);
            mTouchSlop = configuration.getScaledTouchSlop();
            final int doubleTapSlop = configuration.getScaledDoubleTapSlop();
            mDoubleTapSlopSquared = doubleTapSlop * doubleTapSlop;
            mMaximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();
            mVibrator = context.getSystemService(Vibrator.class);
            mCompactMotion = new MomentCompactMotionRunner(this::applyCompactMotion);
            mDragMotion = new MomentDragMotion(getResources().getDisplayMetrics().density,
                    (x, y) -> {
                        if (mDestroyed || mCompact || mInteractionBlocked) return;
                        applyInteractiveMomentBounds(mService.mMomentController.moveMomentTask(
                                mTask, Math.round(x), Math.round(y)));
                    });
            mHandleMenu = new MomentHandleMenuController(this,
                    () -> mService.mMomentController.expandMomentTaskAnimated(mTask.mTaskId),
                    () -> mService.mMomentController.closeMomentTask(mTask.mTaskId),
                    () -> mService.mMomentController.enterMomentCompact(mTask),
                    this::updateTopHandleSurface);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(mSingleTapRunnable);
            cancelNonCompactGesture();
            abortCompactInteraction();
            mHandleMenu.destroy();
            getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
            super.onDetachedFromWindow();
        }

        void setMomentBounds(Rect bounds, boolean compact, boolean interactionBlocked) {
            if ((!mInteractionBlocked && interactionBlocked) || (!mCompact && compact)) {
                cancelNonCompactGesture();
            }
            if ((mCompact && !compact) || (!mInteractionBlocked && interactionBlocked)) {
                abortCompactInteraction();
            }
            if (mCompact && !compact && mCompactDismissWindow != null) {
                mCompactDismissWindow.destroy();
                mCompactDismissWindow = null;
            }
            if (compact || interactionBlocked) {
                mHandleMenu.collapseImmediately();
            }
            final boolean enteringCompact = !mCompact && compact;
            if (enteringCompact) {
                mCompactMotion.setValues(bounds.exactCenterX(), bounds.exactCenterY(), 0f,
                        false /* dispatch */);
                mCompactDismissing = false;
                mCompactStashedSide =
                        mService.mMomentController.getMomentCompactStashedSide(mTask);
            } else if (compact && !mDragging && !mCompactDismissing
                    && !mCompactMotion.isRunning()) {
                mCompactMotion.setValues(bounds.exactCenterX(), bounds.exactCenterY(),
                        mCompactMotion.getMagnetProgress(), false /* dispatch */);
            }
            mMomentBounds.set(bounds);
            mCompact = compact;
            mInteractionBlocked = interactionBlocked;
            updateTopHandleSurface();
            requestLayout();
            invalidate();
            if (enteringCompact && !interactionBlocked && mCompactStashedSide == 0) {
                // A freshly shrunk window slides to the nearer side instead of staying where the
                // resize or the menu left it.
                startCompactSnap(0f, 0f, false /* allowStash */, 0 /* preferredSide */);
            }
        }

        void setMomentBounds(Rect bounds) {
            setMomentBounds(bounds, mCompact, mInteractionBlocked);
        }

        void setBarFractions(float topBarFraction, float bottomBarFraction) {
            mTopBarFraction = topBarFraction;
            mBottomBarFraction = bottomBarFraction;
        }

        boolean hasBarFractions(float topBarFraction, float bottomBarFraction) {
            return mTopBarFraction == topBarFraction && mBottomBarFraction == bottomBarFraction;
        }

        float getBottomBarFraction() {
            return mBottomBarFraction;
        }

        boolean isCompact() {
            return mCompact;
        }

        boolean isInteractionBlocked() {
            return mInteractionBlocked;
        }

        Rect getMomentBounds() {
            return new Rect(mMomentBounds);
        }

        void onGestureStyleChanged() {
            if (mCompact) {
                return;
            }
            // The style is picked at ACTION_DOWN, so drop whatever is in flight and ignore the
            // rest of that pointer stream.
            cancelNonCompactGesture();
            recycleCompactVelocityTracker();
            mGestureAborted = true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (mInteractionBlocked || mCompactDismissing) {
                        return true;
                    }
                    mDownRawX = (int) event.getRawX();
                    mDownRawY = (int) event.getRawY();
                    mActivePointerId = event.getPointerId(0);
                    mStartCenterX = mMomentBounds.centerX();
                    mStartCenterY = mMomentBounds.centerY();
                    mStartMomentBounds.set(mMomentBounds);
                    mDragMotion.begin(mMomentBounds.exactCenterX(), mMomentBounds.exactCenterY());
                    mCloseTriggered = false;
                    mGestureAborted = false;
                    mMiuiGesture = MIUI_GESTURE_NONE;
                    mTopHandlePressed = false;
                    mDragging = false;
                    mResizing = false;
                    mCompactMagnetized = false;
                    mExceededTouchSlop = false;
                    final long timeSinceLastTap = event.getEventTime() - mLastTapUpTime;
                    if (mLastTapUpTime != 0 && timeSinceLastTap <= DOUBLE_TAP_TIMEOUT_MS) {
                        removeCallbacks(mSingleTapRunnable);
                    }
                    if (mCompact) {
                        obtainCompactVelocityTracker();
                        addCompactVelocityMovement(event);
                        mLastTapUpTime = 0;
                        return true;
                    }
                    final boolean miuiStyle = mService.mMomentController.isMiuiGestureStyle();
                    if (isTopHandleHit(event.getX(), event.getY())) {
                        removeCallbacks(mSingleTapRunnable);
                        mLastTapUpTime = 0;
                        if (miuiStyle && !mHandleMenu.isExpanded()) {
                            mMiuiGesture = MIUI_GESTURE_TOP_CANDIDATE;
                            obtainCompactVelocityTracker();
                            addCompactVelocityMovement(event);
                        } else {
                            mTopHandlePressed = true;
                        }
                        return true;
                    }
                    if (mHandleMenu.isExpanded()) {
                        mHandleMenu.collapse();
                        return true;
                    }
                    mResizeEdges = getResizeEdges(event.getX(), event.getY());
                    if (mResizeEdges != RESIZE_EDGE_NONE) {
                        prepareResizeGesture();
                        mLastTapUpTime = 0;
                        return true;
                    }
                    if (miuiStyle) {
                        mMiuiGesture = MIUI_GESTURE_BOTTOM_VERTICAL;
                        mLastTapUpTime = 0;
                        mService.mMomentController.animateMomentHandlePress(mTask, true);
                        obtainCompactVelocityTracker();
                        addCompactVelocityMovement(event);
                        return true;
                    }
                    if (timeSinceLastTap > DOUBLE_TAP_TIMEOUT_MS) {
                        mLastTapUpTime = 0;
                    }
                    mService.mMomentController.animateMomentHandlePress(mTask, true);
                    obtainCompactVelocityTracker();
                    addCompactVelocityMovement(event);
                    postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mInteractionBlocked || mGestureAborted) {
                        return true;
                    }
                    if (mCloseTriggered) {
                        return true;
                    }
                    if (mCompact) {
                        addCompactVelocityMovement(event);
                        updateCompactDrag(event);
                        return true;
                    }
                    if (mMiuiGesture != MIUI_GESTURE_NONE) {
                        updateMiuiGesture(event);
                        return true;
                    }
                    if (mTopHandlePressed) {
                        final int dx = (int) event.getRawX() - mDownRawX;
                        final int dy = (int) event.getRawY() - mDownRawY;
                        mExceededTouchSlop |= Math.hypot(dx, dy) > mTouchSlop;
                        return true;
                    }
                    if (mResizeEdges != RESIZE_EDGE_NONE) {
                        updateResizeGesture(event);
                        return true;
                    }
                    final int pointerIndex = getActivePointerIndex(event);
                    addCompactVelocityMovement(event);
                    final int dx = (int) event.getRawX(pointerIndex) - mDownRawX;
                    final int dy = (int) event.getRawY(pointerIndex) - mDownRawY;
                    mExceededTouchSlop |= Math.hypot(dx, dy) > mTouchSlop;
                    if (!mDragging && mExceededTouchSlop) {
                        mDragging = true;
                        removeCallbacks(mLongPressRunnable);
                        removeCallbacks(mSingleTapRunnable);
                    }
                    if (mDragging) {
                        mDragMotion.track(mStartCenterX + dx, mStartCenterY + dy);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (mGestureAborted) {
                        return true;
                    }
                    if (mCompact) {
                        addCompactVelocityMovement(event);
                        final int actionIndex = event.getActionIndex();
                        if (event.getPointerId(actionIndex) == mActivePointerId) {
                            final int newIndex = actionIndex == 0 ? 1 : 0;
                            mActivePointerId = event.getPointerId(newIndex);
                            mStartCenterX = Math.round(mDragging
                                    ? mCompactDragCenterX : mMomentBounds.exactCenterX());
                            mStartCenterY = Math.round(mDragging
                                    ? mCompactDragCenterY : mMomentBounds.exactCenterY());
                            mDownRawX = Math.round(event.getRawX(newIndex));
                            mDownRawY = Math.round(event.getRawY(newIndex));
                        }
                        return true;
                    }
                    if (mTopHandlePressed) {
                        mTopHandlePressed = false;
                        mExceededTouchSlop = true;
                        return true;
                    }
                    final int actionIndex = event.getActionIndex();
                    if (event.getPointerId(actionIndex) == mActivePointerId) {
                        final int newIndex = actionIndex == 0 ? 1 : 0;
                        mActivePointerId = event.getPointerId(newIndex);
                        mStartCenterX = mMomentBounds.centerX();
                        mStartCenterY = mMomentBounds.centerY();
                        mDownRawX = Math.round(event.getRawX(newIndex));
                        mDownRawY = Math.round(event.getRawY(newIndex));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mInteractionBlocked || mGestureAborted) {
                        return true;
                    }
                    if (mCloseTriggered) {
                        return true;
                    }
                    removeCallbacks(mLongPressRunnable);
                    if (mCompact) {
                        addCompactVelocityMovement(event);
                        finishCompactGesture();
                        return true;
                    }
                    if (mMiuiGesture != MIUI_GESTURE_NONE) {
                        finishMiuiGesture(event);
                        return true;
                    }
                    if (mTopHandlePressed) {
                        if (!mExceededTouchSlop && isTopHandleHit(event.getX(), event.getY())) {
                            mHandleMenu.toggle();
                        }
                        mTopHandlePressed = false;
                        mExceededTouchSlop = false;
                        return true;
                    }
                    if (mResizeEdges != RESIZE_EDGE_NONE) {
                        mService.mMomentController.finishMomentResize(mTask, mResizeStartScale);
                        resetResizeGesture();
                        return true;
                    }
                    addCompactVelocityMovement(event);
                    computeCompactVelocity();
                    if (mDragging) mDragMotion.release(mCompactVelocityX, mCompactVelocityY);
                    recycleCompactVelocityTracker();
                    if (!mDragging && !mExceededTouchSlop && isDoubleTap(event)) {
                        removeCallbacks(mSingleTapRunnable);
                        mLastTapUpTime = 0;
                        mService.mMomentController.expandMomentTaskAnimated(mTask.mTaskId);
                    } else {
                        if (!mDragging && !mExceededTouchSlop) {
                            mLastTapUpTime = event.getEventTime();
                            mLastTapRawX = (int) event.getRawX();
                            mLastTapRawY = (int) event.getRawY();
                            removeCallbacks(mSingleTapRunnable);
                            postDelayed(mSingleTapRunnable, DOUBLE_TAP_TIMEOUT_MS);
                        } else {
                            mLastTapUpTime = 0;
                        }
                        mService.mMomentController.animateMomentHandlePress(mTask, false);
                    }
                    mDragging = false;
                    mExceededTouchSlop = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (mInteractionBlocked || mGestureAborted) {
                        return true;
                    }
                    if (mCloseTriggered) {
                        return true;
                    }
                    removeCallbacks(mLongPressRunnable);
                    if (mCompact) {
                        addCompactVelocityMovement(event);
                        cancelCompactGesture();
                        return true;
                    }
                    if (mMiuiGesture != MIUI_GESTURE_NONE) {
                        if (mMiuiGesture == MIUI_GESTURE_BOTTOM_VERTICAL) {
                            mService.mMomentController.animateMomentHandlePress(mTask, false);
                        }
                        mMiuiGesture = MIUI_GESTURE_NONE;
                        mDragMotion.cancel();
                        recycleCompactVelocityTracker();
                        mDragging = false;
                        mExceededTouchSlop = false;
                        return true;
                    }
                    if (mTopHandlePressed) {
                        mTopHandlePressed = false;
                        mExceededTouchSlop = false;
                        return true;
                    }
                    if (mResizeEdges != RESIZE_EDGE_NONE) {
                        resetResizeGesture();
                        return true;
                    }
                    mLastTapUpTime = 0;
                    mService.mMomentController.animateMomentHandlePress(mTask, false);
                    mDragMotion.cancel();
                    recycleCompactVelocityTracker();
                    mDragging = false;
                    mExceededTouchSlop = false;
                    return true;
                case MotionEvent.ACTION_OUTSIDE:
                    if (mHandleMenu.isExpanded()) {
                        mHandleMenu.collapse();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        /** Top bar drags the window once past the slop; bottom bar only tracks the swipe. */
        private void updateMiuiGesture(MotionEvent event) {
            addCompactVelocityMovement(event);
            if (mMiuiGesture == MIUI_GESTURE_BOTTOM_VERTICAL) {
                return;
            }
            final int pointerIndex = getActivePointerIndex(event);
            final int dx = (int) event.getRawX(pointerIndex) - mDownRawX;
            final int dy = (int) event.getRawY(pointerIndex) - mDownRawY;
            if (mMiuiGesture == MIUI_GESTURE_TOP_CANDIDATE && Math.hypot(dx, dy) > mTouchSlop) {
                mMiuiGesture = MIUI_GESTURE_TOP_DRAG;
                mDragging = true;
                mExceededTouchSlop = true;
            }
            if (mMiuiGesture == MIUI_GESTURE_TOP_DRAG) {
                mDragMotion.track(mStartCenterX + dx, mStartCenterY + dy);
            }
        }

        private void finishMiuiGesture(MotionEvent event) {
            addCompactVelocityMovement(event);
            computeCompactVelocity();
            recycleCompactVelocityTracker();
            final int gesture = mMiuiGesture;
            mMiuiGesture = MIUI_GESTURE_NONE;
            mDragging = false;
            mExceededTouchSlop = false;
            mLastTapUpTime = 0;
            if (gesture == MIUI_GESTURE_TOP_CANDIDATE) {
                if (isTopHandleHit(event.getX(), event.getY())) {
                    mHandleMenu.toggle();
                }
                return;
            }
            if (gesture == MIUI_GESTURE_TOP_DRAG) {
                mDragMotion.release(mCompactVelocityX, mCompactVelocityY);
                return;
            }
            final int dx = (int) event.getRawX() - mDownRawX;
            final int dy = (int) event.getRawY() - mDownRawY;
            final float density = getResources().getDisplayMetrics().density;
            final float distance = MIUI_SWIPE_DISTANCE_DP * density;
            final float velocity = MIUI_SWIPE_VELOCITY_DP * density;
            if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > mTouchSlop) {
                if (dy > 0 && (dy >= distance || mCompactVelocityY >= velocity)) {
                    mService.mMomentController.expandMomentTaskAnimated(mTask.mTaskId);
                    return;
                }
                if (dy < 0 && (-dy >= distance || -mCompactVelocityY >= velocity)) {
                    mService.mMomentController.closeMomentTask(mTask.mTaskId);
                    return;
                }
            }
            mService.mMomentController.animateMomentHandlePress(mTask, false);
        }

        @Override
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
            updateTouchableRegion(info.touchableRegion);
            info.setTouchableInsets(TOUCHABLE_INSETS_REGION);
        }

        private void updateTouchableRegion(Region region) {
            if (mMomentBounds.isEmpty()) {
                region.setEmpty();
                return;
            }
            final int cornerSize = dpToPx(CORNER_TOUCH_SIZE_DP);
            final int cornerInset = cornerSize / 2;
            final int taskLeft = getTaskLeft();
            final int taskTop = getTaskTop();
            final int taskRight = taskLeft + mMomentBounds.width();
            final int taskBottom = taskTop + mMomentBounds.height();
            if (mCompact || mInteractionBlocked) {
                region.set(taskLeft, taskTop, taskRight, taskBottom);
                return;
            }
            getBottomHandleTouchBounds(mTmpBottomHandleBounds);
            region.set(mTmpBottomHandleBounds);
            getCurrentTopHandleBounds(mTmpTopHandleBounds,
                    true /* includeCollapsedTouchTarget */);
            region.op(mTmpTopHandleBounds, Region.Op.UNION);
            region.op(taskLeft - cornerInset, taskTop - cornerInset,
                    taskLeft - cornerInset + cornerSize, taskTop - cornerInset + cornerSize,
                    Region.Op.UNION);
            region.op(taskRight - cornerInset, taskTop - cornerInset,
                    taskRight - cornerInset + cornerSize, taskTop - cornerInset + cornerSize,
                    Region.Op.UNION);
            region.op(taskLeft - cornerInset, taskBottom - cornerInset,
                    taskLeft - cornerInset + cornerSize, taskBottom - cornerInset + cornerSize,
                    Region.Op.UNION);
            region.op(taskRight - cornerInset, taskBottom - cornerInset,
                    taskRight - cornerInset + cornerSize, taskBottom - cornerInset + cornerSize,
                    Region.Op.UNION);
        }

        private boolean isTopHandleHit(float x, float y) {
            getCurrentTopHandleBounds(mTmpTopHandleBounds,
                    true /* includeCollapsedTouchTarget */);
            return mTmpTopHandleBounds.contains(Math.round(x), Math.round(y));
        }

        private void getBottomHandleTouchBounds(Rect outBounds) {
            getLocalTaskBounds(mTmpLocalTaskBounds);
            MomentGeometry.getBottomHandleTouchBounds(mTmpLocalTaskBounds, mBottomBarFraction,
                    getResources().getDisplayMetrics().density, outBounds);
        }

        private int getTaskTop() {
            return dpToPx(HANDLE_MENU_TOP_INSET_DP);
        }

        private int getTaskLeft() {
            return mTaskOffsetX;
        }

        private void getLocalTaskBounds(Rect outBounds) {
            final int taskLeft = getTaskLeft();
            final int taskTop = getTaskTop();
            outBounds.set(taskLeft, taskTop, taskLeft + mMomentBounds.width(),
                    taskTop + mMomentBounds.height());
        }

        void setTaskOffsetX(int taskOffsetX) {
            if (mTaskOffsetX == taskOffsetX) {
                return;
            }
            mTaskOffsetX = taskOffsetX;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            mHandleMenu.measure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            mHandleMenu.layout(getTaskLeft(), getTaskTop(), mMomentBounds.width());
        }

        private void getCurrentTopHandleBounds(Rect outBounds,
                boolean includeCollapsedTouchTarget) {
            getLocalTaskBounds(mTmpLocalTaskBounds);
            final float density = getResources().getDisplayMetrics().density;
            MomentGeometry.getTopHandleBounds(mTmpLocalTaskBounds, mTopBarFraction, density,
                    mHandleMenu.getShapeProgress(), includeCollapsedTouchTarget,
                    mTmpHandleBounds);
            mTmpHandleBounds.round(outBounds);
        }

        private void updateTopHandleSurface() {
            if (mCompact || mInteractionBlocked || mMomentBounds.isEmpty()) {
                mHandleSurfaces.hideTop();
                return;
            }
            mHandleSurfaces.showOrUpdateTop(mMomentBounds, mTopBarFraction,
                    mHandleMenu.getShapeProgress());
        }

        private int getResizeEdges(float x, float y) {
            final int cornerSize = dpToPx(CORNER_TOUCH_SIZE_DP);
            final int cornerInset = cornerSize / 2;
            final int taskLeft = getTaskLeft();
            final int taskTop = getTaskTop();
            final int taskRight = taskLeft + mMomentBounds.width();
            final int taskBottom = taskTop + mMomentBounds.height();
            final boolean left = x >= taskLeft - cornerInset
                    && x < taskLeft - cornerInset + cornerSize;
            final boolean right = x > taskRight - cornerInset
                    && x <= taskRight - cornerInset + cornerSize;
            final boolean top = y >= taskTop - cornerInset
                    && y < taskTop - cornerInset + cornerSize;
            final boolean bottom = y > taskBottom - cornerInset
                    && y <= taskBottom - cornerInset + cornerSize;
            if (!(left || right) || !(top || bottom)) {
                return RESIZE_EDGE_NONE;
            }
            return (left ? RESIZE_EDGE_LEFT : RESIZE_EDGE_RIGHT)
                    | (top ? RESIZE_EDGE_TOP : RESIZE_EDGE_BOTTOM);
        }

        private void prepareResizeGesture() {
            final boolean resizeFromLeft = (mResizeEdges & RESIZE_EDGE_LEFT) != 0;
            final boolean resizeFromTop = (mResizeEdges & RESIZE_EDGE_TOP) != 0;
            mResizeFixedX = resizeFromLeft ? mStartMomentBounds.right : mStartMomentBounds.left;
            mResizeFixedY = resizeFromTop ? mStartMomentBounds.bottom : mStartMomentBounds.top;
            final int movingX = resizeFromLeft
                    ? mStartMomentBounds.left : mStartMomentBounds.right;
            final int movingY = resizeFromTop
                    ? mStartMomentBounds.top : mStartMomentBounds.bottom;
            mResizeStartVectorX = movingX - mResizeFixedX;
            mResizeStartVectorY = movingY - mResizeFixedY;
            mResizeStartScale = mService.mMomentController.getMomentTaskScale(mTask);
        }

        private void updateResizeGesture(MotionEvent event) {
            final int pointerIndex = getActivePointerIndex(event);
            final int dx = (int) event.getRawX(pointerIndex) - mDownRawX;
            final int dy = (int) event.getRawY(pointerIndex) - mDownRawY;
            if (!mResizing && Math.hypot(dx, dy) <= mTouchSlop) {
                return;
            }
            if (!mResizing) {
                final float relativeDx = Math.abs(dx / mResizeStartVectorX);
                final float relativeDy = Math.abs(dy / mResizeStartVectorY);
                mResizeUsingHorizontalAxis = relativeDx >= relativeDy;
                mResizing = true;
                mService.mMomentController.setMomentResizing(mTask, true);
            }
            final float pointerVectorX = event.getRawX(pointerIndex) - mResizeFixedX;
            final float pointerVectorY = event.getRawY(pointerIndex) - mResizeFixedY;
            final float scaleFactor = mResizeUsingHorizontalAxis
                    ? pointerVectorX / mResizeStartVectorX
                    : pointerVectorY / mResizeStartVectorY;
            final boolean resizeFromLeft = (mResizeEdges & RESIZE_EDGE_LEFT) != 0;
            final boolean resizeFromTop = (mResizeEdges & RESIZE_EDGE_TOP) != 0;
            final Rect resizedBounds = mService.mMomentController.resizeMomentTask(mTask,
                    mResizeStartScale * scaleFactor, mResizeFixedX, mResizeFixedY,
                    resizeFromLeft, resizeFromTop);
            applyInteractiveMomentBounds(resizedBounds);
        }

        private void applyInteractiveMomentBounds(Rect bounds) {
            if (bounds.isEmpty()) {
                return;
            }
            setMomentBounds(bounds);
            updateWindowLayout(bounds);
            final WindowManager windowManager = getWindowManager();
            if (mAdded && windowManager != null) {
                windowManager.updateViewLayout(this, mLayoutParams);
            }
        }

        private void resetResizeGesture() {
            if (mResizing) mService.mMomentController.setMomentResizing(mTask, false);
            mResizeEdges = RESIZE_EDGE_NONE;
            mResizing = false;
            mLastTapUpTime = 0;
        }

        private void cancelNonCompactGesture() {
            mDragMotion.cancel();
            removeCallbacks(mLongPressRunnable);
            mHandleSurfaces.animateBottomPress(false);
            mTopHandlePressed = false;
            mDragging = false;
            mExceededTouchSlop = false;
            mMiuiGesture = MIUI_GESTURE_NONE;
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;
            resetResizeGesture();
        }

        private void updateCompactDrag(MotionEvent event) {
            final int pointerIndex = getActivePointerIndex(event);
            final float rawX = event.getRawX(pointerIndex);
            final float rawY = event.getRawY(pointerIndex);
            final int dx = (int) rawX - mDownRawX;
            final int dy = (int) rawY - mDownRawY;
            if (!mDragging && Math.hypot(dx, dy) <= mTouchSlop) {
                return;
            }
            if (!mDragging) {
                mDragging = true;
                cancelCompactMotion();
                getCompactDismissWindow().show(mTask.mSurfaceControl);
            }
            final MomentCompactDismissWindow dismissWindow = getCompactDismissWindow();
            mCompactDragCenterX = mStartCenterX + dx;
            mCompactDragCenterY = mStartCenterY + dy;
            final boolean inMagneticTarget = dismissWindow.isInMagneticTarget(
                    rawX, rawY);
            computeCompactVelocity();
            if (!mCompactMagnetized && inMagneticTarget
                    && Math.abs(mCompactVelocityX) <= COMPACT_MAGNET_MAX_X_VELOCITY) {
                mCompactMagnetized = true;
                mCompactSpringingToTouch = false;
                vibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
                mCompactMotion.springTo(dismissWindow.getTargetCenterX(),
                        dismissWindow.getTargetCenterY(), 1f, mCompactVelocityX,
                        mCompactVelocityY,
                        COMPACT_MAGNET_STIFFNESS, MomentMotion.DEFAULT_SPATIAL.damping, null);
            } else if (mCompactMagnetized && !inMagneticTarget) {
                mCompactMagnetized = false;
                vibrate(VibrationEffect.EFFECT_TICK);
                mCompactMotion.springTo(mCompactDragCenterX, mCompactDragCenterY, 0f,
                        mCompactVelocityX, mCompactVelocityY, COMPACT_CATCH_UP_STIFFNESS,
                        COMPACT_NO_BOUNCE, () -> mCompactSpringingToTouch = false);
                mCompactSpringingToTouch = true;
            } else if (!mCompactMagnetized) {
                if (mCompactSpringingToTouch
                        && mCompactMotion.retargetSpring(mCompactDragCenterX,
                                mCompactDragCenterY, 0f)) {
                    // The catch-up spring now follows the current touch point.
                } else {
                    mCompactMotion.setValues(mCompactDragCenterX, mCompactDragCenterY, 0f,
                            true /* dispatch */);
                }
            }
        }

        private void finishCompactGesture() {
            if (mDragging) {
                computeCompactVelocity();
                recycleCompactVelocityTracker();
                if (mCompactMagnetized) {
                    cancelCompactMotion();
                    if (mCompactVelocityY < -COMPACT_FLING_UNSTUCK_MIN_VELOCITY) {
                        mCompactMagnetized = false;
                        getCompactDismissWindow().hide();
                        startCompactSnap(mCompactVelocityX, mCompactVelocityY,
                                true /* allowStash */, getCompactDragSide());
                    } else {
                        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
                        post(this::startCompactDismiss);
                    }
                } else {
                    startCompactSnap(mCompactVelocityX, mCompactVelocityY,
                            true /* allowStash */, getCompactDragSide());
                }
            } else {
                recycleCompactVelocityTracker();
                cancelCompactMotion();
                if (mCompactStashedSide != 0) {
                    startCompactSnap(0f, 0f, false /* allowStash */, 0 /* preferredSide */);
                } else {
                    mService.mMomentController.restoreMomentCompact(mTask);
                }
            }
            mDragging = false;
        }

        /**
         * Edge the user dragged toward: the side of the net horizontal movement since the gesture
         * started, or 0 when it barely moved sideways.
         */
        private int getCompactDragSide() {
            final float dx = mCompactDragCenterX - mStartCenterX;
            if (Math.abs(dx) <= mTouchSlop) {
                return 0;
            }
            return dx < 0f ? -1 : 1;
        }

        private void cancelCompactGesture() {
            recycleCompactVelocityTracker();
            cancelCompactMotion();
            if (mDragging) {
                mCompactStashedSide = 0;
                mService.mMomentController.setMomentCompactStashedSide(mTask, 0);
                mService.mMomentController.finishMomentCompactDrag(mTask);
                getCompactDismissWindow().hide();
            }
            mDragging = false;
            mCompactMagnetized = false;
            mCompactMotion.setValues(mCompactMotion.getCenterX(), mCompactMotion.getCenterY(), 0f,
                    false /* dispatch */);
        }

        /**
         * Settles the compact window. Unless it is being stashed, it always ends against the left
         * or right edge: {@code preferredSide} picks the edge (-1 left, 1 right), and 0 uses the
         * nearer one.
         */
        private void startCompactSnap(float velocityX, float velocityY, boolean allowStash,
                int preferredSide) {
            final Rect movementBounds =
                    mService.mMomentController.getMomentCompactMovementBounds(mTask);
            if (movementBounds.isEmpty()) {
                mService.mMomentController.finishMomentCompactDrag(mTask);
                return;
            }
            if (mCompactMotion.getCenterX() == 0f && mCompactMotion.getCenterY() == 0f) {
                mCompactMotion.setValues(mMomentBounds.exactCenterX(),
                        mMomentBounds.exactCenterY(), mCompactMotion.getMagnetProgress(),
                        false /* dispatch */);
            }
            final int compactWidth = mStartMomentBounds.width() > 0
                    ? mStartMomentBounds.width() : mMomentBounds.width();
            final boolean wasStashed = mCompactStashedSide != 0;
            final boolean stashLeft = allowStash && mCompactStashedSide != 1
                    && mService.mMomentController.canStashMomentCompact(mTask, -1)
                    && (velocityX < -COMPACT_STASH_VELOCITY_THRESHOLD
                            || mCompactMotion.getCenterX()
                                    < movementBounds.left - compactWidth / 2f);
            final boolean stashRight = allowStash && mCompactStashedSide != -1
                    && mService.mMomentController.canStashMomentCompact(mTask, 1)
                    && (velocityX > COMPACT_STASH_VELOCITY_THRESHOLD
                            || mCompactMotion.getCenterX()
                                    > movementBounds.right + compactWidth / 2f);
            if (velocityX == 0f) {
                final boolean towardLeft = preferredSide != 0 ? preferredSide < 0
                        : mCompactMotion.getCenterX() < movementBounds.exactCenterX();
                velocityX = towardLeft ? -0.001f : 0.001f;
            }
            final int stashOffset = dpToPx(COMPACT_STASH_OFFSET_DP);
            final float leftStashX = movementBounds.left - compactWidth + stashOffset;
            final float rightStashX = movementBounds.right + compactWidth - stashOffset;
            final int targetStashedSide;
            if (stashLeft) {
                targetStashedSide = -1;
                velocityX = Math.min(velocityX, -0.001f);
            } else if (stashRight) {
                targetStashedSide = 1;
                velocityX = Math.max(velocityX, 0.001f);
            } else {
                targetStashedSide = 0;
            }
            final float edgeX;
            if (preferredSide != 0) {
                edgeX = preferredSide < 0 ? movementBounds.left : movementBounds.right;
            } else {
                edgeX = mCompactMotion.getCenterX() < movementBounds.exactCenterX()
                        ? movementBounds.left : movementBounds.right;
            }
            if ((stashLeft || stashRight) && !wasStashed) {
                velocityY = 0f;
            }
            mCompactMagnetized = false;
            mCompactSpringingToTouch = false;
            getCompactDismissWindow().hide();
            final float flingMinX = stashLeft || stashRight ? leftStashX : edgeX;
            final float flingMaxX = stashLeft || stashRight ? rightStashX : edgeX;
            mCompactMotion.flingThenSpring(flingMinX, flingMaxX, velocityX,
                    movementBounds.top, movementBounds.bottom, velocityY,
                    () -> {
                        mCompactStashedSide = targetStashedSide;
                        mService.mMomentController.setMomentCompactStashedSide(
                                mTask, targetStashedSide);
                        if (targetStashedSide == 0) {
                            mService.mMomentController.finishMomentCompactDrag(mTask);
                        }
                    });
        }

        private void startCompactDismiss() {
            if (!mCompact || !mCompactMagnetized) {
                return;
            }
            mCompactDismissing = true;
            getCompactDismissWindow().hide();
            final float targetY = mService.mMomentController.getMomentDisplayBottom(mTask)
                    + mStartMomentBounds.height() + mMomentBounds.height() / 2f;
            mCompactMotion.dismissTo(targetY,
                    () -> mService.mMomentController.dismissMomentCompactTask(mTask));
        }

        private void obtainCompactVelocityTracker() {
            recycleCompactVelocityTracker();
            mCompactVelocityTracker = VelocityTracker.obtain();
        }

        private void addCompactVelocityMovement(MotionEvent event) {
            if (mCompactVelocityTracker == null) {
                return;
            }
            final MotionEvent rawEvent = MotionEvent.obtain(event);
            rawEvent.offsetLocation(event.getRawX() - event.getX(),
                    event.getRawY() - event.getY());
            mCompactVelocityTracker.addMovement(rawEvent);
            rawEvent.recycle();
        }

        private void recycleCompactVelocityTracker() {
            if (mCompactVelocityTracker != null) {
                mCompactVelocityTracker.recycle();
                mCompactVelocityTracker = null;
            }
        }

        private void computeCompactVelocity() {
            if (mCompactVelocityTracker == null) {
                mCompactVelocityX = 0f;
                mCompactVelocityY = 0f;
                return;
            }
            mCompactVelocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
            mCompactVelocityX = mCompactVelocityTracker.getXVelocity(mActivePointerId);
            mCompactVelocityY = mCompactVelocityTracker.getYVelocity(mActivePointerId);
        }

        private int getActivePointerIndex(MotionEvent event) {
            final int index = event.findPointerIndex(mActivePointerId);
            return index >= 0 ? index : 0;
        }

        private void applyCompactMotion(float centerX, float centerY, float magnetProgress) {
            if (!mCompact) {
                return;
            }
            if (mCompactDismissWindow != null) {
                mCompactDismissWindow.setMagnetProgress(magnetProgress);
            }
            mService.mMomentController.moveMomentCompactTask(mTask, centerX, centerY,
                    magnetProgress, false /* constrainToMovementBounds */);
        }

        private void abortCompactInteraction() {
            cancelCompactMotion();
            recycleCompactVelocityTracker();
            if (mCompactDismissWindow != null) {
                mCompactDismissWindow.hide();
            }
            if (mCompact && mDragging && !mCompactDismissing
                    && mTask.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                mCompactStashedSide = 0;
                mService.mMomentController.setMomentCompactStashedSide(mTask, 0);
                mService.mMomentController.finishMomentCompactDrag(mTask);
            }
            mDragging = false;
            mCompactMagnetized = false;
            mCompactDismissing = false;
            mCompactMotion.setValues(mCompactMotion.getCenterX(), mCompactMotion.getCenterY(), 0f,
                    false /* dispatch */);
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        }

        private void cancelCompactMotion() {
            mCompactMotion.cancel();
            mCompactSpringingToTouch = false;
        }

        private void vibrate(int effectId) {
            if (mVibrator != null && mVibrator.hasVibrator()) {
                mVibrator.vibrate(VibrationEffect.get(effectId), mVibrationAttributes);
            }
        }

        private boolean isDoubleTap(MotionEvent event) {
            if (mLastTapUpTime == 0
                    || event.getEventTime() - mLastTapUpTime > DOUBLE_TAP_TIMEOUT_MS) {
                return false;
            }
            final int dx = (int) event.getRawX() - mLastTapRawX;
            final int dy = (int) event.getRawY() - mLastTapRawY;
            return dx * dx + dy * dy <= mDoubleTapSlopSquared;
        }
    }

    private MomentCompactDismissWindow getCompactDismissWindow() {
        if (mCompactDismissWindow == null) {
            mCompactDismissWindow = new MomentCompactDismissWindow(mContext, mTask);
        }
        return mCompactDismissWindow;
    }

}
