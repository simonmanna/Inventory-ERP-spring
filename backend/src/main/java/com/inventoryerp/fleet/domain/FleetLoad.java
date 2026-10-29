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
 * Table {@code "FleetLoad"}: Load = one trip of a truck or transporter for a delivery order, with its lifecycle (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetLoad")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetLoad {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "deliveryOrderId", nullable = false)
    private String deliveryOrderId;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "plannedDate", nullable = false)
    private LocalDate plannedDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "carrier", nullable = false)
    private String carrier;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "trailerId")
    private String trailerId;

    @Column(name = "driverId")
    private String driverId;

    @Column(name = "transporterPartnerId")
    private String transporterPartnerId;

    @Column(name = "transporterRateId")
    private String transporterRateId;

    @Column(name = "transporterPlate")
    private String transporterPlate;

    @Column(name = "transporterDriverName")
    private String transporterDriverName;

    @Column(name = "freightBasis")
    private String freightBasis;

    @Column(name = "freightRate", precision = 14, scale = 2)
    private BigDecimal freightRate;

    @Column(name = "freightAmount", precision = 16, scale = 2)
    private BigDecimal freightAmount;

    @Column(name = "capacityT", precision = 12, scale = 3)
    private BigDecimal capacityT;

    @Column(name = "capacityM3", precision = 12, scale = 3)
    private BigDecimal capacityM3;

    @Column(name = "plannedTonnes", precision = 14, scale = 3)
    private BigDecimal plannedTonnes;

    @Column(name = "plannedM3", precision = 14, scale = 3)
    private BigDecimal plannedM3;

    @Column(name = "loadedTonnes", precision = 14, scale = 3)
    private BigDecimal loadedTonnes;

    @Column(name = "deliveredTonnes", precision = 14, scale = 3)
    private BigDecimal deliveredTonnes;

    @Column(name = "locationId", nullable = false)
    private String locationId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "startOdometer", precision = 14, scale = 1)
    private BigDecimal startOdometer;

    @Column(name = "endOdometer", precision = 14, scale = 1)
    private BigDecimal endOdometer;

    @Column(name = "startReadingId")
    private String startReadingId;

    @Column(name = "endReadingId")
    private String endReadingId;

    @Column(name = "distanceKm", precision = 10, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "distanceSource")
    private String distanceSource;

    @Column(name = "weighSourceType")
    private String weighSourceType;

    @Column(name = "weighSourceId")
    private String weighSourceId;

    @Column(name = "weighedNetQty", precision = 14, scale = 3)
    private BigDecimal weighedNetQty;

    @Column(name = "weighedUom")
    private String weighedUom;

    @Column(name = "stockIssuedAt")
    private Instant stockIssuedAt;

    @Column(name = "stockReturnedAt")
    private Instant stockReturnedAt;

    @Column(name = "deliveryNoteNumber")
    private String deliveryNoteNumber;

    @Column(name = "deliveryNotePrints", nullable = false)
    private Integer deliveryNotePrints;

    @Column(name = "loadingStartedAt")
    private Instant loadingStartedAt;

    @Column(name = "weighedAt")
    private Instant weighedAt;

    @Column(name = "dispatchedAt")
    private Instant dispatchedAt;

    @Column(name = "departedAt")
    private Instant departedAt;

    @Column(name = "deliveredAt")
    private Instant deliveredAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "returnedAt")
    private Instant returnedAt;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "returnReason")
    private String returnReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "dispatchedBy")
    private String dispatchedBy;

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
