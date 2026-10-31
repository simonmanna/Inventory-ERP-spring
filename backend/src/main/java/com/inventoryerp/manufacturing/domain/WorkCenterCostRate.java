package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "WorkCenterCostRate"}: Absorption rates of a work centre (ADR-S018).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "WorkCenterCostRate")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WorkCenterCostRate {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "labourRatePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal labourRatePerHour;

    @Column(name = "machineRatePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal machineRatePerHour;

    @Column(name = "overheadRatePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal overheadRatePerHour;

    @Column(name = "overheadBasis", nullable = false)
    private String overheadBasis;

    @Column(name = "energyRatePerUnit", nullable = false, precision = 14, scale = 4)
    private BigDecimal energyRatePerUnit;

    @Column(name = "energyMeterCode")
    private String energyMeterCode;

    @Column(name = "fleetVehicleId")
    private String fleetVehicleId;

    @Column(name = "fleetCostDays", nullable = false)
    private Integer fleetCostDays;

    @Column(name = "creditAccountId")
    private String creditAccountId;

    @Column(name = "notes")
    private String notes;

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
