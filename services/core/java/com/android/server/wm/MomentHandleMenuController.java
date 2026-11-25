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

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.server.wm.MomentGeometry.HANDLE_MENU_GAP_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_HEIGHT_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_WIDTH_DP;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.android.internal.dynamicanimation.animation.SpringAnimation;

final class MomentHandleMenuController {

    private final ViewGroup mHost;
    private final Runnable mVisualChangedCallback;
    private final ImageButton mFullscreenButton;
    private final ImageButton mCloseButton;
    private final ImageButton mLightweightButton;
    private final boolean mAvailable;

    private SpringAnimation mAnimator;
    private SpringAnimation mIconAnimator;
    private boolean mExpanded;
    private float mProgress;
    private float mIconProgress;

    MomentHandleMenuController(ViewGroup host, Runnable fullscreenAction, Runnable closeAction,
            Runnable lightweightAction, Runnable visualChangedCallback) {
        mHost = host;
        mVisualChangedCallback = visualChangedCallback;
        final Context context = host.getContext();
        final Context shellResources = getShellResourceContext(context);
        final Drawable fullscreenIcon = loadExistingWmDrawable(shellResources,
                "desktop_mode_ic_handle_menu_fullscreen");
        final Drawable closeIcon = loadExistingWmDrawable(shellResources,
                "desktop_mode_header_ic_close");
        final Drawable lightweightIcon = loadExistingWmDrawable(shellResources,
                "desktop_mode_ic_handle_menu_floating");
        mAvailable = fullscreenIcon != null && closeIcon != null && lightweightIcon != null;
        mFullscreenButton = createButton(context, fullscreenIcon, "fullscreen", fullscreenAction);
        mCloseButton = createButton(context, closeIcon, "close", closeAction);
        mLightweightButton = createButton(context, lightweightIcon, "lightweight",
                lightweightAction);
        host.addView(mFullscreenButton);
        host.addView(mCloseButton);
        host.addView(mLightweightButton);
    }

    boolean isExpanded() {
        return mExpanded;
    }

    float getShapeProgress() {
        return Math.max(0f, mProgress);
    }

    void toggle() {
        if (mAvailable) {
            animate(!mExpanded);
        }
    }

    void collapse() {
        if (mExpanded || mProgress != 0f) {
            animate(false);
        }
    }

    void collapseImmediately() {
        setExpandedImmediately(false);
    }

    void destroy() {
        cancelAnimation();
    }

    void measure(int widthMeasureSpec, int heightMeasureSpec) {
        final int buttonWidth = dpToPx(HANDLE_MENU_WIDTH_DP) / 3;
        final int buttonHeight = dpToPx(HANDLE_MENU_HEIGHT_DP);
        final int childWidthSpec = View.MeasureSpec.makeMeasureSpec(
                buttonWidth, View.MeasureSpec.EXACTLY);
        final int childHeightSpec = View.MeasureSpec.makeMeasureSpec(
                buttonHeight, View.MeasureSpec.EXACTLY);
        mFullscreenButton.measure(childWidthSpec, childHeightSpec);
        mCloseButton.measure(childWidthSpec, childHeightSpec);
        mLightweightButton.measure(childWidthSpec, childHeightSpec);
    }

    void layout(int taskLeft, int taskTop, int momentWidth) {
        final int menuWidth = dpToPx(HANDLE_MENU_WIDTH_DP);
        final int menuHeight = dpToPx(HANDLE_MENU_HEIGHT_DP);
        final int menuLeft = taskLeft + (momentWidth - menuWidth) / 2;
        final int menuTop = taskTop - dpToPx(HANDLE_MENU_GAP_DP) - menuHeight;
        final int buttonWidth = menuWidth / 3;
        layoutButton(mFullscreenButton, menuLeft, menuTop, buttonWidth, menuHeight);
        layoutButton(mCloseButton, menuLeft + buttonWidth, menuTop, buttonWidth, menuHeight);
        layoutButton(mLightweightButton, menuLeft + buttonWidth * 2, menuTop,
                menuWidth - buttonWidth * 2, menuHeight);
    }

