package com.inventoryerp.plm.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmLifecycleException"}: an authorized, time-boxed exception to the lifecycle policy for one product
 * and one activity (ADR-S086 §6). Revoked rows stay as the audit trail.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and schema
 * validation.
 */
@Entity
@Table(name = "PlmLifecycleException")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmLifecycleException {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "activity", nullable = false)
    private String activity;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "validUntil", nullable = false)
    private LocalDate validUntil;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "revokedAt")
    private Instant revokedAt;

    @Column(name = "revokedBy")
    private String revokedBy;

    @Column(name = "revokeReason")
    private String revokeReason;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
