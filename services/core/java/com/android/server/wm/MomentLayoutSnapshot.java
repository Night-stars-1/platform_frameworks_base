/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.os.BackgroundThread;

/** Task-local launch-style veil. Never captures app content or owns the task transform. */
final class MomentLayoutSnapshot {
    private static final String TAG = "Moment";

    private final Task mTask;
    private final Rect mExpectedBounds = new Rect();
    private SurfaceControl mBackground;
    private SurfaceControl mIcon;
    private int mIconSize;
    private final Rect mVisibleBounds = new Rect();
    private float mParentScaleX = 1f;
    private float mParentScaleY = 1f;
    private boolean mDestroyed;
    private boolean mRevealing;
    private SpringAnimation mFade;
    private int mExpectedWindowingMode;
    private int mLayoutGeneration;
    private boolean mArmed;
    private boolean mDrawCommitted;
    private boolean mAnimationFinished;

    MomentLayoutSnapshot(Task task) {
        mTask = task;
    }

    void capture(Rect crop) {
        if (mTask.mSurfaceControl == null || !mTask.mSurfaceControl.isValid()) {
            return;
        }
        try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
            final Context context = mTask.mWmService.mContext;
            final boolean dark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            final float tone = dark ? 0f : 1f;
            final float density = mTask.getDisplayContent() != null
                    ? mTask.getDisplayContent().getDisplayMetrics().density
                    : context.getResources().getDisplayMetrics().density;
            mIconSize = Math.max(1, Math.round(48f * density));
            mBackground = mTask.mWmService.makeSurfaceBuilder()
                    .setParent(mTask.mSurfaceControl)
                    .setName("MomentLayoutCover#" + mTask.mTaskId)
                    .setColorLayer()
                    .setCallsite("MomentLayoutSnapshot.capture")
                    .build();
            t.setColor(mBackground, new float[] {tone, tone, tone})
                    .setLayer(mBackground, Integer.MAX_VALUE).show(mBackground);
            applyBounds(t, crop);
            t.apply();
            final ActivityRecord activity = mTask.topRunningActivity();
            final ActivityInfo info = activity != null ? new ActivityInfo(activity.info) : null;
            final int userId = mTask.mUserId;
            // Package resources can require disk/Binder I/O. Never load them under the WM lock
            // or postpone the opaque cover while waiting for the icon.
            BackgroundThread.getHandler().post(() -> loadIcon(context, info, userId));
        } catch (Surface.OutOfResourcesException | IllegalArgumentException e) {
            Slog.w(TAG, "Unable to cover layout change for task " + mTask.mTaskId, e);
            try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
                destroy(t);
                t.apply();
            }
        }
    }

    private void loadIcon(Context context, ActivityInfo info, int userId) {
        try {
            final Context userContext = context.createContextAsUser(UserHandle.of(userId), 0);
            Drawable icon;
            try {
                icon = info != null ? info.loadIcon(userContext.getPackageManager()) : null;
            } catch (RuntimeException unavailable) {
                icon = null;
            }
            if (icon == null) {
                icon = userContext.getPackageManager().getDefaultActivityIcon();
            }
            synchronized (this) {
                if (mDestroyed || !isValid()) {
                    return;
                }
                mIcon = mTask.mWmService.makeSurfaceBuilder()
                        .setParent(mBackground).setName("MomentLayoutIcon#" + mTask.mTaskId)
                        .setBufferSize(mIconSize, mIconSize).setFormat(PixelFormat.RGBA_8888)
                        .setOpaque(false).setCallsite("MomentLayoutSnapshot.loadIcon").build();
                final Surface surface = new Surface(mIcon);
                try {
                    final Canvas canvas = surface.lockCanvas(null);
                    try {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        icon.setBounds(0, 0, mIconSize, mIconSize);
                        icon.draw(canvas);
                    } finally {
                        surface.unlockCanvasAndPost(canvas);
                    }
                } finally {
                    surface.release();
                }
                try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
                    t.setLayer(mIcon, 1).show(mIcon);
                    applyBounds(t, mVisibleBounds, mParentScaleX, mParentScaleY);
                    t.apply();
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to draw layout veil icon for task " + mTask.mTaskId, e);
        }
    }

    synchronized boolean isValid() {
        return !mDestroyed && mBackground != null && mBackground.isValid();
    }

    synchronized void applyBounds(SurfaceControl.Transaction t, Rect bounds) {
        applyBounds(t, bounds, 1f, 1f);
    }

    synchronized void applyBounds(SurfaceControl.Transaction t, Rect bounds,
            float parentScaleX, float parentScaleY) {
        if (!isValid() || bounds.isEmpty()) {
            return;
        }
        mVisibleBounds.set(bounds);
        mParentScaleX = parentScaleX;
        mParentScaleY = parentScaleY;
        t.setPosition(mBackground, bounds.left, bounds.top)
                .setWindowCrop(mBackground, bounds.width(), bounds.height());
        if (mIcon != null && mIcon.isValid()) {
            final float sx = Math.max(0.001f, Math.abs(parentScaleX));
            final float sy = Math.max(0.001f, Math.abs(parentScaleY));
            final float size = Math.min(mIconSize,
                    Math.min(bounds.width() * sx, bounds.height() * sy));
            t.setScale(mIcon, size / mIconSize / sx, size / mIconSize / sy)
                    .setPosition(mIcon, (bounds.width() - size / sx) / 2f,
                            (bounds.height() - size / sy) / 2f);
        }
    }

    synchronized void waitForLayout(boolean animationFinished) {
        mLayoutGeneration++;
        mRevealing = false;
        if (isValid()) {
            try (SurfaceControl.Transaction t = mTask.mWmService.mTransactionFactory.get()) {
                t.setAlpha(mBackground, 1f).apply();
            }
        }
        mExpectedBounds.set(mTask.getBounds());
        mExpectedWindowingMode = mTask.getWindowingMode();
        mDrawCommitted = false;
        mArmed = true;
        mAnimationFinished = animationFinished;
        final WindowState window = mTask.getTopVisibleAppMainWindow();
        if (window != null) {
            // Request a sequenced redraw even for apps which handle configuration changes.
            // Completion is observed in finishDrawing, including replacement windows on relaunch.
            window.applyWithNextDraw(t -> { });
        }
    }

    boolean acceptsDraw(WindowState window) {
        return mArmed && !mDrawCommitted && isValid()
                && mExpectedWindowingMode == mTask.getWindowingMode()
                && mExpectedBounds.equals(mTask.getBounds())
                && window.mActivityRecord != null
                && window.mActivityRecord == mTask.topRunningActivity()
                && window == window.mActivityRecord.findMainWindow(false);
    }

    int getLayoutGeneration() {
        return mLayoutGeneration;
    }

    void onDrawCommitted(int generation) {
        if (mLayoutGeneration == generation) {
            mDrawCommitted = true;
        }
    }

    void onAnimationFinished() {
        mAnimationFinished = true;
    }

    boolean isReadyToReveal() {
        return mDrawCommitted && mAnimationFinished;
    }

    synchronized void reveal(Runnable onFinished) {
        if (mDestroyed || mRevealing) {
            return;
        }
        mRevealing = true;
        final int generation = mLayoutGeneration;
        mTask.mWmService.mAnimationHandler.post(() -> {
            synchronized (this) {
                if (mDestroyed || !mRevealing || generation != mLayoutGeneration) {
                    return;
                }
                if (mFade != null) {
                    mFade.cancel();
                }
                final SpringAnimation fade = MomentMotion.spring(1f, 0f, 0f,
                        MomentMotion.FAST_EFFECTS, 0.001f);
                mFade = fade;
                fade.addUpdateListener((animation, value, velocity) -> {
                    synchronized (this) {
                        if (isValid() && generation == mLayoutGeneration) {
                            try (SurfaceControl.Transaction t =
                                    mTask.mWmService.mTransactionFactory.get()) {
                                t.setAlpha(mBackground, MomentMotion.clamp(value)).apply();
                            }
                        }
                    }
                });
                fade.addEndListener((animation, cancelled, value, velocity) -> {
                    synchronized (MomentLayoutSnapshot.this) {
                        if (mFade == animation) {
                            mFade = null;
                        }
                        if (cancelled || mDestroyed || generation != mLayoutGeneration) {
                            return;
                        }
                    }
                    mTask.mWmService.mH.post(() -> {
                        synchronized (mTask.mWmService.mGlobalLock) {
                            if (generation == getLayoutGeneration()) {
                                onFinished.run();
                            }
                        }
                    });
                });
                fade.start();
            }
        });
    }

    synchronized void destroy(SurfaceControl.Transaction t) {
        mArmed = false;
        mDestroyed = true;
        mTask.mWmService.mAnimationHandler.post(() -> {
            synchronized (this) {
                if (mFade != null) {
                    mFade.cancel();
                    mFade = null;
                }
            }
        });
        if (mIcon != null) {
            if (mIcon.isValid()) {
                t.remove(mIcon);
            }
            mIcon.release();
            mIcon = null;
        }
        if (mBackground != null) {
            if (mBackground.isValid()) {
                t.remove(mBackground);
            }
            mBackground.release();
            mBackground = null;
        }
    }
}
