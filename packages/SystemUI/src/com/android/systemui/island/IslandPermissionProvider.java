/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.app.INotificationManager;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Public, read-only query surface for the Xiaomi notification protocol.
 *
 * <p>Third party applications ask whether the system island may render their notification. The
 * answer is a conjunction of:
 *
 * <ul>
 *   <li>the island engine is installed and globally enabled,
 *   <li>the calling package is allowed by the per-application preference,
 *   <li>notifications for that package are enabled by the user.
 * </ul>
 *
 * <p>Security: the provider is exported because the querying client is an untrusted third party
 * application. Every request is bound to the Binder calling identity, which must own the queried
 * package in the provider's own user. System and root may query any package of that user. Anything
 * else is rejected. The provider never mutates state, so an unprivileged application cannot flip
 * island behaviour.
 */
public final class IslandPermissionProvider extends ContentProvider {

    private static final String TAG = "IslandPermissionProvider";

    /** Authority exposed to third party clients. */
    public static final String AUTHORITY = "miui.statusbar.notification.public";

    /** Path used by {@link #query} clients. */
    public static final String PATH_CAN_SHOW_FOCUS = "canShowFocus";

    /** Method name used by {@link #call} clients. */
    public static final String METHOD_CAN_SHOW_FOCUS = "canShowFocus";

    /** Extras key used by official clients to name the queried package. */
    public static final String EXTRA_PACKAGE = "package";

    public static final String KEY_CAN_SHOW_FOCUS = "canShowFocus";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_APP_ALLOWED = "appAllowed";
    public static final String KEY_NOTIFICATIONS_ENABLED = "notificationsEnabled";
    public static final String KEY_PACKAGE_NAME = "packageName";
    public static final String KEY_CODE = "code";

    public static final int CODE_OK = 0;
    public static final int CODE_DENIED = 1;
    public static final int CODE_INVALID_ARGUMENT = 2;

    private static final String[] COLUMNS = {
            KEY_CAN_SHOW_FOCUS,
            KEY_ENABLED,
            KEY_APP_ALLOWED,
            KEY_NOTIFICATIONS_ENABLED,
            KEY_PACKAGE_NAME,
            KEY_CODE,
    };

    private volatile IslandPreferences mPreferences;

