/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.modifiers.size
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.flags.DesktopSizing
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.SideIconWidth
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TILE_INITIAL_DELAY_MILLIS
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TILE_MARQUEE_ITERATIONS
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileEndPadding
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileLabelBlurWidth
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.internal.R as InternalR
import kotlin.math.abs
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues
import org.uwuaosp.systemui.qsstyle.CircularTileHeight
import org.uwuaosp.systemui.qsstyle.LocalQSTileStyle

private const val TEST_TAG_TILE_ICON = "qs_tile_icon"
private const val TEST_TAG_TOGGLE = "qs_tile_toggle_target"
private const val TEST_TAG_SMALL = "qs_tile_small"
private const val TEST_TAG_LARGE = "qs_tile_large"

/** Horizontal padding applied to the label of a circular tile so it never touches the neighbors. */
private val CircularTileHorizontalPadding = 2.dp

/** Gap between the circular icon and its label in the circular tile style. */
private val CircularTileLabelTopPadding = 7.dp

/** Fixed line slots keep single- and double-line circular tiles vertically aligned. */
private val CircularTilePrimaryLabelHeight = 14.dp
private val CircularTileSecondaryLabelHeight = 12.dp

/** Size of the expand chevron shown next to the label of a tile that expands. */
private val CircularChevronWidth = 10.dp
private val CircularChevronHeight = 12.dp

/** Gap between the label and the expand chevron. */
private val CircularChevronStartPadding = 2.dp

