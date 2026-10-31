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
 * Table {@code "ProductionDemandPeg"}: Demand a plan line (and the order it generates) serves (ADR-S017).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionDemandPeg")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionDemandPeg {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "planId", nullable = false)
    private String planId;

    @Column(name = "planLineId", nullable = false)
    private String planLineId;

    @Column(name = "productionOrderId")
    private String productionOrderId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "demandType", nullable = false)
    private String demandType;

    @Column(name = "demandId")
    private String demandId;

    @Column(name = "demandLineId")
    private String demandLineId;

    @Column(name = "demandRef")
    private String demandRef;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "siteId")
    private String siteId;

    @Column(name = "dueAt", nullable = false)
    private Instant dueAt;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
