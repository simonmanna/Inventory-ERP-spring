package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetLoadLine"}: Product quantity on a load with its stock issue and delivered / returned / billable quantities (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetLoadLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetLoadLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "loadId", nullable = false)
    private String loadId;

    @Column(name = "deliveryOrderLineId", nullable = false)
    private String deliveryOrderLineId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "uomFactor", nullable = false, precision = 20, scale = 6)
    private BigDecimal uomFactor;

    @Column(name = "plannedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal plannedQty;

    @Column(name = "loadedQty", precision = 14, scale = 3)
    private BigDecimal loadedQty;

    @Column(name = "dispatchedQty", precision = 14, scale = 3)
    private BigDecimal dispatchedQty;

    @Column(name = "deliveredQty", precision = 14, scale = 3)
    private BigDecimal deliveredQty;

    @Column(name = "returnedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQty;

    @Column(name = "billableQty", precision = 14, scale = 3)
    private BigDecimal billableQty;

    @Column(name = "tonnesPerUnit", precision = 14, scale = 6)
    private BigDecimal tonnesPerUnit;

    @Column(name = "m3PerUnit", precision = 14, scale = 6)
    private BigDecimal m3PerUnit;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "unitCost", precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "stockValue", precision = 20, scale = 6)
    private BigDecimal stockValue;

    @Column(name = "returnLedgerCode")
    private String returnLedgerCode;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
