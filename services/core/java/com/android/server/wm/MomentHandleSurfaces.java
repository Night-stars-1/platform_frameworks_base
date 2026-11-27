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

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.CompositionSamplingListener;
import android.view.Display;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.graphics.ColorUtils;

final class MomentHandleSurfaces {

    private static final float PRESSED_WIDTH_SCALE = 1.15f;
    private static final float HALO_ALPHA = .22f;
    private static final int SAMPLE_MARGIN_DP = 4;
    private static final long SAMPLE_DELAY_MS = 80;

    private final Task mTask;
    private final float[] mBottomColor = new float[3];
    private final float[] mTopColor = new float[3];
    private final float[] mHaloColor = new float[3];
    private final Rect mBottomBounds = new Rect();
    private final Rect mTopBounds = new Rect();
    private final RectF mTmpBounds = new RectF();
    private final HandleSampler mBottomSampler;
    private final HandleSampler mTopSampler;

    private SurfaceControl mBottomSurface;
    private SurfaceControl mBottomHalo;
    private SurfaceControl mTopSurface;
    private SurfaceControl mTopHalo;
    private SpringAnimation mPressAnimator;
    private float mPressProgress;
    private float mBottomBarFraction;
    private float mTopBarFraction;
    private float mTopShapeProgress;
    private boolean mDestroyed;

