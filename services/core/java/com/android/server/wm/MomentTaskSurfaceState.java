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

import static android.view.WindowInsets.Type.systemBars;

import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;

final class MomentTaskSurfaceState {

    private final Task mTask;
    private final Rect mBaseBounds = new Rect();
    private final Rect mContentCrop = new Rect();
    private final Rect mAnimatedCrop = new Rect();
    private final Rect mFullscreenBounds = new Rect();
    private final Rect mSurfaceBounds = new Rect();
    private final Rect mDecorationBounds = new Rect();
    private final Point mCenter = new Point();
    private final MomentHandleSurfaces mHandleSurfaces;
    private final MomentMorphLayer mMorphLayer;
    private final MomentWindowEffects mWindowEffects;
    private final MomentResizeEffects mResizeEffects;
    private boolean mResizing;
    private final DisplayFrames mPortraitDisplayFrames = new DisplayFrames();
    private final InsetsState mLastRawInsetsState = new InsetsState();

    private float mScale;
    private boolean mHasUserCenter;
    private boolean mApplied;
    private boolean mPreserveTaskBounds;
    private boolean mSuppressConversionRelaunch;
    private boolean mMomentCompact;
    private float mCompactRestoreScale;
    private float mCompactScale;
    private int mCompactStashedSide;
    private boolean mTransformAnimating;
    private boolean mOpening;
    private boolean mClosing;
    private float mAnimatedScaleX;
    private float mAnimatedScaleY;
    private float mAnimatedCenterX;
    private float mAnimatedCenterY;
    private float mAnimatedAlpha = 1f;
    private float mAnimatedCropProgress = 1f;
    private float mAnimatedDisplayedCornerRadius;
    private float mCornerRadius;
    private int mAnimationGeneration;
    private boolean mLandscapeLayout;
    private float mScaleBeforeLandscape;
    private boolean mFixedAspectRatio;
    private volatile MomentLayoutSnapshot mLayoutSnapshot;
    private final Rect mFixedLayoutDisplayBounds = new Rect();
    private final Rect mFixedLayoutBounds = new Rect();
    private final InsetsState mFixedLayoutInsets = new InsetsState();
    private int mFixedLayoutRotation = -1;

    MomentTaskSurfaceState(Task task, float scale) {
        mTask = task;
        mScale = scale;
        mHandleSurfaces = new MomentHandleSurfaces(task);
        mMorphLayer = new MomentMorphLayer(task);
        mWindowEffects = new MomentWindowEffects(task);
        mResizeEffects = new MomentResizeEffects(task);
    }

    void setScale(float scale) {
        mScale = scale;
    }

    void updateLandscapeLayout(boolean landscape, float defaultScale, float maxScale) {
        if (landscape && !mLandscapeLayout) {
            mScaleBeforeLandscape = mMomentCompact ? mCompactRestoreScale : mScale;
            mScale = Math.min(mScale, defaultScale);
            mLandscapeLayout = true;
        } else if (!landscape && mLandscapeLayout) {
            if (!mMomentCompact) {
                mScale = mScaleBeforeLandscape;
            }
            mLandscapeLayout = false;
            return;
        }
        if (landscape) {
            mScale = Math.min(mScale, maxScale);
        }
    }

    float getScale() {
        return mScale;
    }

    void setResizing(boolean resizing) {
        if (mResizing == resizing) return;
        mResizing = resizing;
        mResizeEffects.setActive(resizing);
    }

    void prepareForFullscreenConversion(boolean fixedAspectRatio) {
        mPreserveTaskBounds = !fixedAspectRatio;
        mSuppressConversionRelaunch = !fixedAspectRatio;
        mApplied = true;
    }

    boolean hasFixedAspectRatio() {
        return mFixedAspectRatio;
    }

    void setFixedAspectRatio(boolean fixed) {
        mFixedAspectRatio = fixed;
        mPreserveTaskBounds = false;
    }

    void setLayoutSnapshot(MomentLayoutSnapshot snapshot) {
        mLayoutSnapshot = snapshot;
    }

