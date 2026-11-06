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

/** Table {@code "DebitNoteLine"} (owned by module {@code procurement}). */
@Entity
@Table(name = "DebitNoteLine")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class DebitNoteLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "debitNoteId", nullable = false)
    private String debitNoteId;

    @Column(name = "productId")
    private String productId;

    /** ADR-S058: product variant of the line (null = product without variants). */
    @Column(name = "variantId")
    private String variantId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unitPrice", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "taxId")
    private String taxId;

    @Column(name = "taxAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal taxAmount;

    @Column(name = "subtotal", nullable = false, precision = 20, scale = 6)
    private BigDecimal subtotal;

    @Column(name = "total", nullable = false, precision = 20, scale = 6)
    private BigDecimal total;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "notes")
    private String notes;

    /** Unit the line was entered in (V5 snapshot, ADR-S003). */
    @Column(name = "uomId")
    private String uomId;

    /** Base units per 1 line unit at the time the line was written (V5 snapshot, ADR-S003). */
    @Column(name = "uomFactor", precision = 20, scale = 10)
    private BigDecimal uomFactor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debitNoteId", insertable = false, updatable = false)
    private DebitNote debitNote;
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

    public String getDebitNoteId() {
        return debitNoteId;
    }

    public void setDebitNoteId(String debitNoteId) {
        this.debitNoteId = debitNoteId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
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

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public DebitNote getDebitNote() {
        return debitNote;
    }

    public void setDebitNote(DebitNote debitNote) {
        this.debitNote = debitNote;
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
