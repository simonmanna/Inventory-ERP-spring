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
 * Table {@code "CrushingRunMeter"}: Meter readings of one equipment unit in a crushing run (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingRunMeter")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingRunMeter {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "runId", nullable = false)
    private String runId;

    @Column(name = "unitId", nullable = false)
    private String unitId;

    @Column(name = "hoursStart", precision = 18, scale = 6)
    private BigDecimal hoursStart;

    @Column(name = "hoursEnd", precision = 18, scale = 6)
    private BigDecimal hoursEnd;

    @Column(name = "runHours", nullable = false, precision = 18, scale = 6)
    private BigDecimal runHours;

    @Column(name = "kwhStart", precision = 18, scale = 6)
    private BigDecimal kwhStart;

    @Column(name = "kwhEnd", precision = 18, scale = 6)
    private BigDecimal kwhEnd;

    @Column(name = "kwh", nullable = false, precision = 18, scale = 6)
    private BigDecimal kwh;

    @Column(name = "dieselLitres", nullable = false, precision = 18, scale = 6)
    private BigDecimal dieselLitres;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
