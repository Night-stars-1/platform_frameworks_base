/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders the island content: the compact pill that wraps the physical display cutout, the small
 * secondary activity, and the expanded card.
 *
 * <p>The view is deliberately free of window handling. It is a plain {@link LinearLayout} so that
 * the production window and a rendering harness can both drive it, and so that a tick can refresh
 * running timers without rebuilding anything.
 *
 * <p>All rendering happens on the main thread. {@link Icon} objects are never decoded here: the
 * caller installs an {@link IconResolver} that returns an already decoded drawable (or null), which
 * keeps bitmap work off the main thread and off the tick path.
 */
public class IslandContentView extends LinearLayout {

    /** Callbacks raised from user input on the island. */
    public interface Listener {
        void onIslandClicked(IslandModel model);

        void onIslandLongPressed(IslandModel model);

        void onIslandActionClicked(IslandModel model, IslandModel.Action action);

        void onIslandDismissed(IslandModel model);

        /** A tap landed outside the expanded card. The compact pill does not use this. */
        void onOutsideTouched();
    }

    /** Supplies already decoded drawables for model icons. Returns null when unavailable. */
    public interface IconResolver {
        Drawable resolve(Icon icon);
    }

    /**
     * Notified when the card morph reaches one of its endpoints, so the window can drop the
     * envelope it applied for the transition in the same frame the endpoint layout is restored.
     */
    interface MorphListener {
        void onCardMorphSettled();
    }

    private static final int MAX_ACTION_BUTTONS = 3;

    private static final long ANIM_DURATION_MS = 220L;
    /** Scale at which the pill is born and into which it leaves. The pivot is the cutout gap. */
    private static final float APPEARANCE_SCALE = 0.64f;

    /**
     * Duration of the continuous pill -> expanded card morph, shared by every card template. Longer
     * than the plain card fade because the shape itself travels; the easing is the same curve the
     * other island animations use.
     */
    private static final long CARD_MORPH_DURATION_MS = 300L;

    /**
     * Reserved at each end of the status bar for the clock, the privacy chip and the system icons.
     * Island content must never be laid out into this area, whatever text a client sends.
     */
    private static final int STATUS_BAR_END_RESERVED_DP = 72;

    /** Absolute cap for one text region, regardless of how much width is free. */
    private static final int MAX_TEXT_REGION_DP = 132;

    /**
     * Floor for each half of the compact pill around the cutout, so a short activity (an icon and
     * a few characters) still reads as an island rather than a sliver. Never wider than the room
     * left between the reserved status bar ends.
     */
    private static final int MIN_PILL_SIDE_DP = 64;

    /**
     * Legacy protocol 1 scene used by navigation clients: the turn instruction arrives as the part
     * title, the guidance state as its content and the direction arrow as its icon.
     */
    private static final String NAVIGATION_COMPONENT = "templateRevertProgressScene";

    /** Corner radius of the expanded card, shared by every card template. */
    private static final int CARD_RADIUS_DP = 28;

    /** Margin kept on each side of the expanded card inside the viewport. */
    private static final int CARD_MARGIN_DP = 16;

    private static final int CARD_MIN_HEIGHT_DP = 84;

    private static final int CARD_PADDING_H_DP = 18;

    private static final int CARD_PADDING_V_DP = 16;

    /** Edge of the direction glyph in the expanded navigation card. */
    private static final int NAV_CARD_ARROW_DP = 44;

    private static final float NAV_COMPACT_TITLE_SP = 14f;

    /** Readable type of the expanded card, shared by the title of both card templates and its body. */
    private static final float CARD_TITLE_SP = 18f;

    private static final float CARD_TEXT_SP = 14f;

    private static final int PILL_COLOR = 0xFF000000;

    private static final int CARD_COLOR_NAV = 0xFF262626;

    /**
     * The generic card keeps the pill's surface colour, so the travelling morph shape never changes
     * colour on its way to the expanded endpoint.
     */
    private static final int CARD_COLOR_GENERIC = 0xFF000000;

    private static final int NAV_CARD_SUBTITLE_COLOR = 0xFFD0D0D0;

    /** One direction-icon slot per navigation ImageView; the two never share a drawable. */
    private static final int NAV_ARROW_COMPACT = 0;
    private static final int NAV_ARROW_CARD = 1;
    private static final int NAV_ARROW_SLOTS = 2;


    private final int mGapHeight;
    private final int mPillHeight;
    private final int mSmallHeight;
    private final int mIconSize;
    private final int mSmallIconSize;
    private final int mPillPadding;
    /** Text width available to one side of the pill, computed from the real viewport. */
    private int mTextBudget;
    /** Viewport width in pixels, reported by the window; 0 until then. */
    private int mViewportWidth;

    private final LinearLayout mRow;
    private final LinearLayout mLeft;
    private final View mGap;
    private final LinearLayout mRight;
    private final LinearLayout mSmallRow;
    private final LinearLayout mCard;
    private final LinearLayout mCardHeader;
    private final ImageView mCardIcon;
    private final TextView mCardTitle;
    private final TextView mCardTimer;
    private final TextView mCardContent;
    private final ProgressBar mCardProgress;
    private final LinearLayout mCardParts;
    private final LinearLayout mCardActions;

    // Navigation (legacy protocol 1) rendering. The views are created once and re-populated in
    // place, so a navigation update never builds a new view tree and never re-decodes an icon.
    private final ImageView mNavCompactArrow;
    private final TextView mNavCompactTitle;
    private final LinearLayout mNavCard;
    private final TextView mNavCardTitle;
    private final TextView mNavCardSubtitle;
    private final ImageView mNavCardArrow;
    /**
     * The two endpoint backgrounds, kept so the morph can take them out of the way while it paints
     * the single travelling shape and put them back when it settles. The generic card keeps its own
     * instance because the morph never draws a second shape for it.
     */
    private final GradientDrawable mPillBackground;
    private final GradientDrawable mCardBackground;
    private final GradientDrawable mNavCardBackground;

    /**
     * Last direction icon resolved for each navigation ImageView, plus the drawable handed to that
     * view. A rebuild that carries the same icon instance reuses both, and every ImageView keeps
     * its own drawable instance as the renderer requires.
     */
    private final Icon[] mNavArrowSources = new Icon[NAV_ARROW_SLOTS];
    private final Drawable[] mNavArrowDrawables = new Drawable[NAV_ARROW_SLOTS];

    private final List<IslandModel> mActivities = new ArrayList<>();
    private final List<TextView> mTimerViews = new ArrayList<>();

    /** Reused by the tick path so refreshing timers never allocates. */
    private final char[] mTimeBuffer = new char[16];

    private Listener mListener;
    private IconResolver mIconResolver;
    private boolean mExpanded;
    private int mGapWidth;
    private boolean mVisible;
    private ValueAnimator mAppearanceAnimator;
    private ValueAnimator mCardAnimator;
    private MorphListener mMorphListener;

    /**
     * True while the card morph owns the layout, the background and the touch region. Both
     * endpoints stay laid out for that whole window, so nothing about it is rebuilt per frame.
     */
    private boolean mCardMorphActive;
    /** Which expanded endpoint the running morph was started for; a template switch never reuses it. */
    private boolean mCardMorphNavigation;
    /** 0 = compact pill, 1 = expanded card; only meaningful while the morph is active. */
    private float mCardMorphFraction;
    private ValueAnimator mCardMorphAnimator;
    /** Screen geometry the morph interpolates between; supplied by the window, never per frame. */
    private int mMorphDisplayWidth;
    private int mMorphCutoutCenterX;
    private int mMorphExpandedTop;
    /** Bottom of the physical cutout in this view's coordinates; 0 without a cutout. */
    private int mMorphCutoutBottom;
    /** Endpoint and current background rectangles, reused so no frame allocates a shape. */
    private final RectF mMorphCompactRect = new RectF();
    private final RectF mMorphCardRect = new RectF();
    private final RectF mMorphRect = new RectF();
    private final Rect mMorphScratchRect = new Rect();
    private final Path mMorphPath = new Path();
    private final Paint mMorphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** True while children are temporarily clipped to the travelling morph shape. */
    private boolean mCardMorphClipping;
    private float mMorphCompactRadius;
    private float mMorphCardRadius;
    /** False until a layout pass has placed both endpoints inside the morph envelope. */
    private boolean mMorphGeometryReady;
    /** Endpoint anchors of the morphing content, in this view's coordinates. */
    private final RectF mCompactArrowRect = new RectF();
    private final RectF mCardArrowRect = new RectF();
    private final RectF mCompactTitleRect = new RectF();
    private final RectF mCardTitleRect = new RectF();
    private final RectF mCardSubtitleRect = new RectF();
    /** Reused by the touch region path so no frame allocates a Region or a Rect. */
    private final Region mTouchRegion = new Region();
    private final Region mTouchAddedRegion = new Region();
    private final Region mTouchClipRegion = new Region();
    private final Rect mTouchHoleRect = new Rect();

    private IslandModel mPrimary;
    private IslandModel mSecondary;
    private float mTouchDownX;
    private float mTouchDownY;
    private boolean mLongPressFired;
    private boolean mDragCancelled;
    private IslandModel mLongPressModel;
    private final Runnable mLongPressRunnable = () -> {
        mLongPressFired = true;
        if (mListener != null && mLongPressModel != null) {
            mListener.onIslandLongPressed(mLongPressModel);
        }
    };
    private final int mTouchSlopPx;
    private final int mDismissThresholdPx;

    public IslandContentView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        mGapHeight = dp(6);
        mPillHeight = dp(30);
        mSmallHeight = dp(24);
        mIconSize = dp(18);
        mSmallIconSize = dp(14);
        mPillPadding = dp(10);

        mRow = new LinearLayout(context);
        mRow.setOrientation(HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mLeft = new LinearLayout(context);
        mLeft.setOrientation(HORIZONTAL);
        mLeft.setGravity(Gravity.CENTER_VERTICAL);
        mGap = new View(context);
        mRight = new LinearLayout(context);
        mRight.setOrientation(HORIZONTAL);
        mRight.setGravity(Gravity.CENTER_VERTICAL);
        mRow.addView(mLeft);
        mRow.addView(mGap, new LayoutParams(0, mGapHeight));
        mRow.addView(mRight);
        addView(mRow, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSmallRow = new LinearLayout(context);
        mSmallRow.setOrientation(HORIZONTAL);
        mSmallRow.setGravity(Gravity.CENTER_VERTICAL);
        mSmallRow.setVisibility(GONE);
        addView(mSmallRow, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mSmallHeight));

        mCard = new LinearLayout(context);
        mCard.setOrientation(VERTICAL);
        mCard.setVisibility(GONE);
        addView(mCard, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mCardHeader = new LinearLayout(context);
        mCardHeader.setOrientation(HORIZONTAL);
        mCardHeader.setGravity(Gravity.CENTER_VERTICAL);
        mCardIcon = new ImageView(context);
        mCardHeader.addView(mCardIcon, new LayoutParams(dp(22), dp(22)));
        mCardTitle = new TextView(context);
        mCardTitle.setSingleLine(true);
        mCardTitle.setEllipsize(TextUtils.TruncateAt.END);
        mCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TITLE_SP);
        mCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams =
                new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        mCardHeader.addView(mCardTitle, titleParams);
        mCardTimer = new TextView(context);
        mCardTimer.setSingleLine(true);
        mCardTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP);
        mCardHeader.addView(mCardTimer);
        mCard.addView(mCardHeader);

