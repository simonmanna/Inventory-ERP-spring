package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request bodies of the crushing, screening &amp; aggregate-processing routes (Track C 2.3, ADR-S016); they double as
 * the service inputs. {@code PUT} bodies replace every editable field (whole form), like the quarry master data.
 */
public final class CrushingCommands {

    private CrushingCommands() {}

    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String DATE_MSG = "must be a date (YYYY-MM-DD)";

    // ------------------------------------------------------------------ plant & circuit

    /** One equipment unit (crusher, screen, conveyor ...) of a circuit stage; matched by {@code code} on save. */
    public record UnitInput(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"feeder", "jaw_crusher", "cone_crusher", "vsi_crusher", "impact_crusher", "screen", "conveyor",
                    "washer", "generator", "other"}) String kind,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal capacityTph,
            @DecimalMin(value = "0") @Digits(integer = 9, fraction = 3) BigDecimal powerKw,
            @OneOf({"none", "engine_hours", "kwh", "both"}) String meterKind,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    /** One circuit stage (routing operation) with its equipment. */
    public record StageInput(
            @NotBlank @Size(max = 120) String name,
            @OneOf({"primary", "secondary", "tertiary", "quaternary", "screening", "washing", "other"}) String kind,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal capacityTph,
            List<@Valid @NotNull UnitInput> units) {}

    /** {@code POST / PUT /manufacturing/crushing/plants}: the plant, its rates and its whole circuit (stages in order). */
    public record PlantInput(
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            String siteId,
            String feedStockpileId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal designCapacityTph,
            @DecimalMin(value = "0") @Digits(integer = 10, fraction = 4) BigDecimal dieselCostPerLitre,
            @DecimalMin(value = "0") @Digits(integer = 10, fraction = 4) BigDecimal electricityCostPerKwh,
            @DecimalMin(value = "0") @Digits(integer = 10, fraction = 4) BigDecimal labourRatePerHour,
            Boolean isActive,
            @Size(max = 2000) String notes,
            @NotNull @NotEmpty @Size(max = 10) List<@Valid @NotNull StageInput> stages) {}

    // ------------------------------------------------------------------ grading profile

    /** One size fraction of a profile. */
    public record GradingLineInput(
            @NotBlank String productId,
            @Size(max = 40) String sizeLabel,
            @NotNull @OneOf({"main", "co_product", "by_product", "waste"}) String role,
            @Min(1) @Max(10) Integer stageSequence,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 4)
            BigDecimal expectedPct,
            Boolean stocked,
            String stockpileId,
            @OneOf({"allocated", "nrv", "zero"}) String valuation,
            @DecimalMin("0") BigDecimal nrvUnitValue,
            @DecimalMin("0") BigDecimal marketValue,
            @DecimalMin("0") @DecimalMax("100") BigDecimal fixedPct) {}

    /** {@code POST / PUT /manufacturing/crushing/grading-profiles} (PUT: drafts only). */
    public record ProfileInput(
            @NotBlank String plantId,
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String feedProductId,
            @OneOf({"quantity", "weight", "market_value", "fixed_pct"}) String allocationMethod,
            @Size(max = 2000) String notes,
            @NotNull @NotEmpty @Size(max = 20) List<@Valid @NotNull GradingLineInput> lines) {}

    // ------------------------------------------------------------------ crushing run

    /** Actual output of one fraction: tonnes, or % of the feed when the run's split is a survey. */
    public record RunOutputInput(
            @NotBlank String gradingLineId,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal actualTonnes,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal actualPct) {}

    /** Meter readings of one equipment unit (end − start, or the hours / kWh directly). */
    public record RunMeterInput(
            @NotBlank String unitId,
            @DecimalMin("0") BigDecimal hoursStart,
            @DecimalMin("0") BigDecimal hoursEnd,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal runHours,
            @DecimalMin("0") BigDecimal kwhStart,
            @DecimalMin("0") BigDecimal kwhEnd,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal kwh,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal dieselLitres,
            @Size(max = 500) String notes) {}

    /** A manual cost line (wear parts, maintenance, other): amount, or quantity × unit rate. */
    public record RunCostInput(
            @NotNull @OneOf({"wear_parts", "maintenance", "other"}) String kind,
            String unitId,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal unitRate,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Size(max = 500) String description) {}

    /** {@code POST / PUT /manufacturing/crushing/runs} (drafts). */
    public record RunInput(
            @NotBlank String plantId,
            String profileId,
            @NotBlank @Pattern(regexp = DATE, message = DATE_MSG) String runDate,
            @OneOf({"day", "night", "morning", "afternoon"}) String shift,
            @Size(max = 120) String operatorName,
            String feedStockpileId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal feedTonnes,
            @OneOf({"belt_scale", "weighbridge", "loads", "survey"}) String feedMethod,
            @OneOf({"belt_scale", "weighbridge", "survey_pct"}) String outputMethod,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal recirculatedTonnes,
            @DecimalMin("0") @DecimalMax("24") BigDecimal operatingHours,
            @DecimalMin("0") @DecimalMax("24") BigDecimal downtimeHours,
            @Size(max = 500) String downtimeReason,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal labourHours,
            @Size(max = 2000) String notes,
            List<@Valid @NotNull RunOutputInput> outputs,
            List<@Valid @NotNull RunMeterInput> meters,
            List<@Valid @NotNull RunCostInput> costs) {}

    /** Reason of a reversal / rejection. */
    public record ReasonInput(@NotBlank @Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ stockpile survey

    /** {@code POST / PUT /manufacturing/crushing/stockpile-surveys} (drafts). */
    public record SurveyInput(
            @NotBlank String stockpileId,
            @NotBlank String productId,
            @NotBlank @Pattern(regexp = DATE, message = DATE_MSG) String surveyDate,
            @OneOf({"drone", "total_station", "gps", "tape", "visual", "other"}) String method,
            @Size(max = 120) String surveyor,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal volumeM3,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("5") @Digits(integer = 4, fraction = 4)
            BigDecimal bulkDensity,
            @Size(max = 2000) String notes) {}
}
