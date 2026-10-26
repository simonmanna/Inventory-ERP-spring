package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.Ed25519Keys;
import com.inventoryerp.license.crypto.SecretBox;
import com.inventoryerp.license.domain.SigningKey;
import com.inventoryerp.license.domain.SigningKeyRepository;
import com.inventoryerp.license.shared.ApiException;
import com.inventoryerp.license.shared.Json;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custody of the Ed25519 keys that make a licence a licence.
 *
 * <p>One key is current and signs; retired keys stay in the JWKS until every token they signed has
 * expired, so a rotation never invalidates a licence already in the field. Private halves live
 * sealed in the database and are opened into an in-memory cache keyed by {@code kid} — the
 * passphrase never reaches a column and the cleartext key never leaves this class.
 */
@Service
public class SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final SigningKeyRepository keys;
    private final SecretBox box;
    private final Map<String, PrivateKey> openedPrivate = new ConcurrentHashMap<>();

    public SigningKeyService(SigningKeyRepository keys, LicenseProperties properties) {
        this.keys = keys;
        this.box = new SecretBox(properties.getSigningPassphrase());
    }

    /** The key that signs now, creating the very first one if the database is empty. */
    @Transactional
    public SigningKey current() {
        return keys.findByRetiredAtIsNull().orElseGet(this::createKey);
    }

    @Transactional
    public SigningKey rotate(String actor) {
        keys.findByRetiredAtIsNull().ifPresent(previous -> {
            previous.setRetiredAt(java.time.Instant.now());
            keys.save(previous);
            // The private half of a retired key is never needed again: drop it from memory at once.
            openedPrivate.remove(previous.getKid());
            log.info("Retired signing key {} (requested by {})", previous.getKid(), actor);
        });
        keys.flush();
        SigningKey created = createKey();
        log.info("New signing key {} is now current (requested by {})", created.getKid(), actor);
        return created;
    }

    /** The private half, opened and cached. */
    public PrivateKey privateKeyOf(SigningKey key) {
        return openedPrivate.computeIfAbsent(key.getKid(), kid -> {
            try {
                return Ed25519Keys.decodePrivate(box.open(key.getPrivateKeyCipher()));
            } catch (RuntimeException e) {
                // Almost always a changed LICENSE_SIGNING_PASSPHRASE. Say so plainly; do not leak the cause.
                log.error("Cannot open the private half of signing key {}", kid, e);
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "The signing key cannot be opened. Check LICENSE_SIGNING_PASSPHRASE.");
            }
        });
    }

    public PublicKey publicKeyOf(SigningKey key) {
        return Ed25519Keys.decodePublic(key.getPublicKey());
    }

    /**
     * The public key a token's {@code kid} names, current or retired. Empty for an unknown id, which
     * means the token was signed by something that is not this authority.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<PublicKey> publicKeyFor(String kid) {
        if (kid == null || kid.isBlank()) {
            return java.util.Optional.empty();
        }
        return keys.findByKid(kid).map(this::publicKeyOf);
    }

    /**
     * RFC 8037 JWKS of every key a client might still meet: the current one first, then retired ones
     * newest first. Served publicly and cacheable — it contains only public material.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> jwks() {
        List<Map<String, Object>> jwkList = new ArrayList<>();
        for (SigningKey key : keys.findAllByOrderByActiveFromDesc()) {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "OKP");
            jwk.put("crv", "Ed25519");
            jwk.put("alg", "EdDSA");
            jwk.put("use", "sig");
            jwk.put("kid", key.getKid());
            jwk.put("x", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Ed25519Keys.rawPublic(publicKeyOf(key))));
            jwk.put("status", key.isCurrent() ? "current" : "retired");
            jwkList.add(jwk);
        }
        // Current key first: a client that cannot match a kid should try that one.
        jwkList.sort((a, b) -> Boolean.compare(!"current".equals(a.get("status")), !"current".equals(b.get("status"))));
        return Map.of("keys", jwkList);
    }

    /**
     * The same public keys in the form the ERP compiles into a release
     * ({@code license-keys.json} on its classpath). Printed by the admin API so a release can be cut
     * without copying material out of the database by hand.
     */
    @Transactional(readOnly = true)
    public String pinnedKeysJson() {
        return Json.write(jwks());
    }

    private SigningKey createKey() {
        KeyPair pair = Ed25519Keys.generate();
        SigningKey row = new SigningKey();
        row.setKid(Ed25519Keys.kid(pair.getPublic()));
        row.setAlgorithm("Ed25519");
        row.setPublicKey(Ed25519Keys.encodePublic(pair.getPublic()));
        row.setPrivateKeyCipher(box.seal(Ed25519Keys.encodePrivate(pair.getPrivate())));
        SigningKey saved = keys.saveAndFlush(row);
        openedPrivate.put(saved.getKid(), pair.getPrivate());
        return saved;
    }
}
