package com.inventoryerp.communication.application.providers;

import java.util.Map;

/**
 * Outbound HTTPS for the external providers (Telegram Bot API, WhatsApp Cloud API). A bean so tests replace it
 * and never touch the network.
 */
public interface ProviderHttp {

    /** A response: status, body text and the headers the providers read ({@code retry-after}). */
    record Response(int status, String body, Map<String, String> headers) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    /** POST {@code jsonBody}; a network failure throws (the caller classifies it as ambiguous). */
    Response postJson(String url, Map<String, String> headers, String jsonBody) throws Exception;
}
