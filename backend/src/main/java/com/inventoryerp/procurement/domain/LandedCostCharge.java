package com.inventoryerp.procurement.domain;

import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import java.util.List;
import java.time.LocalDate;
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

/** Table {@code "LandedCostCharge"} (owned by module {@code procurement}). */
@Entity
@Table(name = "LandedCostCharge")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class LandedCostCharge {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "landedCostId", nullable = false)
    private String landedCostId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "description")
    private String description;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "lineNumber")
    private Integer lineNumber;

    @Column(name = "costComponentId")
    private String costComponentId;

    @Column(name = "supplierId")
    private String supplierId;

    @Column(name = "supplierInvoiceRef")
    private String supplierInvoiceRef;

    @Column(name = "invoiceDate")
    private LocalDate invoiceDate;

    @Column(name = "currencyCode")
    private String currencyCode;

    @Column(name = "exchangeRate", precision = 20, scale = 10)
    private BigDecimal exchangeRate;

    @Column(name = "baseAmount", precision = 20, scale = 6)
    private BigDecimal baseAmount;

    @Column(name = "taxAmount", precision = 20, scale = 6)
    private BigDecimal taxAmount;

    @Column(name = "treatment")
    private String treatment;

    @Column(name = "allocationMethod")
    private String allocationMethod;

    @Column(name = "expenseAccountId")
    private String expenseAccountId;

    @Column(name = "creditAccountId")
    private String creditAccountId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "appliesToLineIds")
    private List<String> appliesToLineIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manualAllocations")
    private String manualAllocations;

    @Column(name = "billMode", nullable = false)
    private String billMode;

    @Column(name = "vendorBillId")
    private String vendorBillId;

    @Column(name = "dueDate")
    private LocalDate dueDate;

    @Column(name = "taxId")
    private String taxId;

    /** Tax is recoverable input tax (true), part of the charge (false), or unreviewed (null, ADR-S053). */
    @Column(name = "taxRecoverable")
    private Boolean taxRecoverable;

    /** On a credit voucher: the original charge this line credits (LC-1, ADR-S053). */
    @Column(name = "creditOfChargeId")
    private String creditOfChargeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "landedCostId", insertable = false, updatable = false)
    private LandedCost landedCost;
    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "supplierInvoiceRefNorm")
    private String supplierInvoiceRefNorm;

    @Column(name = "live")
    private Boolean live;

    @Column(name = "duplicateOverrideId")
    private String duplicateOverrideId;

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

    public String getLandedCostId() {
        return landedCostId;
    }

    public void setLandedCostId(String landedCostId) {
        this.landedCostId = landedCostId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LandedCost getLandedCost() {
        return landedCost;
    }

    public void setLandedCost(LandedCost landedCost) {
        this.landedCost = landedCost;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getCostComponentId() {
        return costComponentId;
    }

    public void setCostComponentId(String costComponentId) {
        this.costComponentId = costComponentId;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(String supplierId) {
        this.supplierId = supplierId;
    }

    public String getSupplierInvoiceRef() {
        return supplierInvoiceRef;
    }

    public void setSupplierInvoiceRef(String supplierInvoiceRef) {
        this.supplierInvoiceRef = supplierInvoiceRef;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public String getTreatment() {
        return treatment;
    }

    public void setTreatment(String treatment) {
        this.treatment = treatment;
    }

    public String getAllocationMethod() {
        return allocationMethod;
    }

    public void setAllocationMethod(String allocationMethod) {
        this.allocationMethod = allocationMethod;
    }

    public String getExpenseAccountId() {
        return expenseAccountId;
    }

    public void setExpenseAccountId(String expenseAccountId) {
        this.expenseAccountId = expenseAccountId;
    }

    public String getCreditAccountId() {
        return creditAccountId;
    }

    public void setCreditAccountId(String creditAccountId) {
        this.creditAccountId = creditAccountId;
    }

    public String getCostCenterId() {
        return costCenterId;
    }

    public void setCostCenterId(String costCenterId) {
        this.costCenterId = costCenterId;
    }

    public List<String> getAppliesToLineIds() {
        return appliesToLineIds;
    }

    public void setAppliesToLineIds(List<String> appliesToLineIds) {
        this.appliesToLineIds = appliesToLineIds;
    }

    public String getManualAllocations() {
        return manualAllocations;
    }

    public void setManualAllocations(String manualAllocations) {
        this.manualAllocations = manualAllocations;
    }

    public String getBillMode() {
        return billMode;
    }

    public void setBillMode(String billMode) {
        this.billMode = billMode;
    }

    public String getVendorBillId() {
        return vendorBillId;
    }

    public void setVendorBillId(String vendorBillId) {
        this.vendorBillId = vendorBillId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public Boolean getTaxRecoverable() {
        return taxRecoverable;
    }

    public void setTaxRecoverable(Boolean taxRecoverable) {
        this.taxRecoverable = taxRecoverable;
    }

    public String getCreditOfChargeId() {
        return creditOfChargeId;
    }

    public void setCreditOfChargeId(String creditOfChargeId) {
        this.creditOfChargeId = creditOfChargeId;
    }

    public String getSupplierInvoiceRefNorm() {
        return supplierInvoiceRefNorm;
    }

    public void setSupplierInvoiceRefNorm(String supplierInvoiceRefNorm) {
        this.supplierInvoiceRefNorm = supplierInvoiceRefNorm;
    }

    public Boolean getLive() {
        return live;
    }

    public void setLive(Boolean live) {
        this.live = live;
    }

    public String getDuplicateOverrideId() {
        return duplicateOverrideId;
    }

    public void setDuplicateOverrideId(String duplicateOverrideId) {
        this.duplicateOverrideId = duplicateOverrideId;
    }
}
