package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "ProcessingTemplateOutput"}: Output of a processing template.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingTemplateOutput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingTemplateOutput {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "templateId", nullable = false)
    private String templateId;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "expectedPct", nullable = false, precision = 7, scale = 4)
    private BigDecimal expectedPct;

    @Column(name = "valuation", nullable = false)
    private String valuation;

    @Column(name = "nrvUnitValue", precision = 20, scale = 6)
    private BigDecimal nrvUnitValue;

    @Column(name = "marketValue", precision = 20, scale = 6)
    private BigDecimal marketValue;

    @Column(name = "basisFactor", precision = 20, scale = 6)
    private BigDecimal basisFactor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requiredMeasures")
    private String requiredMeasures;

    @Column(name = "stocked", nullable = false)
    private Boolean stocked;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
