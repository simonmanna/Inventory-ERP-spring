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
 * Table {@code "FleetWorkOrder"}: Fleet maintenance work order (preventive, corrective, breakdown, inspection
 * defect) with its lifecycle and cost totals (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetWorkOrder")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetWorkOrder {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "scheduleId")
    private String scheduleId;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "dueOn")
    private LocalDate dueOn;

    @Column(name = "reportedAt", nullable = false)
    private Instant reportedAt;

    @Column(name = "openedAt")
    private Instant openedAt;

    @Column(name = "startedAt")
    private Instant startedAt;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "completionOdometer", precision = 14, scale = 1)
    private BigDecimal completionOdometer;

    @Column(name = "completionHours", precision = 14, scale = 1)
    private BigDecimal completionHours;

    @Column(name = "assignedEmployeeId")
    private String assignedEmployeeId;

    @Column(name = "previousVehicleStatus")
    private String previousVehicleStatus;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "partsCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal partsCost;

    @Column(name = "labourCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal labourCost;

    @Column(name = "externalCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal externalCost;

    @Column(name = "totalCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal totalCost;

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
