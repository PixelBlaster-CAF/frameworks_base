package com.blaster.android.systemui;

import android.content.Context;

import com.blaster.android.systemui.dagger.BlasterGlobalRootComponent;
import com.blaster.android.systemui.dagger.DaggerBlasterGlobalRootComponent;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;

public class BlasterSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerBlasterGlobalRootComponent.builder()
                .context(context)
                .build();
    }
}
