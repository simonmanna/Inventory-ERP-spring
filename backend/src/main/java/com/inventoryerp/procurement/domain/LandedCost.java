package com.inventoryerp.procurement.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

/** Table {@code "LandedCost"} (owned by module {@code procurement}). */
@Entity
@Table(name = "LandedCost")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class LandedCost {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "goodsReceiptId", nullable = false)
    private String goodsReceiptId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "allocationMethod", nullable = false)
    private String allocationMethod;

    @Column(name = "creditAccountId", nullable = false)
    private String creditAccountId;

    @Column(name = "date", nullable = false)
    private Instant date;

    @Column(name = "notes")
    private String notes;

    @Column(name = "totalAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal totalAmount;

    @Column(name = "capitalizedAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal capitalizedAmount;

    @Column(name = "expensedAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal expensedAmount;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "postedAt")
    private Instant postedAt;

    @Column(name = "postedById")
    private String postedById;

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

    @Column(name = "voucherType")
    private String voucherType;

    /** On a credit voucher: the voucher it credits (LC-1, ADR-S053). */
    @Column(name = "originalVoucherId")
    private String originalVoucherId;

    @Column(name = "shipmentId")
    private String shipmentId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "submittedAt")
    private Instant submittedAt;

    @Column(name = "submittedBy")
    private String submittedBy;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "approvedBy")
    private String approvedBy;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reversalJournalEntryId")
    private String reversalJournalEntryId;

    @Column(name = "taxAmount", precision = 20, scale = 6)
    private BigDecimal taxAmount;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public void setGoodsReceiptId(String goodsReceiptId) {
        this.goodsReceiptId = goodsReceiptId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAllocationMethod() {
        return allocationMethod;
    }

    public void setAllocationMethod(String allocationMethod) {
        this.allocationMethod = allocationMethod;
    }

    public String getCreditAccountId() {
        return creditAccountId;
    }

    public void setCreditAccountId(String creditAccountId) {
        this.creditAccountId = creditAccountId;
    }

    public Instant getDate() {
        return date;
    }

    public void setDate(Instant date) {
        this.date = date;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getCapitalizedAmount() {
        return capitalizedAmount;
    }

    public void setCapitalizedAmount(BigDecimal capitalizedAmount) {
        this.capitalizedAmount = capitalizedAmount;
    }

    public BigDecimal getExpensedAmount() {
        return expensedAmount;
    }

    public void setExpensedAmount(BigDecimal expensedAmount) {
        this.expensedAmount = expensedAmount;
    }

    public String getJournalEntryId() {
        return journalEntryId;
    }

    public void setJournalEntryId(String journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public String getPostedById() {
        return postedById;
    }

    public void setPostedById(String postedById) {
        this.postedById = postedById;
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

    public String getVoucherType() {
        return voucherType;
    }

    public void setVoucherType(String voucherType) {
        this.voucherType = voucherType;
    }

    public String getOriginalVoucherId() {
        return originalVoucherId;
    }

    public void setOriginalVoucherId(String originalVoucherId) {
        this.originalVoucherId = originalVoucherId;
    }

    public String getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(String shipmentId) {
        this.shipmentId = shipmentId;
    }

    public String getCostCenterId() {
        return costCenterId;
    }

    public void setCostCenterId(String costCenterId) {
        this.costCenterId = costCenterId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(Instant reversedAt) {
        this.reversedAt = reversedAt;
    }

    public String getReversedBy() {
        return reversedBy;
    }

    public void setReversedBy(String reversedBy) {
        this.reversedBy = reversedBy;
    }

    public String getReversalJournalEntryId() {
        return reversalJournalEntryId;
    }

    public void setReversalJournalEntryId(String reversalJournalEntryId) {
        this.reversalJournalEntryId = reversalJournalEntryId;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }
}
