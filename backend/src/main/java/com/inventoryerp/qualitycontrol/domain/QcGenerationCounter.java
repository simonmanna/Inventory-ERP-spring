package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcGenerationCounter"}: Supplementary-check sequence per generation base key (ADR-S087 §3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcGenerationCounter")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcGenerationCounter {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "baseKey", nullable = false)
    private String baseKey;

    @Column(name = "nextSeq", nullable = false)
    private Integer nextSeq;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
