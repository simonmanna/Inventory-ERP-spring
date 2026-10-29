package com.inventoryerp.fleet.domain;

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
 * Table {@code "FleetWorkOrderLine"}: Cost line of a fleet work order: spare part issued / returned, labour,
 * external workshop bill (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetWorkOrderLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetWorkOrderLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workOrderId", nullable = false)
    private String workOrderId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "lineType", nullable = false)
    private String lineType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "description")
    private String description;

    @Column(name = "productId")
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "quantity", precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "returnedQuantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal returnedQuantity;

    @Column(name = "returnOfLineId")
    private String returnOfLineId;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "employeeId")
    private String employeeId;

    @Column(name = "hours", precision = 10, scale = 2)
    private BigDecimal hours;

    @Column(name = "workDate")
    private LocalDate workDate;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "supplierReference")
    private String supplierReference;

    @Column(name = "billDate")
    private LocalDate billDate;

    @Column(name = "vendorBillId")
    private String vendorBillId;

    @Column(name = "unitCost", precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "postingKey")
    private String postingKey;

    @Column(name = "reversalEntryId")
    private String reversalEntryId;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reverseReason")
    private String reverseReason;

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
