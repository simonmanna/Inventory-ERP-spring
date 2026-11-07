package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ProcessingJobLoss"}: Declared loss of a processing job.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingJobLoss")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingJobLoss {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "jobId", nullable = false)
    private String jobId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "basisQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal basisQty;

    @Column(name = "value", nullable = false, precision = 20, scale = 6)
    private BigDecimal value;

    @Column(name = "reason")
    private String reason;

    @Column(name = "approvedBy")
    private String approvedBy;

    @Column(name = "postingId")
    private String postingId;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
