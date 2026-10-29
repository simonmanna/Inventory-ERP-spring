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
 * Table {@code "FleetDeliveryOrderLine"}: Product line of a delivery order with ordered / planned / dispatched / delivered quantities (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDeliveryOrderLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDeliveryOrderLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "deliveryOrderId", nullable = false)
    private String deliveryOrderId;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "description")
    private String description;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "uomFactor", nullable = false, precision = 20, scale = 6)
    private BigDecimal uomFactor;

    @Column(name = "orderedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal orderedQty;

    @Column(name = "plannedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal plannedQty;

    @Column(name = "dispatchedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal dispatchedQty;

    @Column(name = "deliveredQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal deliveredQty;

    @Column(name = "returnedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQty;

    @Column(name = "sourceLineId")
    private String sourceLineId;

    @Column(name = "unitPrice", precision = 20, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "discountPercent", precision = 7, scale = 3)
    private BigDecimal discountPercent;

    @Column(name = "taxId")
    private String taxId;

    @Column(name = "batchNumber")
    private String batchNumber;

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
