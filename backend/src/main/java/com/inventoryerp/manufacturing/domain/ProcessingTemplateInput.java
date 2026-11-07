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
 * Table {@code "ProcessingTemplateInput"}: Input of a processing template.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProcessingTemplateInput")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProcessingTemplateInput {

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

    @Column(name = "genealogyBasis", nullable = false)
    private String genealogyBasis;

    @Column(name = "fixedPct", precision = 7, scale = 4)
    private BigDecimal fixedPct;

    @Column(name = "basisFactor", precision = 20, scale = 6)
    private BigDecimal basisFactor;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;
}
