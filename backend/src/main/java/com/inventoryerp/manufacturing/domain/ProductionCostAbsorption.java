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
 * Table {@code "ProductionCostAbsorption"}: Actual cost absorbed for a work order, one row per kind and run (ADR-S018).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionCostAbsorption")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionCostAbsorption {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "workOrderId", nullable = false)
    private String workOrderId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "stageId")
    private String stageId;

    @Column(name = "stagePostingId")
    private String stagePostingId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "basisQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal basisQty;

    @Column(name = "rate", nullable = false, precision = 20, scale = 6)
    private BigDecimal rate;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
