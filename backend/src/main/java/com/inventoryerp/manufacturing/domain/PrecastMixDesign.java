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
 * Table {@code "PrecastMixDesign"}: Mix design (recipe) parameters of a BOM version (ADR-S014, Phase 2.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "PrecastMixDesign")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PrecastMixDesign {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "bomId", nullable = false)
    private String bomId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "cementProductId")
    private String cementProductId;

    @Column(name = "cementPerBag", nullable = false, precision = 20, scale = 6)
    private BigDecimal cementPerBag;

    @Column(name = "batchVolumeM3", precision = 20, scale = 6)
    private BigDecimal batchVolumeM3;

    @Column(name = "waterCementRatio", precision = 8, scale = 4)
    private BigDecimal waterCementRatio;

    @Column(name = "minCuringDays", nullable = false)
    private Integer minCuringDays;

    @Column(name = "curingCheckpointCode")
    private String curingCheckpointCode;

    @Column(name = "defaultCuringAreaId")
    private String defaultCuringAreaId;

    @Column(name = "targetMaturity", precision = 20, scale = 2)
    private BigDecimal targetMaturity;

    @Column(name = "datumTemperatureC", nullable = false, precision = 8, scale = 2)
    private BigDecimal datumTemperatureC;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
