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

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;

/**
 * Dispatch of island actions with the sender's identity, never with SystemUI's.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>A sender created {@link PendingIntent} is preferred. It already carries the sender identity,
 *       so the action executes exactly as the sender intended and SystemUI cannot be used as a
 *       privilege proxy.
 *   <li>The creator identity must match the notification identity <em>exactly</em>: same uid, same
 *       package and same user. A uid match alone is not enough because uids are per user, and a
 *       package match alone is not enough because the same package name can be installed for
 *       another user. There is deliberately no allow-list of "trusted" system uids: trusting one
 *       would let a privileged app's PendingIntent be fired for another app's notification.
 *   <li>The serialized URI fallback is only accepted for activity targets that are explicit,
 *       exported, enabled components of the source package which require no permission the source
 *       package does not hold. Broadcast and service URIs are rejected outright: SystemUI would send
 *       them with its own identity, which can reach protected broadcasts and permission guarded
 *       receivers the sender could not. Senders that need those must ship a PendingIntent.
 *   <li>Everything rejected returns {@code false}. This class never throws and never "repairs" an
 *       unsafe target into a plausible looking one.
 * </ol>
 */
public final class IslandActions {

    /** Maximum accepted serialized intent URI length. */
    public static final int MAX_URI_CHARS = 4096;

    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private IslandActions() {
    }

    /**
     * Fires {@code action} for a notification posted by {@code sourcePackage}/{@code sourceUid} to
     * user {@code userId}.
     *
     * @return true when a target was dispatched; false when rejected or unusable
     */
    public static boolean send(Context context, IslandModel.Action action, String sourcePackage,
            int sourceUid, int userId) {
        if (context == null || action == null) {
            return false;
        }
        if (action.pendingIntent != null) {
            return sendPendingIntent(context, action.pendingIntent, sourcePackage, sourceUid,
                    userId);
        }
        return sendUriAction(context, action.intent, action.intentType, sourcePackage);
    }

    /** Fires the model's content tap target (island body tap). */
    public static boolean sendContent(Context context, IslandModel model) {
        if (context == null || model == null) {
            return false;
        }
        return sendPendingIntent(context, model.contentIntent, model.packageName, model.uid,
                model.userId);
    }

