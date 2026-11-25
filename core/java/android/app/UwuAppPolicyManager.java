/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.app.IAppOpsService;

import java.util.ArrayList;
import java.util.function.IntConsumer;

/**
 * Package-scoped policy adapters shared by uwu services and settings.
 *
 * @hide
 */
public final class UwuAppPolicyManager {
    public static final int CLIPBOARD_READ = 0;
    public static final int CLIPBOARD_WRITE = 1;
    public static final int SENSOR = 2;
    public static final int JUMP_SOURCE = 3;
    public static final int JUMP_TARGET = 4;
    public static final int FEATURE_CLIPBOARD = 1;
    public static final int FEATURE_SENSOR = 2;
    public static final int FEATURE_JUMP = 4;
    public static final String MIGRATIONS = "_migrations";
    public static final int INHERIT = -1;

    private static final int[] OPS = {
        AppOpsManager.OP_UWU_READ_CLIPBOARD, AppOpsManager.OP_UWU_WRITE_CLIPBOARD,
        AppOpsManager.OP_UWU_CLIPBOARD_READ_PROMPT, AppOpsManager.OP_UWU_CLIPBOARD_WRITE_PROMPT,
        AppOpsManager.OP_UWU_SENSOR_ACCESS, AppOpsManager.OP_UWU_SENSOR_ACCESS_ON_LAUNCH,
        AppOpsManager.OP_UWU_START_ACTIVITY, AppOpsManager.OP_UWU_RECEIVE_ACTIVITY,
    };

    private UwuAppPolicyManager() {}

    private static IAppOpsService service() {
        return IAppOpsService.Stub.asInterface(ServiceManager.getService(Context.APP_OPS_SERVICE));
    }

