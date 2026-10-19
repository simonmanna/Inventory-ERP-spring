package com.inventoryerp.kernel.notifications;

import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /push} (port of {@code push.controller.ts}). Note: Nest gates subscribe on
 * {@code notifications:write}, which is not in the permission catalog; kept verbatim.
 */
@RestController
@RequestMapping("/push")
public class PushController {

    /** Browser PushSubscription JSON. No validation decorators in Nest (any shape accepted). */
    public record SubscribeBody(String endpoint, Keys keys, String userAgent, Map<String, Object> expirationTime) {
        public record Keys(String p256dh, String auth) {}
    }

    /** Body of {@code DELETE /push/subscribe}. */
    public record UnsubscribeBody(String endpoint) {}

    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    @Public
    @GetMapping("/vapid-public-key")
    public Map<String, Object> publicKey() {
        return Map.of("publicKey", push.getPublicKey());
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions("notifications:write")
    public Map<String, Object> subscribe(AuthUser user, HttpServletRequest req, @RequestBody SubscribeBody body) {
        String ua = body.userAgent() != null ? body.userAgent() : req.getHeader("User-Agent");
        return push.subscribe(user.organizationId(), user.sub(), body.endpoint(),
                body.keys() == null ? null : body.keys().p256dh(), body.keys() == null ? null : body.keys().auth(), ua);
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions("notifications:write")
    public void unsubscribe(@RequestBody(required = false) UnsubscribeBody body) {
        push.unsubscribe(body == null ? null : body.endpoint());
    }

    @DeleteMapping("/subscribe/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions("notifications:write")
    public void unsubscribeById(@PathVariable String id) {
        push.unsubscribeById(id);
    }
}
