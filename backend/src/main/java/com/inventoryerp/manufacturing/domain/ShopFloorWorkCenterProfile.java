package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "ShopFloorWorkCenterProfile"} (ADR-S015):
 * Shop-floor profile of a work centre: ideal rate for OEE, machine code, meters.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorWorkCenterProfile")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorWorkCenterProfile {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "idealRatePerHour", precision = 20, scale = 6)
    private BigDecimal idealRatePerHour;

    @Column(name = "rateUom")
    private String rateUom;

    @Column(name = "machineCode")
    private String machineCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meterCodes", nullable = false)
    private String meterCodes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
