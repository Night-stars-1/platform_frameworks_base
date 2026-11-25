/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class MomentArcStartable
@Inject
constructor(
    private val context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val controller: MomentArcController,
    private val commandQueue: CommandQueue,
    private val userTracker: UserTracker,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Main private val mainExecutor: Executor,
    @Main private val mainHandler: Handler,
) : CoreStartable {
    private val commandCallbacks = object : CommandQueue.Callbacks {
        override fun showMomentAllApps(displayId: Int, userId: Int) {
            if (userId != userTracker.userId || displayId != context.displayId) {
                Log.w("MomentArc", "Ignoring apps request for display=$displayId user=$userId; " +
                    "current display=${context.displayId} user=${userTracker.userId}")
                return
            }
            Log.i("MomentArc", "Received all-apps command display=$displayId user=$userId")
            controller.showAllApps()
        }
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                controller.onSettingsChanged()
            }
        }

    private val gestureReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SHOW ->
                        controller.show(
                            isLeft = intent.getBooleanExtra(EXTRA_IS_LEFT, true),
                            initialTouchX = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                            initialTouchY = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                        )
                    ACTION_UPDATE ->
                        controller.onTouchCoordinates(
                            x = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                            y = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                            isUp = intent.getBooleanExtra(EXTRA_IS_UP, false),
                            isCancelled = intent.getBooleanExtra(EXTRA_IS_CANCELLED, false),
                        )
                    ACTION_DISMISS -> controller.hide()
                }
            }
        }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = controller.hide(immediate = true)
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onBeforeUserSwitching(newUser: Int) = controller.hide(immediate = true)

            override fun onUserChanged(newUser: Int, userContext: Context) = controller.hide(immediate = true)
        }

    private val wakefulnessObserver =
        object : WakefulnessLifecycle.Observer {
            override fun onStartedGoingToSleep() {
                controller.hide(immediate = true)
            }
        }

    override fun start() {
        commandQueue.addCallback(commandCallbacks)
        broadcastDispatcher.registerReceiver(
            gestureReceiver,
            IntentFilter().apply {
                addAction(ACTION_SHOW)
                addAction(ACTION_UPDATE)
                addAction(ACTION_DISMISS)
            },
            mainExecutor,
            UserHandle.ALL,
            Context.RECEIVER_EXPORTED,
            Manifest.permission.STATUS_BAR,
        )
        broadcastDispatcher.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            mainExecutor,
            UserHandle.ALL,
            Context.RECEIVER_NOT_EXPORTED,
        )
        userTracker.addCallback(userCallback, mainExecutor)
        wakefulnessLifecycle.addObserver(wakefulnessObserver)
        listOf(Settings.Secure.MOMENT_ENABLED, Settings.Secure.MOMENT_ARC_GESTURE_ENABLED)
            .forEach { setting ->
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(setting),
                    false,
                    settingsObserver,
                    UserHandle.USER_ALL,
                )
            }
    }

    private companion object {
        const val ACTION_SHOW = "com.android.systemui.action.SHOW_MOMENT_ARC"
        const val ACTION_UPDATE = "com.android.systemui.action.UPDATE_MOMENT_ARC_TOUCH"
        const val ACTION_DISMISS = "com.android.systemui.action.DISMISS_MOMENT_ARC"
        const val EXTRA_IS_LEFT = "is_left"
        const val EXTRA_TOUCH_X = "touch_x"
        const val EXTRA_TOUCH_Y = "touch_y"
        const val EXTRA_IS_UP = "is_up"
        const val EXTRA_IS_CANCELLED = "is_cancelled"
    }
}
