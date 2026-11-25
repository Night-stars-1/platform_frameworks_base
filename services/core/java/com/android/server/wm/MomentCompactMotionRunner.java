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

import android.animation.ValueAnimator;

import com.android.internal.dynamicanimation.animation.DynamicAnimation;
import com.android.internal.dynamicanimation.animation.FlingAnimation;
import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;

final class MomentCompactMotionRunner {

    private static final float FLING_FRICTION = 1.9f;
    private static final float MOTION_STIFFNESS = MomentMotion.DEFAULT_SPATIAL.stiffness;
    private static final float MOTION_DAMPING = MomentMotion.DEFAULT_SPATIAL.damping;

    interface UpdateCallback {
        void onUpdate(float centerX, float centerY, float magnetProgress);
    }

    private final UpdateCallback mUpdateCallback;

    private float mCenterX;
    private float mCenterY;
    private float mMagnetProgress;
    private int mGeneration;
    private SpringAnimation mXSpring;
    private SpringAnimation mYSpring;
    private SpringAnimation mScaleSpring;
    private FlingAnimation mXFling;
    private FlingAnimation mYFling;

    MomentCompactMotionRunner(UpdateCallback updateCallback) {
        mUpdateCallback = updateCallback;
    }

    void setValues(float centerX, float centerY, float magnetProgress, boolean dispatch) {
        mCenterX = centerX;
        mCenterY = centerY;
        mMagnetProgress = clampProgress(magnetProgress);
        if (dispatch) {
            dispatchUpdate();
        }
    }

    float getCenterX() {
        return mCenterX;
    }

    float getCenterY() {
        return mCenterY;
    }

    float getMagnetProgress() {
        return mMagnetProgress;
    }

