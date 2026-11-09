package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmDocument"}: Versioned engineering document (drawing, specification, CAD, ...) (ADR-S063).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmDocument")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION,
        deduceAliasInjectionPoints = false)
public class PlmDocument {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "revision", nullable = false)
    private String revision;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "docType", nullable = false)
    private String docType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "productId")
    private String productId;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "changeId")
    private String changeId;

    @Column(name = "fileId")
    private String fileId;

    @Column(name = "url")
    private String url;

    @Column(name = "fileName")
    private String fileName;

    @Column(name = "description")
    private String description;

    @Column(name = "releasedAt")
    private Instant releasedAt;

    @Column(name = "releasedById")
    private String releasedById;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "deletedAt")
    private Instant deletedAt;

    public String getId() {
        return id;
    }
}
