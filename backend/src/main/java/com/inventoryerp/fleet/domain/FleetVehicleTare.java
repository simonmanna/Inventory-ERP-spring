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
 * Table {@code "FleetVehicleTare"}: Stored tare of a vehicle / plate with expiry (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetVehicleTare")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetVehicleTare {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "registrationPlate", nullable = false)
    private String registrationPlate;

    @Column(name = "tareKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal tareKg;

    @Column(name = "weighedAt", nullable = false)
    private Instant weighedAt;

    @Column(name = "expiresAt", nullable = false)
    private Instant expiresAt;

    @Column(name = "stationId")
    private String stationId;

    @Column(name = "deviceId")
    private String deviceId;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "rawFrame")
    private String rawFrame;

    @Column(name = "manualReason")
    private String manualReason;

    @Column(name = "ticketId")
    private String ticketId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "revokedReason")
    private String revokedReason;

    @Column(name = "revokedBy")
    private String revokedBy;

    @Column(name = "revokedAt")
    private Instant revokedAt;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
