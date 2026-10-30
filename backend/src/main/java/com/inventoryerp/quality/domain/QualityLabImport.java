package com.inventoryerp.quality.domain;

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
 * Table {@code "QualityLabImport"}: Laboratory result import run through a LabResultAdapter (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityLabImport")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualityLabImport {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "adapter", nullable = false)
    private String adapter;

    @Column(name = "filename")
    private String filename;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "rowsTotal", nullable = false)
    private Integer rowsTotal;

    @Column(name = "rowsImported", nullable = false)
    private Integer rowsImported;

    @Column(name = "rowsRejected", nullable = false)
    private Integer rowsRejected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", nullable = false)
    private String errors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lotIds", nullable = false)
    private String lotIds;

    @Column(name = "importedBy")
    private String importedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
