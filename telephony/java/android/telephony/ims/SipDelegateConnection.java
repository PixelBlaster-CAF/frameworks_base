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

package android.telephony.ims;

import android.annotation.NonNull;
import android.telephony.ims.stub.SipDelegate;

/**
 * Represents a connection to the remote {@link SipDelegate} that is managed by the
 * {@link ImsService} implementing IMS for the subscription that is associated with it.
 * <p>
 * The remote delegate will handle messages sent by this {@link SipDelegateConnection}, notifying
 * the associated {@link DelegateMessageCallback} when the message was either sent successfully or
 * failed to be sent.
 * <p>
 * It is also the responsibility of this {@link SipDelegateConnection} to acknowledge when incoming
 * SIP messages have been received successfully via
 * {@link DelegateMessageCallback#onMessageReceived(SipMessage)} or when there was an error
 * receiving the message using {@link #notifyMessageReceived(String)} and
 * {@link #notifyMessageReceiveError(String, int)}.
 *
 * @see SipDelegateManager#createSipDelegate
 * @hide
 */
public interface SipDelegateConnection {

    /**
     * Send a SIP message to the SIP delegate to be sent over the carrier’s network. The
     * {@link SipMessage} will either be acknowledged with
     * {@link DelegateMessageCallback#onMessageSent(String)} upon successful sending of this message
     * or {@link DelegateMessageCallback#onMessageSendFailure(String, int)} if there was an error
     * sending the message.
     * @param sipMessage The SipMessage to be sent.
     * @param configVersion The SipDelegateImsConfiguration version used to construct the
     *                      SipMessage. See {@link SipDelegateImsConfiguration#getVersion} for more
     *                      information on this parameter and why it is used.
     */
    void sendMessage(@NonNull SipMessage sipMessage, int configVersion);

    /**
     * Notify the {@link SipDelegate} that a SIP message received from
     * {@link DelegateMessageCallback#onMessageReceived(SipMessage)} has been received successfully
     * and is being processed.
     * @param viaTransactionId Per RFC3261 Sec 8.1.1.7 the transaction ID associated with the Via
     *         branch parameter.
     */
    void notifyMessageReceived(@NonNull String viaTransactionId);

    /**
     * Notify the SIP delegate that the SIP message has been received from
     * {@link DelegateMessageCallback#onMessageReceived(SipMessage)}, however there was an error
     * processing it.
     * @param viaTransactionId Per RFC3261 Sec 8.1.1.7 the transaction ID associated with the Via
     *         branch parameter.
     * @param reason The reason why the error occurred.
     */
    void notifyMessageReceiveError(@NonNull String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}
