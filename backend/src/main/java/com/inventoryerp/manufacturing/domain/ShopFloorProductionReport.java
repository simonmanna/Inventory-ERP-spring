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
 * Table {@code "ShopFloorProductionReport"} (ADR-S015):
 * Output / scrap report of the shop floor (operator or machine draft).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorProductionReport")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorProductionReport {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "productionOrderId")
    private String productionOrderId;

    @Column(name = "stageId")
    private String stageId;

    @Column(name = "shiftReportId")
    private String shiftReportId;

    @Column(name = "qtyGood", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyGood;

    @Column(name = "qtyScrap", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyScrap;

    @Column(name = "scrapReasonCode")
    private String scrapReasonCode;

    @Column(name = "completesStage", nullable = false)
    private Boolean completesStage;

    @Column(name = "batchNumber")
    private String batchNumber;

    @Column(name = "periodStart")
    private Instant periodStart;

    @Column(name = "periodEnd")
    private Instant periodEnd;

    @Column(name = "reportedAt", nullable = false)
    private Instant reportedAt;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "stagePostingId")
    private String stagePostingId;

    @Column(name = "machineEventId")
    private String machineEventId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "confirmedBy")
    private String confirmedBy;

    @Column(name = "confirmedAt")
    private Instant confirmedAt;

    @Column(name = "rejectedReason")
    private String rejectedReason;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
