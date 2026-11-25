package com.inventoryerp.qualitycontrol.domain;

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
 * Table {@code "QcCheck"}: Quality check (ADR-S087 §2): the requirement; attempts are {@code QcCheckExecution} revisions.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcCheck")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcCheck {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "checkNumber", nullable = false)
    private String checkNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "controlPointId")
    private String controlPointId;

    @Column(name = "controlPointVersionId")
    private String controlPointVersionId;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "phase", nullable = false)
    private String phase;

    @Column(name = "baseKey")
    private String baseKey;

    @Column(name = "generationSeq")
    private Integer generationSeq;

    @Column(name = "generationKey")
    private String generationKey;

    @Column(name = "operation")
    private String operation;

    @Column(name = "checkType", nullable = false)
    private String checkType;

    @Column(name = "sourceType", nullable = false)
    private String sourceType;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "sourceLineId")
    private String sourceLineId;

    @Column(name = "sourceRef")
    private String sourceRef;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "teamId")
    private String teamId;

    @Column(name = "responsibleUserId")
    private String responsibleUserId;

    @Column(name = "coveredQty", precision = 20, scale = 6)
    private BigDecimal coveredQty;

    @Column(name = "testQty", precision = 20, scale = 6)
    private BigDecimal testQty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false)
    private String snapshot;

    @Column(name = "worksheetTemplateId")
    private String worksheetTemplateId;

    @Column(name = "blocking", nullable = false)
    private Boolean blocking;

    @Column(name = "blockOnFailure", nullable = false)
    private Boolean blockOnFailure;

    @Column(name = "failureClassRequired", nullable = false)
    private Boolean failureClassRequired;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "currentRevision", nullable = false)
    private Integer currentRevision;

    @Column(name = "failureClassId")
    private String failureClassId;

    @Column(name = "failedQty", precision = 20, scale = 6)
    private BigDecimal failedQty;

    @Column(name = "quarantinedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal quarantinedQty;

    @Column(name = "quarantineStatus", nullable = false)
    private String quarantineStatus;

    @Column(name = "quarantineError")
    private String quarantineError;

    @Column(name = "quarantineMoveRef")
    private String quarantineMoveRef;

    @Column(name = "quarantineLocationId")
    private String quarantineLocationId;

    @Column(name = "sourceClosed", nullable = false)
    private Boolean sourceClosed;

    @Column(name = "correctsCheckId")
    private String correctsCheckId;

    @Column(name = "includeInSpc", nullable = false)
    private Boolean includeInSpc;

    @Column(name = "waivedBy")
    private String waivedBy;

    @Column(name = "waivedAt")
    private Instant waivedAt;

    @Column(name = "waiveReason")
    private String waiveReason;

    @Column(name = "waiveDisposition")
    private String waiveDisposition;

    @Column(name = "excludedQty", precision = 20, scale = 6)
    private BigDecimal excludedQty;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "doneBy")
    private String doneBy;

    @Column(name = "doneAt")
    private Instant doneAt;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