        mCardContent = new TextView(context);
        mCardContent.setMaxLines(3);
        mCardContent.setEllipsize(TextUtils.TruncateAt.END);
        mCardContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP);
        LinearLayout.LayoutParams contentParams =
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(4);
        mCard.addView(mCardContent, contentParams);

        mCardProgress = new ProgressBar(
                context, null, android.R.attr.progressBarStyleHorizontal);
        mCardProgress.setMax(100);
        LinearLayout.LayoutParams progressParams =
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.topMargin = dp(6);
        mCard.addView(mCardProgress, progressParams);

        mCardParts = new LinearLayout(context);
        mCardParts.setOrientation(VERTICAL);
        mCard.addView(mCardParts);

        mCardActions = new LinearLayout(context);
        mCardActions.setOrientation(HORIZONTAL);
        mCard.addView(mCardActions);

        mNavCompactArrow = new ImageView(context);
        mNavCompactArrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mNavCompactTitle = new TextView(context);
        mNavCompactTitle.setSingleLine(true);
        mNavCompactTitle.setEllipsize(TextUtils.TruncateAt.END);
        mNavCompactTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_COMPACT_TITLE_SP);
        mNavCompactTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mNavCompactTitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        mNavCard = new LinearLayout(context);
        mNavCard.setOrientation(HORIZONTAL);
        mNavCard.setGravity(Gravity.CENTER_VERTICAL);
        mNavCardBackground = roundedBackground(CARD_COLOR_NAV, dp(CARD_RADIUS_DP));
        mNavCard.setBackground(mNavCardBackground);
        mNavCard.setPadding(dp(CARD_PADDING_H_DP), dp(CARD_PADDING_V_DP),
                dp(CARD_PADDING_H_DP), dp(CARD_PADDING_V_DP));
        mNavCard.setMinimumHeight(dp(CARD_MIN_HEIGHT_DP));
        mNavCard.setVisibility(GONE);
        LinearLayout navText = new LinearLayout(context);
        navText.setOrientation(VERTICAL);
        mNavCardTitle = new TextView(context);
        mNavCardTitle.setSingleLine(true);
        mNavCardTitle.setEllipsize(TextUtils.TruncateAt.END);
        mNavCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TITLE_SP);
        mNavCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mNavCardTitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        navText.addView(mNavCardTitle, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mNavCardSubtitle = new TextView(context);
        mNavCardSubtitle.setSingleLine(true);
        mNavCardSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        mNavCardSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP);
        mNavCardSubtitle.setTextColor(NAV_CARD_SUBTITLE_COLOR);
        mNavCardSubtitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams subtitleParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);
        navText.addView(mNavCardSubtitle, subtitleParams);
        LinearLayout.LayoutParams navTextParams = new LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mNavCard.addView(navText, navTextParams);
        mNavCardArrow = new ImageView(context);
        mNavCardArrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams navArrowParams =
                new LayoutParams(dp(NAV_CARD_ARROW_DP), dp(NAV_CARD_ARROW_DP));
        navArrowParams.leftMargin = dp(12);
        mNavCard.addView(mNavCardArrow, navArrowParams);
        addView(mNavCard, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRow.setOnTouchListener(new GestureHandler(true));
        mSmallRow.setOnTouchListener(new GestureHandler(false));
        mCard.setOnTouchListener(new GestureHandler(true));
        mNavCard.setOnTouchListener(new GestureHandler(true));

        ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlopPx = configuration.getScaledTouchSlop();
        mDismissThresholdPx = dp(24);

        mCardBackground = roundedBackground(CARD_COLOR_GENERIC, dp(CARD_RADIUS_DP));
        mCard.setBackground(mCardBackground);
        mCard.setPadding(dp(CARD_PADDING_H_DP), dp(CARD_PADDING_V_DP),
                dp(CARD_PADDING_H_DP), dp(CARD_PADDING_V_DP));
        mCard.setMinimumHeight(dp(CARD_MIN_HEIGHT_DP));
        mPillBackground = roundedBackground(PILL_COLOR, mPillHeight / 2f);
        mRow.setBackground(mPillBackground);
        mSmallRow.setBackground(roundedBackground(0xFF141414, mSmallHeight / 2f));
        // The morphing content travels between the two endpoint layouts, so no island container may
        // clip its own children.
        allowOverflow(mRow);
        allowOverflow(mLeft);
        allowOverflow(mRight);
        allowOverflow(mSmallRow);
        allowOverflow(mNavCard);
        allowOverflow(navText);
        // Keep the pill radius exactly half of the measured height so the shape stays a pill for
        // any content height.
        mRow.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updatePillRadius(mRow));
        mSmallRow.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> updatePillRadius(mSmallRow));
    }

    private static void updatePillRadius(View pill) {
        int height = pill.getHeight();
        if (height > 0 && pill.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) pill.getBackground()).setCornerRadius(height / 2f);
        }
    }

    /** The morphing content travels outside its own endpoint bounds, so nothing may clip it. */
    private static void allowOverflow(ViewGroup group) {
        group.setClipChildren(false);
        group.setClipToPadding(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mLongPressRunnable);
        if (mAppearanceAnimator != null) {
            mAppearanceAnimator.cancel();
            mAppearanceAnimator = null;
        }
        if (mCardAnimator != null) {
            mCardAnimator.cancel();
            mCardAnimator = null;
        }
        // A detached view has no window to hand the transition back to, so it lands on its endpoint.
        abortCardMorph();
        super.onDetachedFromWindow();
    }

    /**
     * Keeps the cutout gap in the middle of the pill: the narrower side is widened to the wider
     * one, so a one-sided activity still wraps the camera symmetrically instead of leaving the gap
     * at one end of the pill. Both sides are also held at the minimum island width.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int side = mRow.getVisibility() == VISIBLE && mGapWidth > 0
                ? Math.max(minimumSideWidth(), Math.max(naturalWidth(mLeft), naturalWidth(mRight)))
                : 0;
        boolean changed = setMinimumWidthIfChanged(mLeft, side);
        changed |= setMinimumWidthIfChanged(mRight, side);
        if (changed) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /** {@link #MIN_PILL_SIDE_DP}, capped so the pill never reaches the reserved status bar ends. */
    private int minimumSideWidth() {
        int room = viewportWidth() - 2 * dp(STATUS_BAR_END_RESERVED_DP) - mGapWidth;
        return Math.min(dp(MIN_PILL_SIDE_DP), Math.max(0, room) / 2);
    }

    /** Width of one pill side from its padding and children, ignoring any minimum width. */
    private static int naturalWidth(LinearLayout side) {
        int width = side.getPaddingLeft() + side.getPaddingRight();
        for (int i = 0; i < side.getChildCount(); i++) {
            View child = side.getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            width += child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
        }
        return width;
    }

    private static boolean setMinimumWidthIfChanged(View view, int width) {
        if (view.getMinimumWidth() == width) {
            return false;
        }
        view.setMinimumWidth(width);
        return true;
    }

    /**
     * Places the two endpoints at their real geometry while the morph runs; every other layout keeps
     * the normal vertical flow.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mCardMorphActive) {
            layoutCardMorph();
            return;
        }
        super.onLayout(changed, left, top, right, bottom);
        alignGapWithCutout();
    }

    /**
     * The window spans the display, so the content is slid inside it until the reserved gap sits
     * on the camera. Doing it here, in the same pass that sizes the pill, means a wider or narrower
     * pill can never be drawn for a frame at the previous offset.
     */
    private void alignGapWithCutout() {
        if (mMorphDisplayWidth <= 0 || getWidth() < mMorphDisplayWidth || isCardExpanded()) {
            // An expanded card has no camera gap; the CENTER_HORIZONTAL gravity centres it.
            return;
        }
        int gap = getCutoutCenterX();
        if (gap < 0) {
            return;
        }
        int dx = mMorphCutoutCenterX - gap;
        if (dx == 0) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).offsetLeftAndRight(dx);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mCardMorphActive && mMorphGeometryReady) {
            drawCardMorphBackground(canvas);
            // The children travel between the endpoints while the shape is small. Clipping them to
            // the same path keeps content from spilling off the rounded surface.
            mCardMorphClipping = true;
            canvas.save();
            canvas.clipPath(mMorphPath);
        }
        super.dispatchDraw(canvas);
        if (mCardMorphClipping) {
            canvas.restore();
            mCardMorphClipping = false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            // The expanded card's window does not cover the screen, so a tap anywhere else arrives
            // as ACTION_OUTSIDE. Collapse only then; the compact pill must stay up.
            if (isCardExpanded() && mListener != null) {
                mListener.onOutsideTouched();
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private GradientDrawable roundedBackground(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void setIconResolver(IconResolver resolver) {
        mIconResolver = resolver;
    }

    /**
     * Reserves the horizontal space taken by the physical display cutout so the pill wraps it
     * instead of overlapping it.
     */
    public void setCutoutGap(int widthPx) {
        int clamped = Math.max(0, widthPx);
        if (mGapWidth == clamped) {
            return;
        }
        mGapWidth = clamped;
        ViewGroup.LayoutParams params = mGap.getLayoutParams();
        params.width = clamped;
        mGap.setLayoutParams(params);
        // The gap is part of the text budget, so the sections have to be measured again.
        rebuild();
    }

    /**
     * Reports the width of the display the island is drawn on. The text budget is derived from it,
     * so it has to be re-reported on rotation or a display change, but never per frame.
     */
    public void setViewportWidth(int widthPx) {
        if (widthPx <= 0 || mViewportWidth == widthPx) {
            return;
        }
        mViewportWidth = widthPx;
        rebuild();
    }

    /** Replaces the rendered activities. Index 0 is the large activity, index 1 the small one. */
    public void setActivities(List<IslandModel> activities) {
        List<IslandModel> next = activities == null ? Collections.emptyList() : activities;
        if (sameActivities(mActivities, next)) {
            return;
        }
        mActivities.clear();
        mActivities.addAll(next);
        rebuild();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    /**
     * True when the current primary activity is the legacy navigation scene and the user expanded
     * it.
     *
     * <p>Only navigation itself is described by this. The window, the touch handling and the gap
     * alignment care about any expanded card and use {@link #isCardExpanded()} instead.
     */
    public boolean isNavigationExpanded() {
        return mExpanded && isNavigation();
    }

    /**
     * True when any expanded card of the current primary activity is showing: the navigation card
     * and the generic wide card both set it. The window places the card below the safe area and the
     * pill stops aligning its gap with the cutout whenever this is true.
     */
    public boolean isCardExpanded() {
        return mExpanded && canExpand();
    }

    /** True when the expanded card has content to show for the current primary activity. */
    public boolean canExpand() {
        return isNavigation() ? navigationCardHasContent() : hasCardContent();
    }

    /** Designed height of the compact pill, used by the window to center it on the cutout. */
    public int getCompactHeight() {
        return mPillHeight;
    }

    /**
     * Horizontal centre of the reserved cutout gap in this view's coordinate space, or -1 when no
     * gap is reserved (no cutout).
     *
     * <p>The view centre is not the gap centre: the two sides of the pill, the small activity and
     * the card all have independent widths. The window uses this value to place the gap, not the
     * view, on the physical cutout.
     *
     * <p>An expanded card is the one case with no gap to align: the compact row is gone and only
     * the wide card remains, so the stale measured width of the hidden gap is never reported and
     * the window centres the view instead.
     */
    public int getCutoutCenterX() {
        if (isCardExpanded()) {
            return -1;
        }
        if (mGapWidth <= 0 || mGap.getVisibility() != VISIBLE || mGap.getWidth() <= 0) {
            return -1;
        }
        Rect rect = new Rect(0, 0, mGap.getWidth(), mGap.getHeight());
        offsetDescendantRectToMyCoords(mGap, rect);
        return rect.centerX();
    }

    /** Shows or hides the expanded card. Never rebuilds timers or icons on its own. */
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) {
            return;
        }
        mExpanded = expanded;
        if (canMorphCard()) {
            // The morph interpolates the real compact and expanded geometry, so both endpoints stay
            // built and laid out for the whole transition; the rebuild below keeps that true.
            beginCardMorph(expanded);
            rebuild();
            return;
        }
        if (mCardMorphActive) {
            // The primary can no longer be interpolated: land on the endpoint before rebuilding.
            settleCardMorph();
        }
        // A full rebuild re-collects the timer views; setActivities() is a no-op when the visible
        // activities did not change, so no icon is decoded and no model is re-parsed here.
        rebuild();
        animateCard(expanded);
    }

    /** Fades the whole island in or out. */
    public void setVisible(boolean visible) {
        if (mVisible == visible) {
            return;
        }
        mVisible = visible;
        if (mAppearanceAnimator != null) {
            mAppearanceAnimator.cancel();
            mAppearanceAnimator = null;
        }
        if (visible) {
            // Shown again while still fading out: continue from the current size instead of
            // restarting from the smallest one, which reads as the pill popping.
            float from = getVisibility() == VISIBLE ? appearanceFraction() : 0f;
            setVisibility(VISIBLE);
            applyAppearance(from);
            ValueAnimator animator = ValueAnimator.ofFloat(from, 1f);
            animator.setDuration(ANIM_DURATION_MS);
            animator.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
            animator.addUpdateListener(animation -> applyAppearance((float) animation.getAnimatedValue()));
            mAppearanceAnimator = animator;
            animator.start();
        } else {
            anchorAppearance();
            ValueAnimator animator = ValueAnimator.ofFloat(appearanceFraction(), 0f);
            animator.setDuration(ANIM_DURATION_MS);
            animator.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
            animator.addUpdateListener(animation -> applyAppearance((float) animation.getAnimatedValue()));
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!mVisible) {
                        setVisibility(INVISIBLE);
                        setScaleX(1f);
                        setScaleY(1f);
                    }
                }
            });
            mAppearanceAnimator = animator;
            animator.start();
        }
    }

    /** 1 when the pill is fully shown. Scale is the source so a reversed exit continues smoothly. */
    private float appearanceFraction() {
        float span = 1f - APPEARANCE_SCALE;
        if (span <= 0f) {
            return getAlpha();
        }
        return Math.max(0f, Math.min(1f, (getScaleX() - APPEARANCE_SCALE) / span));
    }

    /**
     * Grows or shrinks around the cutout gap, not the view centre. An asymmetric pill (arrow on one
     * side, instruction on the other) would otherwise slide sideways as the scale changes.
     */
    private void applyAppearance(float fraction) {
        anchorAppearance();
        setAlpha(fraction);
        float scale = APPEARANCE_SCALE + (1f - APPEARANCE_SCALE) * fraction;
        setScaleX(scale);
        setScaleY(scale);
    }

    /** Pivot on the reserved cutout gap once it has been laid out, otherwise the view centre. */
    private void anchorAppearance() {
        int gap = getCutoutCenterX();
        setPivotX(gap >= 0 ? gap : getWidth() / 2f);
        setPivotY(getHeight() > 0 ? getHeight() / 2f : getCompactHeight() / 2f);
    }

    public boolean isVisible() {
        return mVisible;
    }

    /**
     * Cancels transient animation state. The view outlives the window it is attached to, so it has
     * to come back to a clean state before it is attached again.
     */
    public void resetAppearance() {
        removeCallbacks(mLongPressRunnable);
        if (mAppearanceAnimator != null) {
            mAppearanceAnimator.cancel();
            mAppearanceAnimator = null;
        }
        if (mCardAnimator != null) {
            mCardAnimator.cancel();
            mCardAnimator = null;
        }
        abortCardMorph();
        mVisible = false;
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(INVISIBLE);
        mCard.setAlpha(1f);
        mCard.setTranslationY(0f);
        mNavCard.setAlpha(1f);
        mNavCard.setTranslationY(0f);
    }

    // ------------------------------------------------------------------- card morph

    void setMorphListener(MorphListener listener) {
        mMorphListener = listener;
    }

    /**
     * Supplies the fixed screen geometry the card morph interpolates between. The window calls this
     * once per display or configuration change, never per frame; every value is in pixels.
     *
     * <p>Until this is called the morph cannot run at all: the view has no way to know where the
     * expanded card belongs on screen, so expansion keeps the plain card animation.
     *
     * @param displayWidth width of the display
     * @param cutoutCenterX screen x of the physical cutout centre, or the display centre without one
     * @param expandedTopOffset screen y of the expanded card minus the compact window position
     * @param cutoutBottomOffset screen y of the physical cutout bottom minus the compact window
     *     position; 0 or less when the display has no cutout
     */
    void setCardMorphMetrics(int displayWidth, int cutoutCenterX, int expandedTopOffset,
            int cutoutBottomOffset) {
        if (displayWidth <= 0) {
            return;
        }
        int top = Math.max(0, expandedTopOffset);
        int cutoutBottom = Math.max(0, cutoutBottomOffset);
        if (mMorphDisplayWidth == displayWidth && mMorphCutoutCenterX == cutoutCenterX
                && mMorphExpandedTop == top && mMorphCutoutBottom == cutoutBottom) {
            return;
        }
        boolean hadGeometry = mMorphDisplayWidth > 0;
        mMorphDisplayWidth = displayWidth;
        mMorphCutoutCenterX = cutoutCenterX;
        mMorphExpandedTop = top;
        mMorphCutoutBottom = cutoutBottom;
        if (mCardMorphActive && hadGeometry) {
            // The display changed under a running transition: land on the endpoint the morph was
            // heading for instead of interpolating through geometry that no longer exists. The
            // first push only establishes the geometry, so it never settles a morph just started.
            settleCardMorph();
        }
    }

    /** True while the continuous pill -> card morph owns the layout, the background and input. */
    boolean isCardMorphRunning() {
        return mCardMorphActive;
    }

    /** 0 = compact pill, 1 = expanded card. Settled endpoints report exactly 0 or 1. */
    float getCardMorphFraction() {
        if (mCardMorphActive) {
            return mCardMorphFraction;
        }
        return mExpanded ? 1f : 0f;
    }

    /**
     * Bounds of the single card background in this view's coordinates: the interpolated shape while
     * the morph runs, otherwise the endpoint that is on screen. Both card templates report here.
     *
     * <p>The rectangle is view local. During the morph the window sits on the compact base, so the
     * expanded endpoint is offset by the compact-to-expanded window distance; once the morph has
     * settled the window has moved to that endpoint and the local offset is gone. The on-screen
     * position is continuous across the settle, the local one is not.
     */
    boolean getCardMorphBounds(RectF out) {
        if (mCardMorphActive) {
            if (!mMorphGeometryReady) {
                return false;
            }
            out.set(mMorphRect);
            return true;
        }
        View endpoint = isCardExpanded() ? activeCard() : mRow;
        if (endpoint.getVisibility() != VISIBLE || endpoint.getWidth() == 0) {
            return false;
        }
        Rect bounds = mMorphScratchRect;
        bounds.set(0, 0, endpoint.getWidth(), endpoint.getHeight());
        offsetDescendantRectToMyCoords(endpoint, bounds);
        out.set(bounds);
        return true;
    }

    /**
     * Height of the window envelope the morph needs: the expanded card's top offset plus its height,
     * and at least the compact lines. Only meaningful while {@link #isCardMorphRunning()}.
     */
    int getCardMorphEnvelopeHeight() {
        int compactBottom = Math.max(mPillHeight, mRow.getMeasuredHeight());
        if (mSecondary != null) {
            compactBottom += mSmallHeight;
        }
        // The laid out height is kept in the comparison: re-measuring the card must never make the
        // envelope smaller than the shape that is already on screen.
        View card = activeCard();
        int cardHeight = Math.max(measureExpandedCardHeight(), card.getHeight());
        return Math.max(mMorphExpandedTop + cardHeight, compactBottom);
    }

    /**
     * Measures the active expanded card on its own so the window can size the morph envelope before
     * the layout pass that draws the transition, and again when the card gains a row or an action.
     * Runs once per content change, never per frame.
     */
    private int measureExpandedCardHeight() {
        int width = cardWidth();
        if (width <= 0) {
            return 0;
        }
        View card = activeCard();
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return card.getMeasuredHeight();
    }

    /**
     * True when the compact pill can morph into the expanded card of the current primary: both
     * endpoints have something to show, the window reported the screen geometry and system
     * animations are enabled. Both card templates share the gate; only the endpoint view differs.
     * With animations switched off the island jumps straight to the endpoint in this frame, so no
     * envelope is ever applied.
     */
    private boolean canMorphCard() {
        if (mMorphDisplayWidth <= 0 || mMorphExpandedTop <= 0
                || !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        if (!hasCompactContent()) {
            return false;
        }
        return isNavigation() ? navigationCardHasContent() : hasCardContent();
    }

    /**
     * True when the compact pill currently carries something the morph can start from: the
     * navigation arrow/title on the navigation side, or any built pill section for a generic
     * primary (text, value, timer or icon).
     */
    private boolean hasCompactContent() {
        if (isNavigation()) {
            IslandModel.Part part = mPrimary == null ? null : mPrimary.left;
            return !TextUtils.isEmpty(labelText(part)) || directionIcon(part) != null;
        }
        return mLeft.getChildCount() > 0 || mRight.getChildCount() > 0;
    }

    /** Starts the morph, or retargets the running one when the user reverses mid transition. */
    private void beginCardMorph(boolean expand) {
        if (mCardAnimator != null) {
            // A plain fade must not keep writing alpha/translation while the morph owns them.
            ValueAnimator previous = mCardAnimator;
            mCardAnimator = null;
            previous.cancel();
        }
        if (!mCardMorphActive) {
            mCardMorphActive = true;
            // The endpoint is captured once: a template switch mid transition must settle instead
            // of carrying the animation over to a card it was never measured against.
            mCardMorphNavigation = isNavigation();
            mCardMorphFraction = expand ? 0f : 1f;
            mMorphGeometryReady = false;
        }
        startCardMorphAnimator(expand);
    }

    private void startCardMorphAnimator(boolean expand) {
        cancelCardMorphAnimator();
        float from = mCardMorphFraction;
        float to = expand ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        // Only the remaining distance is animated, so a reversal continues from the fraction that is
        // on screen at the same speed instead of snapping back to an endpoint first.
        animator.setDuration(Math.max(1L, Math.round(CARD_MORPH_DURATION_MS * Math.abs(to - from))));
        animator.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
        animator.addUpdateListener(animation ->
                applyCardMorphFraction((float) animation.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mCardMorphAnimator == animation) {
                    settleCardMorph();
                }
            }
        });
        mCardMorphAnimator = animator;
        animator.start();
    }

    private void cancelCardMorphAnimator() {
        ValueAnimator animator = mCardMorphAnimator;
        if (animator == null) {
            return;
        }
        // Clearing the field first keeps the cancel callback from being mistaken for a settle.
        mCardMorphAnimator = null;
        animator.cancel();
    }

    private void settleCardMorph() {
        if (!mCardMorphActive) {
            return;
        }
        cancelCardMorphAnimator();
        mCardMorphActive = false;
        mCardMorphFraction = mExpanded ? 1f : 0f;
        mMorphGeometryReady = false;
        // Put the card at its settled place before the window leaves the envelope. Moving the
        // window first leaves the card at the morph offset and the island jumps down.
        finishCardMorphLayout();
        placeSettledCard();
        if (mMorphListener != null) {
            mMorphListener.onCardMorphSettled();
        }
    }

    /**
     * Card origin inside the wrap-content window: top of the view, still centred while it is wide.
     * The card is the only visible child once it is expanded, so top zero is its normal flow
     * position and the release of the envelope never moves it on screen.
     */
    private void placeSettledCard() {
        if (!mExpanded) {
            return;
        }
        View card = activeCard();
        int width = card.getWidth() > 0 ? card.getWidth() : card.getMeasuredWidth();
        int height = card.getHeight() > 0 ? card.getHeight() : card.getMeasuredHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int left = Math.max(0, (getWidth() - width) / 2);
        card.layout(left, 0, left + width, height);
    }

    /** Lands the morph on its endpoint without telling the window, which is already going away. */
    private void abortCardMorph() {
        cancelCardMorphAnimator();
        if (!mCardMorphActive) {
            return;
        }
        mCardMorphActive = false;
        mCardMorphFraction = mExpanded ? 1f : 0f;
        mMorphGeometryReady = false;
        finishCardMorphLayout();
    }

    private void finishCardMorphLayout() {
        clearMorphContentTransforms();
        restoreCardBackgrounds();
        applyExpansionVisibility();
        requestLayout();
        invalidate();
    }

    /**
     * Places both endpoints at their real geometry inside the morph envelope. The window keeps the
     * envelope on the compact base, so the expanded card is offset by the difference between the
     * expanded and the compact window position.
     */
    private void layoutCardMorph() {
        View card = activeCard();
        int width = cardWidth();
        if (width > 0) {
            // The envelope constrains the normal vertical flow, so the card is measured on its own
            // with its real width and no height cap: the morph geometry must match the endpoint
            // card, never a copy squashed into the space left under the pill.
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }
        int pillWidth = mRow.getMeasuredWidth();
        int pillHeight = mRow.getMeasuredHeight();
        int cardWidth = card.getMeasuredWidth();
        int cardHeight = card.getMeasuredHeight();
        if (pillWidth <= 0 || pillHeight <= 0 || cardWidth <= 0 || cardHeight <= 0) {
            // Nothing measured yet: the next layout pass completes the transition.
            return;
        }
        int pillLeft = Math.round(mMorphCutoutCenterX - pillWidth / 2f);
        mRow.layout(pillLeft, 0, pillLeft + pillWidth, pillHeight);
        if (mSmallRow.getVisibility() == VISIBLE) {
            int smallWidth = mSmallRow.getMeasuredWidth();
            int smallHeight = mSmallRow.getMeasuredHeight();
            int smallLeft = pillLeft + (pillWidth - smallWidth) / 2;
            mSmallRow.layout(smallLeft, pillHeight, smallLeft + smallWidth, pillHeight + smallHeight);
        }
        int cardLeft = Math.round((mMorphDisplayWidth - cardWidth) / 2f);
        card.layout(cardLeft, mMorphExpandedTop, cardLeft + cardWidth,
                mMorphExpandedTop + cardHeight);
        computeCardMorphGeometry(pillWidth, pillHeight, cardWidth, cardHeight);
        // The clip the touch region is computed against. Set here, once per layout, so reading the
        // region per frame never allocates.
        mTouchClipRegion.set(0, 0, Math.max(mMorphDisplayWidth, getWidth()),
                Math.max(mMorphExpandedTop + cardHeight, getHeight()));
        mMorphGeometryReady = true;
        suppressCardBackgrounds();
        applyCardMorphFraction(mCardMorphFraction);
    }

    /** Caches the two endpoint rectangles and, for navigation, the content anchors. */
    private void computeCardMorphGeometry(int pillWidth, int pillHeight, int cardWidth,
            int cardHeight) {
        int pillLeft = Math.round(mMorphCutoutCenterX - pillWidth / 2f);
        mMorphCompactRect.set(pillLeft, 0, pillLeft + pillWidth, pillHeight);
        mMorphCompactRadius = pillHeight / 2f;
        int cardLeft = Math.round((mMorphDisplayWidth - cardWidth) / 2f);
        mMorphCardRect.set(cardLeft, mMorphExpandedTop, cardLeft + cardWidth,
                mMorphExpandedTop + cardHeight);
        mMorphCardRadius = dp(CARD_RADIUS_DP);
        if (!mCardMorphNavigation) {
            // The generic pill and card travel as whole blocks, so they have no inner anchors. The
            // stale navigation anchors are cleared so no frame can reuse them for another template.
            mCompactArrowRect.setEmpty();
            mCardArrowRect.setEmpty();
            mCompactTitleRect.setEmpty();
            mCardTitleRect.setEmpty();
            mCardSubtitleRect.setEmpty();
            return;
        }
        anchor(mCompactArrowRect, mNavCompactArrow);
        anchor(mCardArrowRect, mNavCardArrow);
        anchor(mCompactTitleRect, mNavCompactTitle);
        anchor(mCardTitleRect, mNavCardTitle);
        anchor(mCardSubtitleRect, mNavCardSubtitle);
    }

    /** Endpoint anchor of one content view, or an empty rectangle when it is not laid out. */
    private void anchor(RectF out, View view) {
        if (view == null || view.getVisibility() != VISIBLE || view.getWidth() == 0
                || view.getHeight() == 0) {
            out.setEmpty();
            return;
        }
        Rect bounds = mMorphScratchRect;
        bounds.set(0, 0, view.getWidth(), view.getHeight());
        offsetDescendantRectToMyCoords(view, bounds);
        out.set(bounds);
    }

    /**
     * Cross-fade visibility that switches the presentation at the halfway point. Between the two
     * endpoints an instruction must never be on screen twice, so the outgoing presentation is gone
     * while the background is still growing past it and the incoming one appears only after it.
     */
    private static float arrivalAlpha(float arrival) {
        return Math.max(0f, Math.min(1f, 2f * arrival - 1f));
    }

    /** Pushes one interpolated frame. Only reused shapes and view transforms change here. */
    private void applyCardMorphFraction(float fraction) {
        mCardMorphFraction = fraction;
        if (!mMorphGeometryReady) {
            invalidate();
            return;
        }
        mMorphRect.set(
                lerp(mMorphCompactRect.left, mMorphCardRect.left, fraction),
                lerp(mMorphCompactRect.top, mMorphCardRect.top, fraction),
                lerp(mMorphCompactRect.right, mMorphCardRect.right, fraction),
                lerp(mMorphCompactRect.bottom, mMorphCardRect.bottom, fraction));
        // The path is the shape that is on screen this frame; both the draw and the touch region use
        // exactly it, so the rounded corners never swallow a touch.
        float radius = lerp(mMorphCompactRadius, mMorphCardRadius, fraction);
        mMorphPath.reset();
        mMorphPath.addRoundRect(mMorphRect, radius, radius, Path.Direction.CW);
        if (mCardMorphNavigation) {
            // Each navigation endpoint's content travels to the other endpoint's layout while cross
            // fading, so both ends are exactly right and no glyph is ever scaled.
            placeMorphContent(mNavCompactArrow, mCompactArrowRect, mCardArrowRect, 1f - fraction,
                    arrivalAlpha(1f - fraction));
            placeMorphContent(mNavCompactTitle, mCompactTitleRect, mCardTitleRect, 1f - fraction,
                    arrivalAlpha(1f - fraction));
            placeMorphContent(mNavCardArrow, mCardArrowRect, mCompactArrowRect, fraction,
                    arrivalAlpha(fraction));
            placeMorphContent(mNavCardTitle, mCardTitleRect, mCompactTitleRect, fraction,
                    arrivalAlpha(fraction));
            // The subtitle has no compact counterpart. It uses the card's fade and stays inside the
            // morph clip, so the opening shape reveals it instead of popping the line in at the end.
            placeMorphContent(mNavCardSubtitle, mCardSubtitleRect, null, fraction,
                    arrivalAlpha(fraction));
        } else {
            // The generic presentation has no inner anchors: the compact row and the card travel as
            // one block each, so no glyph, label or button is ever scaled or re-laid out. Their
            // cross fades never overlap, so the instruction is on screen exactly once.
            placeMorphContent(mRow, mMorphCompactRect, mMorphCardRect, 1f - fraction,
                    arrivalAlpha(1f - fraction));
            placeMorphContent(mCard, mMorphCardRect, mMorphCompactRect, fraction,
                    arrivalAlpha(fraction));
        }
        // The secondary line has no expanded counterpart, so it leaves early and never sits under
        // the arriving card.
        mSmallRow.setAlpha(Math.max(0f, 1f - 2f * fraction));
        invalidate();
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    /**
     * Places one endpoint's content between its own anchor and its counterpart's. {@code arrival} is
     * 1 when the view sits on its own endpoint and 0 when it sits on the counterpart's; {@code alpha}
     * cross fades the two endpoint presentations without ever scaling a glyph.
     */
    private static void placeMorphContent(View view, RectF own, RectF counterpart, float arrival,
            float alpha) {
        if (view == null) {
            return;
        }
        float offsetX = 0f;
        float offsetY = 0f;
        if (counterpart != null && !own.isEmpty() && !counterpart.isEmpty()) {
            float remaining = 1f - arrival;
            offsetX = (counterpart.centerX() - own.centerX()) * remaining;
            offsetY = (counterpart.centerY() - own.centerY()) * remaining;
        }
        view.setTranslationX(offsetX);
        view.setTranslationY(offsetY);
        view.setAlpha(alpha);
    }

    private void clearMorphContentTransforms() {
        resetMorphContent(mNavCompactArrow);
        resetMorphContent(mNavCompactTitle);
        resetMorphContent(mNavCardArrow);
        resetMorphContent(mNavCardTitle);
        resetMorphContent(mNavCardSubtitle);
        // The generic presentation moves the whole pill and the whole card, so both have to come
        // back to their own endpoint geometry whatever endpoint the morph ended on.
        resetMorphContent(mRow);
        resetMorphContent(mCard);
        resetMorphContent(mNavCard);
        mSmallRow.setAlpha(1f);
    }

    private static void resetMorphContent(View view) {
        if (view == null) {
            return;
        }
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
    }

    /**
     * While the morph paints the single background, the two endpoint backgrounds have to stay out
     * of the way: leaving them on would draw the capsule and the card as two separate shapes side
     * by side. Both templates suppress the pill and whichever card endpoint is active.
     */
    private void suppressCardBackgrounds() {
        if (mRow.getBackground() != null) {
            mRow.setBackground(null);
        }
        View card = activeCard();
        if (card.getBackground() != null) {
            card.setBackground(null);
        }
    }

    private void restoreCardBackgrounds() {
        if (mRow.getBackground() != mPillBackground) {
            mRow.setBackground(mPillBackground);
        }
        if (mCard.getBackground() != mCardBackground) {
            mCard.setBackground(mCardBackground);
        }
        if (mNavCard.getBackground() != mNavCardBackground) {
            mNavCard.setBackground(mNavCardBackground);
        }
    }

    /**
     * Paints the one background that travels between the compact and the expanded bounds, behind the
     * content, from the path the current frame already built. The generic card shares the pill's
     * surface colour, so its morph keeps a single colour the whole way.
     */
    private void drawCardMorphBackground(Canvas canvas) {
        int cardColor = mCardMorphNavigation ? CARD_COLOR_NAV : CARD_COLOR_GENERIC;
        mMorphPaint.setColor(blendColor(PILL_COLOR, cardColor, mCardMorphFraction));
        canvas.drawPath(mMorphPath, mMorphPaint);
    }

    /** Interpolates the two opaque island surface colours channel by channel. */
    private static int blendColor(int from, int to, float fraction) {
        int red = (int) lerp(android.graphics.Color.red(from), android.graphics.Color.red(to),
                fraction);
        int green = (int) lerp(android.graphics.Color.green(from), android.graphics.Color.green(to),
                fraction);
        int blue = (int) lerp(android.graphics.Color.blue(from), android.graphics.Color.blue(to),
                fraction);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    /**
     * Every ImageView receives its own drawable instance: sharing one drawable between views makes
     * them fight over bounds, alpha and callback state.
     */
    private Drawable copyForUse(Drawable source) {
        if (source == null) {
            return null;
        }
        Drawable.ConstantState state = source.getConstantState();
        if (state == null) {
            return source;
        }
        return state.newDrawable(getResources()).mutate();
    }

    /**
     * Refreshes every running timer. This is the only work performed by the periodic tick: no view
     * is rebuilt, no icon is decoded and no model is parsed.
     */
    public void updateTimers(long wallNow) {
        for (TextView view : mTimerViews) {
            TimerSlot slot = (TimerSlot) view.getTag();
            if (slot == null || !slot.timer.running()) {
                // Paused timers are frozen by the model itself and were already rendered when the
                // part was built, so they never need a per-second refresh.
                continue;
            }
            long millis = slot.timer.durationAt(wallNow);
            long seconds = Math.max(0L, millis) / 1000L;
            if (seconds == slot.lastSeconds) {
                // Nothing visible changed: skip the text write entirely.
                continue;
            }
            slot.lastSeconds = seconds;
            int length = formatTimer(millis, mTimeBuffer);
            view.setText(mTimeBuffer, 0, length);
        }
    }

    /**
     * Region that should receive touches, in the coordinate space of this view (which is the window
     * root). Everything else must stay transparent to input.
     *
     * <p>While the morph runs the region follows the travelling background, so the area the card will
     * occupy is never swallowed before it is on screen, the region never reaches outside the visible
     * bounds and the physical cutout stays untouchable throughout.
     *
     * <p>The returned region is reused between calls: copy it when it has to outlive the frame.
     */
    public Region getTouchableRegion() {
        Region region = mTouchRegion;
        region.setEmpty();
        if (mCardMorphActive && mMorphGeometryReady) {
            // The exact rounded shape that is on screen, not its bounding box.
            region.setPath(mMorphPath, mTouchClipRegion);
            if (mGapWidth > 0 && mMorphCutoutBottom > 0) {
                subtractCutout(region);
            }
            return region;
        }
        Rect scratch = mMorphScratchRect;
        addTouchableRect(region, mRow, scratch, mGap);
        addTouchableRect(region, mSmallRow, scratch, null);
        addTouchableRect(region, mCard, scratch, null);
        addTouchableRect(region, mNavCard, scratch, null);
        return region;
    }

    /**
     * Keeps the physical cutout untouchable whatever the morphing background covers. Only the camera
     * rectangle is carved: once the shape has travelled below it, the rest of the background — the
     * card's centre included — stays touchable.
     */
    private void subtractCutout(Region region) {
        int gapLeft = Math.round(mMorphCutoutCenterX - mGapWidth / 2f);
        Rect hole = mTouchHoleRect;
        hole.set(gapLeft, 0, gapLeft + mGapWidth, mMorphCutoutBottom);
        region.op(hole, Region.Op.DIFFERENCE);
    }

    private void addTouchableRect(Region region, View view, Rect scratch, View hole) {
        if (view.getVisibility() != VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) {
            return;
        }
        scratch.set(0, 0, view.getWidth(), view.getHeight());
        offsetDescendantRectToMyCoords(view, scratch);
        Region added = mTouchAddedRegion;
        added.set(scratch);
        if (hole != null && hole.getVisibility() == VISIBLE && hole.getWidth() > 0) {
            Rect holeRect = mTouchHoleRect;
            holeRect.set(0, 0, hole.getWidth(), hole.getHeight());
            offsetDescendantRectToMyCoords(hole, holeRect);
            holeRect.top = scratch.top;
            holeRect.bottom = scratch.bottom;
            added.op(holeRect, Region.Op.DIFFERENCE);
        }
        region.op(added, Region.Op.UNION);
    }

    // ---------------------------------------------------------------- rendering

    private void rebuild() {
        mTimerViews.clear();
        mPrimary = mActivities.isEmpty() ? null : mActivities.get(0);
        mSecondary = mActivities.size() > 1 ? mActivities.get(1) : null;
        updateTextBudget();
        boolean navigation = isNavigation();
        rebuildRow(navigation);
        if (mExpanded && !mCardMorphActive) {
            // An expanded card replaces both compact lines: it already carries the label, the
            // values and every expanded row, so keeping the pill would only duplicate them. A
            // running morph keeps the secondary line: it has to fade out.
            mSmallRow.removeAllViews();
            mSmallRow.setVisibility(GONE);
        } else {
            rebuildSmall();
        }
        if (navigation) {
            // The generic card never renders the navigation scene: drop its content so no stale
            // text, progress or touch target survives the switch, then build the navigation card.
            clearCard();
            rebuildNavigationCard();
        } else {
            rebuildCard();
        }
        if (mCardMorphActive && (mCardMorphNavigation != navigation || !canMorphCard())) {
            // The primary is no longer something the morph can interpolate: a different template
            // would need the other card endpoint, and a card or pill without content has nothing to
            // interpolate. Both endpoints are already rebuilt above, so landing now places the new
            // card at its settled geometry in this frame.
            settleCardMorph();
        }
        applyExpansionVisibility();
    }

    /**
     * Applies the expansion rules shared by both card templates: an expanded card hides the compact
     * pill and the secondary line, and only the active card endpoint is visible. While the morph
     * runs both endpoints stay visible so the single background can interpolate between them; the
     * compact lines keep the visibility {@link #rebuildRow} and {@link #rebuildSmall} just decided.
     */
    private void applyExpansionVisibility() {
        boolean navigation = isNavigation();
        View card = activeCard();
        View other = navigation ? mCard : mNavCard;
        if (other.getVisibility() != GONE) {
            other.setVisibility(GONE);
        }
        boolean hasContent = navigation ? navigationCardHasContent() : hasCardContent();
        if (mCardMorphActive) {
            card.setVisibility(hasContent ? VISIBLE : GONE);
            return;
        }
        if (!mExpanded || !hasContent) {
            card.setVisibility(GONE);
            return;
        }
        mRow.setVisibility(GONE);
        mSmallRow.setVisibility(GONE);
        card.setVisibility(VISIBLE);
    }

    /**
     * Derives how much text one side of the pill may use from the real viewport.
     *
     * <p>The status bar ends, the cutout gap, the icon and the section padding are subtracted first,
     * then the remainder is divided across the two main text regions of the pill row. The result is
     * capped at {@link #MAX_TEXT_REGION_DP}.
     *
     * <p>The small secondary activity lives on its own line below the pill, so it does not steal
     * width from the main regions; it is bounded by the same cap, which keeps the window no wider
     * than one row needs. There is deliberately no per-slot floor: a floor larger than the allocated
     * budget is exactly how untrusted client text ends up over the privacy indicators.
     */
    private void updateTextBudget() {
        int viewport = viewportWidth();
        int reservedEnds = 2 * dp(STATUS_BAR_END_RESERVED_DP);
        int overheadPerSide = mIconSize + dp(6) + mPillPadding;
        int available = viewport - reservedEnds - mGapWidth - 2 * overheadPerSide;
        mTextBudget = Math.min(dp(MAX_TEXT_REGION_DP), Math.max(0, available) / 2);
    }

    /** Viewport width in pixels, falling back to the display until the window reports it. */
    private int viewportWidth() {
        return mViewportWidth > 0
                ? mViewportWidth : getResources().getDisplayMetrics().widthPixels;
    }

    private static boolean sameActivities(List<IslandModel> a, List<IslandModel> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private void rebuildRow(boolean navigation) {
        mLeft.removeAllViews();
        mRight.removeAllViews();
        mLeft.setPadding(0, 0, 0, 0);
        mRight.setPadding(0, 0, 0, 0);
        mLeft.setGravity(Gravity.CENTER_VERTICAL);
        mRight.setGravity(Gravity.CENTER_VERTICAL);
        if (mPrimary == null) {
            mRow.setVisibility(GONE);
            return;
        }
        if (navigation) {
            rebuildNavigationRow();
            return;
        }
        boolean hasLeft = addSection(mLeft, mPrimary.left, true, false);
        boolean hasRight = addSection(mRight, mPrimary.right, false, false);
        if (!hasLeft && !hasRight) {
            mRow.setVisibility(GONE);
            return;
        }
        if (hasLeft != hasRight) {
            // One side only: move its built children across the camera, so a single notification
            // always reads icon left of the gap and text right of it, whichever side it came on.
            splitSingleSide(hasLeft ? mLeft.getContentDescription()
                    : mRight.getContentDescription());
        }
        // Both sides stay laid out so onMeasure can balance them and the gap stays centred.
        mLeft.setVisibility(VISIBLE);
        mRight.setVisibility(VISIBLE);
        mRow.setVisibility(VISIBLE);
    }

    /**
     * Balances a one-sided pill around the camera without building anything again: the icon keeps
     * the half left of the gap and the summary, value and timer slots move to the half right of it.
     * Only the children, the paddings and the accessibility of the two halves are rearranged.
     */
    private void splitSingleSide(CharSequence description) {
        // Text slots always end up on the right half, in their reading order.
        int moved = 0;
        for (int i = 0; i < mLeft.getChildCount(); ) {
            View child = mLeft.getChildAt(i);
            if (!(child instanceof TextView)) {
                i++;
                continue;
            }
            mLeft.removeViewAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.leftMargin = moved == 0 ? 0 : dp(6);
            mRight.addView(child, params);
            moved++;
        }
        // Every direction or app glyph always ends up on the left half, before the camera.
        for (int i = 0; i < mRight.getChildCount(); ) {
            View child = mRight.getChildAt(i);
            if (!(child instanceof ImageView)) {
                i++;
                continue;
            }
            mRight.removeViewAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.leftMargin = 0;
            mLeft.addView(child, params);
        }
        int vertical = dp(6);
        mLeft.setPadding(mPillPadding, vertical, 0, vertical);
        mRight.setPadding(0, vertical, mPillPadding, vertical);
        // The icon is decorative; the text half carries the whole description exactly once, so
        // nothing is announced twice.
        mLeft.setContentDescription(null);
        mLeft.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRight.setContentDescription(description);
        mRight.setImportantForAccessibility(description == null
                ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private void rebuildSmall() {
        mSmallRow.removeAllViews();
        if (mSecondary == null || !addSection(mSmallRow, mSecondary.small, true, true)) {
            mSmallRow.setVisibility(GONE);
            return;
        }
        mSmallRow.setVisibility(VISIBLE);
    }

    // ------------------------------------------------------------ navigation

    /** True when the current primary activity is the legacy navigation scene. */
    private boolean isNavigation() {
        return mPrimary != null && mPrimary.left != null
                && NAVIGATION_COMPONENT.equals(mPrimary.left.component);
    }

    /**
     * The expanded endpoint of the current primary: the navigation card or the generic wide card.
     * The morph geometry, the envelope measurement, the settle placement and the backgrounds all
     * go through this one accessor, so neither template can ever address the other's card.
     */
    private View activeCard() {
        return isNavigation() ? mNavCard : mCard;
    }

    /**
     * Compact navigation line. The direction arrow sits immediately left of the physical cutout and
     * the whole instruction immediately right of it, exactly like the pill it replaces.
     *
     * <p>The detail line (the sender's content, e.g. "navigation running") is deliberately not
     * rendered here: on one line next to the cutout it can only be truncated, so it belongs to the
     * expanded card instead. A missing arrow or an empty title simply leaves that side of the pill
     * empty; no glyph is ever fabricated.
     */
    private void rebuildNavigationRow() {
        IslandModel.Part part = mPrimary.left;
        Drawable arrow = navigationArrow(NAV_ARROW_COMPACT, part);
        CharSequence title = labelText(part);
        int budget = navigationTextBudget();
        // A zero budget means the pill has no room left between the reserved status bar ends, so
        // the arrow alone keeps the pill instead of the text overflowing into them.
        boolean hasTitle = !TextUtils.isEmpty(title) && budget > 0;
        if (arrow == null && !hasTitle) {
            mRow.setVisibility(GONE);
            return;
        }
        mLeft.setPadding(arrow == null ? 0 : mPillPadding, dp(6), 0, dp(6));
        if (arrow != null) {
            mNavCompactArrow.setImageDrawable(arrow);
            applyDirectionTint(mNavCompactArrow, part);
            mLeft.addView(mNavCompactArrow, new LayoutParams(mIconSize, mIconSize));
        }
        // The arrow is decorative: the instruction beside it carries the accessible text.
        mLeft.setContentDescription(null);
        mLeft.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRight.setPadding(0, dp(6), hasTitle ? mPillPadding : 0, dp(6));
        if (hasTitle) {
            mNavCompactTitle.setText(title);
            mNavCompactTitle.setTextColor(textColor(part));
            mNavCompactTitle.setMaxWidth(budget);
            mRight.addView(mNavCompactTitle, new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mRight.setContentDescription(hasTitle ? title : null);
        mRight.setImportantForAccessibility(hasTitle
                ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Both sides stay laid out so onMeasure balances the arrow-only side against the text side
        // and the cutout gap stays centred in the pill.
        mLeft.setVisibility(VISIBLE);
        mRight.setVisibility(VISIBLE);
        mRow.setVisibility(VISIBLE);
    }

    /**
     * Expanded navigation card: the instruction on the left, the direction glyph on the right, on a
     * card as wide as the viewport minus one margin per side.
     */
    private void rebuildNavigationCard() {
        IslandModel.Part part = mPrimary == null ? null : mPrimary.left;
        CharSequence title = labelText(part);
        CharSequence subtitle = detailText(part);
        mNavCardTitle.setText(title == null ? "" : title);
        mNavCardTitle.setTextColor(textColor(part));
        mNavCardTitle.setVisibility(TextUtils.isEmpty(title) ? GONE : VISIBLE);
        mNavCardSubtitle.setText(subtitle == null ? "" : subtitle);
        mNavCardSubtitle.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);
        Drawable arrow = navigationArrow(NAV_ARROW_CARD, part);
        if (arrow == null) {
            mNavCardArrow.setImageDrawable(null);
            mNavCardArrow.setVisibility(GONE);
        } else {
            mNavCardArrow.setImageDrawable(arrow);
            applyDirectionTint(mNavCardArrow, part);
            mNavCardArrow.setVisibility(VISIBLE);
        }
        mNavCard.getLayoutParams().width = cardWidth();
        mNavCard.requestLayout();
        mNavCard.setContentDescription(join(title, subtitle));
        mNavCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /**
     * Width of the expanded card, shared by both templates: the viewport minus one margin on each
     * side. Nothing is ever added beyond this, so the card can never reach the status bar ends.
     */
    private int cardWidth() {
        return Math.max(0, viewportWidth() - 2 * dp(CARD_MARGIN_DP));
    }

    /**
     * Text budget of the compact navigation pill. The instruction is the only text on the pill, so
     * it may use the whole width left between the two reserved status bar ends instead of sharing
     * it with a second region as the generic pill does; the pill itself stays symmetric around the
     * cutout because {@link #onMeasure} balances the arrow-only side against the text side. The
     * {@link #MAX_TEXT_REGION_DP} cap still applies, and the reserved ends are never entered.
     */
    private int navigationTextBudget() {
        int available = viewportWidth() - 2 * dp(STATUS_BAR_END_RESERVED_DP) - mGapWidth;
        int side = available / 2 - mPillPadding;
        return Math.min(dp(MAX_TEXT_REGION_DP), Math.max(0, side));
    }

    /** True when the navigation card has anything to show for the current primary activity. */
    private boolean navigationCardHasContent() {
        IslandModel.Part part = mPrimary == null ? null : mPrimary.left;
        if (part == null) {
            return false;
        }
        return !TextUtils.isEmpty(labelText(part)) || !TextUtils.isEmpty(detailText(part))
                || directionIcon(part) != null;
    }

    /**
     * Direction glyph of a navigation part. The regular icon is the variant meant for the dark
     * island surface; the dark icon is a black glyph, so it is only a fallback and is forced white
     * by {@link #applyDirectionTint}. No application icon is ever substituted here.
     */
    private static Icon directionIcon(IslandModel.Part part) {
        if (part == null) {
            return null;
        }
        return part.icon != null ? part.icon : part.darkIcon;
    }

    /**
     * Resolves the direction glyph for one navigation ImageView. The result is cached against the
     * model icon instance, so a rebuild that carries the same icon neither re-decodes nor re-copies
     * it, while each ImageView keeps its own drawable instance.
     */
    private Drawable navigationArrow(int slot, IslandModel.Part part) {
        Icon source = directionIcon(part);
        if (source == null) {
            mNavArrowSources[slot] = null;
            mNavArrowDrawables[slot] = null;
            return null;
        }
        if (source != mNavArrowSources[slot] || mNavArrowDrawables[slot] == null) {
            mNavArrowSources[slot] = source;
            mNavArrowDrawables[slot] = copyForUse(resolve(source));
        }
        return mNavArrowDrawables[slot];
    }

    /**
     * Forces the direction glyph to white when it can only come from the dark (black) variant,
     * which would otherwise be invisible on the island. Never applied to an application icon.
     */
    private static void applyDirectionTint(ImageView view, IslandModel.Part part) {
        boolean darkVariantOnly = part != null && part.icon == null && part.darkIcon != null;
        view.setImageTintList(darkVariantOnly ? ColorStateList.valueOf(0xFFFFFFFF) : null);
    }

    /** Drops the generic card content so nothing stale survives a switch to navigation. */
    private void clearCard() {
        mCardParts.removeAllViews();
        mCardActions.removeAllViews();
        mCardTimer.setTag(null);
        mCardTimer.setText("");
        mCardTimer.setVisibility(GONE);
        mCardIcon.setImageDrawable(null);
        mCardTitle.setText("");
        mCardContent.setText("");
        mCardProgress.setVisibility(GONE);
        mCard.setVisibility(GONE);
    }

    /**
     * Renders one side of the compact pill. Returns false when the part carries nothing to show,
     * which keeps empty sections from adding width to the pill.
     *
     * <p>The pill shows a semantic summary instead of truncating every field in half: the label
     * (title / front title / sub title) when the sender provided one, the full detail when it did
     * not, the explicit value the sender sends (digit, progress percentage) and the timer. The
     * summary takes the width that is left once the value and timer slots were given the width
     * their own text needs, so a value is never ellipsized away and the whole section, gaps
     * included, stays inside {@link #mTextBudget}. Nothing is dropped silently: the content
     * description keeps every field and the expanded card still shows the full detail.
     */
    private boolean addSection(LinearLayout container, IslandModel.Part part, boolean leadingIcon,
            boolean compact) {
        if (part == null) {
            return false;
        }
        Drawable icon = copyForUse(resolve(pickIcon(part)));
        CharSequence label = labelText(part);
        CharSequence detail = detailText(part);
        boolean hasLabel = !TextUtils.isEmpty(label);
        // Without a label the detail is the summary, so its values never appear twice.
        CharSequence summary = hasLabel ? label : detail;
        CharSequence value = hasLabel ? explicitValue(part) : null;
        IslandModel.Timer timer = part.timer;
        boolean hasTimer = timer != null && (timer.running() || timer.paused());
        if (icon == null && TextUtils.isEmpty(summary) && TextUtils.isEmpty(value) && !hasTimer) {
            return false;
        }
        int size = compact ? mSmallIconSize : mIconSize;
        container.removeAllViews();
        container.setPadding(compact || leadingIcon ? mPillPadding : 0,
                compact ? dp(3) : dp(6), compact || !leadingIcon ? mPillPadding : 0,
                compact ? dp(3) : dp(6));

        TextView summaryView = TextUtils.isEmpty(summary)
                ? null : makeText(summary, part, compact, mTextBudget);
        TextView valueView = TextUtils.isEmpty(value)
                ? null : makeText(value, part, compact, mTextBudget);
        TextView timerView = hasTimer ? makeTimerView(timer, part, compact, mTextBudget) : null;
        CharSequence timerText = timerView == null ? null : timerView.getText();

        // The gaps the section adds between its slots are part of the same budget.
        int gap = dp(6);
        int slots = (summaryView == null ? 0 : 1) + (valueView == null ? 0 : 1)
                + (timerView == null ? 0 : 1);
        int available = mTextBudget - (icon == null ? 0 : gap) - gap * Math.max(0, slots - 1);
        // The value and the timer keep the width their own text needs; the summary gets the rest.
        int valueWidth = valueView == null
                ? 0 : Math.max(0, Math.min(available, desiredWidth(valueView)));
        available -= valueWidth;
        int timerWidth = timerView == null
                ? 0 : Math.max(0, Math.min(available, desiredWidth(timerView)));
        available -= timerWidth;
        int summaryWidth = summaryView == null ? 0 : Math.max(0, available);

        List<TextView> texts = new ArrayList<>(3);
        if (summaryWidth > 0) {
            summaryView.setMaxWidth(summaryWidth);
            texts.add(summaryView);
        }
        if (valueWidth > 0) {
            valueView.setMaxWidth(valueWidth);
            texts.add(valueView);
        }
        if (timerWidth > 0) {
            timerView.setMaxWidth(timerWidth);
            texts.add(timerView);
        }

        ImageView iconView = null;
        if (icon != null) {
            iconView = new ImageView(getContext());
            iconView.setImageDrawable(icon);
            iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (leadingIcon) {
            if (iconView != null) {
                container.addView(iconView, new LayoutParams(size, size));
            }
            addTexts(container, texts, iconView == null ? 0 : gap);
        } else {
            addTexts(container, texts, 0);
            if (iconView != null) {
                LinearLayout.LayoutParams params = new LayoutParams(size, size);
                params.leftMargin = texts.isEmpty() ? 0 : gap;
                container.addView(iconView, params);
            }
        }
        // The accessible text keeps every field the sender provided, including the ones the one
        // line pill summarizes away.
        container.setContentDescription(join(label, detail, timerText));
        container.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        return true;
    }

    /**
     * Explicit value a part carries next to its label: the digit (a licence plate, for example)
     * and the progress percentage. Both are short, self describing numbers, so the pill can give
     * them the width their own text needs instead of splitting the label budget in half.
     */
    private static CharSequence explicitValue(IslandModel.Part part) {
        if (part == null) {
            return null;
        }
        CharSequence progress = part.progress >= 0 && part.progress <= 100
                ? part.progress + "%" : null;
        return join(part.digit, progress);
    }

    /** Width one pill slot's own text needs, measured once per rebuild against the full budget. */
    private static int desiredWidth(TextView view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredWidth();
    }

    private void addTexts(LinearLayout container, List<TextView> texts, int firstMargin) {
        for (int i = 0; i < texts.size(); i++) {
            LinearLayout.LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = i == 0 ? firstMargin : dp(6);
            container.addView(texts.get(i), params);
        }
    }

    private TextView makeText(CharSequence text, IslandModel.Part part, boolean compact,
            int maxWidth) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11f : 13f);
        view.setTextColor(textColor(part));
        view.setMaxWidth(maxWidth);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /**
     * Creates a timer view already showing its current value and registers it for ticking. The
     * initial value goes through the same buffer formatter as the tick path, so no timer text is
     * ever built with a formatter.
     */
    private TextView makeTimerView(IslandModel.Timer timer, IslandModel.Part part, boolean compact,
            int maxWidth) {
        long millis = timer.durationAt(System.currentTimeMillis());
        TextView view = makeText(null, part, compact, maxWidth);
        int length = formatTimer(millis, mTimeBuffer);
        view.setText(mTimeBuffer, 0, length);
        TimerSlot slot = new TimerSlot(timer);
        slot.lastSeconds = Math.max(0L, millis) / 1000L;
        view.setTag(slot);
        mTimerViews.add(view);
        return view;
    }

    private void rebuildCard() {
        // The generic card is the morph endpoint too, so its width is fixed before anything is
        // measured: the viewport minus one margin per side, never wrap content.
        mCard.getLayoutParams().width = cardWidth();
        mCard.requestLayout();
        mCardParts.removeAllViews();
        mCardActions.removeAllViews();
        mCardTimer.setTag(null);
        mCardTimer.setText("");
        if (mPrimary == null) {
            mCardIcon.setImageDrawable(null);
            mCardTitle.setText("");
            mCardContent.setText("");
            mCardProgress.setVisibility(GONE);
            return;
        }
        IslandModel.Part header = firstPart();
        Drawable icon = copyForUse(resolve(mPrimary.appIcon));
        if (icon == null && header != null) {
            icon = copyForUse(resolve(pickIcon(header)));
        }
        mCardIcon.setImageDrawable(icon);
        mCardIcon.setVisibility(icon == null ? GONE : VISIBLE);

        CharSequence title = header == null ? null : labelText(header);
        mCardTitle.setText(title == null ? "" : title);
        mCardTitle.setTextColor(header == null ? 0xFFFFFFFF : textColor(header));

        IslandModel.Timer timer = header == null ? null : header.timer;
        if (timer != null && (timer.running() || timer.paused())) {
            long millis = timer.durationAt(System.currentTimeMillis());
            int length = formatTimer(millis, mTimeBuffer);
            mCardTimer.setText(mTimeBuffer, 0, length);
            mCardTimer.setTextColor(0xFFFFFFFF);
            mCardTimer.setVisibility(VISIBLE);
            TimerSlot slot = new TimerSlot(timer);
            slot.lastSeconds = Math.max(0L, millis) / 1000L;
            mCardTimer.setTag(slot);
            mTimerViews.add(mCardTimer);
        } else {
            mCardTimer.setVisibility(GONE);
        }

        CharSequence content = pickContent();
        mCardContent.setText(content == null ? "" : content);
        mCardContent.setTextColor(0xFFD0D0D0);
        mCardContent.setVisibility(TextUtils.isEmpty(content) ? GONE : VISIBLE);

        IslandModel.Part progressPart = firstWithProgress();
        if (progressPart != null) {
            mCardProgress.setVisibility(VISIBLE);
            mCardProgress.setProgress(Math.max(0, Math.min(100, progressPart.progress)));
            int tint = progressPart.progressColor != 0 ? progressPart.progressColor
                    : (progressPart.darkColor != 0 ? progressPart.darkColor : 0xFF7C8CFF);
            mCardProgress.setProgressTintList(ColorStateList.valueOf(tint));
        } else {
            mCardProgress.setVisibility(GONE);
        }

        List<IslandModel.Part> expanded = mPrimary.expanded;
        if (expanded != null) {
            for (int i = 1; i < expanded.size(); i++) {
                addExpandedRow(expanded.get(i));
            }
        }
        List<IslandModel.Action> actions = mPrimary.actions;
        if (actions != null) {
            int count = Math.min(actions.size(), MAX_ACTION_BUTTONS);
            for (int i = 0; i < count; i++) {
                addActionButton(mPrimary, actions.get(i));
            }
        }
    }

    private void addExpandedRow(IslandModel.Part part) {
        if (part == null) {
            return;
        }
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams =
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);

        Drawable icon = copyForUse(resolve(pickIcon(part)));
        if (icon != null) {
            ImageView iconView = new ImageView(getContext());
            iconView.setImageDrawable(icon);
            iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(iconView, new LayoutParams(dp(16), dp(16)));
        }
        TextView text = new TextView(getContext());
        text.setText(join(labelText(part), detailText(part)));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP);
        text.setTextColor(textColor(part));
        LinearLayout.LayoutParams textParams =
                new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = icon == null ? 0 : dp(6);
        row.addView(text, textParams);

        IslandModel.Timer timer = part.timer;
        if (timer != null && (timer.running() || timer.paused())) {
            TextView timerView = makeTimerView(timer, part, false, dp(64));
            timerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP);
            timerView.setTextColor(0xFFFFFFFF);
            row.addView(timerView);
        }
        mCardParts.addView(row, rowParams);
    }

    private void addActionButton(IslandModel model, IslandModel.Action action) {
        if (action == null) {
            return;
        }
        LinearLayout button = new LinearLayout(getContext());
        button.setOrientation(HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundedBackground(0x26FFFFFF, dp(14)));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setMinimumHeight(dp(36));
        button.setClickable(true);
        button.setFocusable(true);
        LinearLayout.LayoutParams buttonParams =
                new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // Only the gap between buttons, so the row stays flush with the card padding on both sides.
        buttonParams.leftMargin = mCardActions.getChildCount() == 0 ? 0 : dp(8);
        buttonParams.topMargin = dp(10);

        Drawable icon = copyForUse(resolve(action.icon));
        if (icon != null) {
            ImageView iconView = new ImageView(getContext());
            iconView.setImageDrawable(icon);
            iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            button.addView(iconView, new LayoutParams(dp(14), dp(14)));
        }
        if (!TextUtils.isEmpty(action.title)) {
            TextView label = new TextView(getContext());
            label.setText(action.title);
            label.setSingleLine(true);
            // A horizontally scrolling single line uses a VERY_WIDE text layout; centering
            // that layout places the glyphs outside the weighted button's visible bounds.
            label.setHorizontallyScrolling(false);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            label.setTextColor(0xFFFFFFFF);
            // The label shares the button with the icon instead of a fixed cap, so three actions
            // can never overflow the card and every button stays fully tappable.
            LinearLayout.LayoutParams labelParams =
                    new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = icon == null ? 0 : dp(6);
            button.addView(label, labelParams);
        }
        button.setContentDescription(action.title);
        button.setOnClickListener(view -> {
            if (mListener != null) {
                mListener.onIslandActionClicked(model, action);
            }
        });
        mCardActions.addView(button, buttonParams);
    }

    private boolean hasCardContent() {
        if (mPrimary == null) {
            return false;
        }
        return !TextUtils.isEmpty(mCardTitle.getText())
                || !TextUtils.isEmpty(mCardContent.getText())
                || mCardProgress.getVisibility() == VISIBLE
                || mCardTimer.getVisibility() == VISIBLE
                || mCardParts.getChildCount() > 0
                || mCardActions.getChildCount() > 0;
    }

    /**
     * Plain fade used when the morph cannot run (no geometry yet, or system animations are off).
     * The card keeps its shared fixed radius; only alpha and a small vertical offset animate, so the
     * endpoint looks exactly like a settled morph. Visibility stays owned by the endpoint rules, so
     * a collapse lands on the same compact state with or without animations.
     */
    private void animateCard(boolean expand) {
        if (mCardAnimator != null) {
            ValueAnimator previous = mCardAnimator;
            mCardAnimator = null;
            previous.cancel();
        }
        View card = activeCard();
        float offset = dp(6);
        ValueAnimator animator = ValueAnimator.ofFloat(expand ? 0f : 1f, expand ? 1f : 0f);
        animator.setDuration(ANIM_DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            card.setAlpha(fraction);
            card.setTranslationY(-offset * (1f - fraction));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mCardAnimator != animation) {
                    return;
                }
                mCardAnimator = null;
                // The card is left fully opaque so a later rebuild that shows it never inherits a
                // half faded surface from this transition.
                card.setAlpha(1f);
                card.setTranslationY(0f);
            }
        });
        mCardAnimator = animator;
        animator.start();
    }

    private IslandModel.Part firstPart() {
        List<IslandModel.Part> expanded = mPrimary.expanded;
        if (expanded != null && !expanded.isEmpty()) {
            return expanded.get(0);
        }
        if (mPrimary.left != null) {
            return mPrimary.left;
        }
        return mPrimary.right;
    }

    private CharSequence pickContent() {
        List<IslandModel.Part> expanded = mPrimary.expanded;
        if (expanded != null) {
            for (IslandModel.Part part : expanded) {
                CharSequence detail = detailText(part);
                if (!TextUtils.isEmpty(detail)) {
                    return detail;
                }
            }
        }
        CharSequence left = detailText(mPrimary.left);
        if (!TextUtils.isEmpty(left)) {
            return left;
        }
        return detailText(mPrimary.right);
    }

    private IslandModel.Part firstWithProgress() {
        List<IslandModel.Part> expanded = mPrimary.expanded;
        if (expanded != null) {
            for (IslandModel.Part part : expanded) {
                if (part != null && part.progress >= 0) {
                    return part;
                }
            }
        }
        if (mPrimary.left != null && mPrimary.left.progress >= 0) {
            return mPrimary.left;
        }
        if (mPrimary.right != null && mPrimary.right.progress >= 0) {
            return mPrimary.right;
        }
        return null;
    }

    private Drawable resolve(Icon icon) {
        if (icon == null || mIconResolver == null) {
            return null;
        }
        return mIconResolver.resolve(icon);
    }

    /**
     * The island surface is always dark, so the dark resource of a part is preferred, falling back
     * to the regular one.
     */
    private static Icon pickIcon(IslandModel.Part part) {
        if (part == null) {
            return null;
        }
        return part.darkIcon != null ? part.darkIcon : part.icon;
    }

    /** Primary label of a part: title first, then the front title, then the sub title. */
    private static CharSequence labelText(IslandModel.Part part) {
        if (part == null) {
            return null;
        }
        return join(part.title, part.frontTitle, part.subTitle);
    }

    /**
     * Secondary detail of a part: the digit (a licence plate, for example), the content, a progress
     * percentage and the special title. All of them are kept, not just the first one present.
     */
    private static CharSequence detailText(IslandModel.Part part) {
        if (part == null) {
            return null;
        }
        CharSequence detail = join(part.digit, part.content, part.subContent, part.extraTitle);
        if (part.progress >= 0 && part.progress <= 100) {
            detail = join(detail, part.progress + "%");
        }
        return join(detail, part.specialTitle);
    }

    /**
     * Joins every non-empty piece with a space, skipping duplicates. No field of the sender's
     * payload is silently replaced by another one.
     */
    private static CharSequence join(Object... values) {
        StringBuilder builder = null;
        for (Object value : values) {
            if (!(value instanceof CharSequence)) {
                continue;
            }
            CharSequence text = (CharSequence) value;
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            String piece = text.toString();
            if (builder == null) {
                builder = new StringBuilder(piece);
            } else if (!builder.toString().contains(piece)) {
                builder.append(' ').append(piece);
            }
        }
        return builder;
    }

    /**
     * Text colour for a part. A colour that would be invisible on the black island falls back to
     * white instead of rendering unreadable text.
     */
    private static int textColor(IslandModel.Part part) {
        int color = part == null ? 0 : (part.darkColor != 0 ? part.darkColor : part.color);
        if (color == 0) {
            return 0xFFFFFFFF;
        }
        float luminance = (0.299f * android.graphics.Color.red(color)
                + 0.587f * android.graphics.Color.green(color)
                + 0.114f * android.graphics.Color.blue(color)) / 255f;
        return luminance < 0.3f ? 0xFFFFFFFF : color;
    }

    /** Largest hour value rendered; anything beyond that is clamped to keep the buffer bounded. */
    private static final long MAX_TIMER_HOURS = 99999L;

    /** One registered timer view plus the last whole second written to it. */
    private static final class TimerSlot {
        final IslandModel.Timer timer;
        long lastSeconds = Long.MIN_VALUE;

        TimerSlot(IslandModel.Timer timer) {
            this.timer = timer;
        }
    }

    /**
     * Writes {@code MM:SS} or {@code H+:MM:SS} into {@code out} and returns the written length.
     * Allocation free on purpose: the caller owns the buffer and this runs once per second per
     * running timer.
     */
    private static int formatTimer(long millis, char[] out) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        int length;
        if (hours > 0) {
            length = writeNumber(out, 0, Math.min(hours, MAX_TIMER_HOURS));
            out[length++] = ':';
        } else {
            length = 0;
        }
        length = writeTwoDigits(out, length, minutes);
        out[length++] = ':';
        return writeTwoDigits(out, length, seconds);
    }

    private static int writeNumber(char[] out, int index, long value) {
        int digits = 1;
        for (long remaining = value; remaining >= 10; remaining /= 10) {
            digits++;
        }
        int cursor = index + digits;
        int end = cursor;
        long remaining = value;
        while (cursor > index) {
            out[--cursor] = (char) ('0' + (int) (remaining % 10));
            remaining /= 10;
        }
        return end;
    }

    private static int writeTwoDigits(char[] out, int index, long value) {
        long clamped = value < 0 ? 0 : Math.min(value, 99);
        out[index] = (char) ('0' + (int) (clamped / 10));
        out[index + 1] = (char) ('0' + (int) (clamped % 10));
        return index + 2;
    }

    // ---------------------------------------------------------------- input

    private final class GestureHandler implements View.OnTouchListener {

        private final boolean mPrimaryTarget;

        GestureHandler(boolean primaryTarget) {
            mPrimaryTarget = primaryTarget;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            IslandModel model = mPrimaryTarget ? mPrimary : mSecondary;
            if (model == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchDownX = event.getRawX();
                    mTouchDownY = event.getRawY();
                    mLongPressFired = false;
                    mDragCancelled = false;
                    mLongPressModel = model;
                    removeCallbacks(mLongPressRunnable);
                    postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!mDragCancelled
                            && Math.abs(event.getRawY() - mTouchDownY) > mTouchSlopPx) {
                        mDragCancelled = true;
                        removeCallbacks(mLongPressRunnable);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(mLongPressRunnable);
                    if (mLongPressFired) {
                        return true;
                    }
                    float deltaY = event.getRawY() - mTouchDownY;
                    float deltaX = event.getRawX() - mTouchDownX;
                    if (!mDragCancelled && deltaY < -mDismissThresholdPx
                            && Math.abs(deltaY) > Math.abs(deltaX)) {
                        if (mListener != null) {
                            mListener.onIslandDismissed(model);
                        }
                    } else if (!mDragCancelled) {
                        if (mListener != null) {
                            mListener.onIslandClicked(model);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(mLongPressRunnable);
                    return true;
                default:
                    return false;
            }
        }
    }
}
