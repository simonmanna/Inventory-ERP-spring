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
 * Table {@code "CrushingPlantUnit"}: Crusher / screen / conveyor of a circuit, a work centre with capacity and meters (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingPlantUnit")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingPlantUnit {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "plantId", nullable = false)
    private String plantId;

    @Column(name = "stageId")
    private String stageId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "capacityTph", precision = 18, scale = 6)
    private BigDecimal capacityTph;

    @Column(name = "powerKw", precision = 18, scale = 6)
    private BigDecimal powerKw;

    @Column(name = "meterKind", nullable = false)
    private String meterKind;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "sortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
