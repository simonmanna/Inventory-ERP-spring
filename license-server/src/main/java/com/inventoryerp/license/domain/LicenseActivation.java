package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One install bound to one licence. The {@code fingerprint} is what the ERP derives from its install
 * UUID and its Postgres cluster id; the pair (licence, fingerprint) is unique, so re-activating the
 * same install is idempotent and does not consume a second slot.
 */
@Entity
@Table(name = "license_activation")
public class LicenseActivation {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "license_id", nullable = false)
    private String licenseId;

    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @Column(name = "instance_name")
    private String instanceName;

    @Column(name = "hostname")
    private String hostname;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "status", nullable = false)
    private String status = ActivationStatus.ACTIVE.value();

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @PrePersist
    void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    public boolean isActive() {
        return ActivationStatus.ACTIVE.value().equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(Instant deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }
}
