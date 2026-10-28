package com.inventoryerp.communication.application.providers;

import java.time.Instant;
import java.util.Map;

/**
 * The provider boundary (port of {@code messaging-provider.interface.ts}). Nothing provider-specific crosses it:
 * the dispatcher only understands {@link SendOutcome} and {@link ProviderSendError}.
 */
public interface MessagingProvider {

    /** One outbound send. {@code providerRequestId} is stable across retries. */
    record OutboundMessage(String organizationId, String channelId, String conversationId,
            String conversationChannelId, String messageId, String providerRequestId, String toAddress, String body,
            String contentType) {}

    /** {@link Sent} or {@link Deferred}. */
    sealed interface SendOutcome permits Sent, Deferred {}

    /** A message left our side; {@code deliveredAt} is set by instant-delivery providers (internal). */
    record Sent(String externalMessageId, Instant sentAt, Instant deliveredAt) implements SendOutcome {}

    /** The provider's pacing decided this send may not go yet. Not an error. */
    record Deferred(Instant deferUntil, String reason) implements SendOutcome {}

    /** Live channel health: {@code status} is disconnected | connecting | pairing | connected | logged_out | error. */
    record ChannelHealth(String status, Instant lastConnectedAt, String lastError, boolean ownedByThisProcess) {

        public Map<String, Object> toJson() {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("status", status);
            m.put("lastConnectedAt", lastConnectedAt);
            m.put("lastError", lastError);
            m.put("ownedByThisProcess", ownedByThisProcess);
            return m;
        }
    }

    /** internal | whatsapp | telegram. */
    String id();

    /** Ready to accept a send right now on this channel. */
    boolean isReady(String channelId);

    ChannelHealth health(String channelId);

    SendOutcome send(OutboundMessage msg);

    /** No-op where unsupported. */
    void markRead(String channelId, String externalConversationId, String externalMessageId);

    /** Pairing lifecycle; no-ops for stateless providers. */
    void connect(String channelId);

    void disconnect(String channelId, boolean logout);

    /** Normalize a raw address (E.164, chat id) for the provider. */
    String normalizeAddress(String raw);
}
