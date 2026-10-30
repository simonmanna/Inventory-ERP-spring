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
 * Table {@code "ProductionStagePosting"}: One stage transaction (issue, output, conversion or QC release) (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionStagePosting")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionStagePosting {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "stageId", nullable = false)
    private String stageId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "postedAt", nullable = false)
    private Instant postedAt;

    @Column(name = "qtyGood", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyGood;

    @Column(name = "qtyScrap", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyScrap;

    @Column(name = "qtyAbnormalScrap", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyAbnormalScrap;

    @Column(name = "completesStage", nullable = false)
    private Boolean completesStage;

    @Column(name = "allocationMethod")
    private String allocationMethod;

    @Column(name = "scrapReasonCode")
    private String scrapReasonCode;

    @Column(name = "notes")
    private String notes;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversalReason")
    private String reversalReason;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