@Composable
fun LargeTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    sideDrawable: Drawable?,
    colors: TileColors,
    squishiness: () -> Float,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(CommonTileDefaults.InactiveIconCornerRadius),
    textScale: () -> Float = { 1f },
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    circular: Boolean = LocalQSTileStyle.current.isCircular,
    showExpandChevron: Boolean = false,
    /**
     * Whether labels in the circular layout are painted. QQS keeps the labels in the layout as
     * transparent placeholders so its shared-element transition has the same geometry as QS.
     */
    circularLabelsVisible: Boolean = true,
    /**
     * [Expandable] used to host the launch/return transitions of the circular style. When set (and
     * [circular] is true) the icon becomes the host of those transitions, so that they morph into a
     * circle centred on the icon; the label stays outside of it and is never clipped.
     */
    expandable: Expandable? = null,
    /**
     * Interaction source the tile clickable emits its presses on, used by the circular style to
     * draw the press feedback around the icon instead of around the whole tile.
     */
    pressInteractionSource: InteractionSource? = null,
) {
    val isDualTarget = toggleClick != null
    // The circular style puts the colored icon circle above the centered label.
    if (circular) {
        CircularTileContent(
            label = label,
            secondaryLabel = secondaryLabel,
            iconProvider = iconProvider,
            colors = colors,
            iconShape = iconShape,
            isVisible = isVisible,
            accessibilityUiState = accessibilityUiState,
            squishiness = squishiness,
            modifier = modifier,
            toggleClick = toggleClick,
            onLongClick = onLongClick,
            showExpandChevron = showExpandChevron,
            circularLabelsVisible = circularLabelsVisible,
            expandable = expandable,
            pressInteractionSource = pressInteractionSource,
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = tileHorizontalArrangement(),
        modifier = modifier,
    ) {
        // Icon
        val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
        val animatedBackgroundColor by
            animateColorAsState(colors.iconBackground, label = "QSTileDualTargetBackgroundColor")
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        Box(
            modifier =
                Modifier.size(CommonTileDefaults.ToggleTargetSize).thenIf(isDualTarget) {
                    Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                        .clip(iconShape)
                        .drawBehind { drawRect(animatedBackgroundColor) }
                        // apply the squish effect after the bg is drawn
                        .verticalSquish(squishiness)
                        .combinedClickable(
                            onClick = toggleClick!!,
                            onLongClick = onLongClick,
                            onLongClickLabel = longPressLabel,
                            hapticFeedbackEnabled = false, // Haptics handled separately
                        )
                        .thenIf(accessibilityUiState != null) {
                            Modifier.semantics {
                                    accessibilityUiState as AccessibilityUiState
                                    contentDescription = accessibilityUiState.contentDescription
                                    stateDescription = accessibilityUiState.stateDescription
                                    accessibilityUiState.toggleableState?.let {
                                        toggleableState = it
                                    }
                                    role = Role.Switch
                                }
                                .sysuiResTag(TEST_TAG_TOGGLE)
                        }
                }
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { CommonTileDefaults.LargeTileIconSize },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Labels
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            isVisible = isVisible,
            modifier = Modifier.weight(1f).bounceScale(TransformOrigin(0f, .5f), textScale),
        )

        if (sideDrawable != null) {
            Image(
                painter = rememberDrawablePainter(sideDrawable),
                contentDescription = null,
                modifier = Modifier.width(SideIconWidth).height(SideIconHeight),
            )
        }
    }
}

/**
 * Circular style tile content: the state color is drawn as a circle behind the icon and the label
 * is centered right below it. The tile itself has no pill, so only the circle carries the color.
 */
@Composable
private fun CircularTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    iconShape: RoundedCornerShape,
    isVisible: () -> Boolean,
    accessibilityUiState: AccessibilityUiState?,
    squishiness: () -> Float,
    modifier: Modifier = Modifier,
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showExpandChevron: Boolean = false,
    circularLabelsVisible: Boolean = true,
    expandable: Expandable? = null,
    pressInteractionSource: InteractionSource? = null,
) {
    val isDualTarget = toggleClick != null
    val animatedBackgroundColor by
        animateColorAsState(colors.iconBackground, label = "QSCircularIconBackgroundColor")
    val animatedLabelColor by animateColorAsState(colors.label, label = "QSCircularLabelColor")
    val animatedSecondaryLabelColor by
        animateColorAsState(colors.secondaryLabel, label = "QSCircularSecondaryLabelColor")
    val focusBorderColor = MaterialTheme.colorScheme.secondary
    val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
    // The press feedback of the circular style is drawn around the icon, and clipped by it, instead
    // of being drawn around the whole tile.
    val pressIndication = LocalIndication.current

    val iconCircle: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier.size(CommonTileDefaults.ToggleTargetSize)
                    .clip(iconShape)
                    .drawBehind { drawRect(animatedBackgroundColor) }
                    .thenIf(pressInteractionSource != null) {
                        Modifier.indication(pressInteractionSource!!, pressIndication)
                    }
                    .thenIf(isDualTarget) {
                        Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                            .verticalSquish(squishiness)
                            .combinedClickable(
                                onClick = toggleClick!!,
                                onLongClick = onLongClick,
                                onLongClickLabel = longPressLabel,
                                hapticFeedbackEnabled = false, // Haptics handled separately
                            )
                            .thenIf(accessibilityUiState != null) {
                                Modifier.semantics {
                                        accessibilityUiState as AccessibilityUiState
                                        contentDescription = accessibilityUiState.contentDescription
                                        stateDescription = accessibilityUiState.stateDescription
                                        accessibilityUiState.toggleableState?.let {
                                            toggleableState = it
                                        }
                                        role = Role.Switch
                                    }
                                    .sysuiResTag(TEST_TAG_TOGGLE)
                            }
                    },
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { CommonTileDefaults.LargeTileIconSize },
                modifier = Modifier,
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true)
                .heightIn(min = CircularTileHeight)
                .padding(horizontal = CircularTileHorizontalPadding),
    ) {
        if (expandable != null) {
            // Hosting the launch/return transitions on the icon makes their bounds a square, so
            // their shape (and the shadow of the window that uses it) becomes a circle centred on
            // the icon. The labels are composed below, outside of this host, so they are never
            // clipped by it. The color is left transparent because the circle is already drawn by
            // the icon itself, and drawing it twice would change translucent colors.
            CircularIconExpandable(
                expandable = expandable,
                color = { Color.Transparent },
                shape = CircleShape,
            ) {
                iconCircle()
            }
        } else {
            iconCircle()
        }

        Box(modifier = Modifier.height(CircularTileLabelTopPadding))

        // The label (and the expand chevron, when the tile expands) is centered as one group, like
        // in the reference implementation where the chevron sits right after the label text.
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = CircularTilePrimaryLabelHeight).graphicsLayer {
                    alpha = if (circularLabelsVisible) 1f else 0f
                },
        ) {
            CircularTileLabel(
                text = label,
                color = { animatedLabelColor },
                style = CircularTileLabelStyle(),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showExpandChevron) {
                Image(
                    painter =
                        painterResource(id = InternalR.drawable.ic_chooser_group_arrow),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(animatedLabelColor),
                    modifier =
                        Modifier.padding(start = CircularChevronStartPadding)
                            .width(CircularChevronWidth)
                            .height(CircularChevronHeight),
                )
            }
        }
        // Keep the secondary line in the layout even when it is empty. Without this fixed slot,
        // tiles that provide a secondary label are centered as a taller column and their icons
        // appear higher than neighboring tiles.
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = CircularTileSecondaryLabelHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (!TextUtils.isEmpty(secondaryLabel)) {
                CircularTileLabel(
                    text = secondaryLabel ?: "",
                    color = { animatedSecondaryLabelColor },
                    style = CircularSecondaryLabelStyle(),
                    modifier =
                        Modifier.graphicsLayer {
                                alpha = if (circularLabelsVisible) 1f else 0f
                            }
                            .thenIf(
                            accessibilityUiState?.stateDescription?.contains(secondaryLabel ?: "") ==
                                true
                        ) {
                            Modifier.clearAndSetSemantics {}
                        },
                )
            }
        }
    }
}

