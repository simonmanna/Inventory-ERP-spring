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
 * Table {@code "QuarryExtractionPlan"}: Extraction plan and target per site / zone / material and period (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExtractionPlan")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExtractionPlan {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "siteId", nullable = false)
    private String siteId;

    @Column(name = "zoneId")
    private String zoneId;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "periodStart", nullable = false)
    private LocalDate periodStart;

    @Column(name = "periodEnd", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "targetTonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal targetTonnes;

    @Column(name = "budgetCostPerTonne", precision = 14, scale = 4)
    private BigDecimal budgetCostPerTonne;

    @Column(name = "status", nullable = false)
    private String status;

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
