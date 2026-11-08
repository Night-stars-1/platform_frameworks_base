/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.island;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs the protocol behavior suite against the platform and a real Android runtime. */
@RunWith(AndroidJUnit4.class)
public class IslandBehaviorTest {
    @Test
    public void protocolBehavior() {
        IslandSelfTest.runJvmTests();
        IslandSelfTest.runAndroidTests(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertTrue(IslandSelfTest.report(), IslandSelfTest.failures().isEmpty());
    }
}