    void springTo(float targetCenterX, float targetCenterY, float targetMagnetProgress,
            float velocityX, float velocityY, float stiffness, float dampingRatio,
            Runnable endAction) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel();
            setValues(targetCenterX, targetCenterY, targetMagnetProgress, true /* dispatch */);
            if (endAction != null) {
                endAction.run();
            }
            return;
        }
        cancel();
        final int generation = ++mGeneration;
        final int[] endedAnimations = new int[1];
        mXSpring = createSpring(new FloatValueHolder(mCenterX), targetCenterX, velocityX,
                stiffness, dampingRatio, (animation, value, unusedVelocity) -> {
                    mCenterX = value;
                    dispatchUpdate();
                });
        mYSpring = createSpring(new FloatValueHolder(mCenterY), targetCenterY, velocityY,
                stiffness, dampingRatio, (animation, value, unusedVelocity) -> {
                    mCenterY = value;
                    dispatchUpdate();
                });
        mScaleSpring = createSpring(new FloatValueHolder(mMagnetProgress), targetMagnetProgress,
                0f, MomentMotion.DEFAULT_EFFECTS.stiffness,
                MomentMotion.DEFAULT_EFFECTS.damping, (animation, value, unusedVelocity) -> {
                    mMagnetProgress = clampProgress(value);
                    dispatchUpdate();
                });
        mScaleSpring.setMinimumVisibleChange(0.001f);
        final DynamicAnimation.OnAnimationEndListener endListener =
                (animation, canceled, value, velocity) -> {
                    if (!canceled && generation == mGeneration
                            && ++endedAnimations[0] == 3 && endAction != null) {
                        endAction.run();
                    }
                };
        mXSpring.addEndListener(endListener);
        mYSpring.addEndListener(endListener);
        mScaleSpring.addEndListener(endListener);
        mXSpring.start();
        mYSpring.start();
        mScaleSpring.start();
    }

    void dismissTo(float targetY, Runnable endAction) {
        springTo(mCenterX, targetY, mMagnetProgress, 0f, 0f,
                MomentMotion.EXIT_SPATIAL.stiffness,
                MomentMotion.EXIT_SPATIAL.damping, endAction);
    }

    boolean retargetSpring(float targetCenterX, float targetCenterY,
            float targetMagnetProgress) {
        if (mXSpring == null || mYSpring == null || mScaleSpring == null) {
            return false;
        }
        retargetOrSet(mXSpring, targetCenterX, true /* xAxis */);
        retargetOrSet(mYSpring, targetCenterY, false /* xAxis */);
        if (mScaleSpring.isRunning()) {
            mScaleSpring.animateToFinalPosition(targetMagnetProgress);
        } else {
            mMagnetProgress = clampProgress(targetMagnetProgress);
        }
        dispatchUpdate();
        return true;
    }

    void flingThenSpring(float minX, float maxX, float velocityX,
            float minY, float maxY, float velocityY, Runnable endAction) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel();
            final float targetX = velocityX < 0f ? minX : velocityX > 0f ? maxX
                    : mCenterX < (minX + maxX) / 2f ? minX : maxX;
            setValues(targetX, Math.max(minY, Math.min(maxY, mCenterY)), 0f, true /* dispatch */);
            endAction.run();
            return;
        }
        cancel();
        final int generation = ++mGeneration;
        final int[] endedAxes = new int[1];
        final Runnable axisEnd = () -> {
            if (generation == mGeneration && ++endedAxes[0] == 3) {
                endAction.run();
            }
        };
        startXFlingThenSpring(minX, maxX, velocityX, generation, axisEnd);
        startYFlingThenSpring(minY, maxY, velocityY, generation, axisEnd);
        mScaleSpring = createSpring(new FloatValueHolder(mMagnetProgress), 0f, 0f,
                MomentMotion.DEFAULT_EFFECTS.stiffness, MomentMotion.DEFAULT_EFFECTS.damping,
                (animation, value, unusedVelocity) -> {
                    mMagnetProgress = clampProgress(value);
                    dispatchUpdate();
                });
        mScaleSpring.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled && generation == mGeneration) {
                axisEnd.run();
            }
        });
        mScaleSpring.setMinimumVisibleChange(0.001f);
        mScaleSpring.start();
    }

    void cancel() {
        mGeneration++;
        if (mXFling != null) {
            mXFling.cancel();
            mXFling = null;
        }
        if (mYFling != null) {
            mYFling.cancel();
            mYFling = null;
        }
        if (mXSpring != null) {
            mXSpring.cancel();
            mXSpring = null;
        }
        if (mYSpring != null) {
            mYSpring.cancel();
            mYSpring = null;
        }
        if (mScaleSpring != null) {
            mScaleSpring.cancel();
            mScaleSpring = null;
        }
    }

    boolean isRunning() {
        return isRunning(mXFling) || isRunning(mYFling) || isRunning(mXSpring)
                || isRunning(mYSpring) || isRunning(mScaleSpring);
    }

    private void startXFlingThenSpring(float min, float max, float velocity,
            int generation, Runnable axisEnd) {
        final float current = mCenterX;
        final float midpoint = (min + max) / 2f;
        final float projectedEnd = current + velocity / (FLING_FRICTION * 4.2f);
        if (current < min || current > max
                || velocity < 0f && projectedEnd > midpoint
                || velocity > 0f && projectedEnd < midpoint) {
            final float target = current < min ? min : current > max ? max
                    : projectedEnd < midpoint ? min : max;
            startAxisSpring(true /* xAxis */, target, velocity, generation, axisEnd);
            return;
        }
        final float target = velocity < 0f ? min : max;
        final float velocityToReachTarget = (target - current) * FLING_FRICTION * 4.2f;
        final float adjustedVelocity = velocity < 0f
                ? Math.min(velocity, velocityToReachTarget)
                : Math.max(velocity, velocityToReachTarget);
        mXFling = new FlingAnimation(new FloatValueHolder(current))
                .setFriction(FLING_FRICTION)
                .setStartVelocity(adjustedVelocity)
                .setMinValue(min)
                .setMaxValue(max);
        mXFling.addUpdateListener((animation, position, unusedVelocity) -> {
            mCenterX = position;
            dispatchUpdate();
        });
        mXFling.addEndListener((animation, canceled, position, endVelocity) -> {
            if (!canceled && generation == mGeneration) {
                mCenterX = position;
                startAxisSpring(true /* xAxis */, target, endVelocity, generation, axisEnd);
            }
        });
        mXFling.start();
    }

    private void startYFlingThenSpring(float min, float max, float velocity,
            int generation, Runnable axisEnd) {
        final float current = mCenterY;
        if (current < min || current > max) {
            startAxisSpring(false /* xAxis */, Math.max(min, Math.min(max, current)),
                    velocity, generation, axisEnd);
            return;
        }
        mYFling = new FlingAnimation(new FloatValueHolder(current))
                .setFriction(FLING_FRICTION)
                .setStartVelocity(velocity)
                .setMinValue(min)
                .setMaxValue(max);
        mYFling.addUpdateListener((animation, position, unusedVelocity) -> {
            mCenterY = position;
            dispatchUpdate();
        });
        mYFling.addEndListener((animation, canceled, position, endVelocity) -> {
            if (canceled || generation != mGeneration) {
                return;
            }
            mCenterY = position;
            if ((position <= min || position >= max) && endVelocity != 0f) {
                startAxisSpring(false /* xAxis */, Math.max(min, Math.min(max, position)),
                        endVelocity, generation, axisEnd);
            } else {
                axisEnd.run();
            }
        });
        mYFling.start();
    }

    private void startAxisSpring(boolean xAxis, float target, float velocity,
            int generation, Runnable axisEnd) {
        final FloatValueHolder value = new FloatValueHolder(xAxis ? mCenterX : mCenterY);
        final SpringAnimation spring = createSpring(value, target, velocity,
                MOTION_STIFFNESS, MOTION_DAMPING, (animation, position, unusedVelocity) -> {
                    if (xAxis) {
                        mCenterX = position;
                    } else {
                        mCenterY = position;
                    }
                    dispatchUpdate();
                });
        if (xAxis) {
            mXSpring = spring;
        } else {
            mYSpring = spring;
        }
        spring.addEndListener((animation, canceled, position, endVelocity) -> {
            if (!canceled && generation == mGeneration) {
                axisEnd.run();
            }
        });
        spring.start();
    }

    private SpringAnimation createSpring(FloatValueHolder value, float target, float velocity,
            float stiffness, float dampingRatio,
            DynamicAnimation.OnAnimationUpdateListener updateListener) {
        final SpringForce spring = new SpringForce(target)
                .setStiffness(stiffness)
                .setDampingRatio(dampingRatio);
        final SpringAnimation animation = new SpringAnimation(value)
                .setSpring(spring)
                .setStartVelocity(velocity);
        animation.addUpdateListener(updateListener);
        return animation;
    }

    private void retargetOrSet(SpringAnimation spring, float target, boolean xAxis) {
        if (spring.isRunning()) {
            spring.animateToFinalPosition(target);
        } else if (xAxis) {
            mCenterX = target;
        } else {
            mCenterY = target;
        }
    }

    private void dispatchUpdate() {
        mUpdateCallback.onUpdate(mCenterX, mCenterY, mMagnetProgress);
    }

    private static boolean isRunning(DynamicAnimation<?> animation) {
        return animation != null && animation.isRunning();
    }

    private static float clampProgress(float progress) {
        return Math.max(0f, Math.min(1f, progress));
    }
}
