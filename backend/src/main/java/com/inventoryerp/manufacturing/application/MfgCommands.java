package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the manufacturing routes, mirroring the class-validator rules of {@code dto/bom.dto.ts},
 * {@code dto/production.dto.ts}, {@code dto/request.dto.ts} and {@code dto/phase4.dto.ts}. They double as the
 * service inputs (the services are called with exactly what Nest's services receive).
 */
public final class MfgCommands {

    private MfgCommands() {}

    static final String STR = "must be a string";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String POS = "must be a positive number";
    static final String MIN0 = "must not be less than 0";
    static final String INT = "must be an integer number";
    static final String EMPTY = "should not be empty";
    static final String ARRAY = "must be an array";
    static final String ISO_DATE =
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
    static final String DATE_MSG = "must be a valid ISO 8601 date string";

    // ------------------------------------------------------------------ BOM

    /** {@code BomLineInputDto}: {@code quantity} is per ONE run of the BOM. */
    public record BomLineInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String componentProductId,
            String componentVariantId,
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal quantity,
            String uomId,
            @DecimalMin(value = "0", message = MIN0) BigDecimal scrapPct,
            Integer sequence,
            Boolean isOptional,
            String notes) {}

    /** {@code CreateBomDto}. */
    public record CreateBom(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String name,
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String outputProductId,
            String outputVariantId,
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal outputQuantity,
            String outputUomId,
            @DecimalMin(value = "0", message = MIN0) BigDecimal expectedYieldPct,
            @DecimalMin(value = "0", message = MIN0) BigDecimal overheadCost,
            String defaultLocationId,
            String defaultOutputLocationId,
            @Min(value = 0, message = MIN0) Integer shelfLifeDays,
            @Min(value = 0, message = MIN0) Integer estimatedDurationMins,
            Boolean qcRequired,
            Boolean isDefault,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String effectiveFrom,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String effectiveTo,
            String notes,
            @NotNull(message = ARRAY) @ArrayMinSize(1) List<@Valid @NotNull BomLineInput> lines) {}

    /** {@code UpdateBomDto}: a DRAFT edit; {@code lines} (when present) replaces the line set. */
    public record UpdateBom(
            @Size(min = 1, message = EMPTY) String name,
            @Positive(message = POS) BigDecimal outputQuantity,
            String outputUomId,
            @DecimalMin(value = "0", message = MIN0) BigDecimal expectedYieldPct,
            String defaultLocationId,
            String defaultOutputLocationId,
            @Min(value = 0, message = MIN0) Integer shelfLifeDays,
            @Min(value = 0, message = MIN0) Integer estimatedDurationMins,
            Boolean qcRequired,
            Boolean isDefault,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String effectiveFrom,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String effectiveTo,
            String notes,
            @Size(min = 1, message = "must contain at least 1 elements")
            List<@Valid @NotNull BomLineInput> lines) {}

    // ------------------------------------------------------------------ production orders

    /** {@code ProductionMaterialInputDto} (ad-hoc material line). */
    public record MaterialInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String productId,
            String variantId,
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal quantity,
            String uomId,
            String batchNumber,
            @OneOf({"FIFO", "FEFO", "MANUAL", "SERIAL"}) String distStrategy) {}

    /** {@code ProductionOutputInputDto} (extra / byproduct output line). */
    public record OutputInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String productId,
            String variantId,
            @OneOf({"output", "byproduct"}) String kind,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) BigDecimal qtyExpected,
            String uomId,
            @DecimalMin(value = "0", message = MIN0) BigDecimal costAllocationPct) {}

    /** {@code CreateProductionOrderDto}. */
    public record CreateOrder(
            String bomId,
            String outputProductId,
            String outputVariantId,
            String locationId,
            String outputLocationId,
            @Positive(message = POS) BigDecimal runs,
            @Positive(message = POS) BigDecimal plannedQty,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String scheduledFor,
            String batchNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expiryDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String mfgDate,
            String notes,
            List<@Valid @NotNull MaterialInput> materials,
            List<@Valid @NotNull OutputInput> outputs) {

        /** The order a confirmed plan line generates. */
        static CreateOrder fromPlan(String bomId, String productId, String variantId, BigDecimal qty,
                String locationId, String scheduledFor, String notes) {
            return new CreateOrder(bomId, productId, variantId, locationId, null, null, qty, scheduledFor, null, null,
                    null, notes, null, null);
        }
    }

    /** {@code StartMaterialInputDto}: per-material actual consumption override. */
    public record StartMaterialInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String lineId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) BigDecimal qtyConsumed,
            String batchNumber) {}

    /** {@code StartProductionDto}. */
    public record StartOrder(
            List<@Valid @NotNull StartMaterialInput> materials,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String date,
            String notes) {}

    /** {@code CompleteOutputInputDto}: per-output actual produced override. */
    public record CompleteOutputInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String lineId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) BigDecimal qtyProduced,
            String batchNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expiryDate) {}

    /**
     * {@code CompleteProductionDto}. {@code qtyProduced} is {@code @IsPositive} in Nest, so the zero-yield branch is
     * reachable only through the service API (as in Nest).
     */
    public record CompleteOrder(
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal qtyProduced,
            String uomId,
            List<@Valid @NotNull CompleteOutputInput> outputs,
            @DecimalMin(value = "0", message = MIN0) BigDecimal scrapQty,
            String batchNumber,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String expiryDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String mfgDate,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String date,
            String notes) {

        public static CompleteOrder of(BigDecimal qtyProduced) {
            return new CompleteOrder(qtyProduced, null, null, null, null, null, null, null, null);
        }
    }

    /** {@code CancelProductionDto} (also the reverse body). */
    public record CancelOrder(String reason) {}

    /** {@code RecordQcDto}. */
    public record RecordQc(
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) BigDecimal passedQty,
            @DecimalMin(value = "0", message = MIN0) BigDecimal failedQty,
            @DecimalMin(value = "0", message = MIN0) BigDecimal reworkQty,
            String wasteCategory,
            List<Object> checklist,
            String notes) {}

    // ------------------------------------------------------------------ requests / plans

    /** {@code ProductionRequestLineInputDto}. */
    public record RequestLineInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String productId,
            String variantId,
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal quantity,
            String uomId,
            String bomId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String neededBy,
            String notes) {}

    /** {@code CreateProductionRequestDto}. */
    public record CreateRequest(
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String neededBy,
            String notes,
            @NotNull(message = ARRAY) @ArrayMinSize(1) List<@Valid @NotNull RequestLineInput> lines) {}

    /** {@code RejectProductionRequestDto}. */
    public record RejectRequest(String reason) {}

    /** {@code CreateProductionPlanDto}; {@code fromOpenRequests} has no validator in Nest (JS truthiness). */
    public record CreatePlan(
            String name,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dateFrom,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String dateTo,
            String locationId,
            String notes,
            Object fromOpenRequests) {}

    /** {@code ProductionPlanLineInputDto}. */
    public record PlanLineInput(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String productId,
            String variantId,
            String bomId,
            @NotNull(message = NUM) @Positive(message = POS) BigDecimal targetQty,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String scheduledDate,
            String notes) {}

    /** {@code AddPlanLinesDto}. */
    public record AddPlanLines(
            @NotNull(message = ARRAY) @ArrayMinSize(1) List<@Valid @NotNull PlanLineInput> lines) {}

    // ------------------------------------------------------------------ phase 4 masters / work orders

    /** {@code CreateWorkCenterDto}. */
    public record CreateWorkCenter(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String code,
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String name,
            String description,
            Boolean isActive) {}

    /** {@code CreateResourceDto}. */
    public record CreateResource(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String name,
            String code,
            @NotNull(message = "must be one of the following values: machine, labour, space")
            @OneOf({"machine", "labour", "space"}) String kind,
            String workCenterId,
            String fixedAssetId,
            String userId,
            @DecimalMin(value = "0", message = MIN0) BigDecimal costPerHour,
            @Min(value = 0, message = MIN0) Integer capacityMinsPerDay,
            Boolean isActive) {}

    /** {@code RoutingOperationInputDto}. */
    public record RoutingOperationInput(
            @NotNull(message = INT) @Min(value = 0, message = MIN0) Integer sequence,
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String name,
            String workCenterId,
            @Min(value = 0, message = MIN0) Integer durationMins,
            @Min(value = 0, message = MIN0) Integer setupMins,
            String instructions) {}

    /** {@code CreateRoutingDto}. */
    public record CreateRouting(
            @NotNull(message = STR) @Size(min = 1, message = EMPTY) String name,
            String productId,
            String bomId,
            @NotNull(message = ARRAY) @ArrayMinSize(1) List<@Valid @NotNull RoutingOperationInput> operations) {}

    /** {@code AssignWorkOrderDto}. */
    public record AssignWorkOrder(String resourceId, String assignedToId, String workCenterId) {}

    /** {@code CompleteWorkOrderDto}. */
    public record CompleteWorkOrder(
            @DecimalMin(value = "0", message = MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = MIN0) BigDecimal qtyScrap,
            @Min(value = 0, message = MIN0) Integer labourMins,
            String notes) {}
}
