/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensors;

import static com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.utils.TimingsTraceAndSlog;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import android.app.UwuAppPolicyManager;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

public class SensorService extends SystemService {
    private static final String TAG = "SensorService";
    private static final String START_NATIVE_SENSOR_SERVICE = "StartNativeSensorService";
    private static final long LAUNCH_DENIAL_DURATION_MILLIS = 6_000L;
    private final Object mLock = new Object();
    private final Object mPolicyRefreshLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<ProximityActiveListener, ProximityListenerProxy> mProximityListeners =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private final Set<Integer> mRuntimeSensorHandles = new HashSet<>();
    @GuardedBy("mLock")
    private Future<?> mSensorServiceStart;
    @GuardedBy("mLock")
    private long mPtr;
    private final Handler mHandler = BackgroundThread.getHandler();
    @GuardedBy("mLock")
    private final SparseArray<ArrayMap<String, Integer>> mPoliciesByUser = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ArrayMap<String, Long>> mLastResumedByUser = new SparseArray<>();


    /** Start the sensor service. This is a blocking call and can take time. */
    private static native long startSensorServiceNative(ProximityActiveListener listener);

    private static native void registerProximityActiveListenerNative(long ptr);
    private static native void unregisterProximityActiveListenerNative(long ptr);

    private static native int registerRuntimeSensorNative(long ptr, int deviceId, int type,
            String name, String vendor, float maximumRange, float resolution, float power,
            int minDelay, int maxDelay, int flags,
            SensorManagerInternal.RuntimeSensorCallback callback);
    private static native void unregisterRuntimeSensorNative(long ptr, int handle);
    private static native boolean sendRuntimeSensorEventNative(long ptr, int handle, int type,
            long timestampNanos, float[] values);
    private static native boolean sendRuntimeSensorAdditionalInfoNative(long ptr, int handle,
            int type, int serial, long timestampNanos, float[] values);
    private static native void setApplicationSensorAccessNative(long ptr, int userId,
            String packageName, boolean allowed);


