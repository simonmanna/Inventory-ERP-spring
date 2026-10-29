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
 * Table {@code "WeighbridgeStation"}: Weighbridge station of a site / branch: tolerances, tare validity, plausible weight range (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WeighbridgeStation")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WeighbridgeStation {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "branchId")
    private String branchId;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "tolerancePct", nullable = false, precision = 7, scale = 3)
    private BigDecimal tolerancePct;

    @Column(name = "toleranceKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal toleranceKg;

    @Column(name = "tareValidityDays", nullable = false)
    private Integer tareValidityDays;

    @Column(name = "minWeightKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal minWeightKg;

    @Column(name = "maxWeightKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

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
