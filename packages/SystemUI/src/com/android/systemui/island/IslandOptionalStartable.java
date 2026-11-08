/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import android.content.Context;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.res.R;

import javax.inject.Inject;
import javax.inject.Provider;

/** Defers the Island dependency graph until this product explicitly enables the feature. */
public final class IslandOptionalStartable implements CoreStartable {
    private final Context mContext;
    private final Provider<IslandStartable> mIsland;

    @Inject
    public IslandOptionalStartable(@Application Context context, Provider<IslandStartable> island) {
        mContext = context;
        mIsland = island;
    }

    @Override
    public void start() {
        if (mContext.getResources().getBoolean(R.bool.config_uwuIslandEnabled)
                && IslandPreferences.isFeatureInstalled()) {
            mIsland.get().start();
        }
    }
}
