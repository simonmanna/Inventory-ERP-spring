package com.inventoryerp.fleet.domain;

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
 * Table {@code "FleetServiceTemplate"}: Service template of a vehicle class: km / engine-hour / calendar intervals
 * (whichever first), lead window and task checklist (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetServiceTemplate")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetServiceTemplate {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "categoryId")
    private String categoryId;

    @Column(name = "intervalKm", precision = 14, scale = 1)
    private BigDecimal intervalKm;

    @Column(name = "intervalHours", precision = 14, scale = 1)
    private BigDecimal intervalHours;

    @Column(name = "intervalDays")
    private Integer intervalDays;

    @Column(name = "leadKm", nullable = false, precision = 14, scale = 1)
    private BigDecimal leadKm;

    @Column(name = "leadHours", nullable = false, precision = 14, scale = 1)
    private BigDecimal leadHours;

    @Column(name = "leadDays", nullable = false)
    private Integer leadDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tasks", nullable = false)
    private String tasks;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
