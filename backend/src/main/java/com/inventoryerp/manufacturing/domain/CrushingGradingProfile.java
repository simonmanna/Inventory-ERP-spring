package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "CrushingGradingProfile"}: Expected size split of a plant × feed material; owns a system-managed BOM (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingGradingProfile")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingGradingProfile {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "plantId", nullable = false)
    private String plantId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "feedProductId", nullable = false)
    private String feedProductId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "allocationMethod", nullable = false)
    private String allocationMethod;

    @Column(name = "expectedRecoveryPct", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedRecoveryPct;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "routingId")
    private String routingId;

    @Column(name = "activatedAt")
    private Instant activatedAt;

    @Column(name = "activatedBy")
    private String activatedBy;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "deletedAt")
    private Instant deletedAt;
}
