package com.inventoryerp.documents.domain;

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
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;

/** Table {@code "DocumentLine"} (owned by module {@code documents}). */
@Entity
@Table(name = "DocumentLine")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class DocumentLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "documentId", nullable = false)
    private String documentId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "menuItemId")
    private String menuItemId;

    @Column(name = "accountId")
    private String accountId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unitPrice", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "discountPercent", nullable = false, precision = 9, scale = 4)
    private BigDecimal discountPercent;

    @Column(name = "taxId")
    private String taxId;

    @Column(name = "subtotal", nullable = false, precision = 20, scale = 6)
    private BigDecimal subtotal;

    @Column(name = "taxAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal taxAmount;

    @Column(name = "total", nullable = false, precision = 20, scale = 6)
    private BigDecimal total;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "lineType", nullable = false)
    private String lineType;

    @Column(name = "taxInclusive", nullable = false)
    private Boolean taxInclusive;

    /** Unit the line was entered in (V5 snapshot, ADR-S003). */
    @Column(name = "uomId")
    private String uomId;

    /** Base units per 1 line unit at the time the line was written (V5 snapshot, ADR-S003). */
    @Column(name = "uomFactor", precision = 20, scale = 10)
    private BigDecimal uomFactor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documentId", insertable = false, updatable = false)
    private Document document;

    @Column(name = "kitchenPrintCount", nullable = false)
    private Integer kitchenPrintCount;

    @Column(name = "kitchenLastPrintedAt")
    private Instant kitchenLastPrintedAt;

    @Column(name = "kitchenPrintedQty", precision = 20, scale = 6)
    private BigDecimal kitchenPrintedQty;

    @Column(name = "cancelPrintCount", nullable = false)
    private Integer cancelPrintCount;

    @Column(name = "cancelLastPrintedAt")
    private Instant cancelLastPrintedAt;

    @Column(name = "lastKitchenPrintedById")
    private String lastKitchenPrintedById;

    @Column(name = "billPrintedAt")
    private Instant billPrintedAt;
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

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getMenuItemId() {
        return menuItemId;
    }

    public void setMenuItemId(String menuItemId) {
        this.menuItemId = menuItemId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
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

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getLineType() {
        return lineType;
    }

    public void setLineType(String lineType) {
        this.lineType = lineType;
    }

    public Boolean getTaxInclusive() {
        return taxInclusive;
    }

    public void setTaxInclusive(Boolean taxInclusive) {
        this.taxInclusive = taxInclusive;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public Integer getKitchenPrintCount() {
        return kitchenPrintCount;
    }

    public void setKitchenPrintCount(Integer kitchenPrintCount) {
        this.kitchenPrintCount = kitchenPrintCount;
    }

    public Instant getKitchenLastPrintedAt() {
        return kitchenLastPrintedAt;
    }

    public void setKitchenLastPrintedAt(Instant kitchenLastPrintedAt) {
        this.kitchenLastPrintedAt = kitchenLastPrintedAt;
    }

    public BigDecimal getKitchenPrintedQty() {
        return kitchenPrintedQty;
    }

    public void setKitchenPrintedQty(BigDecimal kitchenPrintedQty) {
        this.kitchenPrintedQty = kitchenPrintedQty;
    }

    public Integer getCancelPrintCount() {
        return cancelPrintCount;
    }

    public void setCancelPrintCount(Integer cancelPrintCount) {
        this.cancelPrintCount = cancelPrintCount;
    }

    public Instant getCancelLastPrintedAt() {
        return cancelLastPrintedAt;
    }

    public void setCancelLastPrintedAt(Instant cancelLastPrintedAt) {
        this.cancelLastPrintedAt = cancelLastPrintedAt;
    }

    public String getLastKitchenPrintedById() {
        return lastKitchenPrintedById;
    }

    public void setLastKitchenPrintedById(String lastKitchenPrintedById) {
        this.lastKitchenPrintedById = lastKitchenPrintedById;
    }

    public Instant getBillPrintedAt() {
        return billPrintedAt;
    }

    public void setBillPrintedAt(Instant billPrintedAt) {
        this.billPrintedAt = billPrintedAt;
    }

    public String getUomId() {
        return uomId;
    }

    public void setUomId(String uomId) {
        this.uomId = uomId;
    }

    public BigDecimal getUomFactor() {
        return uomFactor;
    }

    public void setUomFactor(BigDecimal uomFactor) {
        this.uomFactor = uomFactor;
    }
}
