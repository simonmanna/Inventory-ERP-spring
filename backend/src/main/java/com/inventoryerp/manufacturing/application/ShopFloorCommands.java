package com.inventoryerp.manufacturing.application;

import com.inventoryerp.manufacturing.application.StageCommands.OutputQty;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request bodies of the shop-floor routes (ADR-S015, Phase 2.7); they double as service inputs. */
public final class ShopFloorCommands {

    private ShopFloorCommands() {}

    static final String REQ = "should not be empty";
    static final String MIN0 = "must not be less than 0";
    static final String DATE = MfgCommands.ISO_DATE;
    static final String DATE_MSG = MfgCommands.DATE_MSG;

    // ------------------------------------------------------------------ set-up

    /** Production shift (minutes after local midnight; end <= start = ends the next day). */
    public record ShiftInput(
            @NotNull(message = "code " + REQ) @Size(min = 1, max = 20, message = "code must be 1-20 characters") String code,
            @NotNull(message = "name " + REQ) @Size(min = 1, max = 80, message = "name must be 1-80 characters") String name,
            @NotNull(message = "startMinute must be between 0 and 1439") @Min(value = 0, message = "startMinute must be"
                    + " between 0 and 1439") @Max(value = 1439, message = "startMinute must be between 0 and 1439")
            Integer startMinute,
            @NotNull(message = "endMinute must be between 1 and 1440") @Min(value = 1, message = "endMinute must be"
                    + " between 1 and 1440") @Max(value = 1440, message = "endMinute must be between 1 and 1440")
            Integer endMinute,
            @Min(value = 0, message = "breakMinutes " + MIN0) Integer breakMinutes,
            Boolean isActive,
            Integer sortOrder) {}

    /** Partial shift update. */
    public record ShiftPatch(
            @Size(min = 1, max = 80, message = "name must be 1-80 characters") String name,
            @Min(value = 0, message = "startMinute must be between 0 and 1439")
            @Max(value = 1439, message = "startMinute must be between 0 and 1439") Integer startMinute,
            @Min(value = 1, message = "endMinute must be between 1 and 1440")
            @Max(value = 1440, message = "endMinute must be between 1 and 1440") Integer endMinute,
            @Min(value = 0, message = "breakMinutes " + MIN0) Integer breakMinutes,
            Boolean isActive,
            Integer sortOrder) {}

    /** Downtime reason. */
    public record ReasonInput(
            @NotNull(message = "code " + REQ) @Size(min = 1, max = 30, message = "code must be 1-30 characters") String code,
            @NotNull(message = "name " + REQ) @Size(min = 1, max = 80, message = "name must be 1-80 characters") String name,
            @OneOf({"breakdown", "setup", "material", "quality", "operator", "utility", "minor_stop", "planned", "other"})
            String category,
            Boolean isPlanned,
            Boolean isActive,
            Integer sortOrder) {}

    /** Partial reason update (the code is immutable — it is stored on events). */
    public record ReasonPatch(
            @Size(min = 1, max = 80, message = "name must be 1-80 characters") String name,
            @OneOf({"breakdown", "setup", "material", "quality", "operator", "utility", "minor_stop", "planned", "other"})
            String category,
            Boolean isPlanned,
            Boolean isActive,
            Integer sortOrder) {}

    /** {@code PUT /shop-floor/work-center-profiles/{workCenterId}}. */
    public record ProfileInput(
            @Positive(message = "idealRatePerHour must be a positive number") BigDecimal idealRatePerHour,
            @Size(max = 20, message = "rateUom must be at most 20 characters") String rateUom,
            @Size(max = 60, message = "machineCode must be at most 60 characters") String machineCode,
            List<@NotNull @Size(min = 1, max = 30, message = "meter codes must be 1-30 characters") String> meterCodes) {}

    // ------------------------------------------------------------------ terminal

    /** Pause (stop) a running work order: the reason opens a downtime event. */
    public record PauseInput(
            @NotNull(message = "reasonCode " + REQ) @Size(min = 1, message = "reasonCode " + REQ) String reasonCode,
            String notes) {}

    /** Finish the work order's operation (the counts go through production reports). */
    public record CompleteInput(String notes) {}

    /** Clock on (the caller unless {@code userId} is given; an open entry elsewhere is closed first). */
    public record ClockOnInput(
            @NotNull(message = "workCenterId " + REQ) @Size(min = 1, message = "workCenterId " + REQ) String workCenterId,
            String workOrderId,
            String userId,
            @Size(max = 30, message = "crewCode must be at most 30 characters") String crewCode,
            @Size(max = 40, message = "role must be at most 40 characters") String role,
            String notes) {}

    public record ClockOffInput(String notes) {}

