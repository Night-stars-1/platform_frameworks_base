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
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the public Xiaomi client island notification protocol
 * ({@code notification.extras["miui.focus.param"]}, root object {@code param_v2}), the legacy flat
 * protocol 1 navigation card ({@code scene == "templateRevertProgressScene"}) plus the native
 * Android promoted-ongoing projection.
 *
 * <p>Contract of this class:
 * <ul>
 *   <li>Side effect free. Nothing is cancelled, promoted, re-posted or mutated; an unusable payload
 *       never drops the underlying notification.
 *   <li>Never throws for untrusted input. Malformed JSON, oversized/deep payloads, wrong types and
 *       hostile actions all degrade instead of crashing SystemUI.
 *   <li>Bounded. {@link IslandJson} enforces the payload, depth, width and string limits; this class
 *       additionally caps the number of rendered parts and actions.
 *   <li>Returns {@code null} only when the notification carries no island payload and is not a
 *       promoted-ongoing notification, i.e. when the caller should render an ordinary notification.
 * </ul>
 *
 * <p>This renders the documented OS2/OS3 client JSON semantically. It is not a HyperOS/MIPUSH
 * clone: the XMSF transport is out of scope and {@code RemoteViews} status bar payloads
 * ({@code miui.focus.rvBar}) are intentionally ignored rather than executed. Of the legacy flat
 * protocol 1 templates only the navigation card is implemented; other scenes keep the ordinary
 * notification projection instead of being approximated.
 */
public final class IslandProtocol {

    /** Notification extra holding the island JSON payload. */
    public static final String EXTRA_ISLAND = "miui.focus.param";
    /** Notification extra holding the {@code key -> Icon} picture bundle. */
    public static final String EXTRA_PICS = "miui.focus.pics";
    /** Notification extra holding the {@code key -> Notification.Action} action bundle. */
    public static final String EXTRA_ACTIONS = "miui.focus.actions";
    /** Root object of the island payload. */
    public static final String ROOT_V2 = "param_v2";

    /**
     * Legacy flat protocol version: the OS2-era form carries no {@code param_v2} wrapper and puts
     * the template fields directly on the root object.
     */
    private static final int LEGACY_PROTOCOL = 1;
    /** Legacy template scene of the navigation card (the only flat template rendered here). */
    private static final String LEGACY_SCENE_NAVIGATION = "templateRevertProgressScene";

    /** Protocol default for {@code param_v2.timeout}: 720 minutes. */
    private static final long DEFAULT_LIFETIME_MINUTES = 720L;
    /** Protocol default for {@code param_island.islandTimeout}: 3600 seconds. */
    private static final long DEFAULT_ISLAND_LIFETIME_SECONDS = 3600L;
    /** Upper bound applied to sender supplied lifetimes: 7 days. */
    private static final long MAX_LIFETIME_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    /** Maximum rendered expanded parts. */
    public static final int MAX_PARTS = 16;
    /** Maximum rendered actions. */
    public static final int MAX_ACTIONS = 8;

    private IslandProtocol() {
    }

    /**
     * Parses an island model out of {@code sbn}.
     *
     * @param context any context of the notification's user; resource icons and action targets are
     *     resolved for the notification package/user, never for SystemUI's own identity
     * @return the parsed model, or {@code null} when this notification is not island eligible
     */
    public static IslandModel parse(Context context, StatusBarNotification sbn) {
        if (context == null || sbn == null) {
            return null;
        }
        try {
            return parseInternal(context, sbn);
        } catch (RuntimeException e) {
            // Untrusted extras can raise anything, including BadParcelableException from a corrupt
            // Bundle. Never let that reach SystemUI: degrade to a projection that reads no extras.
            return safeFallback(context, sbn);
        }
    }

