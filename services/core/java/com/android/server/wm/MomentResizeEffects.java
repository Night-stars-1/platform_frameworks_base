/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Slog;
import android.view.CrossWindowBlurListeners;
import android.view.SurfaceControl;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.graphics.ColorUtils;

/** Temporary resize material, independent of task transforms and application configuration. */
final class MomentResizeEffects {
    private final Task mTask;
    private SurfaceControl mFrame;
    private SurfaceControl mContent;
    private HardwareBuffer mTint;
    private SpringAnimation mAnimation;
    private boolean mDestroyed;
    private boolean mSuppressed;
    private int mGeneration;
    private boolean mBlurEnabled;
    private float mProgress;
    private float mLeft, mTop, mWidth, mHeight, mRadius, mDensity;
    private final Rect mCrop = new Rect();
    private int mTintColor;

    MomentResizeEffects(Task task) { mTask = task; }

    synchronized void setActive(boolean active) {
        if (active) mSuppressed = false;
        final int generation = mGeneration;
        mTask.mWmService.mAnimationHandler.post(() -> {
            final boolean blur = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
            synchronized (this) {
                if (mDestroyed || generation != mGeneration || mSuppressed) return;
                mBlurEnabled = blur;
                if (mAnimation != null && mAnimation.isRunning()) {
                    mAnimation.animateToFinalPosition(active ? 1f : 0f);
                    return;
                }
                final SpringAnimation animation = MomentMotion.spring(mProgress, active ? 1f : 0f,
                        0f, MomentMotion.FAST_EFFECTS, .001f);
                mAnimation = animation;
                animation.addUpdateListener((a, value, velocity) -> {
                    synchronized (this) {
                        if (mDestroyed || mSuppressed || generation != mGeneration) return;
                        mProgress = MomentMotion.clamp(value);
                        try (SurfaceControl.Transaction t =
                                mTask.mWmService.mTransactionFactory.get()) {
                            render(t);
                            t.apply();
                        }
                    }
                });
                animation.addEndListener((a, cancelled, value, velocity) -> {
                    synchronized (this) {
                        if (mAnimation == a) mAnimation = null;
                    }
                });
                animation.start();
            }
        });
    }

    synchronized void apply(SurfaceControl.Transaction t, Rect crop, float left, float top,
            float width, float height, float radius, float density) {
        mCrop.set(crop);
        mLeft = left; mTop = top; mWidth = width; mHeight = height;
        mRadius = radius; mDensity = density;
        render(t);
    }

    private void render(SurfaceControl.Transaction t) {
        if (mDestroyed || mSuppressed || mProgress <= 0f || mCrop.isEmpty()
                || !mTask.isVisibleRequested() || mTask.mSurfaceControl == null
                || !mTask.mSurfaceControl.isValid() || mTask.getParentSurfaceControl() == null) {
            hide(t);
            return;
        }
        try {
            if (mFrame == null) {
                mFrame = mTask.mWmService.makeSurfaceBuilder()
                        .setName("MomentResizeFrame#" + mTask.mTaskId).setColorLayer()
                        .setParent(mTask.getParentSurfaceControl())
                        .setCallsite("MomentResizeEffects").build();
                mContent = mTask.mWmService.makeSurfaceBuilder()
                        .setName("MomentResizeContent#" + mTask.mTaskId).setBLASTLayer()
                        .setParent(mTask.mSurfaceControl)
                        .setCallsite("MomentResizeEffects").build();
            }
            final int fg = mTask.mWmService.mContext.getColor(
                    com.android.internal.R.color.shade_panel_fg);
            final int bg = mTask.mWmService.mContext.getColor(
                    com.android.internal.R.color.shade_panel_bg);
            final int shade = ColorUtils.compositeColors(fg, bg);
            final int tint = ColorUtils.setAlphaComponent(shade, mBlurEnabled ? 18 : 140);
            if (mTint == null || mTintColor != tint) createTint(tint);
            final float border = 8f * mDensity;
            final int blurRadius = mBlurEnabled ? Math.round(40f * mDensity * mProgress) : 0;
            // The frame is behind the task, so its center never paints over app content.
            t.reparent(mFrame, mTask.getParentSurfaceControl())
                    .setRelativeLayer(mFrame, mTask.mSurfaceControl, -1)
                    .setPosition(mFrame, mLeft - border, mTop - border)
                    .setWindowCrop(mFrame, Math.round(mWidth + border * 2),
                            Math.round(mHeight + border * 2))
                    .setCornerRadius(mFrame, mRadius + border)
                    .setColor(mFrame, new float[] {Color.red(shade) / 255f,
                            Color.green(shade) / 255f, Color.blue(shade) / 255f})
                    .setAlpha(mFrame, mProgress * Math.max(.45f, Color.alpha(shade) / 255f))
                    .setBackgroundBlurRadius(mFrame, blurRadius).show(mFrame);
            // Transparent tint buffer + full layer alpha keeps blur strength independent of
            // tint opacity. This blurs live app details, never a screenshot of the application.
            t.reparent(mContent, mTask.mSurfaceControl).setLayer(mContent, Integer.MAX_VALUE - 1)
                    .setBuffer(mContent, mTint).setWindowCrop(mContent, 1, 1)
                    .setPosition(mContent, mCrop.left, mCrop.top)
                    .setScale(mContent, mCrop.width(), mCrop.height())
                    .setAlpha(mContent, mProgress).setBackgroundBlurRadius(mContent, blurRadius)
                    .show(mContent);
        } catch (RuntimeException e) {
            Slog.w("Moment", "Unable to render resize material", e);
            hide(t);
        }
    }

    private void createTint(int color) {
        final GraphicBuffer buffer = GraphicBuffer.create(1, 1, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_SW_WRITE_OFTEN | GraphicBuffer.USAGE_HW_TEXTURE
                        | GraphicBuffer.USAGE_HW_COMPOSER);
        if (buffer == null) throw new IllegalStateException("No resize tint buffer");
        try {
            final Canvas canvas = buffer.lockCanvas();
            canvas.drawColor(color, android.graphics.PorterDuff.Mode.SRC);
            buffer.unlockCanvasAndPost(canvas);
            final HardwareBuffer tint = HardwareBuffer.createFromGraphicBuffer(buffer);
            if (mTint != null) mTint.close();
            mTint = tint;
            mTintColor = color;
        } finally { buffer.destroy(); }
    }

    synchronized void hide(SurfaceControl.Transaction t) {
        if (mFrame != null && mFrame.isValid()) t.hide(mFrame).setBackgroundBlurRadius(mFrame, 0);
        if (mContent != null && mContent.isValid()) t.hide(mContent).setBackgroundBlurRadius(mContent, 0);
    }

    synchronized void clear(SurfaceControl.Transaction t) {
        mSuppressed = true;
        mGeneration++;
        mProgress = 0f;
        hide(t);
        final SpringAnimation animation = mAnimation;
        mAnimation = null;
        if (animation != null) mTask.mWmService.mAnimationHandler.post(animation::cancel);
    }

    synchronized void destroy(SurfaceControl.Transaction t) {
        mDestroyed = true;
        mTask.mWmService.mAnimationHandler.post(() -> {
            synchronized (this) { if (mAnimation != null) mAnimation.cancel(); }
        });
        if (mFrame != null) { t.remove(mFrame); mFrame.release(); mFrame = null; }
        if (mContent != null) { t.remove(mContent); mContent.release(); mContent = null; }
        if (mTint != null) { mTint.close(); mTint = null; }
    }
}
