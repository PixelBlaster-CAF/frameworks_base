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

package com.android.server.location.timezone;

import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;
import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;

import static com.android.server.location.timezone.LocationTimeZoneManagerService.debugLog;
import static com.android.server.location.timezone.LocationTimeZoneManagerService.warnLog;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_CERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.location.timezone.LocationTimeZoneEvent;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.location.timezone.ThreadingDomain.SingleRunnableQueue;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A real implementation of {@link LocationTimeZoneProviderController} that supports a primary and a
 * secondary {@link LocationTimeZoneProvider}.
 *
 * <p>The primary is used until it fails or becomes uncertain. The secondary will then be enabled.
 * The controller will immediately make suggestions based on "certain" {@link
 * LocationTimeZoneEvent}s, i.e. events that demonstrate the provider is certain what the time zone
 * is. The controller will not make immediate suggestions based on "uncertain" events, giving
 * providers time to change their mind. This also gives the secondary provider time to initialize
 * when the primary becomes uncertain.
 */
class ControllerImpl extends LocationTimeZoneProviderController {

    @NonNull private final LocationTimeZoneProvider mPrimaryProvider;

    @NonNull private final LocationTimeZoneProvider mSecondaryProvider;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private ConfigurationInternal mCurrentUserConfiguration;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Environment mEnvironment;

    @GuardedBy("mSharedLock")
    // Non-null after initialize()
    private Callback mCallback;

    /**
     * Used for scheduling uncertainty timeouts, i.e after a provider has reported uncertainty.
     * This timeout is not provider-specific: it is started when the controller becomes uncertain
     * due to events it has received from one or other provider.
     */
    @NonNull private final SingleRunnableQueue mUncertaintyTimeoutQueue;

    /** Contains the last suggestion actually made, if there is one. */
    @GuardedBy("mSharedLock")
    @Nullable
    private GeolocationTimeZoneSuggestion mLastSuggestion;

    ControllerImpl(@NonNull ThreadingDomain threadingDomain,
            @NonNull LocationTimeZoneProvider primaryProvider,
            @NonNull LocationTimeZoneProvider secondaryProvider) {
        super(threadingDomain);
        mUncertaintyTimeoutQueue = threadingDomain.createSingleRunnableQueue();
        mPrimaryProvider = Objects.requireNonNull(primaryProvider);
        mSecondaryProvider = Objects.requireNonNull(secondaryProvider);
    }

    @Override
    void initialize(@NonNull Environment environment, @NonNull Callback callback) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("initialize()");
            mEnvironment = Objects.requireNonNull(environment);
            mCallback = Objects.requireNonNull(callback);
            mCurrentUserConfiguration = environment.getCurrentUserConfigurationInternal();

            LocationTimeZoneProvider.ProviderListener providerListener =
                    ControllerImpl.this::onProviderStateChange;
            mPrimaryProvider.initialize(providerListener);
            mSecondaryProvider.initialize(providerListener);

