package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetDeliveryOrder"}: Delivery order from a sales order, an invoice or manual entry (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDeliveryOrder")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDeliveryOrder {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "partnerId", nullable = false)
    private String partnerId;

    @Column(name = "siteId")
    private String siteId;

    @Column(name = "sourceType", nullable = false)
    private String sourceType;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "sourceNumber")
    private String sourceNumber;

    @Column(name = "stockMode", nullable = false)
    private String stockMode;

    @Column(name = "goodsInvoiceId")
    private String goodsInvoiceId;

    @Column(name = "goodsInvoiceNumber")
    private String goodsInvoiceNumber;

    @Column(name = "goodsInvoiceKind")
    private String goodsInvoiceKind;

    @Column(name = "chargesInvoiceId")
    private String chargesInvoiceId;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "locationId", nullable = false)
    private String locationId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "requestedDate", nullable = false)
    private LocalDate requestedDate;

    @Column(name = "windowFrom")
    private String windowFrom;

    @Column(name = "windowTo")
    private String windowTo;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "contactName")
    private String contactName;

    @Column(name = "contactPhone")
    private String contactPhone;

    @Column(name = "notes")
    private String notes;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "closeReason")
    private String closeReason;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
