package com.inventoryerp.communication.application.providers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The internal (staff-to-staff) provider (port of {@code internal.provider.ts}): the message row already is the
 * delivery, so {@code send} succeeds instantly with {@code deliveredAt = now}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class InternalProvider implements MessagingProvider {

    @Override
    public String id() {
        return "internal";
    }

    @Override
    public boolean isReady(String channelId) {
        return true;
    }

    @Override
    public ChannelHealth health(String channelId) {
        return new ChannelHealth("connected", Instant.now().truncatedTo(ChronoUnit.MILLIS), null, true);
    }

    @Override
    public SendOutcome send(OutboundMessage msg) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Sent(msg.messageId(), now, now);
    }

    @Override
    public void markRead(String channelId, String externalConversationId, String externalMessageId) {
        // Read state for internal is tracked via ConversationParticipant.lastReadMessageId.
    }

    @Override
    public void connect(String channelId) {
        // Always connected.
    }

    @Override
    public void disconnect(String channelId, boolean logout) {
        // Cannot disconnect the internal transport.
    }

    @Override
    public String normalizeAddress(String raw) {
        return raw;
    }
}
