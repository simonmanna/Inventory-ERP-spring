package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * What is on the price list: a period, a price, the features it unlocks and the caps that come with
 * it. A licence may override any of the caps and the feature set; the plan is the default.
 *
 * <p>{@code priceMinor} is an integer count of minor units (cents) — never a float, per AGENTS.md §8.
 * The licence server does not take payments, so it only records what was agreed.
 */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "period_count", nullable = false)
    private Integer periodCount = 1;

    @Column(name = "price_minor", nullable = false)
    private Long priceMinor = 0L;

    @Column(name = "currency", nullable = false)
    private String currency = "USD";

    @Column(name = "max_devices")
    private Integer maxDevices;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_organizations")
    private Integer maxOrganizations;

    @Column(name = "instance_limit", nullable = false)
    private Integer instanceLimit = 1;

    /** JSON array of feature keys the ERP understands. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false)
    private String features = "[]";

    @Column(name = "grace_days", nullable = false)
    private Integer graceDays = 7;

    @Column(name = "is_trial", nullable = false)
    private Boolean isTrial = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public LicensePeriod periodType() {
        return LicensePeriod.of(period);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Integer getPeriodCount() {
        return periodCount;
    }

    public void setPeriodCount(Integer periodCount) {
        this.periodCount = periodCount;
    }

    public Long getPriceMinor() {
        return priceMinor;
    }

    public void setPriceMinor(Long priceMinor) {
        this.priceMinor = priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getMaxDevices() {
        return maxDevices;
    }

    public void setMaxDevices(Integer maxDevices) {
        this.maxDevices = maxDevices;
    }

    public Integer getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(Integer maxUsers) {
        this.maxUsers = maxUsers;
    }

    public Integer getMaxOrganizations() {
        return maxOrganizations;
    }

    public void setMaxOrganizations(Integer maxOrganizations) {
        this.maxOrganizations = maxOrganizations;
    }

    public Integer getInstanceLimit() {
        return instanceLimit;
    }

    public void setInstanceLimit(Integer instanceLimit) {
        this.instanceLimit = instanceLimit;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public Boolean getIsTrial() {
        return isTrial;
    }

    public void setIsTrial(Boolean isTrial) {
        this.isTrial = isTrial;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
