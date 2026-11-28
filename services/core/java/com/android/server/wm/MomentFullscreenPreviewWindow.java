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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.policy.ScreenDecorationsUtils;

/**
 * MIUI-style bottom-bar swipe down on a Moment window: a fullscreen outline that shows where the
 * window lands if the swipe is released. The window sits directly below the Moment task, so its
 * background blur softens whatever is behind the floating window and leaves the window itself
 * sharp. Without cross-window blur the outline is a translucent fill instead.
 */
final class MomentFullscreenPreviewWindow {

    private static final int OUTLINE_INSET_DP = 8;
    private static final int MAX_BLUR_RADIUS_DP = 24;

    private final Context mContext;
    private final Task mTask;
    private final WindowManager mWindowManager;
    private final PreviewView mView;
    private final LayoutParams mLayoutParams = new LayoutParams();
    private boolean mAdded;

    MomentFullscreenPreviewWindow(Context context, Task task) {
        mContext = context.createWindowContext(TYPE_NAVIGATION_BAR_PANEL, null);
        mTask = task;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mView = new PreviewView(mContext);
    }

    /**
     * Shows the outline for a swipe at {@code progress} (0 = untouched, 1 = release expands the
     * window). {@code armed} is true once releasing would expand.
     */
    void setProgress(float progress, boolean armed) {
        progress = MomentMotion.clamp(progress);
        if (progress <= 0f) {
            if (mAdded) {
                mView.setProgress(0f, false);
            }
            return;
        }
        if (!mAdded) {
            updateLayoutParams();
            mWindowManager.addView(mView, mLayoutParams);
            mAdded = true;
        }
        mView.setProgress(progress, armed);
    }

    void destroy() {
        if (mAdded) {
            mView.release();
            mWindowManager.removeViewImmediate(mView);
            mAdded = false;
        }
    }

    private void updateLayoutParams() {
        mLayoutParams.type = TYPE_NAVIGATION_BAR_PANEL;
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | LayoutParams.FLAG_NOT_TOUCHABLE
                | LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mLayoutParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY
                | SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLayoutParams.setFitInsetsTypes(0);
        mLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        mLayoutParams.width = LayoutParams.MATCH_PARENT;
        mLayoutParams.height = LayoutParams.MATCH_PARENT;
        mLayoutParams.windowAnimations = 0;
        mLayoutParams.setTitle("MomentFullscreenPreview#" + mTask.mTaskId);
    }

    private boolean placeBelowTask() {
        final ViewRootImpl viewRoot = mView.getViewRootImpl();
        final SurfaceControl surface = viewRoot != null ? viewRoot.getSurfaceControl() : null;
        final SurfaceControl taskSurface = mTask.mSurfaceControl;
        if (surface == null || !surface.isValid() || taskSurface == null
                || !taskSurface.isValid()) {
            return false;
        }
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            t.setRelativeLayer(surface, taskSurface, -1).apply();
        }
        return true;
    }

    private int dpToPx(int dp) {
        return (int) (dp * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class PreviewView extends View {

        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final float mCornerRadius;
        private final float mStrokeWidth;
        private final float mArmedStrokeWidth;
        private final boolean mBlurSupported;
        private BackgroundBlurDrawable mBlur;
        private float mProgress;
        private boolean mArmed;
        private boolean mPlacedBelowTask;

        PreviewView(Context context) {
            super(context);
            final float density = context.getResources().getDisplayMetrics().density;
            mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
            mStrokeWidth = 1.5f * density;
            mArmedStrokeWidth = 3f * density;
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setColor(Color.WHITE);
            mFillPaint.setColor(Color.WHITE);
            mBlurSupported = mWindowManager.isCrossWindowBlurEnabled();
        }

        void setProgress(float progress, boolean armed) {
            if (mProgress == progress && mArmed == armed) {
                return;
            }
            mProgress = progress;
            mArmed = armed;
            if (mBlur == null && mBlurSupported && getViewRootImpl() != null) {
                mBlur = getViewRootImpl().createBackgroundBlurDrawable();
                mBlur.setCornerRadius(mCornerRadius);
            }
            if (mBlur != null) {
                mBlur.setBlurRadius(Math.round(dpToPx(MAX_BLUR_RADIUS_DP) * progress));
                mBlur.setColor(Color.argb(Math.round(0x40 * progress), 0, 0, 0));
                mBlur.setVisible(progress > 0f, false);
            }
            invalidate();
        }

        void release() {
            if (mBlur != null) {
                mBlur.setVisible(false, false);
                mBlur = null;
            }
            mProgress = 0f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!mPlacedBelowTask) {
                // The window is created above apps; it must sit under the Moment task before
                // its blur shows, or the blur would cover the floating window itself.
                mPlacedBelowTask = placeBelowTask();
            }
            if (mProgress <= 0f) {
                return;
            }
            final float inset = dpToPx(OUTLINE_INSET_DP);
            mRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            final float radius = Math.max(0f, mCornerRadius - inset);
            if (mBlur != null) {
                mBlur.setBounds(Math.round(mRect.left), Math.round(mRect.top),
                        Math.round(mRect.right), Math.round(mRect.bottom));
                mBlur.setCornerRadius(radius);
                mBlur.draw(canvas);
            } else {
                mFillPaint.setAlpha(Math.round(0x59 * mProgress));
                canvas.drawRoundRect(mRect, radius, radius, mFillPaint);
            }
            final float stroke = mArmed ? mArmedStrokeWidth : mStrokeWidth;
            mStrokePaint.setStrokeWidth(stroke);
            mStrokePaint.setAlpha(Math.round((mArmed ? 0xE6 : 0x80) * mProgress));
            final float half = stroke / 2f;
            mRect.inset(half, half);
            canvas.drawRoundRect(mRect, radius, radius, mStrokePaint);
        }
    }
}
