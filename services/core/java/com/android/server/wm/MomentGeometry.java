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

import android.graphics.Rect;
import android.graphics.RectF;

final class MomentGeometry {

    static final int CORNER_RADIUS_DP = 28;
    static final int HANDLE_TOUCH_HEIGHT_DP = 32;
    static final int CORNER_TOUCH_SIZE_DP = 40;
    static final int MIN_HANDLE_HEIGHT_DP = 2;
    static final int MAX_HANDLE_HEIGHT_DP = 5;
    static final float HANDLE_HEIGHT_WIDTH_RATIO = 0.015f;
    static final int TOP_HANDLE_HEIGHT_DP = 4;
    static final int TOP_HANDLE_TOUCH_WIDTH_DP = 100;
    static final int HANDLE_MENU_WIDTH_DP = 180;
    static final int HANDLE_MENU_HEIGHT_DP = 48;
    static final int HANDLE_MENU_GAP_DP = 4;
    static final int HANDLE_MENU_TOP_INSET_DP = HANDLE_MENU_HEIGHT_DP + HANDLE_MENU_GAP_DP;
    static final int COMPACT_DISMISS_TARGET_SIZE_DP = 96;
    static final float CLOSE_MORPH_FRACTION = 0.72f;


    private MomentGeometry() {
    }

    static float dpToPx(float density, float dp) {
        return density * dp;
    }

    static float getCornerRadius(float density) {
        return dpToPx(density, CORNER_RADIUS_DP);
    }

    static float getBottomHandleHeight(float width, float density) {
        return Math.max(dpToPx(density, MIN_HANDLE_HEIGHT_DP),
                Math.min(dpToPx(density, MAX_HANDLE_HEIGHT_DP),
                        width * HANDLE_HEIGHT_WIDTH_RATIO));
    }

    /**
     * Displayed height of the app's own status bar (top) or navigation bar (bottom) band kept
     * inside {@code momentBounds}. {@code barFraction} is the bar inset divided by the visible
     * task height, so it stays valid for every scale of the same task.
     */
    static float getBarHeight(Rect momentBounds, float barFraction) {
        return momentBounds.height() * barFraction;
    }

    /** Centers a handle in its bar; a bar thinner than the handle keeps it inside the window. */
    private static float getBarCenterOffset(Rect momentBounds, float barFraction,
            float handleHeight) {
        return Math.max(getBarHeight(momentBounds, barFraction), handleHeight) / 2f;
    }

    static float getBottomHandleCenterY(Rect momentBounds, float bottomBarFraction,
            float handleHeight) {
        return momentBounds.bottom
                - getBarCenterOffset(momentBounds, bottomBarFraction, handleHeight);
    }

