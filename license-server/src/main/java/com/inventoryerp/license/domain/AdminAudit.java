package com.inventoryerp.license.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Every mutating admin call, whoever made it. Kept apart from {@link LicenseEvent}: that one is the
 * licence's own history, this one answers "who did this, from where".
 */
@Entity
@Table(name = "admin_audit")
public class AdminAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "admin_id")
    private String adminId;

    @Column(name = "admin_email")
    private String adminEmail;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "target")
    private String target;

    @Column(name = "ip")
    private String ip;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private String detail;

    @PrePersist
    void assignDefaults() {
        if (at == null) {
            at = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public String getAdminId() {
        return adminId;
    }

    public void setAdminId(String adminId) {
        this.adminId = adminId;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
