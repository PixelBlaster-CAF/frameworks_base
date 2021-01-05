/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.dagger;

import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightControllerImpl;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardStateControllerImpl;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.SensorPrivacyControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;

import dagger.Binds;
import dagger.Module;


/** Dagger Module for code in the statusbar.policy package. */
@Module
public interface StatusBarPolicyModule {
    /** */
    @Binds
    BluetoothController provideBluetoothController(BluetoothControllerImpl controllerImpl);

    /** */
    @Binds
    CastController provideCastController(CastControllerImpl controllerImpl);

    /** */
    @Binds
    ExtensionController provideExtensionController(ExtensionControllerImpl controllerImpl);

    /** */
    @Binds
    FlashlightController provideFlashlightController(FlashlightControllerImpl controllerImpl);

    /** */
    @Binds
    KeyguardStateController provideKeyguardMonitor(KeyguardStateControllerImpl controllerImpl);

    /** */
    @Binds
    HotspotController provideHotspotController(HotspotControllerImpl controllerImpl);

    /** */
    @Binds
    LocationController provideLocationController(LocationControllerImpl controllerImpl);

    /** */
    @Binds
    NetworkController provideNetworkController(NetworkControllerImpl controllerImpl);

    /** */
    @Binds
    NextAlarmController provideNextAlarmController(NextAlarmControllerImpl controllerImpl);

    /** */
    @Binds
    RotationLockController provideRotationLockController(RotationLockControllerImpl controllerImpl);

    /** */
    @Binds
    SecurityController provideSecurityController(SecurityControllerImpl controllerImpl);

    /** */
    @Binds
    SensorPrivacyController provideSensorPrivacyControllerImpl(
            SensorPrivacyControllerImpl controllerImpl);

    /** */
    @Binds
    UserInfoController provideUserInfoContrller(UserInfoControllerImpl controllerImpl);

    /** */
    @Binds
    ZenModeController provideZenModeController(ZenModeControllerImpl controllerImpl);

}
