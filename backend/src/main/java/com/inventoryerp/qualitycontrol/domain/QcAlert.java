package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcAlert"}: Quality alert (ADR-S087 §8): operational investigation of a quality problem; may escalate to one NCR.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcAlert")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcAlert {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "alertNumber", nullable = false)
    private String alertNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "stageId", nullable = false)
    private String stageId;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "teamId")
    private String teamId;

    @Column(name = "responsibleUserId")
    private String responsibleUserId;

    @Column(name = "checkId")
    private String checkId;

    @Column(name = "correctionCheckId")
    private String correctionCheckId;

    @Column(name = "controlPointId")
    private String controlPointId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "sourceType")
    private String sourceType;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "sourceRef")
    private String sourceRef;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "failureClassId")
    private String failureClassId;

    @Column(name = "rootCauseId")
    private String rootCauseId;

    @Column(name = "correctiveAction")
    private String correctiveAction;

    @Column(name = "preventiveAction")
    private String preventiveAction;

    @Column(name = "ncrId")
    private String ncrId;

    @Column(name = "closedAt")
    private Instant closedAt;

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
