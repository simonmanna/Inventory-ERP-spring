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
 * Table {@code "ProductPlanning"}: Per-product MRP parameters (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "ProductPlanning")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductPlanning {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "procurement", nullable = false)
    private String procurement;

    @Column(name = "leadTimeDays", nullable = false)
    private Integer leadTimeDays;

    @Column(name = "safetyStock", nullable = false, precision = 20, scale = 6)
    private BigDecimal safetyStock;

    @Column(name = "reorderPoint", nullable = false, precision = 20, scale = 6)
    private BigDecimal reorderPoint;

    @Column(name = "minOrderQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal minOrderQty;

    @Column(name = "orderMultiple", nullable = false, precision = 20, scale = 6)
    private BigDecimal orderMultiple;

    @Column(name = "supplierId")
    private String supplierId;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
