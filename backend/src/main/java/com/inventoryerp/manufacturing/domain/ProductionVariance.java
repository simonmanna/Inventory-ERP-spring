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
 * Table {@code "ProductionVariance"}: Analytic variance of a completed order vs standard (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "ProductionVariance")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionVariance {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "productId")
    private String productId;

    @Column(name = "standardQty", precision = 20, scale = 6)
    private BigDecimal standardQty;

    @Column(name = "actualQty", precision = 20, scale = 6)
    private BigDecimal actualQty;

    @Column(name = "standardCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal standardCost;

    @Column(name = "actualCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal actualCost;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
