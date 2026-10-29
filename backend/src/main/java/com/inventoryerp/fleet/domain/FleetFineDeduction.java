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
 * Table {@code "FleetFineDeduction"}: Salary-deduction request of a fine charged to the driver (fleet 1.7; exported / settled by payroll).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFineDeduction")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFineDeduction {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "fineId", nullable = false)
    private String fineId;

    @Column(name = "employeeId", nullable = false)
    private String employeeId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "requestedAt", nullable = false)
    private Instant requestedAt;

    @Column(name = "requestedBy")
    private String requestedBy;

    @Column(name = "exportedAt")
    private Instant exportedAt;

    @Column(name = "exportReference")
    private String exportReference;

    @Column(name = "settledAt")
    private Instant settledAt;

    @Column(name = "settledReference")
    private String settledReference;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
