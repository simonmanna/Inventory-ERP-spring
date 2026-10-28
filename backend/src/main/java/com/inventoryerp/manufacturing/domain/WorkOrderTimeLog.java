package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "WorkOrderTimeLog"}: Operator / resource time log of a work order (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WorkOrderTimeLog")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WorkOrderTimeLog {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workOrderId", nullable = false)
    private String workOrderId;

    @Column(name = "resourceId")
    private String resourceId;

    @Column(name = "userId")
    private String userId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "startedAt", nullable = false)
    private Instant startedAt;

    @Column(name = "endedAt")
    private Instant endedAt;

    @Column(name = "minutes", nullable = false)
    private Integer minutes;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