    /** Last resort projection; reads no notification extras so it cannot fail the same way. */
    private static IslandModel safeFallback(Context context, StatusBarNotification sbn) {
        try {
            Notification notification = sbn.getNotification();
            return buildPlain(context, sbn, notification, null, isPromotedOngoing(notification));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IslandModel parseInternal(Context context, StatusBarNotification sbn) {
        final Notification notification;
        try {
            notification = sbn.getNotification();
        } catch (RuntimeException e) {
            return null;
        }
        if (notification == null) {
            return null;
        }
        final Bundle extras = notification.extras;
        final String raw = extraString(extras, EXTRA_ISLAND);
        final boolean promoted = isPromotedOngoing(notification);

        if (TextUtils.isEmpty(raw)) {
            return promoted ? buildPlain(context, sbn, notification, extras, true) : null;
        }

        IslandJson.Value root;
        try {
            root = IslandJson.parse(raw);
        } catch (IslandJson.ParseException e) {
            return buildPlain(context, sbn, notification, extras, promoted);
        }
        IslandJson.Value v2 = root.get(ROOT_V2);
        if (v2 == null || !v2.isObject()) {
            // No usable param_v2: the payload may be the legacy flat protocol 1 navigation card
            // instead. Anything else keeps the ordinary notification projection.
            IslandModel legacy = buildLegacy(context, sbn, notification, extras, root, promoted);
            return legacy != null
                    ? legacy : buildPlain(context, sbn, notification, extras, promoted);
        }

        IslandModel model;
        try {
            model = buildIsland(context, sbn, notification, extras, v2, promoted);
        } catch (RuntimeException e) {
            return buildPlain(context, sbn, notification, extras, promoted);
        }
        if (model == null) {
            // Unsupported or empty template: project the ordinary notification so that it stays
            // visible instead of being silently dropped.
            return buildPlain(context, sbn, notification, extras, promoted);
        }
        return model;
    }

    /** True for {@link Notification#FLAG_PROMOTED_ONGOING}. */
    public static boolean isPromotedOngoing(Notification notification) {
        return notification != null
                && (notification.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
    }

    /**
     * Accepts only icons that can be rendered without a privileged fetch: resource and in-parcel
     * bitmap families. URI icons are dropped because loading them would make SystemUI resolve and
     * read a foreign {@code content://} URI on behalf of the sender.
     */
    public static Icon sanitizeIcon(Icon icon) {
        if (icon == null) {
            return null;
        }
        final int type;
        try {
            type = icon.getType();
        } catch (RuntimeException e) {
            return null;
        }
        switch (type) {
            case Icon.TYPE_RESOURCE:
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
                return icon;
            default:
                return null;
        }
    }

    /**
     * Loads an icon for rendering. Call with a context of the notification's user; the icon's own
     * resource package is resolved by the platform against {@code userId}.
     *
     * @return the drawable, or null when the icon is unsafe or cannot be resolved
     */
    public static Drawable loadIcon(Context context, Icon icon, int userId) {
        Icon safe = sanitizeIcon(icon);
        if (context == null || safe == null) {
            return null;
        }
        try {
            return safe.loadDrawableAsUser(context, userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses {@code #RGB}, {@code #RRGGBB} and {@code #AARRGGBB}; 0 when absent or invalid. */
    public static int parseColor(String value) {
        if (value == null) {
            return 0;
        }
        String text = value.trim();
        if (text.isEmpty() || text.charAt(0) != '#') {
            return 0;
        }
        String hex = text.substring(1);
        try {
            switch (hex.length()) {
                case 3: {
                    int r = Integer.parseInt(hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2), 16);
                    int bl = Integer.parseInt(hex.substring(2, 3), 16);
                    return 0xFF000000 | (r * 0x11 << 16) | (g * 0x11 << 8) | (bl * 0x11);
                }
                case 6:
                    return 0xFF000000 | (int) Long.parseLong(hex, 16);
                case 8:
                    return (int) Long.parseLong(hex, 16);
                default:
                    return 0;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Island payload
    // ---------------------------------------------------------------------------------------------

    /**
     * Parsing environment: everything the templates need besides the JSON itself. Holding the
     * source package here keeps the action URI policy and icon resolution bound to the notification
     * identity rather than to SystemUI's.
     */
    private static final class Env {
        final Context context;
        final String sourcePackage;
        final Bundle pics;
        final Bundle actionBundle;

        Env(Context context, String sourcePackage, Bundle pics, Bundle actionBundle) {
            this.context = context;
            this.sourcePackage = sourcePackage;
            this.pics = pics;
            this.actionBundle = actionBundle;
        }

        Icon icon(String key) {
            if (pics == null || TextUtils.isEmpty(key)) {
                return null;
            }
            try {
                Parcelable value = pics.getParcelable(key, Icon.class);
                return sanitizeIcon(value instanceof Icon ? (Icon) value : null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        Notification.Action notificationAction(String key) {
            if (actionBundle == null || TextUtils.isEmpty(key)) {
                return null;
            }
            try {
                Parcelable value = actionBundle.getParcelable(key, Notification.Action.class);
                return value instanceof Notification.Action ? (Notification.Action) value : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static IslandModel buildIsland(Context context, StatusBarNotification sbn,
            Notification notification, Bundle extras, IslandJson.Value v2, boolean promoted) {
        Env env = new Env(packageContext(context, sbn), sbn.getPackageName(),
                getBundle(extras, EXTRA_PICS), getBundle(extras, EXTRA_ACTIONS));

        IslandModel.Builder b = base(sbn);
        b.setPromoted(promoted);
        b.setProtocol(intOf(v2, "protocol", 0));
        b.setBusiness(str(v2, "business"));
        b.setAodTitle(str(v2, "aodTitle"));
        b.setTicker(str(v2, "ticker"));
        b.setFirstFloat(bool(v2, "islandFirstFloat", true));
        b.setUpdateFloat(bool(v2, "enableFloat", false));
        b.setUpdatable(bool(v2, "updatable", false));
        b.setCancel(bool(v2, "cancel", false));
        b.setFilterWhenNoPermission(bool(v2, "filterWhenNoPermission", false));
        b.setLifetimeMillis(minutesToMillis(intOf(v2, "timeout", (int) DEFAULT_LIFETIME_MINUTES)));
        b.setReopen(str(v2, "reopen"));
        Icon notificationIcon = safeSmallIcon(notification);
        Icon launcherIcon = launcherIcon(env.context, env.sourcePackage);
        Icon appIcon = launcherIcon != null ? launcherIcon : notificationIcon;
        b.setAppIcon(appIcon);
        b.setContentIntent(notification.contentIntent);

        IslandJson.Value island = v2.get("param_island");
        if (island != null && island.isObject()) {
            b.setReorder(bool(island, "islandOrder", false));
            b.setDismissIsland(bool(island, "dismissIsland", false));
            b.setIslandLifetimeMillis(secondsToMillis(
                    intOf(island, "islandTimeout", (int) DEFAULT_ISLAND_LIFETIME_SECONDS)));
            b.setHighlightColor(parseColor(str(island, "highlightColor")));
        } else {
            b.setIslandLifetimeMillis(secondsToMillis((int) DEFAULT_ISLAND_LIFETIME_SECONDS));
        }

        // Summary areas. Some senders place the area objects directly under param_v2, so accept
        // both placements; anything else is simply absent.
        IslandJson.Value big = firstObject(v2, "bigIslandArea");
        IslandJson.Value small = firstObject(v2, "smallIslandArea", "smallIsland");
        if (island != null && island.isObject()) {
            if (big == null) {
                big = island.get("bigIslandArea");
            }
            if (small == null) {
                small = firstObject(island, "smallIslandArea", "smallIsland");
            }
        }
        IslandModel.Part left = parseLeftArea(env, big);
        IslandModel.Part right = parseRightArea(env, big);
        IslandModel.Part smallArea = parseSmallArea(env, small);
        b.setLeft(left);
        b.setRight(right);
        b.setSmall(normalizeSmall(smallArea, left, appIcon));

        parseExpanded(env, v2, b);
        IslandModel model = b.build();
        if (left == null && right == null && smallArea == null && model.expanded.isEmpty()) {
            // Nothing the sender supplied is renderable: degrade to the ordinary notification
            // projection instead of showing an island built only from the secondary fallback icon.
            return null;
        }
        return model;
    }

    /**
     * Recognizes and builds the legacy flat protocol 1 payload: no {@code param_v2} object,
     * {@code protocol == 1} and a scene this parser actually implements. Returns null for every
     * other flat payload so ordinary notifications keep their plain projection.
     */
    private static IslandModel buildLegacy(Context context, StatusBarNotification sbn,
            Notification notification, Bundle extras, IslandJson.Value root, boolean promoted) {
        if (!root.isObject() || intOf(root, "protocol", 0) != LEGACY_PROTOCOL) {
            return null;
        }
        if (!LEGACY_SCENE_NAVIGATION.equals(str(root, "scene"))) {
            return null;
        }
        try {
            return buildNavigationCard(context, sbn, notification, extras, root, promoted);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The legacy navigation card ({@code scene == "templateRevertProgressScene"}), the flat form
     * amap sends for its ongoing navigation. Its fields map onto the same model as {@code param_v2}:
     * <ul>
     *   <li>{@code title} / {@code content} are the guidance text, i.e. the big island A area's
     *       label and detail.
     *   <li>{@code aodPic} (and {@code tickerPic}, which the sender points at the same key) is the
     *       navigation direction icon and {@code tickerPicDark} its dark variant. Both are resolved
     *       through the same {@code miui.focus.pics} bundle as {@code param_v2}, so only resource and
     *       in-parcel bitmap icons survive {@link #sanitizeIcon}.
     *   <li>{@code ticker} and {@code aodTitle} fill the equally named model fields, {@code updatable}
     *       and {@code enableFloat} the equally named flags.
     *   <li>{@code reopen} is deliberately <em>not</em> copied. The decompiled sender only shows that
     *       it emits {@code reopen} on ordinary updates and cancels its notification when the
     *       connection ends, so this field is not evidence of the {@code param_v2} cancellation policy
     *       that {@link IslandStore} turns into a tombstone. This parser therefore does not let the
     *       legacy value drive that policy: the card is mapped as updatable and reopenable, so the
     *       sender's own notification removal cannot carry over and suppress a later navigation of the
     *       same notification id.
     *   <li>{@code colorTitle} colours the text. {@code colorContent}, {@code colorBg},
     *       {@code showSmallIcon} and {@code padding} have no equivalent in this model and are
     *       ignored rather than approximated.
     *   <li>No lifetime is applied: the template declares none, and an ongoing navigation card must
     *       not expire on its own. The sender ends it by cancelling the notification.
     * </ul>
     */
    private static IslandModel buildNavigationCard(Context context, StatusBarNotification sbn,
            Notification notification, Bundle extras, IslandJson.Value root, boolean promoted) {
        Env env = new Env(packageContext(context, sbn), sbn.getPackageName(),
                getBundle(extras, EXTRA_PICS), getBundle(extras, EXTRA_ACTIONS));

        String title = str(root, "title");
        String content = str(root, "content");
        if (title == null && content == null) {
            // Nothing renderable was supplied: keep the ordinary notification projection.
            return null;
        }

        IslandModel.Builder b = base(sbn);
        b.setPromoted(promoted);
        b.setProtocol(LEGACY_PROTOCOL);
        b.setTicker(str(root, "ticker"));
        b.setAodTitle(str(root, "aodTitle"));
        b.setUpdatable(bool(root, "updatable", false));
        b.setUpdateFloat(bool(root, "enableFloat", false));
        b.setReopen(IslandModel.REOPEN);
        Icon notificationIcon = safeSmallIcon(notification);
        Icon launcher = launcherIcon(env.context, env.sourcePackage);
        Icon appIcon = launcher != null ? launcher : notificationIcon;
        b.setAppIcon(appIcon);
        b.setContentIntent(notification.contentIntent);

        IslandModel.Part left = new IslandModel.Part.Builder()
                .setComponent(LEGACY_SCENE_NAVIGATION)
                .setTitle(title)
                .setContent(content)
                .setIcon(env.icon(firstString(root, "aodPic", "tickerPic")))
                .setDarkIcon(env.icon(str(root, "tickerPicDark")))
                .setColor(parseColor(str(root, "colorTitle")))
                .build();
        b.setLeft(left);
        b.setSmall(normalizeSmall(null, left, appIcon));
        return b.build();
    }

    /**
     * Big island A area (left of the cutout).
     */
    private static IslandModel.Part parseLeftArea(Env env, IslandJson.Value big) {
        if (big == null || !big.isObject()) {
            return null;
        }
        IslandJson.Value left = big.get("imageTextInfoLeft");
        return left != null && left.isObject()
                ? parseImageText(env, "imageTextInfoLeft", left) : null;
    }

    /**
     * Big island B area (right of the cutout), in the documented priority order: image text, plain
     * text, digits, progress text, plain picture.
     */
    private static IslandModel.Part parseRightArea(Env env, IslandJson.Value big) {
        if (big == null || !big.isObject()) {
            return null;
        }
        IslandJson.Value right = big.get("imageTextInfoRight");
        if (right != null && right.isObject()) {
            return parseImageText(env, "imageTextInfoRight", right);
        }
        IslandJson.Value text = big.get("textInfo");
        if (text != null && text.isObject()) {
            return parseTextInfo("textInfo", text);
        }
        IslandJson.Value digit = big.get("sameWidthDigitInfo");
        if (digit != null && digit.isObject()) {
            return parseDigitInfo("sameWidthDigitInfo", digit);
        }
        digit = big.get("fixedWidthDigitInfo");
        if (digit != null && digit.isObject()) {
            return parseDigitInfo("fixedWidthDigitInfo", digit);
        }
        IslandJson.Value progressText = big.get("progressTextInfo");
        if (progressText != null && progressText.isObject()) {
            return parseProgressTextInfo(progressText);
        }
        IslandJson.Value pic = big.get("picInfo");
        if (pic != null && pic.isObject()) {
            IslandModel.Part.Builder p = new IslandModel.Part.Builder().setComponent("picInfo");
            applyPicInfo(env, p, pic);
            return p.build();
        }
        return null;
    }

    /**
     * Normalizes the small island area. The documented priority is the sender's own small icon, then
     * the big island A area (left) icon, then the app icon; a missing area still falls back so a
     * second activity can render. Everything else the sender supplied (text, progress, timer,
     * action) is preserved.
     */
    private static IslandModel.Part normalizeSmall(IslandModel.Part small, IslandModel.Part left,
            Icon appIcon) {
        Icon icon = null;
        if (small != null) {
            icon = small.icon != null ? small.icon : small.darkIcon;
        }
        if (icon == null && left != null) {
            icon = left.icon != null ? left.icon : left.darkIcon;
        }
        if (icon == null) {
            icon = appIcon;
        }
        if (small == null) {
            if (icon == null) {
                return null;
            }
            return new IslandModel.Part.Builder()
                    .setComponent("smallIslandArea")
                    .setIcon(icon)
                    .build();
        }
        if (small.icon == icon) {
            return small;
        }
        return small.toBuilder().setIcon(icon).build();
    }

    /**
     * The sender's launcher icon, resolved from the notification's own package context so that it is
     * correct for the notification's user. Returns null when it cannot be resolved.
     */
    private static Icon launcherIcon(Context packageContext, String sourcePackage) {
        if (packageContext == null || TextUtils.isEmpty(sourcePackage)) {
            return null;
        }
        try {
            ApplicationInfo info = packageContext.getApplicationInfo();
            if (info == null || info.icon == 0) {
                return null;
            }
            return sanitizeIcon(Icon.createWithResource(sourcePackage, info.icon));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IslandModel.Part parseSmallArea(Env env, IslandJson.Value small) {
        if (small == null || !small.isObject()) {
            return null;
        }
        IslandJson.Value combine = small.get("combinePicInfo");
        if (combine != null && combine.isObject()) {
            IslandModel.Part.Builder p = new IslandModel.Part.Builder()
                    .setComponent("combinePicInfo");
            applyPicInfo(env, p, combine.get("picInfo"));
            applyProgress(p, combine.get("progressInfo"));
            return p.build();
        }
        IslandJson.Value imageText = small.get("imageTextInfoRight");
        if (imageText != null && imageText.isObject()) {
            return parseImageText(env, "imageTextInfoRight", imageText);
        }
        IslandJson.Value pic = small.get("picInfo");
        if (pic != null && pic.isObject()) {
            IslandModel.Part.Builder p = new IslandModel.Part.Builder().setComponent("picInfo");
            applyPicInfo(env, p, pic);
            return p.build();
        }
        return null;
    }

    /** imageTextInfoLeft / imageTextInfoRight: an icon plus up to three text runs. */
    private static IslandModel.Part parseImageText(Env env, String component, IslandJson.Value v) {
        IslandModel.Part.Builder p = new IslandModel.Part.Builder()
                .setComponent(component)
                .setType(intOf(v, "type", 0));
        applyPicInfo(env, p, v.get("picInfo"));
        applyText(p, v.get("textInfo"));
        return p.build();
    }

    /** B area plain text component. */
    private static IslandModel.Part parseTextInfo(String component, IslandJson.Value v) {
        IslandModel.Part.Builder p = new IslandModel.Part.Builder().setComponent(component);
        applyText(p, v);
        return p.build();
    }

    /** sameWidthDigitInfo / fixedWidthDigitInfo. */
    private static IslandModel.Part parseDigitInfo(String component, IslandJson.Value v) {
        return new IslandModel.Part.Builder()
                .setComponent(component)
                .setDigit(str(v, "digit"))
                .setContent(str(v, "content"))
                .setHighlight(bool(v, "showHighlightColor", false))
                .setNarrowFont(bool(v, "narrowFont", false))
                .setTimer(parseTimer(v.get("timerInfo")))
                .build();
    }

    /** progressTextInfo: text plus a ring/line progress indicator. */
    private static IslandModel.Part parseProgressTextInfo(IslandJson.Value v) {
        IslandModel.Part.Builder p = new IslandModel.Part.Builder().setComponent("progressTextInfo");
        applyText(p, v.get("textInfo"));
        applyProgress(p, v.get("progressInfo"));
        return p.build();
    }

    private static void parseExpanded(Env env, IslandJson.Value v2, IslandModel.Builder b) {
        List<IslandModel.Part> parts = new ArrayList<>();
        addPart(parts, parseBaseInfo(env, v2.get("baseInfo")));
        addPart(parts, parseChatInfo(env, v2.get("chatInfo")));
        addPart(parts, parseHighlightInfo(env, v2.get("highlightInfo")));
        addPart(parts, parseIconTextInfo(env, v2.get("iconTextInfo")));
        addPart(parts, parseCoverInfo(env, v2.get("coverInfo")));
        addPart(parts, parseHintInfo(env, v2.get("hintInfo")));
        addPart(parts, parseProgressInfo(v2.get("progressInfo")));
        addPart(parts, parseMultiProgressInfo(v2.get("multiProgressInfo")));
        addPart(parts, parseHighlightInfoV3(env, v2.get("highlightInfoV3")));
        parseTextButton(env, v2.get("textButton"), parts);
        b.setExpanded(parts);

        // Button component 1: the primary action row of the expanded card.
        IslandJson.Value actions = v2.get("actions");
        if (actions != null && actions.isArray()) {
            List<IslandModel.Action> resolved = new ArrayList<>();
            for (IslandJson.Value item : actions.elements()) {
                if (resolved.size() >= MAX_ACTIONS) {
                    break;
                }
                IslandModel.Action action = buildAction(env, item, true);
                if (action != null) {
                    resolved.add(action);
                }
            }
            b.setActions(resolved);
        }
    }

    private static IslandModel.Part parseBaseInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("baseInfo")
                .setType(intOf(v, "type", 1))
                .setTitle(str(v, "title"))
                .setSubTitle(str(v, "subTitle"))
                .setExtraTitle(str(v, "extraTitle"))
                .setSpecialTitle(str(v, "specialTitle"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"))
                .setColor(parseColor(str(v, "colorTitle")))
                .setDarkColor(parseColor(str(v, "colorTitleDark")))
                .setIcon(env.icon(str(v, "picFunction")))
                .build();
    }

    private static IslandModel.Part parseChatInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("chatInfo")
                .setTitle(str(v, "title"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"))
                .setColor(parseColor(str(v, "colorTitle")))
                .setDarkColor(parseColor(str(v, "colorTitleDark")))
                .setIcon(env.icon(str(v, "picProfile")))
                .setDarkIcon(env.icon(str(v, "picProfileDark")))
                .setTimer(parseTimer(v.get("timerInfo")))
                .build();
    }

    private static IslandModel.Part parseHighlightInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("highlightInfo")
                .setType(intOf(v, "type", 0))
                .setTitle(str(v, "title"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"))
                .setColor(parseColor(str(v, "colorTitle")))
                .setDarkColor(parseColor(str(v, "colorTitleDark")))
                .setIcon(env.icon(str(v, "picFunction")))
                .setDarkIcon(env.icon(str(v, "picFunctionDark")))
                .setTimer(parseTimer(v.get("timerInfo")))
                .build();
    }

    private static IslandModel.Part parseIconTextInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        IslandModel.Part.Builder p = new IslandModel.Part.Builder()
                .setComponent("iconTextInfo")
                .setTitle(str(v, "title"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"));
        IslandJson.Value animIcon = v.get("animIconInfo");
        if (animIcon != null && animIcon.isObject()) {
            p.setIcon(env.icon(str(animIcon, "src")));
            p.setDarkIcon(env.icon(str(animIcon, "srcDark")));
        }
        return p.build();
    }

    private static IslandModel.Part parseCoverInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("coverInfo")
                .setTitle(str(v, "title"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"))
                .setIcon(env.icon(str(v, "picCover")))
                .build();
    }

    private static IslandModel.Part parseHintInfo(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("hintInfo")
                .setType(intOf(v, "type", 1))
                .setTitle(str(v, "title"))
                .setSubTitle(str(v, "subTitle"))
                .setContent(str(v, "content"))
                .setSubContent(str(v, "subContent"))
                .setIcon(env.icon(str(v, "picContent")))
                .setTimer(parseTimer(v.get("timerInfo")))
                .setAction(buildAction(env, v.get("actionInfo"), false))
                .build();
    }

    private static IslandModel.Part parseProgressInfo(IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        IslandModel.Part.Builder p = new IslandModel.Part.Builder().setComponent("progressInfo");
        applyProgress(p, v);
        return p.build();
    }

    private static IslandModel.Part parseMultiProgressInfo(IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("multiProgressInfo")
                .setTitle(str(v, "title"))
                .setProgress(clampProgress(intOf(v, "progress", 0)))
                .setProgressColor(parseColor(str(v, "color")))
                .setPoints(Math.max(0, intOf(v, "points", 0)))
                .build();
    }

    private static IslandModel.Part parseHighlightInfoV3(Env env, IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        return new IslandModel.Part.Builder()
                .setComponent("highlightInfoV3")
                .setTitle(str(v, "primaryText"))
                .setSubTitle(str(v, "secondaryText"))
                .setSpecialTitle(str(v, "highLightText"))
                .setColor(parseColor(str(v, "primaryTextColor")))
                .setDarkColor(parseColor(str(v, "primaryTextColorDark")))
                .setAction(buildAction(env, v.get("actionInfo"), false))
                .build();
    }

    private static void parseTextButton(Env env, IslandJson.Value v, List<IslandModel.Part> out) {
        if (v == null || !v.isArray()) {
            return;
        }
        for (IslandJson.Value item : v.elements()) {
            if (out.size() >= MAX_PARTS) {
                return;
            }
            IslandModel.Action action = buildAction(env, item, true);
            if (action == null) {
                continue;
            }
            out.add(new IslandModel.Part.Builder()
                    .setComponent("textButton")
                    .setType(intOf(item, "type", 2))
                    .setTitle(action.title)
                    .setAction(action)
                    .build());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Field helpers
    // ---------------------------------------------------------------------------------------------

    private static void addPart(List<IslandModel.Part> parts, IslandModel.Part part) {
        if (part != null && parts.size() < MAX_PARTS) {
            parts.add(part);
        }
    }

    private static void applyText(IslandModel.Part.Builder p, IslandJson.Value text) {
        if (text == null || !text.isObject()) {
            return;
        }
        p.setTitle(str(text, "title"));
        p.setSubTitle(str(text, "subTitle"));
        p.setFrontTitle(str(text, "frontTitle"));
        p.setContent(str(text, "content"));
        p.setSubContent(str(text, "subContent"));
        p.setExtraTitle(str(text, "extraTitle"));
        p.setSpecialTitle(str(text, "specialTitle"));
        p.setNarrowFont(bool(text, "narrowFont", false));
        p.setHighlight(bool(text, "showHighlightColor", false));
        p.setColor(parseColor(str(text, "colorTitle")));
        p.setDarkColor(parseColor(str(text, "colorTitleDark")));
    }

    private static void applyPicInfo(Env env, IslandModel.Part.Builder p, IslandJson.Value picInfo) {
        if (picInfo == null || !picInfo.isObject()) {
            return;
        }
        p.setIcon(env.icon(str(picInfo, "pic")));
        p.setDarkIcon(env.icon(str(picInfo, "picDark")));
        p.setAction(buildAction(env, picInfo.get("actionInfo"), false));
    }

    private static void applyProgress(IslandModel.Part.Builder p, IslandJson.Value progress) {
        if (progress == null || !progress.isObject()) {
            return;
        }
        p.setProgress(clampProgress(intOf(progress, "progress", 0)));
        p.setProgressColor(parseColor(firstString(progress, "colorProgress", "colorReach")));
        p.setProgressEndColor(parseColor(firstString(progress, "colorProgressEnd", "colorUnReach")));
    }

    private static IslandModel.Timer parseTimer(IslandJson.Value v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        int type = intOf(v, "timerType", IslandModel.Timer.TYPE_NONE);
        if (type < IslandModel.Timer.TYPE_COUNTDOWN_PAUSED
                || type > IslandModel.Timer.TYPE_COUNT_UP_PAUSED) {
            return null;
        }
        long when = longOf(v, "timerWhen", 0L);
        long total = longOf(v, "timerTotal", 0L);
        long reference = longOf(v, "timerSystemCurrent", longOf(v, "timerCurrent", 0L));
        return new IslandModel.Timer(type, when, Math.max(0L, total), reference);
    }

    /**
     * Builds one action from an {@code actionInfo} object, resolving bundle backed actions through
     * {@code miui.focus.actions}. Returns null when the entry has no usable target at all.
     */
    private static IslandModel.Action buildAction(Env env, IslandJson.Value info,
            boolean defaultCollapse) {
        if (info == null || !info.isObject()) {
            return null;
        }
        String key = str(info, "action");
        Notification.Action bundleAction = env.notificationAction(key);
        String title = str(info, "actionTitle");
        if (title == null && bundleAction != null) {
            title = charSequenceToString(bundleAction.title);
        }
        Icon icon = env.icon(str(info, "actionIcon"));
        if (icon == null) {
            icon = safeActionIcon(bundleAction);
        }
        PendingIntent pendingIntent = bundleAction == null ? null : bundleAction.actionIntent;
        int intentType = intOf(info, "actionIntentType", 0);
        Intent intent = IslandActions.resolveIntent(env.context, str(info, "actionIntent"),
                intentType, env.sourcePackage);
        boolean collapse = bool(info, "clickWithCollapse", defaultCollapse);
        if (title == null && icon == null && pendingIntent == null && intent == null) {
            return null;
        }
        return new IslandModel.Action(key, title, icon, pendingIntent, intent, intentType, collapse);
    }

    private static Bundle getBundle(Bundle extras, String key) {
        if (extras == null) {
            return null;
        }
        try {
            Bundle bundle = extras.getBundle(key);
            if (bundle == null || bundle.size() > IslandJson.MAX_ELEMENTS) {
                return null;
            }
            return bundle;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Extras reads are individually guarded: a corrupt Bundle raises on first access, and a hostile
     * sender can put any type under any key.
     */
    private static String extraString(Bundle extras, String key) {
        if (extras == null) {
            return null;
        }
        try {
            return extras.getString(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CharSequence extraText(Bundle extras, String key) {
        if (extras == null) {
            return null;
        }
        try {
            return extras.getCharSequence(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int extraInt(Bundle extras, String key, int fallback) {
        if (extras == null) {
            return fallback;
        }
        try {
            return extras.getInt(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean extraBoolean(Bundle extras, String key, boolean fallback) {
        if (extras == null) {
            return fallback;
        }
        try {
            return extras.getBoolean(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The small icon is a sender supplied Parcelable, so its access is guarded as well. */
    private static Icon safeSmallIcon(Notification notification) {
        if (notification == null) {
            return null;
        }
        try {
            return sanitizeIcon(notification.getSmallIcon());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Icon safeActionIcon(Notification.Action action) {
        if (action == null) {
            return null;
        }
        try {
            return sanitizeIcon(action.getIcon());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Plain notification projection
    // ---------------------------------------------------------------------------------------------

    /**
     * Normalized projection of an ordinary notification. Used for promoted-ongoing notifications and
     * as the graceful fallback for unsupported or malformed island payloads. Such a model never
     * expires on its own (both lifetimes are 0) because the sender asked for no island lifetime.
     */
    private static IslandModel buildPlain(Context context, StatusBarNotification sbn,
            Notification notification, Bundle extras, boolean promoted) {
        if (notification == null) {
            return null;
        }
        IslandModel.Builder b = base(sbn);
        b.setPromoted(promoted);
        b.setFallback(true);
        b.setContentIntent(notification.contentIntent);
        Icon smallIcon = safeSmallIcon(notification);
        Icon launcher = launcherIcon(packageContext(context, sbn), sbn.getPackageName());
        Icon appIcon = launcher != null ? launcher : smallIcon;
        b.setAppIcon(appIcon);

        String title = charSequenceToString(extraText(extras, Notification.EXTRA_TITLE));
        String text = charSequenceToString(extraText(extras, Notification.EXTRA_TEXT));
        String subText = charSequenceToString(extraText(extras, Notification.EXTRA_SUB_TEXT));
        if (TextUtils.isEmpty(title)) {
            title = charSequenceToString(
                    extraText(extras, Notification.EXTRA_SHORT_CRITICAL_TEXT));
        }
        IslandModel.Part left = new IslandModel.Part.Builder()
                .setComponent("notification")
                .setIcon(smallIcon)
                .setTitle(title)
                .setContent(text)
                .setSubContent(subText)
                .build();
        b.setLeft(left);
        // Same secondary fallback as the island path: a promoted or degraded second activity must
        // never end up with an empty small part, or it would render as nothing.
        b.setSmall(normalizeSmall(null, left, appIcon));

        int progress = extraInt(extras, Notification.EXTRA_PROGRESS, 0);
        int progressMax = extraInt(extras, Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = extraBoolean(extras, Notification.EXTRA_PROGRESS_INDETERMINATE,
                false);
        if (!indeterminate && progressMax > 0 && progress >= 0) {
            b.setRight(new IslandModel.Part.Builder()
                    .setComponent("notificationProgress")
                    .setProgress(clampProgress((int) ((long) progress * 100L / progressMax)))
                    .setProgressColor(notification.color)
                    .build());
        }

        List<IslandModel.Action> actions = new ArrayList<>();
        Notification.Action[] standard = notification.actions;
        if (standard != null) {
            for (int i = 0; i < standard.length && actions.size() < MAX_ACTIONS; i++) {
                Notification.Action action = standard[i];
                if (action == null || action.actionIntent == null) {
                    continue;
                }
                actions.add(new IslandModel.Action("android.action." + i,
                        charSequenceToString(action.title), safeActionIcon(action),
                        action.actionIntent, null, 0, false));
            }
        }
        b.setActions(actions);
        return b.build();
    }

    private static IslandModel.Builder base(StatusBarNotification sbn) {
        return new IslandModel.Builder()
                .setKey(sbn.getKey())
                .setPackageName(sbn.getPackageName())
                .setTag(sbn.getTag())
                .setId(sbn.getId())
                .setUid(sbn.getUid())
                .setUserId(sbn.getUserId())
                .setPostedAt(sbn.getPostTime());
    }

    /** Per-user, per-package context for the notification, falling back to {@code context}. */
    private static Context packageContext(Context context, StatusBarNotification sbn) {
        try {
            Context packageContext = sbn.getPackageContext(context);
            return packageContext == null ? context : packageContext;
        } catch (RuntimeException e) {
            return context;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Value helpers
    // ---------------------------------------------------------------------------------------------

    private static String str(IslandJson.Value obj, String key) {
        IslandJson.Value value = obj == null ? null : obj.get(key);
        String text = value == null ? null : value.asStringOrNull();
        return TextUtils.isEmpty(text) ? null : text;
    }

    private static boolean bool(IslandJson.Value obj, String key, boolean fallback) {
        IslandJson.Value value = obj == null ? null : obj.get(key);
        return value == null ? fallback : value.asBoolean(fallback);
    }

    private static int intOf(IslandJson.Value obj, String key, int fallback) {
        IslandJson.Value value = obj == null ? null : obj.get(key);
        return value == null ? fallback : value.asInt(fallback);
    }

    private static long longOf(IslandJson.Value obj, String key, long fallback) {
        IslandJson.Value value = obj == null ? null : obj.get(key);
        return value == null ? fallback : value.asLong(fallback);
    }

    private static IslandJson.Value firstObject(IslandJson.Value obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            IslandJson.Value value = obj.get(key);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private static String firstString(IslandJson.Value obj, String... keys) {
        for (String key : keys) {
            String value = str(obj, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String charSequenceToString(CharSequence value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return TextUtils.isEmpty(text) ? null : text;
    }

    private static int clampProgress(int progress) {
        if (progress < 0) {
            return 0;
        }
        return Math.min(progress, 100);
    }

    private static long minutesToMillis(int minutes) {
        if (minutes <= 0) {
            return 0L;
        }
        return Math.min((long) minutes * 60_000L, MAX_LIFETIME_MILLIS);
    }

    private static long secondsToMillis(int seconds) {
        if (seconds <= 0) {
            return 0L;
        }
        return Math.min((long) seconds * 1000L, MAX_LIFETIME_MILLIS);
    }
}
