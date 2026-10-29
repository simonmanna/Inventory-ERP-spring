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
 * Table {@code "FleetTyreReading"}: Tread-depth (and pressure) reading of a tyre (fleet 1.7).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTyreReading")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTyreReading {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "tyreId", nullable = false)
    private String tyreId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "position")
    private String position;

    @Column(name = "readingAt", nullable = false)
    private Instant readingAt;

    @Column(name = "treadDepthMm", nullable = false, precision = 5, scale = 1)
    private BigDecimal treadDepthMm;

    @Column(name = "pressureKpa", precision = 6, scale = 1)
    private BigDecimal pressureKpa;

    @Column(name = "odometerLifetime", precision = 14, scale = 1)
    private BigDecimal odometerLifetime;

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
