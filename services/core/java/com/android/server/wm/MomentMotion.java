/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;

/** Shared M3 Expressive physics, without a dependency on a UI toolkit in system_server. */
final class MomentMotion {
    // AndroidX ExpressiveMotionTokens. Spatial motion may overshoot; effects must not.
    static final Spec FAST_SPATIAL = new Spec(0.6f, 800f);
    static final Spec DEFAULT_SPATIAL = new Spec(0.8f, 380f);
    static final Spec SLOW_SPATIAL = new Spec(0.8f, 200f);
    // Dismissals are deliberately monotonic: reuse fast spatial stiffness without bounce.
    static final Spec EXIT_SPATIAL = new Spec(1f, 800f);
    static final Spec FAST_EFFECTS = new Spec(1f, 3800f);
    static final Spec DEFAULT_EFFECTS = new Spec(1f, 1600f);
    static final Spec SLOW_EFFECTS = new Spec(1f, 800f);

    private MomentMotion() { }

    static SpringAnimation spring(float from, float to, float velocity, Spec spec,
            float minimumVisibleChange) {
        // The platform dynamic-animation clock respects ValueAnimator duration scale, including
        // disabled animations. Retarget existing springs to preserve both position and velocity.
        return new SpringAnimation(new FloatValueHolder(from))
                .setSpring(new SpringForce(to).setDampingRatio(spec.damping)
                        .setStiffness(spec.stiffness))
                .setStartVelocity(velocity)
                .setMinimumVisibleChange(minimumVisibleChange);
    }

    static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static final class Spec {
        final float damping;
        final float stiffness;

        private Spec(float damping, float stiffness) {
            this.damping = damping;
            this.stiffness = stiffness;
        }
    }
}
