package com.inventoryerp.communication.web;

import com.inventoryerp.shared.validation.MaxLength;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the Discuss routes (ADR-S064; extension, no Nest DTO). */
final class DiscussDtos {

    private DiscussDtos() {}

    static final String STR = "must be a string";
    static final String BOOL = "must be a boolean value";

    record CreateChannelRequest(
            @NotNull(message = STR) @Size(min = 1, max = 80, message = "must be between 1 and 80 characters")
            String name,
            @Size(max = 500, message = "must be shorter than or equal to 500 characters") String description,
            @OneOf({"public", "private"}) String visibility,
            @Size(max = 200, message = "must contain no more than 200 elements") List<String> memberIds) {}

    record ChatRequest(
            @NotNull(message = "should not be empty")
            @Size(min = 1, max = 20, message = "must contain between 1 and 20 elements") List<String> userIds) {}

    record UpdateConversationRequest(
            @Size(min = 1, max = 80, message = "must be between 1 and 80 characters") String name,
            @Size(max = 500, message = "must be shorter than or equal to 500 characters") String description,
            @OneOf({"public", "private"}) String visibility) {}

    record MembersRequest(
            @NotNull(message = "should not be empty")
            @Size(min = 1, max = 200, message = "must contain between 1 and 200 elements") List<String> userIds) {}

    record SettingsRequest(
            @OneOf({"all", "mentions", "none"}) String notifyLevel,
            @Min(value = -1, message = "must not be less than -1")
            @Max(value = 525600, message = "must not be greater than 525600") Integer muteMinutes,
            Boolean pinned) {}

    record TypingRequest(@NotNull(message = BOOL) Boolean typing) {}

    record ReadRequest(String messageId) {}

    record PostMessageRequest(
            @MaxLength(10000) String body,
            String replyToMessageId,
            @Size(max = 50, message = "must contain no more than 50 elements") List<String> mentionUserIds,
            @Size(max = 10, message = "must contain no more than 10 elements") List<String> attachmentIds,
            @OneOf({"comment", "note"}) String subtype) {}

    record EditMessageRequest(
            @NotNull(message = STR) @MaxLength(10000) String body,
            @Size(max = 50, message = "must contain no more than 50 elements") List<String> mentionUserIds) {}

    record ReactionRequest(
            @NotNull(message = STR) @Size(min = 1, max = 16, message = "must be between 1 and 16 characters")
            String emoji) {}

    record InboxReadRequest(@Size(max = 500, message = "must contain no more than 500 elements") List<String> ids) {}
}
