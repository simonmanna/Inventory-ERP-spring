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
 * Table {@code "FleetFine"}: Traffic fine of a vehicle / driver, charged to the company (expense) or the driver (deduction) (fleet 1.7).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "fineNumber", nullable = false)
    private String fineNumber;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "driverEmployeeId")
    private String driverEmployeeId;

    @Column(name = "incidentId")
    private String incidentId;

    @Column(name = "offenceAt", nullable = false)
    private Instant offenceAt;

    @Column(name = "location")
    private String location;

    @Column(name = "offence", nullable = false)
    private String offence;

    @Column(name = "offenceCode")
    private String offenceCode;

    @Column(name = "authority")
    private String authority;

    @Column(name = "noticeNumber")
    private String noticeNumber;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "dueDate")
    private LocalDate dueDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "statusReason")
    private String statusReason;

    @Column(name = "chargedTo")
    private String chargedTo;

    @Column(name = "chargedAt")
    private Instant chargedAt;

    @Column(name = "chargedBy")
    private String chargedBy;

    @Column(name = "chargeCount", nullable = false)
    private Integer chargeCount;

    @Column(name = "expenseId")
    private String expenseId;

    @Column(name = "deductionId")
    private String deductionId;

    @Column(name = "settledAt")
    private Instant settledAt;

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

    @Column(name = "deletedAt")
    private Instant deletedAt;

    public String getId() {
        return id;
    }
}
