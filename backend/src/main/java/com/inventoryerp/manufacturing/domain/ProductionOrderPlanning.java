package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ProductionOrderPlanning"}: Due date, priority and schedule result of a production order (ADR-S019).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionOrderPlanning")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionOrderPlanning {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productionOrderId", nullable = false)
    private String productionOrderId;

    @Column(name = "dueAt")
    private Instant dueAt;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "priorityReason")
    private String priorityReason;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "scheduleMode", nullable = false)
    private String scheduleMode;

    @Column(name = "plannedStartAt")
    private Instant plannedStartAt;

    @Column(name = "plannedEndAt")
    private Instant plannedEndAt;

    @Column(name = "availableAt")
    private Instant availableAt;

    @Column(name = "curingDays", nullable = false)
    private Integer curingDays;

    @Column(name = "lateMinutes", nullable = false)
    private Long lateMinutes;

    @Column(name = "scheduledAt")
    private Instant scheduledAt;

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
