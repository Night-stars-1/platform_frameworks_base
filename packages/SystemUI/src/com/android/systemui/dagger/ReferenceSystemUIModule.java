1:/*
2: * Copyright (C) 2019 The Android Open Source Project
3: *
4: * Licensed under the Apache License, Version 2.0 (the "License");
5: * you may not use this file except in compliance with the License.
6: * You may obtain a copy of the License at
7: *
8: *      http://www.apache.org/licenses/LICENSE-2.0
9: *
10: * Unless required by applicable law or agreed to in writing, software
11: * distributed under the License is distributed on an "AS IS" BASIS,
12: * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
13: * See the License for the specific language governing permissions and
14: * limitations under the License.
15: */
16:
17:package com.android.systemui.dagger;
18:
19:import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
20:import static com.android.systemui.Dependency.LEAK_REPORT_EMAIL_NAME;
21:
22:import android.content.Context;
23:import android.hardware.SensorPrivacyManager;
24:
25:import com.android.keyguard.KeyguardViewController;
26:import com.android.systemui.CoreStartable;
27:import com.android.systemui.Flags;
28:import com.android.systemui.ScreenDecorationsModule;
29:import com.android.systemui.accessibility.AccessibilityModule;
30:import com.android.systemui.accessibility.SystemActionsModule;
31:import com.android.systemui.accessibility.data.repository.AccessibilityRepositoryModule;
32:import com.android.systemui.actioncorner.ActionCornerModule;
33:import com.android.systemui.battery.BatterySaverModule;
34:import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayOverrideModule;
35:import com.android.systemui.communal.posturing.dagger.PosturingModule;
36:import com.android.systemui.contextualcursor.ContextualCursorModule;
37:import com.android.systemui.cursorposition.CursorPositionModule;
38:import com.android.systemui.display.dagger.ReferenceSysUIDisplaySubcomponent;
39:import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent;
40:import com.android.systemui.display.data.repository.DisplayPhoneModule;
41:import com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel;
42:import com.android.systemui.externaldesktop.ExternalDesktopModule;
43:import com.android.systemui.dock.DockManager;
44:import com.android.systemui.dock.DockManagerImpl;
45:import com.android.systemui.doze.DozeHost;
46:import com.android.systemui.dreams.suppression.dagger.NoOpActivityRecognitionModule;
47:import com.android.systemui.education.dagger.ContextualEducationModule;
48:import com.android.systemui.emergency.EmergencyGestureModule;
49:import com.android.systemui.inputdevice.tutorial.KeyboardTouchpadTutorialModule;
50:import com.android.systemui.inputmethod.ImeSwitcherMenuModule;
import com.android.systemui.island.IslandStartupModule;
51:import com.android.systemui.keyboard.shortcut.ShortcutHelperModule;
52:import com.android.systemui.keyguard.dagger.KeyguardModule;
53:import com.android.systemui.keyguard.ui.view.layout.blueprints.KeyguardBlueprintModule;
54:import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSectionsModule;
55:import com.android.systemui.lowlight.dagger.ScreenAwareLightModeMonitorModule;
56:import com.android.systemui.media.dagger.MediaModule;
57:import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionCli;
58:import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
59:import com.android.systemui.minmode.MinModeManager;
60:import com.android.systemui.minmode.MinModeManagerImpl;
61:import com.android.systemui.moment.arc.MomentArcModule;
62:import com.android.systemui.navigationbar.NavigationBarControllerModule;
63:import com.android.systemui.navigationbar.gestural.GestureModule;
64:import com.android.systemui.plugins.qs.QSFactory;
65:import com.android.systemui.power.dagger.PowerModule;
66:import com.android.systemui.prism.PrismGestureModule;
67:import com.android.systemui.qs.QSFragmentStartableModule;
68:import com.android.systemui.qs.dagger.QSModule;
69:import com.android.systemui.qs.tileimpl.QSFactoryImpl;
70:import com.android.systemui.reardisplay.RearDisplayModule;
71:import com.android.systemui.recents.Recents;
72:import com.android.systemui.recents.RecentsImplementation;
73:import com.android.systemui.recents.RecentsModule;
74:import com.android.systemui.rotationlock.RotationLockModule;
75:import com.android.systemui.rotationlock.RotationLockNewModule;
76:import com.android.systemui.scene.SceneContainerFrameworkModule;
77:import com.android.systemui.screencapture.common.ScreenCaptureModule;
78:import com.android.systemui.screenshot.ReferenceScreenshotModule;
79:import com.android.systemui.settings.MultiUserUtilsModule;
80:import com.android.systemui.settings.UserTracker;
81:import com.android.systemui.shade.NotificationShadeWindowControllerImpl;
82:import com.android.systemui.shade.ShadeModule;
83:import com.android.systemui.startable.Dependencies;
84:import com.android.systemui.statusbar.CommandQueue;
85:import com.android.systemui.statusbar.NotificationLockscreenUserManager;
86:import com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl;
87:import com.android.systemui.statusbar.NotificationShadeWindowController;
88:import com.android.systemui.statusbar.SysuiStatusBarStateController;
89:import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
90:import com.android.systemui.statusbar.dagger.StartCentralSurfacesModule;
91:import com.android.systemui.statusbar.notification.dagger.ReferenceNotificationsModule;
92:import com.android.systemui.statusbar.notification.headsup.HeadsUpModule;
93:import com.android.systemui.statusbar.phone.CentralSurfaces;
94:import com.android.systemui.statusbar.phone.DozeServiceHost;
95:import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
96:import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
97:import com.android.systemui.statusbar.policy.AospPolicyModule;
98:import com.android.systemui.statusbar.policy.DeviceProvisionedController;
99:import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;
100:import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
101:import com.android.systemui.statusbar.policy.IndividualSensorPrivacyControllerImpl;
102:import com.android.systemui.statusbar.policy.SensorPrivacyController;
103:import com.android.systemui.statusbar.policy.SensorPrivacyControllerImpl;
104:import com.android.systemui.statusbar.policy.dagger.SmartRepliesInflationModule;
105:import com.android.systemui.toast.ToastModule;
106:import com.android.systemui.topwindoweffects.dagger.TopLevelWindowEffectsModule;
107:import com.android.systemui.touchpad.tutorial.TouchpadTutorialModule;
108:import com.android.systemui.unfold.SysUIUnfoldStartableModule;
109:import com.android.systemui.unfold.UnfoldTransitionModule;
110:import com.android.systemui.util.kotlin.SysUICoroutinesModule;
111:import com.android.systemui.volume.dagger.VolumeModule;
112:import com.android.systemui.wallpapers.dagger.WallpaperModule;
113:
114:import dagger.Binds;
115:import dagger.Module;
116:import dagger.Provides;
117:import dagger.multibindings.ClassKey;
118:import dagger.multibindings.IntoMap;
119:
120:import java.util.Optional;
121:import java.util.Set;
122:
123:import javax.inject.Named;
124:import javax.inject.Provider;
125:
126:/**
127: * A dagger module for injecting default implementations of components of System UI.
128: *
129: * Variants of SystemUI should make a copy of this, include it in their component, and customize it
130: * as needed.
131: *
132: * This module might alternatively be named `AospSystemUIModule`, `PhoneSystemUIModule`,
133: * or `BasicSystemUIModule`.
134: *
135: * Nothing in the module should be strictly required. Each piece should either be swappable with
136: * a different implementation or entirely removable.
137: *
138: * This is different from {@link SystemUIModule} which should be used for pieces of required
139: * SystemUI code that variants of SystemUI _must_ include to function correctly.
140: */
141:@Module(includes = {
142:        AccessibilityModule.class,
143:        AccessibilityRepositoryModule.class,
144:        AospPolicyModule.class,
145:        BatterySaverModule.class,
146:        CentralSurfacesModule.class,
147:        ClipboardOverlayOverrideModule.class,
148:        ConnectingDisplayViewModel.StartableModule.class,
149:        ImeSwitcherMenuModule.class,
150:        DisplayPhoneModule.class,
151:        EmergencyGestureModule.class,
152:        ExternalDesktopModule.class,
153:        GestureModule.class,
154:        HeadsUpModule.class,
155:        KeyguardModule.class,
156:        KeyguardBlueprintModule.class,
157:        KeyguardSectionsModule.class,
158:        KeyboardTouchpadTutorialModule.class,
159:        MediaModule.class,
160:        MomentArcModule.class,
        IslandStartupModule.class,
161:        MediaMuteAwaitConnectionCli.StartableModule.class,
162:        MultiUserUtilsModule.class,
163:        NavigationBarControllerModule.class,
164:        NearbyMediaDevicesManager.StartableModule.class,
165:        NoOpActivityRecognitionModule.class,
166:        PowerModule.class,
167:        PrismGestureModule.class,
168:        QSFragmentStartableModule.class,
169:        QSModule.class,
170:        RearDisplayModule.class,
171:        RecentsModule.class,
172:        ReferenceNotificationsModule.class,
173:        PosturingModule.class,
174:        ReferenceScreenshotModule.class,
175:        RotationLockModule.class,
176:        RotationLockNewModule.class,
177:        ScreenCaptureModule.class,
178:        ScreenDecorationsModule.class,
179:        StatusBarPhoneModule.class,
180:        SystemActionsModule.class,
181:        ShadeModule.class,
182:        StartCentralSurfacesModule.class,
183:        SceneContainerFrameworkModule.class,
184:        ScreenAwareLightModeMonitorModule.class,
185:        SysUICoroutinesModule.class,
186:        SysUIUnfoldStartableModule.class,
187:        UnfoldTransitionModule.Startables.class,
188:        ToastModule.class,
189:        TopLevelWindowEffectsModule.class,
190:        TouchpadTutorialModule.class,
191:        VolumeModule.class,
192:        WallpaperModule.class,
193:        ShortcutHelperModule.class,
194:        SmartRepliesInflationModule.class,
195:        ContextualEducationModule.class,
196:        ActionCornerModule.class,
197:        CursorPositionModule.class,
198:        ContextualCursorModule.class,
199:}, subcomponents = {
200:        ReferenceSysUIDisplaySubcomponent.class
201:})
202:public abstract class ReferenceSystemUIModule {
203:
204:    @Binds
205:    abstract SystemUIDisplaySubcomponent.Factory systemUIDisplaySubcomponentFactory(
206:            ReferenceSysUIDisplaySubcomponent.Factory factory);
207:
208:    @SysUISingleton
209:    @Provides
210:    @Named(LEAK_REPORT_EMAIL_NAME)
211:    static String provideLeakReportEmail() {
212:        return "";
213:    }
214:
215:    @Binds
216:    abstract NotificationLockscreenUserManager bindNotificationLockscreenUserManager(
217:            NotificationLockscreenUserManagerImpl notificationLockscreenUserManager);
218:
219:    @Provides
220:    @SysUISingleton
221:    static SensorPrivacyController provideSensorPrivacyController(
222:            SensorPrivacyManager sensorPrivacyManager) {
223:        SensorPrivacyController spC = new SensorPrivacyControllerImpl(sensorPrivacyManager);
224:        spC.init();
225:        return spC;
226:    }
227:
228:    @Provides
229:    @SysUISingleton
230:    static IndividualSensorPrivacyController provideIndividualSensorPrivacyController(
231:            SensorPrivacyManager sensorPrivacyManager, UserTracker userTracker) {
232:        IndividualSensorPrivacyController spC = new IndividualSensorPrivacyControllerImpl(
233:                sensorPrivacyManager, userTracker);
234:        spC.init();
235:        return spC;
236:    }
237:
238:    /**  */
239:    @Binds
240:    @SysUISingleton
241:    public abstract QSFactory bindQSFactory(QSFactoryImpl qsFactoryImpl);
242:
243:    @Binds
244:    abstract DockManager bindDockManager(DockManagerImpl dockManager);
245:
246:    @Provides
247:    @SysUISingleton
248:    static Optional<MinModeManager> bindMinModeManager(
249:            Provider<MinModeManagerImpl> minModeManager) {
250:        if (Flags.enableMinmode()) {
251:            return Optional.of(minModeManager.get());
252:        } else {
253:            return Optional.empty();
254:        }
255:    }
256:
257:    @SysUISingleton
258:    @Provides
259:    @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME)
260:    static boolean provideAllowNotificationLongPress() {
261:        return true;
262:    }
263:
264:    @Provides
265:    @SysUISingleton
266:    static Recents provideRecents(Context context, RecentsImplementation recentsImplementation,
267:            CommandQueue commandQueue) {
268:        return new Recents(context, recentsImplementation, commandQueue);
269:    }
270:
271:    @SysUISingleton
272:    @Provides
273:    static DeviceProvisionedController bindDeviceProvisionedController(
274:            DeviceProvisionedControllerImpl deviceProvisionedController) {
275:        deviceProvisionedController.init();
276:        return deviceProvisionedController;
277:    }
278:
279:    @Binds
280:    abstract KeyguardViewController bindKeyguardViewController(
281:            StatusBarKeyguardViewManager statusBarKeyguardViewManager);
282:
283:    @Binds
284:    abstract NotificationShadeWindowController bindNotificationShadeController(
285:            NotificationShadeWindowControllerImpl notificationShadeWindowController);
286:
287:    @Binds
288:    abstract DozeHost provideDozeHost(DozeServiceHost dozeServiceHost);
289:
290:    /**  */
291:    @Provides
292:    @IntoMap
293:    @Dependencies
294:    @ClassKey(SysuiStatusBarStateController.class)
295:    static Set<Class<? extends CoreStartable>> providesStatusBarStateControllerDeps() {
296:        return Set.of(CentralSurfaces.class);
297:    }
298:}