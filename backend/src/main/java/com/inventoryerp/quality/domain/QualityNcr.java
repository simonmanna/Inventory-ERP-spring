package com.inventoryerp.quality.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QualityNcr"}: Nonconformance report (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityNcr")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QualityNcr {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "ncrNumber", nullable = false)
    private String ncrNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "lotId")
    private String lotId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "customerPartnerId")
    private String customerPartnerId;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "quantityAffected", precision = 20, scale = 6)
    private BigDecimal quantityAffected;

    @Column(name = "disposition")
    private String disposition;

    @Column(name = "rootCause")
    private String rootCause;

    @Column(name = "ownerUserId")
    private String ownerUserId;

    @Column(name = "reportedBy")
    private String reportedBy;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "closedBy")
    private String closedBy;

    @Column(name = "closureNotes")
    private String closureNotes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

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
