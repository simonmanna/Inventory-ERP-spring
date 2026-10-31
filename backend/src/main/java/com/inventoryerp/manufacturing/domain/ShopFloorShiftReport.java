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
 * Table {@code "ShopFloorShiftReport"} (ADR-S015):
 * Shift report of a work centre with supervisor approval and OEE snapshot.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorShiftReport")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorShiftReport {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "shiftId", nullable = false)
    private String shiftId;

    @Column(name = "shiftDate", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "startsAt", nullable = false)
    private Instant startsAt;

    @Column(name = "endsAt", nullable = false)
    private Instant endsAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "submittedAt")
    private Instant submittedAt;

    @Column(name = "submittedBy")
    private String submittedBy;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "approvedBy")
    private String approvedBy;

    @Column(name = "approvalNotes")
    private String approvalNotes;

    @Column(name = "rejectedAt")
    private Instant rejectedAt;

    @Column(name = "rejectedBy")
    private String rejectedBy;

    @Column(name = "rejectionReason")
    private String rejectionReason;

    @Column(name = "plannedMinutes")
    private Integer plannedMinutes;

    @Column(name = "plannedDowntimeMinutes")
    private Integer plannedDowntimeMinutes;

    @Column(name = "downtimeMinutes")
    private Integer downtimeMinutes;

    @Column(name = "runMinutes")
    private Integer runMinutes;

    @Column(name = "goodQty", precision = 20, scale = 6)
    private BigDecimal goodQty;

    @Column(name = "scrapQty", precision = 20, scale = 6)
    private BigDecimal scrapQty;

    @Column(name = "availability", precision = 20, scale = 6)
    private BigDecimal availability;

    @Column(name = "performance", precision = 20, scale = 6)
    private BigDecimal performance;

    @Column(name = "quality", precision = 20, scale = 6)
    private BigDecimal quality;

    @Column(name = "oee", precision = 20, scale = 6)
    private BigDecimal oee;

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