    @Override
    public boolean onCreate() {
        mPreferences = new IslandPreferences(getContext());
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (mPreferences == null) {
            mPreferences = new IslandPreferences(context);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_CAN_SHOW_FOCUS.equals(method)) {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }
        // Official clients pass the queried package in the extras; older ones use the argument.
        // Both are subject to the same ownership check, and a conflict is refused outright.
        String fromExtras = extras == null ? null : extras.getString(EXTRA_PACKAGE);
        if (!TextUtils.isEmpty(fromExtras) && !TextUtils.isEmpty(arg)
                && !fromExtras.equals(arg)) {
            Bundle conflict = new Bundle();
            conflict.putString(KEY_PACKAGE_NAME, fromExtras);
            conflict.putInt(KEY_CODE, CODE_INVALID_ARGUMENT);
            return denied(conflict, "conflicting package arguments");
        }
        String packageName = !TextUtils.isEmpty(fromExtras) ? fromExtras : arg;
        return canShowFocus(packageName);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        String packageName = null;
        if (uri != null && uri.getPathSegments().size() > 1) {
            packageName = uri.getPathSegments().get(1);
        }
        if (TextUtils.isEmpty(packageName)) {
            packageName = getCallingPackage();
        }
        Bundle result = canShowFocus(packageName);
        String[] columns = sanitizeProjection(projection);
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            row.add(column, columnValue(result, column));
        }
        return cursor;
    }

    /** Only the documented columns can be projected; anything else falls back to the full set. */
    private static String[] sanitizeProjection(String[] projection) {
        if (projection == null || projection.length == 0) {
            return COLUMNS;
        }
        List<String> known = new ArrayList<>(projection.length);
        for (String column : projection) {
            if (column == null) {
                continue;
            }
            for (String candidate : COLUMNS) {
                if (candidate.equals(column)) {
                    known.add(column);
                    break;
                }
            }
        }
        return known.isEmpty() ? COLUMNS : known.toArray(new String[0]);
    }

    private static Object columnValue(Bundle result, String column) {
        switch (column) {
            case KEY_CAN_SHOW_FOCUS:
                return result.getBoolean(KEY_CAN_SHOW_FOCUS, false) ? 1 : 0;
            case KEY_ENABLED:
                return result.getBoolean(KEY_ENABLED, false) ? 1 : 0;
            case KEY_APP_ALLOWED:
                return result.getBoolean(KEY_APP_ALLOWED, false) ? 1 : 0;
            case KEY_NOTIFICATIONS_ENABLED:
                return result.getBoolean(KEY_NOTIFICATIONS_ENABLED, false) ? 1 : 0;
            case KEY_PACKAGE_NAME:
                return result.getString(KEY_PACKAGE_NAME);
            case KEY_CODE:
                return result.getInt(KEY_CODE, CODE_INVALID_ARGUMENT);
            default:
                return null;
        }
    }

    /**
     * Resolves the visibility of the island for one package, enforcing that the Binder caller owns
     * that package in this provider's user.
     */
    private Bundle canShowFocus(String packageName) {
        // The calling identity must be captured before it is cleared below.
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        Bundle result = new Bundle();
        result.putString(KEY_PACKAGE_NAME, packageName);

        if (TextUtils.isEmpty(packageName)) {
            result.putInt(KEY_CODE, CODE_INVALID_ARGUMENT);
            return denied(result, "missing package");
        }

        Context context = getContext();
        if (context == null) {
            result.putInt(KEY_CODE, CODE_DENIED);
            return denied(result, "no context");
        }

        final int providerUserId = context.getUserId();
        if (callingUserId != providerUserId) {
            // Never disclose another user's configuration or notification state.
            result.putInt(KEY_CODE, CODE_DENIED);
            return denied(result, "cross user caller");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            PackageManager packageManager = context.getPackageManager();
            if (!callerOwnsPackage(packageManager, callingUid, packageName)) {
                result.putInt(KEY_CODE, CODE_DENIED);
                return denied(result, "caller does not own package");
            }

            ApplicationInfo info;
            try {
                info = packageManager.getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                result.putInt(KEY_CODE, CODE_INVALID_ARGUMENT);
                return denied(result, "package not installed");
            }

            IslandPreferences preferences = mPreferences;
            if (preferences == null) {
                preferences = new IslandPreferences(context);
                mPreferences = preferences;
            }

            final boolean engineInstalled = IslandPreferences.isFeatureInstalled();
            final boolean globallyEnabled = preferences.isEnabled();
            final boolean appAllowed = preferences.isPackageAllowed(packageName);
            final boolean notificationsEnabled =
                    areNotificationsEnabled(packageManager, packageName, info.uid);

            result.putBoolean(KEY_ENABLED, globallyEnabled);
            result.putBoolean(KEY_APP_ALLOWED, appAllowed);
            result.putBoolean(KEY_NOTIFICATIONS_ENABLED, notificationsEnabled);
            result.putBoolean(KEY_CAN_SHOW_FOCUS,
                    engineInstalled && globallyEnabled && appAllowed && notificationsEnabled);
            result.putInt(KEY_CODE, CODE_OK);
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static boolean callerOwnsPackage(PackageManager packageManager, int callingUid,
            String packageName) {
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        if (callingUid == Process.myUid()) {
            return true;
        }
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String owned : packages) {
            if (packageName.equals(owned)) {
                return true;
            }
        }
        return false;
    }

    private static boolean areNotificationsEnabled(PackageManager packageManager, String packageName,
            int packageUid) {
        try {
            INotificationManager notificationManager = NotificationManager.getService();
            if (notificationManager == null) {
                Log.w(TAG, "Notification service unavailable; denying " + packageName);
                return false;
            }
            return notificationManager.areNotificationsEnabledForPackage(packageName, packageUid);
        } catch (Exception e) {
            // Fail closed: an unreadable notification state must not grant island rendering.
            Log.w(TAG, "Unable to read notification state for " + packageName, e);
            return false;
        }
    }

    private static Bundle denied(Bundle result, String reason) {
        result.putBoolean(KEY_CAN_SHOW_FOCUS, false);
        result.putBoolean(KEY_ENABLED, false);
        result.putBoolean(KEY_APP_ALLOWED, false);
        result.putBoolean(KEY_NOTIFICATIONS_ENABLED, false);
        Log.d(TAG, "canShowFocus denied: " + reason);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("IslandPermissionProvider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("IslandPermissionProvider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("IslandPermissionProvider is read-only");
    }
}
