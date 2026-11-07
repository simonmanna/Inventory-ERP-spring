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
 * Table {@code "ProcessingJobInput"}: Input lot of a processing job.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingJobInput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingJobInput {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "jobId", nullable = false)
    private String jobId;

    @Column(name = "templateInputId")
    private String templateInputId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "genealogyBasis", nullable = false)
    private String genealogyBasis;

    @Column(name = "fixedPct", precision = 7, scale = 4)
    private BigDecimal fixedPct;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "plannedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedQty;

    @Column(name = "issuedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal issuedQty;

    @Column(name = "issuedCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal issuedCost;

    @Column(name = "basisQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal basisQty;

    @Column(name = "basisSource", nullable = false)
    private String basisSource;

    @Column(name = "manualSharePct", precision = 7, scale = 4)
    private BigDecimal manualSharePct;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