    static void getBottomHandleBounds(Rect momentBounds, float bottomBarFraction, float density,
            float widthScale, RectF outBounds) {
        final float baseWidth = Math.max(1f, momentBounds.width() / 3f);
        final float width = Math.max(1f, baseWidth * widthScale);
        final float height = getBottomHandleHeight(momentBounds.width(), density);
        final float centerX = momentBounds.exactCenterX();
        final float centerY = getBottomHandleCenterY(momentBounds, bottomBarFraction, height);
        outBounds.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f);
    }

    /**
     * Drag target covering the whole bottom bar. A bar thinner than a usable target grows it
     * downward, outside the window, so application content never loses touches.
     */
    static void getBottomHandleTouchBounds(Rect momentBounds, float bottomBarFraction,
            float density, Rect outBounds) {
        final float barHeight = Math.max(getBarHeight(momentBounds, bottomBarFraction),
                getBottomHandleHeight(momentBounds.width(), density));
        final int top = momentBounds.bottom - Math.round(barHeight);
        outBounds.set(momentBounds.left, top, momentBounds.right, Math.max(momentBounds.bottom,
                top + Math.round(dpToPx(density, HANDLE_TOUCH_HEIGHT_DP))));
    }

    static void getTopHandleBounds(Rect momentBounds, float topBarFraction, float density,
            float shapeProgress, boolean includeCollapsedTouchTarget, RectF outBounds) {
        final float visualCollapsedWidth = Math.max(1f, momentBounds.width() / 6f);
        final float visualCollapsedHeight = dpToPx(density, TOP_HANDLE_HEIGHT_DP);
        final float collapsedWidth;
        final float collapsedHeight;
        final float collapsedCenterY;
        if (includeCollapsedTouchTarget) {
            // Wide enough to grab, but never over the top-corner resize targets.
            collapsedWidth = Math.max(visualCollapsedWidth,
                    Math.min(dpToPx(density, TOP_HANDLE_TOUCH_WIDTH_DP),
                            momentBounds.width() - dpToPx(density, CORNER_TOUCH_SIZE_DP)));
            // Cover the whole top bar; a thin bar grows the target upward, away from content.
            final float bottom = momentBounds.top + Math.max(
                    getBarHeight(momentBounds, topBarFraction), visualCollapsedHeight);
            collapsedHeight = Math.max(bottom - momentBounds.top,
                    dpToPx(density, HANDLE_TOUCH_HEIGHT_DP));
            collapsedCenterY = bottom - collapsedHeight / 2f;
        } else {
            collapsedWidth = visualCollapsedWidth;
            collapsedHeight = visualCollapsedHeight;
            collapsedCenterY = momentBounds.top
                    + getBarCenterOffset(momentBounds, topBarFraction, visualCollapsedHeight);
        }
        final float expandedWidth = dpToPx(density, HANDLE_MENU_WIDTH_DP);
        final float expandedHeight = dpToPx(density, HANDLE_MENU_HEIGHT_DP);
        final float centerX = momentBounds.exactCenterX();
        final float expandedCenterY = momentBounds.top
                - dpToPx(density, HANDLE_MENU_GAP_DP) - expandedHeight / 2f;
        final float width = lerp(collapsedWidth, expandedWidth, shapeProgress);
        final float height = lerp(collapsedHeight, expandedHeight, shapeProgress);
        final float centerY = lerp(collapsedCenterY, expandedCenterY, shapeProgress);
        outBounds.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f);
    }

    static void evaluateCloseMorph(Rect startBounds, float bottomBarFraction, float taskScale,
            float density, float progress, float effects, MorphFrame outFrame) {
        progress = MomentMotion.clamp(progress);
        final float morphProgress = Math.min(1f, progress / CLOSE_MORPH_FRACTION);
        final float shrinkProgress = progress <= CLOSE_MORPH_FRACTION ? 0f
                : (progress - CLOSE_MORPH_FRACTION) / (1f - CLOSE_MORPH_FRACTION);
        final float targetWidth = Math.max(1f, startBounds.width() / 2f);
        final float targetHeight = getBottomHandleHeight(startBounds.width(), density);
        final float targetCenterX = startBounds.exactCenterX();
        final float targetCenterY = getBottomHandleCenterY(startBounds, bottomBarFraction,
                targetHeight);
        final float morphedWidth = lerp(startBounds.width(), targetWidth, morphProgress);
        outFrame.centerX = lerp(startBounds.exactCenterX(), targetCenterX, morphProgress);
        outFrame.centerY = lerp(startBounds.exactCenterY(), targetCenterY, morphProgress);
        outFrame.width = morphedWidth * (1f - shrinkProgress);
        outFrame.height = lerp(startBounds.height(), targetHeight, morphProgress);
        outFrame.cornerRadius = lerp(getCornerRadius(density) * taskScale,
                targetHeight / 2f, morphProgress);
        // Independent non-bouncy effects. Keep the container legible through the morph,
        // then dissolve the final handle instead of bouncing an almost invisible window.
        outFrame.alpha = 1f - MomentMotion.clamp((effects - 0.65f) / 0.35f);
    }

    static void evaluateOpenMorph(Rect endBounds, float bottomBarFraction, float taskScale,
            float density, float spatial, float effects, MorphFrame outFrame) {
        final float startHeight = getBottomHandleHeight(endBounds.width(), density);
        final float startWidth = endBounds.width() / 2f;
        outFrame.centerX = endBounds.exactCenterX();
        outFrame.centerY = lerp(getBottomHandleCenterY(endBounds, bottomBarFraction, startHeight),
                endBounds.exactCenterY(), MomentMotion.clamp(spatial));
        // The task is hidden during this color-layer morph. A restrained size overshoot
        // provides expression without non-uniformly stretching application content.
        final float growth = Math.max(0f, Math.min(1.02f, spatial));
        outFrame.width = Math.max(1f, lerp(startWidth, endBounds.width(), growth));
        outFrame.height = Math.max(1f, lerp(startHeight, endBounds.height(), growth));
        outFrame.cornerRadius = lerp(startHeight / 2f,
                getCornerRadius(density) * taskScale, growth);
        outFrame.alpha = MomentMotion.clamp(effects);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    static final class MorphFrame {
        float centerX;
        float centerY;
        float width;
        float height;
        float cornerRadius;
        float alpha;
    }
}
