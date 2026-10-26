package com.inventoryerp.core.domain;

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

/**
 * Table {@code "ProductUom"} (owned by module {@code core}, V5, ADR-S003): one extra unit a product can be bought or
 * sold in, with its own conversion to the product's base unit. Takes precedence over the organization-wide
 * {@link UnitOfMeasure#getFactor()} for that product.
 */
@Entity
@Table(name = "ProductUom")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductUom {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "uomId", nullable = false)
    private String uomId;

    /** Base units per 1 of this unit. */
    @Column(name = "factor", nullable = false, precision = 20, scale = 10)
    private BigDecimal factor;

    @Column(name = "isPurchase", nullable = false)
    private Boolean isPurchase;

    @Column(name = "isSales", nullable = false)
    private Boolean isSales;

    @Column(name = "salePrice", precision = 20, scale = 6)
    private BigDecimal salePrice;

    @Column(name = "purchasePrice", precision = 20, scale = 6)
    private BigDecimal purchasePrice;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @CreationTimestamp
    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

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

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getUomId() {
        return uomId;
    }

    public void setUomId(String uomId) {
        this.uomId = uomId;
    }

    public BigDecimal getFactor() {
        return factor;
    }

    public void setFactor(BigDecimal factor) {
        this.factor = factor;
    }

    public Boolean getIsPurchase() {
        return isPurchase;
    }

    public void setIsPurchase(Boolean isPurchase) {
        this.isPurchase = isPurchase;
    }

    public Boolean getIsSales() {
        return isSales;
    }

    public void setIsSales(Boolean isSales) {
        this.isSales = isSales;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(BigDecimal salePrice) {
        this.salePrice = salePrice;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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
}
