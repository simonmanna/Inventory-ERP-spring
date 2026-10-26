package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What an install reported the last time it checked in: counts, version, enforcement mode.
 *
 * <p>Counts only — no customer, product or sales data ever leaves a customer's install
 * (docs/spec/licensing.md §9). Rows are swept after {@code app.retention.heartbeat-days}.
 */
@Entity
@Table(name = "license_heartbeat")
public class LicenseHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "activation_id", nullable = false)
    private String activationId;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "device_count")
    private Integer deviceCount;

    @Column(name = "user_count")
    private Integer userCount;

    @Column(name = "org_count")
    private Integer orgCount;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "mode")
    private String mode;

    @Column(name = "ip")
    private String ip;

    @PrePersist
    void assignDefaults() {
        if (at == null) {
            at = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getActivationId() {
        return activationId;
    }

    public void setActivationId(String activationId) {
        this.activationId = activationId;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public Integer getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(Integer deviceCount) {
        this.deviceCount = deviceCount;
    }

    public Integer getUserCount() {
        return userCount;
    }

    public void setUserCount(Integer userCount) {
        this.userCount = userCount;
    }

    public Integer getOrgCount() {
        return orgCount;
    }

    public void setOrgCount(Integer orgCount) {
        this.orgCount = orgCount;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
