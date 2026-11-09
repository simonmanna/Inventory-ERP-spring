package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the product lifecycle management routes (ADR-S063). */
public final class PlmCommands {

    private PlmCommands() {}

    static final String DATE = "^(\\d{4}-\\d{2}-\\d{2})?$";
    static final String DATE_MSG = "must be a date (YYYY-MM-DD) or empty to clear it";

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    public record RegisterItem(
            @NotBlank String productId,
            @Size(max = 10) String revision,
            @OneOf({"concept", "design", "prototype", "released"}) String lifecycleState,
            String ownerId,
            @Size(max = 2000) String description,
            @Size(max = 4000) String notes) {}

    public record UpdateItem(
            String ownerId,
            @Size(max = 2000) String description,
            @Size(max = 4000) String notes,
            String replacementProductId) {}

    public record Transition(
            @NotNull @OneOf({"concept", "design", "prototype", "released", "phase_out", "obsolete"}) String toState,
            @Size(max = 2000) String note,
            String replacementProductId) {}

    // ---- stages ----------------------------------------------------------------------------------------------------

    public record SaveStage(
            @Size(min = 1, max = 80) String name,
            @Min(0) Integer sequence,
            Boolean requiresApproval,
            @Size(max = 500) String description) {}

    public record StageApprover(@NotBlank String userId, @OneOf({"required", "optional"}) String kind) {}

    public record StageApprovers(@NotNull @Valid List<StageApprover> approvers) {}

    // ---- change requests -------------------------------------------------------------------------------------------

    public record SaveRequest(
            @Size(min = 1, max = 200) String title,
            @Size(max = 4000) String description,
            String productId,
            String bomId,
            @OneOf({"cost_reduction", "quality", "customer", "supplier", "regulatory", "safety", "manufacturability",
                    "other"}) String reasonCode,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @Pattern(regexp = DATE, message = DATE_MSG) String dueDate) {}

    public record ReviewRequest(@NotNull @OneOf({"accept", "reject"}) String decision, @Size(max = 2000) String note) {}

    public record ConvertRequest(
            @NotNull @OneOf({"bom", "routing", "product", "document"}) String type,
            @Size(max = 200) String title,
            String bomId,
            String routingId,
            @OneOf({"concept", "design", "prototype", "released", "phase_out", "obsolete"}) String targetLifecycleState,
            List<String> documentIds) {}

    // ---- change orders ---------------------------------------------------------------------------------------------

    public record CreateChange(
            @NotNull @OneOf({"bom", "routing", "product", "document"}) String type,
            @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @Size(max = 2000) String reason,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            String productId,
            String bomId,
            String routingId,
            @OneOf({"concept", "design", "prototype", "released", "phase_out", "obsolete"}) String targetLifecycleState,
            List<String> documentIds,
            @OneOf({"asap", "date"}) String effectivity,
            @Pattern(regexp = DATE, message = DATE_MSG) String effectiveDate,
            @OneOf({"use_up", "scrap", "rework", "return_to_supplier", "none"}) String stockDisposition,
            @OneOf({"continue", "update", "rework"}) String wipDisposition,
            @Size(max = 10) String newRevision,
            String ownerId,
            String requestId) {}

    public record UpdateChange(
            @Size(min = 1, max = 200) String title,
            @Size(max = 4000) String description,
            @Size(max = 2000) String reason,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @OneOf({"concept", "design", "prototype", "released", "phase_out", "obsolete"}) String targetLifecycleState,
            @OneOf({"asap", "date"}) String effectivity,
            @Pattern(regexp = DATE, message = DATE_MSG) String effectiveDate,
            @OneOf({"use_up", "scrap", "rework", "return_to_supplier", "none"}) String stockDisposition,
            @OneOf({"continue", "update", "rework"}) String wipDisposition,
            @Size(max = 10) String newRevision,
            String ownerId) {}

    public record MoveStage(@NotBlank String stageId) {}

    public record Decide(@NotNull @OneOf({"approved", "rejected"}) String decision, @Size(max = 2000) String comment) {}

    public record Comment(@NotBlank @Size(max = 4000) String message) {}

    public record Cancel(@Size(max = 2000) String reason) {}

    /** {@code force}: switch over now even though stock of removed components is still to be used up. */
    public record ApplyChange(Boolean force) {}

    public record LinkDocuments(@NotNull List<String> documentIds) {}

    /** The new routing's operations of a routing ECO (replaces them all). */
    public record RoutingOperations(
            @NotNull @Size(min = 1, message = "operations must contain at least 1 elements")
            List<@Valid @NotNull RoutingOperationInput> operations) {}

    // ---- documents -------------------------------------------------------------------------------------------------

    public record SaveDocument(
            @Size(min = 1, max = 200) String title,
            @OneOf({"drawing", "specification", "cad", "work_instruction", "test_procedure", "datasheet",
                    "certificate", "other"}) String docType,
            @Size(max = 10) String revision,
            String productId,
            String bomId,
            String changeId,
            String fileId,
            @Size(max = 2000) @Pattern(regexp = "^(https?://.*)?$", message = "url must be an http(s) URL") String url,
            @Size(max = 255) String fileName,
            @Size(max = 4000) String description) {}
}
