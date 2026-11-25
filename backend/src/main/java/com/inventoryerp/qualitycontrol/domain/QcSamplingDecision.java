package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcSamplingDecision"}: Persisted random-frequency roll per generation base key (ADR-S087 §4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcSamplingDecision")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcSamplingDecision {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "controlPointId", nullable = false)
    private String controlPointId;

    @Column(name = "baseKey", nullable = false)
    private String baseKey;

    @Column(name = "selected", nullable = false)
    private Boolean selected;

    @Column(name = "roll", nullable = false, precision = 9, scale = 6)
    private BigDecimal roll;

    @Column(name = "pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal pct;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
