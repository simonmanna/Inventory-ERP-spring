package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ShopFloorDowntime"} (ADR-S015): Downtime event of a work centre (operator or machine draft).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorDowntime")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorDowntime {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "shiftReportId")
    private String shiftReportId;

    @Column(name = "reasonId")
    private String reasonId;

    @Column(name = "reasonCode")
    private String reasonCode;

    @Column(name = "isPlanned", nullable = false)
    private Boolean isPlanned;

    @Column(name = "startedAt", nullable = false)
    private Instant startedAt;

    @Column(name = "endedAt")
    private Instant endedAt;

    @Column(name = "minutes")
    private Integer minutes;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "machineEventId")
    private String machineEventId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "confirmedBy")
    private String confirmedBy;

    @Column(name = "confirmedAt")
    private Instant confirmedAt;

    @Column(name = "rejectedReason")
    private String rejectedReason;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
