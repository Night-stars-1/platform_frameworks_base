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

package com.android.systemui.brightness.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import com.android.systemui.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.brightness.domain.interactor.BrightnessPolicyEnforcementInteractor
import com.android.systemui.brightness.domain.interactor.ScreenBrightnessInteractor
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.ui.BrightnessWarningToast
import com.android.systemui.util.policy.PolicyRestriction
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import org.uwuaosp.systemui.qsstyle.QSStyleRepository
import org.uwuaosp.systemui.qsstyle.QSTileStyle

/**
 * View Model for a brightness slider.
 *
 * If this brightness slider supports mirroring (show on top of current activity while dragging),
 * then:
 * * [showMirror] will be true while dragging
 * * [BrightnessMirrorShowingInteractor.isShowing] will track if the mirror should show (for (other
 *   parts of SystemUI to act accordingly).
 */
class BrightnessSliderViewModel
@AssistedInject
constructor(
    private val screenBrightnessInteractor: ScreenBrightnessInteractor,
    private val brightnessPolicyEnforcementInteractor: BrightnessPolicyEnforcementInteractor,
    val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val brightnessMirrorShowingInteractorLazy: Lazy<BrightnessMirrorShowingInteractor>,
    private val falsingInteractor: FalsingInteractor,
    @Assisted val supportsMirroring: Boolean,
    private val brightnessWarningToast: BrightnessWarningToast,
    private val imageLoader: ImageLoader,
    qsStyleRepository: QSStyleRepository? = null,
    @Application private val applicationContext: Context? = null,
    @Background private val backgroundScope: CoroutineScope? = null,
) : HydratedActivatable() {

    /**
     * Active Quick Settings style, read straight from [QSStyleRepository].
     *
     * The flow is not hydrated, so the brightness slider always renders with the style that is
     * currently persisted in `Settings.Secure.UWU_QS_STYLE` (mirroring the pattern used by
     * `InfiniteGridViewModel.tileStyleFlow`).
     *
     * [qsStyleRepository] is nullable with a default so that manually constructed instances (for
     * example the test fixtures, which are outside this change's write scope) keep compiling and
     * simply fall back to the default style.
     */
    val tileStyleFlow: StateFlow<QSTileStyle> =
        qsStyleRepository?.style ?: MutableStateFlow(QSTileStyle.DEFAULT)

    private val autoBrightnessResolver: ContentResolver? = applicationContext?.contentResolver

    /**
     * Whether adaptive brightness is on, i.e. `Settings.System.SCREEN_BRIGHTNESS_MODE` is
     * [Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC].
     *
     * This is what the circular style's auto-brightness button renders. The value is observed
     * through a [ContentObserver] so it also follows changes made from the Quick Settings tile (or
     * anywhere else), and the observer is only registered while something is collecting (i.e. while
     * the circular slider is on screen).
     *
     * There is no reusable auto-brightness interactor in this build (`pods/brightness` only exposes
     * gamma brightness and the legacy `AutoBrightnessTile` talks to `Settings.System` directly), so
     * this follows the same key as that tile.
     *
     * The constructor dependencies are nullable with defaults so that manually constructed
     * instances (test fixtures, outside this change's write scope) keep compiling; they then report
     * `false` and ignore writes.
     *
     * Lazily created, so the default (non circular) style never even reads the setting.
     */
    val isAutoBrightnessEnabled: StateFlow<Boolean> by lazy { createAutoBrightnessFlow() }

    private fun createAutoBrightnessFlow(): StateFlow<Boolean> {
        val resolver = autoBrightnessResolver
        val scope = backgroundScope
        if (resolver == null || scope == null) {
            return MutableStateFlow(false)
        }
        return callbackFlow {
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(readAutoBrightnessEnabled())
                        }
                    }
                resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false,
                    observer,
                )
                trySend(readAutoBrightnessEnabled())
                awaitClose { resolver.unregisterContentObserver(observer) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), readAutoBrightnessEnabled())
    }

    /** Turns adaptive brightness on or off; the observed [isAutoBrightnessEnabled] follows. */
    fun setAutoBrightnessEnabled(enabled: Boolean) {
        val resolver = autoBrightnessResolver ?: return
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
    }

    private fun readAutoBrightnessEnabled(): Boolean =
        autoBrightnessResolver?.let {
            Settings.System.getInt(
                it,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            ) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        } ?: false

    init {
        if (supportsMirroring) {
            // Create eagerly only if supported
            brightnessMirrorShowingInteractorLazy.get()
        }
    }

    val currentBrightness by
        screenBrightnessInteractor.gammaBrightness.hydratedStateOf(initialValue)

    val maxBrightness = screenBrightnessInteractor.maxGammaBrightness
    val minBrightness = screenBrightnessInteractor.minGammaBrightness

    val policyRestriction = brightnessPolicyEnforcementInteractor.brightnessPolicyRestriction

    fun showPolicyRestrictionDialog(restriction: PolicyRestriction.Restricted) {
        brightnessPolicyEnforcementInteractor.startAdminSupportDetailsDialog(restriction)
    }

    val brightnessOverriddenByWindow = screenBrightnessInteractor.brightnessOverriddenByWindow

    fun showToast(viewContext: Context, @StringRes resId: Int) {
        if (brightnessWarningToast.isToastActive()) {
            return
        }
        brightnessWarningToast.show(viewContext, resId)
    }

    fun emitBrightnessTouchForFalsing() {
        falsingInteractor.isFalseTouch(Classifier.BRIGHTNESS_SLIDER)
    }

    suspend fun loadImage(@DrawableRes resId: Int, context: Context): Icon.Loaded? {
        return withTimeoutOrNull(500L) {
            imageLoader
                .loadDrawable(
                    android.graphics.drawable.Icon.createWithResource(context, resId),
                    context = context,
                    maxHeight = 200,
                    maxWidth = 200,
                )
                ?.asIcon(null, resId)
        }
    }

    /**
     * As a brightness slider is dragged, the corresponding events should be sent using this method.
     */
    suspend fun onDrag(drag: Drag) {
        when (drag) {
            is Drag.Dragging -> screenBrightnessInteractor.setTemporaryBrightness(drag.brightness)
            is Drag.Stopped -> screenBrightnessInteractor.setBrightness(drag.brightness)
        }
    }

    fun setIsDragging(dragging: Boolean) {
        // The circular style draws the slider inline and has no floating mirror in the reference
        // layout. Showing the mirror here made the whole panel dim behind the mirror window while
        // dragging, which is what got reported as the brightness bar background flickering.
        if (supportsMirroring && !tileStyleFlow.value.isCircular) {
            brightnessMirrorShowingInteractorLazy.get().setMirrorShowing(dragging)
        }
    }

    val showMirror by
        if (supportsMirroring) {
                brightnessMirrorShowingInteractorLazy.get().isShowing
            } else {
                MutableStateFlow(false)
            }
            .hydratedStateOf()

    @AssistedFactory
    interface Factory {
        fun create(supportsMirroring: Boolean): BrightnessSliderViewModel
    }

    companion object {
        val initialValue = GammaBrightness(-1)

        private val icons =
            BrightnessIcons(
                brightnessLow = R.drawable.ic_brightness_low,
                brightnessMid = R.drawable.ic_brightness_medium,
                brightnessHigh = R.drawable.ic_brightness_full,
            )

        @DrawableRes
        fun getIconForPercentage(@FloatRange(0.0, 100.0) percentage: Float): Int {
            return when {
                percentage <= 20f -> icons.brightnessLow
                percentage >= 80f -> icons.brightnessHigh
                else -> icons.brightnessMid
            }
        }
    }
}

fun BrightnessSliderViewModel.Factory.create() = create(supportsMirroring = true)

/** Represents a drag event in a brightness slider. */
sealed interface Drag {
    val brightness: GammaBrightness

    @JvmInline value class Dragging(override val brightness: GammaBrightness) : Drag

    @JvmInline value class Stopped(override val brightness: GammaBrightness) : Drag
}

private data class BrightnessIcons(
    @DrawableRes val brightnessLow: Int,
    @DrawableRes val brightnessMid: Int,
    @DrawableRes val brightnessHigh: Int,
)
