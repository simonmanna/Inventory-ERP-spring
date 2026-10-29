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
 * Table {@code "FleetDeliveryDiscrepancy"}: Delivered-vs-dispatched discrepancy routed through the approval engine (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDeliveryDiscrepancy")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDeliveryDiscrepancy {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "deliveryOrderId", nullable = false)
    private String deliveryOrderId;

    @Column(name = "loadId", nullable = false)
    private String loadId;

    @Column(name = "loadLineId", nullable = false)
    private String loadLineId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "dispatchedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal dispatchedQty;

    @Column(name = "deliveredQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal deliveredQty;

    @Column(name = "differenceQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal differenceQty;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "raisedBy")
    private String raisedBy;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "decisionNotes")
    private String decisionNotes;

    @Column(name = "creditNoteId")
    private String creditNoteId;

    @Column(name = "creditNoteNumber")
    private String creditNoteNumber;

    @Column(name = "creditAmount", precision = 16, scale = 2)
    private BigDecimal creditAmount;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
