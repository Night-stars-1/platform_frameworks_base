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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Behavioural self test for the island protocol layer.
 *
 * <p>Two halves, both driven by this class:
 * <ul>
 *   <li>{@link #runJvmTests()} needs no Android runtime beyond the platform classes being on the
 *       classpath (no {@code Context}, no {@code Bundle}, no device). It covers the bounded JSON
 *       reader, timer arithmetic, store lifecycle and the action policy predicates, all with an
 *       injected clock and real {@link IslandModel} instances.
 *   <li>{@link #runAndroidTests(Context)} needs a real Android runtime and exercises the parts that
 *       genuinely depend on platform behaviour: {@link Bundle}/{@link Notification}/{@link Icon}
 *       parsing, malformed and hostile payloads, icon sanitisation and cancel/reopen semantics.
 * </ul>
 *
 * <p>{@code main} runs the JVM half and exits non-zero on failure. Nothing here inspects source text
 * or echoes inputs; every assertion checks observable behaviour.
 */
public final class IslandSelfTest {

    private static final String TAG = "IslandSelfTest";

    /** Pics bundle key whose Parcelable class name is deliberately corrupted by the fixture. */
    private static final String CORRUPT_PIC_KEY = "miui.focus.pic_corrupt";

    /** Mirrors IslandActions' grant mask: a dispatched target must never carry any of these. */
    private static final int URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private static int sPassed;
    private static final List<String> sFailures = new ArrayList<>();
    private static final List<String> sSkipped = new ArrayList<>();

    private IslandSelfTest() {
    }

    public static void main(String[] args) {
        runJvmTests();
        System.out.println(report());
        if (!sFailures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Human readable report of everything observed so far. */
    public static String report() {
        StringBuilder out = new StringBuilder("IslandSelfTest: ").append(sPassed)
                .append(" passed, ").append(sFailures.size()).append(" failed, ")
                .append(sSkipped.size()).append(" skipped");
        for (String skip : sSkipped) {
            out.append("\nSKIP: ").append(skip);
        }
        for (String failure : sFailures) {
            out.append("\nFAIL: ").append(failure);
        }
        return out.toString();
    }

    /** Failures observed so far; empty when every assertion passed. */
    public static List<String> failures() {
        return new ArrayList<>(sFailures);
    }

    // ---------------------------------------------------------------------------------------------
    // JVM half
    // ---------------------------------------------------------------------------------------------

    /**
     * Assertions that need no device, no {@code Context}, no {@code Looper} and no Android native
     * initialization: only pure Java types plus {@link IslandModel}/{@link IslandStore} instances
     * whose Android typed fields are null. The only Android classes touched are those on the compile
     * classpath that are themselves pure Java (verified: the policy predicates use plain String
     * comparison).
     */
    public static void runJvmTests() {
        testJsonParser();
        testJsonBounds();
        testTimer();
        testStoreLifecycle();
        testStoreExpiry();
        testStoreCancellation();
        testActionPolicy();
        testColorParsing();
    }

    private static void testJsonParser() {
        try {
            IslandJson.Value root = IslandJson.parse(
                    "{\"param_v2\":{\"protocol\":1,\"business\":\"taxi\",\"param_island\":"
                            + "{\"islandProperty\":1,\"bigIslandArea\":{\"imageTextInfoLeft\":"
                            + "{\"type\":1,\"picInfo\":{\"type\":1,\"pic\":\"p\"}}}}}}");
            IslandJson.Value v2 = root.get("param_v2");
            checkNotNull("json.root.param_v2", v2);
            checkEq("json.protocol", 1, v2.get("protocol").asInt(0));
            checkEq("json.business", "taxi", v2.get("business").asString(null));
            checkEq("json.nested.type", 1, v2.get("param_island").get("bigIslandArea")
                    .get("imageTextInfoLeft").get("type").asInt(0));
            checkEq("json.missing.member", null, v2.get("nope"));
            check("json.has", v2.has("param_island"));
            checkEq("json.array.size", 2, IslandJson.parse("[1,2]").size());
            checkEq("json.empty.object", 0, IslandJson.parse("{}").size());
            checkEq("json.escapes", "a\"b\\c\nd\te", IslandJson.parse("\"a\\\"b\\\\c\\nd\\te\"")
                    .asString(null));
            checkEq("json.unicode.escape", "\u63a5\u9a7e", IslandJson.parse("\"\\u63a5\\u9a7e\"")
                    .asString(null));
            check("json.boolean.true", IslandJson.parse("true").asBoolean(false));
            checkEq("json.null.is.empty", true, IslandJson.parse("null").isEmpty());
            checkEq("json.string.value", "5", IslandJson.parse("\"5\"").asString(null));
        } catch (IslandJson.ParseException e) {
            fail("json.parser threw " + e);
        }
    }

    private static void testJsonBounds() {
        // Trailing content.
        expectJsonFailure("json.trailing", "{\"a\":1} junk");
        // Unterminated string and bad escape.
        expectJsonFailure("json.unterminated", "{\"a\":\"x}");
        expectJsonFailure("json.bad.escape", "\"a\\qb\"");
        expectJsonFailure("json.bad.hex", "\"\\uZZZZ\"");
        expectJsonFailure("json.empty.input", "");
        expectJsonFailure("json.bare.word", "notjson");
        expectJsonFailure("json.non.finite", "1e999");
        expectJsonFailure("json.missing.colon", "{\"a\" 1}");
        expectJsonFailure("json.object.key.not.string", "{1:2}");

        // Oversized input.
        StringBuilder huge = new StringBuilder(IslandJson.MAX_INPUT_CHARS + 16);
        huge.append('"');
        for (int i = 0; i < IslandJson.MAX_INPUT_CHARS + 4; i++) {
            huge.append('a');
        }
        huge.append('"');
        expectJsonFailure("json.oversized", huge.toString());

        // Depth: 8 levels is fine, 64 must be refused rather than overflowing the stack.
        checkOk("json.depth.8", nestedArrays(8));
        expectJsonFailure("json.depth.64", nestedArrays(64));

        // Width: exactly the limit is accepted, one more is refused.
        StringBuilder wide = new StringBuilder("[");
        for (int i = 0; i < IslandJson.MAX_ELEMENTS; i++) {
            wide.append(i == 0 ? "1" : ",1");
        }
        wide.append(']');
        checkOk("json.width.limit", wide.toString());
        wide.insert(wide.length() - 1, ",1");
        expectJsonFailure("json.width.over", wide.toString());

        // Long strings are refused.
        StringBuilder longString = new StringBuilder("\"");
        for (int i = 0; i < IslandJson.MAX_STRING_CHARS + 8; i++) {
            longString.append('b');
        }
        longString.append('"');
        expectJsonFailure("json.string.too.long", longString.toString());

        // Numbers are clamped instead of overflowing, and strings are not coerced to numbers.
        try {
            checkEq("json.number.clamp.int", Integer.MAX_VALUE,
                    IslandJson.parse("1e30").asInt(0));
            checkEq("json.number.clamp.long", Long.MIN_VALUE,
                    IslandJson.parse("-1e30").asLong(0L));
            checkEq("json.number.normal", 65L, IslandJson.parse("65").asLong(0L));
            checkEq("json.string.not.coerced", 0, IslandJson.parse("\"5\"").asInt(0));
        } catch (IslandJson.ParseException e) {
            fail("json.numeric.boundary threw " + e);
        }
    }

    private static void testTimer() {
        IslandModel.Timer countdown = new IslandModel.Timer(
                IslandModel.Timer.TYPE_COUNTDOWN, 1_000L, 60_000L, 5_000L);
        check("timer.countdown.running", countdown.running());
        check("timer.countdown.is.countdown", countdown.countdown());
        check("timer.countdown.not.countup", !countdown.countUp());
        checkEq("timer.countdown.value", 50_000L, countdown.durationAt(11_000L));
        checkEq("timer.countdown.clamped", 0L, countdown.durationAt(999_000L));

        IslandModel.Timer pausedCountdown = new IslandModel.Timer(
                IslandModel.Timer.TYPE_COUNTDOWN_PAUSED, 1_000L, 60_000L, 21_000L);
        check("timer.paused.not.running", !pausedCountdown.running());
        check("timer.paused.paused", pausedCountdown.paused());
        check("timer.paused.countdown", pausedCountdown.countdown());
        // Frozen at the pause point regardless of how far the wall clock has moved.
        checkEq("timer.paused.frozen", 40_000L, pausedCountdown.durationAt(10_000L));
        checkEq("timer.paused.frozen.later", 40_000L, pausedCountdown.durationAt(900_000L));

        IslandModel.Timer countUp = new IslandModel.Timer(
                IslandModel.Timer.TYPE_COUNT_UP, 1_000L, 0L, 0L);
        check("timer.countup.running", countUp.running());
        check("timer.countup.countUp", countUp.countUp());
        checkEq("timer.countup.value", 60_000L, countUp.durationAt(61_000L));

        IslandModel.Timer pausedCountUp = new IslandModel.Timer(
                IslandModel.Timer.TYPE_COUNT_UP_PAUSED, 1_000L, 0L, 31_000L);
        checkEq("timer.countup.paused.frozen", 30_000L, pausedCountUp.durationAt(500_000L));

        IslandModel.Timer none = new IslandModel.Timer(IslandModel.Timer.TYPE_NONE, 0L, 42L, 0L);
        check("timer.none.not.active", !none.isActive());
        check("timer.none.not.running", !none.running());
        checkEq("timer.none.static", 42L, none.durationAt(123_456L));

    }

    private static void testStoreLifecycle() {
        FakeClock clock = new FakeClock();
        List<Integer> notifications = new ArrayList<>();
        IslandStore store = new IslandStore(clock,
                visible -> notifications.add(visible.size()));

        IslandModel a = model("keyA", 1_000L, 0L);
        IslandModel b = model("keyB", 1_000L, 0L);
        IslandModel c = model("keyC", 1_000L, 0L);

        check("store.empty", store.isEmpty());
        check("store.put.a", store.put(a));
        check("store.put.b", store.put(b));
        check("store.put.c", store.put(c));
        checkEq("store.size", 3, store.size());
        checkEq("store.snapshot.bounded", IslandStore.MAX_VISIBLE, store.snapshot().size());
        // Newest first: index 0 is the primary (large) activity.
        checkEq("store.snapshot.primary", "keyC", store.snapshot().get(0).key);
        checkEq("store.snapshot.secondary", "keyB", store.snapshot().get(1).key);
        checkEq("store.all", 3, store.all().size());

        // Same key updates in place, does not duplicate and keeps its position.
        check("store.update.same.key", store.put(model("keyB", 5_000L, 0L)));
        checkEq("store.update.size", 3, store.size());
        checkEq("store.update.keeps.position", "keyB", store.all().get(1).key);
        checkEq("store.update.content", 5_000L, store.get("keyB").islandLifetimeMillis);

        // reorder moves the updated entry to the front.
        check("store.update.reorder", store.put(model("keyB", 5_000L, 0L).toBuilder()
                .setReorder(true).build()));
        checkEq("store.reorder.front", "keyB", store.all().get(0).key);

        // cancel == true removes.
        check("store.cancel.flag", store.put(model("keyC", 1_000L, 0L).toBuilder()
                .setCancel(true).build()));
        checkNull("store.cancel.removed", store.get("keyC"));

        // dismiss hides but keeps the entry.
        check("store.dismiss", store.dismiss("keyA"));
        check("store.dismiss.hidden", store.isHidden("keyA"));
        checkNotNull("store.dismiss.retained", store.get("keyA"));
        checkEq("store.dismiss.excluded", 1, store.snapshot().size());
        check("store.dismiss.idempotent", !store.dismiss("keyA"));

        // remove drops it entirely.
        check("store.remove", store.remove("keyA"));
        check("store.remove.missing", !store.remove("keyA"));

        // Bounds: never more than MAX_TRACKED entries, oldest evicted.
        for (int i = 0; i < IslandStore.MAX_TRACKED + 4; i++) {
            store.put(model("bulk" + i, 1_000L, 0L));
        }
        checkEq("store.tracked.bound", IslandStore.MAX_TRACKED, store.size());
        checkEq("store.evicted.oldest", "bulk" + (IslandStore.MAX_TRACKED + 3),
                store.all().get(0).key);

        // The listener fires on every successful put and on real mutations, and stays silent for
        // no-op calls.
        int before = notifications.size();
        store.put(model("bulk" + (IslandStore.MAX_TRACKED + 3), 1_000L, 0L));
        checkEq("store.listener.on.put", before + 1, notifications.size());
        before = notifications.size();
        store.tick();
        checkEq("store.listener.silent.on.tick", before, notifications.size());
        store.dismiss("bulk" + (IslandStore.MAX_TRACKED + 3));
        checkEq("store.listener.on.dismiss", before + 1, notifications.size());
        before = notifications.size();
        store.dismiss("bulk" + (IslandStore.MAX_TRACKED + 3));
        checkEq("store.listener.silent.on.noop.dismiss", before, notifications.size());
        check("store.snapshot.immutable", immutable(store.snapshot()));
        check("store.all.immutable", immutable(store.all()));

        store.clear();
        check("store.cleared", store.isEmpty());
    }

    private static void testStoreExpiry() {
        FakeClock clock = new FakeClock();
        IslandStore store = new IslandStore(clock);

        // islandLifetimeMillis wins over lifetimeMillis.
        store.put(model("keyA", 30_000L, 3_600_000L));
        checkEq("store.expiry.remaining", 30_000L, store.remainingMillis("keyA"));
        clock.advance(29_999L);
        store.tick();
        checkNotNull("store.expiry.not.yet", store.get("keyA"));
        clock.advance(1L);
        store.tick();
        checkNull("store.expiry.gone", store.get("keyA"));

        // Falling back to lifetimeMillis when no island lifetime is set.
        store.put(model("keyB", 0L, 60_000L));
        clock.advance(60_000L);
        store.tick();
        checkNull("store.expiry.lifetime.fallback", store.get("keyB"));

        // No lifetime at all means no expiry, even far in the future.
        store.put(model("keyC", 0L, 0L));
        clock.advance(10L * 24L * 60L * 60L * 1000L);
        store.tick();
        checkNotNull("store.no.lifetime", store.get("keyC"));

        // Expiry is not a cancellation: a repost shows again.
        store.put(model("keyD", 1_000L, 1_000L));
        clock.advance(1_000L);
        store.tick();
        checkNull("store.expiry.removed", store.get("keyD"));
        check("store.expiry.repost.allowed", store.put(model("keyD", 1_000L, 1_000L)));
    }

    private static void testStoreCancellation() {
        FakeClock clock = new FakeClock();
        IslandStore store = new IslandStore(clock);

        // updatable + close: the cancelled key must stay hidden.
        store.put(model("keyA", 1_000L, 0L).toBuilder()
                .setUpdatable(true).setReopen(IslandModel.CLOSE).build());
        check("store.cancel.notify.remove", store.remove("keyA"));
        check("store.cancel.tombstoned", store.isTombstoned("keyA"));
        check("store.cancel.repost.blocked", !store.put(model("keyA", 1_000L, 0L).toBuilder()
                .setUpdatable(true).setReopen(IslandModel.CLOSE).build()));
        checkNull("store.cancel.still.hidden", store.get("keyA"));

        // An explicit reopen declaration opts the key back in.
        check("store.cancel.reopen.allowed", store.put(model("keyA", 1_000L, 0L).toBuilder()
                .setUpdatable(true).setReopen(IslandModel.REOPEN).build()));
        check("store.cancel.tombstone.cleared", !store.isTombstoned("keyA"));

        // Regular cancel (default updatable == false) reappears.
        store.put(model("keyB", 1_000L, 0L));
        store.remove("keyB");
        check("store.regular.cancel.no.tombstone", !store.isTombstoned("keyB"));
        check("store.regular.cancel.reappears", store.put(model("keyB", 1_000L, 0L)));

        // updatable + reopen is never tombstoned either.
        store.put(model("keyC", 1_000L, 0L).toBuilder()
                .setUpdatable(true).setReopen(IslandModel.REOPEN).build());
        store.remove("keyC");
        check("store.reopen.no.tombstone", !store.isTombstoned("keyC"));

        // Tombstones are bounded.
        for (int i = 0; i < IslandStore.MAX_TOMBSTONES + 8; i++) {
            String key = "tomb" + i;
            store.put(model(key, 1_000L, 0L).toBuilder()
                    .setUpdatable(true).setReopen(IslandModel.CLOSE).build());
            store.remove(key);
        }
        check("store.tombstone.oldest.evicted", !store.isTombstoned("tomb0"));
        check("store.tombstone.newest.kept",
                store.isTombstoned("tomb" + (IslandStore.MAX_TOMBSTONES + 7)));

        // dismissIsland collapses the island but keeps the entry tracked.
        store.put(model("keyD", 1_000L, 0L).toBuilder().setDismissIsland(true).build());
        check("store.dismissIsland.hidden", store.isHidden("keyD"));
        checkNotNull("store.dismissIsland.tracked", store.get("keyD"));
        check("store.dismissIsland.restored", store.put(model("keyD", 1_000L, 0L)));
        check("store.dismissIsland.visible", !store.isHidden("keyD"));

        // Cancelled keys are independent of each other.
        check("store.tombstone.scoped.to.key", store.put(model("unrelated", 1_000L, 0L)));
    }

    private static void testActionPolicy() {
        // Exact uid AND package are both required.
        check("actions.creator.exact", IslandActions.isTrustedCreator(
                "com.example.app", 10_777, "com.example.app", 10_777));
        // Same package name but a different uid: a different user or a different app install.
        check("actions.creator.same.package.other.uid", !IslandActions.isTrustedCreator(
                "com.example.app", 12_345, "com.example.app", 10_777));
        // Shared uid sibling app: same uid, different package, refused.
        check("actions.creator.shared.uid.sibling", !IslandActions.isTrustedCreator(
                "com.proxy", 10_777, "com.example.app", 10_777));
        // Foreign creator is not trusted.
        check("actions.creator.foreign", !IslandActions.isTrustedCreator(
                "com.other", 10_778, "com.example.app", 10_777));
        // No blanket trust for system or root uids.
        check("actions.creator.system.uid.rejected", !IslandActions.isTrustedCreator(
                "com.example.app", 1_000, "com.example.app", 10_777));
        check("actions.creator.root.rejected", !IslandActions.isTrustedCreator(
                null, 0, "com.example.app", 0));
        // Missing packages and negative source uids are never trusted.
        check("actions.creator.null.creator.package", !IslandActions.isTrustedCreator(
                null, 10_777, "com.example.app", 10_777));
        check("actions.creator.null.source.package", !IslandActions.isTrustedCreator(
                "com.example.app", 10_777, null, 10_777));
        check("actions.creator.negative.source.uid", !IslandActions.isTrustedCreator(
                "com.example.app", -1, "com.example.app", -1));

        // Only activity targets may come from a serialized URI.
        check("actions.uri.activity", IslandActions.isDispatchableUriType(
                IslandModel.Action.TYPE_ACTIVITY));
        check("actions.uri.broadcast.rejected", !IslandActions.isDispatchableUriType(
                IslandModel.Action.TYPE_BROADCAST));
        check("actions.uri.service.rejected", !IslandActions.isDispatchableUriType(
                IslandModel.Action.TYPE_SERVICE));
        check("actions.uri.unknown.rejected", !IslandActions.isDispatchableUriType(0));

        // Schemes that would need a grant SystemUI must not fabricate.
        check("actions.uri.file.rejected", IslandActions.isForbiddenUriScheme("file"));
        check("actions.uri.content.rejected", IslandActions.isForbiddenUriScheme("content"));
        check("actions.uri.https.allowed", !IslandActions.isForbiddenUriScheme("https"));
        check("actions.uri.null.allowed", !IslandActions.isForbiddenUriScheme(null));
    }

    private static void testColorParsing() {
        checkEq("color.rgb.short", 0xFFFF0000, IslandProtocol.parseColor("#F00"));
        checkEq("color.rgb", 0xFF69DAB7, IslandProtocol.parseColor("#69DAB7"));
        checkEq("color.argb", 0x8069DAB7, IslandProtocol.parseColor("#8069DAB7"));
        checkEq("color.invalid", 0, IslandProtocol.parseColor("69DAB7"));
        checkEq("color.null", 0, IslandProtocol.parseColor(null));
        checkEq("color.garbage", 0, IslandProtocol.parseColor("#ZZZZZZ"));
        checkEq("color.empty", 0, IslandProtocol.parseColor("#"));
        checkEq("color.spaces", 0xFF69DAB7, IslandProtocol.parseColor("  #69DAB7  "));
    }


    // ---------------------------------------------------------------------------------------------
    // Android half
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs the assertions that need a real Android runtime. Call from an instrumentation test or
     * from an Activity self check, on a background thread (the delivery checks wait for a broadcast
     * dispatched on the main looper). Results are appended to the shared counters.
     */
    public static void runAndroidTests(Context context) {
        if (context == null) {
            fail("android.context null");
            return;
        }
        testAndroidHappyPath(context);
        testAndroidMalformed(context);
        testAndroidBounds(context);
        testAndroidIcons(context);
        testAndroidActions(context);
        testAndroidSmallFallback(context);
        testAndroidHostileExtras(context);
        testAndroidReopen(context);
        testAndroidLegacyNavigation(context);
        testAndroidCompactLayout(context);
        testAndroidCompactLayoutLifecycle(context);
        testAndroidNavigationLayout(context);
        testAndroidNavigationMorph(context);
        testAndroidGenericCardLifecycle(context);
        String summary = report();
        System.out.println(summary);
        android.util.Log.i(TAG, summary);
    }

    private static void testAndroidCompactLayout(Context context) {
        Icon icon = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        IslandModel primary = new IslandModel.Builder().setKey("layout-primary")
                .setLeft(new IslandModel.Part.Builder().setTitle("pickup").setIcon(icon).build())
                .setRight(new IslandModel.Part.Builder().setTitle("5 min").build()).build();
        IslandModel secondary = new IslandModel.Builder().setKey("layout-secondary")
                .setSmall(new IslandModel.Part.Builder().setIcon(icon).build()).build();
        IslandContentView view = new IslandContentView(context);
        view.setIconResolver(value -> IslandProtocol.loadIcon(context, value, context.getUserId()));
        view.setViewportWidth(1080);
        view.setCutoutGap(80);
        view.setActivities(java.util.Arrays.asList(primary, secondary));
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        android.widget.TextView left = findLayoutText(view, "pickup");
        android.widget.TextView right = findLayoutText(view, "5 min");
        checkNotNull("android.layout.primary.label", left);
        checkNotNull("android.layout.primary.detail", right);
        if (left != null) check("android.layout.primary.label.fits", left.getMeasuredWidth()
                >= Math.ceil(left.getPaint().measureText("pickup")));
        if (right != null) check("android.layout.primary.detail.fits", right.getMeasuredWidth()
                >= Math.ceil(right.getPaint().measureText("5 min")));
        List<android.widget.ImageView> images = new ArrayList<>();
        collectLayoutImages(view, images);
        checkEq("android.layout.both.activity.icons", 2, images.size());
        if (images.size() == 2) {
            android.view.View small = (android.view.View) images.get(1).getParent();
            check("android.layout.secondary.is.capsule", small.getMeasuredWidth() < view.getMeasuredWidth() / 2);
        }
        android.graphics.Region touch = view.getTouchableRegion();
        check("android.layout.cutout.full.height.untouchable", !touch.contains(view.getCutoutCenterX(), 1));
        if (!images.isEmpty()) {
            android.graphics.Rect iconBounds = new android.graphics.Rect(0, 0,
                    images.get(0).getWidth(), images.get(0).getHeight());
            view.offsetDescendantRectToMyCoords(images.get(0), iconBounds);
            check("android.layout.primary.remains.touchable", touch.contains(iconBounds.centerX(), iconBounds.centerY()));
        }

        // A one-sided activity, like a navigation card, must still keep the gap in the middle of
        // the pill so it wraps the camera symmetrically.
        IslandModel oneSided = new IslandModel.Builder().setKey("layout-one-sided")
                .setLeft(new IslandModel.Part.Builder().setTitle("45 m turn left")
                        .setContent("navigating").setIcon(icon).build()).build();
        IslandContentView single = new IslandContentView(context);
        single.setIconResolver(value -> IslandProtocol.loadIcon(context, value, context.getUserId()));
        single.setViewportWidth(1080);
        single.setCutoutGap(80);
        single.setActivities(java.util.Collections.singletonList(oneSided));
        single.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.AT_MOST));
        single.layout(0, 0, single.getMeasuredWidth(), single.getMeasuredHeight());
        check("android.layout.one.sided.gap.centred",
                Math.abs(single.getCutoutCenterX() - single.getMeasuredWidth() / 2) <= 1);
        check("android.layout.one.sided.can.expand", single.canExpand());
    }

    /**
     * One layout lifecycle for the collapsed pill. A single-sided activity always reads the same
     * way: the icon closes the half left of the camera and the summary, value and timer close the
     * half right of it, with both halves at the same width so the gap stays on the camera. A
     * two-sided activity keeps the halves the sender chose, growing then shrinking the text leaves
     * no stale minimum width behind, and expand/collapse returns to the same split.
     */
    private static void testAndroidCompactLayoutLifecycle(Context context) {
        Icon icon = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        String title = "pickup";
        IslandContentView view = new IslandContentView(context);
        view.setIconResolver(value -> IslandProtocol.loadIcon(context, value, context.getUserId()));
        view.setViewportWidth(1080);
        view.setCutoutGap(80);
        List<android.widget.ImageView> icons = new ArrayList<>();

        // --- left only: the icon closes the left half, the text the right one.
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-left-only")
                .setLeft(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                .build()));
        measureLayout(view, 1080);
        int splitWidth = checkSplitGeometry(view, "android.layout.left.only");
        checkSplitSides(view, "android.layout.left.only", title);
        checkEq("android.layout.left.only.icon.half.has.no.text", 0,
                countLayoutTexts(sideOf(view, 0)));

        // --- right only: exactly the same pill, the icon still left of the camera.
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-right-only")
                .setRight(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                .build()));
        measureLayout(view, 1080);
        checkEq("android.layout.right.only.same.width", splitWidth,
                checkSplitGeometry(view, "android.layout.right.only"));
        checkSplitSides(view, "android.layout.right.only", title);

        // --- icon only: no text is invented and the icon still closes the left half.
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-icon-only")
                .setRight(new IslandModel.Part.Builder().setIcon(icon).build()).build()));
        measureLayout(view, 1080);
        checkSplitGeometry(view, "android.layout.icon.only");
        checkSplitSides(view, "android.layout.icon.only", null);
        checkEq("android.layout.icon.only.text.half.empty", 0, countLayoutTexts(sideOf(view, 2)));
        // A lone icon would make a sliver; each half is held at the minimum island width instead.
        check("android.layout.icon.only.minimum.width", sideOf(view, 0).getWidth()
                >= Math.round(64 * context.getResources().getDisplayMetrics().density));

        // --- text only: the text moves right of the camera, no glyph is fabricated for it.
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-text-only")
                .setLeft(new IslandModel.Part.Builder().setTitle(title).build()).build()));
        measureLayout(view, 1080);
        checkSplitGeometry(view, "android.layout.text.only");
        icons.clear();
        collectLayoutImages(sideOf(view, 0), icons);
        checkEq("android.layout.text.only.no.glyph", 0, icons.size());
        checkNotNull("android.layout.text.only.text.right", findLayoutText(sideOf(view, 2), title));
        checkNull("android.layout.text.only.text.not.left", findLayoutText(sideOf(view, 0), title));

        // --- both sides: each keeps the half the sender chose.
        String rightTitle = "5 min";
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-both")
                .setLeft(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                .setRight(new IslandModel.Part.Builder().setTitle(rightTitle).build()).build()));
        measureLayout(view, 1080);
        checkSplitGeometry(view, "android.layout.both");
        android.graphics.Rect bothGap = boundsIn(view, sideOf(view, 1));
        android.widget.TextView leftLabel = findLayoutText(sideOf(view, 0), title);
        android.widget.TextView rightLabel = findLayoutText(sideOf(view, 2), rightTitle);
        checkNotNull("android.layout.both.left.kept", leftLabel);
        checkNotNull("android.layout.both.right.kept", rightLabel);
        checkNull("android.layout.both.nothing.crosses", findLayoutText(sideOf(view, 0), rightTitle));
        if (leftLabel != null) {
            check("android.layout.both.left.before.camera",
                    boundsIn(view, leftLabel).right <= bothGap.left);
        }
        if (rightLabel != null) {
            check("android.layout.both.right.after.camera",
                    boundsIn(view, rightLabel).left >= bothGap.right);
        }
        icons.clear();
        collectLayoutImages(sideOf(view, 0), icons);
        checkEq("android.layout.both.left.own.icon", 1, icons.size());
        icons.clear();
        collectLayoutImages(sideOf(view, 2), icons);
        checkEq("android.layout.both.right.own.icon", 0, icons.size());

        // --- longer text: both halves grow together, then shrinking restores the old width.
        String longerTitle = "pickup location nearby";
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-left-longer")
                .setLeft(new IslandModel.Part.Builder().setTitle(longerTitle).setIcon(icon).build())
                .build()));
        measureLayout(view, 1080);
        int longerWidth = checkSplitGeometry(view, "android.layout.longer");
        check("android.layout.longer.grows", longerWidth > splitWidth);
        checkSplitSides(view, "android.layout.longer", longerTitle);
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-left-short-again")
                .setLeft(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                .build()));
        measureLayout(view, 1080);
        checkEq("android.layout.shorter.restores.width", splitWidth,
                checkSplitGeometry(view, "android.layout.shorter"));
        checkSplitSides(view, "android.layout.shorter", title);

        // --- expand shows only the wide card, collapse restores the identical split.
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-expand")
                .setLeft(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                .build()));
        measureLayout(view, 1080);
        int collapsedWidth = checkSplitGeometry(view, "android.layout.before.expand");
        view.setExpanded(true);
        measureLayout(view, 1080);
        check("android.layout.expand.is.card", view.isCardExpanded());
        checkEq("android.layout.expand.compact.row.gone", android.view.View.GONE,
                rowOf(view).getVisibility());
        checkEq("android.layout.expand.no.camera.gap", -1, view.getCutoutCenterX());
        check("android.layout.expand.wide.card", view.getMeasuredWidth() >= 1080 * 4 / 5);
        view.setExpanded(false);
        measureLayout(view, 1080);
        check("android.layout.collapse.not.expanded", !view.isCardExpanded());
        checkEq("android.layout.collapse.restores.width", collapsedWidth,
                checkSplitGeometry(view, "android.layout.collapse.restored"));
        checkSplitSides(view, "android.layout.collapse.restored", title);

        // --- numeric value and timer: both slots stay right of the camera and keep ticking there.
        int viewport = 2000;
        view.setViewportWidth(viewport);
        IslandModel.Timer countdown = new IslandModel.Timer(IslandModel.Timer.TYPE_COUNTDOWN,
                System.currentTimeMillis(), 3_600_000L, 0L);
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("lifecycle-value-timer")
                .setLeft(new IslandModel.Part.Builder().setTitle("order").setDigit("B123")
                        .setTimer(countdown).setIcon(icon).build())
                .build()));
        measureLayout(view, viewport);
        checkSplitGeometry(view, "android.layout.value.timer");
        checkSplitSides(view, "android.layout.value.timer", "order");
        android.graphics.Rect slotGap = boundsIn(view, sideOf(view, 1));
        android.widget.TextView summarySlot = findLayoutText(sideOf(view, 2), "order");
        android.widget.TextView valueSlot = findLayoutText(sideOf(view, 2), "B123");
        checkNotNull("android.layout.value.timer.summary.kept", summarySlot);
        checkNotNull("android.layout.value.timer.value.kept", valueSlot);
        if (summarySlot != null) {
            check("android.layout.value.timer.summary.after.camera",
                    boundsIn(view, summarySlot).left >= slotGap.right);
        }
        if (valueSlot != null) {
            check("android.layout.value.timer.value.after.camera",
                    boundsIn(view, valueSlot).left >= slotGap.right);
        }
        android.view.ViewGroup textHalf = (android.view.ViewGroup) sideOf(view, 2);
        checkEq("android.layout.value.timer.three.slots", 3, textHalf.getChildCount());
        if (textHalf.getChildCount() == 3) {
            android.view.View timerSlot = textHalf.getChildAt(2);
            check("android.layout.value.timer.slot.is.text",
                    timerSlot instanceof android.widget.TextView);
            if (timerSlot instanceof android.widget.TextView) {
                android.widget.TextView timer = (android.widget.TextView) timerSlot;
                String before = String.valueOf(timer.getText());
                check("android.layout.value.timer.shows.mmss", before.indexOf(':') > 0);
                check("android.layout.value.timer.after.camera",
                        boundsIn(view, timerSlot).left >= slotGap.right);
                view.updateTimers(System.currentTimeMillis() + 61_000L);
                check("android.layout.value.timer.ticks.same.view",
                        !before.equals(String.valueOf(timer.getText())));
                check("android.layout.value.timer.stays.after.camera",
                        boundsIn(view, timerSlot).left >= slotGap.right);
            }
        }

        // --- a secondary capsule keeps its own content beside a split primary.
        view.setActivities(java.util.Arrays.asList(
                new IslandModel.Builder().setKey("lifecycle-split-primary")
                        .setLeft(new IslandModel.Part.Builder().setTitle(title).setIcon(icon).build())
                        .build(),
                new IslandModel.Builder().setKey("lifecycle-secondary")
                        .setSmall(new IslandModel.Part.Builder().setTitle("2 messages").build())
                        .build()));
        measureLayout(view, viewport);
        checkSplitGeometry(view, "android.layout.secondary.split");
        checkSplitSides(view, "android.layout.secondary.split", title);
        checkNotNull("android.layout.secondary.kept", visibleLayoutText(view, "2 messages"));
    }

    /** Navigation has distinct compact and expanded presentations, not a generic app-icon card. */
    private static void testAndroidNavigationLayout(Context context) {
        android.graphics.Bitmap white = android.graphics.Bitmap.createBitmap(12, 12,
                android.graphics.Bitmap.Config.ARGB_8888);
        white.eraseColor(0xFFFFFFFF);
        android.graphics.Bitmap black = android.graphics.Bitmap.createBitmap(12, 12,
                android.graphics.Bitmap.Config.ARGB_8888);
        black.eraseColor(0xFF000000);
        Icon arrow = Icon.createWithBitmap(white);
        Icon darker = Icon.createWithBitmap(black);
        String title = "45\u7c73\u540e\u5de6\u8f6c";
        String content = "\u9ad8\u5fb7\u5bfc\u822a\u4e2d";
        IslandModel navigation = new IslandModel.Builder().setKey("visual-navigation")
                .setLeft(new IslandModel.Part.Builder()
                        .setComponent("templateRevertProgressScene").setTitle(title)
                        .setContent(content).setIcon(arrow).setDarkIcon(darker).build())
                .setAppIcon(darker).build();
        IslandContentView view = new IslandContentView(context);
        view.setIconResolver(value -> IslandProtocol.loadIcon(context, value, context.getUserId()));
        int width = context.getResources().getDisplayMetrics().widthPixels;
        view.setViewportWidth(width);
        view.setCutoutGap(Math.round(22 * context.getResources().getDisplayMetrics().density));
        view.setActivities(java.util.Collections.singletonList(navigation));
        measureLayout(view, width);
        checkNotNull("android.navigation.compact.title", visibleLayoutText(view, title));
        checkEq("android.navigation.compact.no.subtitle", null, visibleLayoutText(view, content));
        check("android.navigation.compact.gap.centred",
                Math.abs(view.getCutoutCenterX() - view.getMeasuredWidth() / 2) <= 1);
        List<android.widget.ImageView> images = new ArrayList<>();
        collectLayoutImages(view, images);
        checkEq("android.navigation.compact.one.direction", 1, images.size());
        if (images.size() == 1) {
            check("android.navigation.compact.white.direction", hasWhitePixel(images.get(0)));
        }

        view.setExpanded(true);
        measureLayout(view, width);
        check("android.navigation.expanded.mode", view.isNavigationExpanded());
        checkEq("android.navigation.expanded.no.camera.gap", -1, view.getCutoutCenterX());
        check("android.navigation.expanded.wide.card", view.getMeasuredWidth() >= width * 4 / 5
                && view.getMeasuredWidth() <= width);
        android.widget.TextView expandedTitle = visibleLayoutText(view, title);
        checkNotNull("android.navigation.expanded.title", expandedTitle);
        checkNotNull("android.navigation.expanded.subtitle", visibleLayoutText(view, content));
        images.clear();
        collectLayoutImages(view, images);
        checkEq("android.navigation.expanded.only.direction", 1, images.size());
        if (images.size() == 1) {
            check("android.navigation.expanded.white.direction", hasWhitePixel(images.get(0)));
            if (expandedTitle != null) {
                android.graphics.Rect textBounds = new android.graphics.Rect();
                expandedTitle.getDrawingRect(textBounds);
                view.offsetDescendantRectToMyCoords(expandedTitle, textBounds);
                android.graphics.Rect arrowBounds = new android.graphics.Rect();
                images.get(0).getDrawingRect(arrowBounds);
                view.offsetDescendantRectToMyCoords(images.get(0), arrowBounds);
                check("android.navigation.expanded.direction.after.text",
                        arrowBounds.left >= textBounds.right);
            }
        }
        check("android.navigation.expanded.card.receives.input", view.getTouchableRegion()
                .contains(view.getMeasuredWidth() / 2, view.getMeasuredHeight() / 2));
        view.setExpanded(false);
        measureLayout(view, width);
        check("android.navigation.collapse.restores.mode", !view.isNavigationExpanded());
        check("android.navigation.collapse.restores.gap", view.getCutoutCenterX() >= 0);
        checkEq("android.navigation.collapse.hides.subtitle", null, visibleLayoutText(view, content));

        view.setExpanded(true);
        view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                .setKey("generic-after-navigation")
                .setLeft(new IslandModel.Part.Builder().setTitle("ordinary activity").build()).build()));
        measureLayout(view, width);
        check("android.navigation.replaced.by.generic", !view.isNavigationExpanded());
        checkNotNull("android.navigation.generic.remains.visible",
                visibleLayoutText(view, "ordinary activity"));
        checkEq("android.navigation.old.subtitle.gone", null, visibleLayoutText(view, content));
    }

    /** A navigation transition must interpolate visible bounds, reverse, and cancel safely. */
    private static void testAndroidNavigationMorph(Context context) {
        float durationScale = android.animation.ValueAnimator.getDurationScale();
        IslandContentView view = new IslandContentView(context);
        int width = context.getResources().getDisplayMetrics().widthPixels;
        float density = context.getResources().getDisplayMetrics().density;
        int topOffset = Math.round(48 * density);
        String key = "morph-navigation";
        IslandModel navigation = new IslandModel.Builder().setKey(key)
                .setLeft(new IslandModel.Part.Builder()
                        .setComponent("templateRevertProgressScene")
                        .setTitle("45 m turn left").setContent("navigation running")
                        .setIcon(Icon.createWithResource("android", android.R.drawable.ic_media_previous))
                        .build()).build();
        try {
            android.animation.ValueAnimator.setDurationScale(1f);
            view.setIconResolver(value -> IslandProtocol.loadIcon(context, value, context.getUserId()));
            view.setViewportWidth(width);
            view.setCutoutGap(Math.round(22 * density));
            view.setCardMorphMetrics(width, width / 2, topOffset, view.getCompactHeight());
            view.setActivities(java.util.Collections.singletonList(navigation));
            view.setVisible(true);
            finishViewAnimator(view, "mAppearanceAnimator");
            measureLayout(view, width);
            view.setExpanded(true);
            measureLayout(view, width);
            android.animation.ValueAnimator forward = cardMorphAnimator(view);
            check("android.morph.forward.started", forward != null && view.isCardMorphRunning());
            if (forward == null) return;
            forward.pause();
            forward.setCurrentPlayTime(0);
            android.graphics.RectF start = new android.graphics.RectF();
            check("android.morph.start.has.bounds", view.getCardMorphBounds(start));
            check("android.morph.transparent.future.card.passes.input", !view.getTouchableRegion()
                    .contains(width / 2, topOffset + view.getCompactHeight()));
            forward.setCurrentPlayTime(forward.getDuration() / 2);
            android.graphics.RectF middle = new android.graphics.RectF();
            check("android.morph.middle.has.bounds", view.getCardMorphBounds(middle));
            float middleFraction = view.getCardMorphFraction();
            check("android.morph.middle.fraction", middleFraction > 0f && middleFraction < 1f);
            check("android.morph.middle.wider", middle.width() > start.width());
            check("android.morph.middle.taller", middle.height() > start.height());
            check("android.morph.middle.moves.down", middle.top > start.top);
            android.graphics.Rect touchBounds = view.getTouchableRegion().getBounds();
            check("android.morph.input.within.visible.bounds",
                    touchBounds.left >= Math.floor(middle.left)
                            && touchBounds.top >= Math.floor(middle.top)
                            && touchBounds.right <= Math.ceil(middle.right)
                            && touchBounds.bottom <= Math.ceil(middle.bottom));
            check("android.morph.camera.passes.input", !view.getTouchableRegion().contains(width / 2, 1));
            check("android.morph.card.centre.receives.input", view.getTouchableRegion()
                    .contains(Math.round(middle.centerX()), Math.round(middle.centerY())));
            check("android.morph.text.not.scaled", textScalesAreOne(view));

            // An update from the same live navigation session must not restart its animation.
            view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder().setKey(key)
                    .setLeft(new IslandModel.Part.Builder()
                            .setComponent("templateRevertProgressScene")
                            .setTitle("45 m turn left").setContent("route updated")
                            .setIcon(navigation.left.icon).build()).build()));
            measureLayout(view, width);
            check("android.morph.content.update.keeps.progress",
                    Math.abs(view.getCardMorphFraction() - middleFraction) < 0.01f);
            android.graphics.RectF beforeReverse = new android.graphics.RectF();
            view.getCardMorphBounds(beforeReverse);
            view.setExpanded(false);
            measureLayout(view, width);
            android.graphics.RectF reversed = new android.graphics.RectF();
            view.getCardMorphBounds(reversed);
            check("android.morph.reverse.no.bounds.jump", closeBounds(beforeReverse, reversed));
            android.animation.ValueAnimator reverse = cardMorphAnimator(view);
            check("android.morph.reverse.started", reverse != null && view.isCardMorphRunning());
            if (reverse == null) return;
            reverse.pause();
            reverse.setCurrentPlayTime(reverse.getDuration() / 2);
            check("android.morph.reverse.returns.towards.pill",
                    view.getCardMorphFraction() < middleFraction);
            reverse.end();
            measureLayout(view, width);
            check("android.morph.collapse.settled", !view.isCardMorphRunning()
                    && !view.isExpanded() && view.getCardMorphFraction() == 0f);

            android.animation.ValueAnimator.setDurationScale(0f);
            view.setExpanded(true);
            measureLayout(view, width);
            check("android.morph.disabled.expands.immediately", !view.isCardMorphRunning()
                    && view.isNavigationExpanded() && view.getCardMorphFraction() == 1f);
            view.setExpanded(false);
            measureLayout(view, width);
            check("android.morph.disabled.collapses.immediately", !view.isCardMorphRunning()
                    && !view.isExpanded() && view.getCardMorphFraction() == 0f);

            android.animation.ValueAnimator.setDurationScale(1f);
            view.setExpanded(true);
            measureLayout(view, width);
            android.animation.ValueAnimator interrupted = cardMorphAnimator(view);
            if (interrupted != null) interrupted.pause();
            view.setActivities(java.util.Collections.singletonList(new IslandModel.Builder()
                    .setKey("generic-interrupts-navigation")
                    .setLeft(new IslandModel.Part.Builder().setTitle("ordinary activity").build()).build()));
            measureLayout(view, width);
            check("android.morph.template.switch.cancels", !view.isCardMorphRunning());
            checkNotNull("android.morph.template.switch.shows.new.activity",
                    visibleLayoutText(view, "ordinary activity"));

            view.setExpanded(false);
            view.setActivities(java.util.Collections.singletonList(navigation));
            measureLayout(view, width);
            view.setExpanded(true);
            measureLayout(view, width);
            view.resetAppearance();
            check("android.morph.reset.cancels", !view.isCardMorphRunning());
            checkEq("android.morph.reset.hides", android.view.View.INVISIBLE, view.getVisibility());
        } catch (ReflectiveOperationException error) {
            fail("android.morph.animator.control " + error);
        } finally {
            view.resetAppearance();
            android.animation.ValueAnimator.setDurationScale(durationScale);
        }
    }

    /**
     * One generic (non-navigation) activity through its real visible lifecycle: the compact pill
     * must give the whole free budget to its title instead of splitting it with the detail, the
     * expansion must morph into the wide card (viewport minus one 16dp margin per side), the same
     * live session must keep its progress while its content is updated, the settled card must show
     * every row, timer, progress and a genuinely clickable action button, the reverse must land back
     * on the intact compact pill, disabled animations must jump to both endpoints, and switching to
     * another template or key must not leave stale content or a half morphed shape behind.
     *
     * <p>Every assertion reads laid out text, icon or touch geometry. Nothing here depends on a
     * private field default; only the animator handle is reflected, exactly like the navigation
     * morph regression above.
     */
    private static void testAndroidGenericCardLifecycle(Context context) {
        float durationScale = android.animation.ValueAnimator.getDurationScale();
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        float density = metrics.density;
        int topOffset = Math.round(48 * density);
        int cardWidth = width - 2 * Math.round(16 * density);
        String key = "generic-qoder";
        String title = "\u4efb\u52a1\u5df2\u5b8c\u6210";
        String detail = "\u5df2\u5b8c\u6210 Qoder CN";
        String secondaryTitle = "\u5907\u4efd\u4efb\u52a1";
        String rowText = "Qoder CN 2 \u4e2a\u6587\u4ef6\u5df2\u66f4\u65b0";
        String actionTitle = "\u67e5\u770b\u8be6\u60c5";
        Icon icon = purpleIcon();

        final AtomicInteger actionClicks = new AtomicInteger();
        final String[] actionEvent = new String[2];
        IslandContentView view = new IslandContentView(context);
        view.setListener(new IslandContentView.Listener() {
            @Override
            public void onIslandClicked(IslandModel model) {
            }

            @Override
            public void onIslandLongPressed(IslandModel model) {
            }

            @Override
            public void onIslandActionClicked(IslandModel model, IslandModel.Action action) {
                actionClicks.incrementAndGet();
                actionEvent[0] = model.key;
                actionEvent[1] = action.title;
            }

            @Override
            public void onIslandDismissed(IslandModel model) {
            }

            @Override
            public void onOutsideTouched() {
            }
        });
        try {
            android.animation.ValueAnimator.setDurationScale(1f);
            view.setIconResolver(value -> IslandProtocol.loadIcon(context, value,
                    context.getUserId()));
            view.setViewportWidth(width);
            view.setCutoutGap(Math.round(22 * density));
            view.setCardMorphMetrics(width, width / 2, topOffset, view.getCompactHeight());
            view.setActivities(genericActivities(key, icon, title, detail, secondaryTitle, 65,
                    125_000L, actionTitle));
            view.setVisible(true);
            finishViewAnimator(view, "mAppearanceAnimator");
            measureLayout(view, width);

            // --- compact: one summary slot, the whole title, detail never steals its width.
            android.widget.TextView compactTitle = visibleLayoutText(view, title);
            checkNotNull("android.generic.compact.title", compactTitle);
            int compactTitleWidth = compactTitle == null ? 0 : compactTitle.getMeasuredWidth();
            if (compactTitle != null) {
                // "任务已完成" is five characters: the measured view must really hold all of them.
                check("android.generic.compact.title.holds.five.chars",
                        compactTitle.getMeasuredWidth() + 0.5f >= compactTitle.getPaint()
                                .measureText(title));
                check("android.generic.compact.title.not.ellipsized",
                        compactTitle.getLayout() != null
                                && compactTitle.getLayout().getEllipsisCount(0) == 0);
            }
            checkEq("android.generic.compact.title.once", 1, countVisibleText(view, title));
            checkNull("android.generic.compact.detail.not.splitting.title",
                    visibleLayoutText(view, detail));
            check("android.generic.compact.detail.kept.for.accessibility",
                    contentDescriptionContains(view, detail));
            check("android.generic.compact.not.expanded", !view.isCardExpanded());
            check("android.generic.compact.no.morph", !view.isCardMorphRunning()
                    && view.getCardMorphFraction() == 0f);
            check("android.generic.compact.camera.gap.centred",
                    Math.abs(view.getCutoutCenterX() - view.getMeasuredWidth() / 2) <= 1);

            List<android.widget.ImageView> compactImages = new ArrayList<>();
            collectLayoutImages(view, compactImages);
            checkEq("android.generic.compact.own.icons", 2, compactImages.size());
            if (compactImages.size() == 2) {
                check("android.generic.compact.icon.decoded", hasColorPixel(compactImages.get(0),
                        0xFF6C5CE7));
            }
            android.graphics.Region compactTouch = view.getTouchableRegion();
            check("android.generic.compact.camera.untouchable", view.getCutoutCenterX() >= 0
                    && !compactTouch.contains(view.getCutoutCenterX(), 1));
            if (compactTitle != null) {
                android.graphics.Rect titleBounds = new android.graphics.Rect(0, 0,
                        compactTitle.getWidth(), compactTitle.getHeight());
                view.offsetDescendantRectToMyCoords(compactTitle, titleBounds);
                check("android.generic.compact.title.touchable", compactTouch.contains(
                        titleBounds.centerX(), titleBounds.centerY()));
            }

            // --- expand: the morph travels continuously and input follows the visible shape only.
            view.setExpanded(true);
            measureLayout(view, width);
            check("android.generic.expand.is.expanded", view.isCardExpanded());
            android.animation.ValueAnimator forward = cardMorphAnimator(view);
            check("android.generic.expand.morph.started",
                    forward != null && view.isCardMorphRunning());
            if (forward == null) {
                fail("android.generic.expand.morph.missing");
                return;
            }
            forward.pause();
            forward.setCurrentPlayTime(0);
            android.graphics.RectF start = new android.graphics.RectF();
            check("android.generic.morph.start.bounds", view.getCardMorphBounds(start));
            check("android.generic.morph.start.future.card.untouchable",
                    !view.getTouchableRegion().contains(width / 2,
                            topOffset + view.getCompactHeight()));
            check("android.generic.morph.start.camera.untouchable",
                    !view.getTouchableRegion().contains(width / 2, 1));

            forward.setCurrentPlayTime(forward.getDuration() / 2);
            android.graphics.RectF middle = new android.graphics.RectF();
            check("android.generic.morph.middle.bounds", view.getCardMorphBounds(middle));
            float middleFraction = view.getCardMorphFraction();
            check("android.generic.morph.middle.fraction",
                    middleFraction > 0f && middleFraction < 1f);
            check("android.generic.morph.middle.wider", middle.width() > start.width());
            check("android.generic.morph.middle.taller", middle.height() > start.height());
            check("android.generic.morph.middle.moves.down", middle.top > start.top);
            android.graphics.Region middleTouch = view.getTouchableRegion();
            android.graphics.Rect touchBounds = middleTouch.getBounds();
            check("android.generic.morph.input.within.visible.path",
                    touchBounds.left >= Math.floor(middle.left)
                            && touchBounds.top >= Math.floor(middle.top)
                            && touchBounds.right <= Math.ceil(middle.right)
                            && touchBounds.bottom <= Math.ceil(middle.bottom));
            check("android.generic.morph.camera.untouchable",
                    !middleTouch.contains(width / 2, 1));
            check("android.generic.morph.visible.card.touchable", middleTouch.contains(
                    Math.round(middle.centerX()), Math.round(middle.centerY())));
            int envelope = view.getCardMorphEnvelopeHeight();
            check("android.generic.morph.envelope.covers.card", envelope > middle.bottom);
            check("android.generic.morph.future.card.untouchable",
                    !middleTouch.contains(width / 2, envelope - 2));
            check("android.generic.morph.text.not.scaled", textScalesAreOne(view));

            // A same-key update from the live session must not restart or jump the morph, and its
            // new content must already be in place.
            view.setActivities(genericActivities(key, icon, title, detail, secondaryTitle, 80,
                    60_000L, "\u7ee7\u7eed\u67e5\u770b"));
            measureLayout(view, width);
            check("android.generic.update.keeps.progress",
                    Math.abs(view.getCardMorphFraction() - middleFraction) < 0.01f);
            checkNotNull("android.generic.update.content.during.morph",
                    visibleLayoutText(view, "\u8fdb\u5ea6 80%"));
            forward.end();
            measureLayout(view, width);

            // --- settled wide card: only the card, complete content, real action button.
            check("android.generic.expanded.settled", !view.isCardMorphRunning()
                    && view.isCardExpanded() && view.getCardMorphFraction() == 1f);
            android.graphics.RectF expandedBounds = new android.graphics.RectF();
            check("android.generic.expanded.has.bounds",
                    view.getCardMorphBounds(expandedBounds));
            check("android.generic.card.width.viewport.minus.32dp",
                    Math.abs(expandedBounds.width() - cardWidth) <= 1f);
            checkEq("android.generic.view.width.card", cardWidth, view.getMeasuredWidth());
            checkEq("android.generic.expanded.camera.gap.gone", -1, view.getCutoutCenterX());
            checkEq("android.generic.expanded.title.once", 1, countVisibleText(view, title));
            checkNull("android.generic.expanded.secondary.gone",
                    visibleLayoutText(view, secondaryTitle));
            android.widget.TextView cardContent = visibleLayoutText(view, detail);
            checkNotNull("android.generic.expanded.content", cardContent);
            check("android.generic.expanded.content.not.ellipsized", cardContent != null
                    && cardContent.getLayout() != null
                    && cardContent.getLayout().getEllipsisCount(0) == 0);
            android.widget.TextView baseRowView = visibleLayoutText(view, rowText);
            checkNotNull("android.generic.expanded.base.row", baseRowView);
            check("android.generic.expanded.base.row.not.ellipsized", baseRowView != null
                    && baseRowView.getLayout() != null
                    && baseRowView.getLayout().getEllipsisCount(0) == 0);
            android.widget.TextView progressRowView = visibleLayoutText(view, "\u8fdb\u5ea6 80%");
            checkNotNull("android.generic.expanded.progress.row", progressRowView);
            check("android.generic.expanded.progress.row.not.ellipsized", progressRowView != null
                    && progressRowView.getLayout() != null
                    && progressRowView.getLayout().getEllipsisCount(0) == 0);
            checkNotNull("android.generic.expanded.timer", visibleLayoutText(view, "01:00"));
            checkEq("android.generic.expanded.progress.visible", 1, countVisibleViews(
                    view, android.widget.ProgressBar.class));

            List<android.widget.ImageView> expandedImages = new ArrayList<>();
            collectLayoutImages(view, expandedImages);
            checkEq("android.generic.expanded.only.card.icons", 3, expandedImages.size());
            for (android.widget.ImageView image : expandedImages) {
                android.graphics.Rect iconBounds = new android.graphics.Rect(0, 0,
                        image.getWidth(), image.getHeight());
                view.offsetDescendantRectToMyCoords(image, iconBounds);
                check("android.generic.expanded.icon.inside.card",
                        iconBounds.left >= expandedBounds.left - 1
                                && iconBounds.right <= expandedBounds.right + 1);
            }
            android.view.View actionButton = findClickableByDescription(view,
                    "\u7ee7\u7eed\u67e5\u770b");
            checkNotNull("android.generic.expanded.action.button", actionButton);
            if (actionButton != null) {
                check("android.generic.expanded.action.laid.out",
                        actionButton.getWidth() > 0 && actionButton.getHeight() > 0);
                android.widget.TextView actionLabel = visibleLayoutText(actionButton,
                        "\u7ee7\u7eed\u67e5\u770b");
                checkNotNull("android.generic.expanded.action.label", actionLabel);
                if (actionLabel != null && actionLabel.getLayout() != null) {
                    android.text.Layout textLayout = actionLabel.getLayout();
                    check("android.generic.expanded.action.glyphs.within.button",
                            textLayout.getLineLeft(0) >= actionLabel.getScrollX()
                                    && textLayout.getLineRight(0) <= actionLabel.getScrollX()
                                            + actionLabel.getWidth());
                }
                android.graphics.Rect actionBounds = new android.graphics.Rect(0, 0,
                        actionButton.getWidth(), actionButton.getHeight());
                view.offsetDescendantRectToMyCoords(actionButton, actionBounds);
                check("android.generic.expanded.action.inside.card",
                        actionBounds.left >= expandedBounds.left
                                && actionBounds.right <= expandedBounds.right);
                actionButton.performClick();
                checkEq("android.generic.action.click.delivered", 1, actionClicks.get());
                checkEq("android.generic.action.click.model", key, actionEvent[0]);
                checkEq("android.generic.action.click.action", "\u7ee7\u7eed\u67e5\u770b",
                        actionEvent[1]);
            }
            checkNull("android.generic.expanded.old.action.gone",
                    findClickableByDescription(view, actionTitle));
            check("android.generic.expanded.card.touchable", view.getTouchableRegion()
                    .contains(Math.round(expandedBounds.centerX()),
                            Math.round(expandedBounds.centerY())));

            // --- reverse, retarget again, then settle back on the intact pill.
            view.setExpanded(false);
            measureLayout(view, width);
            android.animation.ValueAnimator reverse = cardMorphAnimator(view);
            check("android.generic.reverse.started",
                    reverse != null && view.isCardMorphRunning());
            if (reverse == null) {
                fail("android.generic.reverse.morph.missing");
                return;
            }
            reverse.pause();
            reverse.setCurrentPlayTime(reverse.getDuration() / 2);
            float reverseFraction = view.getCardMorphFraction();
            check("android.generic.reverse.returns.towards.pill", reverseFraction < 1f);
            android.graphics.RectF beforeRetarget = new android.graphics.RectF();
            check("android.generic.reverse.has.bounds",
                    view.getCardMorphBounds(beforeRetarget));
            view.setExpanded(true);
            measureLayout(view, width);
            android.graphics.RectF afterRetarget = new android.graphics.RectF();
            check("android.generic.retarget.has.bounds",
                    view.getCardMorphBounds(afterRetarget));
            check("android.generic.retarget.no.bounds.jump",
                    closeBounds(beforeRetarget, afterRetarget));
            check("android.generic.retarget.continues.from.current",
                    Math.abs(view.getCardMorphFraction() - reverseFraction) < 0.05f);
            android.animation.ValueAnimator retarget = cardMorphAnimator(view);
            if (retarget != null) retarget.pause();
            view.setExpanded(false);
            measureLayout(view, width);
            android.animation.ValueAnimator collapse = cardMorphAnimator(view);
            if (collapse != null) collapse.end();
            measureLayout(view, width);
            check("android.generic.collapse.settled", !view.isCardMorphRunning()
                    && !view.isCardExpanded() && view.getCardMorphFraction() == 0f);
            android.widget.TextView restoredTitle = visibleLayoutText(view, title);
            checkNotNull("android.generic.collapse.restores.title", restoredTitle);
            if (restoredTitle != null) {
                checkEq("android.generic.collapse.title.width.restored", compactTitleWidth,
                        restoredTitle.getMeasuredWidth());
                check("android.generic.collapse.title.still.full", restoredTitle.getLayout() != null
                        && restoredTitle.getLayout().getEllipsisCount(0) == 0);
            }
            checkEq("android.generic.collapse.title.once", 1, countVisibleText(view, title));
            checkNull("android.generic.collapse.hides.content", visibleLayoutText(view, detail));
            checkNotNull("android.generic.collapse.restores.secondary",
                    visibleLayoutText(view, secondaryTitle));
            check("android.generic.collapse.detail.kept", contentDescriptionContains(view, detail));
            compactImages.clear();
            collectLayoutImages(view, compactImages);
            checkEq("android.generic.collapse.icons.restored", 2, compactImages.size());

            // --- animations disabled: both endpoints are reached in the same frame.
            android.animation.ValueAnimator.setDurationScale(0f);
            view.setExpanded(true);
            measureLayout(view, width);
            check("android.generic.disabled.expands.immediately", !view.isCardMorphRunning()
                    && view.isCardExpanded() && view.getCardMorphFraction() == 1f);
            checkNotNull("android.generic.disabled.content", visibleLayoutText(view, rowText));
            android.graphics.RectF disabledBounds = new android.graphics.RectF();
            check("android.generic.disabled.bounds", view.getCardMorphBounds(disabledBounds));
            check("android.generic.disabled.card.width",
                    Math.abs(disabledBounds.width() - cardWidth) <= 1f);
            view.setExpanded(false);
            measureLayout(view, width);
            check("android.generic.disabled.collapses.immediately", !view.isCardMorphRunning()
                    && !view.isCardExpanded() && view.getCardMorphFraction() == 0f);
            checkNotNull("android.generic.disabled.compact.title", visibleLayoutText(view, title));
            android.animation.ValueAnimator.setDurationScale(1f);

            // --- a different template must terminate the generic morph without stale content.
            view.setActivities(genericActivities(key, icon, title, detail, secondaryTitle, 80,
                    60_000L, "\u7ee7\u7eed\u67e5\u770b"));
            measureLayout(view, width);
            view.setExpanded(true);
            measureLayout(view, width);
            android.animation.ValueAnimator genericMorph = cardMorphAnimator(view);
            check("android.generic.template.setup.morph", genericMorph != null
                    && view.isCardMorphRunning());
            if (genericMorph != null) {
                genericMorph.pause();
                genericMorph.setCurrentPlayTime(genericMorph.getDuration() / 2);
            }
            IslandModel navigation = new IslandModel.Builder().setKey("generic-interrupted-by-nav")
                    .setLeft(new IslandModel.Part.Builder()
                            .setComponent("templateRevertProgressScene")
                            .setTitle("45 m turn left").setContent("navigation running")
                            .setIcon(icon).build()).build();
            view.setActivities(java.util.Collections.singletonList(navigation));
            measureLayout(view, width);
            check("android.generic.template.switch.settles", !view.isCardMorphRunning());
            check("android.generic.template.switch.navigation.expanded",
                    view.isNavigationExpanded());
            checkNotNull("android.generic.template.switch.shows.navigation",
                    visibleLayoutText(view, "45 m turn left"));
            checkNull("android.generic.template.switch.no.old.title",
                    visibleLayoutText(view, title));
            checkNull("android.generic.template.switch.no.old.row", visibleLayoutText(view, rowText));

            // --- a different generic key keeps the running morph but never reuses old content.
            view.setExpanded(false);
            measureLayout(view, width);
            android.animation.ValueAnimator navCollapse = cardMorphAnimator(view);
            if (navCollapse != null) navCollapse.end();
            measureLayout(view, width);
            view.setActivities(genericActivities(key, icon, title, detail, secondaryTitle, 80,
                    60_000L, "\u7ee7\u7eed\u67e5\u770b"));
            measureLayout(view, width);
            check("android.generic.after.navigation.compact", view.getCutoutCenterX() >= 0);
            view.setExpanded(true);
            measureLayout(view, width);
            android.animation.ValueAnimator keyMorph = cardMorphAnimator(view);
            check("android.generic.key.setup.morph", keyMorph != null
                    && view.isCardMorphRunning());
            if (keyMorph == null) {
                fail("android.generic.key.morph.missing");
                return;
            }
            keyMorph.pause();
            keyMorph.setCurrentPlayTime(keyMorph.getDuration() / 2);
            float keyFraction = view.getCardMorphFraction();
            view.setActivities(genericActivities("generic-other", icon,
                    "\u53e6\u4e00\u4e2a\u4efb\u52a1", "\u6b63\u5728\u6574\u7406",
                    "\u65b0\u7684\u5907\u4efd", 30, 120_000L, "\u6253\u5f00"));
            measureLayout(view, width);
            check("android.generic.different.key.keeps.progress",
                    Math.abs(view.getCardMorphFraction() - keyFraction) < 0.01f);
            android.animation.ValueAnimator remaining = cardMorphAnimator(view);
            if (remaining != null) remaining.end();
            measureLayout(view, width);
            check("android.generic.different.key.settled", !view.isCardMorphRunning()
                    && view.isCardExpanded());
            checkEq("android.generic.different.key.new.title.once", 1,
                    countVisibleText(view, "\u53e6\u4e00\u4e2a\u4efb\u52a1"));
            checkNull("android.generic.different.key.no.old.title",
                    visibleLayoutText(view, title));
            checkNull("android.generic.different.key.no.old.detail",
                    visibleLayoutText(view, detail));
            checkNull("android.generic.different.key.no.old.progress",
                    visibleLayoutText(view, "\u8fdb\u5ea6 80%"));
            checkNull("android.generic.different.key.no.old.action",
                    findClickableByDescription(view, "\u7ee7\u7eed\u67e5\u770b"));
            checkNotNull("android.generic.different.key.new.action",
                    findClickableByDescription(view, "\u6253\u5f00"));
            checkNotNull("android.generic.different.key.new.content",
                    visibleLayoutText(view, "\u6b63\u5728\u6574\u7406"));
        } catch (ReflectiveOperationException error) {
            fail("android.generic.animator.control " + error);
        } finally {
            view.resetAppearance();
            android.animation.ValueAnimator.setDurationScale(durationScale);
        }
    }

    /** One generic activity plus the secondary line the compact island renders below it. */
    private static List<IslandModel> genericActivities(String key, Icon icon, String title,
            String detail, String secondaryTitle, int progress, long timerMillis,
            String actionTitle) {
        long now = System.currentTimeMillis();
        IslandModel.Part header = new IslandModel.Part.Builder()
                .setComponent("baseInfo")
                .setTitle(title)
                .setContent(detail)
                .setTimer(new IslandModel.Timer(IslandModel.Timer.TYPE_COUNTDOWN_PAUSED,
                        now, timerMillis, now))
                .build();
        IslandModel.Part baseRow = new IslandModel.Part.Builder()
                .setComponent("baseInfo")
                .setTitle("Qoder CN")
                .setContent("2 \u4e2a\u6587\u4ef6\u5df2\u66f4\u65b0")
                .setIcon(icon)
                .build();
        IslandModel.Part progressRow = new IslandModel.Part.Builder()
                .setComponent("progressInfo")
                .setTitle("\u8fdb\u5ea6")
                .setProgress(progress)
                .build();
        IslandModel primary = new IslandModel.Builder().setKey(key)
                .setPackageName("com.example.qoder")
                .setLeft(new IslandModel.Part.Builder()
                        .setComponent("baseInfo")
                        .setTitle(title)
                        .setContent(detail)
                        .setIcon(icon)
                        .build())
                .setExpanded(java.util.Arrays.asList(header, baseRow, progressRow))
                .setAppIcon(icon)
                .addAction(new IslandModel.Action("open", actionTitle, icon, null, null, 0,
                        false))
                .build();
        IslandModel secondary = new IslandModel.Builder().setKey(key + "-secondary")
                .setSmall(new IslandModel.Part.Builder()
                        .setTitle(secondaryTitle)
                        .setIcon(icon)
                        .build())
                .build();
        return java.util.Arrays.asList(primary, secondary);
    }

    /** A distinct, non-resource icon so its pixels prove which layout rendered it. */
    private static Icon purpleIcon() {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(24, 24,
                android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF6C5CE7);
        return Icon.createWithBitmap(bitmap);
    }

    /** Counts visible TextViews whose whole text equals {@code text}. */
    private static int countVisibleText(android.view.View root, String text) {
        int count = 0;
        if (root.getVisibility() != android.view.View.VISIBLE) {
            return 0;
        }
        if (root instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) root).getText())) {
            count++;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countVisibleText(group.getChildAt(i), text);
            }
        }
        return count;
    }

    /** Counts visible views of {@code type} (used for the card's single progress bar). */
    private static int countVisibleViews(android.view.View root, Class<?> type) {
        int count = 0;
        if (root.getVisibility() != android.view.View.VISIBLE) {
            return 0;
        }
        if (type.isInstance(root)) {
            count++;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countVisibleViews(group.getChildAt(i), type);
            }
        }
        return count;
    }

    /** True when any visible view's content description contains {@code text}. */
    private static boolean contentDescriptionContains(android.view.View root, String text) {
        if (root.getVisibility() != android.view.View.VISIBLE) {
            return false;
        }
        CharSequence description = root.getContentDescription();
        if (description != null && description.toString().contains(text)) {
            return true;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (contentDescriptionContains(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Finds the visible, clickable view the island attaches {@code description} to. */
    private static android.view.View findClickableByDescription(android.view.View root,
            String description) {
        if (root.getVisibility() != android.view.View.VISIBLE) {
            return null;
        }
        CharSequence own = root.getContentDescription();
        if (root.isClickable() && own != null && description.contentEquals(own)) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findClickableByDescription(group.getChildAt(i),
                        description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Reports whether the drawable renders the purple test icon (proves which slot it came from). */
    private static boolean hasColorPixel(android.widget.ImageView view, int expected) {
        android.graphics.drawable.Drawable drawable = view.getDrawable();
        if (drawable == null) {
            return false;
        }
        android.graphics.Bitmap rendered = android.graphics.Bitmap.createBitmap(24, 24,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 24, 24);
        drawable.draw(new android.graphics.Canvas(rendered));
        drawable.setBounds(previous);
        int target = rendered.getPixel(12, 12);
        return android.graphics.Color.alpha(target) > 128
                && Math.abs(android.graphics.Color.red(target)
                        - android.graphics.Color.red(expected)) < 24
                && Math.abs(android.graphics.Color.green(target)
                        - android.graphics.Color.green(expected)) < 24
                && Math.abs(android.graphics.Color.blue(target)
                        - android.graphics.Color.blue(expected)) < 24;
    }

    private static android.animation.ValueAnimator cardMorphAnimator(IslandContentView view)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = IslandContentView.class.getDeclaredField("mCardMorphAnimator");
        field.setAccessible(true);
        return (android.animation.ValueAnimator) field.get(view);
    }

    private static void finishViewAnimator(IslandContentView view, String name)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = IslandContentView.class.getDeclaredField(name);
        field.setAccessible(true);
        android.animation.ValueAnimator animator = (android.animation.ValueAnimator) field.get(view);
        if (animator != null) animator.end();
    }

    private static boolean closeBounds(android.graphics.RectF first, android.graphics.RectF second) {
        return Math.abs(first.left - second.left) <= 1f && Math.abs(first.top - second.top) <= 1f
                && Math.abs(first.right - second.right) <= 1f
                && Math.abs(first.bottom - second.bottom) <= 1f;
    }

    private static boolean textScalesAreOne(android.view.View view) {
        if (view instanceof android.widget.TextView
                && (view.getScaleX() != 1f || view.getScaleY() != 1f)) return false;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (!textScalesAreOne(group.getChildAt(i))) return false;
            }
        }
        return true;
    }

    private static void measureLayout(IslandContentView view, int width) {
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,
                        android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(2000,
                        android.view.View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static android.widget.TextView visibleLayoutText(android.view.View root, String text) {
        if (root.getVisibility() != android.view.View.VISIBLE) return null;
        if (root instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) root).getText())) {
            return (android.widget.TextView) root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = visibleLayoutText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasWhitePixel(android.widget.ImageView view) {
        android.graphics.drawable.Drawable drawable = view.getDrawable();
        if (drawable == null) return false;
        android.graphics.Bitmap rendered = android.graphics.Bitmap.createBitmap(24, 24,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 24, 24);
        drawable.draw(new android.graphics.Canvas(rendered));
        drawable.setBounds(previous);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int color = rendered.getPixel(x, y);
                if (android.graphics.Color.alpha(color) > 128
                        && android.graphics.Color.red(color) > 180
                        && android.graphics.Color.green(color) > 180
                        && android.graphics.Color.blue(color) > 180) return true;
            }
        }
        return false;
    }

    private static android.widget.TextView findLayoutText(android.view.View root, String text) {
        if (root instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) root).getText())) return (android.widget.TextView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findLayoutText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectLayoutImages(android.view.View root, List<android.widget.ImageView> images) {
        if (root.getVisibility() != android.view.View.VISIBLE) return;
        if (root instanceof android.widget.ImageView) images.add((android.widget.ImageView) root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectLayoutImages(group.getChildAt(i), images);
        }
    }

    /** The compact pill row: the first child of the content view in every compact presentation. */
    private static android.view.ViewGroup rowOf(IslandContentView view) {
        return (android.view.ViewGroup) view.getChildAt(0);
    }

    /** One child of the compact row: 0 is the half left of the camera, 1 the gap, 2 the right one. */
    private static android.view.View sideOf(IslandContentView view, int index) {
        return rowOf(view).getChildAt(index);
    }

    /** Bounds of {@code target} in the coordinate space of {@code root}. */
    private static android.graphics.Rect boundsIn(android.view.ViewGroup root, android.view.View target) {
        android.graphics.Rect rect = new android.graphics.Rect(0, 0,
                target.getWidth(), target.getHeight());
        root.offsetDescendantRectToMyCoords(target, rect);
        return rect;
    }

    private static int countLayoutTexts(android.view.View root) {
        if (root.getVisibility() != android.view.View.VISIBLE) return 0;
        if (root instanceof android.widget.TextView) {
            return android.text.TextUtils.isEmpty(((android.widget.TextView) root).getText()) ? 0 : 1;
        }
        int count = 0;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countLayoutTexts(group.getChildAt(i));
            }
        }
        return count;
    }

    /**
     * Asserts the collapsed pill balances its two halves around the reserved camera gap and returns
     * the shared half width.
     */
    private static int checkSplitGeometry(IslandContentView view, String name) {
        android.view.View row = rowOf(view);
        android.view.View left = sideOf(view, 0);
        android.view.View gap = sideOf(view, 1);
        android.view.View right = sideOf(view, 2);
        check(name + ".halves.present", row.getVisibility() == android.view.View.VISIBLE
                && left.getWidth() > 0 && right.getWidth() > 0);
        checkEq(name + ".equal.halves", left.getWidth(), right.getWidth());
        checkEq(name + ".width.is.two.max.plus.gap",
                2L * Math.max(left.getWidth(), right.getWidth()) + gap.getWidth(), row.getWidth());
        android.graphics.Rect gapBounds = boundsIn(view, gap);
        checkEq(name + ".gap.on.camera", gapBounds.centerX(), view.getCutoutCenterX());
        check(name + ".gap.centred",
                Math.abs(gapBounds.centerX() - view.getMeasuredWidth() / 2) <= 1);
        return left.getWidth();
    }

    /** Asserts the icon sits left of the camera and every text slot sits right of it. */
    private static void checkSplitSides(IslandContentView view, String name, String text) {
        android.graphics.Rect gapBounds = boundsIn(view, sideOf(view, 1));
        List<android.widget.ImageView> leftImages = new ArrayList<>();
        collectLayoutImages(sideOf(view, 0), leftImages);
        checkEq(name + ".one.icon.before.camera", 1, leftImages.size());
        if (leftImages.size() == 1) {
            check(name + ".icon.left.of.camera",
                    boundsIn(view, leftImages.get(0)).right <= gapBounds.left);
        }
        List<android.widget.ImageView> rightImages = new ArrayList<>();
        collectLayoutImages(sideOf(view, 2), rightImages);
        checkEq(name + ".text.half.has.no.icon", 0, rightImages.size());
        // One announcement, not two: the icon half is decorative and the text half carries every
        // field once.
        checkNull(name + ".icon.half.silent", sideOf(view, 0).getContentDescription());
        checkEq(name + ".icon.half.not.announced",
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                sideOf(view, 0).getImportantForAccessibility());
        if (text != null) {
            checkEq(name + ".text.half.announced",
                    android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                    sideOf(view, 2).getImportantForAccessibility());
            CharSequence description = sideOf(view, 2).getContentDescription();
            checkNotNull(name + ".text.half.description", description);
            check(name + ".description.keeps.text", description != null
                    && description.toString().contains(text));
            android.widget.TextView label = findLayoutText(sideOf(view, 2), text);
            checkNotNull(name + ".text.right.of.camera", label);
            if (label != null) {
                check(name + ".text.after.camera", boundsIn(view, label).left >= gapBounds.right);
            }
            checkNull(name + ".text.not.on.icon.half", findLayoutText(sideOf(view, 0), text));
        }
    }

    private static void testAndroidHappyPath(Context context) {
        String json = "{\"param_v2\":{\"protocol\":1,\"business\":\"taxi\","
                + "\"islandFirstFloat\":true,\"enableFloat\":false,\"updatable\":true,"
                + "\"timeout\":720,\"reopen\":\"reopen\",\"filterWhenNoPermission\":true,"
                + "\"param_island\":{\"islandProperty\":1,\"islandOrder\":true,"
                + "\"islandTimeout\":3600,\"highlightColor\":\"#FF69DAB7\","
                + "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,"
                + "\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_taxi\"},"
                + "\"textInfo\":{\"title\":\"\u63a5\u9a7e\u4e2d\",\"content\":\"5 \u5206\u949f\","
                + "\"showHighlightColor\":false}},"
                + "\"sameWidthDigitInfo\":{\"digit\":\"A12345\",\"content\":\"white\"}},"
                + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_taxi\"}}},"
                + "\"baseInfo\":{\"type\":2,\"title\":\"driver\",\"content\":\"1.2km\","
                + "\"subTitle\":\"5min\"},"
                + "\"progressInfo\":{\"progress\":65,\"colorProgress\":\"#69DAB7\","
                + "\"colorProgressEnd\":\"#91BFFF\"},"
                + "\"actions\":[{\"action\":\"miui.focus.action_call\"}],"
                + "\"aodTitle\":\"pickup\"}}";

        Notification notification = baseNotification(context);
        Bundle pics = new Bundle();
        Icon pic = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        pics.putParcelable("miui.focus.pic_taxi", pic);
        notification.extras.putBundle(IslandProtocol.EXTRA_PICS, pics);
        Bundle actions = new Bundle();
        actions.putParcelable("miui.focus.action_call",
                new Notification.Action.Builder(pic, "Call",
                        PendingIntent.getBroadcast(context, 0, new Intent("selftest.call"),
                                PendingIntent.FLAG_IMMUTABLE)).build());
        notification.extras.putBundle(IslandProtocol.EXTRA_ACTIONS, actions);
        notification.extras.putString(IslandProtocol.EXTRA_ISLAND, json);

        StatusBarNotification sbn = sbn(context, 1, notification);
        IslandModel model = IslandProtocol.parse(context, sbn);
        checkNotNull("android.model", model);
        if (model == null) {
            return;
        }
        checkEq("android.key", sbn.getKey(), model.key);
        checkEq("android.package", context.getPackageName(), model.packageName);
        checkEq("android.business", "taxi", model.business);
        checkEq("android.aodTitle", "pickup", model.aodTitle);
        checkEq("android.protocol", 1, model.protocol);
        check("android.firstFloat", model.firstFloat);
        check("android.updatable", model.updatable);
        check("android.filterWhenNoPermission", model.filterWhenNoPermission);
        check("android.reorder", model.reorder);
        checkEq("android.reopen", IslandModel.REOPEN, model.reopen);
        checkEq("android.lifetime", 720L * 60_000L, model.lifetimeMillis);
        checkEq("android.islandLifetime", 3_600_000L, model.islandLifetimeMillis);
        checkEq("android.highlightColor", 0xFF69DAB7, model.highlightColor);
        checkNotNull("android.left", model.left);
        checkEq("android.left.title", "\u63a5\u9a7e\u4e2d", model.left.title);
        checkEq("android.left.icon", pic, model.left.icon);
        checkNotNull("android.right", model.right);
        checkEq("android.right.component", "sameWidthDigitInfo", model.right.component);
        checkEq("android.right.digit", "A12345", model.right.digit);
        checkNotNull("android.small", model.small);
        checkEq("android.small.icon", pic, model.small.icon);
        checkEq("android.expanded.size", 2, model.expanded.size());
        checkEq("android.baseInfo.component", "baseInfo", model.expanded.get(0).component);
        checkEq("android.baseInfo.type", 2, model.expanded.get(0).type);
        checkEq("android.progress", 65, model.expanded.get(1).progress);
        checkEq("android.progress.color", 0xFF69DAB7, model.expanded.get(1).progressColor);
        checkEq("android.progress.end.color", 0xFF91BFFF, model.expanded.get(1).progressEndColor);
        checkEq("android.actions.size", 1, model.actions.size());
        checkEq("android.action.title", "Call", model.actions.get(0).title);
        checkNotNull("android.action.pendingIntent", model.actions.get(0).pendingIntent);
        checkEq("android.action.intentType", 0, model.actions.get(0).intentType);
        check("android.not.fallback", !model.fallback);
        check("android.not.promoted", !model.promoted);
    }

    private static void testAndroidMalformed(Context context) {
        // Malformed JSON must not throw and must not drop the notification.
        String[] broken = {
                "{\"param_v2\":",
                "[]",
                "\"text\"",
                "{\"param_v2\":[]}",
                "{\"param_v2\":{\"param_island\":\"nope\"}}",
                "{\"param_v2\":{\"baseInfo\":42}}",
                "{\"param_v2\":{\"timeout\":\"soon\"}}",
                "{\"param_v2\":{\"param_island\":{\"islandTimeout\":-1}}}",
                "{\"param_v2\":{\"actions\":{\"action\":\"x\"}}}",
                "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":[]}}}}",
        };
        for (int i = 0; i < broken.length; i++) {
            Notification notification = baseNotification(context);
            notification.extras.putString(IslandProtocol.EXTRA_ISLAND, broken[i]);
            IslandModel model = IslandProtocol.parse(context, sbn(context, 10 + i, notification));
            checkNotNull("android.malformed[" + i + "]", model);
            if (model != null) {
                check("android.malformed[" + i + "].fallback", model.fallback);
                checkNotNull("android.malformed[" + i + "].left", model.left);
                checkEq("android.malformed[" + i + "].title", "self test title",
                        model.left.title);
            }
            // The payload is never rewritten.
            checkEq("android.malformed[" + i + "].payload.intact", broken[i],
                    notification.extras.getString(IslandProtocol.EXTRA_ISLAND));
        }

        // No island payload and no promoted flag: not island eligible.
        checkNull("android.plain.null",
                IslandProtocol.parse(context, sbn(context, 30, baseNotification(context))));

        // Promoted ongoing without island payload still produces a model.
        Notification promoted = baseNotification(context);
        promoted.flags |= Notification.FLAG_PROMOTED_ONGOING;
        promoted.extras.putInt(Notification.EXTRA_PROGRESS, 30);
        promoted.extras.putInt(Notification.EXTRA_PROGRESS_MAX, 60);
        promoted.extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "sub");
        IslandModel promotedModel = IslandProtocol.parse(context, sbn(context, 31, promoted));
        checkNotNull("android.promoted.model", promotedModel);
        if (promotedModel != null) {
            check("android.promoted.flag", promotedModel.promoted);
            check("android.promoted.fallback", promotedModel.fallback);
            checkEq("android.promoted.title", "self test title", promotedModel.left.title);
            checkNotNull("android.promoted.progress", promotedModel.right);
            checkEq("android.promoted.progress.value", 50, promotedModel.right.progress);
            checkEq("android.promoted.lifetime", 0L, promotedModel.islandLifetimeMillis);
        }

        // A payload with an unsupported template degrades instead of vanishing.
        Notification unsupported = baseNotification(context);
        unsupported.extras.putString(IslandProtocol.EXTRA_ISLAND,
                "{\"param_v2\":{\"protocol\":1,\"someFutureTemplate\":{\"a\":1}}}");
        IslandModel unsupportedModel = IslandProtocol.parse(context, sbn(context, 32, unsupported));
        checkNotNull("android.unsupported.model", unsupportedModel);
        if (unsupportedModel != null) {
            check("android.unsupported.fallback", unsupportedModel.fallback);
        }
    }

    private static void testAndroidBounds(Context context) {
        // Oversized payload: refused by the JSON layer, degraded to the plain projection.
        StringBuilder big = new StringBuilder("{\"param_v2\":{\"business\":\"");
        while (big.length() < IslandJson.MAX_INPUT_CHARS + 64) {
            big.append('x');
        }
        big.append("\"}}");
        Notification notification = baseNotification(context);
        notification.extras.putString(IslandProtocol.EXTRA_ISLAND, big.toString());
        IslandModel model = IslandProtocol.parse(context, sbn(context, 40, notification));
        checkNotNull("android.oversized.model", model);
        if (model != null) {
            check("android.oversized.fallback", model.fallback);
        }

        // Deeply nested payload: refused, no stack overflow.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        for (int i = 0; i < 500; i++) {
            deep.append('}');
        }
        Notification deepNotification = baseNotification(context);
        deepNotification.extras.putString(IslandProtocol.EXTRA_ISLAND, deep.toString());
        IslandModel deepModel = IslandProtocol.parse(context, sbn(context, 41, deepNotification));
        checkNotNull("android.deep.model", deepModel);
        if (deepModel != null) {
            check("android.deep.fallback", deepModel.fallback);
        }

        // Too many parts/actions are capped.
        StringBuilder many = new StringBuilder("{\"param_v2\":{\"actions\":[");
        for (int i = 0; i < 40; i++) {
            many.append(i == 0 ? "" : ",").append("{\"actionTitle\":\"a").append(i).append("\"}");
        }
        many.append("],\"textButton\":[");
        for (int i = 0; i < 40; i++) {
            many.append(i == 0 ? "" : ",").append("{\"actionTitle\":\"t").append(i).append("\"}");
        }
        many.append("]}}");
        Notification manyNotification = baseNotification(context);
        manyNotification.extras.putString(IslandProtocol.EXTRA_ISLAND, many.toString());
        IslandModel manyModel = IslandProtocol.parse(context, sbn(context, 42, manyNotification));
        checkNotNull("android.capped.model", manyModel);
        if (manyModel != null) {
            checkEq("android.capped.actions", IslandProtocol.MAX_ACTIONS, manyModel.actions.size());
            check("android.capped.parts", manyModel.expanded.size() <= IslandProtocol.MAX_PARTS);
        }
    }

    private static void testAndroidIcons(Context context) {
        Icon resource = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        checkEq("android.icon.resource.kept", resource, IslandProtocol.sanitizeIcon(resource));
        Icon uri = Icon.createWithContentUri("content://com.example.foreign/icon.png");
        checkNull("android.icon.uri.dropped", IslandProtocol.sanitizeIcon(uri));
        Icon data = Icon.createWithData(new byte[] {1, 2, 3}, 0, 1);
        checkNull("android.icon.data.dropped", IslandProtocol.sanitizeIcon(data));
        checkNull("android.icon.null", IslandProtocol.sanitizeIcon(null));

        // A payload that references a URI icon must not expose it.
        Notification notification = baseNotification(context);
        Bundle pics = new Bundle();
        pics.putParcelable("miui.focus.pic_bad", uri);
        notification.extras.putBundle(IslandProtocol.EXTRA_PICS, pics);
        notification.extras.putString(IslandProtocol.EXTRA_ISLAND,
                "{\"param_v2\":{\"param_island\":{\"smallIslandArea\":"
                        + "{\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_bad\"}}}}}");
        IslandModel model = IslandProtocol.parse(context, sbn(context, 50, notification));
        checkNotNull("android.icon.uri.model", model);
        if (model != null) {
            check("android.icon.uri.not.fallback", !model.fallback);
            checkNotNull("android.icon.uri.small", model.small);
            if (model.small != null) {
                // The URI icon is never exposed; the safe fallback (app or notification icon) may
                // take its place, but it must always be a renderable resource icon.
                check("android.icon.uri.not.exposed", model.small.icon != uri);
                if (model.small.icon != null) {
                    checkEq("android.icon.uri.fallback.resource", Icon.TYPE_RESOURCE,
                            model.small.icon.getType());
                }
            }
        }

        // Resource icons load for the notification's user.
        checkNotNull("android.icon.load",
                IslandProtocol.loadIcon(context, resource, context.getUserId()));
        checkNull("android.icon.load.uri", IslandProtocol.loadIcon(context, uri,
                context.getUserId()));
    }

    private static void testAndroidActions(Context context) {
        String sourcePackage = context.getPackageName();
        int sourceUid = android.os.Process.myUid();
        int userId = context.getUserId();
        ComponentName foreign = new ComponentName("com.other.app", "com.other.app.Evil");

        // URI cases that need no resolvable component: they are refused before any lookup.
        checkNull("android.action.garbage.uri", IslandActions.resolveIntent(context, "not an intent",
                IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
        checkNull("android.action.empty.uri", IslandActions.resolveIntent(context, "",
                IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
        checkNull("android.action.null.uri", IslandActions.resolveIntent(context, null,
                IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
        checkNull("android.action.null.package", IslandActions.resolveIntent(context,
                "intent:#Intent;component=" + sourcePackage + "/.Anything;end",
                IslandModel.Action.TYPE_ACTIVITY, null));
        StringBuilder oversized = new StringBuilder("intent:#Intent;component=");
        while (oversized.length() < IslandActions.MAX_URI_CHARS + 32) {
            oversized.append('a');
        }
        checkNull("android.action.oversized.uri", IslandActions.resolveIntent(context,
                oversized.toString(), IslandModel.Action.TYPE_ACTIVITY, sourcePackage));

        // Everything below alters exactly one unsafe aspect of one real, resolvable baseline: the
        // harness' own exported, permission free launcher activity. A rejection therefore cannot be
        // explained by an unresolvable component.
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(sourcePackage);
        ResolveInfo launchInfo = launch == null ? null
                : context.getPackageManager().resolveActivity(launch, 0);
        boolean launchable = launch != null && launch.getComponent() != null && launchInfo != null
                && launchInfo.activityInfo != null && launchInfo.activityInfo.exported
                && launchInfo.activityInfo.enabled && launchInfo.activityInfo.permission == null;
        if (!launchable) {
            skip("harness launcher activity is not an exported permission-free component:"
                    + " URI baseline cases");
        } else {
            ComponentName target = launch.getComponent();
            String baselineUri = "intent:#Intent;component=" + target.flattenToShortString()
                    + ";end";

            // Baseline: accepted, made explicit for this package and given NEW_TASK.
            Intent baseline = IslandActions.resolveIntent(context, baselineUri,
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage);
            checkNotNull("android.action.baseline.resolves", baseline);
            if (baseline != null) {
                checkEq("android.action.baseline.package", sourcePackage, baseline.getPackage());
                check("android.action.baseline.newtask",
                        (baseline.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            }

            // Same baseline component, but a foreign package.
            checkNull("android.action.foreign.uri", IslandActions.resolveIntent(context,
                    "intent:#Intent;component=com.other.app/.Evil;end",
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
            // Same baseline, but implicit instead of an explicit component.
            checkNull("android.action.implicit.uri", IslandActions.resolveIntent(context,
                    "intent:#Intent;action=android.intent.action.VIEW;end",
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
            // Same baseline, plus a selector that points at a foreign component.
            Intent withSelector = new Intent().setComponent(target);
            withSelector.setSelector(new Intent().setComponent(foreign));
            String selectorUri = withSelector.toUri(Intent.URI_INTENT_SCHEME);
            check("android.action.selector.fixture", selectorUri.contains("SEL"));
            checkNull("android.action.selector.uri", IslandActions.resolveIntent(context, selectorUri,
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
            // Same baseline, plus a URI grant flag. The serialized URI is asserted to carry the flag
            // (that is what the sender sent), but the platform may drop it while parsing, so the
            // assertion is on the final safety invariant: no returned target may carry a URI grant.
            Intent withGrant = new Intent().setComponent(target)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String grantUri = withGrant.toUri(Intent.URI_INTENT_SCHEME);
            check("android.action.grant.fixture", grantUri.contains("launchFlags="));
            Intent grantResult = IslandActions.resolveIntent(context, grantUri,
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage);
            check("android.action.grant.result.no.grant", grantResult == null
                    || (grantResult.getFlags() & URI_GRANT_FLAGS) == 0);
            if (grantResult != null) {
                checkEq("android.action.grant.result.component", target,
                        grantResult.getComponent());
            }
            // Same baseline, plus content:// or file:// data. The fixture is proven by round tripping
            // the serialized URI: however the platform spells it, the URI handed to resolveIntent
            // must really carry the forbidden scheme.
            Intent withContent = new Intent().setComponent(target)
                    .setData(Uri.parse("content://com.example.foreign/x"));
            String contentUri = withContent.toUri(Intent.URI_INTENT_SCHEME);
            check("android.action.content.fixture", hasDataScheme(contentUri, "content"));
            checkNull("android.action.content.data", IslandActions.resolveIntent(context, contentUri,
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
            Intent withFile = new Intent().setComponent(target).setData(Uri.parse("file:///data/x"));
            String fileUri = withFile.toUri(Intent.URI_INTENT_SCHEME);
            check("android.action.file.fixture", hasDataScheme(fileUri, "file"));
            checkNull("android.action.file.data", IslandActions.resolveIntent(context, fileUri,
                    IslandModel.Action.TYPE_ACTIVITY, sourcePackage));
            // Same accepted baseline URI, but a broadcast, service or unknown target type.
            checkNull("android.action.broadcast.uri", IslandActions.resolveIntent(context,
                    baselineUri, IslandModel.Action.TYPE_BROADCAST, sourcePackage));
            checkNull("android.action.service.uri", IslandActions.resolveIntent(context,
                    baselineUri, IslandModel.Action.TYPE_SERVICE, sourcePackage));
            checkNull("android.action.unknown.type.uri", IslandActions.resolveIntent(context,
                    baselineUri, 0, sourcePackage));
        }

        // PendingIntent creator identity is enforced (uid, package and user must all match) and the
        // enforcement is observed through real delivery, not only through the return value.
        final AtomicInteger received = new AtomicInteger();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receivingContext, Intent intent) {
                received.incrementAndGet();
            }
        };
        String action = "com.android.systemui.island.selftest.PING";
        // Register on a dedicated looper so delivery does not depend on the caller's thread or on the
        // main looper being idle.
        HandlerThread receiverThread = new HandlerThread("island-selftest-receiver");
        receiverThread.start();
        context.registerReceiver(receiver, new IntentFilter(action), null,
                new Handler(receiverThread.getLooper()), Context.RECEIVER_NOT_EXPORTED);
        try {
            PendingIntent ownPendingIntent = PendingIntent.getBroadcast(context, 1,
                    new Intent(action)
                            .setPackage(context.getPackageName())
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    PendingIntent.FLAG_IMMUTABLE);
            check("android.pendingintent.trusted", IslandActions.isTrustedCreator(
                    context.getPackageName(), sourceUid, sourcePackage, sourceUid));
            check("android.pendingintent.foreign.uid", !IslandActions.isTrustedCreator(
                    context.getPackageName(), sourceUid, "com.other.app", sourceUid + 1));

            check("android.pendingintent.send.trusted", IslandActions.sendPendingIntent(context,
                    ownPendingIntent, sourcePackage, sourceUid, userId));
            waitForAtLeast(received, 1);
            checkEq("android.pendingintent.received", 1, received.get());

            int before = received.get();
            check("android.pendingintent.foreign.not.accepted",
                    !IslandActions.sendPendingIntent(context, ownPendingIntent, "com.other.app",
                            sourceUid + 1, userId));
            check("android.pendingintent.other.user.not.accepted",
                    !IslandActions.sendPendingIntent(context, ownPendingIntent, sourcePackage,
                            sourceUid, userId + 1));
            check("android.pendingintent.no.user.not.accepted",
                    !IslandActions.sendPendingIntent(context, ownPendingIntent, sourcePackage,
                            sourceUid, UserHandle.USER_NULL));
            check("android.pendingintent.shared.uid.not.accepted",
                    !IslandActions.sendPendingIntent(context, ownPendingIntent, "com.other.app",
                            sourceUid, userId));
            check("android.pendingintent.null.not.accepted",
                    !IslandActions.sendPendingIntent(context, null, sourcePackage, sourceUid,
                            userId));
            // Nothing may have been delivered by the rejected attempts.
            check("android.pendingintent.rejected.no.delivery", !settleAbove(received, before));
            checkEq("android.pendingintent.count.unchanged", before, received.get());

            // A matching send still delivers after the rejected attempts.
            check("android.pendingintent.send.trusted.again", IslandActions.sendPendingIntent(context,
                    ownPendingIntent, sourcePackage, sourceUid, userId));
            waitForAtLeast(received, before + 1);
            checkEq("android.pendingintent.received.again", before + 1, received.get());
        } finally {
            context.unregisterReceiver(receiver);
            receiverThread.quitSafely();
        }

        // A model whose only target is an unsafe URI reports not dispatchable.
        IslandModel.Action unsafe = new IslandModel.Action("k", "t", null, null,
                IslandActions.resolveIntent(context, "intent:#Intent;component=com.other/.E;end",
                        IslandModel.Action.TYPE_ACTIVITY, sourcePackage), 0, false);
        check("android.action.unsafe.not.dispatchable", !unsafe.isDispatchable());
        check("android.action.unsafe.send.false", !IslandActions.send(context, unsafe,
                sourcePackage, sourceUid, userId));
        check("android.action.null.send.false", !IslandActions.send(context, null, sourcePackage,
                sourceUid, userId));
        check("android.action.content.null.false", !IslandActions.sendContent(context, null));
    }

    /**
     * Small island fallback hierarchy, per the documented order: the sender's own small icon, then
     * the big island A area (left) icon, then the app icon. A missing small area still yields a
     * renderable part, and text/progress supplied for the small area is preserved.
     */
    private static void testAndroidSmallFallback(Context context) {
        String sourcePackage = context.getPackageName();
        Icon custom = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        Icon leftIcon = Icon.createWithResource("android", android.R.drawable.ic_menu_info_details);

        // The sender's own small icon wins over the left icon.
        IslandModel customModel = parseSmall(context, 80, custom, leftIcon, "pic_left",
                "{\"picInfo\":{\"type\":1,\"pic\":\"pic_custom\"}}");
        checkNotNull("android.small.custom.model", customModel);
        if (customModel != null) {
            checkNotNull("android.small.custom", customModel.small);
            if (customModel.small != null) {
                checkEq("android.small.custom.icon", custom, customModel.small.icon);
            }
        }

        // A small picInfo without a pic falls back to the left icon.
        IslandModel leftModel = parseSmall(context, 81, custom, leftIcon, "pic_left",
                "{\"picInfo\":{\"type\":1}}");
        checkNotNull("android.small.left.model", leftModel);
        if (leftModel != null) {
            checkNotNull("android.small.left", leftModel.small);
            if (leftModel.small != null) {
                checkEq("android.small.left.icon", leftIcon, leftModel.small.icon);
            }
        }

        // An unknown pics key falls back the same way instead of producing an empty part.
        IslandModel unknownModel = parseSmall(context, 82, custom, leftIcon, "pic_left",
                "{\"picInfo\":{\"type\":1,\"pic\":\"pic_missing\"}}");
        checkNotNull("android.small.unknown.model", unknownModel);
        if (unknownModel != null && unknownModel.small != null) {
            checkEq("android.small.unknown.icon", leftIcon, unknownModel.small.icon);
        }

        // A missing small area still yields a part carrying the left icon.
        IslandModel absentModel = parseSmall(context, 83, custom, leftIcon, "pic_left", null);
        checkNotNull("android.small.absent.model", absentModel);
        if (absentModel != null) {
            checkNotNull("android.small.absent", absentModel.small);
            if (absentModel.small != null) {
                checkEq("android.small.absent.icon", leftIcon, absentModel.small.icon);
                checkEq("android.small.absent.component", "smallIslandArea",
                        absentModel.small.component);
            }
        }

        // With no left icon either, the app icon is used; it must be the launcher icon when the
        // package exposes one.
        IslandModel appModel = parseSmall(context, 84, custom, null, null, null);
        checkNotNull("android.small.app.model", appModel);
        if (appModel != null) {
            checkNotNull("android.small.app", appModel.small);
            if (appModel.small != null) {
                checkEq("android.small.app.icon", appModel.appIcon, appModel.small.icon);
                checkNotNull("android.small.app.icon.present", appModel.small.icon);
                if (appModel.small.icon != null) {
                    checkEq("android.small.app.icon.type", Icon.TYPE_RESOURCE,
                            appModel.small.icon.getType());
                }
            }
            if (context.getApplicationInfo().icon != 0) {
                checkEq("android.app.icon.prefers.launcher", sourcePackage,
                        appModel.appIcon == null ? null : appModel.appIcon.getResPackage());
            } else {
                skip("harness package declares no launcher icon: launcher preference case");
            }
        }

        // Small area text and progress survive the icon normalization.
        IslandModel textModel = parseSmall(context, 85, custom, leftIcon, "pic_left",
                "{\"imageTextInfoRight\":{\"type\":6,\"picInfo\":{\"type\":4,"
                        + "\"pic\":\"pic_custom\"},\"textInfo\":{\"title\":\"34\"}}}");
        checkNotNull("android.small.text.model", textModel);
        if (textModel != null && textModel.small != null) {
            checkEq("android.small.text.icon", custom, textModel.small.icon);
            checkEq("android.small.text.title", "34", textModel.small.title);
        }

        IslandModel progressModel = parseSmall(context, 86, custom, leftIcon, "pic_left",
                "{\"combinePicInfo\":{\"picInfo\":{\"type\":1,\"pic\":\"pic_custom\"},"
                        + "\"progressInfo\":{\"progress\":60,\"colorReach\":\"#00FF00\"}}}");
        checkNotNull("android.small.progress.model", progressModel);
        if (progressModel != null && progressModel.small != null) {
            checkEq("android.small.progress.icon", custom, progressModel.small.icon);
            checkEq("android.small.progress.value", 60, progressModel.small.progress);
            checkEq("android.small.progress.color", 0xFF00FF00,
                    progressModel.small.progressColor);
        }

        // A fallback icon never replaces a URI icon with another unsafe icon.
        Icon uri = Icon.createWithContentUri("content://com.example.foreign/icon.png");
        IslandModel uriModel = parseSmall(context, 87, uri, leftIcon, "pic_left",
                "{\"picInfo\":{\"type\":1,\"pic\":\"pic_custom\"}}");
        checkNotNull("android.small.uri.model", uriModel);
        if (uriModel != null && uriModel.small != null) {
            check("android.small.uri.not.exposed", uriModel.small.icon != uri);
            if (uriModel.small.icon != null) {
                checkEq("android.small.uri.fallback.resource", Icon.TYPE_RESOURCE,
                        uriModel.small.icon.getType());
            }
        }

        // The same secondary fallback must exist on the plain projection, otherwise a promoted or
        // degraded second activity renders as nothing.
        Notification promoted = baseNotification(context);
        promoted.flags |= Notification.FLAG_PROMOTED_ONGOING;
        StatusBarNotification promotedSbn = sbn(context, 88, promoted);
        IslandModel promotedModel = IslandProtocol.parse(context, promotedSbn);
        checkNotNull("android.small.promoted.model", promotedModel);
        if (promotedModel != null) {
            check("android.small.promoted.flag", promotedModel.promoted);
            check("android.small.promoted.fallback", promotedModel.fallback);
            checkEq("android.small.promoted.key", promotedSbn.getKey(), promotedModel.key);
            checkNotNull("android.small.promoted.small", promotedModel.small);
            if (promotedModel.small != null) {
                checkEq("android.small.promoted.component", "smallIslandArea",
                        promotedModel.small.component);
                checkNotNull("android.small.promoted.icon", promotedModel.small.icon);
                checkEq("android.small.promoted.icon.from.left",
                        promotedModel.left == null ? null : promotedModel.left.icon,
                        promotedModel.small.icon);
                if (promotedModel.small.icon != null) {
                    checkEq("android.small.promoted.icon.type", Icon.TYPE_RESOURCE,
                            promotedModel.small.icon.getType());
                }
            }
        }

        // And on the degraded path used for an unusable island payload.
        Notification degraded = baseNotification(context);
        degraded.extras.putString(IslandProtocol.EXTRA_ISLAND, "{\"param_v2\":");
        IslandModel degradedModel = IslandProtocol.parse(context, sbn(context, 89, degraded));
        checkNotNull("android.small.degraded.model", degradedModel);
        if (degradedModel != null) {
            check("android.small.degraded.fallback", degradedModel.fallback);
            checkNotNull("android.small.degraded.small", degradedModel.small);
            if (degradedModel.small != null) {
                checkNotNull("android.small.degraded.icon", degradedModel.small.icon);
            }
        }
    }

    /**
     * Builds one notification with a custom pics bundle and parses it. {@code leftPicKey} may be
     * null to omit the big island A area icon, and {@code smallAreaJson} may be null to omit the
     * small island area entirely.
     */
    private static IslandModel parseSmall(Context context, int id, Icon custom, Icon left,
            String leftPicKey, String smallAreaJson) {
        Bundle pics = new Bundle();
        if (custom != null) {
            pics.putParcelable("pic_custom", custom);
        }
        if (left != null) {
            pics.putParcelable("pic_left", left);
        }
        StringBuilder payload = new StringBuilder("{\"param_v2\":{\"param_island\":{");
        payload.append("\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,");
        if (leftPicKey != null) {
            payload.append("\"picInfo\":{\"type\":1,\"pic\":\"").append(leftPicKey).append("\"},");
        }
        payload.append("\"textInfo\":{\"title\":\"A\"}}},");
        if (smallAreaJson != null) {
            payload.append("\"smallIslandArea\":").append(smallAreaJson).append(',');
        }
        payload.append("\"baseInfo\":{\"type\":1,\"title\":\"kept\"}}}}");

        Notification notification = baseNotification(context);
        notification.extras.putBundle(IslandProtocol.EXTRA_PICS, pics);
        notification.extras.putString(IslandProtocol.EXTRA_ISLAND, payload.toString());
        return IslandProtocol.parse(context, sbn(context, id, notification));
    }

    private static void testAndroidReopen(Context context) {
        String payload = "{\"param_v2\":{\"protocol\":1,\"updatable\":%s,\"reopen\":\"%s\","
                + "\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":"
                + "{\"type\":1,\"textInfo\":{\"title\":\"t\"}}}}}}";

        IslandStore store = new IslandStore(new FakeClock());
        IslandModel persistent = IslandProtocol.parse(context, sbn(context, 60,
                notificationWith(context, String.format(payload, "true", "close"))));
        checkNotNull("android.reopen.persistent", persistent);
        store.put(persistent);
        store.remove(persistent.key);
        IslandModel repost = IslandProtocol.parse(context, sbn(context, 60,
                notificationWith(context, String.format(payload, "true", "close"))));
        checkNotNull("android.reopen.repost", repost);
        check("android.reopen.blocked", !store.put(repost));
        checkNull("android.reopen.hidden", store.get(repost.key));

        IslandModel plain = IslandProtocol.parse(context, sbn(context, 61,
                notificationWith(context, String.format(payload, "false", "close"))));
        checkNotNull("android.reopen.plain", plain);
        store.put(plain);
        store.remove(plain.key);
        IslandModel plainRepost = IslandProtocol.parse(context, sbn(context, 61,
                notificationWith(context, String.format(payload, "false", "close"))));
        check("android.reopen.plain.reappears", store.put(plainRepost));
        checkNotNull("android.reopen.plain.visible", store.get(plainRepost.key));

        // Cancel-then-repost of the same notification id never duplicates.
        IslandModel first = IslandProtocol.parse(context, sbn(context, 62,
                notificationWith(context, String.format(payload, "false", "close"))));
        IslandModel second = IslandProtocol.parse(context, sbn(context, 62,
                notificationWith(context, String.format(payload, "false", "close"))));
        checkEq("android.same.id.key", first.key, second.key);
        store.put(first);
        int size = store.size();
        store.put(second);
        checkEq("android.same.id.no.duplicate", size, store.size());
    }

    /**
     * The legacy flat protocol 1 navigation card: no {@code param_v2} wrapper, {@code protocol == 1}
     * and {@code scene == "templateRevertProgressScene"}. It must become a real island (not the plain
     * projection) whose label, detail and direction icon come from the payload and its
     * {@code miui.focus.pics} bundle, it must never be permanently suppressed by its own notification
     * removal, and every other flat payload must keep the ordinary notification projection.
     */
    private static void testAndroidLegacyNavigation(Context context) {
        // Mirrors the fields amap actually puts on the root object.
        String payload = "{\"protocol\":1,\"scene\":\"templateRevertProgressScene\","
                + "\"aodTitle\":\"guide 500m | navigating\","
                + "\"aodPic\":\"miui.focus.pic_forward_v2\",\"ticker\":\"guide 500m\","
                + "\"tickerPic\":\"miui.focus.pic_forward_v2\","
                + "\"tickerPicDark\":\"miui.focus.tickerPicDark\",\"reopen\":\"reopen\","
                + "\"title\":\"guide 500m\",\"content\":\"turn right | navigating\","
                + "\"colorTitle\":\"#FFFFFF\",\"colorContent\":\"#FFFFFF\",\"colorBg\":\"#000000\","
                + "\"showSmallIcon\":false,\"enableFloat\":true,\"padding\":true,"
                + "\"updatable\":true}";

        Icon arrow = Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        Icon arrowDark = Icon.createWithResource("android", android.R.drawable.ic_menu_info_details);

        Notification notification = navigationNotification(context, payload, arrow, arrowDark);
        IslandModel model = IslandProtocol.parse(context, sbn(context, 90, notification));
        checkNotNull("android.legacy.model", model);
        if (model != null) {
            check("android.legacy.not.fallback", !model.fallback);
            check("android.legacy.not.promoted", !model.promoted);
            checkEq("android.legacy.protocol", 1, model.protocol);
            checkEq("android.legacy.ticker", "guide 500m", model.ticker);
            checkEq("android.legacy.aodTitle", "guide 500m | navigating", model.aodTitle);
            check("android.legacy.updatable", model.updatable);
            check("android.legacy.updateFloat", model.updateFloat);
            checkEq("android.legacy.reopen", IslandModel.REOPEN, model.reopen);
            checkEq("android.legacy.lifetime", 0L, model.lifetimeMillis);
            checkEq("android.legacy.islandLifetime", 0L, model.islandLifetimeMillis);
            checkNotNull("android.legacy.left", model.left);
            if (model.left != null) {
                checkEq("android.legacy.left.component", "templateRevertProgressScene",
                        model.left.component);
                checkEq("android.legacy.left.title", "guide 500m", model.left.title);
                checkEq("android.legacy.left.content", "turn right | navigating",
                        model.left.content);
                checkEq("android.legacy.left.icon", arrow, model.left.icon);
                checkEq("android.legacy.left.darkIcon", arrowDark, model.left.darkIcon);
                checkEq("android.legacy.left.color", 0xFFFFFFFF, model.left.color);
            }
            checkNotNull("android.legacy.small", model.small);
            if (model.small != null) {
                checkEq("android.legacy.small.icon", arrow, model.small.icon);
            }
            check("android.legacy.actions.empty", model.actions.isEmpty());
            check("android.legacy.expanded.empty", model.expanded.isEmpty());
        }

        // The ongoing navigation card must survive its own notification removal: a re-post of the
        // same notification id has to come back instead of being tombstoned.
        if (model != null) {
            IslandStore store = new IslandStore(new FakeClock());
            check("android.legacy.store.put", store.put(model));
            check("android.legacy.store.remove", store.remove(model.key));
            IslandModel repost = IslandProtocol.parse(context, sbn(context, 90, notification));
            checkNotNull("android.legacy.repost", repost);
            check("android.legacy.repost.shows", repost != null && store.put(repost));
        }

        // Missing pics: the card still renders with its text, only the arrow is absent.
        Notification noPics = navigationNotification(context, payload, null, null);
        IslandModel noPicsModel = IslandProtocol.parse(context, sbn(context, 91, noPics));
        checkNotNull("android.legacy.missing.pics.model", noPicsModel);
        if (noPicsModel != null) {
            check("android.legacy.missing.pics.not.fallback", !noPicsModel.fallback);
            checkNotNull("android.legacy.missing.pics.left", noPicsModel.left);
            if (noPicsModel.left != null) {
                checkEq("android.legacy.missing.pics.title", "guide 500m", noPicsModel.left.title);
                checkNull("android.legacy.missing.pics.icon", noPicsModel.left.icon);
            }
            checkNotNull("android.legacy.missing.pics.small", noPicsModel.small);
            if (noPicsModel.small != null && noPicsModel.small.icon != null) {
                checkEq("android.legacy.missing.pics.small.resource", Icon.TYPE_RESOURCE,
                        noPicsModel.small.icon.getType());
            }
        }

        // A hostile icon under the direction key is dropped exactly like a param_v2 icon.
        Notification uriPics = navigationNotification(context, payload, null, null);
        Bundle hostile = new Bundle();
        hostile.putParcelable("miui.focus.pic_forward_v2",
                Icon.createWithContentUri("content://com.example.foreign/icon.png"));
        uriPics.extras.putBundle(IslandProtocol.EXTRA_PICS, hostile);
        IslandModel uriModel = IslandProtocol.parse(context, sbn(context, 92, uriPics));
        checkNotNull("android.legacy.uri.model", uriModel);
        if (uriModel != null) {
            check("android.legacy.uri.not.fallback", !uriModel.fallback);
            if (uriModel.left != null) {
                checkNull("android.legacy.uri.icon.dropped", uriModel.left.icon);
                checkEq("android.legacy.uri.title", "guide 500m", uriModel.left.title);
            }
        }

        // A wrong protocol, a wrong scene, a missing scene, a missing protocol or a card without text
        // must never be promoted to a navigation island.
        String[] notNavigation = {
                "{\"protocol\":2,\"scene\":\"templateRevertProgressScene\",\"title\":\"guide\","
                        + "\"content\":\"nav\"}",
                "{\"protocol\":1,\"scene\":\"someOtherScene\",\"title\":\"guide\","
                        + "\"content\":\"nav\"}",
                "{\"protocol\":1,\"title\":\"guide\",\"content\":\"nav\"}",
                "{\"title\":\"guide\",\"content\":\"nav\"}",
                "{\"protocol\":1,\"scene\":\"templateRevertProgressScene\"}",
        };
        for (int i = 0; i < notNavigation.length; i++) {
            Notification other = notificationWith(context, notNavigation[i]);
            IslandModel otherModel = IslandProtocol.parse(context, sbn(context, 93 + i, other));
            checkNotNull("android.legacy.other[" + i + "].model", otherModel);
            if (otherModel != null) {
                check("android.legacy.other[" + i + "].fallback", otherModel.fallback);
                checkEq("android.legacy.other[" + i + "].protocol", 0, otherModel.protocol);
                checkNotNull("android.legacy.other[" + i + "].left", otherModel.left);
                if (otherModel.left != null) {
                    checkEq("android.legacy.other[" + i + "].plain.title", "self test title",
                            otherModel.left.title);
                }
            }
        }

        // param_v2 keeps priority: a payload carrying both forms is parsed as param_v2.
        Notification both = notificationWith(context,
                "{\"protocol\":1,\"scene\":\"templateRevertProgressScene\",\"title\":\"legacy\","
                        + "\"param_v2\":{\"protocol\":1,\"business\":\"taxi\",\"param_island\":"
                        + "{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,"
                        + "\"textInfo\":{\"title\":\"v2 title\"}}}}}}");
        IslandModel bothModel = IslandProtocol.parse(context, sbn(context, 98, both));
        checkNotNull("android.legacy.priority.model", bothModel);
        if (bothModel != null) {
            check("android.legacy.priority.not.fallback", !bothModel.fallback);
            checkEq("android.legacy.priority.business", "taxi", bothModel.business);
            checkNotNull("android.legacy.priority.left", bothModel.left);
            if (bothModel.left != null) {
                checkEq("android.legacy.priority.left.component", "imageTextInfoLeft",
                        bothModel.left.component);
                checkEq("android.legacy.priority.left.title", "v2 title", bothModel.left.title);
            }
        }
    }

    /**
     * One legacy flat navigation notification: the payload plus, when supplied, the sender's
     * {@code miui.focus.pics} bundle with the direction icon under the keys the template names.
     */
    private static Notification navigationNotification(Context context, String payload, Icon arrow,
            Icon arrowDark) {
        Notification notification = notificationWith(context, payload);
        if (arrow != null || arrowDark != null) {
            Bundle pics = new Bundle();
            if (arrow != null) {
                pics.putParcelable("miui.focus.pic_forward_v2", arrow);
            }
            if (arrowDark != null) {
                pics.putParcelable("miui.focus.tickerPicDark", arrowDark);
            }
            notification.extras.putBundle(IslandProtocol.EXTRA_PICS, pics);
        }
        return notification;
    }

    /**
     * Untrusted extras must never escape {@link IslandProtocol#parse}. Both cases below raise a real
     * platform exception on a path the parser actually consumes: a wrong-typed value under the
     * payload key ({@code ClassCastException}) and a pics entry whose Parcelable class cannot be
     * resolved ({@code BadParcelableException}).
     */
    private static void testAndroidHostileExtras(Context context) {
        // Wrong type under the consumed payload key: getString() raises ClassCastException. No
        // island payload survives, the notification is not promoted, so the caller keeps rendering
        // the ordinary notification - and nothing about the notification is mutated.
        Notification typed = baseNotification(context);
        byte[] payloadBytes = {1, 2, 3};
        typed.extras.putByteArray(IslandProtocol.EXTRA_ISLAND, payloadBytes);
        Notification.Action[] actionsBefore = typed.actions;
        PendingIntent contentBefore = typed.contentIntent;
        int flagsBefore = typed.flags;
        IslandModel typedModel = null;
        try {
            typedModel = IslandProtocol.parse(context, sbn(context, 70, typed));
        } catch (RuntimeException e) {
            fail("android.hostile.typed threw " + e);
        }
        checkNull("android.hostile.typed.model", typedModel);
        check("android.hostile.typed.payload.intact", java.util.Arrays.equals(payloadBytes,
                typed.extras.getByteArray(IslandProtocol.EXTRA_ISLAND)));
        check("android.hostile.typed.actions.intact", actionsBefore == typed.actions);
        check("android.hostile.typed.content.intact", contentBefore == typed.contentIntent);
        checkEq("android.hostile.typed.flags.intact", flagsBefore, typed.flags);

        // A pics entry whose Parcelable class name cannot be resolved. Bundle values are stored
        // lazily, so this raises only when the value is unwrapped - which is exactly what the
        // parser's typed icon lookup does. The fixture is proven with the parser's own call.
        Bundle corruptPics = corruptPicsBundle();
        if (corruptPics == null) {
            skip("corrupt pics bundle could not be built");
        } else {
            boolean raised = false;
            String raisedType = "";
            try {
                corruptPics.getParcelable(CORRUPT_PIC_KEY, Icon.class);
            } catch (RuntimeException e) {
                raised = true;
                raisedType = e.getClass().getSimpleName();
            }
            check("android.hostile.pics.parcel.fixture.raises." + raisedType, raised);
            check("android.hostile.pics.parcel.fixture.badparcelable",
                    "BadParcelableException".equals(raisedType));

            // The same bundle on a real notification: the island still renders, the unusable icon is
            // simply absent, and nothing is thrown.
            Notification corrupt = baseNotification(context);
            corrupt.extras.putBundle(IslandProtocol.EXTRA_PICS, corruptPics);
            corrupt.extras.putString(IslandProtocol.EXTRA_ISLAND,
                    "{\"param_v2\":{\"param_island\":{\"smallIslandArea\":"
                            + "{\"picInfo\":{\"type\":1,\"pic\":\"" + CORRUPT_PIC_KEY + "\"}}},"
                            + "\"baseInfo\":{\"type\":1,\"title\":\"kept\"}}}");
            try {
                IslandModel model = IslandProtocol.parse(context, sbn(context, 71, corrupt));
                checkNotNull("android.hostile.pics.parcel.model", model);
                if (model != null) {
                    check("android.hostile.pics.parcel.not.fallback", !model.fallback);
                    checkNotNull("android.hostile.pics.parcel.small", model.small);
                    if (model.small != null) {
                        // The corrupt entry is never used; the safe fallback is acceptable, but it
                        // must stay a renderable resource icon (no URI, no corrupt object).
                        check("android.hostile.pics.parcel.safe.fallback",
                                model.small.icon == null
                                        || model.small.icon.getType() == Icon.TYPE_RESOURCE);
                    }
                    checkEq("android.hostile.pics.parcel.expanded.size", 1, model.expanded.size());
                    checkEq("android.hostile.pics.parcel.base.title", "kept",
                            model.expanded.get(0).title);
                }
            } catch (RuntimeException e) {
                fail("android.hostile.pics.parcel threw " + e);
            }

            // A promoted notification never reads the pics bundle, so its projection stays complete.
            Notification promoted = baseNotification(context);
            promoted.flags |= Notification.FLAG_PROMOTED_ONGOING;
            promoted.extras.putBundle(IslandProtocol.EXTRA_PICS, corruptPics);
            StatusBarNotification promotedSbn = sbn(context, 73, promoted);
            try {
                IslandModel model = IslandProtocol.parse(context, promotedSbn);
                checkNotNull("android.hostile.promoted.model", model);
                if (model != null) {
                    check("android.hostile.promoted.flag", model.promoted);
                    check("android.hostile.promoted.fallback", model.fallback);
                    checkEq("android.hostile.promoted.key", promotedSbn.getKey(), model.key);
                    checkEq("android.hostile.promoted.uid", 10_001, model.uid);
                    checkEq("android.hostile.promoted.user", context.getUserId(), model.userId);
                    checkNotNull("android.hostile.promoted.left", model.left);
                    if (model.left != null) {
                        checkEq("android.hostile.promoted.left.title", "self test title",
                                model.left.title);
                    }
                }
            } catch (RuntimeException e) {
                fail("android.hostile.promoted threw " + e);
            }
        }

        // A readable payload whose pics/actions bundles have the wrong type: the island still
        // renders, only the icon and the bundle backed action are gone.
        Notification wrongPics = baseNotification(context);
        wrongPics.extras.putString(IslandProtocol.EXTRA_ISLAND,
                "{\"param_v2\":{\"param_island\":{\"smallIslandArea\":"
                        + "{\"picInfo\":{\"type\":1,\"pic\":\"p\"}}},"
                        + "\"baseInfo\":{\"type\":1,\"title\":\"kept\"}}}");
        wrongPics.extras.putString(IslandProtocol.EXTRA_PICS, "not a bundle");
        wrongPics.extras.putString(IslandProtocol.EXTRA_ACTIONS, "not a bundle");
        try {
            IslandModel model = IslandProtocol.parse(context, sbn(context, 72, wrongPics));
            checkNotNull("android.hostile.pics.model", model);
            if (model != null) {
                check("android.hostile.pics.not.fallback", !model.fallback);
                checkNotNull("android.hostile.pics.small", model.small);
                if (model.small != null) {
                    check("android.hostile.pics.safe.fallback", model.small.icon == null
                            || model.small.icon.getType() == Icon.TYPE_RESOURCE);
                }
                checkEq("android.hostile.pics.expanded.size", 1, model.expanded.size());
                checkEq("android.hostile.pics.base.title", "kept", model.expanded.get(0).title);
                check("android.hostile.pics.actions.empty", model.actions.isEmpty());
            }
        } catch (RuntimeException e) {
            fail("android.hostile.pics threw " + e);
        }
    }

    /**
     * Builds a pics Bundle that is in parcelled state, holding a Parcelable whose class name cannot
     * be resolved. Bundle values are stored lazily, so the corruption only surfaces when the value is
     * unwrapped through a typed getter - the parser's own icon lookup. The result is a real Bundle
     * object, so it can be nested in an in-process extras Bundle.
     */
    private static Bundle corruptPicsBundle() {
        Bundle inner = new Bundle();
        inner.putParcelable(CORRUPT_PIC_KEY,
                Icon.createWithResource("android", android.R.drawable.ic_dialog_info));
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(inner);
            byte[] bytes = parcel.marshall();
            int index = indexOfUtf16(bytes, "android.graphics.drawable.Icon");
            if (index < 0) {
                return null;
            }
            // Corrupt the class name so the platform cannot resolve it.
            bytes[index] = 'x';
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return parcel.readBundle(IslandSelfTest.class.getClassLoader());
        } catch (RuntimeException e) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Round trips a serialized intent URI and reports whether it really carries data with
     * {@code scheme}. Used to prove a URI fixture instead of matching the platform's spelling.
     */
    private static boolean hasDataScheme(String uri, String scheme) {
        try {
            Intent roundTrip = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            Uri data = roundTrip == null ? null : roundTrip.getData();
            return data != null && scheme.equals(data.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    /** Finds the UTF-16 little endian encoding of {@code needle} inside {@code haystack}. */
    private static int indexOfUtf16(byte[] haystack, String needle) {
        for (int i = 0; i + needle.length() * 2 <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length(); j++) {
                if (haystack[i + j * 2] != (byte) needle.charAt(j)
                        || haystack[i + j * 2 + 1] != 0) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------------------------------

    private static Notification baseNotification(Context context) {
        Notification notification = new Notification.Builder(context, "island_selftest")
                .setContentTitle("self test title")
                .setContentText("self test text")
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_dialog_info))
                .build();
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "self test title");
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, "self test text");
        return notification;
    }

    private static Notification notificationWith(Context context, String payload) {
        Notification notification = baseNotification(context);
        notification.extras.putString(IslandProtocol.EXTRA_ISLAND, payload);
        return notification;
    }

    private static StatusBarNotification sbn(Context context, int id, Notification notification) {
        return new StatusBarNotification(context.getPackageName(), context.getPackageName(), id,
                null, 10_001, 0, 0, notification, UserHandle.of(context.getUserId()),
                System.currentTimeMillis());
    }

    private static IslandModel model(String key, long islandLifetimeMillis, long lifetimeMillis) {
        return new IslandModel.Builder()
                .setKey(key)
                .setPackageName("com.example.app")
                .setUid(10_777)
                .setUserId(0)
                .setIslandLifetimeMillis(islandLifetimeMillis)
                .setLifetimeMillis(lifetimeMillis)
                .setLeft(new IslandModel.Part.Builder()
                        .setComponent("baseInfo")
                        .setTitle("title")
                        .build())
                .build();
    }

    private static String nestedArrays(int depth) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            out.append('[');
        }
        for (int i = 0; i < depth; i++) {
            out.append(']');
        }
        return out.toString();
    }

    private static boolean immutable(List<IslandModel> list) {
        try {
            list.add(null);
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    /** Waits up to two seconds for {@code counter} to reach {@code expected}. */
    private static void waitForAtLeast(AtomicInteger counter, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 2_000L;
        while (counter.get() < expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L);
        }
    }

    /** Waits briefly and reports whether {@code counter} grew above {@code baseline}. */
    private static boolean settleAbove(AtomicInteger counter, int baseline) {
        long deadline = SystemClock.elapsedRealtime() + 400L;
        while (counter.get() <= baseline && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L);
        }
        return counter.get() > baseline;
    }

    private static void checkOk(String name, String json) {
        try {
            IslandJson.parse(json);
            pass();
        } catch (IslandJson.ParseException e) {
            fail(name + ": unexpected ParseException " + e.getMessage());
        }
    }

    private static void expectJsonFailure(String name, String json) {
        try {
            IslandJson.parse(json);
            fail(name + ": expected ParseException");
        } catch (IslandJson.ParseException e) {
            pass();
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            pass();
        } else {
            fail(name + ": expected true");
        }
    }

    private static void checkEq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass();
        } else {
            fail(name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void checkEq(String name, long expected, long actual) {
        if (expected == actual) {
            pass();
        } else {
            fail(name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void checkNull(String name, Object value) {
        checkEq(name, null, value);
    }

    private static void checkNotNull(String name, Object value) {
        if (value == null) {
            fail(name + ": expected non-null");
        } else {
            pass();
        }
    }

    private static void pass() {
        sPassed++;
    }

    private static void fail(String message) {
        sFailures.add(message);
    }

    private static void skip(String reason) {
        sSkipped.add(reason);
    }

    /** Deterministic monotonic clock for store tests. */
    static final class FakeClock implements IslandStore.Clock {
        private long mNow = 1_000_000L;

        @Override
        public long elapsedRealtime() {
            return mNow;
        }

        void advance(long millis) {
            mNow += millis;
        }
    }
}
