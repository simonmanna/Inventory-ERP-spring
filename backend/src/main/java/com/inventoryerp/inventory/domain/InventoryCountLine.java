package com.inventoryerp.inventory.domain;

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
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code "InventoryCountLine"} (owned by module {@code inventory}). */
@Entity
@Table(name = "InventoryCountLine")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class InventoryCountLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "sessionId", nullable = false)
    private String sessionId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "variantId")
    private String variantId;

    @Column(name = "productName", nullable = false)
    private String productName;

    @Column(name = "parentProductId")
    private String parentProductId;

    @Column(name = "parentProductName")
    private String parentProductName;

    @Column(name = "unit")
    private String unit;

    @Column(name = "systemQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal systemQty;

    @Column(name = "countedQty", precision = 20, scale = 6)
    private BigDecimal countedQty;

    @Column(name = "variance", nullable = false, precision = 20, scale = 6)
    private BigDecimal variance;

    @Column(name = "reason")
    private String reason;

    @Column(name = "countedById")
    private String countedById;

    @Column(name = "countedAt")
    private Instant countedAt;

    /** V465: the lot this line counts ({@code InventoryBatch} id); null on product-level and "no lot" lines. */
    @Column(name = "batchId")
    private String batchId;

    @Column(name = "batchNumber")
    private String batchNumber;

    /** V465: the line belongs to a per-lot count of a batch-tracked product (a lot line or its "no lot" line). */
    @Column(name = "lotLine", nullable = false)
    private Boolean lotLine;

    /** V465: serial numbers expected here but not found (a serial-tracked loss posts exactly these). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "missingSerials")
    private List<String> missingSerials;

    /** V465: serial numbers found that the system did not hold here (a serial-tracked gain receives these). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "foundSerials")
    private List<String> foundSerials;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sessionId", insertable = false, updatable = false)
    private InventoryCountSession session;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getParentProductId() {
        return parentProductId;
    }

    public void setParentProductId(String parentProductId) {
        this.parentProductId = parentProductId;
    }

    public String getParentProductName() {
        return parentProductName;
    }

    public void setParentProductName(String parentProductName) {
        this.parentProductName = parentProductName;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getSystemQty() {
        return systemQty;
    }

    public void setSystemQty(BigDecimal systemQty) {
        this.systemQty = systemQty;
    }

    public BigDecimal getCountedQty() {
        return countedQty;
    }

    public void setCountedQty(BigDecimal countedQty) {
        this.countedQty = countedQty;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public void setVariance(BigDecimal variance) {
        this.variance = variance;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCountedById() {
        return countedById;
    }

    public void setCountedById(String countedById) {
        this.countedById = countedById;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public void setCountedAt(Instant countedAt) {
        this.countedAt = countedAt;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public Boolean getLotLine() {
        return lotLine;
    }

    public void setLotLine(Boolean lotLine) {
        this.lotLine = lotLine;
    }

    public List<String> getMissingSerials() {
        return missingSerials;
    }

    public void setMissingSerials(List<String> missingSerials) {
        this.missingSerials = missingSerials;
    }

    public List<String> getFoundSerials() {
        return foundSerials;
    }

    public void setFoundSerials(List<String> foundSerials) {
        this.foundSerials = foundSerials;
    }

    public InventoryCountSession getSession() {
        return session;
    }

    public void setSession(InventoryCountSession session) {
        this.session = session;
    }
}
