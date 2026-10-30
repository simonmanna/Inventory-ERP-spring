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
 * Table {@code "QuarryEquipmentRate"}: Hourly rate of mobile plant per vehicle or fleet vehicle class (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryEquipmentRate")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryEquipmentRate {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "categoryId")
    private String categoryId;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "ratePerHour", nullable = false, precision = 14, scale = 4)
    private BigDecimal ratePerHour;

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