    public SensorService(Context ctx) {
        super(ctx);
        synchronized (mLock) {
            mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_NATIVE_SENSOR_SERVICE);
                long ptr = startSensorServiceNative(new ProximityListenerDelegate());
                synchronized (mLock) {
                    mPtr = ptr;
                }
                traceLog.traceEnd();
            }, START_NATIVE_SENSOR_SERVICE);
        }
    }

    @Override
    public void onStart() {
        LocalServices.addService(SensorManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE) {
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart,
                    START_NATIVE_SENSOR_SERVICE);
            synchronized (mLock) {
                mSensorServiceStart = null;
            }
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            initializeApplicationSensorPolicies();
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        mHandler.post(() -> reloadPolicies(user.getUserIdentifier()));
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();
        mHandler.post(() -> clearUserPolicies(userId));
    }

    private void initializeApplicationSensorPolicies() {
        UwuAppPolicyManager.watch(getContext(), mHandler, UwuAppPolicyManager.FEATURE_SENSOR, this::reloadPolicies);

        UwuAppPolicyManager.watchPackageRemoval(getContext(), mHandler, this::reloadPolicies);

        final UsageStatsManagerInternal usageStats =
                LocalServices.getService(UsageStatsManagerInternal.class);
        if (usageStats != null) {
            usageStats.registerListener(this::onUsageEvent);
        } else {
            Slog.w(TAG, "UsageStatsManagerInternal unavailable; launch sensor policy disabled");
        }

        final UserManager userManager = getContext().getSystemService(UserManager.class);
        if (userManager == null) {
            reloadPolicies(UserHandle.USER_SYSTEM);
            return;
        }
        for (UserHandle user : userManager.getUserHandles(true)) {
            reloadPolicies(user.getIdentifier());
        }
    }

    private void onUsageEvent(int userId, @NonNull UsageEvents.Event event) {
        if (event.mEventType != UsageEvents.Event.ACTIVITY_RESUMED || event.mPackage == null) {
            return;
        }
        final String packageName = event.mPackage;
        final long resumedAt = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            ArrayMap<String, Long> resumed = mLastResumedByUser.get(userId);
            if (resumed == null) {
                resumed = new ArrayMap<>();
                mLastResumedByUser.put(userId, resumed);
            }
            resumed.put(packageName, resumedAt);
            if (getPolicyLocked(userId, packageName)
                    != Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH) {
                return;
            }
            setApplicationSensorAccessNative(mPtr, userId, packageName, false);
        }
        mHandler.postDelayed(() -> finishLaunchDenial(userId, packageName, resumedAt),
                LAUNCH_DENIAL_DURATION_MILLIS);
    }

    private void finishLaunchDenial(int userId, String packageName, long resumedAt) {
        synchronized (mLock) {
            final ArrayMap<String, Long> resumed = mLastResumedByUser.get(userId);
            if (resumed == null || resumed.getOrDefault(packageName, -1L) != resumedAt
                    || getPolicyLocked(userId, packageName)
                    != Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH) {
                return;
            }
            setApplicationSensorAccessNative(mPtr, userId, packageName, true);
        }
    }

    private void reloadPolicies(int userId) {
        synchronized (mPolicyRefreshLock) { reloadPoliciesSerialized(userId); }
    }

    private void reloadPoliciesSerialized(int userId) {
        if (userId < UserHandle.USER_SYSTEM) {
            return;
        }
        Bundle snapshot = UwuAppPolicyManager.snapshot(userId);
        if (!UwuAppPolicyManager.migrated(snapshot, UwuAppPolicyManager.FEATURE_SENSOR)) {
            final ArrayMap<String, Integer> old = parsePolicies(Settings.Secure.getStringForUser(
                    getContext().getContentResolver(), Settings.Secure.UWU_APP_SENSOR_POLICIES,
                    userId));
            final Bundle legacy = new Bundle();
            for (int i = 0; i < old.size(); i++) {
                int[] values = UwuAppPolicyManager.defaults();
                values[UwuAppPolicyManager.SENSOR] = old.valueAt(i);
                legacy.putIntArray(old.keyAt(i), values);
            }
            UwuAppPolicyManager.migrate(userId, UwuAppPolicyManager.FEATURE_SENSOR, legacy);
            snapshot = UwuAppPolicyManager.snapshot(userId);
        }
        final ArrayMap<String, Integer> policies = new ArrayMap<>();
        for (String name : snapshot.keySet()) {
            if (UwuAppPolicyManager.MIGRATIONS.equals(name)) continue;
            int policy = UwuAppPolicyManager.policy(snapshot, name, UwuAppPolicyManager.SENSOR);
            if (policy != Settings.Secure.UWU_APP_SENSOR_POLICY_ALLOW) policies.put(name, policy);
        }
        synchronized (mLock) {
            final ArrayMap<String, Integer> oldPolicies = mPoliciesByUser.get(userId);
            final ArrayMap<String, Boolean> affected = new ArrayMap<>();
            if (oldPolicies != null) {
                for (int i = 0; i < oldPolicies.size(); i++) {
                    affected.put(oldPolicies.keyAt(i), true);
                }
            }
            for (int i = 0; i < policies.size(); i++) {
                affected.put(policies.keyAt(i), true);
            }
            mPoliciesByUser.put(userId, policies);
            for (int i = 0; i < affected.size(); i++) {
                final String packageName = affected.keyAt(i);
                final long now = SystemClock.elapsedRealtime();
                setApplicationSensorAccessNative(mPtr, userId, packageName,
                        !isDeniedLocked(userId, packageName, now));
                if (getPolicyLocked(userId, packageName)
                        == Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH
                        && (oldPolicies == null || oldPolicies.getOrDefault(packageName, 0)
                                != Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH)) {
                    final ArrayMap<String, Long> resumed = mLastResumedByUser.get(userId);
                    final long resumedAt = resumed == null ? -1L
                            : resumed.getOrDefault(packageName, -1L);
                    final long remaining = resumedAt + LAUNCH_DENIAL_DURATION_MILLIS - now;
                    if (resumedAt >= 0 && remaining > 0) {
                        mHandler.postDelayed(
                                () -> finishLaunchDenial(userId, packageName, resumedAt), remaining);
                    }
                }
            }
        }
    }

    private void clearUserPolicies(int userId) {
        synchronized (mLock) {
            final ArrayMap<String, Integer> policies = mPoliciesByUser.get(userId);
            if (policies != null) {
                for (int i = 0; i < policies.size(); i++) {
                    setApplicationSensorAccessNative(mPtr, userId, policies.keyAt(i), true);
                }
            }
            mPoliciesByUser.remove(userId);
            mLastResumedByUser.remove(userId);
        }
    }

    private boolean isDeniedLocked(int userId, String packageName, long now) {
        final int policy = getPolicyLocked(userId, packageName);
        if (policy == Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ALWAYS) {
            return true;
        }
        if (policy != Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH) {
            return false;
        }
        final ArrayMap<String, Long> resumed = mLastResumedByUser.get(userId);
        final long resumedAt = resumed == null ? -1L : resumed.getOrDefault(packageName, -1L);
        return resumedAt >= 0 && now - resumedAt < LAUNCH_DENIAL_DURATION_MILLIS;
    }

    private int getPolicyLocked(int userId, String packageName) {
        final ArrayMap<String, Integer> policies = mPoliciesByUser.get(userId);
        return policies == null ? Settings.Secure.UWU_APP_SENSOR_POLICY_ALLOW
                : policies.getOrDefault(packageName,
                        Settings.Secure.UWU_APP_SENSOR_POLICY_ALLOW);
    }

    private static ArrayMap<String, Integer> parsePolicies(@Nullable String value) {
        final ArrayMap<String, Integer> policies = new ArrayMap<>();
        if (value == null || value.isBlank()) {
            return policies;
        }
        try {
            final JSONObject object = new JSONObject(value);
            final Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                final String packageName = keys.next();
                final int policy = object.optInt(packageName,
                        Settings.Secure.UWU_APP_SENSOR_POLICY_ALLOW);
                if (policy == Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ON_LAUNCH
                        || policy == Settings.Secure.UWU_APP_SENSOR_POLICY_DENY_ALWAYS) {
                    policies.put(packageName, policy);
                }
            }
        } catch (JSONException e) {
            Slog.w(TAG, "Ignoring malformed app sensor policy", e);
        }
        return policies;
    }

    class LocalService extends SensorManagerInternal {
        @Override
        public int createRuntimeSensor(int deviceId, int type, @NonNull String name,
                @NonNull String vendor, float maximumRange, float resolution, float power,
                int minDelay, int maxDelay, int flags, @NonNull RuntimeSensorCallback callback) {
            synchronized (mLock) {
                int handle = registerRuntimeSensorNative(mPtr, deviceId, type, name, vendor,
                        maximumRange, resolution, power, minDelay, maxDelay, flags, callback);
                mRuntimeSensorHandles.add(handle);
                return handle;
            }
        }

        @Override
        public void removeRuntimeSensor(int handle) {
            synchronized (mLock) {
                if (mRuntimeSensorHandles.contains(handle)) {
                    mRuntimeSensorHandles.remove(handle);
                    unregisterRuntimeSensorNative(mPtr, handle);
                }
            }
        }

        @Override
        public boolean sendSensorEvent(int handle, int type, long timestampNanos,
                @NonNull float[] values) {
            synchronized (mLock) {
                if (!mRuntimeSensorHandles.contains(handle)) {
                    return false;
                }
                return sendRuntimeSensorEventNative(mPtr, handle, type, timestampNanos, values);
            }
        }

        @Override
        public boolean sendSensorAdditionalInfo(int handle, int type, int serial,
                long timestampNanos, @Nullable float[] values) {
            synchronized (mLock) {
                if (!mRuntimeSensorHandles.contains(handle)) {
                    return false;
                }
                return sendRuntimeSensorAdditionalInfoNative(mPtr, handle, type, serial,
                        timestampNanos, values);
            }
        }

        @Override
        public void addProximityActiveListener(@NonNull Executor executor,
                @NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            ProximityListenerProxy proxy = new ProximityListenerProxy(executor, listener);
            synchronized (mLock) {
                if (mProximityListeners.containsKey(listener)) {
                    throw new IllegalArgumentException("listener already registered");
                }
                mProximityListeners.put(listener, proxy);
                if (mProximityListeners.size() == 1) {
                    registerProximityActiveListenerNative(mPtr);
                }
            }
        }

        @Override
        public void removeProximityActiveListener(@NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            synchronized (mLock) {
                ProximityListenerProxy proxy = mProximityListeners.remove(listener);
                if (proxy == null) {
                    throw new IllegalArgumentException(
                            "listener was not registered with sensor service");
                }
                if (mProximityListeners.isEmpty()) {
                    unregisterProximityActiveListenerNative(mPtr);
                }
            }
        }
    }

    private static class ProximityListenerProxy implements ProximityActiveListener {
        private final Executor mExecutor;
        private final ProximityActiveListener mListener;

        ProximityListenerProxy(Executor executor, ProximityActiveListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onProximityActive(boolean isActive) {
            mExecutor.execute(() -> mListener.onProximityActive(isActive));
        }
    }

    private class ProximityListenerDelegate implements ProximityActiveListener {
        @Override
        public void onProximityActive(boolean isActive) {
            final ProximityListenerProxy[] listeners;
            // We can't call out while holding the lock because clients might be calling into us
            // while holding their own  locks (e.g. when registering / unregistering their
            // listeners).This would break lock ordering and create deadlocks. Instead, we need to
            // copy the listeners out and then only invoke them once we've dropped the lock.
            synchronized (mLock) {
                listeners = mProximityListeners.values().toArray(new ProximityListenerProxy[0]);
            }
            for (ProximityListenerProxy listener : listeners) {
                listener.onProximityActive(isActive);
            }
        }
    }
}
