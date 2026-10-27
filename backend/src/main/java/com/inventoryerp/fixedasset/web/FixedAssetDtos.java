package com.inventoryerp.fixedasset.web;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Request bodies of the fixed-asset routes ({@code fixed-asset/dto/*.ts}). */
final class FixedAssetDtos {

    private FixedAssetDtos() {}

    static final String STR = "must be a string";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String EMPTY = "should not be empty";
    static final String ISO_DATE =
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";

    static final String[] ASSET_STATUSES = {"active", "under_maintenance", "disposed", "lost_stolen", "in_repair",
        "reserved"};
    /** {@code CreateAssetCategoryDto}. */
    public record CreateCategoryRequest(
            @NotNull(message = STR) @NotBlank(message = EMPTY) String name,
            String description,
            String parentId,
            @OneOf({"straight_line", "declining_balance", "double_declining", "units_of_production", "manual"})
            String depreciationMethod,
            BigDecimal defaultUsefulLife,
            BigDecimal defaultResidualValue,
            Boolean isActive) {}

    /** {@code UpdateAssetCategoryDto}. */
    public record UpdateCategoryRequest(
            String name,
            String description,
            String parentId,
            @OneOf({"straight_line", "declining_balance", "double_declining", "units_of_production", "manual"})
            String depreciationMethod,
            BigDecimal defaultUsefulLife,
            BigDecimal defaultResidualValue,
            Boolean isActive) {}

    /** {@code CreateAssetDto}. */
    public record CreateAssetRequest(
            @NotNull(message = STR) @NotBlank(message = EMPTY) String name,
            String assetCode,
            String barcode,
            String description,
            String serialNumber,
            String brand,
            String model,
            String manufacturer,
            String categoryId,
            String department,
            String branchId,
            String location,
            String assignedToId,
            String assignedToType,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String assignmentDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String purchaseDate,
            String supplierId,
            String invoiceNumber,
            BigDecimal purchaseCost,
            BigDecimal currentValue,
            BigDecimal salvageValue,
            BigDecimal usefulLife,
            String usefulLifeUnit,
            @OneOf({"purchased", "donated", "leased", "constructed", "transferred_in", "gifted"})
            String acquisitionMethod,
            BigDecimal capitalizedCost,
            String image,
            String notes,
            @OneOf({"active", "under_maintenance", "disposed", "lost_stolen", "in_repair", "reserved"})
            String status) {}

    /** {@code UpdateAssetDto}. */
    public record UpdateAssetRequest(
            String name,
            String assetCode,
            String barcode,
            String description,
            String serialNumber,
            String brand,
            String model,
            String manufacturer,
            String categoryId,
            String department,
            String branchId,
            String location,
            String assignedToId,
            String assignedToType,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String assignmentDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String purchaseDate,
            String supplierId,
            String invoiceNumber,
            BigDecimal purchaseCost,
            BigDecimal currentValue,
            BigDecimal salvageValue,
            BigDecimal usefulLife,
            String usefulLifeUnit,
            @OneOf({"purchased", "donated", "leased", "constructed", "transferred_in", "gifted"})
            String acquisitionMethod,
            BigDecimal capitalizedCost,
            String image,
            String notes,
            @OneOf({"active", "under_maintenance", "disposed", "lost_stolen", "in_repair", "reserved"})
            String status) {}

    /** {@code CreateAcquisitionDto}. */
    public record AcquisitionRequest(
            String vendor,
            String vendorId,
            String purchaseOrderId,
            String invoiceId,
            BigDecimal taxes,
            BigDecimal freight,
            BigDecimal installationCost,
            BigDecimal otherCosts,
            BigDecimal capitalizedCost,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String acquisitionDate,
            String notes) {}

    /** {@code CreateAssignmentDto}. */
    public record AssignmentRequest(
            String assignedToId,
            @OneOf({"employee", "teacher", "student", "department", "branch", "room", "vehicle", "clinic", "store"})
            String assignedToType,
            String assignedToName,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String assignedDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String returnedDate,
            String conditionBefore,
            String conditionAfter,
            String responsiblePerson,
            String notes) {}

    /** {@code CreateCheckInOutDto}. */
    public record CheckInOutRequest(
            String checkedOutTo,
            String checkedOutToType,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String checkoutDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expectedReturnDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String returnDate,
            String conditionBefore,
            String conditionAfter,
            String notes) {}

    /** {@code CreateDisposalDto}. */
    public record DisposalRequest(
            @NotNull(message = "must be one of the following values: sold, scrapped, donated, lost, stolen, destroyed")
            @OneOf({"sold", "scrapped", "donated", "lost", "stolen", "destroyed"}) String disposalMethod,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String disposalDate,
            BigDecimal disposalValue,
            String approvedBy,
            String reason,
            String notes) {}

    /** {@code CreateInspectionDto}. */
    public record InspectionRequest(
            String inspector,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String inspectionDate,
            String condition,
            Boolean isWorking,
            Boolean isDamaged,
            Boolean isMissing,
            Boolean requiresRepair,
            String notes) {}

    /** {@code CreateInsuranceDto}. */
    public record InsuranceRequest(
            String policyNumber,
            String company,
            BigDecimal premium,
            BigDecimal coverageAmount,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String startDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String renewalDate,
            String contactPerson,
            String contactPhone,
            String notes) {}

    /** {@code CreateMaintenanceDto}. */
    public record MaintenanceRequest(
            @OneOf({"preventive", "corrective", "emergency", "scheduled", "calibration"}) String maintenanceType,
            @NotNull(message = STR) String title,
            String description,
            String technician,
            String vendor,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String scheduledDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String completedDate,
            BigDecimal cost,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String nextMaintenanceDate,
            String status,
            String notes) {}

    /** {@code CreateRepairDto}. */
    public record RepairRequest(
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String breakdownDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String repairDate,
            String description,
            String vendor,
            BigDecimal sparePartsCost,
            BigDecimal laborCost,
            BigDecimal totalCost,
            BigDecimal downtimeHours,
            String vendorInvoiceRef,
            String status,
            String notes) {}

    /** {@code CreateRevaluationDto}. */
    public record RevaluationRequest(
            @NotNull(message = NUM) BigDecimal previousValue,
            @NotNull(message = NUM) BigDecimal newValue,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String revaluationDate,
            String reason,
            String approvedBy,
            String notes) {}

    /** {@code CreateTransferDto}. */
    public record TransferRequest(
            String fromLocation,
            String toLocation,
            String fromBranchId,
            String toBranchId,
            String fromDepartment,
            String toDepartment,
            String fromPersonId,
            String toPersonId,
            String reason,
            String approvedBy,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String transferDate,
            String notes) {}

    /** {@code CreateWarrantyDto}. */
    public record WarrantyRequest(
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String warrantyStart,
            @NotNull(message = DATE_MSG) @Pattern(regexp = ISO_DATE, message = DATE_MSG) String warrantyEnd,
            String vendor,
            String contactPerson,
            String contactPhone,
            String contactEmail,
            String terms,
            String coverage,
            String notes) {}

    /** {@code RunDepreciationDto}. */
    public record RunDepreciationRequest(
            @NotNull(message = STR) String period,
            Boolean postEntries,
            String assetId) {}
}