    Rect getContentCrop() {
        updateBounds();
        return new Rect(mContentCrop);
    }

    Rect getFixedAspectRatioBounds(DisplayContent displayContent) {
        final ActivityRecord activity = mTask.topRunningActivity();
        final boolean landscape = activity != null
                && activity.getRequestedConfigurationOrientation()
                        == Configuration.ORIENTATION_LANDSCAPE;
        final Rect displayBounds = displayContent.getBounds();
        final int shortEdge = Math.min(displayBounds.width(), displayBounds.height());
        final int rotation = landscape
                ? displayContent.getDisplayRotation().getLandscapeRotation()
                : displayContent.getDisplayRotation().getPortraitRotation();
        final InsetsState rawInsets = displayContent.getInsetsStateController().getRawInsetsState();
        if (mFixedLayoutRotation == rotation && mFixedLayoutDisplayBounds.equals(displayBounds)
                && mFixedLayoutInsets.equals(rawInsets)) {
            return new Rect(mFixedLayoutBounds);
        }
        final DisplayFrames frames = new DisplayFrames();
        displayContent.updateDisplayFrames(frames, rotation,
                landscape ? Math.round(shortEdge * 16f / 9f) : shortEdge,
                landscape ? shortEdge : Math.round(shortEdge * 16f / 9f));
        displayContent.getDisplayPolicy().simulateLayoutDisplay(frames);
        final Rect frame = frames.mInsetsState.getDisplayFrame();
        final Insets bars = frames.mInsetsState.calculateInsets(frame, frame,
                systemBars(), true /* ignoreVisibility */);
        // Reserve system-bar insets outside the fixed-ratio content. The existing crop then
        // produces an actual 16:9 (or 9:16) visible app surface, not a shortened 16:9 task.
        if (landscape) {
            final int contentHeight = Math.max(1, shortEdge - bars.top - bars.bottom);
            mFixedLayoutBounds.set(0, 0, Math.round(contentHeight * 16f / 9f)
                    + bars.left + bars.right, shortEdge);
        } else {
            final int contentWidth = Math.max(1, shortEdge - bars.left - bars.right);
            mFixedLayoutBounds.set(0, 0, shortEdge,
                    Math.round(contentWidth * 16f / 9f) + bars.top + bars.bottom);
        }
        mFixedLayoutDisplayBounds.set(displayBounds);
        mFixedLayoutInsets.set(rawInsets, true /* copySources */);
        mFixedLayoutRotation = rotation;
        return new Rect(mFixedLayoutBounds);
    }

    boolean shouldPreserveTaskBounds() {
        return mPreserveTaskBounds;
    }

    boolean shouldSuppressConversionRelaunch() {
        return mSuppressConversionRelaunch;
    }

    void finishFullscreenConversion() {
        mSuppressConversionRelaunch = false;
    }

    boolean isMomentCompact() {
        return mMomentCompact;
    }

    synchronized boolean isTransformAnimating() {
        return mTransformAnimating;
    }

    synchronized boolean isClosing() {
        return mClosing;
    }

    synchronized boolean isOpening() {
        return mOpening;
    }

    synchronized boolean isOpenAnimation(int generation) {
        return mOpening && generation == mAnimationGeneration;
    }

    synchronized boolean isCloseAnimation(int generation) {
        return mClosing && generation == mAnimationGeneration;
    }

    synchronized boolean isTransformAnimation(int generation) {
        return mTransformAnimating && generation == mAnimationGeneration;
    }

    void enterCompact(float restoreScale) {
        if (mMomentCompact) {
            return;
        }
        mCompactRestoreScale = restoreScale;
        mCompactScale = mScale;
        mCompactStashedSide = 0;
        mMomentCompact = true;
    }

    float getCompactRestoreScale() {
        return mCompactRestoreScale;
    }

    float getCompactScale() {
        return mCompactScale;
    }

    int getCompactStashedSide() {
        return mCompactStashedSide;
    }

