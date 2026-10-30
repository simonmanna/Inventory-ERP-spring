package com.inventoryerp.quality.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "QualityInspectionLot"}: Inspection lot (incoming / in-process / final) with result and disposition quantities (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityInspectionLot")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualityInspectionLot {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "lotNumber", nullable = false)
    private String lotNumber;

    @Column(name = "stage", nullable = false)
    private String stage;

    @Column(name = "planId")
    private String planId;

    @Column(name = "specificationId")
    private String specificationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "quarantineLocationId")
    private String quarantineLocationId;

    @Column(name = "stockControl", nullable = false)
    private String stockControl;

    @Column(name = "sourceType", nullable = false)
    private String sourceType;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "sourceLineId")
    private String sourceLineId;

    @Column(name = "sourceRef")
    private String sourceRef;

    @Column(name = "stageKey")
    private String stageKey;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "sampleSize", nullable = false)
    private Integer sampleSize;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "result", nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation", nullable = false)
    private String evaluation;

    @Column(name = "releasedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal releasedQty;

    @Column(name = "rejectedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal rejectedQty;

    @Column(name = "downgradedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal downgradedQty;

    @Column(name = "independentRelease", nullable = false)
    private Boolean independentRelease;

    @Column(name = "autoNcr", nullable = false)
    private Boolean autoNcr;

    @Column(name = "inspectedBy")
    private String inspectedBy;

    @Column(name = "inspectedAt")
    private Instant inspectedAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "createdBy")
    private String createdBy;

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
