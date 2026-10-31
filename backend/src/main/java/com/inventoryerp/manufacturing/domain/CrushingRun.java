package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "CrushingRun"}: A crushing run by shift; posted as a multi-stage production order (ADR-S016).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "CrushingRun")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class CrushingRun {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "plantId", nullable = false)
    private String plantId;

    @Column(name = "profileId", nullable = false)
    private String profileId;

    @Column(name = "runDate", nullable = false)
    private LocalDate runDate;

    @Column(name = "shift", nullable = false)
    private String shift;

    @Column(name = "operatorName")
    private String operatorName;

    @Column(name = "feedStockpileId", nullable = false)
    private String feedStockpileId;

    @Column(name = "feedProductId", nullable = false)
    private String feedProductId;

    @Column(name = "feedTonnes", nullable = false, precision = 18, scale = 6)
    private BigDecimal feedTonnes;

    @Column(name = "feedMethod", nullable = false)
    private String feedMethod;

    @Column(name = "outputMethod", nullable = false)
    private String outputMethod;

    @Column(name = "recirculatedTonnes", nullable = false, precision = 18, scale = 6)
    private BigDecimal recirculatedTonnes;

    @Column(name = "operatingHours", precision = 18, scale = 6)
    private BigDecimal operatingHours;

    @Column(name = "downtimeHours", nullable = false, precision = 18, scale = 6)
    private BigDecimal downtimeHours;

    @Column(name = "downtimeReason")
    private String downtimeReason;

    @Column(name = "labourHours", nullable = false, precision = 18, scale = 6)
    private BigDecimal labourHours;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "productionOrderId")
    private String productionOrderId;

    @Column(name = "outputTonnes", precision = 18, scale = 6)
    private BigDecimal outputTonnes;

    @Column(name = "lossTonnes", precision = 18, scale = 6)
    private BigDecimal lossTonnes;

    @Column(name = "actualYieldPct", precision = 18, scale = 6)
    private BigDecimal actualYieldPct;

    @Column(name = "expectedYieldPct", precision = 18, scale = 6)
    private BigDecimal expectedYieldPct;

    @Column(name = "feedCost", precision = 18, scale = 6)
    private BigDecimal feedCost;

    @Column(name = "conversionCost", precision = 18, scale = 6)
    private BigDecimal conversionCost;

    @Column(name = "abnormalLossValue", precision = 18, scale = 6)
    private BigDecimal abnormalLossValue;

    @Column(name = "totalCost", precision = 18, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "costPerTonne", precision = 18, scale = 6)
    private BigDecimal costPerTonne;

    @Column(name = "notes")
    private String notes;

    @Column(name = "postedAt")
    private Instant postedAt;

    @Column(name = "postedBy")
    private String postedBy;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reversalReason")
    private String reversalReason;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "deletedAt")
    private Instant deletedAt;
}