    void setCompactStashedSide(int side) {
        mCompactStashedSide = side;
    }

    void setCompactDragTransform(float scale, int centerX, int centerY) {
        mScale = scale;
        setCenter(centerX, centerY);
    }

    void finishCompactRestore(float scale) {
        mScale = scale;
        mMomentCompact = false;
        mCompactStashedSide = 0;
        mTransformAnimating = false;
        mAnimatedAlpha = 1f;
        mAnimatedCropProgress = 1f;
    }

    Rect getSurfaceBounds() {
        updateBounds();
        return new Rect(mSurfaceBounds);
    }

    Rect getFullscreenBounds() {
        updateBounds();
        return new Rect(mFullscreenBounds);
    }

    int getCenterX() {
        updateBounds();
        return mCenter.x;
    }

    int getCenterY() {
        updateBounds();
        return mCenter.y;
    }

    Rect getDecorationBounds() {
        updateBounds();
        return new Rect(mDecorationBounds);
    }

    Rect getHandleBounds() {
        updateBounds();
        return new Rect(mSurfaceBounds);
    }

    String getDebugGeometry() {
        updateBounds();
        final DisplayContent displayContent = mTask.getDisplayContent();
        final WindowState mainWindow = mTask.getTopVisibleAppMainWindow();
        if (displayContent == null) {
            return "baseBounds=" + mBaseBounds
                    + " contentCrop=" + mContentCrop
                    + " surfaceBounds=" + mSurfaceBounds
                    + " display=null";
        }
        final InsetsState rawInsetsState = displayContent.getInsetsStateController()
                .getRawInsetsState();
        final Rect displayFrame = rawInsetsState.getDisplayFrame();
        final Insets rawSystemBarInsets = rawInsetsState.calculateInsets(displayFrame, displayFrame,
                systemBars(), true /* ignoreVisibility */);
        final InsetsState windowInsetsState = mainWindow != null ? mainWindow.getInsetsState() : null;
        final Insets windowSystemBarInsets = windowInsetsState != null
                ? windowInsetsState.calculateInsets(mainWindow.getFrame(), mainWindow.getBounds(),
                        systemBars(), true /* ignoreVisibility */)
                : Insets.NONE;
        return "baseBounds=" + mBaseBounds
                + " contentCrop=" + mContentCrop
                + " animatedCrop=" + mAnimatedCrop
                + " fullscreenBounds=" + mFullscreenBounds
                + " surfaceBounds=" + mSurfaceBounds
                + " center=" + mCenter
                + " scale=" + mScale
                + " displayBounds=" + displayContent.getBounds()
                + " displayFrame=" + displayFrame
                + " displayRotation=" + displayContent.getRotation()
                + " taskRotation=" + mTask.getWindowConfiguration().getRotation()
                + " rawSystemBarInsets=" + rawSystemBarInsets
                + " mainWindowFrame=" + (mainWindow != null ? mainWindow.getFrame() : "null")
                + " mainWindowBounds=" + (mainWindow != null ? mainWindow.getBounds() : "null")
                + " windowSystemBarInsets=" + windowSystemBarInsets;
    }

    float getDisplayedCornerRadius() {
        updateBounds();
        return MomentGeometry.getCornerRadius(getDensity()) * mScale;
    }

    void setCenter(int centerX, int centerY) {
        mCenter.set(centerX, centerY);
        mHasUserCenter = true;
    }

    void setScaleFromCorner(float scale, int fixedX, int fixedY,
            boolean resizeFromLeft, boolean resizeFromTop) {
        updateBounds();
        mScale = scale;
        final int width = Math.round(mContentCrop.width() * mScale);
        final int height = Math.round(mContentCrop.height() * mScale);
        mCenter.set(
                resizeFromLeft ? fixedX - (width - width / 2) : fixedX + width / 2,
                resizeFromTop ? fixedY - (height - height / 2) : fixedY + height / 2);
        mHasUserCenter = true;
    }

