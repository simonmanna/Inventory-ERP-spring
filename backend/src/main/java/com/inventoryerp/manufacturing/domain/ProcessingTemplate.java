package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "ProcessingTemplate"}: Versioned recipe of one processing step (ADR-S060).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingTemplate")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingTemplate {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "allocationMethod", nullable = false)
    private String allocationMethod;

    @Column(name = "yieldBasisUnit", nullable = false)
    private String yieldBasisUnit;

    @Column(name = "expectedYieldPct", nullable = false, precision = 7, scale = 4)
    private BigDecimal expectedYieldPct;

    @Column(name = "qcGate", nullable = false)
    private Boolean qcGate;

    @Column(name = "qcCheckpointCode")
    private String qcCheckpointCode;

    @Column(name = "defaultInputLocationId")
    private String defaultInputLocationId;

    @Column(name = "defaultOutputLocationId")
    private String defaultOutputLocationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costRates")
    private String costRates;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "routingId")
    private String routingId;

    @Column(name = "activatedAt")
    private Instant activatedAt;

    @Column(name = "activatedBy")
    private String activatedBy;

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
