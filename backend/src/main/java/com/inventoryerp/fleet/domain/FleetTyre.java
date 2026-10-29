package com.inventoryerp.fleet.domain;

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
 * Table {@code "FleetTyre"}: Tyre tracked by serial number: fitment, stints, tread and cost (fleet 1.7, ADR-S009).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTyre")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTyre {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "serialNumber", nullable = false)
    private String serialNumber;

    @Column(name = "brand")
    private String brand;

    @Column(name = "model")
    private String model;

    @Column(name = "size")
    private String size;

    @Column(name = "tyreType", nullable = false)
    private String tyreType;

    @Column(name = "purchaseCost", precision = 18, scale = 2)
    private BigDecimal purchaseCost;

    @Column(name = "extraCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal extraCost;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "purchasedOn")
    private LocalDate purchasedOn;

    @Column(name = "invoiceReference")
    private String invoiceReference;

    @Column(name = "productId")
    private String productId;

    @Column(name = "stockLocationId")
    private String stockLocationId;

    @Column(name = "stockIssuedAt")
    private Instant stockIssuedAt;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "stockIssueValue", precision = 18, scale = 2)
    private BigDecimal stockIssueValue;

    @Column(name = "issueJournalEntryId")
    private String issueJournalEntryId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "position")
    private String position;

    @Column(name = "fittedAt")
    private Instant fittedAt;

    @Column(name = "fitOdometer", precision = 14, scale = 1)
    private BigDecimal fitOdometer;

    @Column(name = "fitHours", precision = 14, scale = 1)
    private BigDecimal fitHours;

    @Column(name = "kmRun", nullable = false, precision = 14, scale = 1)
    private BigDecimal kmRun;

    @Column(name = "hoursRun", nullable = false, precision = 14, scale = 1)
    private BigDecimal hoursRun;

    @Column(name = "treadKmBase", nullable = false, precision = 14, scale = 1)
    private BigDecimal treadKmBase;

    @Column(name = "newTreadDepthMm", precision = 5, scale = 1)
    private BigDecimal newTreadDepthMm;

    @Column(name = "treadDepthMm", precision = 5, scale = 1)
    private BigDecimal treadDepthMm;

    @Column(name = "treadMeasuredAt")
    private Instant treadMeasuredAt;

    @Column(name = "minTreadDepthMm", nullable = false, precision = 5, scale = 1)
    private BigDecimal minTreadDepthMm;

    @Column(name = "retreadCount", nullable = false)
    private Integer retreadCount;

    @Column(name = "scrappedAt")
    private Instant scrappedAt;

    @Column(name = "scrapReason")
    private String scrapReason;

    @Column(name = "notes")
    private String notes;

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

    public String getId() {
        return id;
    }
}
