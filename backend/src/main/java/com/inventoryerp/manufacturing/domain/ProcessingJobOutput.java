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
 * Table {@code "ProcessingJobOutput"}: Output lot of a processing job, with optional measurements.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingJobOutput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingJobOutput {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "jobId", nullable = false)
    private String jobId;

    @Column(name = "templateOutputId")
    private String templateOutputId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "outputLineId")
    private String outputLineId;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "value", nullable = false, precision = 20, scale = 6)
    private BigDecimal value;

    @Column(name = "unitCost", precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "grade")
    private String grade;

    @Column(name = "basisQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal basisQty;

    @Column(name = "basisSource", nullable = false)
    private String basisSource;

    @Column(name = "basisFactor", precision = 20, scale = 6)
    private BigDecimal basisFactor;

    @Column(name = "marketValue", precision = 20, scale = 6)
    private BigDecimal marketValue;

    @Column(name = "weight", precision = 20, scale = 6)
    private BigDecimal weight;

    @Column(name = "length", precision = 20, scale = 6)
    private BigDecimal length;

    @Column(name = "width", precision = 20, scale = 6)
    private BigDecimal width;

    @Column(name = "thickness", precision = 20, scale = 6)
    private BigDecimal thickness;

    @Column(name = "area", precision = 20, scale = 6)
    private BigDecimal area;

    @Column(name = "volume", precision = 20, scale = 6)
    private BigDecimal volume;

    @Column(name = "density", precision = 20, scale = 6)
    private BigDecimal density;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;
}
