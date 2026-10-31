package com.inventoryerp.quarry.domain;

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
 * Table {@code "QuarryExtractionLog"}: Daily extraction log (shift) posted into the ROM stockpile with its cost (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExtractionLog")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExtractionLog {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "siteId", nullable = false)
    private String siteId;

    @Column(name = "zoneId")
    private String zoneId;

    @Column(name = "materialId", nullable = false)
    private String materialId;

    @Column(name = "stockpileId", nullable = false)
    private String stockpileId;

    @Column(name = "blastId")
    private String blastId;

    @Column(name = "extractedOn", nullable = false)
    private LocalDate extractedOn;

    @Column(name = "shift", nullable = false)
    private String shift;

    @Column(name = "hrShiftId")
    private String hrShiftId;

    @Column(name = "supervisorEmployeeId")
    private String supervisorEmployeeId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "grossTonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal grossTonnes;

    @Column(name = "lossTonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal lossTonnes;

    @Column(name = "netTonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal netTonnes;

    @Column(name = "otherCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherCost;

    @Column(name = "otherCostNote")
    private String otherCostNote;

    @Column(name = "allocationBasis")
    private String allocationBasis;

    @Column(name = "fuelCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "labourCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal labourCost;

    @Column(name = "equipmentCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal equipmentCost;

    @Column(name = "blastingCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal blastingCost;

    @Column(name = "royaltyCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal royaltyCost;

    @Column(name = "totalCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "capitalisedCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal capitalisedCost;

    @Column(name = "lossCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal lossCost;

    @Column(name = "unitCost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "reversalEntryId")
    private String reversalEntryId;

    @Column(name = "postedBy")
    private String postedBy;

    @Column(name = "postedAt")
    private Instant postedAt;

    @Column(name = "reversedBy")
    private String reversedBy;

    @Column(name = "reversedAt")
    private Instant reversedAt;

    @Column(name = "reversalReason")
    private String reversalReason;

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
