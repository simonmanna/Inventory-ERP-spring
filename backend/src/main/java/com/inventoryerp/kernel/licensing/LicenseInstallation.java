package com.inventoryerp.kernel.licensing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * This installation's licence, as one row.
 *
 * <p>Install-scoped, not organization-scoped: the documented exception to AGENTS.md §8. A licence
 * binds the deployment, however many organizations share its database — which is why a
 * multi-organization licence carries an {@code organizations} limit rather than a row per tenant.
 * The primary key is the constant {@code installation} and a CHECK constraint keeps it that way, so
 * "the licence" is a single row by construction rather than by convention.
 */
@Entity
@Table(name = "LicenseInstallation")
public class LicenseInstallation {

    /** The only id this table ever holds. */
    public static final String ID = "installation";

    @Id
    @Column(name = "id", nullable = false)
    private String id = ID;

    /** Generated once on first boot; half of the fingerprint. */
    @Column(name = "installId", nullable = false, updatable = false)
    private String installId;

    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @Column(name = "licenseKey")
    private String licenseKey;

    @Column(name = "token")
    private String token;

    @Column(name = "tokenKid")
    private String tokenKid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "claims")
    private String claims;

    @Column(name = "mode", nullable = false)
    private String mode = LicenseMode.UNLICENSED.name();

    @Column(name = "status")
    private String status;

    @Column(name = "startsAt")
    private Instant startsAt;

    @Column(name = "expiresAt")
    private Instant expiresAt;

    @Column(name = "graceUntil")
    private Instant graceUntil;

    @Column(name = "refreshAfter")
    private Instant refreshAfter;

    @Column(name = "lastCheckAt")
    private Instant lastCheckAt;

    @Column(name = "lastCheckOk", nullable = false)
    private Boolean lastCheckOk = false;

    @Column(name = "lastCheckError")
    private String lastCheckError;

    /**
     * Newest {@code serverTime} ever seen in a token. Monotonic by construction: a local clock
     * behind it means the clock moved, not the licence.
     */
    @Column(name = "serverTimeHighWater")
    private Instant serverTimeHighWater;

    /**
     * JWKS last fetched from the authority. Cached so a restart with no network can still verify a
     * token signed by a key rotated in after this release was built — otherwise a rotation plus an
     * outage would read as tampering and take the install read-only.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trustedKeys")
    private String trustedKeys;

    /** First boot, and so the start of the trial window when no licence is ever activated. */
    @Column(name = "createdAt", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getInstallId() {
        return installId;
    }

    public void setInstallId(String installId) {
        this.installId = installId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenKid() {
        return tokenKid;
    }

    public void setTokenKid(String tokenKid) {
        this.tokenKid = tokenKid;
    }

    public String getClaims() {
        return claims;
    }

    public void setClaims(String claims) {
        this.claims = claims;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getGraceUntil() {
        return graceUntil;
    }

    public void setGraceUntil(Instant graceUntil) {
        this.graceUntil = graceUntil;
    }

    public Instant getRefreshAfter() {
        return refreshAfter;
    }

    public void setRefreshAfter(Instant refreshAfter) {
        this.refreshAfter = refreshAfter;
    }

    public Instant getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(Instant lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public Boolean getLastCheckOk() {
        return lastCheckOk;
    }

    public void setLastCheckOk(Boolean lastCheckOk) {
        this.lastCheckOk = lastCheckOk;
    }

    public String getLastCheckError() {
        return lastCheckError;
    }

    public void setLastCheckError(String lastCheckError) {
        this.lastCheckError = lastCheckError;
    }

    public Instant getServerTimeHighWater() {
        return serverTimeHighWater;
    }

    public void setServerTimeHighWater(Instant serverTimeHighWater) {
        this.serverTimeHighWater = serverTimeHighWater;
    }

    public String getTrustedKeys() {
        return trustedKeys;
    }

    public void setTrustedKeys(String trustedKeys) {
        this.trustedKeys = trustedKeys;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
