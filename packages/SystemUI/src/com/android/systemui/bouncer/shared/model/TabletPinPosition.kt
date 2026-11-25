/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.bouncer.shared.model

/** Physical screen position of the tablet PIN panel, independent of layout direction. */
enum class TabletPinPosition(val horizontalBias: Float) {
    LEFT(0f),
    CENTER(0.5f),
    RIGHT(1f);

    companion object {
        @JvmStatic
        fun fromTouch(x: Float, width: Float): TabletPinPosition {
            if (!x.isFinite() || !width.isFinite() || width <= 0f) return CENTER
            return when {
                x < width / 3f -> LEFT
                x < width * 2f / 3f -> CENTER
                else -> RIGHT
            }
        }
    }
}
