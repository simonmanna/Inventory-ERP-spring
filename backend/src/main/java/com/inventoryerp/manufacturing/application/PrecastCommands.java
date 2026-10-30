package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request bodies of the block / precast routes (ADR-S014, Phase 2.4); they double as service inputs. */
public final class PrecastCommands {

    private PrecastCommands() {}

    static final String POS = "must be a positive number";
    static final String MIN0 = "must not be less than 0";

    // ------------------------------------------------------------------ set-up

    /** {@code PUT /manufacturing/precast/product-specs/{productId}}: upsert. */
    public record ProductSpecInput(
            @OneOf({"hollow", "solid", "paver", "kerb", "lintel", "slab", "other"}) String blockType,
            @Positive(message = "lengthMm " + POS) BigDecimal lengthMm,
            @Positive(message = "widthMm " + POS) BigDecimal widthMm,
            @Positive(message = "heightMm " + POS) BigDecimal heightMm,
            @Size(max = 40) String strengthClass,
            @DecimalMin(value = "0", message = "strengthMpa " + MIN0) BigDecimal strengthMpa,
            @DecimalMin(value = "0", message = "weightKg " + MIN0) BigDecimal weightKg,
            @Min(value = 1, message = "unitsPerPallet must not be less than 1") Integer unitsPerPallet,
            @Min(value = 1, message = "unitsPerCycle must not be less than 1") Integer unitsPerCycle,
            String secondGradeProductId,
            @Size(max = 1000) String notes,
            Boolean isActive) {}

    /** Curing chamber / yard / shed. */
    public record CuringAreaInput(
            @NotBlank(message = "code should not be empty") @Size(max = 40) String code,
            @NotBlank(message = "name should not be empty") @Size(max = 120) String name,
            @OneOf({"chamber", "yard", "shed"}) String kind,
            @Min(value = 1, message = "capacityPallets must not be less than 1") Integer capacityPallets,
            String locationId,
            BigDecimal targetTemperatureC,
            @Size(max = 1000) String notes,
            Boolean isActive) {}

    /** {@code PATCH /manufacturing/precast/curing-areas/{id}}: absent fields are kept. */
    public record UpdateCuringArea(
            @Size(min = 1, max = 120) String name,
            @OneOf({"chamber", "yard", "shed"}) String kind,
            @Min(value = 1, message = "capacityPallets must not be less than 1") Integer capacityPallets,
            String locationId,
            BigDecimal targetTemperatureC,
            @Size(max = 1000) String notes,
            Boolean isActive) {}

    // ------------------------------------------------------------------ mix designs

    /** A material of the mix per batch. */
    public record MixLine(
            @NotBlank(message = "productId should not be empty") String productId,
            @NotNull(message = "qtyPerBatch " + POS) @Positive(message = "qtyPerBatch " + POS) BigDecimal qtyPerBatch,
            String uomId,
            @DecimalMin(value = "0", message = "scrapPct " + MIN0) BigDecimal scrapPct,
            @Size(max = 500) String notes) {}

    /**
     * {@code POST /manufacturing/precast/mix-designs}: a draft BOM version (output = {@code unitsPerBatch} blocks, lines
     * = materials per batch) plus its batch parameters. Without {@code routingId} a two-stage routing (batching &amp;
     * pressing → curing) is created; with one, its last operation is the curing stage and the one before it pressing.
     */
    public record CreateMixDesign(
            @NotBlank(message = "name should not be empty") @Size(max = 200) String name,
            @NotBlank(message = "productId should not be empty") String productId,
            @NotNull(message = "unitsPerBatch " + POS) @Positive(message = "unitsPerBatch " + POS) BigDecimal unitsPerBatch,
            @NotNull(message = "lines must be an array") @NotEmpty(message = "lines should not be empty")
            List<@Valid @NotNull MixLine> lines,
            String cementProductId,
            @Positive(message = "cementPerBag " + POS) BigDecimal cementPerBag,
            @Positive(message = "batchVolumeM3 " + POS) BigDecimal batchVolumeM3,
            @DecimalMin(value = "0", message = "waterCementRatio " + MIN0) BigDecimal waterCementRatio,
            @Min(value = 0, message = "minCuringDays " + MIN0)
            @Max(value = 365, message = "minCuringDays must not be greater than 365") Integer minCuringDays,
            @Size(max = 60) String curingCheckpointCode,
            String defaultCuringAreaId,
            @DecimalMin(value = "0", message = "targetMaturity " + MIN0) BigDecimal targetMaturity,
            BigDecimal datumTemperatureC,
            @DecimalMin(value = "0.0001", message = "expectedYieldPct must be greater than 0")
            @DecimalMax(value = "100", message = "expectedYieldPct must be between 0 and 100") BigDecimal expectedYieldPct,
            String routingId,
            String pressWorkCenterId,
            String defaultLocationId,
            String defaultOutputLocationId,
            Boolean isDefault,
            @Size(max = 2000) String notes) {}

