package com.inventoryerp.hr.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "HrPayrollSettings"}: one row per organisation with the payroll engine, statutory pack, proration
 * basis, rounding and control switches (HP-0).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and schema
 * validation.
 */
@Entity
@Table(name = "HrPayrollSettings")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class HrPayrollSettings {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "engine", nullable = false)
    private String engine;

    @Column(name = "statutoryPack", nullable = false)
    private String statutoryPack;

    @Column(name = "prorationBasis", nullable = false)
    private String prorationBasis;

    @Column(name = "weekendDays", nullable = false)
    private String weekendDays;

    @Column(name = "standardHoursPerDay", nullable = false, precision = 5, scale = 2)
    private BigDecimal standardHoursPerDay;

    @Column(name = "roundingScale", nullable = false)
    private Integer roundingScale;

    @Column(name = "requireSeparateApprover", nullable = false)
    private Boolean requireSeparateApprover;

    @Column(name = "lockPeriodOnApproval", nullable = false)
    private Boolean lockPeriodOnApproval;

    @Column(name = "payslipNote")
    private String payslipNote;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
