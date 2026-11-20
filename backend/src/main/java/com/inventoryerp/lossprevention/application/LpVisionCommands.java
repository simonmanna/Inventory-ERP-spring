package com.inventoryerp.lossprevention.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Inputs of the camera platform (LP1): agents, cameras, zones, the agent protocol and evidence. */
public final class LpVisionCommands {

    private LpVisionCommands() {}

    // ------------------------------------------------------------------ administration

    /** {@code POST /loss-prevention/agents}. */
    public record CreateAgentInput(
            @NotBlank(message = "branchId is required") String branchId,
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name) {}

    /** {@code PUT /loss-prevention/agents/:id}: the whole editable part. An empty version clears the target. */
    public record UpdateAgentInput(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            String desiredModelVersion,
            String desiredAgentVersion) {}

    /** {@code POST /loss-prevention/agents/:id/revoke}. */
    public record RevokeAgentInput(@Size(max = 1000, message = "reason must hold at most 1000 characters")
            String reason) {}

    /** {@code POST /loss-prevention/cameras}. */
    public record CreateCameraInput(
            @NotBlank(message = "agentId is required") String agentId,
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @NotNull(message = "streamAlias is required") String streamAlias,
            Boolean enabled,
            Boolean analysed,
            @Size(max = 1000, message = "notes must hold at most 1000 characters") String notes) {}

    /** {@code PUT /loss-prevention/cameras/:id}: a camera stays with its agent. */
    public record UpdateCameraInput(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @NotNull(message = "streamAlias is required") String streamAlias,
            Boolean enabled,
            Boolean analysed,
            @Size(max = 1000, message = "notes must hold at most 1000 characters") String notes) {}

    /** {@code POST /loss-prevention/zones}. */
    public record CreateZoneInput(
            @NotBlank(message = "cameraId is required") String cameraId,
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @NotNull(message = "zoneType is required") String zoneType,
            @NotNull(message = "polygon is required") JsonNode polygon,
            String inSide,
            String linkedType,
            String linkedId,
            JsonNode params,
            Boolean enabled) {}

    /** {@code PUT /loss-prevention/zones/:id}: everything but the camera and the type. */
    public record UpdateZoneInput(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @NotNull(message = "polygon is required") JsonNode polygon,
            String inSide,
            String linkedType,
            String linkedId,
            JsonNode params,
            Boolean enabled) {}

    // ------------------------------------------------------------------ agent protocol

    /** {@code POST /loss-prevention/agents/enrol}. */
    public record EnrolInput(
            @NotBlank(message = "code is required") @Size(max = 40, message = "code must hold at most 40 characters")
            String code,
            @Size(max = 200, message = "hostname must hold at most 200 characters") String hostname,
            @Size(max = 50, message = "agentVersion must hold at most 50 characters") String agentVersion) {}

    /** One camera's state in a heartbeat. */
    public record CameraStatus(
            @NotBlank(message = "cameras[].cameraId is required") String cameraId,
            @NotNull(message = "cameras[].streamState is required") String streamState,
            BigDecimal fps,
            String lastFrameAt,
            String observedThrough) {}

    /** {@code POST /loss-prevention/agents/heartbeat}. */
    public record HeartbeatInput(
            @Size(max = 50, message = "agentVersion must hold at most 50 characters") String agentVersion,
            @Size(max = 100, message = "modelVersion must hold at most 100 characters") String modelVersion,
            Integer configVersion,
            Long clockDriftMs,
            Long diskFreeBytes,
            Long diskTotalBytes,
            Integer queueLength,
            String observedThrough,
            @Size(max = LpVisionCatalog.MAX_CAMERAS_PER_HEARTBEAT, message = "cameras must hold at most 64 cameras")
            List<@Valid @NotNull(message = "a camera must not be null") CameraStatus> cameras) {}

    /** {@code POST /loss-prevention/agents/commands/:id/result}. */
    public record CommandResultInput(
            @NotNull(message = "status is required") String status,
            @Size(max = 2000, message = "message must hold at most 2000 characters") String message,
            @Size(max = 40, message = "code must hold at most 40 characters") String code) {}

    /** One clip kept by an agent. */
    public record ClipInput(
            @NotBlank(message = "clips[].clipId is required") @Size(max = 200, message = "clips[].clipId must hold at"
                    + " most 200 characters") String clipId,
            @NotBlank(message = "clips[].cameraId is required") String cameraId,
            String zoneId,
            @NotNull(message = "clips[].startedAt is required") String startedAt,
            @NotNull(message = "clips[].endedAt is required") String endedAt,
            @NotNull(message = "clips[].sha256 is required") String sha256,
            @NotNull(message = "clips[].byteSize is required") Long byteSize,
            @NotNull(message = "clips[].contentType is required") String contentType,
            @Size(max = 50, message = "clips[].eventIds must hold at most 50 ids") List<String> eventIds) {}

    /** {@code POST /loss-prevention/agents/clips}. */
    public record ClipsInput(
            @NotNull(message = "clips is required")
            @Size(min = 1, max = LpVisionCatalog.MAX_CLIPS_PER_CALL, message = "clips must hold between 1 and 100"
                    + " clips")
            List<@Valid @NotNull(message = "a clip must not be null") ClipInput> clips) {}

    // ------------------------------------------------------------------ evidence

    /** {@code POST /loss-prevention/evidence/:id/request}. */
    public record EvidenceRequestInput(
            String caseId,
            @Size(max = 1000, message = "reason must hold at most 1000 characters") String reason) {}

    /** {@code POST /loss-prevention/evidence/range}: a clip cut from the agent's buffer. */
    public record EvidenceRangeInput(
            @NotBlank(message = "cameraId is required") String cameraId,
            @NotBlank(message = "from is required") String from,
            @NotBlank(message = "to is required") String to,
            String caseId,
            @Size(max = 1000, message = "reason must hold at most 1000 characters") String reason) {}

    /**
     * {@code POST /loss-prevention/evidence/still} (LP1c): a still of one camera at a moment, taken by the edge box
     * from its buffer. {@code at} absent = now.
     */
    public record EvidenceStillInput(
            @NotBlank(message = "cameraId is required") String cameraId,
            String at,
            String caseId,
            @Size(max = 1000, message = "reason must hold at most 1000 characters") String reason) {}

    /** Filters of the evidence list. */
    public record EvidenceQuery(String caseId, String cameraId, String zoneId, String state, String from, String to,
            Integer page, Integer pageSize, String kind) {}

    /**
     * {@code GET /loss-prevention/footage} (LP1c): the cameras and evidence around a POS sale ({@code invoiceId}), a
     * ledger record ({@code eventId}) or a branch and moment ({@code branchId} + {@code at}).
     */
    public record FootageQuery(String invoiceId, String eventId, String branchId, String at) {}
}
