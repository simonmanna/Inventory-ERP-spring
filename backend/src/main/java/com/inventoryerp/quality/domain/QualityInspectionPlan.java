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
 * Table {@code "QualityInspectionPlan"}: Inspection plan: stage, product / supplier / stage key, specification, sampling and hold policy (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityInspectionPlan")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QualityInspectionPlan {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "stage", nullable = false)
    private String stage;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "stageKey")
    private String stageKey;

    @Column(name = "specificationId", nullable = false)
    private String specificationId;

    @Column(name = "samplingMethod", nullable = false)
    private String samplingMethod;

    @Column(name = "sampleSize")
    private Integer sampleSize;

    @Column(name = "samplePct", precision = 9, scale = 4)
    private BigDecimal samplePct;

    @Column(name = "perQuantity", precision = 20, scale = 6)
    private BigDecimal perQuantity;

    @Column(name = "minSamples", nullable = false)
    private Integer minSamples;

    @Column(name = "maxSamples")
    private Integer maxSamples;

    @Column(name = "holdPolicy", nullable = false)
    private String holdPolicy;

    @Column(name = "independentRelease", nullable = false)
    private Boolean independentRelease;

    @Column(name = "autoNcr", nullable = false)
    private Boolean autoNcr;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "notes")
    private String notes;

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
