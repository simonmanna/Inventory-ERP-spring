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
 * Table {@code "PrecastCuringBatch"}: Blocks of one pressing run in curing (pallet group) (ADR-S014, Phase 2.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "PrecastCuringBatch")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PrecastCuringBatch {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "orderId", nullable = false)
    private String orderId;

    @Column(name = "stageId", nullable = false)
    private String stageId;

    @Column(name = "pressingPostingId", nullable = false)
    private String pressingPostingId;

    @Column(name = "cubingPostingId")
    private String cubingPostingId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "batchNumber", nullable = false)
    private String batchNumber;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "brokenAtPress", nullable = false, precision = 20, scale = 6)
    private BigDecimal brokenAtPress;

    @Column(name = "cycles")
    private Integer cycles;

    @Column(name = "pallets", nullable = false)
    private Integer pallets;

    @Column(name = "curingAreaId")
    private String curingAreaId;

    @Column(name = "palletLocation")
    private String palletLocation;

    @Column(name = "shift")
    private String shift;

    @Column(name = "startedAt", nullable = false)
    private Instant startedAt;

    @Column(name = "minCuringDays", nullable = false)
    private Integer minCuringDays;

    @Column(name = "curingUntil", nullable = false)
    private Instant curingUntil;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "maturity", nullable = false, precision = 20, scale = 2)
    private BigDecimal maturity;

    @Column(name = "lastTemperatureC", precision = 8, scale = 2)
    private BigDecimal lastTemperatureC;

    @Column(name = "lastReadingAt")
    private Instant lastReadingAt;

    @Column(name = "qtyGood", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyGood;

    @Column(name = "qtySecondGrade", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtySecondGrade;

    @Column(name = "qtyBroken", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyBroken;

    @Column(name = "scrapReasonCode")
    private String scrapReasonCode;

    @Column(name = "cubedAt")
    private Instant cubedAt;

    @Column(name = "releasedAt")
    private Instant releasedAt;

    @Column(name = "finalLotId")
    private String finalLotId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
