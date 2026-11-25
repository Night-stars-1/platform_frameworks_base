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

import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.server.wm.MomentGeometry.COMPACT_DISMISS_TARGET_SIZE_DP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

final class MomentCompactDismissWindow {

    private static final int OVERLAY_HEIGHT_DP = 548;
    private static final int TARGET_ICON_SIZE_DP = 32;
    private static final int TARGET_BOTTOM_MARGIN_DP = 50;
    private static final float MAGNET_RADIUS_MULTIPLIER = 1.25f;
    private static final float MAGNETIZED_TARGET_SCALE = 1.15f;

    private final Context mContext;
    private final Task mTask;
    private final WindowManager mWindowManager;
    private final DismissView mView;
    private final LayoutParams mLayoutParams = new LayoutParams();

    private boolean mAdded;
    private boolean mLayerUpdateScheduled;
    private SurfaceControl mLayerTaskSurface;
    private ViewTreeObserver mLayerObserver;
    private ViewTreeObserver.OnPreDrawListener mLayerListener;
    private int mTargetCenterX;
    private int mTargetCenterY;
    private float mTargetCenterLocalY;
    private float mTargetRadius;
    private float mMagnetRadius;

    MomentCompactDismissWindow(Context context, Task task) {
        mContext = context.createWindowContext(TYPE_NAVIGATION_BAR_PANEL, null);
        mTask = task;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mView = new DismissView(mContext);
    }

    void show(SurfaceControl taskSurface) {
        mView.setMagnetProgress(0f);
        if (!mAdded) {
            updateLayoutParams();
            mView.setVisibility(View.INVISIBLE);
            mWindowManager.addView(mView, mLayoutParams);
            mAdded = true;
        } else {
            updateLayoutParams();
            mWindowManager.updateViewLayout(mView, mLayoutParams);
        }
        scheduleLayerUpdate(taskSurface);
        mView.show();
    }

    void hide() {
        if (!mAdded) {
            return;
        }
        mView.hide();
    }

    void setMagnetProgress(float progress) {
        if (mAdded) {
            mView.setMagnetProgress(progress);
        }
    }

    void destroy() {
        cancelLayerUpdate();
        if (mAdded) {
            mView.cancelAnimations();
            mWindowManager.removeViewImmediate(mView);
            mAdded = false;
        }
    }

    boolean isInMagneticTarget(float rawX, float rawY) {
        final float dx = rawX - mTargetCenterX;
        final float dy = rawY - mTargetCenterY;
        return dx * dx + dy * dy < mMagnetRadius * mMagnetRadius;
    }

    int getTargetCenterX() {
        return mTargetCenterX;
    }

    int getTargetCenterY() {
        return mTargetCenterY;
    }

    private void updateLayoutParams() {
        final int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
        final int overlayHeight = Math.min(screenHeight, dpToPx(OVERLAY_HEIGHT_DP));
        final int navigationBarInset = getNavigationBarInset();
        mTargetRadius = dpToPx(COMPACT_DISMISS_TARGET_SIZE_DP) / 2f;
        mMagnetRadius = mTargetRadius * 2f * MAGNET_RADIUS_MULTIPLIER;
        mTargetCenterX = screenWidth / 2;
        mTargetCenterY = Math.round(screenHeight - navigationBarInset
                - dpToPx(TARGET_BOTTOM_MARGIN_DP) - mTargetRadius);
        mTargetCenterLocalY = mTargetCenterY - (screenHeight - overlayHeight);
        mLayoutParams.type = TYPE_NAVIGATION_BAR_PANEL;
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_NOT_TOUCHABLE
                | LayoutParams.FLAG_NOT_FOCUSABLE;
        mLayoutParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY
                | SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLayoutParams.setFitInsetsTypes(0);
        mLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        mLayoutParams.x = 0;
        mLayoutParams.y = screenHeight - overlayHeight;
        mLayoutParams.width = LayoutParams.MATCH_PARENT;
        mLayoutParams.height = overlayHeight;
        mLayoutParams.setTitle("MomentCompactDismiss#" + mTask.mTaskId);
    }