    void animateHandlePress(boolean isTouchDown) {
        mHandleSurfaces.animateBottomPress(isTouchDown);
    }

    MomentHandleSurfaces getHandleSurfaces() {
        return mHandleSurfaces;
    }

    DisplayFrames getPortraitDisplayFrames() {
        final DisplayContent displayContent = mTask.getDisplayContent();
        final Rect taskBounds = mTask.getBounds();
        if (displayContent == null || taskBounds.isEmpty()
                || (!mFixedAspectRatio
                        && (displayContent.getBounds().width() <= displayContent.getBounds().height()
                                || taskBounds.width() >= taskBounds.height()))) {
            return null;
        }
        final int rotation = taskBounds.width() > taskBounds.height()
                ? displayContent.getDisplayRotation().getLandscapeRotation()
                : displayContent.getDisplayRotation().getPortraitRotation();
        final InsetsState rawInsetsState = displayContent.getInsetsStateController()
                .getRawInsetsState();
        if (mPortraitDisplayFrames.mRotation != rotation
                || mPortraitDisplayFrames.mWidth != taskBounds.width()
                || mPortraitDisplayFrames.mHeight != taskBounds.height()
                || !mLastRawInsetsState.equals(rawInsetsState)) {
            displayContent.updateDisplayFrames(mPortraitDisplayFrames, rotation,
                    taskBounds.width(), taskBounds.height());
            displayContent.getDisplayPolicy().simulateLayoutDisplay(mPortraitDisplayFrames);
            mLastRawInsetsState.set(rawInsetsState, true /* copySources */);
        }
        return mPortraitDisplayFrames;
    }

    synchronized void apply(SurfaceControl.Transaction t, boolean showHandle) {
        if (mTask.mSurfaceControl == null) {
            return;
        }
        if (mOpening || mClosing) {
            mWindowEffects.hide(t);
            mResizeEffects.clear(t);
            t.hide(mTask.mSurfaceControl);
            mHandleSurfaces.hideBottom(t);
            return;
        }
        updateBounds();
        mCornerRadius = MomentGeometry.getCornerRadius(getDensity());
        applyTaskTransform(t);
        if (showHandle && !mMomentCompact && !mTransformAnimating && !mResizing) {
            mHandleSurfaces.showOrUpdateBottom(t, getHandleBounds());
        } else {
            mHandleSurfaces.hideBottom(t);
        }
        if (mTask.isVisibleRequested()) {
            mApplied = true;
        }
    }

    synchronized int beginTransformAnimation() {
        updateBounds();
        return beginTransformAnimation(mScale, mScale, mSurfaceBounds.exactCenterX(),
                mSurfaceBounds.exactCenterY(), 1f, getDisplayedCornerRadius(), 1f);
    }

    synchronized int beginTransformAnimation(float scaleX, float scaleY, float centerX,
            float centerY, float alpha, float displayedCornerRadius, float cropProgress) {
        updateBounds();
        mCornerRadius = MomentGeometry.getCornerRadius(getDensity());
        mTransformAnimating = true;
        mAnimatedScaleX = scaleX;
        mAnimatedScaleY = scaleY;
        mAnimatedCenterX = centerX;
        mAnimatedCenterY = centerY;
        mAnimatedAlpha = alpha;
        mAnimatedDisplayedCornerRadius = displayedCornerRadius;
        mAnimatedCropProgress = cropProgress;
        return ++mAnimationGeneration;
    }

    synchronized boolean applyAnimatedTransform(SurfaceControl.Transaction t, int generation,
            float scaleX, float scaleY, float centerX, float centerY, float alpha,
            float displayedCornerRadius, float cropProgress) {
        if (!mTransformAnimating || generation != mAnimationGeneration
                || mTask.mSurfaceControl == null || !mTask.mSurfaceControl.isValid()) {
            return false;
        }
        mAnimatedScaleX = scaleX;
        mAnimatedScaleY = scaleY;
        mAnimatedCenterX = centerX;
        mAnimatedCenterY = centerY;
        mAnimatedAlpha = alpha;
        mAnimatedDisplayedCornerRadius = displayedCornerRadius;
        mAnimatedCropProgress = cropProgress;
        applyTaskTransform(t);
        return true;
    }

