package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetFuelLog"}: Fuel log of a vehicle: station purchase or site-tank issue, meters, efficiency and
 * postings (fleet 1.5).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFuelLog")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFuelLog {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "fuelledAt", nullable = false)
    private Instant fuelledAt;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "fuelType")
    private String fuelType;

    @Column(name = "litres", nullable = false, precision = 14, scale = 3)
    private BigDecimal litres;

    @Column(name = "unitPrice", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "fullTank", nullable = false)
    private Boolean fullTank;

    @Column(name = "odometer", precision = 14, scale = 1)
    private BigDecimal odometer;

    @Column(name = "hours", precision = 14, scale = 1)
    private BigDecimal hours;

    @Column(name = "supplierId")
    private String supplierId;

    @Column(name = "stationName")
    private String stationName;

    @Column(name = "paymentMethod")
    private String paymentMethod;

    @Column(name = "paymentAccountId")
    private String paymentAccountId;

    @Column(name = "fuelCardId")
    private String fuelCardId;

    @Column(name = "tankId")
    private String tankId;

    @Column(name = "receiptNumber")
    private String receiptNumber;

    @Column(name = "receiptAttachmentId")
    private String receiptAttachmentId;

    @Column(name = "employeeId")
    private String employeeId;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "previousFullLogId")
    private String previousFullLogId;

    @Column(name = "segmentLitres", precision = 14, scale = 3)
    private BigDecimal segmentLitres;

    @Column(name = "distanceKm", precision = 14, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "hoursRun", precision = 14, scale = 1)
    private BigDecimal hoursRun;

    @Column(name = "lPer100Km", precision = 12, scale = 3)
    private BigDecimal lPer100Km;

    @Column(name = "lPerHour", precision = 12, scale = 3)
    private BigDecimal lPerHour;

    @Column(name = "anomalyCount", nullable = false)
    private Integer anomalyCount;

    @Column(name = "odometerReadingId")
    private String odometerReadingId;

    @Column(name = "hoursReadingId")
    private String hoursReadingId;

    @Column(name = "meterNote")
    private String meterNote;

    @Column(name = "expenseId")
    private String expenseId;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "stockUnitCost", precision = 20, scale = 6)
    private BigDecimal stockUnitCost;

    @Column(name = "stockValue", precision = 20, scale = 6)
    private BigDecimal stockValue;

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

    @Column(name = "reversalJournalEntryId")
    private String reversalJournalEntryId;

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
