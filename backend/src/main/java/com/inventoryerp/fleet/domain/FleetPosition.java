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
 * Table {@code "FleetPosition"}: GPS position of a vehicle reported by its telematics unit (fleet 1.8; high volume, retention purge).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetPosition")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetPosition {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "deviceId", nullable = false)
    private String deviceId;

    @Column(name = "recordedAt", nullable = false)
    private Instant recordedAt;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "speedKmh", precision = 6, scale = 1)
    private BigDecimal speedKmh;

    @Column(name = "ignition")
    private Boolean ignition;

    @Column(name = "externalId")
    private String externalId;

    @Column(name = "receivedAt", nullable = false)
    private Instant receivedAt;

    public String getId() {
        return id;
    }
}
