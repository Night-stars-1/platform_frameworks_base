/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.List;

import javax.inject.Inject;

/**
 * Owns the native island window.
 *
 * <p>The window is a {@link WindowManager.LayoutParams#TYPE_STATUS_BAR_ADDITIONAL} window, which
 * sits below the notification shade and above the status bar. It spans the display width and wraps
 * its content vertically. The touchable region covers only the visible island, and the camera area
 * inside the pill is excluded from it, so input outside the pill is never blocked.
 *
 * <p>The window never moves sideways. The view slides its own content so the reserved cutout
 * <em>gap</em>, not the view centre, sits on the physical cutout: two activities, an asymmetric
 * pill or a wider card all move the gap away from the centre of the content. A window whose x
 * followed the content width would be repositioned one frame after each resize, which shows as a
 * sideways jump while the island appears, disappears or changes text.
 *
 * <p>When the primary activity shows an expanded card the reserved gap is gone and the card is
 * centred on the display instead, and the window is dropped below the status bar / cutout safe area
 * by a small margin. This is true for the navigation card and for the generic wide card alike. The
 * safe area is read from the display's window metrics, and from the framework status bar height and
 * the measured cutout bottom otherwise, so the card never hugs the camera on any display.
 *
 * <p>Expanding or collapsing a card is a continuous morph rather than two states that fade into each
 * other. For the duration of that transition the window becomes a fixed envelope that covers both
 * endpoints and stays anchored on the compact position, so the pill keeps wrapping the cutout while
 * the view draws the travelling shape and offsets the card itself. The envelope is applied once when
 * the morph starts and released once it settles: the animation never calls updateViewLayout per frame,
 * and no transparent envelope is left behind at either endpoint. A card that grows during the morph
 * changes the envelope height, which the position cache follows without any per-frame work.
 *
 * <p>The window is only attached while the island has something to show, which keeps the component
 * completely idle (no layout, no draw, no timer) when it is hidden. {@link #hideImmediately()} is
 * used whenever the reason for hiding is a security gate, so no fade can keep content on screen.
 */
@SysUISingleton
public final class IslandWindow {

    private static final String TAG = "IslandWindow";
    private static final String WINDOW_TITLE = "Island";
    private static final long DETACH_DELAY_MS = 260L;
    /** Gap between the status bar / cutout safe area and an expanded card. */
    private static final int CARD_TOP_MARGIN_DP = 8;

    private final Context mWindowContext;
    private final Display mDisplay;
    private final WindowManager mWindowManager;
    private final Handler mMainHandler;
    private final IslandContentView mView;
    private final int mCardTopMarginPx;

    private final Runnable mDetachRunnable = this::detach;

    private boolean mAttached;
    private int mDisplayWidth;
    private int mCutoutCenterX;
    private int mCutoutBottom;
    private int mWindowY;
    private int mAppliedX = Integer.MIN_VALUE;
    private int mAppliedY = Integer.MIN_VALUE;
    private int mAppliedWidth = Integer.MIN_VALUE;
    /**
     * Window height last handed to the window manager: either the morph envelope or
     * {@link WindowManager.LayoutParams#WRAP_CONTENT}. A card that gains a row or an action changes
     * the envelope, so the height is part of the change detection instead of being derived per frame.
     */
    private int mAppliedHeight = Integer.MIN_VALUE;
    /** True while the window carries the morph envelope instead of wrapping the visible island. */
    private boolean mEnvelopeApplied;

    private int mStatusBarHeightId = -1;
    /**
     * Expanded window y that was last handed to the view. The settle uses it so the card lands
     * exactly where the morph ended, even if the safe area was read from a fallback when the
     * transition started.
     */
    private int mExpandedWindowY = Integer.MIN_VALUE;

