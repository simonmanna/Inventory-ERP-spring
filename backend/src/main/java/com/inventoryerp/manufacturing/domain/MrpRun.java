package com.inventoryerp.manufacturing.domain;

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
 * Table {@code "MrpRun"}: One MRP planning run (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "MrpRun")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class MrpRun {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "runNumber", nullable = false)
    private String runNumber;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "horizonDays", nullable = false)
    private Integer horizonDays;

    @Column(name = "locationId")
    private String locationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "startedAt", nullable = false)
    private Instant startedAt;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
