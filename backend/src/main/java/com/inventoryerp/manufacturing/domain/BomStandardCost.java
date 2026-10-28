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
 * Table {@code "BomStandardCost"}: Frozen standard cost of a BOM version (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "BomStandardCost")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class BomStandardCost {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "bomId", nullable = false)
    private String bomId;

    @Column(name = "bomVersion", nullable = false)
    private Integer bomVersion;

    @Column(name = "outputQuantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal outputQuantity;

    @Column(name = "materialCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal materialCost;

    @Column(name = "labourCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal labourCost;

    @Column(name = "overheadCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal overheadCost;

    @Column(name = "subcontractCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal subcontractCost;

    @Column(name = "totalCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "unitCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitCost;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown", nullable = false)
    private String breakdown;

    @Column(name = "isCurrent", nullable = false)
    private Boolean isCurrent;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "computedAt", nullable = false)
    private Instant computedAt;

    public String getId() {
        return id;
    }
}
