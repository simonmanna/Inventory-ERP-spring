package com.inventoryerp.kernel.notifications;

import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

/** {@code /notifications} (port of {@code notifications.controller.ts}). */
@RestController
@RequestMapping("/notifications")
@Validated
public class NotificationsController {

    /** Per-channel, per-category opt-out. */
    public record UpdatePreferenceRequest(
            @NotNull(message = "channel must be one of the following values: in_app, email, sms, push")
            @OneOf(value = {"in_app", "email", "sms", "push"}) String channel,
            String category,
            @NotNull(message = "enabled must be a boolean value") Boolean enabled) {}

    private final NotificationInboxService inbox;
    private final NotificationsService notifications;

    public NotificationsController(NotificationInboxService inbox, NotificationsService notifications) {
        this.inbox = inbox;
        this.notifications = notifications;
    }

    @GetMapping
    public Map<String, Object> list(AuthUser user,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) @OneOf(value = {"all", "unread"}) String filter) {
        return inbox.list(user, page, pageSize, filter);
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> markRead(AuthUser user, @PathVariable String id) {
        inbox.markRead(user, id);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> markAllRead(AuthUser user) {
        int count = inbox.markAllRead(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", count);
        return out;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(AuthUser user, @PathVariable String id) {
        inbox.remove(user, id);
        return Map.of("ok", true);
    }

    @GetMapping("/preferences")
    public List<Map<String, Object>> prefs(AuthUser user) {
        return inbox.preferences(user);
    }

    @PatchMapping("/preferences")
    public Map<String, Object> setPref(AuthUser user, @Valid @RequestBody UpdatePreferenceRequest dto) {
        return inbox.setPreference(user, dto.channel(), dto.category(), dto.enabled());
    }

    /** Manual trigger for testing. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> test(AuthUser user) {
        return notifications.send(new NotificationsService.SendInput(user.organizationId(), user.sub(), "in_app",
                "general", "Hello from ERP", "This is a test notification.", null));
    }
}