            alterProvidersEnabledStateIfRequired(
                    null /* oldConfiguration */, mCurrentUserConfiguration);
        }
    }

    @Override
    void onConfigChanged() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            debugLog("onEnvironmentConfigChanged()");

            ConfigurationInternal oldConfig = mCurrentUserConfiguration;
            ConfigurationInternal newConfig = mEnvironment.getCurrentUserConfigurationInternal();
            mCurrentUserConfiguration = newConfig;

            if (!newConfig.equals(oldConfig)) {
                if (newConfig.getUserId() != oldConfig.getUserId()) {
                    // If the user changed, disable the providers if needed. They may be re-enabled
                    // for the new user immediately afterwards if their settings allow.
                    debugLog("User changed. old=" + oldConfig.getUserId()
                            + ", new=" + newConfig.getUserId() + ": Disabling providers");
                    disableProviders();

                    alterProvidersEnabledStateIfRequired(null /* oldConfiguration */, newConfig);
                } else {
                    alterProvidersEnabledStateIfRequired(oldConfig, newConfig);
                }
            }
        }
    }

    @Override
    boolean isUncertaintyTimeoutSet() {
        return mUncertaintyTimeoutQueue.hasQueued();
    }

    @Override
    long getUncertaintyTimeoutDelayMillis() {
        return mUncertaintyTimeoutQueue.getQueuedDelayMillis();
    }

    @GuardedBy("mSharedLock")
    private void disableProviders() {
        disableProviderIfEnabled(mPrimaryProvider);
        disableProviderIfEnabled(mSecondaryProvider);

        // By definition, if both providers are disabled, the controller is uncertain.
        cancelUncertaintyTimeout();
    }

    @GuardedBy("mSharedLock")
    private void disableProviderIfEnabled(@NonNull LocationTimeZoneProvider provider) {
        if (provider.getCurrentState().isEnabled()) {
            disableProvider(provider);
        }
    }

    @GuardedBy("mSharedLock")
    private void disableProvider(@NonNull LocationTimeZoneProvider provider) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_DISABLED: {
                debugLog("No need to disable " + provider + ": already disabled");
                break;
            }
            case PROVIDER_STATE_ENABLED_INITIALIZING:
            case PROVIDER_STATE_ENABLED_CERTAIN:
            case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                debugLog("Disabling " + provider);
                provider.disable();
                break;
            }
            case PROVIDER_STATE_PERM_FAILED: {
                debugLog("Unable to disable " + provider + ": it is perm failed");
                break;
            }
            default: {
                warnLog("Unknown provider state: " + provider);
                break;
            }
        }
    }

    /**
     * Sets the providers into the correct enabled/disabled state for the {@code newConfiguration}
     * and, if there is a provider state change, makes any suggestions required to inform the
     * downstream time zone detection code.
     *
     * <p>This is a utility method that exists to avoid duplicated logic for the various cases when
     * provider enabled / disabled state may need to be set or changed, e.g. during initialization
     * or when a new configuration has been received.
     */
    @GuardedBy("mSharedLock")
    private void alterProvidersEnabledStateIfRequired(
            @Nullable ConfigurationInternal oldConfiguration,
            @NonNull ConfigurationInternal newConfiguration) {

        // Provider enabled / disabled states only need to be changed if geoDetectionEnabled has
        // changed.
        boolean oldGeoDetectionEnabled = oldConfiguration != null
                && oldConfiguration.getGeoDetectionEnabledBehavior();
        boolean newGeoDetectionEnabled = newConfiguration.getGeoDetectionEnabledBehavior();
        if (oldGeoDetectionEnabled == newGeoDetectionEnabled) {
            return;
        }

        // The check above ensures that the logic below only executes if providers are going from
        // {enabled *} -> {disabled}, or {disabled} -> {enabled initializing}. If this changes in
        // future and there could be {enabled *} -> {enabled *} cases, or cases where the provider
        // can't be assumed to go straight to the {enabled initializing} state, then the logic below
        // would need to cover extra conditions, for example:
        // 1) If the primary is in {enabled uncertain}, the secondary should be enabled.
        // 2) If (1), and the secondary instantly enters the {perm failed} state, the uncertainty
        //    timeout started when the primary entered {enabled uncertain} should be cancelled.

        if (newGeoDetectionEnabled) {
            // Try to enable the primary provider.
            tryEnableProvider(mPrimaryProvider, newConfiguration);

            // The secondary should only ever be enabled if the primary now isn't enabled (i.e. it
            // couldn't become {enabled initializing} because it is {perm failed}).
            ProviderState newPrimaryState = mPrimaryProvider.getCurrentState();
            if (!newPrimaryState.isEnabled()) {
                // If the primary provider is {perm failed} then the controller must try to enable
                // the secondary.
                tryEnableProvider(mSecondaryProvider, newConfiguration);

                ProviderState newSecondaryState = mSecondaryProvider.getCurrentState();
                if (!newSecondaryState.isEnabled()) {
                    // If both providers are {perm failed} then the controller immediately
                    // becomes uncertain.
                    GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                            "Providers are failed:"
                                    + " primary=" + mPrimaryProvider.getCurrentState()
                                    + " secondary=" + mPrimaryProvider.getCurrentState());
                    makeSuggestion(suggestion);
                }
            }
        } else {
            disableProviders();

            // There can be an uncertainty timeout set if the controller most recently received
            // an uncertain event. This is a no-op if there isn't a timeout set.
            cancelUncertaintyTimeout();

            // If a previous "certain" suggestion has been made, then a new "uncertain"
            // suggestion must now be made to indicate the controller {does not / no longer has}
            // an opinion and will not be sending further updates (until at least the config
            // changes again and providers are re-enabled).
            if (mLastSuggestion != null && mLastSuggestion.getZoneIds() != null) {
                GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                        "Provider is disabled:"
                                + " primary=" + mPrimaryProvider.getCurrentState());
                makeSuggestion(suggestion);
            }
        }
    }

    private void tryEnableProvider(@NonNull LocationTimeZoneProvider provider,
            @NonNull ConfigurationInternal configuration) {
        ProviderState providerState = provider.getCurrentState();
        switch (providerState.stateEnum) {
            case PROVIDER_STATE_DISABLED: {
                debugLog("Enabling " + provider);
                provider.enable(configuration, mEnvironment.getProviderInitializationTimeout(),
                        mEnvironment.getProviderInitializationTimeoutFuzz());
                break;
            }
            case PROVIDER_STATE_ENABLED_INITIALIZING:
            case PROVIDER_STATE_ENABLED_CERTAIN:
            case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                debugLog("No need to enable " + provider + ": already enabled");
                break;
            }
            case PROVIDER_STATE_PERM_FAILED: {
                debugLog("Unable to enable " + provider + ": it is perm failed");
                break;
            }
            default: {
                throw new IllegalStateException("Unknown provider state:"
                        + " provider=" + provider);
            }
        }
    }

    void onProviderStateChange(@NonNull ProviderState providerState) {
        mThreadingDomain.assertCurrentThread();
        LocationTimeZoneProvider provider = providerState.provider;
        assertProviderKnown(provider);

        synchronized (mSharedLock) {
            switch (providerState.stateEnum) {
                case PROVIDER_STATE_DISABLED: {
                    // This should never happen: entering disabled does not trigger a state change.
                    warnLog("onProviderStateChange: Unexpected state change for disabled provider,"
                            + " provider=" + provider);
                    break;
                }
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                    // Entering enabled does not trigger a state change, so this only happens if an
                    // event is received while the provider is enabled.
                    debugLog("onProviderStateChange: Received notification of a state change while"
                            + " enabled, provider=" + provider);
                    handleProviderEnabledStateChange(providerState);
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("Received notification of permanent failure for"
                            + " provider=" + provider);
                    handleProviderFailedStateChange(providerState);
                    break;
                }
                default: {
                    warnLog("onProviderStateChange: Unexpected provider=" + provider);
                }
            }
        }
    }

    private void assertProviderKnown(@NonNull LocationTimeZoneProvider provider) {
        if (provider != mPrimaryProvider && provider != mSecondaryProvider) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Called when a provider has reported that it has failed permanently.
     */
    @GuardedBy("mSharedLock")
    private void handleProviderFailedStateChange(@NonNull ProviderState providerState) {
        LocationTimeZoneProvider failedProvider = providerState.provider;
        ProviderState primaryCurrentState = mPrimaryProvider.getCurrentState();
        ProviderState secondaryCurrentState = mSecondaryProvider.getCurrentState();

        // If a provider has failed, the other may need to be enabled.
        if (failedProvider == mPrimaryProvider) {
            if (secondaryCurrentState.stateEnum != PROVIDER_STATE_PERM_FAILED) {
                // The primary must have failed. Try to enable the secondary. This does nothing if
                // the provider is already enabled, and will leave the provider in
                // {enabled initializing} if the provider is disabled.
                tryEnableProvider(mSecondaryProvider, mCurrentUserConfiguration);
            }
        } else if (failedProvider == mSecondaryProvider) {
            // No-op: The secondary will only be active if the primary is uncertain or is failed.
            // So, there the primary should not need to be enabled when the secondary fails.
            if (primaryCurrentState.stateEnum != PROVIDER_STATE_ENABLED_UNCERTAIN
                    && primaryCurrentState.stateEnum != PROVIDER_STATE_PERM_FAILED) {
                warnLog("Secondary provider unexpected reported a failure:"
                        + " failed provider=" + failedProvider.getName()
                        + ", primary provider=" + mPrimaryProvider
                        + ", secondary provider=" + mSecondaryProvider);
            }
        }

        // If both providers are now failed, the controller needs to tell the next component in the
        // time zone detection process.
        if (primaryCurrentState.stateEnum == PROVIDER_STATE_PERM_FAILED
                && secondaryCurrentState.stateEnum == PROVIDER_STATE_PERM_FAILED) {

            // If both providers are newly failed then the controller is uncertain by definition
            // and it will never recover so it can send a suggestion immediately.
            cancelUncertaintyTimeout();

            // If both providers are now failed, then a suggestion must be sent informing the time
            // zone detector that there are no further updates coming in future.
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    "Both providers are permanently failed:"
                            + " primary=" + primaryCurrentState.provider
                            + ", secondary=" + secondaryCurrentState.provider);
            makeSuggestion(suggestion);
        }
    }

    /**
     * Called when a provider has changed state but just moved from one enabled state to another
     * enabled state, usually as a result of a new {@link LocationTimeZoneEvent} being received.
     * However, there are rare cases where the event can also be null.
     */
    @GuardedBy("mSharedLock")
    private void handleProviderEnabledStateChange(@NonNull ProviderState providerState) {
        LocationTimeZoneProvider provider = providerState.provider;
        LocationTimeZoneEvent event = providerState.event;
        if (event == null) {
            // Implicit uncertainty, i.e. where the provider is enabled, but a problem has been
            // detected without having received an event. For example, if the process has detected
            // the loss of a binder-based provider, or initialization took too long. This is treated
            // the same as explicit uncertainty, i.e. where the provider has explicitly told this
            // process it is uncertain.
            handleProviderUncertainty(provider, "provider=" + provider
                    + ", implicit uncertainty, event=null");
            return;
        }

        // Consistency check for user. This may be possible as there are various races around
        // current user switches.
        if (!Objects.equals(event.getUserHandle(), mCurrentUserConfiguration.getUserHandle())) {
            warnLog("Using event=" + event + " from a different user="
                    + mCurrentUserConfiguration);
        }

        if (!mCurrentUserConfiguration.getGeoDetectionEnabledBehavior()) {
            // This should not happen: the provider should not be in an enabled state if the user
            // does not have geodetection enabled.
            warnLog("Provider=" + provider + " is enabled, but"
                    + " currentUserConfiguration=" + mCurrentUserConfiguration
                    + " suggests it shouldn't be.");
        }

        switch (event.getEventType()) {
            case EVENT_TYPE_PERMANENT_FAILURE: {
                // This shouldn't happen. A provider cannot be enabled and have this event type.
                warnLog("Provider=" + provider
                        + " is enabled, but event suggests it shouldn't be");
                break;
            }
            case EVENT_TYPE_UNCERTAIN: {
                handleProviderUncertainty(provider, "provider=" + provider
                        + ", explicit uncertainty. event=" + event);
                break;
            }
            case EVENT_TYPE_SUCCESS: {
                handleProviderCertainty(provider, event.getTimeZoneIds(),
                        "Event received provider=" + provider + ", event=" + event);
                break;
            }
            default: {
                warnLog("Unknown eventType=" + event.getEventType());
                break;
            }
        }
    }

    /**
     * Called when a provider has become "certain" about the time zone(s).
     */
    @GuardedBy("mSharedLock")
    private void handleProviderCertainty(
            @NonNull LocationTimeZoneProvider provider,
            @Nullable List<String> timeZoneIds,
            @NonNull String reason) {
        // By definition, the controller is now certain.
        cancelUncertaintyTimeout();

        if (provider == mPrimaryProvider) {
            disableProviderIfEnabled(mSecondaryProvider);
        }

        GeolocationTimeZoneSuggestion suggestion =
                new GeolocationTimeZoneSuggestion(timeZoneIds);
        suggestion.addDebugInfo(reason);
        // Rely on the receiver to dedupe suggestions. It is better to over-communicate.
        makeSuggestion(suggestion);
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("LocationTimeZoneProviderController:");

            ipw.increaseIndent(); // level 1
            ipw.println("mCurrentUserConfiguration=" + mCurrentUserConfiguration);
            ipw.println("providerInitializationTimeout="
                    + mEnvironment.getProviderInitializationTimeout());
            ipw.println("providerInitializationTimeoutFuzz="
                    + mEnvironment.getProviderInitializationTimeoutFuzz());
            ipw.println("uncertaintyDelay=" + mEnvironment.getUncertaintyDelay());
            ipw.println("mLastSuggestion=" + mLastSuggestion);

            ipw.println("Primary Provider:");
            ipw.increaseIndent(); // level 2
            mPrimaryProvider.dump(ipw, args);
            ipw.decreaseIndent(); // level 2

            ipw.println("Secondary Provider:");
            ipw.increaseIndent(); // level 2
            mSecondaryProvider.dump(ipw, args);
            ipw.decreaseIndent(); // level 2

            ipw.decreaseIndent(); // level 1
        }
    }

    /** Sends an immediate suggestion, updating mLastSuggestion. */
    @GuardedBy("mSharedLock")
    private void makeSuggestion(@NonNull GeolocationTimeZoneSuggestion suggestion) {
        debugLog("makeSuggestion: suggestion=" + suggestion);
        mCallback.suggest(suggestion);
        mLastSuggestion = suggestion;
    }

    /** Clears the uncertainty timeout. */
    @GuardedBy("mSharedLock")
    private void cancelUncertaintyTimeout() {
        mUncertaintyTimeoutQueue.cancel();
    }

    /**
     * Called when a provider has become "uncertain" about the time zone.
     *
     * <p>A provider is expected to report its uncertainty as soon as it becomes uncertain, as
     * this enables the most flexibility for the controller to enable other providers when there are
     * multiple ones available. The controller is therefore responsible for deciding when to make a
     * "uncertain" suggestion to the downstream time zone detector.
     *
     * <p>This method schedules an "uncertainty" timeout (if one isn't already scheduled) to be
     * triggered later if nothing else preempts it. It can be preempted if the provider becomes
     * certain (or does anything else that calls {@link
     * #makeSuggestion(GeolocationTimeZoneSuggestion)}) within {@link
     * Environment#getUncertaintyDelay()}. Preemption causes the scheduled
     * "uncertainty" timeout to be cancelled. If the provider repeatedly sends uncertainty events
     * within the uncertainty delay period, those events are effectively ignored (i.e. the timeout
     * is not reset each time).
     */
    @GuardedBy("mSharedLock")
    void handleProviderUncertainty(
            @NonNull LocationTimeZoneProvider provider, @NonNull String reason) {
        Objects.requireNonNull(provider);

        // Start the uncertainty timeout if needed to ensure the controller will eventually make an
        // uncertain suggestion if no success event arrives in time to counteract it.
        if (!mUncertaintyTimeoutQueue.hasQueued()) {
            debugLog("Starting uncertainty timeout: reason=" + reason);

            Duration delay = mEnvironment.getUncertaintyDelay();
            mUncertaintyTimeoutQueue.runDelayed(() -> onProviderUncertaintyTimeout(provider),
                    delay.toMillis());
        }

        if (provider == mPrimaryProvider) {
            // (Try to) enable the secondary. It could already be enabled, or enabling might not
            // succeed if the provider has previously reported it is perm failed. The uncertainty
            // timeout (set above) is used to ensure that an uncertain suggestion will be made if
            // the secondary cannot generate a success event in time.
            tryEnableProvider(mSecondaryProvider, mCurrentUserConfiguration);
        }
    }

    private void onProviderUncertaintyTimeout(@NonNull LocationTimeZoneProvider provider) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            GeolocationTimeZoneSuggestion suggestion = createUncertainSuggestion(
                    "Uncertainty timeout triggered for " + provider.getName() + ":"
                            + " primary=" + mPrimaryProvider
                            + ", secondary=" + mSecondaryProvider);
            makeSuggestion(suggestion);
        }
    }

    private static GeolocationTimeZoneSuggestion createUncertainSuggestion(@NonNull String reason) {
        GeolocationTimeZoneSuggestion suggestion = new GeolocationTimeZoneSuggestion(null);
        suggestion.addDebugInfo(reason);
        return suggestion;
    }

    /**
     * Asynchronously passes a {@link SimulatedBinderProviderEvent] to the appropriate provider.
     * If the provider name does not match a known provider, then the event is logged and discarded.
     */
    void simulateBinderProviderEvent(@NonNull SimulatedBinderProviderEvent event) {
        String targetProviderName = event.getProviderName();
        LocationTimeZoneProvider targetProvider;
        if (Objects.equals(mPrimaryProvider.getName(), targetProviderName)) {
            targetProvider = mPrimaryProvider;
        } else if (Objects.equals(mSecondaryProvider.getName(), targetProviderName)) {
            targetProvider = mSecondaryProvider;
        } else {
            warnLog("Unable to process simulated binder provider event,"
                    + " unknown providerName in event=" + event);
            return;
        }
        if (!(targetProvider instanceof BinderLocationTimeZoneProvider)) {
            warnLog("Unable to process simulated binder provider event,"
                    + " provider=" + targetProvider
                    + " is not a " + BinderLocationTimeZoneProvider.class
                    + ", event=" + event);
            return;
        }
        ((BinderLocationTimeZoneProvider) targetProvider).simulateBinderProviderEvent(event);
    }
}
