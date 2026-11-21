// Owned by hand since ADR-S083 O0 (additive columns from V593, V594).
package com.inventoryerp.sync.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code "SyncOpDeadLetter"} (owned by module {@code sync}). */
@Entity
@Table(name = "SyncOpDeadLetter")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class SyncOpDeadLetter {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "deviceId", nullable = false)
    private String deviceId;

    @Column(name = "opId", nullable = false)
    private String opId;

    @Column(name = "deviceSeq", nullable = false)
    private Integer deviceSeq;

    @Column(name = "opType", nullable = false)
    private String opType;

    @Column(name = "actorUserId")
    private String actorUserId;

    @Column(name = "occurredAt")
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "error", nullable = false)
    private String error;

    @Column(name = "httpStatus")
    private Integer httpStatus;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "resolvedById")
    private String resolvedById;

    @Column(name = "resolvedAt")
    private Instant resolvedAt;

    @Column(name = "resolutionNote")
    private String resolutionNote;

    // ─── ADR-S083 S5 (V593) ───

    @Column(name = "failureCode")
    private String failureCode;

    @Column(name = "retryable", nullable = false)
    private Boolean retryable;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "lastAttemptAt")
    private Instant lastAttemptAt;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "resolvedByOpId")
    private String resolvedByOpId;

    @Column(name = "investigatingById")
    private String investigatingById;

    @Column(name = "investigatingAt")
    private Instant investigatingAt;

    @Column(name = "investigationNote")
    private String investigationNote;

    // ADR-S083 O0 corrections (V594): server receipt time and supervisor recovery.
    @Column(name = "receivedAt")
    private Instant receivedAt;

    @Column(name = "recoveredById")
    private String recoveredById;

    @Column(name = "recoveredAt")
    private Instant recoveredAt;

    @Column(name = "recoveryGapAcknowledged", nullable = false)
    private Boolean recoveryGapAcknowledged;

    @CreationTimestamp
    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getOpId() {
        return opId;
    }

    public void setOpId(String opId) {
        this.opId = opId;
    }

    public Integer getDeviceSeq() {
        return deviceSeq;
    }

    public void setDeviceSeq(Integer deviceSeq) {
        this.deviceSeq = deviceSeq;
    }

    public String getOpType() {
        return opType;
    }

    public void setOpType(String opType) {
        this.opType = opType;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResolvedById() {
        return resolvedById;
    }

    public void setResolvedById(String resolvedById) {
        this.resolvedById = resolvedById;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
