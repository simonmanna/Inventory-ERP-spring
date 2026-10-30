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
 * Table {@code "CrushingRunOutput"}: Actual output of one size fraction of a crushing run (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingRunOutput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingRunOutput {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "runId", nullable = false)
    private String runId;

    @Column(name = "gradingLineId", nullable = false)
    private String gradingLineId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "stageSequence")
    private Integer stageSequence;

    @Column(name = "stockpileId")
    private String stockpileId;

    @Column(name = "expectedPct", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedPct;

    @Column(name = "expectedTonnes", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedTonnes;

    @Column(name = "actualPct", precision = 18, scale = 6)
    private BigDecimal actualPct;

    @Column(name = "actualTonnes", nullable = false, precision = 18, scale = 6)
    private BigDecimal actualTonnes;

    @Column(name = "value", precision = 18, scale = 6)
    private BigDecimal value;

    @Column(name = "unitCost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "sortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