    synchronized boolean finishTransformAnimation(int generation) {
        if (generation != mAnimationGeneration) {
            return false;
        }
        mTransformAnimating = false;
        mAnimatedAlpha = 1f;
        mAnimatedCropProgress = 1f;
        return true;
    }

    synchronized void cancelTransformAnimation() {
        mAnimationGeneration++;
        mTransformAnimating = false;
        mAnimatedAlpha = 1f;
        mAnimatedCropProgress = 1f;
    }

    synchronized int beginCloseAnimation(SurfaceControl.Transaction t, int color,
            float cornerRadius) throws Surface.OutOfResourcesException {
        if (mClosing || mTask.mSurfaceControl == null) {
            return -1;
        }
        final SurfaceControl parent = mTask.getParentSurfaceControl();
        if (parent == null) {
            return -1;
        }
        updateBounds();
        mMorphLayer.create(parent, "MomentCloseLayer#" + mTask.mTaskId);
        mClosing = true;
        final int generation = ++mAnimationGeneration;
        mMorphLayer.show(t, color, mSurfaceBounds.exactCenterX(), mSurfaceBounds.exactCenterY(),
                mSurfaceBounds.width(), mSurfaceBounds.height(), cornerRadius, 1f);
        t.hide(mTask.mSurfaceControl);
        mHandleSurfaces.hideBottom(t);
        return generation;
    }

    synchronized int beginOpenAnimation(SurfaceControl.Transaction t, int color, float centerX,
            float centerY, float width, float height, float cornerRadius) {
        if (mOpening || mClosing || mTask.mSurfaceControl == null) {
            return -1;
        }
        final SurfaceControl parent = mTask.getParentSurfaceControl();
        if (parent == null) {
            return -1;
        }
        updateBounds();
        try {
            mMorphLayer.create(parent, "MomentOpenLayer#" + mTask.mTaskId);
        } catch (Surface.OutOfResourcesException e) {
            return -1;
        }
        mOpening = true;
        mApplied = true;
        final int generation = ++mAnimationGeneration;
        mCornerRadius = MomentGeometry.getCornerRadius(getDensity());
        applyTaskTransform(t);
        mMorphLayer.show(t, color, centerX, centerY, width, height, cornerRadius, 0f);
        t.hide(mTask.mSurfaceControl);
        mHandleSurfaces.hideBottom(t);
        return generation;
    }

    synchronized boolean applyOpenAnimationFrame(SurfaceControl.Transaction t, int generation,
            float centerX, float centerY, float width, float height, float cornerRadius,
            float alpha) {
        if (!mOpening || generation != mAnimationGeneration || !mMorphLayer.isValid()) {
            return false;
        }
        mMorphLayer.applyFrame(t, centerX, centerY, width, height, cornerRadius, alpha);
        t.hide(mTask.mSurfaceControl);
        return true;
    }

    synchronized boolean finishOpenAnimation(SurfaceControl.Transaction t, int generation) {
        if (!mOpening || generation != mAnimationGeneration) {
            return false;
        }
        mMorphLayer.destroy(t);
        mOpening = false;
        applyTaskTransform(t);
        return true;
    }

    synchronized boolean applyCloseAnimationFrame(SurfaceControl.Transaction t, int generation,
            float centerX, float centerY, float width, float height, float cornerRadius,
            float alpha) {
        if (!mClosing || generation != mAnimationGeneration || !mMorphLayer.isValid()) {
            return false;
        }
        return mMorphLayer.applyFrame(t, centerX, centerY, width, height, cornerRadius, alpha);
    }

