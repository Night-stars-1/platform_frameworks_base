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

package com.android.systemui.brightness.ui.compose

import android.content.Context
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconAppearSpec
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconDisappearSpec
import com.android.systemui.brightness.ui.compose.InternalDimensions.IconPadding
import com.android.systemui.brightness.ui.compose.InternalDimensions.SliderTrackRoundedCorner
import com.android.systemui.brightness.ui.compose.InternalDimensions.ThumbTrackGapSize
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.colors.SystemUISliderColors
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@VisibleForTesting
fun BrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    iconResProvider: (Float) -> Int,
    imageLoader: suspend (Int, Context) -> Icon.Loaded?,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    overriddenByAppState: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showToast: () -> Unit = {},
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
    /**
     * When true (`Settings.Secure.UWU_QS_STYLE` == 1) the slider is drawn like the reference ROM
     * brightness bar. The default (`false`) path keeps the original AOSP implementation as is.
     */
    circular: Boolean = false,
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted
    val contentDescription = stringResource(R.string.accessibility_brightness)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel =
        rememberViewModel(traceName = "SliderHapticsViewModel") {
            hapticsViewModelFactory.create(
                interactionSource,
                floatValueRange,
                Orientation.Horizontal,
                SliderHapticFeedbackConfig(
                    maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                ),
                SeekableSliderTrackerConfig(),
            )
        }
    // Circular style: the reference's unfilled track is the *opaque* light surface color
    // (`?attr/offStateColor` = `@*android:color/surface_light`, i.e. the near-white base surface),
    // not the translucent `surfaceEffect1` overlay used by the default AOSP slider. Measured on the
    // device the overlay renders ~(205,215,223) / lum 0.86 over the QS wallpaper, i.e. only +0.12
    // over the background, while the reference pill is (255,251,255) / lum 0.99.
    val colors =
        if (circular) {
            SystemUISliderColors.Defaults.copy(
                inactiveTrackColor = MaterialTheme.colorScheme.surface
            )
        } else {
            SystemUISliderColors.Defaults
        }

    // The value state is recreated every time gammaValue changes, so we recreate this derivedState
    // We have to use value as that's the value that changes when the user is dragging (gammaValue
    // is always the starting value: actual (not temporary) brightness).
    val iconRes by
        remember(gammaValue, valueRange) {
            derivedStateOf {
                val percentage =
                    (value - valueRange.first) * 100f / (valueRange.last - valueRange.first)
                iconResProvider(percentage)
            }
        }
    val context = LocalContext.current
    val painter: Painter by
        produceState<Painter>(
            initialValue = ColorPainter(Color.Transparent),
            key1 = iconRes,
            key2 = context,
        ) {
            val icon: Icon.Loaded? = imageLoader(iconRes, context)
            if (icon != null) {
                val bitmap = icon.drawable.toBitmap()?.asImageBitmap()
                if (bitmap != null) {
                    this@produceState.value = BitmapPainter(bitmap)
                }
            }
        }
    val activeIconColor = colors.activeTickColor
    val iconSize = dimensions.iconSize
    val inactiveIconColor = colors.inactiveTickColor
    // Offset from the right
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit = remember {
        { offset, color, alpha ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                translate(offset.x - IconPadding.toPx() - iconSize.toSize().width, offset.y) {
                    with(painter) {
                        draw(
                            iconSize.toSize(),
                            colorFilter = ColorFilter.tint(color),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }

    Slider(
        value = animatedValue,
        valueRange = floatValueRange,
        enabled = enabled,
        colors = colors,
        onValueChange = {
            if (enabled) {
                if (!overriddenByAppState) {
                    hapticsViewModel.onValueChange(it)
                    value = it.toInt()
                    onDrag(value)
                }
            }
        },
        onValueChangeFinished = {
            if (enabled) {
                if (!overriddenByAppState) {
                    hapticsViewModel.onValueChangeEnded()
                    onStop(value)
                }
            }
        },
        modifier =
            modifier
                .sysuiResTag("slider")
                .semantics(mergeDescendants = true) {
                    this.text = AnnotatedString(contentDescription)
                }
                .sliderPercentage {
                    (value - valueRange.first).toFloat() / (valueRange.last - valueRange.first)
                }
                .thenIf(isRestricted) {
                    Modifier.clickable {
                        if (restriction is PolicyRestriction.Restricted) {
                            onRestrictedClick(restriction)
                        }
                    }
                },
        interactionSource = interactionSource,
        thumb = {
            if (circular) {
                // Reference slider has no thumb: the round cap of the fill (with the icon in it)
                // is what the user drags. Keep an invisible, fixed size spacer so the track is
                // laid out exactly like the default one (the Material3 slider always reserves the
                // thumb width).
                Spacer(Modifier.size(dimensions.thumbWidth, dimensions.trackHeight))
            } else {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                    colors = colors,
                )
            }
        },
        track = { sliderState ->
            if (circular) {
                CircularSliderTrack(
                    sliderState = sliderState,
                    colors = colors,
                    painter = painter,
                    enabled = enabled,
                    iconSize = dimensions.iconSize,
                    trackHeight = dimensions.trackHeight,
                )
                return@Slider
            }

            // ---- Default (Settings.Secure.UWU_QS_STYLE == 0) implementation, unchanged. ----
            var showIconActive by remember { mutableStateOf(true) }
            val iconActiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 1f,
                    typeConverter = Float.VectorConverter,
                    label = "iconActiveAlpha",
                )
            }

            val iconInactiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 0f,
                    typeConverter = Float.VectorConverter,
                    label = "iconInactiveAlpha",
                )
            }

            LaunchedEffect(iconActiveAlphaAnimatable, iconInactiveAlphaAnimatable, showIconActive) {
                if (showIconActive) {
                    launch { iconActiveAlphaAnimatable.appear() }
                    launch { iconInactiveAlphaAnimatable.disappear() }
                } else {
                    launch { iconActiveAlphaAnimatable.disappear() }
                    launch { iconInactiveAlphaAnimatable.appear() }
                }
            }

            SliderDefaults.Track(
                sliderState = sliderState,
                modifier =
                    Modifier.motionTestValues {
                            iconActiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.ActiveIconAlpha
                            iconInactiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.InactiveIconAlpha
                        }
                        .height(dimensions.trackHeight)
                        .drawWithContent {
                            drawContent()

                            val yOffset = size.height / 2 - iconSize.toSize().height / 2
                            val activeTrackStart = 0f
                            val activeTrackEnd =
                                size.width * sliderState.coercedValueAsFraction -
                                    ThumbTrackGapSize.toPx()
                            val inactiveTrackStart = activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                            val inactiveTrackEnd = size.width

                            val activeTrackWidth = activeTrackEnd - activeTrackStart
                            val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart

                            if (
                                iconSize.toSize().width <
                                    inactiveTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = false
                                trackIcon(
                                    Offset(inactiveTrackEnd, yOffset),
                                    inactiveIconColor,
                                    iconInactiveAlphaAnimatable.value,
                                )
                            } else if (
                                iconSize.toSize().width < activeTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = true
                                trackIcon(
                                    Offset(activeTrackEnd, yOffset),
                                    activeIconColor,
                                    iconActiveAlphaAnimatable.value,
                                )
                            }
                        },
                trackCornerSize = SliderTrackRoundedCorner,
                trackInsideCornerSize = 2.dp,
                drawStopIndicator = null,
                thumbTrackGapSize = ThumbTrackGapSize,
                colors = colors,
            )
        },
    )

    val currentShowToast by rememberUpdatedState(showToast)
    // Showing the warning toast if the current running app window has controlled the
    // brightness value.
    LaunchedEffect(interactionSource, overriddenByAppState) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && overriddenByAppState) {
                currentShowToast()
            }
        }
    }
}

