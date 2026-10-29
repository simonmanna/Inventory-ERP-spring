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
 * Table {@code "FleetFuelBand"}: Expected fuel consumption band of a vehicle class: L/100 km and / or L/hour
 * (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFuelBand")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFuelBand {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "categoryId", nullable = false)
    private String categoryId;

    @Column(name = "minLPer100Km", precision = 10, scale = 2)
    private BigDecimal minLPer100Km;

    @Column(name = "maxLPer100Km", precision = 10, scale = 2)
    private BigDecimal maxLPer100Km;

    @Column(name = "minLPerHour", precision = 10, scale = 2)
    private BigDecimal minLPerHour;

    @Column(name = "maxLPerHour", precision = 10, scale = 2)
    private BigDecimal maxLPerHour;

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
