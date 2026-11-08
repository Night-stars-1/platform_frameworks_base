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

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, bounded, fully validated description of one island activity.
 *
 * <p>Instances are produced by {@link IslandProtocol#parse} and consumed by the island window and
 * by {@link IslandStore}. Every field is read-only; collections are unmodifiable copies. The model
 * never holds a reference to the source {@link android.service.notification.StatusBarNotification}
 * beyond the scalar identity fields below, so it can outlive the notification pipeline objects.
 *
 * <p>Fields map onto the public Xiaomi client notification protocol (param_v2 / param_island), see
 * the protocol owner notes. Additions beyond the minimum contract are documented per field.
 */
public final class IslandModel {

    /** {@code reopen} value: a cancelled notification with the same key may reappear. */
    public static final String REOPEN = "reopen";
    /** {@code reopen} value (protocol default): a cancelled notification stays hidden. */
    public static final String CLOSE = "close";

    // --- notification identity (from StatusBarNotification) ------------------------------------

    /** Stable store key, identical to {@link android.service.notification.StatusBarNotification#getKey()}. */
    public final String key;
    public final String packageName;
    /** Notification tag; empty string when untagged. */
    public final String tag;
    public final int id;
    public final int uid;
    public final int userId;
    /** Wall clock millis the notification was posted ({@code StatusBarNotification#getPostTime()}). */
    public final long postedAt;

    // --- island attributes (param_v2 + param_island) -------------------------------------------

    /** param_v2.islandFirstFloat: show expanded the first time this activity appears. */
    public final boolean firstFloat;
    /** param_v2.enableFloat: show expanded again when the activity is updated. */
    public final boolean updateFloat;
    /** param_island.islandOrder: move to the front of the ordering when updated. */
    public final boolean reorder;
    /** param_island.dismissIsland: this update collapses the island itself. */
    public final boolean dismissIsland;
    /** param_v2.updatable: the sender treats this as a persistent activity. */
    public final boolean updatable;
    /** param_v2.cancel: the sender asks for the notification to be ended. */
    public final boolean cancel;
    /**
     * param_v2.filterWhenNoPermission: when the sender's focus permission is disabled, true means
     * the notification must be filtered instead of degrading to a plain notification.
     */
    public final boolean filterWhenNoPermission;
    /** param_v2.timeout in millis (protocol default 720 min). 0 means "no expiry". */
    public final long lifetimeMillis;
    /** param_island.islandTimeout in millis (protocol default 3600 s). 0 means "no expiry". */
    public final long islandLifetimeMillis;
    /** param_v2.reopen, one of {@link #REOPEN} / {@link #CLOSE}. */
    public final String reopen;
    /** param_v2.business, operational scene tag used for statistics/routing. */
    public final String business;
    /** param_v2.aodTitle: always-on-display caption. */
    public final String aodTitle;
    /** param_v2.ticker: status bar caption (OS2 focus notifications). */
    public final String ticker;
    /** param_v2.protocol, sender declared protocol version; 0 when absent. */
    public final int protocol;
    /** param_island.highlightColor parsed ARGB, or 0 when absent. */
    public final int highlightColor;
    /** True for a {@code Notification.FLAG_PROMOTED_ONGOING} notification rendered without island JSON. */
    public final boolean promoted;
    /**
     * True when island JSON was present but could not be rendered (malformed or unsupported
     * template). Such a model is a normalized projection of the ordinary notification so that the
     * notification is never silently dropped.
     */
    public final boolean fallback;

    // --- content -------------------------------------------------------------------------------

    /** Big island area, left of the cutout (A area). Null when absent. */
    public final Part left;
    /** Big island area, right of the cutout (B area). Null when absent. */
    public final Part right;
    /** Small island area (second activity). Null when absent. */
    public final Part small;
    /** Expanded card components in protocol order. Never null, possibly empty. */
    public final List<Part> expanded;
    /** Action buttons in protocol order. Never null, possibly empty. */
    public final List<Action> actions;
    /** Content tap target. Null when the sender supplied none. */
    public final PendingIntent contentIntent;
    /**
     * Island/app icon: the sender's launcher icon when it can be resolved for the notification's
     * user, otherwise the notification small icon. Null when neither is available. Used as the last
     * step of the small island fallback.
     */
    public final Icon appIcon;

    private IslandModel(Builder b) {
        key = b.key;
        packageName = b.packageName;
        tag = b.tag;
        id = b.id;
        uid = b.uid;
        userId = b.userId;
        postedAt = b.postedAt;
        firstFloat = b.firstFloat;
        updateFloat = b.updateFloat;
        reorder = b.reorder;
        dismissIsland = b.dismissIsland;
        updatable = b.updatable;
        cancel = b.cancel;
        filterWhenNoPermission = b.filterWhenNoPermission;
        lifetimeMillis = b.lifetimeMillis;
        islandLifetimeMillis = b.islandLifetimeMillis;
        reopen = b.reopen;
        business = b.business;
        aodTitle = b.aodTitle;
        ticker = b.ticker;
        protocol = b.protocol;
        highlightColor = b.highlightColor;
        promoted = b.promoted;
        fallback = b.fallback;
        left = b.left;
        right = b.right;
        small = b.small;
        expanded = unmodifiable(b.expanded);
        actions = unmodifiable(b.actions);
        contentIntent = b.contentIntent;
        appIcon = b.appIcon;
    }

    private static <T> List<T> unmodifiable(List<T> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    @Override
    public String toString() {
        return "IslandModel{key=" + key + " pkg=" + packageName + " uid=" + uid
                + " promoted=" + promoted + " fallback=" + fallback
                + " parts=" + expanded.size() + " actions=" + actions.size() + "}";
    }

    /** Copies this model into a builder so callers can derive variants without re-parsing. */
    public Builder toBuilder() {
        return new Builder()
                .setKey(key)
                .setPackageName(packageName)
                .setTag(tag)
                .setId(id)
                .setUid(uid)
                .setUserId(userId)
                .setPostedAt(postedAt)
                .setFirstFloat(firstFloat)
                .setUpdateFloat(updateFloat)
                .setReorder(reorder)
                .setDismissIsland(dismissIsland)
                .setUpdatable(updatable)
                .setCancel(cancel)
                .setFilterWhenNoPermission(filterWhenNoPermission)
                .setLifetimeMillis(lifetimeMillis)
                .setIslandLifetimeMillis(islandLifetimeMillis)
                .setReopen(reopen)
                .setBusiness(business)
                .setAodTitle(aodTitle)
                .setTicker(ticker)
                .setProtocol(protocol)
                .setHighlightColor(highlightColor)
                .setPromoted(promoted)
                .setFallback(fallback)
                .setLeft(left)
                .setRight(right)
                .setSmall(small)
                .setExpanded(expanded)
                .setActions(actions)
                .setContentIntent(contentIntent)
                .setAppIcon(appIcon);
    }

    /** Fluent builder; all fields default to absent/false/0. */
    public static final class Builder {
        String key;
        String packageName;
        String tag = "";
        int id;
        int uid;
        int userId;
        long postedAt;
        boolean firstFloat;
        boolean updateFloat;
        boolean reorder;
        boolean dismissIsland;
        boolean updatable;
        boolean cancel;
        boolean filterWhenNoPermission;
        long lifetimeMillis;
        long islandLifetimeMillis;
        String reopen = CLOSE;
        String business;
        String aodTitle;
        String ticker;
        int protocol;
        int highlightColor;
        boolean promoted;
        boolean fallback;
        Part left;
        Part right;
        Part small;
        List<Part> expanded = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        PendingIntent contentIntent;
        Icon appIcon;

        public Builder setKey(String v) { key = v; return this; }
        public Builder setPackageName(String v) { packageName = v; return this; }
        public Builder setTag(String v) { tag = v == null ? "" : v; return this; }
        public Builder setId(int v) { id = v; return this; }
        public Builder setUid(int v) { uid = v; return this; }
        public Builder setUserId(int v) { userId = v; return this; }
        public Builder setPostedAt(long v) { postedAt = v; return this; }
        public Builder setFirstFloat(boolean v) { firstFloat = v; return this; }
        public Builder setUpdateFloat(boolean v) { updateFloat = v; return this; }
        public Builder setReorder(boolean v) { reorder = v; return this; }
        public Builder setDismissIsland(boolean v) { dismissIsland = v; return this; }
        public Builder setUpdatable(boolean v) { updatable = v; return this; }
        public Builder setCancel(boolean v) { cancel = v; return this; }
        public Builder setFilterWhenNoPermission(boolean v) { filterWhenNoPermission = v; return this; }
        public Builder setLifetimeMillis(long v) { lifetimeMillis = v; return this; }
        public Builder setIslandLifetimeMillis(long v) { islandLifetimeMillis = v; return this; }
        public Builder setReopen(String v) { reopen = v == null ? CLOSE : v; return this; }
        public Builder setBusiness(String v) { business = v; return this; }
        public Builder setAodTitle(String v) { aodTitle = v; return this; }
        public Builder setTicker(String v) { ticker = v; return this; }
        public Builder setProtocol(int v) { protocol = v; return this; }
        public Builder setHighlightColor(int v) { highlightColor = v; return this; }
        public Builder setPromoted(boolean v) { promoted = v; return this; }
        public Builder setFallback(boolean v) { fallback = v; return this; }
        public Builder setLeft(Part v) { left = v; return this; }
        public Builder setRight(Part v) { right = v; return this; }
        public Builder setSmall(Part v) { small = v; return this; }
        public Builder setExpanded(List<Part> v) { expanded = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder addExpanded(Part v) { if (v != null) expanded.add(v); return this; }
        public Builder setActions(List<Action> v) { actions = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder addAction(Action v) { if (v != null) actions.add(v); return this; }
        public Builder setContentIntent(PendingIntent v) { contentIntent = v; return this; }
        public Builder setAppIcon(Icon v) { appIcon = v; return this; }

        public IslandModel build() {
            if (key == null || key.isEmpty()) {
                throw new IllegalStateException("IslandModel requires a non-empty key");
            }
            if (packageName == null) {
                packageName = "";
            }
            return new IslandModel(this);
        }
    }

    /**
     * One normalized component of an island. A part is either a summary area (big island A/B area,
     * small island) or one entry of the expanded card. {@link #component} names the protocol
     * template it was built from so the UI can render semantically instead of dumping fields.
     */
    public static final class Part {
        public static final int NO_PROGRESS = -1;

        /** Template name, e.g. {@code "baseInfo"}, {@code "imageTextInfoLeft"}. */
        public final String component;
        /** Template type field; 0 when the template has none. */
        public final int type;
        public final String title;
        public final String subTitle;
        public final String frontTitle;
        public final String content;
        public final String subContent;
        public final String extraTitle;
        public final String specialTitle;
        /** Static digit text for fixed/equal width digit templates. */
        public final String digit;
        public final Icon icon;
        public final Icon darkIcon;
        /** Parsed ARGB text color, 0 when absent. */
        public final int color;
        public final int darkColor;
        /** Progress 0..100, or {@link #NO_PROGRESS} when the template has no progress. */
        public final int progress;
        public final int progressColor;
        public final int progressEndColor;
        /** Node count for multiProgressInfo, or {@link #NO_PROGRESS} when absent. */
        public final int points;
        public final boolean narrowFont;
        public final boolean highlight;
        public final Timer timer;
        /** Single actionInfo attached to this part (hintInfo / picInfo / highlightInfoV3). */
        public final Action action;
        /** Button rows attached to this part. Never null, possibly empty. */
        public final List<Action> actions;

        private Part(Builder b) {
            component = b.component;
            type = b.type;
            title = b.title;
            subTitle = b.subTitle;
            frontTitle = b.frontTitle;
            content = b.content;
            subContent = b.subContent;
            extraTitle = b.extraTitle;
            specialTitle = b.specialTitle;
            digit = b.digit;
            icon = b.icon;
            darkIcon = b.darkIcon;
            color = b.color;
            darkColor = b.darkColor;
            progress = b.progress;
            progressColor = b.progressColor;
            progressEndColor = b.progressEndColor;
            points = b.points;
            narrowFont = b.narrowFont;
            highlight = b.highlight;
            timer = b.timer;
            action = b.action;
            actions = unmodifiable(b.actions);
        }

        public boolean hasText() {
            return title != null || content != null || subTitle != null || frontTitle != null
                    || subContent != null || extraTitle != null || specialTitle != null;
        }

        /** Copies this part into a builder so callers can derive variants without re-parsing. */
        public Builder toBuilder() {
            return new Builder()
                    .setComponent(component)
                    .setType(type)
                    .setTitle(title)
                    .setSubTitle(subTitle)
                    .setFrontTitle(frontTitle)
                    .setContent(content)
                    .setSubContent(subContent)
                    .setExtraTitle(extraTitle)
                    .setSpecialTitle(specialTitle)
                    .setDigit(digit)
                    .setIcon(icon)
                    .setDarkIcon(darkIcon)
                    .setColor(color)
                    .setDarkColor(darkColor)
                    .setProgress(progress)
                    .setProgressColor(progressColor)
                    .setProgressEndColor(progressEndColor)
                    .setPoints(points)
                    .setNarrowFont(narrowFont)
                    .setHighlight(highlight)
                    .setTimer(timer)
                    .setAction(action)
                    .setActions(actions);
        }

        @Override
        public String toString() {
            return "Part{" + component + " type=" + type + " title=" + title
                    + " content=" + content + " progress=" + progress + "}";
        }

        public static final class Builder {
            String component = "";
            int type;
            String title;
            String subTitle;
            String frontTitle;
            String content;
            String subContent;
            String extraTitle;
            String specialTitle;
            String digit;
            Icon icon;
            Icon darkIcon;
            int color;
            int darkColor;
            int progress = NO_PROGRESS;
            int progressColor;
            int progressEndColor;
            int points = NO_PROGRESS;
            boolean narrowFont;
            boolean highlight;
            Timer timer;
            Action action;
            List<Action> actions = new ArrayList<>();

            public Builder setComponent(String v) { component = v == null ? "" : v; return this; }
            public Builder setType(int v) { type = v; return this; }
            public Builder setTitle(String v) { title = v; return this; }
            public Builder setSubTitle(String v) { subTitle = v; return this; }
            public Builder setFrontTitle(String v) { frontTitle = v; return this; }
            public Builder setContent(String v) { content = v; return this; }
            public Builder setSubContent(String v) { subContent = v; return this; }
            public Builder setExtraTitle(String v) { extraTitle = v; return this; }
            public Builder setSpecialTitle(String v) { specialTitle = v; return this; }
            public Builder setDigit(String v) { digit = v; return this; }
            public Builder setIcon(Icon v) { icon = v; return this; }
            public Builder setDarkIcon(Icon v) { darkIcon = v; return this; }
            public Builder setColor(int v) { color = v; return this; }
            public Builder setDarkColor(int v) { darkColor = v; return this; }
            public Builder setProgress(int v) { progress = v; return this; }
            public Builder setProgressColor(int v) { progressColor = v; return this; }
            public Builder setProgressEndColor(int v) { progressEndColor = v; return this; }
            public Builder setPoints(int v) { points = v; return this; }
            public Builder setNarrowFont(boolean v) { narrowFont = v; return this; }
            public Builder setHighlight(boolean v) { highlight = v; return this; }
            public Builder setTimer(Timer v) { timer = v; return this; }
            public Builder setAction(Action v) { action = v; return this; }
            public Builder setActions(List<Action> v) { actions = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
            public Builder addAction(Action v) { if (v != null) actions.add(v); return this; }

            public Part build() { return new Part(this); }
        }
    }

    /**
     * Countdown/count-up descriptor. All times are wall clock epoch millis as sent by the client;
     * {@link #durationAt(long)} is pure so rendering can be driven from any clock.
     */
    public static final class Timer {
        /** Countdown paused. */
        public static final int TYPE_COUNTDOWN_PAUSED = -2;
        /** Countdown running. */
        public static final int TYPE_COUNTDOWN = -1;
        /** Static value, no ticking. */
        public static final int TYPE_NONE = 0;
        /** Count-up running. */
        public static final int TYPE_COUNT_UP = 1;
        /** Count-up paused. */
        public static final int TYPE_COUNT_UP_PAUSED = 2;

        public final int type;
        /** Counting start point, epoch millis. */
        public final long when;
        /** Total countdown duration in millis; 0 when unused. */
        public final long total;
        /** Sender wall clock at send time (clock skew correction), epoch millis. */
        public final long reference;

        public Timer(int type, long when, long total, long reference) {
            this.type = type;
            this.when = when;
            this.total = total;
            this.reference = reference;
        }

        /** True while the value changes over time. */
        public boolean running() {
            return type == TYPE_COUNTDOWN || type == TYPE_COUNT_UP;
        }

        /** True when the timer is frozen at a sender supplied value. */
        public boolean paused() {
            return type == TYPE_COUNTDOWN_PAUSED || type == TYPE_COUNT_UP_PAUSED;
        }

        /** True for both countdown states. */
        public boolean countdown() {
            return type == TYPE_COUNTDOWN || type == TYPE_COUNTDOWN_PAUSED;
        }

        /** True for both count-up states. */
        public boolean countUp() {
            return type == TYPE_COUNT_UP || type == TYPE_COUNT_UP_PAUSED;
        }

        /** True when this descriptor actually carries a timer. */
        public boolean isActive() {
            return type != TYPE_NONE;
        }

        /**
         * Value to display at {@code wallNow} (epoch millis), clamped at zero.
         *
         * <p>Countdown: remaining millis. Count-up: elapsed millis. Paused states are evaluated
         * against {@link #reference} so they stay frozen even as the wall clock advances. The
         * static type returns {@link #total}.
         */
        public long durationAt(long wallNow) {
            if (type == TYPE_NONE) {
                return Math.max(0L, total);
            }
            final long anchor = running() ? wallNow : reference;
            final long delta = anchor - when;
            final long value = countdown() ? total - delta : delta;
            return value < 0L ? 0L : value;
        }

        @Override
        public String toString() {
            return "Timer{type=" + type + " when=" + when + " total=" + total
                    + " ref=" + reference + "}";
        }
    }

    /**
     * One tappable action. Exactly one of {@link #pendingIntent} / {@link #intent} is expected to be
     * usable; {@link IslandActions} decides whether it may be fired.
     */
    public static final class Action {
        /** Action intent targets an activity. */
        public static final int TYPE_ACTIVITY = 1;
        /** Action intent targets a broadcast receiver. */
        public static final int TYPE_BROADCAST = 2;
        /** Action intent targets a service. */
        public static final int TYPE_SERVICE = 3;

        /** Protocol key: {@code miui.focus.action_xxx} for bundle actions, or a preset action string. */
        public final String key;
        public final String title;
        public final Icon icon;
        /** Bundle supplied PendingIntent, already carrying the sender identity. */
        public final PendingIntent pendingIntent;
        /** Sanitized URI fallback, or null when the URI was unsafe/absent/unusable. */
        public final Intent intent;
        /** One of {@link #TYPE_ACTIVITY} / {@link #TYPE_BROADCAST} / {@link #TYPE_SERVICE}, else 0. */
        public final int intentType;
        /** Collapse the expanded card after the action fires. */
        public final boolean collapse;

        public Action(String key, String title, Icon icon, PendingIntent pendingIntent,
                Intent intent, int intentType, boolean collapse) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.pendingIntent = pendingIntent;
            this.intent = intent;
            this.intentType = intentType;
            this.collapse = collapse;
        }

        /** True when this action has any dispatchable target. */
        public boolean isDispatchable() {
            return pendingIntent != null || intent != null;
        }

        @Override
        public String toString() {
            return "Action{" + key + " title=" + title + " pi=" + (pendingIntent != null)
                    + " intent=" + (intent != null) + " type=" + intentType + "}";
        }
    }
}
