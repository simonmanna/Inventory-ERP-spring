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
 * Table {@code "FleetFuelAnomaly"}: Anomaly flagged on a fuel log (overfill, meter, consumption band, card) with
 * its review state (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFuelAnomaly")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFuelAnomaly {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "fuelLogId", nullable = false)
    private String fuelLogId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "detail", nullable = false)
    private String detail;

    @Column(name = "observed", precision = 14, scale = 3)
    private BigDecimal observed;

    @Column(name = "expectedMin", precision = 14, scale = 3)
    private BigDecimal expectedMin;

    @Column(name = "expectedMax", precision = 14, scale = 3)
    private BigDecimal expectedMax;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "note")
    private String note;

    @Column(name = "acknowledgedBy")
    private String acknowledgedBy;

    @Column(name = "acknowledgedAt")
    private Instant acknowledgedAt;

    @Column(name = "resolvedAt")
    private Instant resolvedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
