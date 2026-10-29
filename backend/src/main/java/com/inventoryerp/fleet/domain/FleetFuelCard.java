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
 * Table {@code "FleetFuelCard"}: Fuel card (masked number only), assigned vehicle / driver, provider, status and
 * spend limit (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFuelCard")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFuelCard {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "maskedNumber", nullable = false)
    private String maskedNumber;

    @Column(name = "last4", nullable = false)
    private String last4;

    @Column(name = "numberHash", nullable = false)
    private String numberHash;

    @Column(name = "label")
    private String label;

    @Column(name = "providerPartnerId")
    private String providerPartnerId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "employeeId")
    private String employeeId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "expiresOn")
    private LocalDate expiresOn;

    @Column(name = "limitAmount", precision = 18, scale = 2)
    private BigDecimal limitAmount;

    @Column(name = "limitPeriod", nullable = false)
    private String limitPeriod;

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
