/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import android.animation.ValueAnimator;
import android.view.Choreographer;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

/** UI-thread drag follower with bounded lag and a short, velocity-continuous release. */
final class MomentDragMotion {
    interface Callback { void move(float x, float y); }
    private final Callback mCallback;
    private final float mDensity;
    private final Choreographer mClock = Choreographer.getInstance();
    private final Runnable mFrame = this::dispatch;
    private SpringAnimation mXSpring;
    private SpringAnimation mYSpring;
    private float mX, mY, mTargetX, mTargetY, mOriginX, mOriginY;
    private float mVisibleX, mVisibleY;
    private boolean mFollowing;
    private boolean mScheduled;

    MomentDragMotion(float density, Callback callback) {
        mDensity = density;
        mCallback = callback;
    }

    void begin(float x, float y) {
        cancel();
        mX = mTargetX = x;
        mY = mTargetY = y;
        mVisibleX = x;
        mVisibleY = y;
        mFollowing = true;
    }

    void track(float x, float y) {
        mTargetX = x;
        mTargetY = y;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mX = x;
            mY = y;
            dispatch();
            return;
        }
        if (mXSpring == null) start(x, y, 0f, 0f, true);
        else {
            mXSpring.animateToFinalPosition(x);
            mYSpring.animateToFinalPosition(y);
        }
        schedule();
    }

    void release(float vx, float vy) {
        mFollowing = false;
        mOriginX = mX = mVisibleX;
        mOriginY = mY = mVisibleY;
        final float speed = (float) Math.hypot(vx, vy);
        final float cap = 2400f * mDensity;
        if (speed > cap) { vx *= cap / speed; vy *= cap / speed; }
        final float dx = vx * .025f;
        final float dy = vy * .025f;
        final float x = mTargetX + dx;
        final float y = mTargetY + dy;
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mX = mTargetX;
            mY = mTargetY;
            dispatch();
        } else start(x, y, vx, vy, false);
    }

    private void start(float x, float y, float vx, float vy, boolean following) {
        mXSpring = MomentMotion.spring(mX, x, vx, MomentMotion.DEFAULT_SPATIAL, .5f);
        mYSpring = MomentMotion.spring(mY, y, vy, MomentMotion.DEFAULT_SPATIAL, .5f);
        if (following) {
            // A stiff, nearly critically damped follower gives weight without a loose tail.
            mXSpring.getSpring().setStiffness(1800f).setDampingRatio(.9f);
            mYSpring.getSpring().setStiffness(1800f).setDampingRatio(.9f);
        }
        mXSpring.addUpdateListener((a, value, velocity) -> { mX = value; schedule(); });
        mYSpring.addUpdateListener((a, value, velocity) -> { mY = value; schedule(); });
        mXSpring.start();
        mYSpring.start();
    }

    private void schedule() {
        if (mScheduled) return;
        mScheduled = true;
        mClock.postCallback(Choreographer.CALLBACK_TRAVERSAL, mFrame, null);
    }

    private void dispatch() {
        mScheduled = false;
        final float anchorX = mFollowing ? mTargetX : mOriginX;
        final float anchorY = mFollowing ? mTargetY : mOriginY;
        final float limit = (mFollowing ? 32f : 96f) * mDensity;
        final float distance = (float) Math.hypot(mX - anchorX, mY - anchorY);
        final float ratio = distance > limit ? limit / distance : 1f;
        final float x = anchorX + (mX - anchorX) * ratio;
        final float y = anchorY + (mY - anchorY) * ratio;
        mVisibleX = x;
        mVisibleY = y;
        mCallback.move(x, y);
    }

    void cancel() {
        if (mXSpring != null) mXSpring.cancel();
        if (mYSpring != null) mYSpring.cancel();
        mXSpring = mYSpring = null;
        mClock.removeCallbacks(Choreographer.CALLBACK_TRAVERSAL, mFrame, null);
        mScheduled = false;
    }
}
