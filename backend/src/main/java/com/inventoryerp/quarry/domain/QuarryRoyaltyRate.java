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
 * Table {@code "QuarryRoyaltyRate"}: Royalty / levy / fee per tonne, effective-dated (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryRoyaltyRate")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryRoyaltyRate {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "siteId")
    private String siteId;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "basis", nullable = false)
    private String basis;

    @Column(name = "ratePerTonne", nullable = false, precision = 14, scale = 4)
    private BigDecimal ratePerTonne;

    @Column(name = "authorityPartnerId")
    private String authorityPartnerId;

    @Column(name = "effectiveFrom", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effectiveTo")
    private LocalDate effectiveTo;

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

    public String getId() {
        return id;
    }
}