/**
 * Track of the circular (`Settings.Secure.UWU_QS_STYLE == 1`) brightness slider.
 *
 * Reproduces the visible features of the reference ROM (Android 13 based, Java/XML):
 * `brightness_progress_drawable.xml` + `brightness_progress_full_drawable.xml`, whose geometry is
 * driven by `rounded_slider_height` (48dp), `rounded_slider_corner_radius` (24dp),
 * `rounded_slider_icon_size` (20dp) and `rounded_slider_icon_inset` (14dp).
 * * the whole bar is a pill (corner radius = half the height);
 * * the unfilled part uses the inactive track role, the filled part the active track role;
 * * the fill is never narrower than a full circle (`RoundedCornerProgressDrawable` adds `height /
 *   2` to the progress width and clamps it to at least the track height);
 * * the brightness icon is tinted with the active tick role and centered on the leading cap of the
 *   fill, which puts its edge `radius - iconSize / 2` away from the fill's edge - exactly the
 *   reference `rounded_slider_icon_inset` (24 - 10 = 14dp);
 * * there is no separate thumb: the icon inside the cap rides the finger.
 */
@Composable
private fun CircularSliderTrack(
    sliderState: SliderState,
    colors: SliderColors,
    painter: Painter,
    enabled: Boolean,
    iconSize: DpSize,
    trackHeight: Dp,
) {
    val activeTrackColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveTrackColor =
        if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val iconColor = if (enabled) colors.activeTickColor else colors.disabledActiveTickColor

    Box(
        modifier =
            Modifier.fillMaxWidth().height(trackHeight).drawBehind {
                val radius = size.height / 2f
                val iconWidth = iconSize.width.toPx()
                val iconHeight = iconSize.height.toPx()
                val rtl = layoutDirection == LayoutDirection.Rtl

                drawRoundRect(
                    color = inactiveTrackColor,
                    size = size,
                    cornerRadius = CornerRadius(radius),
                )

                // Mirrors RoundedCornerProgressDrawable: width = progress * width + radius,
                // clamped to [height, width].
                val progress = size.width * sliderState.coercedValueAsFraction
                val minFillWidth = (radius * 2f).coerceAtMost(size.width)
                val fillWidth = (progress + radius).coerceIn(minFillWidth, size.width)
                val fillLeft = if (rtl) size.width - fillWidth else 0f
                drawRoundRect(
                    color = activeTrackColor,
                    topLeft = Offset(fillLeft, 0f),
                    size = Size(fillWidth, size.height),
                    cornerRadius = CornerRadius(radius),
                )

                // Center of the leading cap of the fill: the icon sits directly under the finger.
                val iconCenterX = if (rtl) fillLeft + radius else fillLeft + fillWidth - radius
                translate(
                    left = iconCenterX - iconWidth / 2f,
                    top = size.height / 2f - iconHeight / 2f,
                ) {
                    with(painter) {
                        draw(
                            size = iconSize.toSize(),
                            colorFilter = ColorFilter.tint(iconColor),
                            alpha = 1f,
                        )
                    }
                }
            }
    )
}

