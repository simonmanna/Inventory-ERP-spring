package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarrySettings"}: Quarry settings of an organization (one row): cost-allocation basis, fallback rates, explosive SoD (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarrySettings")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarrySettings {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "allocationBasis", nullable = false)
    private String allocationBasis;

    @Column(name = "fuelCostPerLitre", nullable = false, precision = 14, scale = 4)
    private BigDecimal fuelCostPerLitre;

    @Column(name = "labourRatePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal labourRatePerHour;

    @Column(name = "equipmentRatePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal equipmentRatePerHour;

    @Column(name = "distinctExplosiveIssuer", nullable = false)
    private Boolean distinctExplosiveIssuer;

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
