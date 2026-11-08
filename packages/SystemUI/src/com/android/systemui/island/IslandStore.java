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

package com.android.systemui.island;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ordered, bounded, keyed store of live island activities.
 *
 * <p>Design points:
 * <ul>
 *   <li>Plain Java plus {@link SystemClock}: the only Android dependency is the default clock, so
 *       the whole state machine is deterministic and testable with an injected {@link Clock}.
 *   <li>Single threaded. Every method must be called from the same thread (the island's main
 *       thread); there is deliberately no synchronization.
 *   <li>Bounded. At most {@link #MAX_VISIBLE} activities are exposed, at most {@link #MAX_TRACKED}
 *       are retained, at most {@link #MAX_TOMBSTONES} cancellation tombstones are remembered.
 *   <li>Expiry uses injected elapsed-realtime millis, never wall clock, so a clock jump cannot make
 *       activities expire early or late.
 * </ul>
 *
 * <p>Update semantics, matching the public client protocol:
 * <ul>
 *   <li>Same key updates in place and never duplicates. A cancel-then-repost of the same
 *       notification id maps onto the same key, so it updates rather than duplicating.
 *   <li>A new key is inserted at the front, so the newest activity is the primary one.
 *   <li>{@code model.reorder} moves an updated entry to the front; without it the position is kept.
 *   <li>{@code model.dismissIsland} collapses the island but keeps the entry tracked, so a later
 *       update can restore it. The notification itself is never touched by this class.
 *   <li>{@code model.cancel} removes the entry. This class does not cancel notifications.
 *   <li>A cancellation tombstone is remembered only for a cancelled entry that declared
 *       {@code updatable == true} together with {@code reopen == "close"}: that is the case the
 *       protocol says must stay hidden after a user cancellation. Every other cancel, including the
 *       default {@code updatable == false}, reappears when the notification is sent again. A re-post
 *       that explicitly declares {@code updatable && reopen == "reopen"} clears the tombstone.
 * </ul>
 */
public final class IslandStore {

    /** Injected monotonic time source. */
    public interface Clock {
        /** Elapsed realtime in millis; must be monotonic. */
        long elapsedRealtime();
    }

    /**
     * Change callback. Invoked after every successful {@link #put} and after every mutation that
     * actually altered the store (removal, dismiss, expiry, clear); no-op calls stay silent.
     */
    public interface Listener {
        /** @param visibleSnapshot immutable snapshot of the visible activities, index 0 first */
        void onIslandStoreChanged(List<IslandModel> visibleSnapshot);
    }

    /** Default clock backed by {@link SystemClock#elapsedRealtime()}. */
    public static final Clock ELAPSED_REALTIME_CLOCK = new Clock() {
        @Override
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    };

    /** Maximum simultaneously visible activities (large + small). */
    public static final int MAX_VISIBLE = 2;
    /** Maximum retained activities, including hidden ones. */
    public static final int MAX_TRACKED = 8;
    /** Maximum remembered cancellation tombstones. */
    public static final int MAX_TOMBSTONES = 64;

    private final Clock mClock;
    private final List<Entry> mEntries = new ArrayList<>();
    private final LinkedHashSet<String> mTombstones = new LinkedHashSet<>();
    private Listener mListener;
    private long mInsertionOrder;

    public IslandStore(Clock clock, Listener listener) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        mClock = clock;
        mListener = listener;
    }

    public IslandStore(Clock clock) {
        this(clock, null);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    // ---------------------------------------------------------------------------------------------
    // Mutation
    // ---------------------------------------------------------------------------------------------

    /**
     * Adds or updates an activity.
     *
     * @return true when the activity was accepted and stored; false when the model is unusable or
     *     the key is still blocked by a cancellation tombstone
     */
    public boolean put(IslandModel model) {
        if (model == null || model.key == null || model.key.isEmpty()) {
            return false;
        }
        final String key = model.key;
        if (model.cancel) {
            // The sender asked for the notification to be ended; drop the island entry. Cancelling
            // the notification itself is the bridge's job, not this class's.
            return remove(key);
        }
        if (model.updatable && IslandModel.REOPEN.equals(model.reopen)) {
            // The sender explicitly opted this key back in to reopening.
            mTombstones.remove(key);
        } else if (isTombstoned(key)) {
            // Previously cancelled with reopen == close; stay hidden.
            return false;
        }

        final long now = mClock.elapsedRealtime();
        final long expires = computeExpiry(model, now);
        Entry existing = find(key);
        if (existing != null) {
            existing.model = model;
            existing.expiresElapsed = expires;
            existing.hidden = model.dismissIsland;
            if (model.reorder) {
                moveToFront(existing);
            }
            notifyChanged();
            return true;
        }
        Entry entry = new Entry(model, mInsertionOrder++, expires, model.dismissIsland);
        mEntries.add(0, entry);
        trimToBound();
        notifyChanged();
        return true;
    }

    /**
     * Removes an activity because its notification was cancelled. Records a cancellation tombstone
     * for entries that declared {@code updatable && reopen == "close"}.
     *
     * @return true when an entry was removed
     */
    public boolean remove(String key) {
        Entry entry = find(key);
        if (entry == null) {
            return false;
        }
        mEntries.remove(entry);
        if (entry.model.updatable && IslandModel.CLOSE.equals(entry.model.reopen)) {
            rememberTombstone(key);
        }
        notifyChanged();
        return true;
    }

    /**
     * Hides an activity on the island without touching its notification (user swipe / island only
     * dismiss). The entry stays tracked so it can be restored by a later update.
     *
     * @return true when the visible set changed
     */
    public boolean dismiss(String key) {
        Entry entry = find(key);
        if (entry == null || entry.hidden) {
            return false;
        }
        entry.hidden = true;
        notifyChanged();
        return true;
    }

    /** Expires activities whose island lifetime elapsed. */
    public void tick() {
        final long now = mClock.elapsedRealtime();
        boolean changed = false;
        for (int i = mEntries.size() - 1; i >= 0; i--) {
            Entry entry = mEntries.get(i);
            if (entry.expiresElapsed != 0L && now >= entry.expiresElapsed) {
                // Expiry is not a cancellation: a fresh notification may show again.
                mEntries.remove(i);
                changed = true;
            }
        }
        if (changed) {
            notifyChanged();
        }
    }

    /** Drops every activity and tombstone. */
    public void clear() {
        if (mEntries.isEmpty() && mTombstones.isEmpty()) {
            return;
        }
        mEntries.clear();
        mTombstones.clear();
        notifyChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------

    /**
     * Immutable snapshot of the visible activities, in display order: index 0 is the primary (large)
     * activity, index 1 the small one. At most {@link #MAX_VISIBLE} entries.
     */
    public List<IslandModel> snapshot() {
        List<IslandModel> out = new ArrayList<>(Math.min(mEntries.size(), MAX_VISIBLE));
        for (int i = 0; i < mEntries.size() && out.size() < MAX_VISIBLE; i++) {
            Entry entry = mEntries.get(i);
            if (!entry.hidden) {
                out.add(entry.model);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Immutable snapshot of every tracked activity, including hidden ones. */
    public List<IslandModel> all() {
        List<IslandModel> out = new ArrayList<>(mEntries.size());
        for (Entry entry : mEntries) {
            out.add(entry.model);
        }
        return Collections.unmodifiableList(out);
    }

    /** Model for {@code key}, or null. */
    public IslandModel get(String key) {
        Entry entry = find(key);
        return entry == null ? null : entry.model;
    }

    /** True when the entry exists but is hidden on the island. */
    public boolean isHidden(String key) {
        Entry entry = find(key);
        return entry != null && entry.hidden;
    }

    /** True when the key was cancelled and must stay hidden. */
    public boolean isTombstoned(String key) {
        return key != null && mTombstones.contains(key);
    }

    /** Number of tracked activities, including hidden ones. */
    public int size() {
        return mEntries.size();
    }

    public boolean isEmpty() {
        return mEntries.isEmpty();
    }

    /** Remaining island lifetime in millis, or 0 when the entry never expires or is absent. */
    public long remainingMillis(String key) {
        Entry entry = find(key);
        if (entry == null || entry.expiresElapsed == 0L) {
            return 0L;
        }
        return Math.max(0L, entry.expiresElapsed - mClock.elapsedRealtime());
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private static final class Entry {
        IslandModel model;
        final long insertionOrder;
        long expiresElapsed;
        boolean hidden;

        Entry(IslandModel model, long insertionOrder, long expiresElapsed, boolean hidden) {
            this.model = model;
            this.insertionOrder = insertionOrder;
            this.expiresElapsed = expiresElapsed;
            this.hidden = hidden;
        }
    }

    private Entry find(String key) {
        if (key == null) {
            return null;
        }
        for (int i = 0; i < mEntries.size(); i++) {
            if (key.equals(mEntries.get(i).model.key)) {
                return mEntries.get(i);
            }
        }
        return null;
    }

    private void moveToFront(Entry entry) {
        if (mEntries.remove(entry)) {
            mEntries.add(0, entry);
        }
    }

    private void trimToBound() {
        while (mEntries.size() > MAX_TRACKED) {
            // Evict the oldest inserted entry; that is not necessarily the last one, because a
            // reordered entry can be old while sitting at the front.
            int oldestIndex = 0;
            for (int i = 1; i < mEntries.size(); i++) {
                if (mEntries.get(i).insertionOrder < mEntries.get(oldestIndex).insertionOrder) {
                    oldestIndex = i;
                }
            }
            mEntries.remove(oldestIndex);
        }
    }

    private void rememberTombstone(String key) {
        mTombstones.remove(key);
        mTombstones.add(key);
        while (mTombstones.size() > MAX_TOMBSTONES) {
            mTombstones.remove(mTombstones.iterator().next());
        }
    }

    /**
     * Island lifetime in millis from now, or 0 for "never". The island lifetime is preferred because
     * it is the one the protocol defines for the island itself; the notification lifetime only
     * applies when the sender supplied no island lifetime.
     */
    private static long computeExpiry(IslandModel model, long now) {
        long lifetime = model.islandLifetimeMillis > 0L
                ? model.islandLifetimeMillis : model.lifetimeMillis;
        if (lifetime <= 0L) {
            return 0L;
        }
        long expires = now + lifetime;
        return expires < 0L ? 0L : expires;
    }

    private void notifyChanged() {
        Listener listener = mListener;
        if (listener != null) {
            listener.onIslandStoreChanged(snapshot());
        }
    }
}
