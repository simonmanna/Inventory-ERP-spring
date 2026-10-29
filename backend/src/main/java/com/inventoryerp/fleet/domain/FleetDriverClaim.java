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
 * Table {@code "FleetDriverClaim"}: Driver allowance / trip-expense claim: approval, then paid via expenses or
 * exported to payroll (ADR-S009, fleet 1.2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDriverClaim")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDriverClaim {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "driverId", nullable = false)
    private String driverId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "tripId")
    private String tripId;

    @Column(name = "claimDate", nullable = false)
    private LocalDate claimDate;

    @Column(name = "routeZone")
    private String routeZone;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "totalAmount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currencyCode", nullable = false)
    private String currencyCode;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "submittedBy")
    private String submittedBy;

    @Column(name = "submittedAt")
    private Instant submittedAt;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "decisionNotes")
    private String decisionNotes;

    @Column(name = "settlement")
    private String settlement;

    @Column(name = "expenseId")
    private String expenseId;

    @Column(name = "payrollInputId")
    private String payrollInputId;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "settledBy")
    private String settledBy;

    @Column(name = "settledAt")
    private Instant settledAt;

    @Column(name = "settlementCount", nullable = false)
    private Integer settlementCount;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversalReason")
    private String reversalReason;

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
