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
 * Table {@code "FleetFuelSettings"}: Fuel settings of an organization: overfill tolerance, dip-variance approval
 * thresholds, expense category (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFuelSettings")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFuelSettings {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "overfillTolerancePct", nullable = false, precision = 6, scale = 2)
    private BigDecimal overfillTolerancePct;

    @Column(name = "dipApprovalLitres", nullable = false, precision = 14, scale = 3)
    private BigDecimal dipApprovalLitres;

    @Column(name = "dipApprovalPct", nullable = false, precision = 6, scale = 2)
    private BigDecimal dipApprovalPct;

    @Column(name = "expenseCategoryId")
    private String expenseCategoryId;

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
