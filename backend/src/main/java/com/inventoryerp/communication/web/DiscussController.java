package com.inventoryerp.communication.web;

import com.inventoryerp.communication.application.DepartmentChannelSync;
import com.inventoryerp.communication.application.DiscussMessageService;
import com.inventoryerp.communication.application.DiscussRecordService;
import com.inventoryerp.communication.application.DiscussService;
import com.inventoryerp.communication.web.DiscussDtos.ChatRequest;
import com.inventoryerp.communication.web.DiscussDtos.CreateChannelRequest;
import com.inventoryerp.communication.web.DiscussDtos.EditMessageRequest;
import com.inventoryerp.communication.web.DiscussDtos.InboxReadRequest;
import com.inventoryerp.communication.web.DiscussDtos.MembersRequest;
import com.inventoryerp.communication.web.DiscussDtos.PostMessageRequest;
import com.inventoryerp.communication.web.DiscussDtos.ReactionRequest;
import com.inventoryerp.communication.web.DiscussDtos.ReadRequest;
import com.inventoryerp.communication.web.DiscussDtos.SettingsRequest;
import com.inventoryerp.communication.web.DiscussDtos.TypingRequest;
import com.inventoryerp.communication.web.DiscussDtos.UpdateConversationRequest;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Discuss {@code /communication/discuss} (ADR-S064, extension: no Nest counterpart): Odoo-style staff and department
 * communication. Access to a specific conversation is decided in the services; the permission is the capability gate.
 */