/**
 * Label used by the circular tiles.
 *
 * Unlike [TileLabel] it wraps its content instead of filling the tile width, which keeps a short
 * label centered next to the expand chevron, and it ellipsizes long labels instead of letting them
 * overflow (and be clipped at both ends, since the text is centered).
 */
@Composable
private fun CircularTileLabel(
    text: String,
    color: ColorProducer,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        modifier = modifier,
    )
}

/** Primary label style of the circular tiles: centered, single line, 11sp. */
@Composable
private fun CircularTileLabelStyle(): TextStyle =
    MaterialTheme.typography.labelMedium.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        textAlign = TextAlign.Center,
    )

/** Secondary label style of the circular tiles: centered, single line, 9sp, regular weight. */
@Composable
private fun CircularSecondaryLabelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LargeTileLabels(
    label: String,
    secondaryLabel: String?,
    colors: TileColors,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
) {
    val animatedLabelColor by animateColorAsState(colors.label, label = "QSTileLabelColor")
    val animatedSecondaryLabelColor by
        animateColorAsState(colors.secondaryLabel, label = "QSTileSecondaryLabelColor")
    Column(verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxHeight()) {
        TileLabel(
            text = label,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = { animatedLabelColor },
            isVisible = isVisible,
        )
        if (!TextUtils.isEmpty(secondaryLabel)) {
            TileLabel(
                secondaryLabel ?: "",
                color = { animatedSecondaryLabelColor },
                style = MaterialTheme.typography.labelMedium,
                isVisible = isVisible,
                modifier =
                    Modifier.thenIf(
                        accessibilityUiState?.stateDescription?.contains(secondaryLabel ?: "") ==
                            true
                    ) {
                        Modifier.clearAndSetSemantics {}
                    },
            )
        }
    }
}

@Composable
fun SmallTileContent(
    iconProvider: Context.() -> Icon,
    color: Color,
    modifier: Modifier = Modifier,
    size: @Composable () -> Dp = { CommonTileDefaults.SmallTileIconSize },
    animateToEnd: Boolean = false,
) {
    val context = LocalContext.current
    val icon = iconProvider(context)
    val materialIcon =
        remember(icon, context) {
            (icon as? Icon.Resource)?.let { context.uwuMaterialTileIconVector(it.resId) }
        }
    val animatedColor by animateColorAsState(color, label = "QSTileIconColor")
    val sizeValue = size()
    val iconModifier =
        modifier
            .size({ sizeValue.roundToPx() }, { sizeValue.roundToPx() })
            .sysuiResTag(TEST_TAG_TILE_ICON)

    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is Icon.Loaded -> icon.drawable
                is Icon.Resource -> context.getDrawable(icon.resId)
            }
        }

    // Skip initial animation, icons should animate only as the state change
    // and not when first composed
    var shouldSkipInitialAnimation by remember { mutableStateOf(true) }
    if (materialIcon != null) {
        androidx.compose.material3.Icon(
            imageVector = materialIcon,
            contentDescription = icon.contentDescription?.load(),
            tint = animatedColor,
            modifier = iconModifier,
        )
    } else if (loadedDrawable is Animatable) {
        LaunchedEffect(Unit) { shouldSkipInitialAnimation = animateToEnd }

        val painter =
            when (icon) {
                is Icon.Resource -> {
                    val image = AnimatedImageVector.animatedVectorResource(id = icon.resId)
                    key(icon) {
                        var atEnd by remember(icon) { mutableStateOf(shouldSkipInitialAnimation) }
                        LaunchedEffect(key1 = icon.resId) { atEnd = true }

                        rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
                    }
                }

                is Icon.Loaded -> {
                    val painter = rememberDrawablePainter(loadedDrawable)

                    // rememberDrawablePainter automatically starts the animation. Using
                    // SideEffect here to immediately stop it if needed
                    DisposableEffect(painter) {
                        if (loadedDrawable is AnimatedVectorDrawable) {
                            loadedDrawable.forceAnimationOnUI()
                        }
                        if (shouldSkipInitialAnimation) {
                            loadedDrawable.stop()
                        }
                        onDispose {}
                    }

                    painter
                }
            }

        NonClippedImage(
            painter = painter,
            contentDescription = icon.contentDescription?.load(),
            colorFilter = ColorFilter.tint(color = animatedColor),
            modifier = iconModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(icon = icon, tint = animatedColor, modifier = iconModifier)
    }
}

