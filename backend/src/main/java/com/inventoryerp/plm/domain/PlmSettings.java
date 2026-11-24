package com.inventoryerp.plm.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "PlmSettings"}: the PLM application's settings of an organization (ADR-S086) — lifecycle policy
 * overrides, default cost policy, first-article default.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and schema
 * validation.
 */
@Entity
@Table(name = "PlmSettings")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmSettings {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lifecyclePolicy", nullable = false)
    private String lifecyclePolicy;

    @Column(name = "defaultCostPolicy", nullable = false)
    private String defaultCostPolicy;

    @Column(name = "firstArticleDefault", nullable = false)
    private Boolean firstArticleDefault;

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