    private void scheduleLayerUpdate(SurfaceControl taskSurface) {
        mLayerTaskSurface = taskSurface;
        if (mLayerUpdateScheduled) {
            return;
        }
        mLayerUpdateScheduled = true;
        mLayerObserver = mView.getViewTreeObserver();
        mLayerListener = () -> {
            final SurfaceControl currentTaskSurface = mLayerTaskSurface;
            cancelLayerUpdate();
            if (!mAdded || mView.getViewRootImpl() == null) {
                return true;
            }
            final SurfaceControl surface = mView.getViewRootImpl().getSurfaceControl();
            if (surface != null && surface.isValid() && currentTaskSurface != null
                    && currentTaskSurface.isValid()) {
                try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
                    t.setRelativeLayer(surface, currentTaskSurface, -1).apply();
                }
            }
            return true;
        };
        mLayerObserver.addOnPreDrawListener(mLayerListener);
    }

    private void cancelLayerUpdate() {
        if (mLayerObserver != null && mLayerObserver.isAlive() && mLayerListener != null) {
            mLayerObserver.removeOnPreDrawListener(mLayerListener);
        }
        mLayerUpdateScheduled = false;
        mLayerTaskSurface = null;
        mLayerObserver = null;
        mLayerListener = null;
    }

    private int dpToPx(int dp) {
        return (int) (dp * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getNavigationBarInset() {
        return mWindowManager.getCurrentWindowMetrics().getWindowInsets()
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
    }

    private final class DismissView extends View {

        private final Paint mGradientPaint = new Paint();
        private final Paint mCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mNeutralColor;
        private float mScrimAlpha;
        private float mCircleTranslationY;
        private float mMagnetProgress;
        private boolean mShowing;
        private SpringAnimation mScrimAnimator;
        private SpringAnimation mCircleSpring;
        private Runnable mCircleEndAction;

        DismissView(Context context) {
            super(context);
            mCirclePaint.setColor(context.getColor(android.R.color.system_primary_fixed));
            mIconPaint.setColor(context.getColor(android.R.color.system_on_primary_fixed));
            mNeutralColor = context.getColor(android.R.color.system_neutral1_900);
            mIconPaint.setStrokeCap(Paint.Cap.BUTT);
            mIconPaint.setStrokeWidth(dpToPx(2));
            mCircleTranslationY = dpToPx(OVERLAY_HEIGHT_DP);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            mGradientPaint.setShader(new LinearGradient(0, 0, 0, height,
                    Color.TRANSPARENT, withAlpha(mNeutralColor, 0.7f), Shader.TileMode.CLAMP));
        }

        void show() {
            if (mShowing) {
                return;
            }
            mShowing = true;
            setVisibility(View.VISIBLE);
            animateScrimTo(1f);
            animateCircleTo(0f, null);
        }

        void hide() {
            if (!mShowing) {
                return;
            }
            mShowing = false;
            animateScrimTo(0f);
            animateCircleTo(getHeight(), () -> setVisibility(View.INVISIBLE));
        }

        void setMagnetProgress(float progress) {
            mMagnetProgress = Math.max(0f, Math.min(1f, progress));
            invalidate();
        }

        void cancelAnimations() {
            mCircleEndAction = null;
            if (mScrimAnimator != null) {
                mScrimAnimator.cancel();
                mScrimAnimator = null;
            }
            if (mCircleSpring != null) {
                mCircleSpring.cancel();
                mCircleSpring = null;
            }
        }

        private void animateScrimTo(float target) {
            if (mScrimAnimator != null && mScrimAnimator.isRunning()) {
                mScrimAnimator.animateToFinalPosition(target);
                return;
            }
            mScrimAnimator = MomentMotion.spring(mScrimAlpha, target, 0f,
                    MomentMotion.DEFAULT_EFFECTS, 0.001f);
            mScrimAnimator.addUpdateListener((animation, value, velocity) -> {
                mScrimAlpha = MomentMotion.clamp(value);
                invalidate();
            });
            final SpringAnimation animator = mScrimAnimator;
            animator.addEndListener((animation, cancelled, value, velocity) -> {
                if (mScrimAnimator == animator) {
                    mScrimAnimator = null;
                }
            });
            mScrimAnimator.start();
        }

        private void animateCircleTo(float target, Runnable endAction) {
            mCircleEndAction = endAction;
            final MomentMotion.Spec spec = mShowing
                    ? MomentMotion.DEFAULT_SPATIAL : MomentMotion.EXIT_SPATIAL;
            if (mCircleSpring != null && mCircleSpring.isRunning()) {
                mCircleSpring.getSpring().setDampingRatio(spec.damping)
                        .setStiffness(spec.stiffness);
                mCircleSpring.animateToFinalPosition(target);
                return;
            }
            mCircleSpring = MomentMotion.spring(mCircleTranslationY, target, 0f, spec, 0.5f);
            mCircleSpring.addUpdateListener((animation, value, velocity) -> {
                mCircleTranslationY = value;
                invalidate();
            });
            final SpringAnimation spring = mCircleSpring;
            spring.addEndListener((animation, canceled, value, velocity) -> {
                if (mCircleSpring == spring) {
                    mCircleSpring = null;
                    final Runnable finish = mCircleEndAction;
                    mCircleEndAction = null;
                    if (!canceled && finish != null) {
                        finish.run();
                    }
                }
            });
            mCircleSpring.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            mGradientPaint.setAlpha(Math.round(255f * mScrimAlpha));
            canvas.drawRect(0, 0, getWidth(), getHeight(), mGradientPaint);

            final float targetScale = 1f
                    + (MAGNETIZED_TARGET_SCALE - 1f) * mMagnetProgress;
            final float radius = mTargetRadius * targetScale;
            final float centerX = getWidth() / 2f;
            final float centerY = mTargetCenterLocalY + mCircleTranslationY;
            canvas.drawCircle(centerX, centerY, radius, mCirclePaint);

            final float iconHalf = dpToPx(TARGET_ICON_SIZE_DP) * 7f / 24f * targetScale;
            canvas.drawLine(centerX - iconHalf, centerY - iconHalf,
                    centerX + iconHalf, centerY + iconHalf, mIconPaint);
            canvas.drawLine(centerX + iconHalf, centerY - iconHalf,
                    centerX - iconHalf, centerY + iconHalf, mIconPaint);
        }

        private int withAlpha(int color, float alpha) {
            return Color.argb(Math.round(255f * alpha), Color.red(color), Color.green(color),
                    Color.blue(color));
        }
    }
}
