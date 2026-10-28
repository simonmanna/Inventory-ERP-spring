package com.inventoryerp.communication.web;

import com.inventoryerp.communication.application.ChannelService;
import com.inventoryerp.communication.application.CommunicationConfigService;
import com.inventoryerp.communication.application.CommunicationStreamService;
import com.inventoryerp.communication.application.ConversationService;
import com.inventoryerp.communication.application.MessageService;
import com.inventoryerp.communication.web.CommunicationDtos.CreateChannelRequest;
import com.inventoryerp.communication.web.CommunicationDtos.CreateConversationRequest;
import com.inventoryerp.communication.web.CommunicationDtos.DisconnectChannelRequest;
import com.inventoryerp.communication.web.CommunicationDtos.MarkReadRequest;
import com.inventoryerp.communication.web.CommunicationDtos.ResolveContextRequest;
import com.inventoryerp.communication.web.CommunicationDtos.SendMessageRequest;
import com.inventoryerp.communication.web.CommunicationDtos.SetEnabledRequest;
import com.inventoryerp.communication.web.CommunicationDtos.UpsertRuleRequest;
import com.inventoryerp.communication.web.CommunicationDtos.UpsertTemplateRequest;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unified inbox API {@code /communication} (port of {@code communication.controller.ts}). Static routes
 * ({@code /stream}, {@code /conversations/resolve-context}, {@code /rules/eventable}) win over {@code /{id}} by
 * Spring's specificity rules. Per-conversation access is enforced inside the services; the permission here is only
 * the capability gate.
 */
@RestController
@RequestMapping("/communication")
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class CommunicationController {

    private final ConversationService conversations;
    private final MessageService messages;
    private final CommunicationStreamService stream;
    private final CommunicationConfigService config;
    private final ChannelService channels;

    public CommunicationController(ConversationService conversations, MessageService messages,
            CommunicationStreamService stream, CommunicationConfigService config, ChannelService channels) {
        this.conversations = conversations;
        this.messages = messages;
        this.stream = stream;
        this.config = config;
        this.channels = channels;
    }

    /* ── SSE ─────────────────────────────────────────────────────────────────── */

    /** Live inbox stream. Auth via query token (EVENT_STREAM_PATHS). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public SseEmitter streamInbox() {
        return stream.subscribe(TenantContext.organizationId(), TenantContext.userId(), TenantContext.permissions());
    }

    /* ── Conversations ───────────────────────────────────────────────────────── */

    @GetMapping("/conversations")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> list() {
        return conversations.list();
    }

    @PostMapping("/conversations")
    @Idempotent
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> create(@Valid @RequestBody CreateConversationRequest dto) {
        return conversations.create(new ConversationService.CreateInput(dto.kind(), dto.name(),
                dto.participantUserIds(), dto.contextType(), dto.contextId(), dto.visibility(),
                dto.visibleToPermissions(), null));
    }

    /** Chatter: get-or-create the conversation attached to a business record. */
    @PostMapping("/conversations/resolve-context")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_WRITE)
    public Map<String, Object> resolveContext(@Valid @RequestBody ResolveContextRequest dto) {
        return conversations.getOrCreateForContext(dto.contextType(), dto.contextId());
    }

    @GetMapping("/conversations/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return conversations.get(id);
    }

    @GetMapping("/conversations/{id}/messages")
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public List<Map<String, Object>> listMessages(@PathVariable String id,
            @RequestParam(required = false) String beforeSeq, @RequestParam(required = false) String limit) {
        Integer take = null;
        if (limit != null && !limit.isEmpty()) {
            // Nest: Number(limit), then Math.min(200, Math.max(1, n ?? 50)) — NaN propagates to the DB as no limit.
            double n = JsNumbers.number(limit);
            take = Double.isNaN(n) ? 50 : (int) Math.max(1, Math.min(200, Math.floor(n)));
        }
        return messages.list(id, beforeSeq, take);
    }

    @PostMapping("/conversations/{id}/messages")
    @Idempotent
    @RequirePermissions(Permissions.COMMUNICATION__MESSAGE_SEND)
    public Map<String, Object> send(@PathVariable String id, @Valid @RequestBody SendMessageRequest dto) {
        return messages.send(new MessageService.SendInput(id, dto.body(), dto.replyToMessageId(),
                dto.targetConversationChannelIds()));
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CONVERSATION_READ)
    public Map<String, Object> markRead(@PathVariable String id,
            @Valid @RequestBody(required = false) MarkReadRequest dto) {
        return conversations.markRead(id, dto == null ? null : dto.messageId());
    }

    /* ── Templates (admin) ──────────────────────────────────────────────────── */

    @GetMapping("/templates")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public List<Map<String, Object>> listTemplates() {
        return config.listTemplates();
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> upsertTemplate(@Valid @RequestBody UpsertTemplateRequest dto) {
        return config.upsertTemplate(new CommunicationConfigService.TemplateInput(dto.key(), dto.eventName(),
                dto.providerId(), dto.locale(), dto.subject(), dto.body(), dto.active()));
    }

    /* ── Rules (admin) ──────────────────────────────────────────────────────── */

    @GetMapping("/rules/eventable")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public List<String> eventable() {
        return config.eventableEvents();
    }

    @GetMapping("/rules")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public List<Map<String, Object>> listRules() {
        return config.listRules();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> upsertRule(@Valid @RequestBody UpsertRuleRequest dto) {
        return config.upsertRule(new CommunicationConfigService.RuleInput(dto.eventName(), dto.templateKey(),
                dto.recipientResolver(), dto.channelSelector(), dto.condition(), dto.enabled()));
    }

    @PatchMapping("/rules/{id}/enabled")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> setRuleEnabled(@PathVariable String id, @Valid @RequestBody SetEnabledRequest dto) {
        return config.setRuleEnabled(id, dto.enabled());
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return config.deleteRule(id);
    }

    /* ── Channels (admin) ───────────────────────────────────────────────────── */

    @GetMapping("/channels")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_READ)
    public List<Map<String, Object>> listChannels() {
        return channels.list();
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> createChannel(@Valid @RequestBody CreateChannelRequest dto) {
        return channels.create(new ChannelService.CreateInput(dto.providerId(), dto.name(), dto.transport(),
                dto.config()));
    }

    @GetMapping("/channels/{id}/pairing")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_READ)
    public Map<String, Object> pairingState(@PathVariable String id) {
        return channels.pairingState(id);
    }

    @PostMapping("/channels/{id}/connect")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> connectChannel(@PathVariable String id) {
        return channels.connect(id);
    }

    @PostMapping("/channels/{id}/disconnect")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> disconnectChannel(@PathVariable String id,
            @Valid @RequestBody(required = false) DisconnectChannelRequest dto) {
        return channels.disconnect(id, dto != null && Boolean.TRUE.equals(dto.logout()));
    }

    @DeleteMapping("/channels/{id}")
    @RequirePermissions(Permissions.COMMUNICATION__CHANNEL_MANAGE)
    public Map<String, Object> removeChannel(@PathVariable String id) {
        return channels.remove(id);
    }
}
