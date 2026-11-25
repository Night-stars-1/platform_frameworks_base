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

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.content.res.Configuration;
import android.view.CompositionSamplingListener;
import android.view.Display;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

final class MomentHandleSurfaces {

    private static final float PRESSED_WIDTH_SCALE = 1.15f;

    private final Task mTask;
    private final float[] mBottomColor = new float[3];
    private final float[] mTopColor = new float[3];
    private final Rect mBottomBounds = new Rect();
    private final RectF mTmpBounds = new RectF();

    private SurfaceControl mBottomSurface;
    private SurfaceControl mBottomHalo;
    private SurfaceControl mTopSurface;
    private SpringAnimation mPressAnimator;
    private float mPressProgress;
    private boolean mDestroyed;
    private boolean mDarkHandle;
    private boolean mSamplingVisible;
    private boolean mSampleScheduled;
    private CompositionSamplingListener mSampler;
    private final Runnable mSample = this::updateSampling;

    MomentHandleSurfaces(Task task) {
        mTask = task;
        mDarkHandle = (task.mWmService.mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
    }

    synchronized void showOrUpdateBottom(SurfaceControl.Transaction t, Rect momentBounds) {
        if (mTask.mSurfaceControl == null || momentBounds.isEmpty()) {
            hideBottom(t);
            return;
        }
        final SurfaceControl parent = mTask.getParentSurfaceControl();
        if (parent == null) {
            hideBottom(t);
            return;
        }
        ensureBottomSurface(parent);
        if (mBottomSurface == null) {
            return;
        }

        mBottomBounds.set(momentBounds);
        mSamplingVisible = true;
        applyBottomGeometry(t);
        t.reparent(mBottomSurface, parent)
                .setColor(mBottomSurface, toColorArray(resolveBottomColor(), mBottomColor))
                .setAlpha(mBottomSurface, 1f)
                .setRelativeLayer(mBottomSurface, mTask.mSurfaceControl, 1)
                .show(mBottomSurface);
        if (!mSampleScheduled) {
            mSampleScheduled = true;
            mTask.mWmService.mAnimationHandler.postDelayed(mSample, 80);
        }
    }

    void showOrUpdateTop(Rect momentBounds, float shapeProgress) {
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            synchronized (this) {
                if (mDestroyed || mTask.mSurfaceControl == null || momentBounds.isEmpty()) {
                    hideTopLocked(t);
                } else {
                    final SurfaceControl parent = mTask.getParentSurfaceControl();
                    if (parent == null) {
                        hideTopLocked(t);
                    } else {
                        ensureTopSurface(parent);
                        if (mTopSurface != null) {
                            final float density = getDensity();
                            MomentGeometry.getTopHandleBounds(momentBounds, density,
                                    MomentGeometry.getDisplayedCornerRadius(
                                            mTask, momentBounds, density),
                                    shapeProgress, false /* includeCollapsedTouchTarget */,
                                    mTmpBounds);
                            applySurfaceBounds(t, mTopSurface, mTmpBounds);
                            t.reparent(mTopSurface, parent)
                                    .setColor(mTopSurface,
                                            toColorArray(Color.WHITE, mTopColor))
                                    .setAlpha(mTopSurface, 1f)
                                    .setRelativeLayer(mTopSurface, mTask.mSurfaceControl, 2)
                                    .show(mTopSurface);
                        }
                    }
                }
            }
            t.apply();
        }
    }

    void animateBottomPress(boolean isTouchDown) {
        mTask.mWmService.mAnimationHandler.post(() -> startPressAnimation(isTouchDown));
    }

    synchronized void hideBottom(SurfaceControl.Transaction t) {
        mBottomBounds.setEmpty();
        mSamplingVisible = false;
        mTask.mWmService.mAnimationHandler.removeCallbacks(mSample);
        mSampleScheduled = true;
        mTask.mWmService.mAnimationHandler.post(mSample);
        mPressProgress = 0f;
        cancelPressAnimationLocked();
        if (mBottomSurface != null) {
            t.hide(mBottomSurface);
        }
        if (mBottomHalo != null) t.hide(mBottomHalo);
    }

