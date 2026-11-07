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
 * Table {@code "ProcessingJob"}: One processing step; execution state lives on its production order.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingJob")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingJob {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "templateId", nullable = false)
    private String templateId;

    @Column(name = "templateVersion", nullable = false)
    private Integer templateVersion;

    @Column(name = "jobDate", nullable = false)
    private LocalDate jobDate;

    @Column(name = "locationId", nullable = false)
    private String locationId;

    @Column(name = "outputLocationId", nullable = false)
    private String outputLocationId;

    @Column(name = "orderId")
    private String orderId;

    @Column(name = "stageId")
    private String stageId;

    @Column(name = "reprocessOfJobId")
    private String reprocessOfJobId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot")
    private String snapshot;

    @Column(name = "overYieldReason")
    private String overYieldReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelledBy")
    private String cancelledBy;

    @Column(name = "cancelReason")
    private String cancelReason;

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
}
