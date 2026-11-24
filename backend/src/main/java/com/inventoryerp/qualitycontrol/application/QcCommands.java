package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Request / query records of the quality-control module (ADR-S087). {@code null} in a patch = unchanged. */
public final class QcCommands {

    private QcCommands() {}

    // ------------------------------------------------------------------ teams

    public record TeamInput(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            List<@NotBlank String> memberUserIds) {}

    public record TeamPatch(
            @Size(min = 1, max = 120) String name,
            @Size(max = 500) String description,
            Boolean isActive,
            List<@NotBlank String> memberUserIds) {}

    // ------------------------------------------------------------------ failure classes

    public record FailureClassInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @Min(0) @Max(9999) Integer sortOrder) {}

    public record FailureClassPatch(
            @Size(min = 1, max = 120) String name,
            @Min(0) @Max(9999) Integer sortOrder,
            Boolean isActive) {}

    // ------------------------------------------------------------------ worksheet templates

    public record TemplateInput(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotEmpty List<Map<String, Object>> fields) {}

    public record TemplatePatch(
            @Size(min = 1, max = 120) String name,
            @Size(max = 500) String description,
            List<Map<String, Object>> fields,
            Boolean isActive) {}

    // ------------------------------------------------------------------ control points

    public record ControlPointInput(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @OneOf({"receipt", "delivery", "manufacturing", "work_order"}) String operation,
            List<@NotBlank String> productIds,
            List<@NotBlank String> categoryIds,
            String supplierPartnerId,
            String routingOperationId,
            String teamId,
            String responsibleUserId,
            @NotBlank @OneOf({"instructions", "pass_fail", "measure", "picture", "worksheet"}) String checkType,
            @Size(max = 10000) String instructions,
            @Size(max = 2000) String failureMessage,
            Boolean failureClassRequired,
            BigDecimal norm,
            BigDecimal toleranceMin,
            BigDecimal toleranceMax,
            @Size(max = 20) String unit,
            @Min(0) @Max(6) Integer decimals,
            String worksheetTemplateId,
            @OneOf({"operation", "product", "quantity"}) String controlPer,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal testQtyPct,
            @DecimalMin(value = "0", inclusive = false) BigDecimal minSampleQty,
            @OneOf({"all", "random", "periodic"}) String frequency,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal randomPct,
            @Min(1) @Max(366) Integer periodValue,
            @OneOf({"day", "week", "month"}) String periodUnit,
            Boolean blocking,
            Boolean blockOnFailure,
            Boolean autoAlert,
            @OneOf({"pre_receipt", "quarantine_on_receipt"}) String receiptMode,
            String failureLocationId,
            @OneOf({"individual", "consecutive", "batch", "work_order", "time_window"}) String spcSubgroup,
            @Min(2) @Max(10) Integer spcSubgroupSize,
            @Min(1) @Max(720) Integer spcWindowHours,
            Boolean spcSplitByWorkCenter,
            @Min(0) @Max(9999) Integer sequence,
            @Size(max = 500) String reason) {}

    public record ControlPointQuery(String search, String operation, String checkType, Boolean active, Integer page,
            Integer pageSize) {}

    public record StatusReason(@Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ checks

    public record ManualCheckInput(
            @Size(max = 160) String title,
            String controlPointId,
            @OneOf({"instructions", "pass_fail", "measure", "picture", "worksheet"}) String checkType,
            @OneOf({"production_order", "work_order", "goods_receipt", "sales_delivery", "manual"}) String sourceType,
            String sourceId,
            @Size(max = 80) String sourceRef,
            String productId,
            String variantId,
            @Size(max = 80) String batchNumber,
            String supplierPartnerId,
            String workCenterId,
            @DecimalMin(value = "0") BigDecimal coveredQty,
            @DecimalMin(value = "0") BigDecimal testQty,
            String teamId,
            String responsibleUserId,
            @Size(max = 10000) String instructions,
            BigDecimal norm,
            BigDecimal toleranceMin,
            BigDecimal toleranceMax,
            @Size(max = 20) String unit,
            @Min(0) @Max(6) Integer decimals,
            String worksheetTemplateId,
            Boolean failureClassRequired,
            Boolean includeInSpc,
            @Size(max = 2000) String notes) {}

    public record CheckQuery(String search, String status, String origin, String checkType, String sourceType,
            String sourceId, String controlPointId, String productId, String teamId, String quarantineStatus,
            Integer page, Integer pageSize) {}

    public record SampleInput(
            @Size(max = 80) String sampleRef,
            @Size(max = 80) String batchNumber,
            @DecimalMin(value = "0", inclusive = false) BigDecimal sampleQty,
            BigDecimal measureValue,
            @OneOf({"passed", "failed"}) String result,
            @Size(max = 1000) String notes) {}

    public record ExecuteInput(
            @OneOf({"passed", "failed"}) String result,
            BigDecimal measureValue,
            @Size(max = 200) List<@Valid SampleInput> samples,
            Map<String, Object> worksheetAnswers,
            String pictureDocumentId,
            String failureClassId,
            @DecimalMin(value = "0") BigDecimal failedQty,
            @Size(max = 2000) String notes) {}

    public record WaiveInput(
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @OneOf({"accepted_as_is", "quantity_excluded", "reworked", "other"}) String disposition,
            @DecimalMin(value = "0") BigDecimal excludedQty) {}

    public record ReasonInput(@NotBlank @Size(max = 1000) String reason) {}

    public record CorrectionInput(
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 2000) String notes) {}
}
