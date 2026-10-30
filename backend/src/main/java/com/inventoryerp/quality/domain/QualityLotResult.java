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
 * Table {@code "QualityLotResult"}: Test result of one parameter and sample of a lot (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityLotResult")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualityLotResult {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "lotId", nullable = false)
    private String lotId;

    @Column(name = "parameterId", nullable = false)
    private String parameterId;

    @Column(name = "sampleNo", nullable = false)
    private Integer sampleNo;

    @Column(name = "numericValue", precision = 18, scale = 6)
    private BigDecimal numericValue;

    @Column(name = "textValue")
    private String textValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sieveResults", nullable = false)
    private String sieveResults;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "labReference")
    private String labReference;

    @Column(name = "testedAt")
    private Instant testedAt;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