@Composable
private fun TileLabel(
    text: String,
    color: ColorProducer,
    style: TextStyle,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
) {
    var textSize by remember { mutableIntStateOf(0) }

    val iterations = if (isVisible()) TILE_MARQUEE_ITERATIONS else 0

    BasicText(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        onTextLayout = { textSize = it.size.width },
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    if (textSize > size.width) {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (textSize > size.width) {
                        // Draw a blur over the end of the text
                        val edgeWidthPx = TileLabelBlurWidth.toPx()
                        if (layoutDirection == LayoutDirection.Rtl) {
                            drawFadedEdge(
                                startX = 0f,
                                endX = edgeWidthPx,
                                colors = listOf(Color.Transparent, Color.Black),
                            )
                        } else {
                            drawFadedEdge(
                                startX = size.width - edgeWidthPx,
                                endX = size.width,
                                colors = listOf(Color.Black, Color.Transparent),
                            )
                        }
                    }
                }
                .basicMarquee(
                    iterations = iterations,
                    initialDelayMillis = TILE_INITIAL_DELAY_MILLIS,
                ),
    )
}

fun Modifier.tileTestTag(iconOnly: Boolean): Modifier {
    return sysuiResTag(if (iconOnly) TEST_TAG_SMALL else TEST_TAG_LARGE)
}

/**
 * Apply the correct padding for large tiles
 *
 * Large tiles have a different end padding based on the content, such as if it's a dual target tile
 * or if it has a side drawable.
 */
@Composable
fun Modifier.largeTilePadding(isDualTarget: Boolean = false): Modifier {
    return padding(
        start = CommonTileDefaults.StartPadding,
        end = if (isDualTarget) CommonTileDefaults.DualTargetEndPadding else TileEndPadding,
    )
}

private fun DrawScope.drawFadedEdge(startX: Float, endX: Float, colors: List<Color>) {
    drawRect(
        topLeft = Offset(startX, 0f),
        size = Size(abs(endX - startX), size.height),
        brush = Brush.horizontalGradient(colors = colors, startX = startX, endX = endX),
        blendMode = BlendMode.DstIn,
    )
}

fun Modifier.bounceScale(
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    scale: () -> Float,
): Modifier {
    return motionTestValues { scale() exportAs TileBounceMotionTestKeys.BounceScale }
        .graphicsLayer {
            scale().let {
                scaleY = it
                scaleX = it
                this.transformOrigin = transformOrigin
            }
        }
}

@VisibleForTesting
object TileBounceMotionTestKeys {
    val BounceScale = MotionTestValueKey<Float>("bounceScale")
}

object CommonTileDefaults {
    val ActiveIconCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_active_icon_corner_radius)

    val ActiveTileCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_active_tile_corner_radius)

    val DualTargetEndPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_dual_target_end_padding)

    val InactiveIconCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_inactive_icon_corner_radius)

    val InactiveTileCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_inactive_tile_corner_radius)

    /** Dimensions for focus rings with wide corners */
    val TileDetailsEntryWideCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.focus_ring_wide_corner_radius)

    /** Dimensions for focus rings with tight corners */
    val TileDetailsEntryTightCornerRadius: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.focus_ring_tight_corner_radius)

    // The size of the icon in the tile with an icon and a label.
    val LargeTileIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (DesktopSizing.isEnabled) {
                smallIconSize
            } else {
                largeIconSize
            }

    // The size of the icon in the tile with an icon only.
    val SmallTileIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            if (DesktopSizing.isEnabled) {
                largeIconSize
            } else {
                smallIconSize
            }

    val StartPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_start_padding)

    val TileHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_tile_height)

    val ToggleTargetSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_toggle_target_size)

    val SideIconWidth = 32.dp
    val SideIconHeight = 20.dp
    val ChevronSize = 14.dp
    val TileEndPadding = 12.dp
    val TileArrangementPadding = 6.dp
    val TileLabelBlurWidth = 32.dp
    const val TILE_MARQUEE_ITERATIONS = 1
    const val TILE_INITIAL_DELAY_MILLIS = 2000

    @Composable
    fun longPressLabelSettings() = stringResource(id = R.string.accessibility_long_click_tile)

    @Composable
    fun longPressLabelMoreDetails() =
        stringResource(id = R.string.accessibility_long_click_tile_details)

    private val largeIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_large_tile_icon_size)

    private val smallIconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.common_tile_default_icon_size)
}

/** Same as Image, but it doesn't clip its content. */
@Composable
private fun NonClippedImage(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter,
) {
    val semantics =
        if (contentDescription != null) {
            Modifier.semantics {
                this.contentDescription = contentDescription
                this.role = Role.Image
            }
        } else {
            Modifier
        }

    // Explicitly use a simple Layout implementation here as Spacer squashes any non fixed
    // constraint with zero
    Layout(
        modifier
            .then(semantics)
            .paint(
                painter,
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                colorFilter = colorFilter,
            )
    ) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}
