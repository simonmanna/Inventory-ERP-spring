package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "EngineeringChange"}: Engineering change order (ADR-S006): between two BOM versions, or a typed
 * routing / product / document change under PLM stages (ADR-S063).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "EngineeringChange")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class EngineeringChange {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "reason")
    private String reason;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "newBomId")
    private String newBomId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approvedById")
    private String approvedById;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "appliedAt")
    private Instant appliedAt;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "stageId")
    private String stageId;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "description")
    private String description;

    @Column(name = "productId")
    private String productId;

    @Column(name = "requestId")
    private String requestId;

    @Column(name = "routingId")
    private String routingId;

    @Column(name = "newRoutingId")
    private String newRoutingId;

    @Column(name = "targetLifecycleState")
    private String targetLifecycleState;

    @Column(name = "newRevision")
    private String newRevision;

    @Column(name = "effectivity", nullable = false)
    private String effectivity;

    @Column(name = "effectiveDate")
    private LocalDate effectiveDate;

    @Column(name = "stockDisposition", nullable = false)
    private String stockDisposition;

    @Column(name = "wipDisposition", nullable = false)
    private String wipDisposition;

    @Column(name = "ownerId")
    private String ownerId;

    @Column(name = "rejectedReason")
    private String rejectedReason;

    @Column(name = "applyQueued", nullable = false)
    private Boolean applyQueued;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dispositionResult")
    private String dispositionResult;

    /** PLM ECO type (ADR-S086); {@code type} stays the kind of change. */
    @Column(name = "changeTypeId")
    private String changeTypeId;

    @Column(name = "kanbanState", nullable = false)
    private String kanbanState;

    @Column(name = "blockedReason")
    private String blockedReason;

    /** PLM product master-data change (ADR-S086): the proposed values by field. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "productChanges")
    private String productChanges;

    /** What applying the master-data change did, {@code {field: {from, to}}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "productChangesApplied")
    private String productChangesApplied;

    /** PLM cost policy on apply (ADR-S086): {@code none}, {@code propose} or {@code activate}. */
    @Column(name = "costPolicy", nullable = false)
    private String costPolicy;

    @Column(name = "standardCostId")
    private String standardCostId;

    @Column(name = "revaluationJournalId")
    private String revaluationJournalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costResult")
    private String costResult;

    /** PLM first-article inspection (ADR-S086): the first order on the new version is inspected. */
    @Column(name = "firstArticle", nullable = false)
    private Boolean firstArticle;

    @Column(name = "faiStatus")
    private String faiStatus;

    @Column(name = "faiLotId")
    private String faiLotId;

    @Column(name = "faiOrderId")
    private String faiOrderId;

    @Column(name = "faiNote")
    private String faiNote;

    public String getId() {
        return id;
    }
}
