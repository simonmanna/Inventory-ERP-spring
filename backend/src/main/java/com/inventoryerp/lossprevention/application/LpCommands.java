package com.inventoryerp.lossprevention.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Inputs of the loss-prevention API. */
public final class LpCommands {

    private LpCommands() {}

    /** Largest batch {@code POST /loss-prevention/events} accepts. */
    public static final int MAX_BATCH = 500;

    /** {@code POST /loss-prevention/events}: a batch of events. Each event is accepted or refused on its own. */
    public record IngestRequest(
            @NotNull(message = "events is required")
            @Size(min = 1, max = MAX_BATCH, message = "events must hold between 1 and 500 events")
            List<@NotNull(message = "an event must not be null") EventInput> events) {}

    /**
     * One event in the canonical envelope (ADR-S077 §3). Nothing is validated here: {@link LpEventValidator} checks
     * each event, so one bad event is refused without failing its batch. The organization is never part of the body;
     * it is the caller's.
     */
    public record EventInput(
            String eventId,
            String source,
            String sourceEventId,
            String branchId,
            String deviceId,
            String actorId,
            String transactionId,
            String orderId,
            String ticketId,
            String lineId,
            String sessionId,
            String businessDate,
            String occurredAt,
            Integer schemaVersion,
            String type,
            JsonNode payload,
            String evidenceLevel,
            String evidenceSource,
            String observationStatus,
            BigDecimal confidence) {}

    /** {@code PUT /loss-prevention/rules/:code}: the whole rule. A parameter left out keeps its default. */
    public record RuleInput(
            @NotNull(message = "enabled is required") Boolean enabled,
            @NotNull(message = "severity is required") String severity,
            java.util.Map<String, Object> params) {}

    /** {@code PUT /loss-prevention/settings}: the organization's values, or one branch's when branchId is given. */
    public record SettingsInput(String branchId, java.util.Map<String, Object> values) {}

    /** {@code POST /loss-prevention/runs/replay}: reconcile again every window of an engine in a date range. */
    public record ReplayInput(
            @NotNull(message = "engine is required") String engine,
            @NotNull(message = "from is required") String from,
            @NotNull(message = "to is required") String to,
            String branchId) {}

    /** Filters of the run list. */
    public record RunQuery(String engine, String branchId, String from, String to, String windowId, Integer page,
            Integer pageSize) {}

    /** Filters of the case list. */
    public record CaseQuery(String state, String outcome, String engine, String ruleCode, String branchId,
            String subjectId, String assigneeId, String severity, String from, String to, Integer page,
            Integer pageSize) {}

    public record AssignInput(@Size(max = 100) String assigneeId) {}

    public record NoteInput(
            @NotNull(message = "note is required") @Size(min = 1, max = 4000, message = "note must hold 1 to 4000"
                    + " characters") String note) {}

    public record EscalateInput(
            @NotNull(message = "reason is required") @Size(min = 1, max = 4000, message = "reason must hold 1 to 4000"
                    + " characters") String reason) {}

    public record CloseInput(
            @NotNull(message = "outcome is required") String outcome,
            String causeCode,
            @Size(max = 4000) String note) {}

    /** {@code POST /loss-prevention/insights/recompute}: the days to compute again (LP6a). */
    public record InsightsRecomputeInput(
            @NotNull(message = "from is required") @Size(max = 10) String from,
            @NotNull(message = "to is required") @Size(max = 10) String to,
            @Size(max = 100) String branchId) {}

    /** Filters of the insights (LP6a). {@code groupBy} is used by the staff view only. */
    public record InsightsQuery(String from, String to, String branchId, String groupBy) {}

    /** Filters of the reports. */
    public record ReportQuery(String from, String to, String branchId) {}

    /** {@code GET /loss-prevention/research/activities} (LP7). {@code verdict}: of the latest review, or unreviewed. */
    public record ActivityQuery(String from, String to, String branchId, String zoneId, String activity,
            String recognizer, String verdict, Integer page, Integer pageSize) {}

    /** {@code POST /loss-prevention/research/activities/:eventId/review} (LP7). Checked by the service. */
    public record ActivityReviewInput(String verdict, String label, String note) {}

    /** Filters of the ledger list. */
    public record EventQuery(String type, String source, String branchId, String deviceId, String actorId,
            String transactionId, String orderId, String ticketId, String lineId, String sessionId, String from,
            String to, Integer page, Integer pageSize) {}
}
