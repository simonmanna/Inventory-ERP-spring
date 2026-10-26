package com.inventoryerp.procurement.domain;

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
import java.util.UUID;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;

/** Table {@code "PurchaseOrderLine"} (owned by module {@code procurement}). */
@Entity
@Table(name = "PurchaseOrderLine")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PurchaseOrderLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "purchaseOrderId", nullable = false)
    private String purchaseOrderId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "receivedQuantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal receivedQuantity;

    @Column(name = "billedQuantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal billedQuantity;

    @Column(name = "unitOfMeasureId")
    private String unitOfMeasureId;

    @Column(name = "unitPrice", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "taxRate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate;

    @Column(name = "taxId")
    private String taxId;

    @Column(name = "subtotal", nullable = false, precision = 20, scale = 6)
    private BigDecimal subtotal;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "notes")
    private String notes;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** Base units per 1 line unit at the time the line was written (V5 snapshot, ADR-S003). */
    @Column(name = "uomFactor", precision = 20, scale = 10)
    private BigDecimal uomFactor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseOrderId", insertable = false, updatable = false)
    private PurchaseOrder order;
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

    public String getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(String purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public void setReceivedQuantity(BigDecimal receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    public BigDecimal getBilledQuantity() {
        return billedQuantity;
    }

    public void setBilledQuantity(BigDecimal billedQuantity) {
        this.billedQuantity = billedQuantity;
    }

    public String getUnitOfMeasureId() {
        return unitOfMeasureId;
    }

    public void setUnitOfMeasureId(String unitOfMeasureId) {
        this.unitOfMeasureId = unitOfMeasureId;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    public void setOrder(PurchaseOrder order) {
        this.order = order;
    }

    public BigDecimal getUomFactor() {
        return uomFactor;
    }

    public void setUomFactor(BigDecimal uomFactor) {
        this.uomFactor = uomFactor;
    }
}
