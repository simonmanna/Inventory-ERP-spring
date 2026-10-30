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
 * Table {@code "BomCoProduct"}: Co-product, by-product or waste output of a BOM (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "BomCoProduct")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class BomCoProduct {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "bomId", nullable = false)
    private String bomId;

    @Column(name = "routingOperationId")
    private String routingOperationId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "valuation", nullable = false)
    private String valuation;

    @Column(name = "nrvUnitValue", precision = 20, scale = 6)
    private BigDecimal nrvUnitValue;

    @Column(name = "marketValue", precision = 20, scale = 6)
    private BigDecimal marketValue;

    @Column(name = "weightPerUnit", precision = 20, scale = 6)
    private BigDecimal weightPerUnit;

    @Column(name = "fixedPct", precision = 20, scale = 6)
    private BigDecimal fixedPct;

    @Column(name = "stocked", nullable = false)
    private Boolean stocked;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
