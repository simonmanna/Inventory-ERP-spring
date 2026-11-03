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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code "LandedCostAllocation"} (owned by module {@code procurement}). */
@Entity
@Table(name = "LandedCostAllocation")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class LandedCostAllocation {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "landedCostId", nullable = false)
    private String landedCostId;

    @Column(name = "goodsReceiptLineId", nullable = false)
    private String goodsReceiptLineId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "locationId", nullable = false)
    private String locationId;

    @Column(name = "batchId")
    private String batchId;

    @Column(name = "receivedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal receivedQty;

    @Column(name = "onHandQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal onHandQty;

    @Column(name = "basisValue", nullable = false, precision = 20, scale = 6)
    private BigDecimal basisValue;

    @Column(name = "allocatedAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal allocatedAmount;

    @Column(name = "capitalizedAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal capitalizedAmount;

    @Column(name = "expensedAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal expensedAmount;

    @Column(name = "unitCostBefore", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitCostBefore;

    @Column(name = "unitCostAfter", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitCostAfter;

    @Column(name = "chargeId")
    private String chargeId;

    @Column(name = "goodsReceiptId")
    private String goodsReceiptId;

    @Column(name = "basisMethod")
    private String basisMethod;

    @Column(name = "sharePct", precision = 20, scale = 10)
    private BigDecimal sharePct;

    @Column(name = "roundingAmount", precision = 20, scale = 6)
    private BigDecimal roundingAmount;

    @Column(name = "reversedAmount", precision = 20, scale = 6)
    private BigDecimal reversedAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "landedCostId", insertable = false, updatable = false)
    private LandedCost landedCost;
    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disposition")
    private String disposition;

    @Column(name = "weightUsed")
    private java.math.BigDecimal weightUsed;

    @Column(name = "volumeUsed")
    private java.math.BigDecimal volumeUsed;

    @Column(name = "uomId")
    private String uomId;

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

    public String getGoodsReceiptLineId() {
        return goodsReceiptLineId;
    }

    public void setGoodsReceiptLineId(String goodsReceiptLineId) {
        this.goodsReceiptLineId = goodsReceiptLineId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public BigDecimal getReceivedQty() {
        return receivedQty;
    }

    public void setReceivedQty(BigDecimal receivedQty) {
        this.receivedQty = receivedQty;
    }

    public BigDecimal getOnHandQty() {
        return onHandQty;
    }

    public void setOnHandQty(BigDecimal onHandQty) {
        this.onHandQty = onHandQty;
    }

    public BigDecimal getBasisValue() {
        return basisValue;
    }

    public void setBasisValue(BigDecimal basisValue) {
        this.basisValue = basisValue;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public void setAllocatedAmount(BigDecimal allocatedAmount) {
        this.allocatedAmount = allocatedAmount;
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

    public BigDecimal getUnitCostBefore() {
        return unitCostBefore;
    }

    public void setUnitCostBefore(BigDecimal unitCostBefore) {
        this.unitCostBefore = unitCostBefore;
    }

    public BigDecimal getUnitCostAfter() {
        return unitCostAfter;
    }

    public void setUnitCostAfter(BigDecimal unitCostAfter) {
        this.unitCostAfter = unitCostAfter;
    }

    public LandedCost getLandedCost() {
        return landedCost;
    }

    public void setLandedCost(LandedCost landedCost) {
        this.landedCost = landedCost;
    }

    public String getChargeId() {
        return chargeId;
    }

    public void setChargeId(String chargeId) {
        this.chargeId = chargeId;
    }

    public String getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public void setGoodsReceiptId(String goodsReceiptId) {
        this.goodsReceiptId = goodsReceiptId;
    }

    public String getBasisMethod() {
        return basisMethod;
    }

    public void setBasisMethod(String basisMethod) {
        this.basisMethod = basisMethod;
    }

    public BigDecimal getSharePct() {
        return sharePct;
    }

    public void setSharePct(BigDecimal sharePct) {
        this.sharePct = sharePct;
    }

    public BigDecimal getRoundingAmount() {
        return roundingAmount;
    }

    public void setRoundingAmount(BigDecimal roundingAmount) {
        this.roundingAmount = roundingAmount;
    }

    public BigDecimal getReversedAmount() {
        return reversedAmount;
    }

    public void setReversedAmount(BigDecimal reversedAmount) {
        this.reversedAmount = reversedAmount;
    }

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public java.math.BigDecimal getWeightUsed() {
        return weightUsed;
    }

    public void setWeightUsed(java.math.BigDecimal weightUsed) {
        this.weightUsed = weightUsed;
    }

    public java.math.BigDecimal getVolumeUsed() {
        return volumeUsed;
    }

    public void setVolumeUsed(java.math.BigDecimal volumeUsed) {
        this.volumeUsed = volumeUsed;
    }

    public String getUomId() {
        return uomId;
    }

    public void setUomId(String uomId) {
        this.uomId = uomId;
    }
}