    /**
     * Fires a sender created PendingIntent after validating its creator identity and user.
     *
     * @param userId the notification's user; must be a real user, never {@link UserHandle#USER_NULL}
     */
    public static boolean sendPendingIntent(Context context, PendingIntent pendingIntent,
            String sourcePackage, int sourceUid, int userId) {
        if (context == null || pendingIntent == null || userId < 0) {
            return false;
        }
        final String creatorPackage;
        final int creatorUid;
        final int creatorUserId;
        try {
            creatorPackage = pendingIntent.getCreatorPackage();
            creatorUid = pendingIntent.getCreatorUid();
            UserHandle creatorUser = pendingIntent.getCreatorUserHandle();
            creatorUserId = creatorUser == null ? UserHandle.USER_NULL : creatorUser.getIdentifier();
        } catch (RuntimeException e) {
            return false;
        }
        if (!isTrustedCreator(creatorPackage, creatorUid, sourcePackage, sourceUid)) {
            return false;
        }
        if (creatorUserId != userId) {
            return false;
        }
        try {
            // Background-activity-launch options only make sense for an activity target: passing an
            // options bundle with a broadcast or service send routes through the platform's
            // broadcast-options permission check and can silently change delivery semantics.
            pendingIntent.send(context, 0, null, null, null, null,
                    pendingIntent.isActivity() ? userTriggeredOptions() : null);
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True when a PendingIntent created by {@code creatorPackage}/{@code creatorUid} may be fired on
     * behalf of the notification owned by {@code sourcePackage}/{@code sourceUid}.
     *
     * <p>Both the uid and the package must match exactly, and the source uid must be a real uid.
     * This deliberately refuses a shared-uid sibling app's PendingIntent: uid equality without
     * package equality does not prove the same sender, and package equality without uid equality
     * can be satisfied by the same package name installed for another user.
     */
    public static boolean isTrustedCreator(String creatorPackage, int creatorUid,
            String sourcePackage, int sourceUid) {
        if (sourceUid < 0 || creatorUid != sourceUid) {
            return false;
        }
        // Plain String comparison: this predicate is exercised by the host JVM self test, so it must
        // not depend on any Android class.
        return sourcePackage != null && !sourcePackage.isEmpty() && sourcePackage.equals(creatorPackage);
    }

    /**
     * Resolves a serialized {@code actionIntent} URI to a target that is safe to fire as SystemUI.
     *
     * @return the sanitized explicit intent, or null when the URI is unsafe, unsupported or absent
     */
    public static Intent resolveIntent(Context context, String uri, int intentType,
            String sourcePackage) {
        if (context == null || TextUtils.isEmpty(uri) || TextUtils.isEmpty(sourcePackage)) {
            return null;
        }
        if (uri.length() > MAX_URI_CHARS) {
            return null;
        }
        if (!isDispatchableUriType(intentType)) {
            // Only activity targets are dispatched from a URI; broadcast/service would run with
            // SystemUI's identity and can reach protected components.
            return null;
        }
        final Intent intent;
        try {
            intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (Exception e) {
            return null;
        }
        if (intent == null) {
            return null;
        }
        if (intent.getSelector() != null || intent.getClipData() != null) {
            return null;
        }
        if ((intent.getFlags() & GRANT_FLAGS) != 0) {
            return null;
        }
        ComponentName component = intent.getComponent();
        String explicitPackage = intent.getPackage();
        if (component != null) {
            if (!sourcePackage.equals(component.getPackageName())) {
                return null;
            }
            if (explicitPackage != null && !sourcePackage.equals(explicitPackage)) {
                return null;
            }
        } else if (!sourcePackage.equals(explicitPackage)) {
            // Implicit or foreign package: refused rather than re-targeted.
            return null;
        }
        Uri data = intent.getData();
        if (data != null && isForbiddenUriScheme(data.getScheme())) {
            return null;
        }
        if (!isLaunchableComponent(context, intent, sourcePackage)) {
            return null;
        }
        intent.setPackage(sourcePackage);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Only activity targets may be dispatched from a serialized URI. */
    public static boolean isDispatchableUriType(int intentType) {
        return intentType == IslandModel.Action.TYPE_ACTIVITY;
    }

    /** True for data schemes that would require SystemUI to hold a grant it must not fabricate. */
    public static boolean isForbiddenUriScheme(String scheme) {
        return "file".equals(scheme) || "content".equals(scheme);
    }

    /**
     * Activity options for a user triggered activity launch. The island tap is a real user
     * interaction, so the target activity must be allowed to start even though SystemUI itself is in
     * the background and its uid is {@code android.uid.systemui}, not {@code 1000}. Without this,
     * background activity launch rules can silently drop the launch on Android 15+.
     *
     * @return the options bundle, or null when the platform refuses to build one
     */
    private static Bundle userTriggeredOptions() {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
            return options.toBundle();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * True when the intent resolves to an exported, enabled component of the source package that
     * requires no permission the source package does not already hold.
     */
    private static boolean isLaunchableComponent(Context context, Intent intent,
            String sourcePackage) {
        try {
            PackageManager pm = context.getPackageManager();
            ResolveInfo info = pm.resolveActivity(intent, 0);
            if (info == null || info.activityInfo == null) {
                return false;
            }
            ActivityInfo activity = info.activityInfo;
            if (!sourcePackage.equals(activity.packageName)) {
                return false;
            }
            if (!activity.exported || !activity.enabled) {
                return false;
            }
            if (activity.applicationInfo != null && !activity.applicationInfo.enabled) {
                return false;
            }
            String permission = activity.permission;
            if (permission != null && pm.checkPermission(permission, sourcePackage)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean sendUriAction(Context context, Intent intent, int intentType,
            String sourcePackage) {
        if (intent == null || TextUtils.isEmpty(sourcePackage)) {
            return false;
        }
        if (!isDispatchableUriType(intentType)) {
            return false;
        }
        if (!sourcePackage.equals(intent.getPackage())) {
            return false;
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