    private void animate(boolean expanded) {
        mExpanded = expanded;
        if (expanded) {
            setButtonsVisible(true);
        }
        updateButtons();
        final float target = expanded ? 1f : 0f;
        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.animateToFinalPosition(target);
        } else {
            final SpringAnimation animator = MomentMotion.spring(mProgress, target, 0f,
                    MomentMotion.DEFAULT_SPATIAL, 0.001f);
            mAnimator = animator;
            animator.addUpdateListener((animation, value, velocity) -> {
                mProgress = value;
                updateButtons();
                notifyVisualChanged();
            });
            animator.addEndListener((animation, cancelled, value, velocity) -> {
                if (mAnimator == animation) {
                    mAnimator = null;
                }
                if (!cancelled && !mExpanded) {
                    setButtonsVisible(false);
                }
            });
            animator.start();
        }
        if (mIconAnimator != null && mIconAnimator.isRunning()) {
            mIconAnimator.animateToFinalPosition(target);
        } else {
            final SpringAnimation icons = MomentMotion.spring(mIconProgress, target, 0f,
                    MomentMotion.FAST_EFFECTS, 0.001f);
            mIconAnimator = icons;
            icons.addUpdateListener((animation, value, velocity) -> {
                mIconProgress = MomentMotion.clamp(value);
                updateButtons();
            });
            icons.addEndListener((animation, cancelled, value, velocity) -> {
                if (mIconAnimator == animation) {
                    mIconAnimator = null;
                }
            });
            icons.start();
        }
        mHost.requestLayout();
    }

    private void setExpandedImmediately(boolean expanded) {
        cancelAnimation();
        mExpanded = expanded;
        mProgress = expanded ? 1f : 0f;
        mIconProgress = mProgress;
        setButtonsVisible(expanded);
        updateButtons();
        notifyVisualChanged();
        mHost.requestLayout();
    }

    private void cancelAnimation() {
        final SpringAnimation animator = mAnimator;
        mAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        final SpringAnimation icons = mIconAnimator;
        mIconAnimator = null;
        if (icons != null) {
            icons.cancel();
        }
    }

    private void updateButtons() {
        // Effects have their own clock; visibility is additionally gated by the space actually
        // available in the container so icons cannot appear outside the expanding handle.
        final float iconProgress = Math.min(mIconProgress,
                MomentMotion.clamp((mProgress - 0.35f) / 0.65f));
        mFullscreenButton.setAlpha(iconProgress);
        mCloseButton.setAlpha(iconProgress);
        mLightweightButton.setAlpha(iconProgress);
        final boolean clickable = mExpanded && iconProgress >= 0.99f;
        mFullscreenButton.setClickable(clickable);
        mCloseButton.setClickable(clickable);
        mLightweightButton.setClickable(clickable);
    }

    private void setButtonsVisible(boolean visible) {
        final int visibility = visible ? VISIBLE : INVISIBLE;
        mFullscreenButton.setVisibility(visibility);
        mCloseButton.setVisibility(visibility);
        mLightweightButton.setVisibility(visibility);
    }

    private ImageButton createButton(Context context, Drawable icon, String description,
            Runnable action) {
        final ImageButton button = new ImageButton(context);
        button.setImageDrawable(icon);
        button.setImageTintList(ColorStateList.valueOf(Color.rgb(28, 28, 20)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        final TypedValue selectableBackground = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground, true)) {
            button.setBackgroundResource(selectableBackground.resourceId);
        } else {
            button.setBackground(null);
        }
        button.setVisibility(INVISIBLE);
        button.setOnClickListener(clicked -> {
            setExpandedImmediately(false);
            action.run();
        });
        return button;
    }

    private Context getShellResourceContext(Context context) {
        try {
            return context.createPackageContext("com.android.systemui", 0);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e("Moment", "SystemUI resources are unavailable", e);
            return null;
        }
    }

    private Drawable loadExistingWmDrawable(Context shellResources, String drawableName) {
        if (shellResources == null) {
            return null;
        }
        final int drawableId = shellResources.getResources().getIdentifier(
                drawableName, "drawable", shellResources.getPackageName());
        if (drawableId == 0) {
            Slog.e("Moment", "Missing existing WM drawable " + drawableName);
            return null;
        }
        return shellResources.getDrawable(drawableId);
    }

    private void notifyVisualChanged() {
        mVisualChangedCallback.run();
        mHost.invalidate();
    }

    private void layoutButton(View button, int left, int top, int width, int height) {
        button.layout(left, top, left + width, top + height);
    }

    private int dpToPx(int dp) {
        return (int) (dp * mHost.getResources().getDisplayMetrics().density + 0.5f);
    }
}
