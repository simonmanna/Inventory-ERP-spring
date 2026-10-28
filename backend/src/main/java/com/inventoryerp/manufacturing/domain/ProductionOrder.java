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

/** Table {@code "ProductionOrder"} (owned by module {@code manufacturing}). */
@Entity
@Table(name = "ProductionOrder")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionOrder {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderCode", nullable = false)
    private String orderCode;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "bomVersion")
    private Integer bomVersion;

    @Column(name = "requestId")
    private String requestId;

    @Column(name = "planId")
    private String planId;

    @Column(name = "qcRequired", nullable = false)
    private Boolean qcRequired;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversalReason")
    private String reversalReason;

    @Column(name = "outputProductId", nullable = false)
    private String outputProductId;

    @Column(name = "outputVariantId")
    private String outputVariantId;

    @Column(name = "locationId", nullable = false)
    private String locationId;

    @Column(name = "outputLocationId")
    private String outputLocationId;

    @Column(name = "status", nullable = false)
    private ProductionOrderStatus status;

    @Column(name = "plannedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedQty;

    @Column(name = "producedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal producedQty;

    @Column(name = "scrapQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal scrapQty;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "scheduledFor")
    private Instant scheduledFor;

    @Column(name = "startedAt")
    private Instant startedAt;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "consumedAt")
    private Instant consumedAt;

    @Column(name = "postedAt")
    private Instant postedAt;

    @Column(name = "materialCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal materialCost;

    @Column(name = "overheadCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal overheadCost;

    @Column(name = "byproductCredit", nullable = false, precision = 20, scale = 6)
    private BigDecimal byproductCredit;

    @Column(name = "totalCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "outputUnitCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal outputUnitCost;

    @Column(name = "yieldVariance", nullable = false, precision = 20, scale = 6)
    private BigDecimal yieldVariance;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "expiryDate")
    private Instant expiryDate;

    @Column(name = "mfgDate")
    private Instant mfgDate;

    @Column(name = "notes")
    private String notes;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "performedById")
    private String performedById;

    @Column(name = "approvedById")
    private String approvedById;

    @Column(name = "approvedAt")
    private Instant approvedAt;

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
    @Column(name = "parentOrderId")
    private String parentOrderId;

    /** ADR-S006 (V9). */
    @Column(name = "orderKind", nullable = false)
    private String orderKind;

    /** ADR-S006 (V9). */
    @Column(name = "standardCostId")
    private String standardCostId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bomId", insertable = false, updatable = false)
    private Bom bom;
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

    public String getOrderCode() {
        return orderCode;
    }

    public void setOrderCode(String orderCode) {
        this.orderCode = orderCode;
    }

    public String getBomId() {
        return bomId;
    }

    public void setBomId(String bomId) {
        this.bomId = bomId;
    }

    public Integer getBomVersion() {
        return bomVersion;
    }

    public void setBomVersion(Integer bomVersion) {
        this.bomVersion = bomVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public Boolean getQcRequired() {
        return qcRequired;
    }

    public void setQcRequired(Boolean qcRequired) {
        this.qcRequired = qcRequired;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(Instant reversedAt) {
        this.reversedAt = reversedAt;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(String reversalReason) {
        this.reversalReason = reversalReason;
    }

    public String getOutputProductId() {
        return outputProductId;
    }

    public void setOutputProductId(String outputProductId) {
        this.outputProductId = outputProductId;
    }

    public String getOutputVariantId() {
        return outputVariantId;
    }

    public void setOutputVariantId(String outputVariantId) {
        this.outputVariantId = outputVariantId;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public String getOutputLocationId() {
        return outputLocationId;
    }

    public void setOutputLocationId(String outputLocationId) {
        this.outputLocationId = outputLocationId;
    }

    public ProductionOrderStatus getStatus() {
        return status;
    }

    public void setStatus(ProductionOrderStatus status) {
        this.status = status;
    }

    public BigDecimal getPlannedQty() {
        return plannedQty;
    }

    public void setPlannedQty(BigDecimal plannedQty) {
        this.plannedQty = plannedQty;
    }

    public BigDecimal getProducedQty() {
        return producedQty;
    }

    public void setProducedQty(BigDecimal producedQty) {
        this.producedQty = producedQty;
    }

    public BigDecimal getScrapQty() {
        return scrapQty;
    }

    public void setScrapQty(BigDecimal scrapQty) {
        this.scrapQty = scrapQty;
    }

    public String getUomId() {
        return uomId;
    }

    public void setUomId(String uomId) {
        this.uomId = uomId;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public void setScheduledFor(Instant scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public BigDecimal getMaterialCost() {
        return materialCost;
    }

    public void setMaterialCost(BigDecimal materialCost) {
        this.materialCost = materialCost;
    }

    public BigDecimal getOverheadCost() {
        return overheadCost;
    }

    public void setOverheadCost(BigDecimal overheadCost) {
        this.overheadCost = overheadCost;
    }

    public BigDecimal getByproductCredit() {
        return byproductCredit;
    }

    public void setByproductCredit(BigDecimal byproductCredit) {
        this.byproductCredit = byproductCredit;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public BigDecimal getOutputUnitCost() {
        return outputUnitCost;
    }

    public void setOutputUnitCost(BigDecimal outputUnitCost) {
        this.outputUnitCost = outputUnitCost;
    }

    public BigDecimal getYieldVariance() {
        return yieldVariance;
    }

    public void setYieldVariance(BigDecimal yieldVariance) {
        this.yieldVariance = yieldVariance;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(Instant expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Instant getMfgDate() {
        return mfgDate;
    }

    public void setMfgDate(Instant mfgDate) {
        this.mfgDate = mfgDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getPerformedById() {
        return performedById;
    }

    public void setPerformedById(String performedById) {
        this.performedById = performedById;
    }

    public String getApprovedById() {
        return approvedById;
    }

    public void setApprovedById(String approvedById) {
        this.approvedById = approvedById;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
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

    public Bom getBom() {
        return bom;
    }

    public void setBom(Bom bom) {
        this.bom = bom;
    }
}
