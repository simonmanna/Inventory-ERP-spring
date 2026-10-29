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
 * Table {@code "FleetVehicleStatusHistory"}: One status change of a fleet vehicle (ADR-S009).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetVehicleStatusHistory")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetVehicleStatusHistory {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "fromStatus")
    private String fromStatus;

    @Column(name = "toStatus", nullable = false)
    private String toStatus;

    @Column(name = "reason")
    private String reason;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "odometer", precision = 14, scale = 1)
    private BigDecimal odometer;

    @Column(name = "hours", precision = 14, scale = 1)
    private BigDecimal hours;

    @Column(name = "changedBy")
    private String changedBy;

    @Column(name = "changedAt", nullable = false)
    private Instant changedAt;

    public String getId() {
        return id;
    }
}
