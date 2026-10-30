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
 * Table {@code "QuarryLoss"}: Extraction loss (overburden, unusable rock, waste, spillage) (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryLoss")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryLoss {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "siteId", nullable = false)
    private String siteId;

    @Column(name = "zoneId")
    private String zoneId;

    @Column(name = "logId")
    private String logId;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "lossOn", nullable = false)
    private LocalDate lossOn;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "tonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal tonnes;

    @Column(name = "reason")
    private String reason;

    @Column(name = "createdBy")
    private String createdBy;

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
