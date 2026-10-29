package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetMeterCorrection"}: Approval-gated correction of a meter reading or a meter replacement (ADR-S009).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetMeterCorrection")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetMeterCorrection {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "meterType", nullable = false)
    private String meterType;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "readingId")
    private String readingId;

    @Column(name = "previousValue", precision = 14, scale = 1)
    private BigDecimal previousValue;

    @Column(name = "correctedValue", nullable = false, precision = 14, scale = 1)
    private BigDecimal correctedValue;

    @Column(name = "readingAt")
    private Instant readingAt;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "failureReason")
    private String failureReason;

    @Column(name = "requestedBy")
    private String requestedBy;

    @Column(name = "requestedAt", nullable = false)
    private Instant requestedAt;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "appliedReadingId")
    private String appliedReadingId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
