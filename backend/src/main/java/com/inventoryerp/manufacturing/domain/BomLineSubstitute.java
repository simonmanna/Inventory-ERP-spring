package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "BomLineSubstitute"}: Alternative component of a BOM line (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "BomLineSubstitute")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class BomLineSubstitute {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "bomLineId", nullable = false)
    private String bomLineId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "ratio", nullable = false, precision = 20, scale = 6)
    private BigDecimal ratio;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "notes")
    private String notes;

    public String getId() {
        return id;
    }
}
