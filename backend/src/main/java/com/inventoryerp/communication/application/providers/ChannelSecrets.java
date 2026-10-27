package com.inventoryerp.communication.application.providers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code CommunicationChannel.config} with its credentials sealed at rest (docs/SECURITY.md: channel credentials
 * encrypted with {@code COMM_ENCRYPTION_KEY}). The credential keys ({@link #SECRET_KEYS}) are stored as
 * {@code enc:v1:<iv:tag:ciphertext>}; other keys stay plain. A plain credential written by Nest still reads.
 * Responses never carry a credential: {@link #mask} replaces each with {@value #MASK}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class ChannelSecrets {

    private static final Logger log = LoggerFactory.getLogger(ChannelSecrets.class);

    /** Telegram {@code botToken}/{@code webhookSecret}, WhatsApp Cloud {@code accessToken}. */
    public static final Set<String> SECRET_KEYS = Set.of("botToken", "webhookSecret", "accessToken");
    public static final String PREFIX = "enc:v1:";
    public static final String MASK = "********";

    private final CommSecretService secrets;
    private final JsonMapper mapper;
    private final JdbcClient jdbc;

    public ChannelSecrets(CommSecretService secrets, JsonMapper mapper, JdbcClient jdbc) {
        this.secrets = secrets;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    /** The config to store: credentials encrypted when the key is configured. */
    public Map<String, Object> seal(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config == null ? Map.of() : config);
        for (String k : SECRET_KEYS) {
            if (out.get(k) instanceof String s && !s.isEmpty() && !s.startsWith(PREFIX)) {
                if (secrets.configured()) {
                    out.put(k, PREFIX + secrets.encrypt(s));
                } else {
                    log.warn("COMM_ENCRYPTION_KEY is not set — channel credential '{}' stored unencrypted.", k);
                }
            }
        }
        return out;
    }

    /** The stored config with credentials decrypted (provider use only — never returned). */
    public Map<String, Object> open(Map<String, Object> stored) {
        Map<String, Object> out = new LinkedHashMap<>(stored == null ? Map.of() : stored);
        for (String k : SECRET_KEYS) {
            if (out.get(k) instanceof String s && s.startsWith(PREFIX)) {
                out.put(k, secrets.decrypt(s.substring(PREFIX.length())));
            }
        }
        return out;
    }

    /** The config safe for a response: each present credential becomes {@value #MASK}. */
    public static Map<String, Object> mask(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config == null ? Map.of() : config);
        for (String k : SECRET_KEYS) {
            if (out.get(k) != null) {
                out.put(k, MASK);
            }
        }
        return out;
    }

    /** The decrypted config of a channel (unscoped read, Nest {@code prisma.raw}); empty when missing. */
    public Map<String, Object> channelConfig(String channelId) {
        String json = jdbc.sql("SELECT config::text FROM \"CommunicationChannel\" WHERE id = ?")
                .param(channelId).query(String.class).optional().orElse(null);
        return open(parse(json));
    }

    public Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object v = mapper.readValue(json, Object.class);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return new LinkedHashMap<>(cast);
        }
        return new LinkedHashMap<>();
    }

    public String toJson(Map<String, Object> config) {
        return mapper.writeValueAsString(config);
    }
}