    public static Bundle snapshot(int userId) {
        try {
            return service().getUwuAppPolicies(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static int policy(Bundle snapshot, String packageName, int type) {
        int[] values = snapshot.getIntArray(packageName);
        return values == null ? defaultPolicy(type) : values[type];
    }

    public static int defaultPolicy(int type) {
        if (type < CLIPBOARD_READ || type > JUMP_TARGET) {
            throw new IllegalArgumentException("Invalid policy type");
        }
        return type <= CLIPBOARD_WRITE ? INHERIT : 0;
    }

    public static int feature(int type) {
        defaultPolicy(type);
        return type <= CLIPBOARD_WRITE
                ? FEATURE_CLIPBOARD
                : type == SENSOR ? FEATURE_SENSOR : FEATURE_JUMP;
    }

    public static boolean migrated(Bundle state, int feature) {
        return (state.getInt(MIGRATIONS) & feature) != 0;
    }

    public static void setPolicy(String packageName, int userId, int type, int policy) {
        encode(type, policy);
        try {
            service()
                    .setUwuAppPolicies(
                            userId,
                            new String[] {packageName},
                            new int[] {type},
                            new int[] {policy},
                            0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Each legacy package has five policy slots; unrelated slots are ignored. */
    public static void migrate(int userId, int feature, Bundle legacy) {
        ArrayList<String> packages = new ArrayList<>();
        ArrayList<Integer> types = new ArrayList<>();
        ArrayList<Integer> policies = new ArrayList<>();
        for (String name : legacy.keySet()) {
            int[] values = legacy.getIntArray(name);
            if (values == null || values.length != 5) continue;
            for (int type = 0; type < 5; type++) {
                if (feature(type) != feature) continue;
                packages.add(name);
                types.add(type);
                policies.add(values[type]);
            }
        }
        try {
            service()
                    .setUwuAppPolicies(
                            userId,
                            packages.toArray(new String[0]),
                            types.stream().mapToInt(Integer::intValue).toArray(),
                            policies.stream().mapToInt(Integer::intValue).toArray(),
                            feature);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static int[] defaults() {
        return new int[] {-1, -1, 0, 0, 0};
    }

    /** Returns op/mode pairs for one logical policy; callers commit the pairs together. */
    public static int[] encode(int type, int policy) {
        feature(type);
        if (policy < (type <= CLIPBOARD_WRITE ? -1 : 0) || policy > 2) {
            throw new IllegalArgumentException("Invalid policy");
        }
        if (type <= CLIPBOARD_WRITE) {
            return new int[] {
                OPS[type],
                policy == 0
                        ? AppOpsManager.MODE_ALLOWED
                        : policy == 2 ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_DEFAULT,
                OPS[type + 2],
                policy == 1 ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED
            };
        }
        if (type == SENSOR) {
            return new int[] {
                OPS[4],
                policy == 2 ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                OPS[5],
                policy == 1 ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED
            };
        }
        return new int[] {
            OPS[type + 3],
            policy == 1
                    ? AppOpsManager.MODE_ALLOWED
                    : policy == 2 ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_DEFAULT
        };
    }

    /** Decodes raw package modes, without introducing UID-wide policy for shared UIDs. */
    public static int[] decode(int[] modes) {
        int[] result = defaults();
        for (int i = 0; i < 2; i++) {
            result[i] =
                    modes[i] == AppOpsManager.MODE_ALLOWED
                            ? 0
                            : modes[i] != AppOpsManager.MODE_DEFAULT
                                    ? 2
                                    : modes[i + 2] == AppOpsManager.MODE_ALLOWED ? 1 : INHERIT;
        }
        result[SENSOR] =
                modes[4] != AppOpsManager.MODE_ALLOWED
                        ? 2
                        : modes[5] != AppOpsManager.MODE_ALLOWED ? 1 : 0;
        for (int i = 3; i < 5; i++) {
            result[i] =
                    modes[i + 3] == AppOpsManager.MODE_ALLOWED
                            ? 1
                            : modes[i + 3] == AppOpsManager.MODE_DEFAULT ? 0 : 2;
        }
        return result;
    }

    public static int[] operations() {
        return OPS.clone();
    }

    /** Keeps service projections consistent when AppOps drops a removed package silently. */
    public static void watchPackageRemoval(Context context, Handler handler, IntConsumer callback) {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, android.content.Intent intent) {
                        if (intent.getBooleanExtra(android.content.Intent.EXTRA_REPLACING, false))
                            return;
                        int uid = intent.getIntExtra(android.content.Intent.EXTRA_UID, -1);
                        if (uid < 0) return;
                        int userId = UserHandle.getUserId(uid);
                        refresh(context, userId, callback);
                    }
                },
                UserHandle.ALL,
                filter,
                null,
                handler);
    }

    private static void refresh(Context context, int userId, IntConsumer callback) {
        if (userId < 0) return;
        try {
            if (context.getSystemService(android.os.UserManager.class).getUserInfo(userId) != null) {
                callback.accept(userId);
            }
        } catch (RuntimeException e) {
            android.util.Log.e("UwuAppPolicy", "Could not refresh app policies", e);
        }
    }

    public static AppOpsManager.OnOpChangedListener watch(
            Context context, Handler handler, IntConsumer callback) {
        return watch(context, handler, 7, callback);
    }

    public static AppOpsManager.OnOpChangedListener watch(
            Context context, Handler handler, int features, IntConsumer callback) {
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        AppOpsManager.OnOpChangedListener listener =
                new AppOpsManager.OnOpChangedListener() {
                    @Override
                    public void onOpChanged(String op, String packageName) {
                        onOpChanged(op, packageName, UserHandle.myUserId());
                    }

                    @Override
                    public void onOpChanged(String op, String packageName, int userId) {
                        handler.post(() -> refresh(context, userId, callback));
                    }
                };
        for (int i = 0; i < OPS.length; i++) {
            int feature = i < 4 ? FEATURE_CLIPBOARD : i < 6 ? FEATURE_SENSOR : FEATURE_JUMP;
            if ((features & feature) != 0) manager.startWatchingMode(OPS[i], null, listener);
        }
        return listener;
    }
}
