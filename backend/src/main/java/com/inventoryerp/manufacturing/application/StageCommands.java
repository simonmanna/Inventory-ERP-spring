package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request bodies of the multi-stage production routes (ADR-S011, Phase 2.1); they double as service inputs. */
public final class StageCommands {

    private StageCommands() {}

    static final String STR = "must be a string";
    static final String POS = "must be a positive number";
    static final String MIN0 = "must not be less than 0";
    static final String PCT = "must be between 0 and 100";
    static final String EMPTY = "should not be empty";

    // ------------------------------------------------------------------ set-up

    /** Stage behaviour of one routing operation. */
    public record StageSetupInput(
            @NotNull(message = "operationId " + STR) @Size(min = 1, message = "operationId " + EMPTY) String operationId,
            @OneOf({"backflush", "manual"}) String issueMode,
            String outputProductId,
            @Positive(message = "outputPerUnit " + POS) BigDecimal outputPerUnit,
            String stageLocationId,
            @DecimalMin(value = "0.0001", message = "expectedYieldPct must be greater than 0")
            @DecimalMax(value = "100", message = "expectedYieldPct " + PCT) BigDecimal expectedYieldPct,
            Boolean qcGate,
            String qcCheckpointCode,
            List<@NotNull String> materialProductIds) {}

    /** {@code PUT /routings/{id}/stage-setup}. */
    public record RoutingStageSetup(@NotNull(message = "stages must be an array") List<@Valid @NotNull StageSetupInput> stages) {}

    /** One co-product / by-product / waste output of a BOM. */
    public record CoProductInput(
            @NotNull(message = "productId " + STR) @Size(min = 1, message = "productId " + EMPTY) String productId,
            @NotNull(message = "role must be one of the following values: co_product, by_product, waste")
            @OneOf({"co_product", "by_product", "waste"}) String role,
            String operationId,
            @NotNull(message = "quantity " + MIN0) @DecimalMin(value = "0", message = "quantity " + MIN0) BigDecimal quantity,
            String uomId,
            @OneOf({"allocated", "nrv", "zero"}) String valuation,
            @DecimalMin(value = "0", message = "nrvUnitValue " + MIN0) BigDecimal nrvUnitValue,
            @DecimalMin(value = "0", message = "marketValue " + MIN0) BigDecimal marketValue,
            @DecimalMin(value = "0", message = "weightPerUnit " + MIN0) BigDecimal weightPerUnit,
            @DecimalMin(value = "0", message = "fixedPct " + PCT) @DecimalMax(value = "100", message = "fixedPct " + PCT)
            BigDecimal fixedPct,
            Boolean stocked,
            String locationId,
            String notes) {}

    /** Joint-cost allocation rule body. */
    public record AllocationRuleInput(
            @NotNull(message = "method must be one of the following values: quantity, weight, market_value, fixed_pct")
            @OneOf({"quantity", "weight", "market_value", "fixed_pct"}) String method,
            @DecimalMin(value = "0", message = "mainWeightPerUnit " + MIN0) BigDecimal mainWeightPerUnit,
            @DecimalMin(value = "0", message = "mainMarketValue " + MIN0) BigDecimal mainMarketValue,
            String notes) {}

    /** {@code PUT /boms/{id}/stage-setup}: replaces the co-products; {@code allocationRule} null = use the default. */
    public record BomStageSetup(
            @NotNull(message = "coProducts must be an array") List<@Valid @NotNull CoProductInput> coProducts,
            @Valid AllocationRuleInput allocationRule) {}

    // ------------------------------------------------------------------ execution

    /** {@code POST /production-orders/{id}/stages/initialize}. */
    public record InitializeStages(String notes) {}

    /** One manual issue line: a stage material ({@code materialId}) or the previous stage's intermediate. */
    public record IssueLine(
            String materialId,
            String productId,
            @NotNull(message = "quantity " + POS) @Positive(message = "quantity " + POS) BigDecimal quantity,
            String batchNumber) {}

    /** {@code POST /production-stages/{id}/issue}. */
    public record StageIssue(
            @NotNull(message = "lines must be an array") @NotEmpty(message = "lines " + EMPTY)
            List<@Valid @NotNull IssueLine> lines,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            String notes) {}

    /** Actual quantity of a co-product / by-product / waste output line. */
    public record OutputQty(
            @NotNull(message = "outputId " + STR) String outputId,
            @NotNull(message = "quantity " + MIN0) @DecimalMin(value = "0", message = "quantity " + MIN0) BigDecimal quantity,
            String batchNumber) {}

    /** {@code POST /production-stages/{id}/output}: partial ({@code complete} false) or completing. */
    public record StageOutput(
            @NotNull(message = "qtyGood " + MIN0) @DecimalMin(value = "0", message = "qtyGood " + MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = "qtyScrap " + MIN0) BigDecimal qtyScrap,
            String scrapReasonCode,
            Boolean complete,
            List<@Valid @NotNull OutputQty> outputs,
            String batchNumber,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String expiryDate,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            String notes) {}

    /** {@code POST /production-stages/{id}/conversion-costs}: labour / machine / overhead / utility applied. */
    public record StageConversion(
            @NotNull(message = "amount " + POS) @Positive(message = "amount " + POS) BigDecimal amount,
            @OneOf({"labour", "machine", "overhead", "utility"}) String kind,
            String creditAccountId,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            String notes) {}

    /** {@code POST /production-stages/{id}/qc-decision}. */
    public record StageQcDecision(
            @NotNull(message = "result must be one of the following values: passed, failed")
            @OneOf({"passed", "failed"}) String result,
            String reference,
            String notes) {}

    /** Reversal body. */
    public record StageReversal(String reason) {}

    /** {@code POST /production-orders/{id}/child-orders}. */
    public record ChildOrders(Boolean netAgainstStock, Boolean initializeStages, Integer maxLevels) {}

    /** {@code POST /manufacturing/multistage-orders}: create + stages + child orders in one step. */
    public record CreateMultistageOrder(
            @NotNull(message = "order must be an object") @Valid MfgCommands.CreateOrder order,
            Boolean createChildOrders,
            Boolean netAgainstStock) {}
}
