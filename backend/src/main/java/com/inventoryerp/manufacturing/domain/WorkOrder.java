package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

/** Table {@code "WorkOrder"} (owned by module {@code manufacturing}). */
@Entity
@Table(name = "WorkOrder")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WorkOrder {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productionOrderId", nullable = false)
    private String productionOrderId;

    @Column(name = "routingOperationId")
    private String routingOperationId;

    @Column(name = "operationSequence", nullable = false)
    private Integer operationSequence;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "resourceId")
    private String resourceId;

    @Column(name = "status", nullable = false)
    private WorkOrderStatus status;

    @Column(name = "assignedToId")
    private String assignedToId;

    @Column(name = "plannedDurationMins")
    private Integer plannedDurationMins;

    @Column(name = "startedAt")
    private Instant startedAt;

    @Column(name = "pausedAt")
    private Instant pausedAt;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "labourMins", nullable = false)
    private Integer labourMins;

    @Column(name = "qtyGood", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyGood;

    @Column(name = "qtyScrap", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyScrap;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    /** ADR-S006 (V9). */
    @Column(name = "plannedStartAt")
    private Instant plannedStartAt;

    /** ADR-S006 (V9). */
    @Column(name = "plannedEndAt")
    private Instant plannedEndAt;

    /** ADR-S006 (V9). */
    @Column(name = "scrapReasonCode")
    private String scrapReasonCode;

    /** ADR-S006 (V9). */
    @Column(name = "subcontractCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal subcontractCost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workCenterId", insertable = false, updatable = false)
    private WorkCenter workCenter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resourceId", insertable = false, updatable = false)
    private Resource resource;
    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getProductionOrderId() {
        return productionOrderId;
    }

    public void setProductionOrderId(String productionOrderId) {
        this.productionOrderId = productionOrderId;
    }

    public String getRoutingOperationId() {
        return routingOperationId;
    }

    public void setRoutingOperationId(String routingOperationId) {
        this.routingOperationId = routingOperationId;
    }

    public Integer getOperationSequence() {
        return operationSequence;
    }

    public void setOperationSequence(Integer operationSequence) {
        this.operationSequence = operationSequence;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWorkCenterId() {
        return workCenterId;
    }

    public void setWorkCenterId(String workCenterId) {
        this.workCenterId = workCenterId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public void setStatus(WorkOrderStatus status) {
        this.status = status;
    }

    public String getAssignedToId() {
        return assignedToId;
    }

    public void setAssignedToId(String assignedToId) {
        this.assignedToId = assignedToId;
    }

    public Integer getPlannedDurationMins() {
        return plannedDurationMins;
    }

    public void setPlannedDurationMins(Integer plannedDurationMins) {
        this.plannedDurationMins = plannedDurationMins;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getLabourMins() {
        return labourMins;
    }

    public void setLabourMins(Integer labourMins) {
        this.labourMins = labourMins;
    }

    public BigDecimal getQtyGood() {
        return qtyGood;
    }

    public void setQtyGood(BigDecimal qtyGood) {
        this.qtyGood = qtyGood;
    }

    public BigDecimal getQtyScrap() {
        return qtyScrap;
    }

    public void setQtyScrap(BigDecimal qtyScrap) {
        this.qtyScrap = qtyScrap;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public WorkCenter getWorkCenter() {
        return workCenter;
    }

    public void setWorkCenter(WorkCenter workCenter) {
        this.workCenter = workCenter;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }
}
