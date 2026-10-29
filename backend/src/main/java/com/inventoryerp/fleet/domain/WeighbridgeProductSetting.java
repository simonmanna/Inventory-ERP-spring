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
 * Table {@code "WeighbridgeProductSetting"}: Weighing settings of a product: kg per base unit and tolerance overrides (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WeighbridgeProductSetting")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WeighbridgeProductSetting {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "kgPerUnit", precision = 18, scale = 6)
    private BigDecimal kgPerUnit;

    @Column(name = "tolerancePct", precision = 7, scale = 3)
    private BigDecimal tolerancePct;

    @Column(name = "toleranceKg", precision = 14, scale = 2)
    private BigDecimal toleranceKg;

    @Column(name = "notes")
    private String notes;

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
