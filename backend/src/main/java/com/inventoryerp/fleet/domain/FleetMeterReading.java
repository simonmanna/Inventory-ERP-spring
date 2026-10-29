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
 * Table {@code "FleetMeterReading"}: Odometer (km) or hour-meter reading of a fleet vehicle (ADR-S009).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetMeterReading")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetMeterReading {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "meterType", nullable = false)
    private String meterType;

    @Column(name = "readingAt", nullable = false)
    private Instant readingAt;

    @Column(name = "value", nullable = false, precision = 14, scale = 1)
    private BigDecimal value;

    @Column(name = "lifetimeValue", nullable = false, precision = 14, scale = 1)
    private BigDecimal lifetimeValue;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "correctionId")
    private String correctionId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
