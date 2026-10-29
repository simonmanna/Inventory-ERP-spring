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
 * Table {@code "FleetDeliveryChargeRule"}: Transport / delivery charge rule per customer, zone and distance band (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDeliveryChargeRule")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDeliveryChargeRule {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "zone")
    private String zone;

    @Column(name = "minDistanceKm", precision = 10, scale = 1)
    private BigDecimal minDistanceKm;

    @Column(name = "maxDistanceKm", precision = 10, scale = 1)
    private BigDecimal maxDistanceKm;

    @Column(name = "basis", nullable = false)
    private String basis;

    @Column(name = "rate", nullable = false, precision = 14, scale = 2)
    private BigDecimal rate;

    @Column(name = "minAmount", precision = 14, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "taxId")
    private String taxId;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "notes")
    private String notes;

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
