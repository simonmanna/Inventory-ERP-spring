package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ShopFloorShift"} (ADR-S015): Production shift (local start / end minute, break) of the shop floor.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorShift")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorShift {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "startMinute", nullable = false)
    private Integer startMinute;

    @Column(name = "endMinute", nullable = false)
    private Integer endMinute;

    @Column(name = "breakMinutes", nullable = false)
    private Integer breakMinutes;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "sortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
