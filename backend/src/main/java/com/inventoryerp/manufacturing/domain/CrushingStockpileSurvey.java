package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "CrushingStockpileSurvey"}: Stockpile survey (volume × bulk density = tonnes) and its approved adjustment (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingStockpileSurvey")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingStockpileSurvey {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "stockpileId", nullable = false)
    private String stockpileId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "surveyDate", nullable = false)
    private LocalDate surveyDate;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "surveyor")
    private String surveyor;

    @Column(name = "volumeM3", nullable = false, precision = 18, scale = 6)
    private BigDecimal volumeM3;

    @Column(name = "bulkDensity", nullable = false, precision = 18, scale = 6)
    private BigDecimal bulkDensity;

    @Column(name = "surveyedTonnes", nullable = false, precision = 18, scale = 6)
    private BigDecimal surveyedTonnes;

    @Column(name = "bookTonnes", precision = 18, scale = 6)
    private BigDecimal bookTonnes;

    @Column(name = "varianceTonnes", precision = 18, scale = 6)
    private BigDecimal varianceTonnes;

    @Column(name = "variancePct", precision = 18, scale = 6)
    private BigDecimal variancePct;

    @Column(name = "unitCost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "varianceValue", precision = 18, scale = 6)
    private BigDecimal varianceValue;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "ledgerCode")
    private String ledgerCode;

    @Column(name = "notes")
    private String notes;

    @Column(name = "submittedAt")
    private Instant submittedAt;

    @Column(name = "submittedBy")
    private String submittedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "rejectionReason")
    private String rejectionReason;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reversalReason")
    private String reversalReason;

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
