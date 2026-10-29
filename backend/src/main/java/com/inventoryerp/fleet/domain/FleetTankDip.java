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
 * Table {@code "FleetTankDip"}: Dip reading of a site fuel tank with its book level, variance and reconciliation
 * state (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTankDip")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTankDip {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "tankId", nullable = false)
    private String tankId;

    @Column(name = "dippedAt", nullable = false)
    private Instant dippedAt;

    @Column(name = "dipLitres", nullable = false, precision = 14, scale = 3)
    private BigDecimal dipLitres;

    @Column(name = "bookLitres", nullable = false, precision = 14, scale = 3)
    private BigDecimal bookLitres;

    @Column(name = "varianceLitres", nullable = false, precision = 14, scale = 3)
    private BigDecimal varianceLitres;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "reason")
    private String reason;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "adjustmentLedgerCode")
    private String adjustmentLedgerCode;

    @Column(name = "adjustedLitres", precision = 14, scale = 3)
    private BigDecimal adjustedLitres;

    @Column(name = "adjustmentValue", precision = 20, scale = 6)
    private BigDecimal adjustmentValue;

    @Column(name = "failureReason")
    private String failureReason;

    @Column(name = "reconciledBy")
    private String reconciledBy;

    @Column(name = "reconciledAt")
    private Instant reconciledAt;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
