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
 * Table {@code "ProductionTarget"}: Production target per work centre, crushing plant or product for a period (ADR-S019).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionTarget")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionTarget {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "plantId")
    private String plantId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "periodFrom", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "periodTo", nullable = false)
    private LocalDate periodTo;

    @Column(name = "targetQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal targetQty;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
