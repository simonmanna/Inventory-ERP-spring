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
 * Table {@code "PrecastCuringReading"}: Temperature reading or pallet move of a curing batch (ADR-S014, Phase 2.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "PrecastCuringReading")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PrecastCuringReading {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "batchId", nullable = false)
    private String batchId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "readAt", nullable = false)
    private Instant readAt;

    @Column(name = "temperatureC", precision = 8, scale = 2)
    private BigDecimal temperatureC;

    @Column(name = "humidityPct", precision = 6, scale = 2)
    private BigDecimal humidityPct;

    @Column(name = "curingAreaId")
    private String curingAreaId;

    @Column(name = "palletLocation")
    private String palletLocation;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
