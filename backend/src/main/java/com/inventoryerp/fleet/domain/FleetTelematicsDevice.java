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
 * Table {@code "FleetTelematicsDevice"}: Telematics unit of a provider mapped to a vehicle, with sync cursors and last fix (fleet 1.8).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTelematicsDevice")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTelematicsDevice {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "externalUnitId", nullable = false)
    private String externalUnitId;

    @Column(name = "name")
    private String name;

    @Column(name = "imei")
    private String imei;

    @Column(name = "simNumber")
    private String simNumber;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "syncOdometer", nullable = false)
    private Boolean syncOdometer;

    @Column(name = "syncHours", nullable = false)
    private Boolean syncHours;

    @Column(name = "positionsSyncedTo")
    private Instant positionsSyncedTo;

    @Column(name = "eventsSyncedTo")
    private Instant eventsSyncedTo;

    @Column(name = "lastSyncAt")
    private Instant lastSyncAt;

    @Column(name = "lastSyncStatus")
    private String lastSyncStatus;

    @Column(name = "lastError")
    private String lastError;

    @Column(name = "lastPositionAt")
    private Instant lastPositionAt;

    @Column(name = "lastLatitude", precision = 9, scale = 6)
    private BigDecimal lastLatitude;

    @Column(name = "lastLongitude", precision = 9, scale = 6)
    private BigDecimal lastLongitude;

    @Column(name = "lastSpeedKmh", precision = 6, scale = 1)
    private BigDecimal lastSpeedKmh;

    @Column(name = "lastIgnition")
    private Boolean lastIgnition;

    @Column(name = "lastOdometerKm", precision = 14, scale = 1)
    private BigDecimal lastOdometerKm;

    @Column(name = "lastEngineHours", precision = 14, scale = 1)
    private BigDecimal lastEngineHours;

    @Column(name = "lastMeterAt")
    private Instant lastMeterAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

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
