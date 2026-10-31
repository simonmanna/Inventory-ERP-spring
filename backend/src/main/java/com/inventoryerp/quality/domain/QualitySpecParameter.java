package com.inventoryerp.quality.domain;

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
 * Table {@code "QualitySpecParameter"}: Test parameter of a specification version: numeric limits, sieve envelope, boolean / text (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualitySpecParameter")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualitySpecParameter {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "specificationId", nullable = false)
    private String specificationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "unit")
    private String unit;

    @Column(name = "testMethod")
    private String testMethod;

    @Column(name = "minValue", precision = 18, scale = 6)
    private BigDecimal minValue;

    @Column(name = "maxValue", precision = 18, scale = 6)
    private BigDecimal maxValue;

    @Column(name = "targetValue", precision = 18, scale = 6)
    private BigDecimal targetValue;

    @Column(name = "decimals", nullable = false)
    private Integer decimals;

    @Column(name = "expectedText")
    private String expectedText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sieveEnvelope", nullable = false)
    private String sieveEnvelope;

    @Column(name = "isCritical", nullable = false)
    private Boolean isCritical;

    @Column(name = "sortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