    /** Output / scrap of a work order (multi-stage: posts the stage output; single-leg: counts on the work order). */
    public record ProductionReportInput(
            @NotNull(message = "workOrderId " + REQ) @Size(min = 1, message = "workOrderId " + REQ) String workOrderId,
            @NotNull(message = "qtyGood " + MIN0) @DecimalMin(value = "0", message = "qtyGood " + MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = "qtyScrap " + MIN0) BigDecimal qtyScrap,
            String scrapReasonCode,
            Boolean completesStage,
            List<@Valid @NotNull OutputQty> outputs,
            String batchNumber,
            @Pattern(regexp = DATE, message = DATE_MSG) String reportedAt,
            String notes) {}

    /** Confirm a machine draft; the operator may correct the counts and must say which work order it is for. */
    public record ConfirmProductionInput(
            String workOrderId,
            @DecimalMin(value = "0", message = "qtyGood " + MIN0) BigDecimal qtyGood,
            @DecimalMin(value = "0", message = "qtyScrap " + MIN0) BigDecimal qtyScrap,
            String scrapReasonCode,
            Boolean completesStage,
            String batchNumber,
            String notes) {}

    public record RejectInput(
            @NotNull(message = "reason " + REQ) @Size(min = 1, message = "reason " + REQ) String reason) {}

    /** Log downtime (closed when {@code endedAt} or {@code minutes} is given, else open until ended). */
    public record DowntimeInput(
            @NotNull(message = "workCenterId " + REQ) @Size(min = 1, message = "workCenterId " + REQ) String workCenterId,
            String workOrderId,
            @NotNull(message = "reasonCode " + REQ) @Size(min = 1, message = "reasonCode " + REQ) String reasonCode,
            @Pattern(regexp = DATE, message = DATE_MSG) String startedAt,
            @Pattern(regexp = DATE, message = DATE_MSG) String endedAt,
            @Positive(message = "minutes must be a positive number") Integer minutes,
            String notes) {}

    public record EndDowntimeInput(@Pattern(regexp = DATE, message = DATE_MSG) String endedAt, String notes) {}

    /** Confirm a machine downtime draft with its reason. */
    public record ConfirmDowntimeInput(
            String reasonCode,
            String workOrderId,
            String notes) {}

    public record MeterReadingInput(
            @NotNull(message = "workCenterId " + REQ) @Size(min = 1, message = "workCenterId " + REQ) String workCenterId,
            @NotNull(message = "meterCode " + REQ) @Size(min = 1, max = 30, message = "meterCode must be 1-30 characters")
            String meterCode,
            @NotNull(message = "reading " + MIN0) @DecimalMin(value = "0", message = "reading " + MIN0) BigDecimal reading,
            Boolean isReset,
            @Pattern(regexp = DATE, message = DATE_MSG) String readAt,
            String notes) {}

    // ------------------------------------------------------------------ shift reports

    public record OpenShiftReportInput(
            @NotNull(message = "workCenterId " + REQ) @Size(min = 1, message = "workCenterId " + REQ) String workCenterId,
            @NotNull(message = "shiftId " + REQ) @Size(min = 1, message = "shiftId " + REQ) String shiftId,
            @NotNull(message = "shiftDate " + DATE_MSG) @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "shiftDate "
                    + DATE_MSG) String shiftDate) {}

    public record ShiftReportNote(String notes) {}

    // ------------------------------------------------------------------ machine data

    public record PollInput(
            @NotNull(message = "adapter " + REQ) @Size(min = 1, message = "adapter " + REQ) String adapter,
            List<@NotNull String> workCenterIds,
            @Pattern(regexp = DATE, message = DATE_MSG) String since,
            @Pattern(regexp = DATE, message = DATE_MSG) String until) {}

    /** One pushed event (gateway / MQTT bridge). */
    public record PushEvent(
            String externalId,
            @NotNull(message = "machineCode " + REQ) String machineCode,
            @NotNull(message = "type must be one of the following values: count, weight, downtime, downtime_start,"
                    + " downtime_stop")
            @OneOf({"count", "weight", "downtime", "downtime_start", "downtime_stop"}) String type,
            @NotNull(message = "occurredAt " + DATE_MSG) @Pattern(regexp = DATE, message = "occurredAt " + DATE_MSG)
            String occurredAt,
            @Pattern(regexp = DATE, message = "endedAt " + DATE_MSG) String endedAt,
            @DecimalMin(value = "0", message = "goodQty " + MIN0) BigDecimal goodQty,
            @DecimalMin(value = "0", message = "scrapQty " + MIN0) BigDecimal scrapQty,
            String reasonCode,
            String notes) {}

    public record PushInput(
            @Size(max = 40, message = "source must be at most 40 characters") String source,
            @NotNull(message = "events must be an array") @NotEmpty(message = "events " + REQ)
            @Size(max = 5000, message = "events must contain at most 5000 elements")
            List<@Valid @NotNull PushEvent> events) {}

    public record CsvInput(
            @NotNull(message = "content " + REQ) @Size(min = 1, message = "content " + REQ) String content,
            String filename) {}
}
