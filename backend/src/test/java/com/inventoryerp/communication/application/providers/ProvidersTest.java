package com.inventoryerp.communication.application.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure provider helpers: secret sealing, Telegram error classification, address normalisation, masking. */
class ProvidersTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    @DisplayName("CommSecretService round-trips with a fresh IV each time and rejects bad keys and ciphertext")
    void secrets() {
        CommSecretService svc = new CommSecretService(KEY);
        assertThat(svc.configured()).isTrue();
        String a = svc.encrypt("123:SECRET");
        String b = svc.encrypt("123:SECRET");
        assertThat(a).isNotEqualTo(b).matches("^[^:]+:[^:]+:[^:]+$");
        assertThat(svc.decrypt(a)).isEqualTo("123:SECRET");

        String[] parts = a.split(":");
        String tampered = parts[0] + ":" + parts[1] + ":" + "AAAA" + parts[2].substring(4);
        assertThatThrownBy(() -> svc.decrypt(tampered)).hasMessage("Communication secret decryption failed.");
        assertThatThrownBy(() -> svc.decrypt("nope")).hasMessage("Malformed communication ciphertext.");
        assertThatThrownBy(() -> svc.decrypt(null)).hasMessage("Malformed communication ciphertext.");

        CommSecretService none = new CommSecretService("");
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x"))
                .hasMessage("COMM_ENCRYPTION_KEY is not set — cannot encrypt communication secrets.");
        assertThatThrownBy(() -> new CommSecretService("c2hvcnQ=").encrypt("x"))
                .hasMessage("COMM_ENCRYPTION_KEY must decode to 32 bytes (base64).");
        assertThatThrownBy(() -> new CommSecretService("***").encrypt("x"))
                .hasMessage("COMM_ENCRYPTION_KEY must decode to 32 bytes (base64).");
    }

    @Test
    @DisplayName("Telegram: 403 / chat-not-found are terminal, 429 honours retry_after, 5xx and network retry")
    void telegramClassify() {
        ProviderSendError blocked = TelegramProvider.classifyError(403, "Forbidden: bot was blocked by the user", null);
        assertThat(blocked.code()).isEqualTo("blocked");
        assertThat(blocked.retryable()).isFalse();
        ProviderSendError chat = TelegramProvider.classifyError(400, "Bad Request: chat not found", null);
        assertThat(chat.code()).isEqualTo("chat_not_found");
        assertThat(chat.retryable()).isFalse();
        ProviderSendError other400 = TelegramProvider.classifyError(400, "Bad Request: message is too long", null);
        assertThat(other400.code()).isEqualTo("telegram_400");
        assertThat(other400.retryable()).isFalse();
        ProviderSendError limited = TelegramProvider.classifyError(429, "Too Many Requests", 7L);
        assertThat(limited.code()).isEqualTo("rate_limited");
        assertThat(limited.retryAfterMs()).isEqualTo(7000L);
        assertThat(TelegramProvider.classifyError(429, "Too Many Requests", null).retryAfterMs()).isEqualTo(1000L);
        assertThat(TelegramProvider.classifyError(502, "Bad Gateway", null).retryable()).isTrue();
        ProviderSendError net = TelegramProvider.classifyError(null, "unknown", null);
        assertThat(net.code()).isEqualTo("telegram_net");
        assertThat(net.retryable()).isTrue();
    }

    @Test
    @DisplayName("address normalisation and the stateless provider hooks")
    void addresses() {
        WhatsAppCloudProvider wa = new WhatsAppCloudProvider(null, null, null, "https://graph.test");
        assertThat(wa.id()).isEqualTo("whatsapp");
        assertThat(wa.normalizeAddress("+256 700-123456")).isEqualTo("256700123456");
        assertThat(wa.normalizeAddress("256700123456@s.whatsapp.net")).isEqualTo("256700123456");
        wa.connect("c");
        wa.disconnect("c", true);
        TelegramProvider tg = new TelegramProvider(null, null, null, "", "https://tg.test");
        assertThat(tg.normalizeAddress("12345")).isEqualTo("12345");
        tg.connect("c");
        tg.disconnect("c", false);
        tg.markRead("c", "x", "y");
        InternalProvider internal = new InternalProvider();
        assertThat(internal.isReady("c")).isTrue();
        assertThat(internal.health("c").status()).isEqualTo("connected");
        assertThat(internal.normalizeAddress("abc")).isEqualTo("abc");
        internal.connect("c");
        internal.disconnect("c", true);
        internal.markRead("c", "x", "y");
    }

    @Test
    @DisplayName("mask hides only the credential keys")
    void mask() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("botToken", "enc:v1:abc");
        cfg.put("accessToken", "plain");
        cfg.put("phoneNumberId", "PN1");
        Map<String, Object> masked = ChannelSecrets.mask(cfg);
        assertThat(masked).containsEntry("botToken", ChannelSecrets.MASK).containsEntry("accessToken",
                ChannelSecrets.MASK).containsEntry("phoneNumberId", "PN1");
        assertThat(new ProviderHttp.Response(204, "", Map.of("Retry-After", "3")).header("retry-after"))
                .isEqualTo("3");
        assertThat(new ProviderHttp.Response(429, "", Map.of()).ok()).isFalse();
    }
}
