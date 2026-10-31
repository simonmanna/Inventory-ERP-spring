package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "CrushingPlant"}: Crushing plant / crusher line (Track C 2.3, ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingPlant")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingPlant {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "siteId")
    private String siteId;

    @Column(name = "feedStockpileId")
    private String feedStockpileId;

    @Column(name = "designCapacityTph", precision = 18, scale = 6)
    private BigDecimal designCapacityTph;

    @Column(name = "dieselCostPerLitre", nullable = false, precision = 18, scale = 6)
    private BigDecimal dieselCostPerLitre;

    @Column(name = "electricityCostPerKwh", nullable = false, precision = 18, scale = 6)
    private BigDecimal electricityCostPerKwh;

    @Column(name = "labourRatePerHour", nullable = false, precision = 18, scale = 6)
    private BigDecimal labourRatePerHour;

    @Column(name = "routingId")
    private String routingId;

    @Column(name = "circuitVersion", nullable = false)
    private Integer circuitVersion;

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

    @Column(name = "deletedAt")
    private Instant deletedAt;
}
