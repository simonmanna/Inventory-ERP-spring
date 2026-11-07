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
 * Table {@code "LotGenealogyLink"}: Many-to-many link from a consumed lot to a produced lot.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "LotGenealogyLink")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class LotGenealogyLink {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "jobId", nullable = false)
    private String jobId;

    @Column(name = "postingId")
    private String postingId;

    @Column(name = "sourceProductId", nullable = false)
    private String sourceProductId;

    @Column(name = "sourceBatchNumber")
    private String sourceBatchNumber;

    @Column(name = "sourceQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal sourceQty;

    @Column(name = "sourceUomId")
    private String sourceUomId;

    @Column(name = "outputProductId", nullable = false)
    private String outputProductId;

    @Column(name = "outputBatchNumber")
    private String outputBatchNumber;

    @Column(name = "attributedQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal attributedQty;

    @Column(name = "outputUomId")
    private String outputUomId;

    @Column(name = "allocationMethod", nullable = false)
    private String allocationMethod;

    @Column(name = "sharePct", nullable = false, precision = 9, scale = 4)
    private BigDecimal sharePct;

    @Column(name = "attributedCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal attributedCost;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
