/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import com.android.systemui.CoreStartable;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Binds the island startable into the SystemUI core startable map.
 *
 * <p>This module is added to {@code ReferenceSystemUIModule}, which is part of the component the
 * running SystemUI is built from.
 */
@Module
public abstract class IslandStartupModule {

    @Binds
    @IntoMap
    @ClassKey(IslandOptionalStartable.class)
    public abstract CoreStartable bindIslandStartable(IslandOptionalStartable startable);
}