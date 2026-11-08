/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.content.Context;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 * Owns the island state machine: which activities are visible, whether the card is expanded, and
 * whether the island may be on screen at all.
 *
 * <p>Everything here runs on the main thread. The controller is also the input handler for the
 * rendered content, so privileged action dispatch only ever happens in response to real user input
 * while the device is unlocked.
 */
@SysUISingleton
public final class IslandController
        implements IslandStore.Listener, IslandContentView.Listener,
        IslandPreferences.Listener {

    private static final String TAG = "IslandController";

    /** Cadence of the timer refresh while the island is on screen. */
    private static final long TICK_INTERVAL_MS = 1000L;

    /** How long the card stays open after it was opened automatically, by a tap or a long press. */
    private static final long EXPANDED_TIMEOUT_MS = 5000L;

    private final Context mContext;
    private final IslandPreferences mPreferences;
    private final IslandWindow mWindow;
    private final IslandStore mStore;
    private final Handler mMainHandler;

    private final List<IslandModel> mActivities = new ArrayList<>();

    /**
     * Keys suppressed by the island itself (DND, ranking suppression, ambient, per-application
     * preference). Kept here on purpose: {@code IslandStore} owns the user's swipe dismissal and the
     * activity lifetime, so suppression must not go through the store or it would clear the
     * dismissal and reset the lifetime on every ranking refresh.
     */
    private final Set<String> mWithheldKeys = new ArraySet<>();

    private final Runnable mTickRunnable = this::onTick;
    private final Runnable mCollapseRunnable = () -> setExpanded(false);

    private boolean mStarted;
    private boolean mExpanded;
    private boolean mScreenOn = true;
    private boolean mDozing;
    private boolean mShadeExpanded;
    /** Locked until the startable seeds the real keyguard state; fails closed, never open. */
    private boolean mDeviceLocked = true;
    private boolean mOrientationAllowed = true;
    private boolean mTicking;
    /** True between the before/after user switch callbacks; fails closed for the whole window. */
    private boolean mUserSwitching;

    @Inject
    public IslandController(
            @Application Context context,
            IslandPreferences preferences,
            IslandWindow window,
            @Main Handler mainHandler) {
        mContext = context;
        mPreferences = preferences;
        mWindow = window;
        mMainHandler = mainHandler;
        mStore = new IslandStore(IslandStore.ELAPSED_REALTIME_CLOCK, this);
        mWindow.setListener(this);
    }

    public void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mPreferences.addListener(this);
        refresh();
    }

    /** Installs the icon resolver used by the rendering layer. */
    public void setIconResolver(IslandContentView.IconResolver resolver) {
        mWindow.setIconResolver(resolver);
    }

    // ------------------------------------------------------------ model intake

    /**
     * Publishes a parsed model. Called on the main thread by the bridge, atomically with the
     * eligibility decision so an ineligible model is registered as withheld before the store's
     * change callback can render it.
     *
     * <p>This is a real payload update, so it is the only path that goes through {@code put}: it
     * legitimately clears a previous user dismissal and restarts the activity lifetime.
     */
    public void putModel(IslandModel model, boolean eligible) {
        if (model == null || model.key == null) {
            return;
        }
        // firstFloat opens the card once per new lifecycle; later revisions use updateFloat.
        boolean isNew = mStore.get(model.key) == null;
        if (eligible) {
            mWithheldKeys.remove(model.key);
        } else {
            mWithheldKeys.add(model.key);
        }
        mStore.put(model);
        if (eligible && (isNew ? model.firstFloat : model.updateFloat)) {
            setExpanded(true);
        }
    }

    /** Removes a model because its notification is gone. */
    public void removeModel(String key) {
        mWithheldKeys.remove(key);
        mStore.remove(key);
    }

    /**
     * Temporarily suppresses a model that is still posted but must not be rendered right now. Only
     * island visibility changes: the store entry, its user dismissal and its lifetime are left
     * untouched.
     */
    public void withdrawModel(String key) {
        if (key != null && mWithheldKeys.add(key)) {
            updateUi();
        }
    }

    /**
     * Lifts a previous suppression. Never re-enters the store, so a user swipe dismissal and the
     * remaining lifetime survive a DND or preference toggle.
     */
    public void reshowModel(String key) {
        if (key == null || !mWithheldKeys.remove(key)) {
            return;
        }
        // Expire anything that ran out while the island was suppressed, so nothing flashes.
        mStore.tick();
        updateUi();
    }

    // ------------------------------------------------------------ external state

    public void setScreenOn(boolean screenOn) {
        if (mScreenOn == screenOn) {
            return;
        }
        mScreenOn = screenOn;
        refresh();
    }

    public void setDozing(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        refresh();
    }

    public void setShadeExpanded(boolean expanded) {
        if (mShadeExpanded == expanded) {
            return;
        }
        mShadeExpanded = expanded;
        refresh();
    }

    /**
     * The device is locked when the keyguard is showing or not yet unlocked. The island is hidden
     * in that state so no notification content is disclosed, and privileged dispatch is refused.
     */
    public void setDeviceLocked(boolean locked) {
        if (mDeviceLocked == locked) {
            return;
        }
        mDeviceLocked = locked;
        refresh();
    }

    public void setOrientationAllowed(boolean allowed) {
        if (mOrientationAllowed == allowed) {
            return;
        }
        mOrientationAllowed = allowed;
        refresh();
    }

    /** Display geometry changed; the window has to be re-positioned on the cutout. */
    public void onDisplayChanged() {
        mWindow.updateGeometry();
    }

    /**
     * Set while the foreground user is changing. Everything fails closed for that window: nothing
     * is shown, nothing is dispatched, and the previous user's content is removed immediately.
     */
    public void setUserSwitching(boolean switching) {
        if (mUserSwitching == switching) {
            return;
        }
        mUserSwitching = switching;
        refresh();
    }

    /** The device switched user; nothing of the previous user may stay on screen. */
    public void onUserChanged() {
        cancelCollapse();
        mExpanded = false;
        mWithheldKeys.clear();
        mStore.clear();
        mWindow.hideImmediately();
        updateUi();
    }

    // ------------------------------------------------------------ store / prefs

    @Override
    public void onIslandStoreChanged(List<IslandModel> visibleSnapshot) {
        updateUi();
    }

    @Override
    public void onIslandPreferencesChanged() {
        refresh();
    }

    private void refresh() {
        mStore.tick();
        updateUi();
    }

    private void updateUi() {
        mActivities.clear();
        // Build the visible set from the tracked entries, skipping the user's swipe dismissal (the
        // store's hidden flag) and the island's own suppression, in store order.
        for (IslandModel model : mStore.all()) {
            if (mActivities.size() >= IslandStore.MAX_VISIBLE) {
                break;
            }
            if (mStore.isHidden(model.key) || mWithheldKeys.contains(model.key)) {
                continue;
            }
            mActivities.add(model);
        }

        boolean shouldShow = shouldShow();
        if (!shouldShow && mExpanded) {
            cancelCollapse();
            mExpanded = false;
        }
        if (shouldShow) {
            mWindow.setContent(mActivities, mExpanded);
            mWindow.show();
        } else if (mDeviceLocked || mUserSwitching) {
            // Security gate: never let a fade-out keep island content on the lock screen or across a
            // user switch.
            mWindow.setContent(mActivities, mExpanded);
            mWindow.hideImmediately();
        } else {
            // Fade out what is on screen. Replacing it first would empty the pill before the exit
            // animation starts, so the island would just vanish.
            mWindow.hide();
        }
        updateTicking(shouldShow);
    }

    private boolean shouldShow() {
        return mStarted
                && !mUserSwitching
                && mPreferences.isEnabled()
                && mScreenOn
                && !mDozing
                && !mShadeExpanded
                && !mDeviceLocked
                && mOrientationAllowed
                && !mActivities.isEmpty();
    }

    // ------------------------------------------------------------ ticking

    private void updateTicking(boolean visible) {
        if (visible && (hasRunningTimer() || hasBoundedLifetime())) {
            armTick();
        } else {
            disarmTick();
        }
    }

    private void armTick() {
        if (mTicking) {
            return;
        }
        mTicking = true;
        mMainHandler.postDelayed(mTickRunnable, TICK_INTERVAL_MS);
    }

    private void disarmTick() {
        if (!mTicking) {
            return;
        }
        mTicking = false;
        mMainHandler.removeCallbacks(mTickRunnable);
    }

    private void onTick() {
        mTicking = false;
        long wallNow = System.currentTimeMillis();
        mWindow.updateTimers(wallNow);
        // Expiry lives in the store; a tick that changes the visible set re-enters updateUi().
        mStore.tick();
        updateTicking(shouldShow());
    }

    private boolean hasRunningTimer() {
        for (IslandModel model : mActivities) {
            if (hasRunningTimer(model)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRunningTimer(IslandModel model) {
        if (hasRunningTimer(model.left) || hasRunningTimer(model.right)
                || hasRunningTimer(model.small)) {
            return true;
        }
        List<IslandModel.Part> expanded = model.expanded;
        if (expanded != null) {
            for (IslandModel.Part part : expanded) {
                if (hasRunningTimer(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRunningTimer(IslandModel.Part part) {
        return part != null && part.timer != null && part.timer.running();
    }

    private boolean hasBoundedLifetime() {
        // Expiry is enforced by IslandStore.tick(); only activities that actually carry a lifetime
        // keep the tick loop alive.
        for (IslandModel model : mActivities) {
            if (model.lifetimeMillis > 0 || model.islandLifetimeMillis > 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ input

    @Override
    public void onIslandClicked(IslandModel model) {
        if (!canDispatch()) {
            return;
        }
        if (!mExpanded && !mActivities.isEmpty() && model.key != null
                && model.key.equals(mActivities.get(0).key) && mWindow.canExpand()) {
            // The first tap on the pill opens the card; tapping the open card launches the sender.
            setExpanded(true);
            return;
        }
        if (model.contentIntent != null) {
            if (!IslandActions.sendContent(mContext, model)) {
                Log.w(TAG, "Rejected content intent for " + model.packageName);
            }
            collapseSoon();
        } else {
            setExpanded(!mExpanded);
        }
    }

    @Override
    public void onIslandLongPressed(IslandModel model) {
        if (!canDispatch()) {
            return;
        }
        setExpanded(!mExpanded);
    }

    @Override
    public void onIslandActionClicked(IslandModel model, IslandModel.Action action) {
        if (!canDispatch()) {
            return;
        }
        if (IslandActions.send(mContext, action, model.packageName, model.uid, model.userId)) {
            if (action.collapse) {
                collapseSoon();
            }
        } else {
            Log.w(TAG, "Rejected island action for " + model.packageName);
        }
    }

    @Override
    public void onIslandDismissed(IslandModel model) {
        if (model.key == null) {
            return;
        }
        // Island-only dismissal: the notification itself is never cancelled or mutated.
        mStore.dismiss(model.key);
    }

    @Override
    public void onOutsideTouched() {
        if (!canDispatch()) {
            return;
        }
        collapseSoon();
    }
    /**
     * Privileged dispatch requires real user input on a visible island: if the island is not on
     * screen (locked, AOD, shade open, landscape, disabled) there is nothing to tap and no
     * dispatch happens.
     */
    private boolean canDispatch() {
        return mStarted && shouldShow();
    }

    // ------------------------------------------------------------ expansion

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) {
            return;
        }
        mExpanded = expanded;
        mWindow.setContent(mActivities, mExpanded);
        if (expanded) {
            scheduleCollapse();
        } else {
            cancelCollapse();
        }
    }

    private void collapseSoon() {
        if (mExpanded) {
            setExpanded(false);
        }
    }

    private void scheduleCollapse() {
        mMainHandler.removeCallbacks(mCollapseRunnable);
        mMainHandler.postDelayed(mCollapseRunnable, EXPANDED_TIMEOUT_MS);
    }

    private void cancelCollapse() {
        mMainHandler.removeCallbacks(mCollapseRunnable);
    }
}
