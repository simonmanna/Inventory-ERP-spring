package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcPointSchedule"}: Last periodic generation per control point and scope (ADR-S087 §4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcPointSchedule")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcPointSchedule {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "controlPointId", nullable = false)
    private String controlPointId;

    @Column(name = "scopeKey", nullable = false)
    private String scopeKey;

    @Column(name = "lastGeneratedAt", nullable = false)
    private Instant lastGeneratedAt;

    public String getId() {
        return id;
    }
}
