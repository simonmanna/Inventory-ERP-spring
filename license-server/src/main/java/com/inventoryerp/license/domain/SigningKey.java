package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An Ed25519 keypair. The public half is served in the JWKS and compiled into ERP releases; the
 * private half is stored sealed with {@code LICENSE_SIGNING_PASSPHRASE} and only ever opened in
 * memory to sign.
 *
 * <p>Exactly one key has {@code retiredAt = null} (enforced by a partial unique index): that one
 * signs. Retired keys stay listed in the JWKS until every token they signed has expired, otherwise
 * rotation would invalidate tokens in the field.
 */
@Entity
@Table(name = "signing_key")
public class SigningKey {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "kid", nullable = false, updatable = false)
    private String kid;

    @Column(name = "algorithm", nullable = false)
    private String algorithm = "Ed25519";

    /** Base64 SubjectPublicKeyInfo. */
    @Column(name = "public_key", nullable = false, updatable = false)
    private String publicKey;

    /** {@code SecretBox}-sealed base64 PKCS#8. Never logged, never returned by any route. */
    @Column(name = "private_key_cipher", nullable = false, updatable = false)
    private String privateKeyCipher;

    @Column(name = "active_from", nullable = false)
    private Instant activeFrom;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (activeFrom == null) {
            activeFrom = Instant.now();
        }
    }

    public boolean isCurrent() {
        return retiredAt == null;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPrivateKeyCipher() {
        return privateKeyCipher;
    }

    public void setPrivateKeyCipher(String privateKeyCipher) {
        this.privateKeyCipher = privateKeyCipher;
    }

    public Instant getActiveFrom() {
        return activeFrom;
    }

    public void setActiveFrom(Instant activeFrom) {
        this.activeFrom = activeFrom;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(Instant retiredAt) {
        this.retiredAt = retiredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
