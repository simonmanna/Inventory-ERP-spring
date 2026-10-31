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
 * Table {@code "CrushingGradingLine"}: One size fraction of a grading profile (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingGradingLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingGradingLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "profileId", nullable = false)
    private String profileId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "sizeLabel")
    private String sizeLabel;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "stageSequence")
    private Integer stageSequence;

    @Column(name = "expectedPct", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedPct;

    @Column(name = "stocked", nullable = false)
    private Boolean stocked;

    @Column(name = "stockpileId")
    private String stockpileId;

    @Column(name = "valuation", nullable = false)
    private String valuation;

    @Column(name = "nrvUnitValue", precision = 18, scale = 6)
    private BigDecimal nrvUnitValue;

    @Column(name = "marketValue", precision = 18, scale = 6)
    private BigDecimal marketValue;

    @Column(name = "fixedPct", precision = 18, scale = 6)
    private BigDecimal fixedPct;

    @Column(name = "sortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
