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
 * Table {@code "ProductionOrderStage"}: One stage of a multi-stage production order (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionOrderStage")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionOrderStage {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "routingOperationId")
    private String routingOperationId;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "issueMode", nullable = false)
    private String issueMode;

    @Column(name = "outputProductId")
    private String outputProductId;

    @Column(name = "stageLocationId")
    private String stageLocationId;

    @Column(name = "plannedOutputQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal plannedOutputQty;

    @Column(name = "expectedYieldPct", nullable = false, precision = 20, scale = 6)
    private BigDecimal expectedYieldPct;

    @Column(name = "isLast", nullable = false)
    private Boolean isLast;

    @Column(name = "qcGate", nullable = false)
    private Boolean qcGate;

    @Column(name = "qcCheckpointCode")
    private String qcCheckpointCode;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "qcStatus", nullable = false)
    private String qcStatus;

    @Column(name = "qcReference")
    private String qcReference;

    @Column(name = "qcNotes")
    private String qcNotes;

    @Column(name = "qcDecidedAt")
    private Instant qcDecidedAt;

    @Column(name = "qcDecidedBy")
    private String qcDecidedBy;

    @Column(name = "qtyGood", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyGood;

    @Column(name = "qtyScrap", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyScrap;

    @Column(name = "qtyProcessed", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyProcessed;

    @Column(name = "qtyQuarantined", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyQuarantined;

    @Column(name = "materialCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal materialCost;

    @Column(name = "transferredInCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal transferredInCost;

    @Column(name = "conversionCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal conversionCost;

    @Column(name = "outputCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal outputCost;

    @Column(name = "scrapCost", nullable = false, precision = 20, scale = 6)
    private BigDecimal scrapCost;

    @Column(name = "startedAt")
    private Instant startedAt;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
