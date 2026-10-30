package com.inventoryerp.hr.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "HrPayrollInput"}: One-off payroll input (earning) queued for an employee's next payroll run by
 * another module, e.g. fleet driver claims (ADR-S009 §2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "HrPayrollInput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class HrPayrollInput {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "employeeId", nullable = false)
    private String employeeId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "earnedOn", nullable = false)
    private LocalDate earnedOn;

    @Column(name = "isTaxable", nullable = false)
    private Boolean isTaxable;

    @Column(name = "debitAccountId")
    private String debitAccountId;

    @Column(name = "creditAccountId")
    private String creditAccountId;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "sourceId", nullable = false)
    private String sourceId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "runId")
    private String runId;

    @Column(name = "itemId")
    private String itemId;

    @Column(name = "includedAt")
    private Instant includedAt;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