/**
 * Auto-brightness toggle of the circular style, matching the reference ROM's right hand button
 * (AOSP 13 `quick_settings_brightness_dialog.xml` `@id/brightness_icon`): a 48dp circle placed
 * after the slider, with the `ic_qs_autobrightness` glyph.
 *
 * Off uses the light `surface` role with an `onSurface` glyph, on uses `primary` with `onPrimary`
 * (the same pairing as the reference's `bg_qs_brightness_auto_off` / `_on`).
 */
@Composable
private fun AutoBrightnessButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val description = stringResource(R.string.quick_settings_autobrightness_label)
    Box(
        modifier =
            modifier
                .size(CircularDimensions.AutoBrightnessButtonSize)
                .clip(CircleShape)
                .background(background)
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
                .semantics { contentDescription = description }
                .sysuiResTag("brightness_auto_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_qs_autobrightness),
            contentDescription = null,
            tint = contentColor,
        )
    }
}

private fun Modifier.sliderBackground(
    backgroundFrameSize: DpSize,
    backgroundRoundedCorner: Dp,
    color: Color,
) = drawWithCache {
    val offsetAround = backgroundFrameSize.toSize()
    val newSize = Size(size.width + 2 * offsetAround.width, size.height + 2 * offsetAround.height)
    val offset = Offset(-offsetAround.width, -offsetAround.height)
    val cornerRadius = CornerRadius(backgroundRoundedCorner.toPx())
    onDrawBehind {
        drawRoundRect(color = color, topLeft = offset, size = newSize, cornerRadius = cornerRadius)
    }
}

