package com.inventoryerp.communication.web;

import com.inventoryerp.communication.application.TelegramWebhookService;
import com.inventoryerp.kernel.security.Public;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Inbound Telegram webhook (port of {@code telegram-webhook.controller.ts}). Public because Telegram cannot present a
 * JWT; authenticity is the per-channel secret in {@code X-Telegram-Bot-Api-Secret-Token}. Always 200
 * {@code {ok: true}} — never reveals which channel ids exist, never invites a Telegram retry.
 */
@RestController
@RequestMapping("/communication/webhooks/telegram")
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class TelegramWebhookController {

    private final TelegramWebhookService webhook;

    public TelegramWebhookController(TelegramWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/{channelId}")
    @Public
    public Map<String, Object> receive(@PathVariable String channelId,
            @RequestHeader(value = "x-telegram-bot-api-secret-token", required = false) String secret,
            @RequestBody(required = false) JsonNode update) {
        return webhook.receive(channelId, secret, update);
    }
}
