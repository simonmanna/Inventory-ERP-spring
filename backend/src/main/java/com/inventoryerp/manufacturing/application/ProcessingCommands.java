package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/** Request bodies of the processing routes (ADR-S060); they double as service inputs. */
public final class ProcessingCommands {

    private ProcessingCommands() {}

    static final String POS = "must be a positive number";
    static final String MIN0 = "must not be less than 0";
    static final String PCT = "must be between 0 and 100";
    static final String REQUIRED = "should not be empty";

    /** Suggested cost per basis unit, shown on job entry. */
    public record CostRate(
            @NotNull(message = "category " + REQUIRED) String category,
            @DecimalMin(value = "0", message = "ratePerBasis " + MIN0) BigDecimal ratePerBasis) {}

    /** One input of a template. */
    public record TemplateInputLine(
            @NotNull(message = "productId " + REQUIRED) String productId,
            @OneOf({"traceable", "consumable"}) String role,
            @OneOf({"mass", "value", "fixed_pct", "manual"}) String genealogyBasis,
            @DecimalMin(value = "0", message = "fixedPct " + PCT) @DecimalMax(value = "100", message = "fixedPct " + PCT)
            BigDecimal fixedPct,
            @Positive(message = "basisFactor " + POS) BigDecimal basisFactor) {}

    /** One output of a template. */
    public record TemplateOutputLine(
            @NotNull(message = "productId " + REQUIRED) String productId,
            @NotNull(message = "role " + REQUIRED) @OneOf({"main", "co_product", "by_product", "residual", "waste"})
            String role,
            @NotNull(message = "expectedPct " + PCT) @DecimalMin(value = "0", message = "expectedPct " + PCT)
            BigDecimal expectedPct,
            @OneOf({"allocated", "nrv", "zero"}) String valuation,
            @DecimalMin(value = "0", message = "nrvUnitValue " + MIN0) BigDecimal nrvUnitValue,
            @DecimalMin(value = "0", message = "marketValue " + MIN0) BigDecimal marketValue,
            @Positive(message = "basisFactor " + POS) BigDecimal basisFactor,
            List<String> requiredMeasures,
            Boolean stocked,
            String locationId) {}

    /** Draft body of a template. */
    public record TemplateBody(
            @NotNull(message = "code " + REQUIRED) @NotEmpty(message = "code " + REQUIRED) String code,
            @NotNull(message = "name " + REQUIRED) @NotEmpty(message = "name " + REQUIRED) String name,
            String description,
            String workCenterId,
            @OneOf({"weight", "market_value", "quantity", "fixed_pct"}) String allocationMethod,
            String yieldBasisUnit,
            @DecimalMin(value = "0.0001", message = "expectedYieldPct must be greater than 0")
            @DecimalMax(value = "100", message = "expectedYieldPct " + PCT) BigDecimal expectedYieldPct,
            Boolean qcGate,
            String qcCheckpointCode,
            String defaultInputLocationId,
            String defaultOutputLocationId,
            List<@Valid @NotNull CostRate> costRates,
            @NotNull(message = "inputs must be an array") List<@Valid @NotNull TemplateInputLine> inputs,
            @NotNull(message = "outputs must be an array") List<@Valid @NotNull TemplateOutputLine> outputs) {}

    /** One input lot of a job. */
    public record JobInputLine(
            @NotNull(message = "templateInputId " + REQUIRED) String templateInputId,
            String batchNumber,
            @NotNull(message = "quantity " + POS) @Positive(message = "quantity " + POS) BigDecimal quantity,
            @Positive(message = "basisQty " + POS) BigDecimal basisQty,
            @DecimalMin(value = "0", message = "manualSharePct " + PCT)
            @DecimalMax(value = "100", message = "manualSharePct " + PCT) BigDecimal manualSharePct) {}

    /** Draft of a job: reserves its input. */
    public record CreateJob(
            @NotNull(message = "templateId " + REQUIRED) String templateId,
            String locationId,
            String outputLocationId,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String jobDate,
            String reprocessOfJobId,
            String notes,
            @NotNull(message = "inputs must be an array") @NotEmpty(message = "inputs " + REQUIRED)
            List<@Valid @NotNull JobInputLine> inputs) {}

    /** One cost of a job. */
    public record JobCostInput(
            @NotNull(message = "category " + REQUIRED)
            @OneOf({"labour", "machine", "overhead", "utility", "fuel", "consumable", "subcontract"}) String category,
            String description,
            @NotNull(message = "amount " + POS) @Positive(message = "amount " + POS) BigDecimal amount,
            String currency,
            @Positive(message = "exchangeRate " + POS) BigDecimal exchangeRate,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String costDate,
            String sourceType,
            String sourceId,
            String creditAccountId) {}

    /** One output of a posting, with optional measurements. */
    public record JobOutputLine(
            @NotNull(message = "templateOutputId " + REQUIRED) String templateOutputId,
            @NotNull(message = "quantity " + MIN0) @DecimalMin(value = "0", message = "quantity " + MIN0) BigDecimal quantity,
            String batchNumber,
            String grade,
            @Positive(message = "basisQty " + POS) BigDecimal basisQty,
            @Positive(message = "weight " + POS) BigDecimal weight,
            @Positive(message = "length " + POS) BigDecimal length,
            @Positive(message = "width " + POS) BigDecimal width,
            @Positive(message = "thickness " + POS) BigDecimal thickness,
            @Positive(message = "area " + POS) BigDecimal area,
            @Positive(message = "volume " + POS) BigDecimal volume,
            @Positive(message = "density " + POS) BigDecimal density) {}

    /** An output posting: partial, or completing with a full reconciliation. */
    public record RecordOutput(
            @NotNull(message = "lines must be an array") List<@Valid @NotNull JobOutputLine> lines,
            @DecimalMin(value = "0", message = "lossBasis " + MIN0) BigDecimal lossBasis,
            Boolean complete,
            Boolean writeOff,
            String lossReason,
            String overYieldReason,
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String date,
            String notes) {}

    /** The whole job in one transaction, for a step entered after the fact. */
    public record PostJob(
            @NotNull(message = "job must be an object") @Valid CreateJob job,
            List<@Valid @NotNull JobCostInput> costs,
            @NotNull(message = "output must be an object") @Valid RecordOutput output) {}

    /** Cancel or reverse reason. */
    public record JobReason(String reason) {}

    /** One step of a route. */
    public record RouteStepInput(@NotNull(message = "templateCode " + REQUIRED) String templateCode, String notes) {}

    /** Route body. */
    public record RouteBody(
            @NotNull(message = "code " + REQUIRED) @NotEmpty(message = "code " + REQUIRED) String code,
            @NotNull(message = "name " + REQUIRED) @NotEmpty(message = "name " + REQUIRED) String name,
            String description,
            Boolean isActive,
            @NotNull(message = "steps must be an array") List<@Valid @NotNull RouteStepInput> steps) {}
}
