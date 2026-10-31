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
 * Table {@code "FleetFreightBill"}: Transporter freight bill (fleet 1.9): closed trips of a transporter and period
 * matched against the supplier's invoice (AP draft bill + accrual), or inbound freight capitalised as landed cost.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFreightBill")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFreightBill {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "direction", nullable = false)
    private String direction;

    @Column(name = "transporterPartnerId", nullable = false)
    private String transporterPartnerId;

    @Column(name = "periodFrom")
    private LocalDate periodFrom;

    @Column(name = "periodTo")
    private LocalDate periodTo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "tripCount", nullable = false)
    private Integer tripCount;

    @Column(name = "totalAmount", nullable = false, precision = 16, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currencyCode")
    private String currencyCode;

    @Column(name = "supplierReference")
    private String supplierReference;

    @Column(name = "supplierInvoiceDate")
    private LocalDate supplierInvoiceDate;

    @Column(name = "supplierAmount", precision = 16, scale = 2)
    private BigDecimal supplierAmount;

    @Column(name = "varianceAmount", precision = 16, scale = 2)
    private BigDecimal varianceAmount;

    @Column(name = "variancePct", precision = 9, scale = 3)
    private BigDecimal variancePct;

    @Column(name = "allowedVariance", precision = 16, scale = 2)
    private BigDecimal allowedVariance;

    @Column(name = "varianceAccepted", nullable = false)
    private Boolean varianceAccepted;

    @Column(name = "varianceAcceptedBy")
    private String varianceAcceptedBy;

    @Column(name = "vendorBillId")
    private String vendorBillId;

    @Column(name = "vendorBillNumber")
    private String vendorBillNumber;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "postingKey")
    private String postingKey;

    @Column(name = "reversalEntryId")
    private String reversalEntryId;

    @Column(name = "goodsReceiptId")
    private String goodsReceiptId;

    @Column(name = "landedCostId")
    private String landedCostId;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "matchedAt")
    private Instant matchedAt;

    @Column(name = "matchedBy")
    private String matchedBy;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelledBy")
    private String cancelledBy;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

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
