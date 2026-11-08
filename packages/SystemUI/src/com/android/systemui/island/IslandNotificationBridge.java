/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

/**
 * Subscribes the island to the native notification pipeline.
 *
 * <p>The bridge is a plain {@link NotifCollectionListener}: it never cancels, promotes or otherwise
 * mutates a notification. When the user dismisses the island the underlying notification is left
 * untouched.
 *
 * <p>Work is split by thread on purpose. Parsing the Xiaomi payload and decoding icons happens on
 * the single threaded background executor, results are cached per notification key, and only the
 * finished model is published on the main thread. A tick never parses, decodes or allocates.
 *
 * <p>Two kinds of removal are distinguished. A real cancellation calls {@code remove}, which is
 * what the store's reopen/tombstone policy applies to. A temporary suppression (DND, ranking
 * suppression, ambient, per-application preference) only withdraws the island entry, so turning DND
 * off brings the island back instead of being blocked by a tombstone forever.
 */
@SysUISingleton
public final class IslandNotificationBridge
        implements NotifCollectionListener, IslandPreferences.Listener {

    private static final String TAG = "IslandNotificationBridge";

    /** Maximum number of decoded icons kept in the cache. */
    private static final int MAX_CACHED_ICONS = 96;

    private final Context mContext;
    private final CommonNotifCollection mCollection;
    private final IslandPreferences mPreferences;
    private final IslandController mController;
    private final UserTracker mUserTracker;
    private final ZenModeController mZenModeController;
    private final DelayableExecutor mBackgroundExecutor;
    private final DelayableExecutor mMainExecutor;

    /** Parsed models by notification key. Main thread only. */
    private final Map<String, IslandModel> mModels = new ArrayMap<>();

    /** Notifications that parsed to "not island content", keyed by the exact revision seen. */
    private final Map<String, StatusBarNotification> mNullFor = new ArrayMap<>();

    /** Latest revision awaiting a parse, by key. Main thread only. */
    private final Map<String, Pending> mQueued = new ArrayMap<>();

    /** Generation currently being parsed, by key. Main thread only. */
    private final Map<String, Long> mInFlight = new ArrayMap<>();

    /** Decoded drawables. Written on the background executor, read on main. */
    private final Map<Icon, Drawable> mIcons = new ConcurrentHashMap<>();

    private long mGeneration;

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            refreshEligibility();
        }

        @Override
        public void onConsolidatedPolicyChanged(NotificationManager.Policy policy) {
            refreshEligibility();
        }
    };

    private boolean mStarted;
    /** True between the before/after user switch callbacks; all intake is ignored meanwhile. */
    private boolean mUserSwitching;

    /** One scheduled parse. The generation ties the result to the revision that asked for it. */
    private static final class Pending {
        final StatusBarNotification sbn;
        final long generation;

        Pending(StatusBarNotification sbn, long generation) {
            this.sbn = sbn;
            this.generation = generation;
        }
    }

    @Inject
    public IslandNotificationBridge(
            @Application Context context,
            CommonNotifCollection collection,
            IslandPreferences preferences,
            IslandController controller,
            UserTracker userTracker,
            ZenModeController zenModeController,
            @Background DelayableExecutor backgroundExecutor,
            @Main DelayableExecutor mainExecutor) {
        mContext = context;
        mCollection = collection;
        mPreferences = preferences;
        mController = controller;
        mUserTracker = userTracker;
        mZenModeController = zenModeController;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
    }

    /** Registers with the pipeline and replays every notification that is already posted. */
    public void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mCollection.addCollectionListener(this);
        mPreferences.addListener(this);
        mZenModeController.addCallback(mZenCallback);
        replay();
    }

    /**
     * Freezes intake before the foreground user changes. Queued and in-flight parses are cancelled,
     * and every later callback is ignored until {@link #onUserChanged()} completes, so no model
     * parsed for the previous user can be published in between.
     */
    public void onUserChanging() {
        mUserSwitching = true;
        cancelAll();
    }

    /**
     * The device switched user: every cached model and icon belongs to the previous user and must
     * be dropped before the new user's notifications are replayed.
     */
    public void onUserChanged() {
        cancelAll();
        mModels.clear();
        mNullFor.clear();
        mIcons.clear();
        mUserSwitching = false;
        if (mStarted) {
            replay();
        }
    }

    /** Icon resolver handed to the rendering layer. Never decodes anything. */
    public Drawable resolveIcon(Icon icon) {
        return icon == null ? null : mIcons.get(icon);
    }

    // ------------------------------------------------------------ pipeline events

    @Override
    public void onEntryAdded(NotificationEntry entry) {
        handleEntry(entry);
    }

    @Override
    public void onEntryUpdated(NotificationEntry entry) {
        handleEntry(entry);
    }

    @Override
    public void onEntryRemoved(NotificationEntry entry, int reason) {
        dropEntry(entry.getKey());
    }

    @Override
    public void onEntryCleanUp(NotificationEntry entry) {
        dropEntry(entry.getKey());
    }

    @Override
    public void onRankingApplied() {
        refreshEligibility();
    }

    @Override
    public void onIslandPreferencesChanged() {
        // A preference change never changes the payload, so cached models are reused as is: only
        // visibility is re-evaluated.
        refreshEligibility();
    }

    // ------------------------------------------------------------ bookkeeping

    private void replay() {
        List<NotificationEntry> entries = new ArrayList<>(mCollection.getAllNotifs());
        // Oldest first: the store inserts new activities at the front, so publishing in posting
        // order leaves the newest activity as the primary one. The background executor is single
        // threaded, which keeps the publish order identical to this order.
        entries.sort(Comparator.comparingLong(IslandNotificationBridge::postTime));
        for (NotificationEntry entry : entries) {
            handleEntry(entry);
        }
    }

    private static long postTime(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();
        return sbn == null ? 0L : sbn.getPostTime();
    }

    private void handleEntry(NotificationEntry entry) {
        if (entry == null || mUserSwitching) {
            return;
        }
        String key = entry.getKey();
        StatusBarNotification sbn = entry.getSbn();
        if (key == null || sbn == null) {
            return;
        }
        if (!isCandidate(sbn)) {
            dropEntry(key);
            return;
        }
        if (!isEligible(entry)) {
            // The notification changed while it was suppressed, so the cached model is stale.
            suspend(key, true);
            return;
        }
        mNullFor.remove(key);
        scheduleParse(key, sbn);
    }

    /** Real cancellation: the notification is gone and the store's tombstone policy applies. */
    private void dropEntry(String key) {
        if (key == null) {
            return;
        }
        cancelPending(key);
        mModels.remove(key);
        mNullFor.remove(key);
        mController.removeModel(key);
    }

    /** Temporary suppression: island entry withdrawn, model kept unless it is stale. */
    private void suspend(String key, boolean staleModel) {
        cancelPending(key);
        if (staleModel) {
            mModels.remove(key);
        }
        mController.withdrawModel(key);
    }

    /**
     * Re-evaluates every posted notification against the current ranking, DND and preference
     * state. Cached models are reused, so a preference-only change never re-parses a payload.
     */
    private void refreshEligibility() {
        if (mUserSwitching) {
            return;
        }
        for (NotificationEntry entry : new ArrayList<>(mCollection.getAllNotifs())) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            StatusBarNotification sbn = entry.getSbn();
            if (sbn == null || !isCandidate(sbn)) {
                continue;
            }
            boolean eligible = isEligible(entry);
            if (mModels.containsKey(key)) {
                if (eligible) {
                    mController.reshowModel(key);
                } else {
                    mController.withdrawModel(key);
                }
                continue;
            }
            if (!eligible || mNullFor.get(key) == sbn) {
                continue;
            }
            if (mInFlight.containsKey(key) || mQueued.containsKey(key)) {
                continue;
            }
            scheduleParse(key, sbn);
        }
    }

    /**
     * Cheap pre-filter so ordinary notifications are never handed to the parser. A notification is
     * only interesting when it carries the public client payload or is a promoted ongoing
     * notification.
     */
    private static boolean isCandidate(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return false;
        }
        if (IslandProtocol.isPromotedOngoing(notification)) {
            return true;
        }
        Bundle extras = notification.extras;
        return extras != null
                && (extras.containsKey(IslandProtocol.EXTRA_ISLAND)
                        || extras.containsKey(IslandProtocol.EXTRA_PICS)
                        || extras.containsKey(IslandProtocol.EXTRA_ACTIONS));
    }

    /**
     * Native pipeline state that gates the island: other users, ambient/AOD, visual suppression
     * (how DND and blocked channels surface per notification), suspended packages, filters, and the
     * user's per-application preference.
     *
     * <p>Suppression is read from the entry, which already accounts for whether DND is actually
     * active and for per-notification exemptions. The raw consolidated DND policy is deliberately
     * not consulted, because a configured policy applies only while zen is on.
     */
    private boolean isEligible(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();
        if (sbn == null || sbn.getNotification() == null) {
            return false;
        }
        if (sbn.getUser().getIdentifier() != mUserTracker.getUserId()) {
            return false;
        }
        if (!mPreferences.isEnabled() || !mPreferences.isPackageAllowed(sbn.getPackageName())) {
            return false;
        }
        if (entry.isAmbient()) {
            return false;
        }
        if ((entry.getSuppressedVisualEffects()
                & NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR) != 0) {
            return false;
        }
        if (entry.getExcludingFilter() != null) {
            return false;
        }
        Ranking ranking = entry.getRanking();
        if (ranking != null) {
            if (ranking.isSuspended()) {
                return false;
            }
            if (ranking.getImportance() <= NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ background parsing

    private void cancelPending(String key) {
        mQueued.remove(key);
        mInFlight.remove(key);
        mNullFor.remove(key);
    }

    private void cancelAll() {
        mQueued.clear();
        mInFlight.clear();
    }

    private void scheduleParse(String key, StatusBarNotification sbn) {
        long generation = ++mGeneration;
        mQueued.put(key, new Pending(sbn, generation));
        if (mInFlight.containsKey(key)) {
            return;
        }
        dispatchNext(key);
    }

    private void dispatchNext(String key) {
        final Pending pending = mQueued.remove(key);
        if (pending == null) {
            mInFlight.remove(key);
            return;
        }
        mInFlight.put(key, pending.generation);
        mBackgroundExecutor.execute(() -> {
            IslandModel model = null;
            try {
                model = IslandProtocol.parse(mContext, pending.sbn);
            } catch (Throwable t) {
                // A malformed payload must never take SystemUI down: the ordinary notification
                // stays exactly as it is.
                Log.w(TAG, "Failed to parse island payload for " + pending.sbn.getKey(), t);
            }
            if (model != null) {
                preloadIcons(model);
            }
            final IslandModel parsed = model;
            mMainExecutor.execute(() -> onParsed(key, pending.generation, parsed));
        });
    }

    private void onParsed(String key, long generation, IslandModel model) {
        if (mUserSwitching) {
            // A user switch invalidated every outstanding parse; nothing may be published.
            return;
        }
        Long inFlight = mInFlight.get(key);
        if (inFlight == null || inFlight.longValue() != generation) {
            // Cancelled, superseded by a newer revision, or invalidated by a user switch.
            return;
        }
        if (mQueued.containsKey(key)) {
            // A newer revision arrived while parsing; render the newest one instead.
            dispatchNext(key);
            return;
        }
        mInFlight.remove(key);
        NotificationEntry entry = mCollection.getEntry(key);
        if (entry == null) {
            dropEntry(key);
            return;
        }
        if (model == null) {
            StatusBarNotification sbn = entry.getSbn();
            if (sbn != null) {
                mNullFor.put(key, sbn);
            }
            dropEntry(key);
            return;
        }
        if (!isEligible(entry)) {
            // Register the model in the store as withheld in one step: the store's own change
            // callback fires before this method returns, so the suppression must already be in place
            // or the island would flash once.
            mModels.put(key, model);
            mController.putModel(model, false);
            return;
        }
        mModels.put(key, model);
        // model.filterWhenNoPermission is deliberately not acted upon here: honouring it would mean
        // filtering the ordinary notification out of the shade, and the island never mutates
        // notification presentation. Only the island rendering itself is gated, by isEligible().
        mController.putModel(model, true);
    }

    private void preloadIcons(IslandModel model) {
        int userId = model.userId;
        resolveAndCache(model.appIcon, userId);
        preloadPart(model.left, userId);
        preloadPart(model.right, userId);
        preloadPart(model.small, userId);
        List<IslandModel.Part> expanded = model.expanded;
        if (expanded != null) {
            for (IslandModel.Part part : expanded) {
                preloadPart(part, userId);
            }
        }
        List<IslandModel.Action> actions = model.actions;
        if (actions != null) {
            for (IslandModel.Action action : actions) {
                resolveAndCache(action.icon, userId);
            }
        }
    }

    /** Both theme variants are decoded so the renderer never has to decode on the main thread. */
    private void preloadPart(IslandModel.Part part, int userId) {
        if (part == null) {
            return;
        }
        resolveAndCache(part.icon, userId);
        resolveAndCache(part.darkIcon, userId);
        List<IslandModel.Action> actions = part.actions;
        if (actions != null) {
            for (IslandModel.Action action : actions) {
                resolveAndCache(action.icon, userId);
            }
        }
    }

    private void resolveAndCache(Icon icon, int userId) {
        if (icon == null || mIcons.containsKey(icon)) {
            return;
        }
        if (mIcons.size() >= MAX_CACHED_ICONS) {
            // The rendering layer keeps its own reference to every drawable it is using, so
            // dropping the cache can never blank a visible icon.
            mIcons.clear();
        }
        try {
            // IslandProtocol owns the single icon policy: URI and data icons are rejected there and
            // the remaining types are loaded for the notification's own user.
            Drawable drawable = IslandProtocol.loadIcon(mContext, icon, userId);
            if (drawable != null) {
                mIcons.put(icon, drawable);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to decode island icon of type " + icon.getType(), t);
        }
    }
}
