/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Native SystemUI entry point for the island.
 *
 * <p>It wires the notification bridge, the rendering controller and every system signal the island
 * must respect: screen on/off, dozing/AOD, the notification shade, keyguard, display rotation and
 * user switches. All callbacks arrive on the main thread.
 *
 * <p>Nothing is started when the ROM does not ship the engine: the capability property is read-only
 * and its absence keeps the whole feature inert.
 */
@SysUISingleton
public final class IslandStartable implements CoreStartable {

    private static final String TAG = "IslandStartable";

    private final Context mContext;
    private final IslandController mController;
    private final IslandNotificationBridge mBridge;
    private final IslandPreferences mPreferences;
    private final UserTracker mUserTracker;
    private final ConfigurationController mConfigurationController;
    private final ScreenLifecycle mScreenLifecycle;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final Executor mMainExecutor;

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mController.setScreenOn(true);
        }

        @Override
        public void onScreenTurnedOff() {
            mController.setScreenOn(false);
        }
    };

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onUnlockedChanged() {
                    updateKeyguard();
                }

                @Override
                public void onKeyguardShowingChanged() {
                    updateKeyguard();
                }
            };

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onExpandedChanged(boolean isExpanded) {
                    mController.setShadeExpanded(isExpanded);
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    mController.setDozing(isDozing);
                }
            };

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    mController.setOrientationAllowed(
                            newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE);
                    mController.onDisplayChanged();
                }

                @Override
                public void onOrientationChanged(int orientation) {
                    mController.setOrientationAllowed(
                            orientation != Configuration.ORIENTATION_LANDSCAPE);
                }

                @Override
                public void onMovedToDisplay(int displayId, Configuration newConfiguration) {
                    mController.onDisplayChanged();
                }
            };

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onBeforeUserSwitching(int newUser) {
            // Freeze intake before the user changes: no parse started for the previous user may be
            // published, and no previous user content may stay on screen.
            mBridge.onUserChanging();
            mController.setUserSwitching(true);
        }

        @Override
        public void onUserChanged(int newUser, Context userContext) {
            // Preferences first: the new user's configuration must be in place before the bridge
            // replays that user's notifications.
            mPreferences.onUserChanged(userContext);
            mController.onUserChanged();
            // Re-advertise the protocol for the new user and replay that user's notifications.
            writeProtocolSetting(newUser);
            mBridge.onUserChanged();
            // System signals may not fire again for the new user, so re-read them.
            reseedState();
            // Last: nothing may be shown until every source above is consistent.
            mController.setUserSwitching(false);
        }
    };

    @Inject
    public IslandStartable(
            @Application Context context,
            IslandController controller,
            IslandNotificationBridge bridge,
            IslandPreferences preferences,
            UserTracker userTracker,
            ConfigurationController configurationController,
            ScreenLifecycle screenLifecycle,
            KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController,
            @Main Executor mainExecutor) {
        mContext = context;
        mController = controller;
        mBridge = bridge;
        mPreferences = preferences;
        mUserTracker = userTracker;
        mConfigurationController = configurationController;
        mScreenLifecycle = screenLifecycle;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mMainExecutor = mainExecutor;
    }

    @Override
    public void start() {
        if (!IslandPreferences.isFeatureInstalled()) {
            Log.i(TAG, "Island engine is not part of this build; nothing to start");
            return;
        }

        mController.setIconResolver(mBridge::resolveIcon);

        // The injected instance is created from the application context, which is user 0 in the
        // SystemUI process that tracks the foreground user: bind it to the current user before
        // anything reads it.
        mPreferences.onUserChanged(mUserTracker.getUserContext());

        // Seed the state machine before any activity can be published.
        reseedState();

        mController.start();
        mBridge.start();

        // Advertise the protocol to clients only now that the engine is actually running.
        writeProtocolSetting(mUserTracker.getUserId());

        mScreenLifecycle.addObserver(mScreenObserver);
        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.addCallback(mStateListener);
        mConfigurationController.addCallback(mConfigurationListener);
        mUserTracker.addCallback(mUserCallback, mMainExecutor);
    }

    /**
     * Reads every system signal the island depends on. Used once at start and again after a user
     * switch, because those signals are not guaranteed to fire again for the new user.
     */
    private void reseedState() {
        mController.setScreenOn(mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON);
        updateKeyguard();
        mController.setDozing(mStatusBarStateController.isDozing());
        mController.setShadeExpanded(mStatusBarStateController.isExpanded());
        Configuration configuration = mContext.getResources().getConfiguration();
        mController.setOrientationAllowed(
                configuration.orientation != Configuration.ORIENTATION_LANDSCAPE);
    }

    /**
     * The island must never be visible while the keyguard is up: {@code isUnlocked()} alone is not
     * enough, because a trusted or face authenticated state unlocks the device while the keyguard
     * is still showing.
     */
    private void updateKeyguard() {
        boolean locked = !mKeyguardStateController.isUnlocked()
                || mKeyguardStateController.isShowing();
        mController.setDeviceLocked(locked);
    }

    private void writeProtocolSetting(int userId) {
        try {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    IslandPreferences.SETTING_PROTOCOL, IslandPreferences.PROTOCOL_VERSION, userId);
        } catch (Exception e) {
            Log.w(TAG, "Unable to advertise the island protocol for user " + userId, e);
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("IslandStartable: installed=" + IslandPreferences.isFeatureInstalled()
                + " enabled=" + mPreferences.isEnabled());
    }
}