    void hideTop() {
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            synchronized (this) {
                hideTopLocked(t);
            }
            t.apply();
        }
    }

    synchronized void destroy(SurfaceControl.Transaction t) {
        mDestroyed = true;
        mSamplingVisible = false;
        mTask.mWmService.mAnimationHandler.removeCallbacks(mSample);
        mTask.mWmService.mAnimationHandler.post(() -> {
            synchronized (this) {
                if (mSampler != null) {
                    mSampler.destroy();
                    mSampler = null;
                }
            }
        });
        mBottomBounds.setEmpty();
        mPressProgress = 0f;
        cancelPressAnimationLocked();
        if (mBottomSurface != null) {
            t.remove(mBottomSurface);
            mBottomSurface.release();
            mBottomSurface = null;
        }
        if (mTopSurface != null) {
            t.remove(mTopSurface);
            mTopSurface.release();
            mTopSurface = null;
        }
        if (mBottomHalo != null) {
            t.remove(mBottomHalo);
            mBottomHalo.release();
            mBottomHalo = null;
        }
    }

    private void startPressAnimation(boolean isTouchDown) {
        synchronized (this) {
            if (mDestroyed || mBottomSurface == null || mBottomBounds.isEmpty()) {
                return;
            }
            final float targetProgress = isTouchDown ? 1f : 0f;
            if (mPressAnimator != null && mPressAnimator.isRunning()) {
                mPressAnimator.animateToFinalPosition(targetProgress);
                return;
            }
            if (mPressProgress == targetProgress) {
                return;
            }
            final SpringAnimation animator = MomentMotion.spring(mPressProgress, targetProgress,
                    0f, MomentMotion.FAST_SPATIAL, 0.001f);
            mPressAnimator = animator;
            animator.addUpdateListener((animation, value, velocity) ->
                    applyPressAnimationFrame(animator, value));
            animator.addEndListener((animation, cancelled, value, velocity) -> {
                synchronized (MomentHandleSurfaces.this) {
                    if (mPressAnimator == animation) {
                        mPressAnimator = null;
                    }
                }
            });
            animator.start();
        }
    }

    private synchronized void applyPressAnimationFrame(SpringAnimation animator, float value) {
        if (mPressAnimator != animator || mDestroyed || mBottomSurface == null
                || !mBottomSurface.isValid() || mBottomBounds.isEmpty()) {
            return;
        }
        mPressProgress = value;
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            applyBottomGeometry(t);
            t.apply();
        }
    }

    private void applyBottomGeometry(SurfaceControl.Transaction t) {
        final float progress = Math.max(0f, Math.min(1.2f, mPressProgress));
        final float widthScale = 1f + (PRESSED_WIDTH_SCALE - 1f) * progress;
        MomentGeometry.getBottomHandleBounds(mBottomBounds, getDensity(), widthScale, mTmpBounds);
        final float extraHeight = mTmpBounds.height() * .65f * progress / 2f;
        mTmpBounds.inset(0f, -extraHeight);
        applySurfaceBounds(t, mBottomSurface, mTmpBounds);
        if (mBottomHalo != null) {
            final RectF halo = new RectF(mTmpBounds);
            halo.inset(-getDensity(), -getDensity());
            applySurfaceBounds(t, mBottomHalo, halo);
            t.setColor(mBottomHalo, mDarkHandle ? new float[] {1f, 1f, 1f}
                    : new float[] {0f, 0f, 0f})
                    .setAlpha(mBottomHalo, .22f)
                    .setRelativeLayer(mBottomHalo, mBottomSurface, -1).show(mBottomHalo);
        }
    }

    private void applySurfaceBounds(SurfaceControl.Transaction t, SurfaceControl surface,
            RectF bounds) {
        final int width = Math.max(1, Math.round(bounds.width()));
        final int height = Math.max(1, Math.round(bounds.height()));
        t.setWindowCrop(surface, width, height)
                .setPosition(surface, bounds.centerX() - width / 2f,
                        bounds.centerY() - height / 2f)
                .setCornerRadius(surface, height / 2f);
    }

    private void hideTopLocked(SurfaceControl.Transaction t) {
        if (mTopSurface != null) {
            t.hide(mTopSurface);
        }
    }

    private void cancelPressAnimationLocked() {
        final SpringAnimation animator = mPressAnimator;
        mPressAnimator = null;
        if (animator != null) {
            mTask.mWmService.mAnimationHandler.post(animator::cancel);
        }
    }

    private void ensureBottomSurface(SurfaceControl parent) {
        if (mBottomSurface != null || mDestroyed) {
            return;
        }
        try {
            mBottomSurface = makeColorLayer(parent, "MomentBottomHandle#" + mTask.mTaskId);
            mBottomHalo = makeColorLayer(parent, "MomentHandleContrast#" + mTask.mTaskId);
        } catch (OutOfResourcesException e) {
            mBottomSurface = null;
        }
    }

    private void ensureTopSurface(SurfaceControl parent) {
        if (mTopSurface != null || mDestroyed) {
            return;
        }
        try {
            mTopSurface = makeColorLayer(parent, "MomentTopHandle#" + mTask.mTaskId);
        } catch (OutOfResourcesException e) {
            mTopSurface = null;
        }
    }

    private SurfaceControl makeColorLayer(SurfaceControl parent, String title)
            throws OutOfResourcesException {
        return mTask.mWmService.makeSurfaceBuilder()
                .setName(title)
                .setParent(parent)
                .setColorLayer()
                .setCallsite("MomentHandleSurfaces")
                .build();
    }

    private float[] toColorArray(int color, float[] outColor) {
        outColor[0] = Color.red(color) / 255f;
        outColor[1] = Color.green(color) / 255f;
        outColor[2] = Color.blue(color) / 255f;
        return outColor;
    }

    private int resolveBottomColor() {
        return mDarkHandle ? Color.rgb(24, 24, 24) : Color.rgb(248, 248, 248);
    }

    private synchronized void updateSampling() {
        mSampleScheduled = false;
        if (mDestroyed) return;
        if (!mSamplingVisible || mBottomBounds.isEmpty()) {
            if (mSampler != null) CompositionSamplingListener.unregister(mSampler);
            return;
        }
        // Composition sampling currently supports only the default display. Other displays
        // retain the theme fallback plus a small opposite-tone backing, without screenshots.
        if (mTask.getDisplayId() != Display.DEFAULT_DISPLAY || mBottomHalo == null
                || !mBottomHalo.isValid()) return;
        if (mSampler == null) {
            mSampler = new CompositionSamplingListener(
                    command -> mTask.mWmService.mAnimationHandler.post(command)) {
                @Override
                public void onSampleCollected(float luma) {
                    applySample(luma);
                }
            };
        }
        final RectF visual = new RectF();
        MomentGeometry.getBottomHandleBounds(mBottomBounds, getDensity(), 1f, visual);
        visual.inset(0, -4f * getDensity());
        final Rect sample = new Rect();
        visual.roundOut(sample);
        if (!sample.intersect(0, 0,
                mTask.mWmService.mContext.getResources().getDisplayMetrics().widthPixels,
                mTask.mWmService.mContext.getResources().getDisplayMetrics().heightPixels)) return;
        CompositionSamplingListener.register(mSampler, Display.DEFAULT_DISPLAY, mBottomHalo, sample);
    }

    private synchronized void applySample(float luma) {
        if (mDestroyed || !mSamplingVisible || !Float.isFinite(luma)) return;
        // Wide hysteresis prevents black/white chatter while moving over textured content.
        final boolean dark = mDarkHandle ? luma >= .35f : luma > .55f;
        if (dark == mDarkHandle) return;
        mDarkHandle = dark;
        if (mBottomSurface == null || !mBottomSurface.isValid()) return;
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            t.setColor(mBottomSurface, toColorArray(resolveBottomColor(), mBottomColor));
            applyBottomGeometry(t);
            t.apply();
        }
    }

    private float getDensity() {
        final DisplayContent displayContent = mTask.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }
}
