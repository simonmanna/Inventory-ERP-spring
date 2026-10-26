package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One sale: a key, a customer, a plan, a term, and whatever was negotiated away from the plan.
 *
 * <p>Nullable caps and {@code featureOverrides} mean "take the plan's value". That distinction
 * matters: a licence that inherits its limits picks up a plan change, one that overrides does not.
 */
@Entity
@Table(name = "license")
public class License {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "status", nullable = false)
    private String status = LicenseStatus.PENDING.value();

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "starts_at")
    private Instant startsAt;

    /** {@code null} only for a PERPETUAL licence. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "grace_days", nullable = false)
    private Integer graceDays = 7;

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew = false;

    @Column(name = "instance_limit")
    private Integer instanceLimit;

    @Column(name = "max_devices")
    private Integer maxDevices;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_organizations")
    private Integer maxOrganizations;

    /** JSON array of feature keys replacing the plan's set, or {@code null} to inherit it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_overrides")
    private String featureOverrides;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by")
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Renew, suspend and revoke all read-then-write. Optimistic locking makes two admins racing on
     * the same licence a 409 rather than a silently lost decision.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public LicenseStatus statusType() {
        return LicenseStatus.of(status);
    }

    public void setStatusType(LicenseStatus status) {
        this.status = status.value();
    }

    /** End of the window in which an expired licence still works. */
    public Instant graceUntil() {
        if (expiresAt == null) {
            return null;
        }
        return expiresAt.plus(java.time.Duration.ofDays(graceDays == null ? 0 : graceDays));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
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

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public Boolean getAutoRenew() {
        return autoRenew;
    }

    public void setAutoRenew(Boolean autoRenew) {
        this.autoRenew = autoRenew;
    }

    public Integer getInstanceLimit() {
        return instanceLimit;
    }

    public void setInstanceLimit(Integer instanceLimit) {
        this.instanceLimit = instanceLimit;
    }

    public Integer getMaxDevices() {
        return maxDevices;
    }

    public void setMaxDevices(Integer maxDevices) {
        this.maxDevices = maxDevices;
    }

    public Integer getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(Integer maxUsers) {
        this.maxUsers = maxUsers;
    }

    public Integer getMaxOrganizations() {
        return maxOrganizations;
    }

    public void setMaxOrganizations(Integer maxOrganizations) {
        this.maxOrganizations = maxOrganizations;
    }

    public String getFeatureOverrides() {
        return featureOverrides;
    }

    public void setFeatureOverrides(String featureOverrides) {
        this.featureOverrides = featureOverrides;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