    @Inject
    public IslandWindow(@Application Context context, @Main Handler mainHandler) {
        mMainHandler = mainHandler;
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        Display display = displayManager == null
                ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        mDisplay = display;
        // A display context keeps the resources, metrics and window manager bound to the display the
        // island is drawn on.
        mWindowContext = display == null ? context : context.createDisplayContext(display);
        mWindowManager = mWindowContext.getSystemService(WindowManager.class);
        mCardTopMarginPx = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, CARD_TOP_MARGIN_DP,
                mWindowContext.getResources().getDisplayMetrics()));
        mView = new IslandContentView(mWindowContext);
        mView.setVisibility(View.INVISIBLE);
        mView.getViewTreeObserver().addOnComputeInternalInsetsListener(this::computeTouchableInsets);
        mView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> applyWindowPosition());
        // The morph reports its own end so the envelope is released in the same frame the endpoint
        // layout is restored, which keeps the transition from jumping on its last frame.
        mView.setMorphListener(this::applyWindowPosition);
    }

    /** The rendered island content. Exposed for the rendering harness and for tests. */
    public IslandContentView getView() {
        return mView;
    }

    public boolean isShowing() {
        return mAttached;
    }

    public void setIconResolver(IslandContentView.IconResolver resolver) {
        mView.setIconResolver(resolver);
    }

    public void setListener(IslandContentView.Listener listener) {
        mView.setListener(listener);
    }

    /** Replaces the rendered activities and the expanded state. */
    public void setContent(List<IslandModel> activities, boolean expanded) {
        // The morph needs the screen geometry before the expansion is applied, so the transition can
        // start from the real compact bounds on this very frame.
        pushCardMorphMetrics();
        mView.setActivities(activities);
        mView.setExpanded(expanded);
        // The primary template decides whether the navigation card replaces the compact pill, which
        // changes both the vertical safe-area placement and the horizontal centring, so the window
        // position has to be re-evaluated on every content update (expand, collapse, new template).
        // While the morph runs this applies the envelope instead of either endpoint.
        applyWindowPosition();
    }

    /**
     * Hands the view the fixed geometry the card morph interpolates between: the display width, the
     * cutout centre, the vertical distance from the compact window position to the expanded card and
     * the bottom of the physical cutout in the same coordinates. The view drops an unchanged set,
     * and nothing here runs per frame.
     */
    private void pushCardMorphMetrics() {
        mExpandedWindowY = cardSafeAreaBottom() + mCardTopMarginPx;
        mView.setCardMorphMetrics(mDisplayWidth, mCutoutCenterX, mExpandedWindowY - mWindowY,
                mCutoutBottom - mWindowY);
    }

    /** True when the expanded card of the primary activity has content to show. */
    public boolean canExpand() {
        return mView.canExpand();
    }

    /** Refreshes running timers only. Never rebuilds or re-parses anything. */
    public void updateTimers(long wallNow) {
        mView.updateTimers(wallNow);
    }

    /** Attaches the window (if needed) and fades the island in. */
    public void show() {
        mMainHandler.removeCallbacks(mDetachRunnable);
        updateGeometry();
        int x = computeWindowX();
        int y = computeWindowY();
        if (!mAttached) {
            try {
                boolean envelope = mView.isCardMorphRunning();
                int height = envelope ? mView.getCardMorphEnvelopeHeight()
                        : WindowManager.LayoutParams.WRAP_CONTENT;
                mWindowManager.addView(mView, createLayoutParams(x, y, height));
                mAttached = true;
                mAppliedX = x;
                mAppliedY = y;
                mAppliedWidth = mDisplayWidth;
                mAppliedHeight = height;
                mEnvelopeApplied = envelope;
            } catch (Exception e) {
                Log.w(TAG, "Unable to attach the island window", e);
                return;
            }
        } else {
            // Geometry only changes on rotation, display changes and morph boundaries. Horizontal
            // placement never changes; the window height still follows the content.
            applyPosition(x, y);
        }
        mView.setVisible(true);
    }

    /** Fades the island out and detaches the window once the animation is done. */
    public void hide() {
        if (!mAttached) {
            return;
        }
        mView.setVisible(false);
        mMainHandler.removeCallbacks(mDetachRunnable);
        mMainHandler.postDelayed(mDetachRunnable, DETACH_DELAY_MS);
    }

    /**
     * Detaches the window on this frame, with no fade. Used for keyguard, user switches and any
     * other security gate where lingering content would be a disclosure.
     */
    public void hideImmediately() {
        mMainHandler.removeCallbacks(mDetachRunnable);
        detach();
    }

    /** Recomputes the cutout geometry, for example after a rotation or a display change. */
    public void updateGeometry() {
        DisplayMetrics metrics = mWindowContext.getResources().getDisplayMetrics();
        mDisplayWidth = metrics.widthPixels;
        // Fall back to a centred pill: never leave the window at the left edge.
        int centerX = mDisplayWidth / 2;
        int gap = 0;
        int top = 0;
        int cutoutHeight = 0;
        try {
            DisplayCutout cutout = mDisplay == null ? null : mDisplay.getCutout();
            if (cutout != null && !cutout.getBoundingRects().isEmpty()) {
                Rect bounds = cutout.getBoundingRects().get(0);
                gap = bounds.width();
                centerX = bounds.centerX();
                top = bounds.top;
                cutoutHeight = bounds.height();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read the display cutout", e);
        }
        mView.setViewportWidth(mDisplayWidth);
        mView.setCutoutGap(gap);
        mCutoutCenterX = centerX;
        mCutoutBottom = top + cutoutHeight;
        // Compact placement keeps the original coordinates; computeWindowY() overrides them while the
        // expanded navigation card is showing.
        mWindowY = top + Math.max(0, (cutoutHeight - mView.getCompactHeight()) / 2);
        pushCardMorphMetrics();
        applyWindowPosition();
    }

    /** The window is display wide and always at the left edge; the view aligns its own content. */
    private int computeWindowX() {
        return 0;
    }

    /**
     * Re-applies the position after a layout that changed the measured geometry, and after the morph
     * reported that it settled. Only a changed position or a changed envelope triggers
     * {@code updateViewLayout}, so the resulting layout pass terminates.
     */
    private void applyWindowPosition() {
        if (!mAttached) {
            return;
        }
        applyPosition(computeWindowX(), computeWindowY());
    }

    /**
     * Vertical window offset for the current mode.
     *
     * <p>Compact: the pill stays centred on the cutout, exactly as before. Any expanded card
     * (navigation or generic): the card is taller and the reserved gap is gone, so it is placed
     * below the status bar / cutout safe area with a small margin instead of hugging the camera.
     */
    private int computeWindowY() {
        if (mView.isCardMorphRunning()) {
            // The envelope stays on the compact base for the whole transition: the view draws the
            // card's offset itself, so the window never jumps to the expanded position mid morph.
            return mWindowY;
        }
        if (!mView.isCardExpanded()) {
            return mWindowY;
        }
        return expandedWindowY();
    }

    /**
     * Screen y of the expanded card: the value the view was given, so releasing the envelope lands
     * exactly on the geometry the morph drew, and the live safe area until one was pushed.
     */
    private int expandedWindowY() {
        return mExpandedWindowY != Integer.MIN_VALUE
                ? mExpandedWindowY
                : cardSafeAreaBottom() + mCardTopMarginPx;
    }

    /**
     * Bottom of the status bar / cutout safe area in screen coordinates.
     *
     * <p>The insets come from the display's window metrics, not from the island's own window. Once
     * the card is placed below the status bar, the island window no longer overlaps it and reports a
     * top inset of 0; reading that window would move the card down on the next content update.
     * The framework status bar height and the compact pill height are only fallbacks. The physical
     * cutout bottom is always honoured, so the card never covers the camera.
     */
    private int cardSafeAreaBottom() {
        int top = 0;
        try {
            top = mWindowManager.getCurrentWindowMetrics().getWindowInsets().getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read the status bar inset", e);
        }
        if (top <= 0) {
            top = statusBarHeight();
        }
        if (top <= 0) {
            top = mView.getCompactHeight();
        }
        return Math.max(top, mCutoutBottom);
    }

    /**
     * Framework status bar height, or 0 when the resource is unavailable. The resource id is
     * resolved once and cached, so the layout path never repeats a name lookup.
     */
    private int statusBarHeight() {
        if (mStatusBarHeightId < 0) {
            mStatusBarHeightId = mWindowContext.getResources()
                    .getIdentifier("status_bar_height", "dimen", "android");
        }
        return mStatusBarHeightId > 0
                ? mWindowContext.getResources().getDimensionPixelSize(mStatusBarHeightId) : 0;
    }

    private void applyPosition(int x, int y) {
        boolean envelope = mView.isCardMorphRunning();
        // The envelope height is part of the change detection: a card that gains a row or an action
        // while the morph runs must grow the window, and an unchanged one must not touch the window
        // manager at all, so the resulting layout pass terminates.
        int height = envelope ? mView.getCardMorphEnvelopeHeight()
                : WindowManager.LayoutParams.WRAP_CONTENT;
        if (x == mAppliedX && y == mAppliedY && mDisplayWidth == mAppliedWidth
                && height == mAppliedHeight && envelope == mEnvelopeApplied) {
            return;
        }
        try {
            mWindowManager.updateViewLayout(mView, createLayoutParams(x, y, height));
            mAppliedX = x;
            mAppliedY = y;
            mAppliedWidth = mDisplayWidth;
            mAppliedHeight = height;
            mEnvelopeApplied = envelope;
        } catch (Exception e) {
            Log.w(TAG, "Unable to update the island window", e);
        }
    }

    private void detach() {
        if (!mAttached) {
            return;
        }
        mAttached = false;
        mAppliedX = Integer.MIN_VALUE;
        mAppliedY = Integer.MIN_VALUE;
        mAppliedWidth = Integer.MIN_VALUE;
        mAppliedHeight = Integer.MIN_VALUE;
        mEnvelopeApplied = false;
        try {
            mWindowManager.removeViewImmediate(mView);
        } catch (Exception e) {
            Log.w(TAG, "Unable to detach the island window", e);
        }
        // The view outlives the window, so its transient animation state must be reset.
        mView.resetAppearance();
    }

    /**
     * Builds the window parameters for one exact height. The caller passes the height it caches, so
     * measuring the card and touching the window manager never happen more than once per change.
     */
    private WindowManager.LayoutParams createLayoutParams(int x, int y, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                mDisplayWidth,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        params.setTitle(WINDOW_TITLE);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = x;
        params.y = y;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        params.accessibilityTitle = WINDOW_TITLE;
        return params;
    }

    /**
     * Restricts touch delivery to the pill, the small activity and the expanded card. Without this
     * the window would swallow every touch that lands on the reserved cutout gap.
     */
    private void computeTouchableInsets(ViewTreeObserver.InternalInsetsInfo info) {
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(mView.getTouchableRegion());
    }
}