    synchronized boolean completeCloseAnimation(SurfaceControl.Transaction t, int generation) {
        if (!mClosing || generation != mAnimationGeneration || !mMorphLayer.isValid()) {
            return false;
        }
        mMorphLayer.hide(t);
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.hide(mTask.mSurfaceControl);
        }
        return true;
    }

    synchronized void prepareForTaskRemoval(SurfaceControl.Transaction t) {
        mResizeEffects.clear(t);
        mWindowEffects.hide(t);
        mAnimationGeneration++;
        mTransformAnimating = false;
        mOpening = false;
        mClosing = true;
        mMorphLayer.hide(t);
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.hide(mTask.mSurfaceControl);
        }
        mHandleSurfaces.hideBottom(t);
    }

    synchronized void cancelCloseAnimation(SurfaceControl.Transaction t, boolean restoreTask) {
        if (!mOpening && !mClosing && !mMorphLayer.isValid()) {
            return;
        }
        mAnimationGeneration++;
        mOpening = false;
        mClosing = false;
        mMorphLayer.destroy(t);
        if (restoreTask && mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.show(mTask.mSurfaceControl);
        }
    }

    private void applyTaskTransform(SurfaceControl.Transaction t) {
        final float scaleX = mTransformAnimating ? mAnimatedScaleX : mScale;
        final float scaleY = mTransformAnimating ? mAnimatedScaleY : mScale;
        final float centerX = mTransformAnimating ? mAnimatedCenterX : mSurfaceBounds.exactCenterX();
        final float centerY = mTransformAnimating ? mAnimatedCenterY : mSurfaceBounds.exactCenterY();
        final float cropProgress = mTransformAnimating ? mAnimatedCropProgress : 1f;
        mAnimatedCrop.set(
                Math.round(mContentCrop.left * cropProgress),
                Math.round(mContentCrop.top * cropProgress),
                mBaseBounds.width() - Math.round(
                        (mBaseBounds.width() - mContentCrop.right) * cropProgress),
                mBaseBounds.height() - Math.round(
                        (mBaseBounds.height() - mContentCrop.bottom) * cropProgress));
        final float width = mAnimatedCrop.width() * scaleX;
        final float height = mAnimatedCrop.height() * scaleY;
        final float scale = Math.max(Math.abs(scaleX), Math.abs(scaleY));
        final float cornerRadius = mTransformAnimating
                ? mAnimatedDisplayedCornerRadius / Math.max(0.001f, scale)
                : mCornerRadius;
        t.setPosition(mTask.mSurfaceControl,
                centerX - width / 2f - mAnimatedCrop.left * scaleX,
                centerY - height / 2f - mAnimatedCrop.top * scaleY)
                .setWindowCrop(mTask.mSurfaceControl, mAnimatedCrop)
                .setCornerRadius(mTask.mSurfaceControl,
                        cornerRadius)
                .setScale(mTask.mSurfaceControl, scaleX, scaleY)
                .setAlpha(mTask.mSurfaceControl, mTransformAnimating ? mAnimatedAlpha : 1f)
                .show(mTask.mSurfaceControl);
        mWindowEffects.apply(t, centerX - width / 2f, centerY - height / 2f,
                width, height, mTransformAnimating ? mAnimatedDisplayedCornerRadius
                        : mCornerRadius * scale,
                mTransformAnimating ? mAnimatedAlpha : 1f, getDensity());
        mResizeEffects.apply(t, mAnimatedCrop, centerX - width / 2f, centerY - height / 2f,
                width, height, mTransformAnimating ? mAnimatedDisplayedCornerRadius
                        : mCornerRadius * scale, getDensity());
        final MomentLayoutSnapshot snapshot = mLayoutSnapshot;
        if (snapshot != null) {
            snapshot.applyBounds(t, mAnimatedCrop, scaleX, scaleY);
        }
    }

    boolean hasApplied() {
        return mApplied;
    }

    boolean ensureForceTranslucent() {
        return mTask.setForceTranslucent(true);
    }

    void reset(SurfaceControl.Transaction t) {
        setResizing(false);
        mResizeEffects.clear(t);
        mWindowEffects.hide(t);
        if (mTask.mSurfaceControl == null) {
            return;
        }
        t.setPosition(mTask.mSurfaceControl, 0f, 0f)
                .setWindowCrop(mTask.mSurfaceControl, null)
                .setScale(mTask.mSurfaceControl, 1f, 1f)
                .setAlpha(mTask.mSurfaceControl, 1f)
                .setCornerRadius(mTask.mSurfaceControl, 0f);
        mTask.setForceTranslucent(false);
        mHandleSurfaces.hideBottom(t);
        cancelCloseAnimation(t, false /* restoreTask */);
        cancelTransformAnimation();
        mMomentCompact = false;
        mCompactStashedSide = 0;
        mPreserveTaskBounds = false;
        mSuppressConversionRelaunch = false;
        mApplied = false;
    }

    void destroy(SurfaceControl.Transaction t) {
        cancelCloseAnimation(t, false /* restoreTask */);
        cancelTransformAnimation();
        mTask.setForceTranslucent(false);
        mHandleSurfaces.destroy(t);
        mWindowEffects.destroy(t);
        mResizeEffects.destroy(t);
    }

    private float getDensity() {
        final DisplayContent displayContent = mTask.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }

    private void updateBounds() {
        mBaseBounds.set(mTask.getBounds());
        if (mBaseBounds.isEmpty()) {
            final DisplayContent displayContent = mTask.getDisplayContent();
            if (displayContent != null) {
                displayContent.getBounds(mBaseBounds);
            }
        }
        final Rect displayBounds = new Rect();
        final DisplayContent displayContent = mTask.getDisplayContent();
        if (displayContent != null) {
            displayContent.getBounds(displayBounds);
        } else {
            displayBounds.set(mBaseBounds);
        }

        mContentCrop.set(0, 0, mBaseBounds.width(), mBaseBounds.height());
        mFullscreenBounds.set(displayBounds);
        if (displayContent != null) {
            final DisplayFrames portraitDisplayFrames = getPortraitDisplayFrames();
            final InsetsState insetsState = portraitDisplayFrames != null
                    ? portraitDisplayFrames.mInsetsState
                    : displayContent.getInsetsStateController().getRawInsetsState();
            final Rect displayFrame = insetsState.getDisplayFrame();
            final Insets systemBarInsets = insetsState.calculateInsets(displayFrame, displayFrame,
                    systemBars(), true /* ignoreVisibility */);
            mContentCrop.inset(systemBarInsets.left, systemBarInsets.top,
                    systemBarInsets.right, systemBarInsets.bottom);
            if (mContentCrop.isEmpty()) {
                mContentCrop.set(0, 0, mBaseBounds.width(), mBaseBounds.height());
            }
        }

        final int width = Math.round(mContentCrop.width() * mScale);
        final int height = Math.round(mContentCrop.height() * mScale);
        if (!mHasUserCenter) {
            mCenter.set(displayBounds.centerX(), displayBounds.centerY());
        }
        final int left = mCenter.x - width / 2;
        final int top = mCenter.y - height / 2;
        mSurfaceBounds.set(left, top, left + width, top + height);

        mDecorationBounds.set(mSurfaceBounds);
        final ActivityRecord topActivity = mTask.topRunningActivity();
        final WindowState mainWindow = topActivity != null
                ? topActivity.findMainWindow(false /* includeStartingApp */) : null;
        if (mainWindow != null && !mainWindow.getFrame().isEmpty()) {
            final Rect frame = mainWindow.getFrame();
            mDecorationBounds.set(
                    left + Math.round((frame.left - mBaseBounds.left - mContentCrop.left) * mScale),
                    top + Math.round((frame.top - mBaseBounds.top - mContentCrop.top) * mScale),
                    left + Math.round(
                            (frame.right - mBaseBounds.left - mContentCrop.left) * mScale),
                    top + Math.round(
                            (frame.bottom - mBaseBounds.top - mContentCrop.top) * mScale));
            if (!mDecorationBounds.intersect(mSurfaceBounds)) {
                mDecorationBounds.set(mSurfaceBounds);
            }
        }
    }
}
