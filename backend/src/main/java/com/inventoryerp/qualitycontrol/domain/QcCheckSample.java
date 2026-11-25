package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcCheckSample"}: Physical sample tested in a check execution (ADR-S087 §4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcCheckSample")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcCheckSample {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "checkId", nullable = false)
    private String checkId;

    @Column(name = "executionId", nullable = false)
    private String executionId;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    @Column(name = "sampleNo", nullable = false)
    private Integer sampleNo;

    @Column(name = "sampleRef")
    private String sampleRef;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "sampleQty", precision = 20, scale = 6)
    private BigDecimal sampleQty;

    @Column(name = "measureValue", precision = 20, scale = 6)
    private BigDecimal measureValue;

    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "notes")
    private String notes;

    @Column(name = "sampledBy")
    private String sampledBy;

    @Column(name = "sampledAt", nullable = false)
    private Instant sampledAt;

    public String getId() {
        return id;
    }
}