@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColors: ContainerColors,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) { // Ignore initial negative value.
        return
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overriddenByAppState by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }

    DisposableEffectWithLifecycle(Unit) {
        enabled = true
        onDispose {
            dragging = false
            viewModel.setIsDragging(false)
            enabled = false
        }
    }

    val isRestricted = restriction is PolicyRestriction.Restricted
    // Raw style flow, observed directly so the slider always renders the currently persisted style
    // (same pattern as InfiniteGridLayout / QuickQuickSettings).
    val tileStyle by viewModel.tileStyleFlow.collectAsState()
    val circular = tileStyle.isCircular

    // Use dragging instead of viewModel.showMirror so the color starts changing as soon as the
    // dragging state changes. If not, we may be waiting for the background to finish fading in
    // when stopping dragging.
    //
    // The circular style draws no background frame: the reference bar is a bare capsule, and the
    // frame fading in and out on every drag was reported as an abnormal background flicker.
    val containerColor by
        animateColorAsState(
            if (!circular && dragging && viewModel.supportsMirroring) {
                containerColors.mirrorColor
            } else {
                containerColors.idleColor
            }
        )

    // The circular style has its own geometry (pill track, no thumb, icon inside the fill).
    val sliderDimensions = if (circular) BrightnessSliderDimensions.Circular else dimensions
    Box(
        modifier =
            modifier
                .thenIf(!circular) {
                    Modifier.padding(vertical = { sliderDimensions.verticalPadding.roundToPx() })
                }
                .fillMaxWidth()
                .sysuiResTag("brightness_slider")
    ) {
        BrightnessSlider(
            enabled = enabled && !isRestricted,
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            iconResProvider = BrightnessSliderViewModel::getIconForPercentage,
            imageLoader = viewModel::loadImage,
            restriction = restriction,
            onRestrictedClick = viewModel::showPolicyRestrictionDialog,
            onDrag = {
                viewModel.setIsDragging(true)
                dragging = true
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) }
            },
            onStop = {
                viewModel.setIsDragging(false)
                dragging = false
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) }
            },
            modifier =
                Modifier.borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize =
                            CornerSize(
                                if (circular) sliderDimensions.trackHeight / 2
                                else SliderTrackRoundedCorner
                            ),
                    )
                    .then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                    .sliderBackground(
                        DpSize(
                            sliderDimensions.backgroundFrameWidth,
                            sliderDimensions.backgroundFrameHeight,
                        ),
                        sliderDimensions.backgroundRoundedCorner,
                        containerColor,
                    )
                    .fillMaxWidth()
                    .thenIf(circular) {
                        // Circular style reserves the end of the row for the auto-brightness button
                        // (reference: `layout_marginStart="8dp"` + a 48dp round button).
                        Modifier.padding(
                            end = { CircularDimensions.AutoBrightnessButtonTotalWidth.roundToPx() }
                        )
                    }
                    .pointerInteropFilter {
                        if (
                            it.actionMasked == MotionEvent.ACTION_UP ||
                                it.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            viewModel.emitBrightnessTouchForFalsing()
                        }
                        false
                    }
                    // The auto-brightness button is centered in this Box. Center the track the same
                    // way, so both stay on the same axis even if the container is briefly laid out
                    // with a different height (for example while the shade expands into QS).
                    .align(Alignment.CenterStart),
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            overriddenByAppState = overriddenByAppState,
            showToast = {
                viewModel.showToast(
                    context,
                    com.android.internal.R.string.brightness_unable_adjust_msg,
                )
            },
            dimensions = sliderDimensions,
            circular = circular,
        )
        if (circular) {
            // Reference row: [slider] [8dp] [48dp auto-brightness button].
            val autoBrightnessEnabled by viewModel.isAutoBrightnessEnabled.collectAsState()
            AutoBrightnessButton(
                checked = autoBrightnessEnabled,
                onCheckedChange = viewModel::setAutoBrightnessEnabled,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

data class ContainerColors(val idleColor: Color, val mirrorColor: Color) {
    companion object {
        fun singleColor(color: Color) = ContainerColors(color, color)

        val defaultContainerColor: Color
            @Composable @ReadOnlyComposable get() = colorResource(R.color.shade_panel_fallback)
    }
}

data class BrightnessSliderDimensions(
    val iconSize: DpSize,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackHeight: Dp,
    val verticalPadding: Dp,
    val backgroundRoundedCorner: Dp,
    val backgroundFrameWidth: Dp,
    val backgroundFrameHeight: Dp,
) {
    companion object {
        val Default =
            BrightnessSliderDimensions(
                iconSize = DpSize(28.dp, 28.dp),
                thumbHeight = 52.dp,
                thumbWidth = 4.dp,
                trackHeight = 40.dp,
                verticalPadding = 6.dp,
                backgroundRoundedCorner = 24.dp,
                backgroundFrameWidth = 10.dp,
                backgroundFrameHeight = 6.dp,
            )

        /**
         * Geometry of the circular style (`Settings.Secure.UWU_QS_STYLE == 1`), taken verbatim from
         * the reference ROM's `rounded_slider_*` dimensions:
         * * `rounded_slider_height` = 48dp -> [trackHeight];
         * * `rounded_slider_corner_radius` = 24dp, i.e. half the height, so the track is a pill
         *   (the container derives it from [trackHeight]);
         * * `rounded_slider_icon_size` = 20dp -> [iconSize];
         * * `rounded_slider_icon_inset` = 14dp, which is `corner radius - icon size / 2` and is
         *   therefore derived by [CircularSliderTrack];
         * * `rounded_slider_background_padding` = 8dp ->
         *   [backgroundFrameWidth]/[backgroundFrameHeight];
         * * `rounded_slider_background_rounded_corner` = 32dp -> [backgroundRoundedCorner].
         *
         * [thumbWidth]/[thumbHeight] only reserve layout space: the circular style has no visible
         * thumb (see [CircularSliderTrack]).
         */
        val Circular =
            BrightnessSliderDimensions(
                iconSize = DpSize(20.dp, 20.dp),
                thumbHeight = 48.dp,
                thumbWidth = 4.dp,
                trackHeight = 48.dp,
                verticalPadding = 6.dp,
                backgroundRoundedCorner = 32.dp,
                backgroundFrameWidth = 8.dp,
                backgroundFrameHeight = 8.dp,
            )
    }
}

private object InternalDimensions {
    val SliderTrackRoundedCorner = 12.dp
    val IconPadding = 6.dp
    val ThumbTrackGapSize = 6.dp
}

private object CircularDimensions {
    /** Reference `bg_qs_brightness_auto_on` = 48x48dp circle (also `rounded_slider_height`). */
    val AutoBrightnessButtonSize = 48.dp

    /** Reference `quick_settings_brightness_dialog.xml` `android:layout_marginStart="8dp"`. */
    val AutoBrightnessButtonGap = 8.dp

    /** Horizontal space the button plus its gap take away from the slider. */
    val AutoBrightnessButtonTotalWidth = AutoBrightnessButtonSize + AutoBrightnessButtonGap
}

private object AnimationSpecs {
    val IconAppearSpec = tween<Float>(durationMillis = 100, delayMillis = 33)
    val IconDisappearSpec = tween<Float>(durationMillis = 50)
}

private suspend fun Animatable<Float, AnimationVector1D>.appear() =
    animateTo(targetValue = 1f, animationSpec = IconAppearSpec)

private suspend fun Animatable<Float, AnimationVector1D>.disappear() =
    animateTo(targetValue = 0f, animationSpec = IconDisappearSpec)

@VisibleForTesting
object BrightnessSliderMotionTestKeys {
    val ActiveIconAlpha = MotionTestValueKey<Float>("activeIconAlpha")
    val InactiveIconAlpha = MotionTestValueKey<Float>("inactiveIconAlpha")
}
