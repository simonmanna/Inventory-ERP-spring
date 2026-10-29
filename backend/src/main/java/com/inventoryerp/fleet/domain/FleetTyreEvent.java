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
 * Table {@code "FleetTyreEvent"}: Lifecycle event of a tyre: registered, fitted, rotated, removed, retreaded, scrapped (fleet 1.7).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTyreEvent")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTyreEvent {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "tyreId", nullable = false)
    private String tyreId;

    @Column(name = "eventType", nullable = false)
    private String eventType;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "fromPosition")
    private String fromPosition;

    @Column(name = "toPosition")
    private String toPosition;

    @Column(name = "eventAt", nullable = false)
    private Instant eventAt;

    @Column(name = "odometer", precision = 14, scale = 1)
    private BigDecimal odometer;

    @Column(name = "odometerLifetime", precision = 14, scale = 1)
    private BigDecimal odometerLifetime;

    @Column(name = "hours", precision = 14, scale = 1)
    private BigDecimal hours;

    @Column(name = "hoursLifetime", precision = 14, scale = 1)
    private BigDecimal hoursLifetime;

    @Column(name = "kmRun", precision = 14, scale = 1)
    private BigDecimal kmRun;

    @Column(name = "hoursRun", precision = 14, scale = 1)
    private BigDecimal hoursRun;

    @Column(name = "treadDepthMm", precision = 5, scale = 1)
    private BigDecimal treadDepthMm;

    @Column(name = "cost", precision = 18, scale = 2)
    private BigDecimal cost;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "meterReadingId")
    private String meterReadingId;

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
