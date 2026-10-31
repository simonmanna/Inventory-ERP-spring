package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryExplosiveIssue"}: Explosive issue request: approval-gated, issued from a magazine, reconciled (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExplosiveIssue")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExplosiveIssue {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "blastId", nullable = false)
    private String blastId;

    @Column(name = "magazineId", nullable = false)
    private String magazineId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "shotfirerEmployeeId")
    private String shotfirerEmployeeId;

    @Column(name = "shotfirerLicenceNo")
    private String shotfirerLicenceNo;

    @Column(name = "requestedBy")
    private String requestedBy;

    @Column(name = "requestedAt")
    private Instant requestedAt;

    @Column(name = "approvalRequestId")
    private String approvalRequestId;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "issuedBy")
    private String issuedBy;

    @Column(name = "issuedAt")
    private Instant issuedAt;

    @Column(name = "issueJournalEntryId")
    private String issueJournalEntryId;

    @Column(name = "issuedValue", nullable = false, precision = 18, scale = 2)
    private BigDecimal issuedValue;

    @Column(name = "reconciledBy")
    private String reconciledBy;

    @Column(name = "reconciledAt")
    private Instant reconciledAt;

    @Column(name = "returnJournalEntryId")
    private String returnJournalEntryId;

    @Column(name = "returnedValue", nullable = false, precision = 18, scale = 2)
    private BigDecimal returnedValue;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
