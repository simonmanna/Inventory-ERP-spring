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
 * Table {@code "MfgCostAllocationRule"}: Joint-cost allocation rule (organization default or BOM override) (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "MfgCostAllocationRule")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class MfgCostAllocationRule {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "mainWeightPerUnit", precision = 20, scale = 6)
    private BigDecimal mainWeightPerUnit;

    @Column(name = "mainMarketValue", precision = 20, scale = 6)
    private BigDecimal mainMarketValue;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
