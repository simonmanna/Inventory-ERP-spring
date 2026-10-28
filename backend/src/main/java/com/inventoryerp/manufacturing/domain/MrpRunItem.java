package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "MrpRunItem"}: Netting worksheet row of an MRP run (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "MrpRunItem")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class MrpRunItem {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "runId", nullable = false)
    private String runId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "lowLevelCode", nullable = false)
    private Integer lowLevelCode;

    @Column(name = "procurement", nullable = false)
    private String procurement;

    @Column(name = "grossRequirement", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossRequirement;

    @Column(name = "onHand", nullable = false, precision = 20, scale = 6)
    private BigDecimal onHand;

    @Column(name = "reserved", nullable = false, precision = 20, scale = 6)
    private BigDecimal reserved;

    @Column(name = "scheduledReceipts", nullable = false, precision = 20, scale = 6)
    private BigDecimal scheduledReceipts;

    @Column(name = "safetyStock", nullable = false, precision = 20, scale = 6)
    private BigDecimal safetyStock;

    @Column(name = "netRequirement", nullable = false, precision = 20, scale = 6)
    private BigDecimal netRequirement;

    @Column(name = "plannedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedQty;

    public String getId() {
        return id;
    }
}
