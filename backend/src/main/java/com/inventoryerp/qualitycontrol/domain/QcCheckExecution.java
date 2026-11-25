package com.inventoryerp.qualitycontrol.domain;

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
 * Table {@code "QcCheckExecution"}: Immutable execution attempt (revision) of a quality check (ADR-S087 §2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcCheckExecution")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcCheckExecution {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "checkId", nullable = false)
    private String checkId;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "measureValue", precision = 20, scale = 6)
    private BigDecimal measureValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "worksheetAnswers")
    private String worksheetAnswers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation", nullable = false)
    private String evaluation;

    @Column(name = "failureClassId")
    private String failureClassId;

    @Column(name = "failedQty", precision = 20, scale = 6)
    private BigDecimal failedQty;

    @Column(name = "pictureDocumentId")
    private String pictureDocumentId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "executedBy")
    private String executedBy;

    @Column(name = "executedAt", nullable = false)
    private Instant executedAt;

    public String getId() {
        return id;
    }
}
