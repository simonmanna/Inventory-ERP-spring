package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request records of quality alerts and their settings (ADR-S087 §8, QC3). */
public final class QcAlertCommands {

    private QcAlertCommands() {}

    public record AlertQuery(String search, String stageId, String teamId, String responsibleUserId, Integer priority,
            String productId, String checkId, String ncrId, String tagId, Boolean open, String origin, Integer page,
            Integer pageSize) {}

    /** A manual alert: free, from a check ({@code checkId}) or from a document ({@code sourceType} + {@code sourceId}). */
    public record AlertInput(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @Min(0) @Max(3) Integer priority,
            String teamId,
            String responsibleUserId,
            String checkId,
            String productId,
            @Size(max = 80) String batchNumber,
            String workCenterId,
            @OneOf({"production_order", "work_order", "goods_receipt", "sales_delivery"}) String sourceType,
            String sourceId,
            String supplierPartnerId,
            String failureClassId,
            String rootCauseId,
            @Size(max = 5000) String correctiveAction,
            @Size(max = 5000) String preventiveAction,
            List<@NotBlank String> tagIds) {}

    /** Fields left null stay as they are; {@code tagIds} replaces the tags; "" clears an optional reference. */
    public record AlertPatch(
            @Size(min = 1, max = 200) String title,
            @Size(max = 5000) String description,
            @Min(0) @Max(3) Integer priority,
            String teamId,
            String responsibleUserId,
            String failureClassId,
            String rootCauseId,
            String correctionCheckId,
            @Size(max = 5000) String correctiveAction,
            @Size(max = 5000) String preventiveAction,
            List<@NotBlank String> tagIds) {}

    public record MoveInput(@NotBlank String stageId, @Size(max = 1000) String comment) {}

    public record EscalateInput(@OneOf({"minor", "major", "critical"}) String severity, String ownerUserId) {}

    public record StageInput(
            @NotBlank @Size(max = 80) String name,
            @Min(0) @Max(9999) Integer sequence,
            Boolean isClosed,
            Boolean folded) {}

    public record StagePatch(
            @Size(min = 1, max = 80) String name,
            @Min(0) @Max(9999) Integer sequence,
            Boolean isClosed,
            Boolean folded,
            Boolean isActive) {}

    public record TagInput(@NotBlank @Size(max = 60) String name, @Size(max = 20) String color) {}

    public record TagPatch(@Size(min = 1, max = 60) String name, @Size(max = 20) String color) {}

    public record RootCauseInput(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description) {}

    public record RootCausePatch(@Size(min = 1, max = 120) String name, @Size(max = 1000) String description,
            Boolean isActive) {}
}
