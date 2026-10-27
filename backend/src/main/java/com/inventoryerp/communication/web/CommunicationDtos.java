package com.inventoryerp.communication.web;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Request bodies of the communication routes ({@code communication/dto/communication.dto.ts}). */
final class CommunicationDtos {

    private CommunicationDtos() {}

    static final String STR = "must be a string";
    static final String BOOL = "must be a boolean value";
    static final String MIN1 = "must be longer than or equal to 1 characters";

    /** {@code CreateConversationDto}. */
    record CreateConversationRequest(
            @OneOf({"direct", "group", "channel"}) String kind,
            @Size(max = 200, message = "must be shorter than or equal to 200 characters") String name,
            List<String> participantUserIds,
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String contextType,
            @Size(max = 64, message = "must be shorter than or equal to 64 characters") String contextId,
            @OneOf({"private", "org", "role"}) String visibility,
            List<String> visibleToPermissions) {}

    /** {@code SendMessageDto}. */
    record SendMessageRequest(
            @NotNull(message = STR) @Size(min = 1, message = MIN1)
            @Size(max = 8000, message = "must be shorter than or equal to 8000 characters") String body,
            String replyToMessageId,
            // @IsOptional() @ArrayNotEmpty(): absent is fine, an empty array is not.
            @Size(min = 1, message = "should not be empty") List<String> targetConversationChannelIds) {}

    /** {@code MarkReadDto}. */
    record MarkReadRequest(String messageId) {}

    /** {@code ResolveContextDto}. */
    record ResolveContextRequest(
            @NotNull(message = STR) @Size(max = 64, message = "must be shorter than or equal to 64 characters")
            String contextType,
            @NotNull(message = STR) @Size(max = 64, message = "must be shorter than or equal to 64 characters")
            String contextId) {}

    /** {@code UpsertTemplateDto}. */
    record UpsertTemplateRequest(
            @NotNull(message = STR) @Size(min = 1, message = MIN1)
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String key,
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String eventName,
            @Size(max = 40, message = "must be shorter than or equal to 40 characters") String providerId,
            @Size(max = 10, message = "must be shorter than or equal to 10 characters") String locale,
            @Size(max = 200, message = "must be shorter than or equal to 200 characters") String subject,
            @NotNull(message = STR) @Size(min = 1, message = MIN1)
            @Size(max = 8000, message = "must be shorter than or equal to 8000 characters") String body,
            Boolean active) {}

    /** {@code UpsertRuleDto}. */
    record UpsertRuleRequest(
            @NotNull(message = STR) @Size(max = 120, message = "must be shorter than or equal to 120 characters")
            String eventName,
            @NotNull(message = STR) @Size(max = 120, message = "must be shorter than or equal to 120 characters")
            String templateKey,
            @NotNull(message = STR) @Size(max = 200, message = "must be shorter than or equal to 200 characters")
            String recipientResolver,
            @Size(max = 40, message = "must be shorter than or equal to 40 characters") String channelSelector,
            Map<String, Object> condition,
            Boolean enabled) {}

    /** {@code SetEnabledDto}. */
    record SetEnabledRequest(@NotNull(message = BOOL) Boolean enabled) {}

    /** {@code CreateChannelDto}. */
    record CreateChannelRequest(
            @NotNull(message = STR) @Size(max = 40, message = "must be shorter than or equal to 40 characters")
            String providerId,
            @NotNull(message = STR) @Size(min = 1, message = MIN1)
            @Size(max = 120, message = "must be shorter than or equal to 120 characters") String name,
            @Size(max = 40, message = "must be shorter than or equal to 40 characters") String transport,
            Map<String, Object> config) {}

    /** {@code DisconnectChannelDto}. */
    record DisconnectChannelRequest(Boolean logout) {}
}