@RestController
@RequestMapping("/communication/discuss")
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DiscussController {

    private final DiscussService discuss;
    private final DiscussMessageService messages;
    private final DiscussRecordService records;
    private final DepartmentChannelSync departments;

    public DiscussController(DiscussService discuss, DiscussMessageService messages, DiscussRecordService records,
            DepartmentChannelSync departments) {
        this.discuss = discuss;
        this.messages = messages;
        this.records = records;
        this.departments = departments;
    }

    /* ── Overview ──────────────────────────────────────────────────────────── */

    @GetMapping("/sidebar")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> sidebar() {
        return discuss.sidebar();
    }

    @GetMapping("/counters")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> counters() {
        return discuss.counters();
    }

    @GetMapping("/directory")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> directory() {
        return discuss.directory();
    }

    @GetMapping("/presence")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> presence() {
        return discuss.presence();
    }

    /* ── Channels and chats ────────────────────────────────────────────────── */

    @GetMapping("/channels")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> channels(@RequestParam(required = false) String archived) {
        return discuss.browseChannels("true".equals(archived));
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> createChannel(@Valid @RequestBody CreateChannelRequest dto) {
        return discuss.createChannel(new DiscussService.ChannelInput(dto.name(), dto.description(), dto.visibility(),
                dto.memberIds()));
    }

    @PostMapping("/chats")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest dto) {
        return discuss.chat(dto.userIds());
    }

    @PostMapping("/departments/sync")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> syncDepartments() {
        return departments.sync();
    }

    /* ── One conversation ──────────────────────────────────────────────────── */

    @GetMapping("/conversations/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> detail(@PathVariable String id) {
        return discuss.detail(id);
    }

    @PatchMapping("/conversations/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateConversationRequest dto) {
        return discuss.update(id, new DiscussService.UpdateInput(dto.name(), dto.description(), dto.visibility()));
    }

    @PostMapping("/conversations/{id}/join")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> join(@PathVariable String id) {
        return discuss.join(id);
    }

    @PostMapping("/conversations/{id}/leave")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> leave(@PathVariable String id) {
        return discuss.leave(id);
    }

    @PostMapping("/conversations/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> addMembers(@PathVariable String id, @Valid @RequestBody MembersRequest dto) {
        return discuss.addMembers(id, dto.userIds());
    }

    @DeleteMapping("/conversations/{id}/members/{userId}")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> removeMember(@PathVariable String id, @PathVariable String userId) {
        return discuss.removeMember(id, userId);
    }

    @PatchMapping("/conversations/{id}/settings")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> settings(@PathVariable String id, @Valid @RequestBody SettingsRequest dto) {
        return discuss.settings(id, new DiscussService.SettingsInput(dto.notifyLevel(), dto.muteMinutes(),
                dto.pinned()));
    }

    @PostMapping("/conversations/{id}/archive")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> archive(@PathVariable String id) {
        return discuss.setArchived(id, true);
    }

    @PostMapping("/conversations/{id}/unarchive")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> unarchive(@PathVariable String id) {
        return discuss.setArchived(id, false);
    }

    @PostMapping("/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> typing(@PathVariable String id, @Valid @RequestBody TypingRequest dto) {
        return discuss.typing(id, dto.typing());
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> read(@PathVariable String id, @Valid @RequestBody(required = false) ReadRequest dto) {
        return discuss.markRead(id, dto == null ? null : dto.messageId());
    }

    @GetMapping("/conversations/{id}/messages")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> messages(@PathVariable String id, @RequestParam(required = false) String beforeSeq,
            @RequestParam(required = false) String afterSeq, @RequestParam(required = false) Integer limit) {
        return messages.page(id, beforeSeq, afterSeq, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> post(@PathVariable String id, @Valid @RequestBody PostMessageRequest dto) {
        return messages.post(id, postInput(dto));
    }

    @GetMapping("/conversations/{id}/pinned")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> pinned(@PathVariable String id) {
        return messages.pinned(id);
    }

    @GetMapping("/conversations/{id}/attachments")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> attachments(@PathVariable String id) {
        return messages.sharedFiles(id);
    }

    /* ── Messages ──────────────────────────────────────────────────────────── */

    @PatchMapping("/messages/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> edit(@PathVariable String id, @Valid @RequestBody EditMessageRequest dto) {
        return messages.edit(id, dto.body(), dto.mentionUserIds());
    }

    @DeleteMapping("/messages/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> delete(@PathVariable String id) {
        return messages.delete(id);
    }

    @PostMapping("/messages/{id}/reactions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> react(@PathVariable String id, @Valid @RequestBody ReactionRequest dto) {
        return messages.react(id, dto.emoji());
    }

    @PostMapping("/messages/{id}/pin")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> pin(@PathVariable String id) {
        return messages.pin(id);
    }

    @PostMapping("/messages/{id}/star")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> star(@PathVariable String id) {
        return messages.star(id);
    }

    @PostMapping("/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> upload(@RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return messages.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    /* ── Inbox, starred, search ────────────────────────────────────────────── */

    @GetMapping("/inbox")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> inbox(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String unreadOnly) {
        return messages.inbox(limit, "true".equals(unreadOnly));
    }

    @PostMapping("/inbox/read")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> inboxRead(@Valid @RequestBody(required = false) InboxReadRequest dto) {
        return messages.inboxRead(dto == null ? null : dto.ids());
    }

    @GetMapping("/starred")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> starred() {
        return messages.starred();
    }

    @GetMapping("/search")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String conversationId, @RequestParam(required = false) Integer limit) {
        return messages.search(q, conversationId, limit);
    }

    /* ── Record chatter ────────────────────────────────────────────────────── */

    @GetMapping("/records/{type}/{recordId}")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> record(@PathVariable String type, @PathVariable String recordId) {
        return records.get(type, recordId);
    }

    @PostMapping("/records/{type}/{recordId}/messages")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> recordPost(@PathVariable String type, @PathVariable String recordId,
            @Valid @RequestBody PostMessageRequest dto) {
        return records.post(type, recordId, postInput(dto));
    }

    @PostMapping("/records/{type}/{recordId}/follow")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> follow(@PathVariable String type, @PathVariable String recordId) {
        return records.follow(type, recordId, true);
    }

    @PostMapping("/records/{type}/{recordId}/unfollow")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> unfollow(@PathVariable String type, @PathVariable String recordId) {
        return records.follow(type, recordId, false);
    }

    private static DiscussMessageService.PostInput postInput(PostMessageRequest dto) {
        return new DiscussMessageService.PostInput(dto.body(), dto.replyToMessageId(), dto.mentionUserIds(),
                dto.attachmentIds(), dto.subtype());
    }
}
