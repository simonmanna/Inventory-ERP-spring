package com.inventoryerp.manufacturing.domain;

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
 * Table {@code "ProcessingJobCost"}: Audited conversion cost of a processing job.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingJobCost")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingJobCost {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "jobId", nullable = false)
    private String jobId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "description")
    private String description;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "exchangeRate", precision = 20, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "baseAmount", nullable = false, precision = 20, scale = 6)
    private BigDecimal baseAmount;

    @Column(name = "costDate", nullable = false)
    private LocalDate costDate;

    @Column(name = "sourceType")
    private String sourceType;

    @Column(name = "sourceId")
    private String sourceId;

    @Column(name = "allocationMethod", nullable = false)
    private String allocationMethod;

    @Column(name = "posted", nullable = false)
    private Boolean posted;

    @Column(name = "postingId")
    private String postingId;

    @Column(name = "creditAccountId")
    private String creditAccountId;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
