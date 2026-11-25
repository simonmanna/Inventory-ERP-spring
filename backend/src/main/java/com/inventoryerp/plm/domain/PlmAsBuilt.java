package com.inventoryerp.plm.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmAsBuilt"}: the product revision a completed production order produced (ADR-S086), matched by
 * the BOM the order used ({@code bom}), else by the revision current when the order was created ({@code date}).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmAsBuilt")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmAsBuilt {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "orderCode", nullable = false)
    private String orderCode;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "revisionId")
    private String revisionId;

    @Column(name = "revision")
    private String revision;

    @Column(name = "matchedBy", nullable = false)
    private String matchedBy;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "bomVersion")
    private Integer bomVersion;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
