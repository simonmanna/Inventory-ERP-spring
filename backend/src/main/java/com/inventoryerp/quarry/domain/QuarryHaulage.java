package com.inventoryerp.quarry.domain;

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
 * Table {@code "QuarryHaulage"}: Internal haulage record (face to ROM stockpile / crusher) (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryHaulage")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryHaulage {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "siteId", nullable = false)
    private String siteId;

    @Column(name = "fromZoneId")
    private String fromZoneId;

    @Column(name = "destination", nullable = false)
    private String destination;

    @Column(name = "stockpileId")
    private String stockpileId;

    @Column(name = "destinationNote")
    private String destinationNote;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "hauledOn", nullable = false)
    private LocalDate hauledOn;

    @Column(name = "shift", nullable = false)
    private String shift;

    @Column(name = "truckVehicleId")
    private String truckVehicleId;

    @Column(name = "driverEmployeeId")
    private String driverEmployeeId;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "loadCount")
    private Integer loadCount;

    @Column(name = "capacityT", precision = 12, scale = 3)
    private BigDecimal capacityT;

    @Column(name = "weighbridgeTicketId")
    private String weighbridgeTicketId;

    @Column(name = "fleetTripId")
    private String fleetTripId;

    @Column(name = "tonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal tonnes;

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
