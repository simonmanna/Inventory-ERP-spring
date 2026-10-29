package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetVehicle"}: Vehicle or piece of mobile / fixed equipment in the fleet register (ADR-S009).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetVehicle")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class FleetVehicle {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "registrationPlate")
    private String registrationPlate;

    @Column(name = "vin")
    private String vin;

    @Column(name = "engineNumber")
    private String engineNumber;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

    @Column(name = "year")
    private Integer year;

    @Column(name = "color")
    private String color;

    @Column(name = "categoryId", nullable = false)
    private String categoryId;

    @Column(name = "ownership", nullable = false)
    private String ownership;

    @Column(name = "ownerPartnerId")
    private String ownerPartnerId;

    @Column(name = "payloadCapacityT", precision = 12, scale = 3)
    private BigDecimal payloadCapacityT;

    @Column(name = "bodyVolumeM3", precision = 12, scale = 3)
    private BigDecimal bodyVolumeM3;

    @Column(name = "axleConfig")
    private String axleConfig;

    @Column(name = "fuelType", nullable = false)
    private String fuelType;

    @Column(name = "tankCapacityL", precision = 12, scale = 2)
    private BigDecimal tankCapacityL;

    @Column(name = "meterType", nullable = false)
    private String meterType;

    @Column(name = "currentOdometer", precision = 14, scale = 1)
    private BigDecimal currentOdometer;

    @Column(name = "odometerOffset", nullable = false, precision = 14, scale = 1)
    private BigDecimal odometerOffset;

    @Column(name = "odometerAt")
    private Instant odometerAt;

    @Column(name = "currentHours", precision = 14, scale = 1)
    private BigDecimal currentHours;

    @Column(name = "hoursOffset", nullable = false, precision = 14, scale = 1)
    private BigDecimal hoursOffset;

    @Column(name = "hoursAt")
    private Instant hoursAt;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "homeLocationId")
    private String homeLocationId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "statusReason")
    private String statusReason;

    @Column(name = "statusChangedAt")
    private Instant statusChangedAt;

    @Column(name = "assetId")
    private String assetId;

    @Column(name = "acquiredOn")
    private LocalDate acquiredOn;

    @Column(name = "disposedAt")
    private Instant disposedAt;

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

    @Column(name = "deletedAt")
    private Instant deletedAt;

    public String getId() {
        return id;
    }
}
