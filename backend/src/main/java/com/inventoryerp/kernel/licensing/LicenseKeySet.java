package com.inventoryerp.kernel.licensing;

import com.inventoryerp.shared.web.EntityJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The public keys this installation will trust, pinned by key id.
 *
 * <p>Three sources, in order of trustworthiness:
 *
 * <ol>
 *   <li><b>{@code license-keys.json} on the classpath</b> — compiled into the release. This is the
 *       root of trust: it cannot be changed without replacing the binary.
 *   <li><b>{@code trustedKeys} on the licence row</b> — a JWKS previously fetched from the authority
 *       and kept. Without this, rotating a key and then losing the network would look like
 *       tampering and take the install read-only.
 *   <li><b>A live fetch</b> of {@code /.well-known/license-keys.json}, which is how a key rotated in
 *       after this release was built becomes known at all.
 * </ol>
 *
 * <p>Keys are only ever <em>added</em>, never replaced: a kid already known keeps the key it was
 * first seen with, so a hostile or mistaken JWKS cannot substitute a key for an existing id and
 * make forged licences verify. The authority is identified by its keys, not by its hostname.
 */
@Component
public class LicenseKeySet {

    private static final Logger log = LoggerFactory.getLogger(LicenseKeySet.class);

    /** Pinned keys shipped with the release. Produced by the authority's {@code /admin/keys/pinned}. */
    static final String CLASSPATH_KEYS = "license-keys.json";

    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();
    private final Map<String, String> rawJwks = new ConcurrentHashMap<>();

    public LicenseKeySet() {
        loadPinned();
    }

    /** The key a token's {@code kid} names, if this install trusts it. */
    public Optional<PublicKey> get(String kid) {
        return kid == null ? Optional.empty() : Optional.ofNullable(keys.get(kid));
    }

    public boolean knows(String kid) {
        return kid != null && keys.containsKey(kid);
    }

    public int size() {
        return keys.size();
    }

    /**
     * Add every key in a JWKS document that is not already known.
     *
     * @return how many were new, so a caller can tell whether the document was worth persisting
     */
    public int addAll(String jwksJson) {
        if (jwksJson == null || jwksJson.isBlank()) {
            return 0;
        }
        int added = 0;
        for (JsonNode jwk : jwks(jwksJson)) {
            String kid = string(jwk, "kid");
            String x = string(jwk, "x");
            if (kid == null || x == null) {
                continue;
            }
            if (!"OKP".equals(string(jwk, "kty")) || !"Ed25519".equals(string(jwk, "crv"))) {
                log.warn("Ignoring licence key {}: not an Ed25519 OKP key", kid);
                continue;
            }
            if (keys.containsKey(kid)) {
                continue; // never replace: a known id keeps the key it was first seen with
            }
            try {
                keys.put(kid, LicenseJws.fromJwkX(x));
                rawJwks.put(kid, x);
                added++;
            } catch (RuntimeException e) {
                log.warn("Ignoring unreadable licence key {}: {}", kid, e.getMessage());
            }
        }
        return added;
    }

    /** Everything trusted, as a JWKS document to persist on the licence row. */
    public String asJwks() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        rawJwks.forEach((kid, x) -> {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "OKP");
            jwk.put("crv", "Ed25519");
            jwk.put("alg", "EdDSA");
            jwk.put("use", "sig");
            jwk.put("kid", kid);
            jwk.put("x", x);
            list.add(jwk);
        });
        return EntityJson.toJson(Map.of("keys", list));
    }

    private void loadPinned() {
        ClassPathResource resource = new ClassPathResource(CLASSPATH_KEYS);
        if (!resource.exists()) {
            log.warn("No {} on the classpath: this build pins no licence keys and will have to learn "
                    + "them from the licence server before it can verify a licence.", CLASSPATH_KEYS);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            int added = addAll(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            log.info("Pinned {} licence signing key(s) from the release", added);
        } catch (IOException e) {
            log.error("Cannot read {}: {}", CLASSPATH_KEYS, e.getMessage());
        }
    }

    /** The {@code keys} array of a JWKS document, or empty for anything that is not one. */
    private static List<JsonNode> jwks(String json) {
        JsonNode root;
        try {
            root = EntityJson.tree(json);
        } catch (RuntimeException e) {
            log.warn("Ignoring an unreadable JWKS document: {}", e.getMessage());
            return List.of();
        }
        JsonNode keys = root == null ? null : root.path("keys");
        if (keys == null || !keys.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new java.util.ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            out.add(keys.get(i));
        }
        return out;
    }

    private static String string(JsonNode parent, String name) {
        JsonNode node = parent.path(name);
        return node.isString() ? node.asString() : null;
    }
}
