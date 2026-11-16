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

/** Table {@code "Tax"} (owned by module {@code core}). */
@Entity
@Table(name = "Tax")
@DynamicInsert
@DynamicUpdate
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class Tax {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code")
    private String code;

    @Column(name = "type", nullable = false)
    private TaxType type;

    @Column(name = "rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal rate;

    @Column(name = "isInclusive", nullable = false)
    private Boolean isInclusive;

    @Column(name = "isCompound", nullable = false)
    private Boolean isCompound;

    @Column(name = "vatCategory", nullable = false)
    private String vatCategory;

    @Column(name = "accountId")
    private String accountId;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    /** ADR-S071: a group tax applies its ordered children ({@code TaxGroupMember}) instead of a rate of its own. */
    @Column(name = "isGroup", nullable = false)
    private Boolean isGroup;

    /** ADR-S071: reverse-charge (self-assessed) VAT on a vendor bill: input and output VAT, nothing payable. */
    @Column(name = "reverseCharge", nullable = false)
    private Boolean reverseCharge;

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

    @Column(name = "deletedAt")
    private Instant deletedAt;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public TaxType getType() {
        return type;
    }

    public void setType(TaxType type) {
        this.type = type;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public Boolean getIsInclusive() {
        return isInclusive;
    }

    public void setIsInclusive(Boolean isInclusive) {
        this.isInclusive = isInclusive;
    }

    public Boolean getIsCompound() {
        return isCompound;
    }

    public void setIsCompound(Boolean isCompound) {
        this.isCompound = isCompound;
    }

    public String getVatCategory() {
        return vatCategory;
    }

    public void setVatCategory(String vatCategory) {
        this.vatCategory = vatCategory;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getIsGroup() {
        return isGroup;
    }

    public void setIsGroup(Boolean isGroup) {
        this.isGroup = isGroup;
    }

    public Boolean getReverseCharge() {
        return reverseCharge;
    }

    public void setReverseCharge(Boolean reverseCharge) {
        this.reverseCharge = reverseCharge;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
