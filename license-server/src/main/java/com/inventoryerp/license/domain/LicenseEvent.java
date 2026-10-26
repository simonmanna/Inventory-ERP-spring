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
 * Append-only audit of a licence. Never updated and never deleted while its licence exists: this is
 * the record produced when a customer disputes a lockout, so it has to be trustworthy.
 *
 * @see LicenseEventType
 */
@Entity
@Table(name = "license_event")
public class LicenseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "license_id")
    private String licenseId;

    @Column(name = "activation_id")
    private String activationId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** Admin email, {@code install} for a client-driven event, or {@code system} for a job. */
    @Column(name = "actor")
    private String actor;

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

    public String getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    public String getActivationId() {
        return activationId;
    }

    public void setActivationId(String activationId) {
        this.activationId = activationId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
