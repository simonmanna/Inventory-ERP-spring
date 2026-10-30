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
 * Table {@code "PrecastProductSpec"}: Block / precast product specification (ADR-S014, Phase 2.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "PrecastProductSpec")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PrecastProductSpec {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "blockType", nullable = false)
    private String blockType;

    @Column(name = "lengthMm", precision = 12, scale = 2)
    private BigDecimal lengthMm;

    @Column(name = "widthMm", precision = 12, scale = 2)
    private BigDecimal widthMm;

    @Column(name = "heightMm", precision = 12, scale = 2)
    private BigDecimal heightMm;

    @Column(name = "strengthClass")
    private String strengthClass;

    @Column(name = "strengthMpa", precision = 12, scale = 3)
    private BigDecimal strengthMpa;

    @Column(name = "weightKg", precision = 12, scale = 3)
    private BigDecimal weightKg;

    @Column(name = "unitsPerPallet", nullable = false)
    private Integer unitsPerPallet;

    @Column(name = "unitsPerCycle", nullable = false)
    private Integer unitsPerCycle;

    @Column(name = "secondGradeProductId")
    private String secondGradeProductId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