    /** {@code PATCH /manufacturing/precast/mix-designs/{id}} (draft versions only; absent fields are kept). */
    public record UpdateMixDesign(
            @Size(min = 1, max = 200) String name,
            @Positive(message = "unitsPerBatch " + POS) BigDecimal unitsPerBatch,
            List<@Valid @NotNull MixLine> lines,
            String cementProductId,
            @Positive(message = "cementPerBag " + POS) BigDecimal cementPerBag,
            @Positive(message = "batchVolumeM3 " + POS) BigDecimal batchVolumeM3,
            @DecimalMin(value = "0", message = "waterCementRatio " + MIN0) BigDecimal waterCementRatio,
            @Min(value = 0, message = "minCuringDays " + MIN0)
            @Max(value = 365, message = "minCuringDays must not be greater than 365") Integer minCuringDays,
            @Size(max = 60) String curingCheckpointCode,
            String defaultCuringAreaId,
            @DecimalMin(value = "0", message = "targetMaturity " + MIN0) BigDecimal targetMaturity,
            BigDecimal datumTemperatureC,
            @DecimalMin(value = "0.0001", message = "expectedYieldPct must be greater than 0")
            @DecimalMax(value = "100", message = "expectedYieldPct must be between 0 and 100") BigDecimal expectedYieldPct,
            @Size(max = 2000) String notes) {}

    /** {@code POST /manufacturing/precast/mix-designs/{id}/revise}. */
    public record ReviseMixDesign(@Size(max = 200) String title, @Size(max = 1000) String reason) {}

    // ------------------------------------------------------------------ orders and runs

    /** {@code POST /manufacturing/precast/orders}: a staged production order sized by the batch scaler. */
    public record CreatePrecastOrder(
            @NotBlank(message = "mixDesignId should not be empty") String mixDesignId,
            @NotNull(message = "basis must be one of the following values: units, batches, cement_bags, volume_m3,"
                    + " cycles, pallets")
            @OneOf({"units", "batches", "cement_bags", "volume_m3", "cycles", "pallets"}) String basis,
            @NotNull(message = "value " + POS) @Positive(message = "value " + POS) BigDecimal value,
            Boolean wholeBatches,
            String locationId,
            String outputLocationId,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String scheduledFor,
            @Size(max = 80) String batchNumber,
            Boolean confirm,
            @Size(max = 2000) String notes) {}

    /** {@code POST /manufacturing/precast/orders/{id}/pressing-runs}: blocks off the press into curing. */
    public record PressingRun(
            @NotNull(message = "qtyGood " + MIN0) @DecimalMin(value = "0", message = "qtyGood " + MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = "qtyBroken " + MIN0) BigDecimal qtyBroken,
            String scrapReasonCode,
            @Min(value = 0, message = "cycles " + MIN0) Integer cycles,
            @Min(value = 0, message = "pallets " + MIN0) Integer pallets,
            String curingAreaId,
            @Size(max = 80) String palletLocation,
            @Size(max = 40) String shift,
            @Size(max = 80) String batchNumber,
            Boolean lastRun,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            @Size(max = 1000) String notes) {}

    /** {@code POST /manufacturing/precast/curing-batches/{id}/readings}. */
    public record CuringReading(
            @NotNull(message = "temperatureC must be a number") BigDecimal temperatureC,
            @DecimalMin(value = "0", message = "humidityPct " + MIN0)
            @DecimalMax(value = "100", message = "humidityPct must not be greater than 100") BigDecimal humidityPct,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String readAt,
            @Size(max = 500) String notes) {}

    /** {@code POST /manufacturing/precast/curing-batches/{id}/move}: pallets to another area / bay. */
    public record MovePallets(
            String curingAreaId,
            @Size(max = 80) String palletLocation,
            @Size(max = 500) String notes) {}

    /** {@code POST /manufacturing/precast/curing-batches/{id}/cube}: count the cured batch off the boards. */
    public record CubeBatch(
            @NotNull(message = "qtyGood " + MIN0) @DecimalMin(value = "0", message = "qtyGood " + MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = "qtySecondGrade " + MIN0) BigDecimal qtySecondGrade,
            @DecimalMin(value = "0", message = "qtyBroken " + MIN0) BigDecimal qtyBroken,
            String scrapReasonCode,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            @Size(max = 1000) String notes) {}

    /** {@code POST /manufacturing/precast/curing-batches/{id}/gate-decision}: manual strength gate. */
    public record GateDecision(
            @NotNull(message = "result must be one of the following values: passed, failed")
            @OneOf({"passed", "failed"}) String result,
            @Size(max = 120) String reference,
            @Size(max = 1000) String notes) {}
}
