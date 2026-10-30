package com.inventoryerp.quality.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request / query records of the quality module (ADR-S013). {@code null} in a patch = unchanged. */
public final class QualityCommands {

    private QualityCommands() {}

    // ------------------------------------------------------------------ specifications

    public record SievePointInput(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sieveMm,
            @DecimalMin("0") @DecimalMax("100") BigDecimal minPassingPct,
            @DecimalMin("0") @DecimalMax("100") BigDecimal maxPassingPct) {}

    public record SpecParameterInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"numeric", "sieve", "boolean", "text"}) String kind,
            @Size(max = 20) String unit,
            @Size(max = 120) String testMethod,
            BigDecimal minValue,
            BigDecimal maxValue,
            BigDecimal targetValue,
            @Min(0) @Max(6) Integer decimals,
            @Size(max = 120) String expectedText,
            @Valid List<SievePointInput> sieveEnvelope,
            Boolean isCritical,
            Integer sortOrder) {}

    public record SpecInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name,
            String productId,
            @Size(max = 60) String grade,
            @Size(max = 120) String standard,
            LocalDate effectiveFrom,
            String notes,
            @Valid List<SpecParameterInput> parameters) {}

    public record SpecPatch(
            @Size(max = 160) String name,
            String productId,
            @Size(max = 60) String grade,
            @Size(max = 120) String standard,
            LocalDate effectiveFrom,
            String notes,
            @Valid List<SpecParameterInput> parameters) {}

    public record SpecQuery(String search, String status, String productId, Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ inspection plans

    public record PlanInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @OneOf({"incoming", "in_process", "final"}) String stage,
            @NotBlank String productId,
            String supplierPartnerId,
            @Size(max = 60) String stageKey,
            @NotBlank String specificationId,
            @OneOf({"fixed", "percent", "sqrt", "per_quantity"}) String samplingMethod,
            @Min(1) @Max(10000) Integer sampleSize,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal samplePct,
            @DecimalMin(value = "0", inclusive = false) BigDecimal perQuantity,
            @Min(1) @Max(10000) Integer minSamples,
            @Min(1) @Max(10000) Integer maxSamples,
            @OneOf({"quarantine", "hold", "none"}) String holdPolicy,
            Boolean independentRelease,
            Boolean autoNcr,
            Boolean isActive,
            String notes) {}

    public record PlanPatch(
            @Size(max = 160) String name,
            String supplierPartnerId,
            @Size(max = 60) String stageKey,
            String specificationId,
            @OneOf({"fixed", "percent", "sqrt", "per_quantity"}) String samplingMethod,
            @Min(1) @Max(10000) Integer sampleSize,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal samplePct,
            @DecimalMin(value = "0", inclusive = false) BigDecimal perQuantity,
            @Min(1) @Max(10000) Integer minSamples,
            @Min(1) @Max(10000) Integer maxSamples,
            @OneOf({"quarantine", "hold", "none"}) String holdPolicy,
            Boolean independentRelease,
            Boolean autoNcr,
            Boolean isActive,
            String notes) {}

    public record PlanQuery(String search, String stage, String productId, Boolean active, Integer page,
            Integer pageSize) {}

    // ------------------------------------------------------------------ inspection lots

    /**
     * Open a lot from code (GRN subscriber, 2.1 stage gates, 2.3 / 2.4). {@code planId} null = best matching active
     * plan; {@code stockControl} null = {@code quality}; {@code applyHoldPolicy} null = true.
     */
    public record OpenLot(String stage, String productId, String variantId, String batchNumber, BigDecimal quantity,
            String uomId, String locationId, String sourceType, String sourceId, String sourceLineId,
            String sourceRef, String stageKey, String supplierPartnerId, String workCenterId, String planId,
            String stockControl, Boolean applyHoldPolicy, String notes) {}

    public record LotInput(
            @NotBlank @OneOf({"incoming", "in_process", "final"}) String stage,
            @NotBlank String productId,
            String variantId,
            @Size(max = 80) String batchNumber,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String locationId,
            String planId,
            String supplierPartnerId,
            String workCenterId,
            @Size(max = 60) String stageKey,
            @Size(max = 120) String sourceRef,
            Boolean applyHoldPolicy,
            String notes) {}

    public record LotQuery(String search, String status, String stage, String result, String productId,
            String supplierPartnerId, String sourceType, String sourceId, String batchNumber, Integer page,
            Integer pageSize) {}

    public record SieveReadingInput(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sieveMm,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal passingPct) {}

    public record ResultEntry(
            String parameterId,
            @Size(max = 40) String parameterCode,
            @Min(1) @Max(10000) Integer sampleNo,
            BigDecimal numericValue,
            @Size(max = 500) String textValue,
            @Valid List<SieveReadingInput> sieve,
            @Size(max = 120) String labReference,
            Instant testedAt) {}

    public record RecordResults(@NotEmpty @Valid List<ResultEntry> results, String notes) {}

    public record ReasonInput(@Size(max = 500) String reason) {}

    public record ReleaseInput(
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String toLocationId,
            @Size(max = 500) String concessionReason,
            @Size(max = 500) String reason) {}

    public record RejectInput(
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @OneOf({"scrap", "return_to_supplier"}) String disposition,
            @NotBlank @Size(max = 500) String reason) {}

    public record DowngradeInput(
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotBlank String productId,
            String variantId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal toQuantity,
            @Size(max = 80) String batchNumber,
            @DecimalMin("0") BigDecimal unitCost,
            String toLocationId,
            @NotBlank @Size(max = 500) String reason) {}

    public record CancelInput(@NotBlank @Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ NCR / CAPA

    public record NcrInput(
            @NotBlank @Size(max = 200) String title,
            String description,
            @OneOf({"incoming", "in_process", "final", "customer", "supplier", "internal"}) String source,
            @OneOf({"minor", "major", "critical"}) String severity,
            String lotId,
            String productId,
            @Size(max = 80) String batchNumber,
            String supplierPartnerId,
            String customerPartnerId,
            String workCenterId,
            @DecimalMin("0") BigDecimal quantityAffected,
            String disposition,
            String rootCause,
            String ownerUserId) {}

    public record NcrPatch(
            @Size(max = 200) String title,
            String description,
            @OneOf({"minor", "major", "critical"}) String severity,
            @DecimalMin("0") BigDecimal quantityAffected,
            String disposition,
            String rootCause,
            String ownerUserId) {}

    public record NcrStatusChange(
            @NotBlank @OneOf({"investigating", "action", "closed", "cancelled", "open"}) String status,
            @Size(max = 1000) String notes) {}

    public record NcrQuery(String search, String status, String severity, String source, String lotId,
            String supplierPartnerId, Integer page, Integer pageSize) {}

    public record CapaActionInput(
            @OneOf({"corrective", "preventive"}) String kind,
            @NotBlank @Size(max = 1000) String description,
            String ownerUserId,
            LocalDate dueDate) {}

    public record CapaInput(
            @NotBlank @Size(max = 200) String title,
            String ncrId,
            String rootCause,
            @OneOf({"five_why", "fishbone", "fault_tree", "other"}) String rootCauseMethod,
            String ownerUserId,
            LocalDate dueDate,
            @Valid List<CapaActionInput> actions) {}

    public record CapaPatch(
            @Size(max = 200) String title,
            String rootCause,
            @OneOf({"five_why", "fishbone", "fault_tree", "other"}) String rootCauseMethod,
            String ownerUserId,
            LocalDate dueDate,
            String effectivenessNotes) {}

    public record CapaStatusChange(
            @NotBlank @OneOf({"in_progress", "verification", "closed", "cancelled"}) String status,
            @Size(max = 1000) String notes) {}

    public record CapaActionPatch(
            @OneOf({"corrective", "preventive"}) String kind,
            @Size(max = 1000) String description,
            String ownerUserId,
            LocalDate dueDate,
            String notes) {}

    public record CapaActionComplete(@OneOf({"done", "cancelled"}) String status, @Size(max = 1000) String notes) {}

    public record CapaQuery(String search, String status, String ownerUserId, String ncrId, Boolean overdue,
            Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ lab SPI

    public record LabCsvImport(@NotBlank @Size(max = 2_000_000) String content, @Size(max = 200) String filename) {}

    public record LabFetch(@NotBlank @OneOf({"rest", "simulator"}) String adapter,
            @NotEmpty @Size(max = 200) List<String> lotIds) {}

    // ------------------------------------------------------------------ reports

    public record ReportQuery(String groupBy, String stage, LocalDate from, LocalDate to) {}
}
