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
    static final int HANDLE_AREA_HEIGHT_DP = 18;
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

    static void getBottomHandleBounds(Rect momentBounds, float density, float widthScale,
            RectF outBounds) {
        final float baseWidth = Math.max(1f, momentBounds.width() / 3f);
        final float width = Math.max(1f, baseWidth * widthScale);
        final float height = getBottomHandleHeight(momentBounds.width(), density);
        final float centerX = momentBounds.exactCenterX();
        final float centerY = momentBounds.bottom
                + dpToPx(density, HANDLE_AREA_HEIGHT_DP) / 2f;
        outBounds.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f);
    }

    static void getTopHandleBounds(Rect momentBounds, float density, float displayedCornerRadius,
            float shapeProgress, boolean includeCollapsedTouchTarget, RectF outBounds) {
        final float visualCollapsedWidth = Math.max(1f, momentBounds.width() / 6f);
        final float collapsedWidth = includeCollapsedTouchTarget
                ? Math.max(dpToPx(density, TOP_HANDLE_TOUCH_WIDTH_DP), visualCollapsedWidth)
                : visualCollapsedWidth;
        final float collapsedHeight = dpToPx(density, includeCollapsedTouchTarget
                ? HANDLE_AREA_HEIGHT_DP : TOP_HANDLE_HEIGHT_DP);
        final float expandedWidth = dpToPx(density, HANDLE_MENU_WIDTH_DP);
        final float expandedHeight = dpToPx(density, HANDLE_MENU_HEIGHT_DP);
        final float collapsedCenterX = momentBounds.left + displayedCornerRadius
                + visualCollapsedWidth / 2f;
        final float expandedCenterX = momentBounds.exactCenterX();
        final float collapsedCenterY = includeCollapsedTouchTarget
                ? momentBounds.top - collapsedHeight / 2f
                : momentBounds.top + collapsedHeight / 2f;
        final float expandedCenterY = momentBounds.top
                - dpToPx(density, HANDLE_MENU_GAP_DP) - expandedHeight / 2f;
        final float width = lerp(collapsedWidth, expandedWidth, shapeProgress);
        final float height = lerp(collapsedHeight, expandedHeight, shapeProgress);
        final float centerX = lerp(collapsedCenterX, expandedCenterX, shapeProgress);
        final float centerY = lerp(collapsedCenterY, expandedCenterY, shapeProgress);
        outBounds.set(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f);
    }

    static float getDisplayedCornerRadius(Task task, Rect momentBounds, float density) {
        final Rect taskBounds = task.getBounds();
        if (taskBounds.isEmpty()) {
            return getCornerRadius(density);
        }
        final float scaleX = (float) momentBounds.width() / taskBounds.width();
        final float scaleY = (float) momentBounds.height() / taskBounds.height();
        return getCornerRadius(density) * Math.min(scaleX, scaleY);
    }

    static void evaluateCloseMorph(Rect startBounds, float taskScale, float density,
            float progress, float effects, MorphFrame outFrame) {
        progress = MomentMotion.clamp(progress);
        final float morphProgress = Math.min(1f, progress / CLOSE_MORPH_FRACTION);
        final float shrinkProgress = progress <= CLOSE_MORPH_FRACTION ? 0f
                : (progress - CLOSE_MORPH_FRACTION) / (1f - CLOSE_MORPH_FRACTION);
        final float targetWidth = Math.max(1f, startBounds.width() / 2f);
        final float targetHeight = getBottomHandleHeight(startBounds.width(), density);
        final float targetCenterX = startBounds.exactCenterX();
        final float targetCenterY = startBounds.bottom
                + dpToPx(density, HANDLE_AREA_HEIGHT_DP) / 2f;
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

    static void evaluateOpenMorph(Rect endBounds, float taskScale, float density,
            float spatial, float effects, MorphFrame outFrame) {
        final float startHeight = getBottomHandleHeight(endBounds.width(), density);
        final float startWidth = endBounds.width() / 2f;
        outFrame.centerX = endBounds.exactCenterX();
        outFrame.centerY = lerp(endBounds.bottom
                + dpToPx(density, HANDLE_AREA_HEIGHT_DP) / 2f,
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
