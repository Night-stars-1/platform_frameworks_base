/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ThresholdSwipeDistance
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.not
import com.android.compose.animation.scene.or
import com.android.systemui.shade.ui.composable.OverlayShade

/** Slide both shade panels together rather than collapsing and expanding them vertically. */
fun TransitionBuilder.betweenShadePanelsTransition(
    fromPanel: ElementKey = OverlayShade.Elements.NotificationsSlidingContent,
    toPanel: ElementKey = OverlayShade.Elements.QuickSettingsSlidingContent,
    exitEdge: Edge = Edge.Start,
    enterEdge: Edge = Edge.End,
) {
    val panelSpec = tween<Float>(560, easing = CubicBezierEasing(0.22f, 1.13f, 0.04f, 0.99f))
    spec = panelSpec
    distance =
        ThresholdSwipeDistance(
            positionalThreshold = 130.dp,
            minimumFlingDistance = 0.dp,
            velocityThreshold = 0.dp,
            swipeDistance = 260.dp,
            velocityProjectionMillis = 180,
            edgeResistance = 0.18f,
            settleSpec = panelSpec,
        )
    // Children must travel with their panel, not morph between two moving containers.
    sharedElement(!(OverlayShade.Elements.Scrim or OverlayShade.Elements.Header), enabled = false)
    translate(fromPanel, exitEdge)
    translate(toPanel, enterEdge)
    sharedElement(OverlayShade.Elements.Scrim, enabled = true)
    sharedElement(OverlayShade.Elements.Header, enabled = true)
}
