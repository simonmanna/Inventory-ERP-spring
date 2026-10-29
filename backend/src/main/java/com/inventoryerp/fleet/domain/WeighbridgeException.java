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
 * Table {@code "WeighbridgeException"}: Out-of-tolerance exception of a weighbridge ticket, approved through the approval engine (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WeighbridgeException")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WeighbridgeException {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "ticketId", nullable = false)
    private String ticketId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "expectedKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedKg;

    @Column(name = "netKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal netKg;

    @Column(name = "varianceKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal varianceKg;

    @Column(name = "variancePct", precision = 9, scale = 3)
    private BigDecimal variancePct;

    @Column(name = "allowedVarianceKg", nullable = false, precision = 14, scale = 2)
    private BigDecimal allowedVarianceKg;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "raisedBy")
    private String raisedBy;

    @Column(name = "raisedAt", nullable = false)
    private Instant raisedAt;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "decisionNotes")
    private String decisionNotes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
