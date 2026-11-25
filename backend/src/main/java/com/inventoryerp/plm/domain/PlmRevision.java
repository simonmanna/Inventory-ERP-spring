package com.inventoryerp.plm.domain;

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
 * Table {@code "PlmRevision"}: one revision of a product under PLM (ADR-S086) with a snapshot of what it was — BOM,
 * routing, released documents, product data and unit cost.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmRevision")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmRevision {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "itemId", nullable = false)
    private String itemId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "revision", nullable = false)
    private String revision;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "effectiveFrom", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effectiveTo")
    private Instant effectiveTo;

    @Column(name = "changeId")
    private String changeId;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "bomVersion")
    private Integer bomVersion;

    @Column(name = "routingId")
    private String routingId;

    @Column(name = "unitCost", precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "costSource")
    private String costSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false)
    private String snapshot;

    @Column(name = "note")
    private String note;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
