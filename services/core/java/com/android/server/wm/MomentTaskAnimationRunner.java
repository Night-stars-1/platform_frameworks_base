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

import android.util.ArrayMap;
import android.view.Choreographer;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

import java.util.function.BooleanSupplier;

final class MomentTaskAnimationRunner {

    interface FrameCallback {
        void onFrame(float spatialProgress, float effectsProgress);
    }

    private final WindowManagerService mService;
    private final ArrayMap<MomentTaskSurfaceState, Motion> mAnimators = new ArrayMap<>();

    MomentTaskAnimationRunner(WindowManagerService service) {
        mService = service;
    }

    void start(MomentTaskSurfaceState state, MomentMotion.Spec spatialSpec,
            BooleanSupplier isValid, FrameCallback frameCallback, Runnable endCallback) {
        mService.mAnimationHandler.post(() -> {
            if (!isValid.getAsBoolean()) {
                return;
            }
            final Motion previous = mAnimators.remove(state);
            if (previous != null) {
                previous.cancel();
            }
            final Motion motion = new Motion(state, spatialSpec, isValid, frameCallback, endCallback);
            mAnimators.put(state, motion);
            motion.start();
        });
    }

    void cancel(MomentTaskSurfaceState state) {
        mService.mAnimationHandler.post(() -> {
            final Motion animator = mAnimators.remove(state);
            if (animator != null) {
                animator.cancel();
            }
        });
    }

    /** Both channels have one task owner and one lifecycle, but independent spring physics. */
    private final class Motion {
        private final MomentTaskSurfaceState mState;
        private final BooleanSupplier mIsValid;
        private final FrameCallback mFrame;
        private final Runnable mEnd;
        private final SpringAnimation mSpatial;
        private final SpringAnimation mEffects;
        private final Choreographer mChoreographer = Choreographer.getInstance();
        private final Runnable mDispatchFrame = this::applyFrame;
        private float mSpatialProgress;
        private float mEffectsProgress;
        private int mEnded;
        private boolean mCancelled;
        private boolean mFrameScheduled;

        Motion(MomentTaskSurfaceState state, MomentMotion.Spec spatialSpec,
                BooleanSupplier isValid, FrameCallback frame, Runnable end) {
            mState = state;
            mIsValid = isValid;
            mFrame = frame;
            mEnd = end;
            mSpatial = MomentMotion.spring(0f, 1f, 0f, spatialSpec, 0.001f);
            mEffects = MomentMotion.spring(0f, 1f, 0f, MomentMotion.DEFAULT_EFFECTS, 0.001f);
            mSpatial.addUpdateListener((animation, value, velocity) -> {
                mSpatialProgress = value;
                dispatchFrame();
            });
            mEffects.addUpdateListener((animation, value, velocity) -> {
                mEffectsProgress = MomentMotion.clamp(value);
                dispatchFrame();
            });
            mSpatial.addEndListener((animation, cancelled, value, velocity) -> finish(cancelled));
            mEffects.addEndListener((animation, cancelled, value, velocity) -> finish(cancelled));
        }

        void start() {
            mSpatial.start();
            if (!mCancelled) {
                mEffects.start();
            }
        }

        void cancel() {
            mCancelled = true;
            removeFrameCallback();
            mSpatial.cancel();
            mEffects.cancel();
        }

        private void dispatchFrame() {
            if (!mCancelled && !mFrameScheduled) {
                mFrameScheduled = true;
                // DynamicAnimation updates both springs in the animation phase. Submit their
                // combined pose once afterwards, rather than two task transactions per vsync.
                mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                        mDispatchFrame, null);
            }
        }

        private void removeFrameCallback() {
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_TRAVERSAL,
                    mDispatchFrame, null);
            mFrameScheduled = false;
        }

        private void applyFrame() {
            mFrameScheduled = false;
            if (mCancelled) {
                return;
            }
            if (!mIsValid.getAsBoolean()) {
                cancel();
                if (mAnimators.get(mState) == this) {
                    mAnimators.remove(mState);
                }
                return;
            }
            mFrame.onFrame(mSpatialProgress, mEffectsProgress);
        }

        private void finish(boolean cancelled) {
            if (cancelled || mCancelled || ++mEnded != 2) {
                return;
            }
            if (mAnimators.get(mState) == this) {
                mAnimators.remove(mState);
            }
            removeFrameCallback();
            if (mIsValid.getAsBoolean()) {
                mFrame.onFrame(1f, 1f);
                mEnd.run();
            }
        }
    }
}
