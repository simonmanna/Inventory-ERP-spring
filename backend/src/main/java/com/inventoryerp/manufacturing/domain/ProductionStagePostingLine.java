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
 * Table {@code "ProductionStagePostingLine"}: A line of a stage transaction (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionStagePostingLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionStagePostingLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "postingId", nullable = false)
    private String postingId;

    @Column(name = "stageId", nullable = false)
    private String stageId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "productId")
    private String productId;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unitCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "value", nullable = false, precision = 20, scale = 6)
    private BigDecimal value;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "ledgerCode")
    private String ledgerCode;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "outputId")
    private String outputId;

    @Column(name = "costSource")
    private String costSource;

    @Column(name = "sourceRef")
    private String sourceRef;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