    MomentHandleSurfaces(Task task) {
        mTask = task;
        final boolean dark = (task.mWmService.mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        mBottomSampler = new HandleSampler(dark, this::applyBottomSampleLocked);
        mTopSampler = new HandleSampler(dark, this::applyTopSampleLocked);
    }

    synchronized void showOrUpdateBottom(SurfaceControl.Transaction t, Rect momentBounds,
            float bottomBarFraction) {
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
        mBottomBarFraction = bottomBarFraction;
        applyBottomGeometry(t);
        t.reparent(mBottomSurface, parent)
                .setColor(mBottomSurface,
                        toColorArray(resolveHandleColor(mBottomSampler.mDark), mBottomColor))
                .setAlpha(mBottomSurface, 1f)
                .setRelativeLayer(mBottomSurface, mTask.mSurfaceControl, 1)
                .show(mBottomSurface);
        MomentGeometry.getBottomHandleBounds(mBottomBounds, mBottomBarFraction, getDensity(), 1f,
                mTmpBounds);
        mBottomSampler.setAreaLocked(mTmpBounds, mBottomBounds, mBottomHalo);
    }

    void showOrUpdateTop(Rect momentBounds, float topBarFraction, float shapeProgress) {
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
                            mTopBounds.set(momentBounds);
                            mTopBarFraction = topBarFraction;
                            mTopShapeProgress = shapeProgress;
                            t.reparent(mTopSurface, parent)
                                    .setAlpha(mTopSurface, 1f)
                                    .setRelativeLayer(mTopSurface, mTask.mSurfaceControl, 2)
                                    .show(mTopSurface);
                            if (mTopHalo != null) {
                                t.reparent(mTopHalo, parent);
                            }
                            applyTopGeometry(t);
                            // The collapsed grip sits over the app's status-bar band; sample
                            // there, never at the expanded menu above the window.
                            MomentGeometry.getTopHandleBounds(mTopBounds, mTopBarFraction,
                                    getDensity(), 0f, false /* includeCollapsedTouchTarget */,
                                    mTmpBounds);
                            mTopSampler.setAreaLocked(mTmpBounds, mTopBounds, mTopHalo);
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
        mBottomSampler.clearLocked();
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
        mBottomSampler.destroyLocked();
        mTopSampler.destroyLocked();
        mBottomBounds.setEmpty();
        mTopBounds.setEmpty();
        mPressProgress = 0f;
        cancelPressAnimationLocked();
        mBottomSurface = removeSurface(t, mBottomSurface);
        mBottomHalo = removeSurface(t, mBottomHalo);
        mTopSurface = removeSurface(t, mTopSurface);
        mTopHalo = removeSurface(t, mTopHalo);
    }

    private static SurfaceControl removeSurface(SurfaceControl.Transaction t,
            SurfaceControl surface) {
        if (surface != null) {
            t.remove(surface);
            surface.release();
        }
        return null;
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
        MomentGeometry.getBottomHandleBounds(mBottomBounds, mBottomBarFraction, getDensity(),
                widthScale, mTmpBounds);
        final float extraHeight = mTmpBounds.height() * .65f * progress / 2f;
        mTmpBounds.inset(0f, -extraHeight);
        applySurfaceBounds(t, mBottomSurface, mTmpBounds);
        applyHalo(t, mBottomHalo, mBottomSurface, mTmpBounds, mBottomSampler.mDark, HALO_ALPHA);
    }

    private void applyTopGeometry(SurfaceControl.Transaction t) {
        MomentGeometry.getTopHandleBounds(mTopBounds, mTopBarFraction, getDensity(),
                mTopShapeProgress, false /* includeCollapsedTouchTarget */, mTmpBounds);
        applySurfaceBounds(t, mTopSurface, mTmpBounds);
        // The grip takes the sampled contrast tone; the expanded menu keeps its light surface
        // so the dark menu icons stay legible.
        final float menuProgress = MomentMotion.clamp(mTopShapeProgress);
        t.setColor(mTopSurface, toColorArray(ColorUtils.blendARGB(
                resolveHandleColor(mTopSampler.mDark), Color.WHITE, menuProgress), mTopColor));
        applyHalo(t, mTopHalo, mTopSurface, mTmpBounds, mTopSampler.mDark,
                HALO_ALPHA * (1f - menuProgress));
    }

    private void applyHalo(SurfaceControl.Transaction t, SurfaceControl halo,
            SurfaceControl handle, RectF handleBounds, boolean darkHandle, float alpha) {
        if (halo == null) {
            return;
        }
        final RectF haloBounds = new RectF(handleBounds);
        haloBounds.inset(-getDensity(), -getDensity());
        applySurfaceBounds(t, halo, haloBounds);
        t.setColor(halo, toColorArray(darkHandle ? Color.WHITE : Color.BLACK, mHaloColor))
                .setAlpha(halo, alpha)
                .setRelativeLayer(halo, handle, -1).show(halo);
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
        mTopBounds.setEmpty();
        mTopSampler.clearLocked();
        if (mTopSurface != null) {
            t.hide(mTopSurface);
        }
        if (mTopHalo != null) {
            t.hide(mTopHalo);
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
            mTopHalo = makeColorLayer(parent, "MomentTopHandleContrast#" + mTask.mTaskId);
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

    private static int resolveHandleColor(boolean darkHandle) {
        return darkHandle ? Color.rgb(24, 24, 24) : Color.rgb(248, 248, 248);
    }

    private void applyBottomSampleLocked() {
        if (mBottomSurface == null || !mBottomSurface.isValid() || mBottomBounds.isEmpty()) {
            return;
        }
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            t.setColor(mBottomSurface,
                    toColorArray(resolveHandleColor(mBottomSampler.mDark), mBottomColor));
            applyBottomGeometry(t);
            t.apply();
        }
    }

    private void applyTopSampleLocked() {
        if (mTopSurface == null || !mTopSurface.isValid() || mTopBounds.isEmpty()) {
            return;
        }
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            applyTopGeometry(t);
            t.apply();
        }
    }

    private float getDensity() {
        final DisplayContent displayContent = mTask.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }

    /**
     * Picks a black or white handle from the luma of the app's bar band behind it. Each handle
     * samples its own band because the status-bar and navigation-bar backgrounds can differ.
     * All state is guarded by the enclosing {@link MomentHandleSurfaces} lock.
     */
    private final class HandleSampler {
        private final Runnable mUpdate = this::update;
        private final Runnable mOnChanged;
        private final Rect mArea = new Rect();
        private final Rect mRegisteredArea = new Rect();
        private SurfaceControl mStopLayer;
        private CompositionSamplingListener mListener;
        private boolean mScheduled;
        boolean mDark;

        HandleSampler(boolean dark, Runnable onChanged) {
            mDark = dark;
            mOnChanged = onChanged;
        }

        void setAreaLocked(RectF handleBounds, Rect momentBounds, SurfaceControl stopLayer) {
            final RectF visual = new RectF(handleBounds);
            visual.inset(0f, -SAMPLE_MARGIN_DP * getDensity());
            visual.roundOut(mArea);
            // Only the app's own bar band decides the tone, never what lies outside the window.
            if (!mArea.intersect(momentBounds)) {
                mArea.setEmpty();
            }
            mStopLayer = stopLayer;
            if (!mScheduled) {
                mScheduled = true;
                mTask.mWmService.mAnimationHandler.postDelayed(mUpdate, SAMPLE_DELAY_MS);
            }
        }

        void clearLocked() {
            mArea.setEmpty();
            mStopLayer = null;
            mTask.mWmService.mAnimationHandler.removeCallbacks(mUpdate);
            mScheduled = true;
            mTask.mWmService.mAnimationHandler.post(mUpdate);
        }

        void destroyLocked() {
            mArea.setEmpty();
            mStopLayer = null;
            mTask.mWmService.mAnimationHandler.removeCallbacks(mUpdate);
            mTask.mWmService.mAnimationHandler.post(() -> {
                synchronized (MomentHandleSurfaces.this) {
                    if (mListener != null) {
                        mListener.destroy();
                        mListener = null;
                    }
                    mRegisteredArea.setEmpty();
                }
            });
        }

        private void update() {
            synchronized (MomentHandleSurfaces.this) {
                mScheduled = false;
                if (mDestroyed) return;
                // Composition sampling currently supports only the default display. Other
                // displays retain the theme fallback plus a small opposite-tone backing.
                final Rect sample = new Rect(mArea);
                final boolean canSample = !sample.isEmpty()
                        && mTask.getDisplayId() == Display.DEFAULT_DISPLAY
                        && mStopLayer != null && mStopLayer.isValid()
                        && sample.intersect(0, 0,
                                mTask.mWmService.mContext.getResources().getDisplayMetrics()
                                        .widthPixels,
                                mTask.mWmService.mContext.getResources().getDisplayMetrics()
                                        .heightPixels);
                if (!canSample) {
                    if (mListener != null && !mRegisteredArea.isEmpty()) {
                        CompositionSamplingListener.unregister(mListener);
                        mRegisteredArea.setEmpty();
                    }
                    return;
                }
                if (sample.equals(mRegisteredArea)) return;
                if (mListener == null) {
                    mListener = new CompositionSamplingListener(
                            command -> mTask.mWmService.mAnimationHandler.post(command)) {
                        @Override
                        public void onSampleCollected(float luma) {
                            applySample(luma);
                        }
                    };
                }
                // SurfaceFlinger keys listeners by binder and keeps the first area it saw, so
                // a moved band must be unregistered before registering the new area.
                CompositionSamplingListener.unregister(mListener);
                CompositionSamplingListener.register(mListener, Display.DEFAULT_DISPLAY,
                        mStopLayer, sample);
                mRegisteredArea.set(sample);
            }
        }

        private void applySample(float luma) {
            synchronized (MomentHandleSurfaces.this) {
                if (mDestroyed || mArea.isEmpty() || !Float.isFinite(luma)) return;
                // Wide hysteresis prevents black/white chatter while moving over textured
                // content.
                final boolean dark = mDark ? luma >= .35f : luma > .55f;
                if (dark == mDark) return;
                mDark = dark;
                mOnChanged.run();
            }
        }
    }
}
