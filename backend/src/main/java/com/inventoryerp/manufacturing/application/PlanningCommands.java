package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request bodies of the advanced planning routes (Track C 2.5, ADR-S017); they double as the service inputs. */
public final class PlanningCommands {

    private PlanningCommands() {}

    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String DATE_MSG = "must be a date (YYYY-MM-DD)";

    /**
     * {@code POST /manufacturing/planning/plans/from-demand}: a draft plan for what stock and open orders cannot cover.
     */
    public record PlanFromDemand(
            @Size(max = 200) String name,
            @Pattern(regexp = DATE, message = DATE_MSG) String dateFrom,
            @NotNull(message = DATE_MSG) @Pattern(regexp = DATE, message = DATE_MSG) String dateTo,
            @NotBlank String locationId,
            Boolean includeForecast,
            @Min(1) @Max(52) Integer forecastWeeks,
            Boolean includeStockPolicy,
            List<@NotBlank String> productIds,
            @Size(max = 1000) String notes,
            @Pattern(regexp = "run_rate|smoothed", message = "must be run_rate or smoothed") String forecastMethod) {}

    /** {@code PUT /manufacturing/planning/orders/{id}}: due date and priority of an order. */
    public record OrderPlanningInput(
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String dueAt,
            @Min(1) @Max(4) Integer priority,
            @Size(max = 500) String priorityReason) {}

    /** {@code POST /manufacturing/planning/orders/{id}/schedule-backward}. */
    public record BackwardInput(@Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String dueAt) {}

    /** {@code POST /manufacturing/planning/schedule-open-orders}: every open order with a due date, by priority. */
    public record ScheduleOpenInput(
            @Pattern(regexp = DATE, message = DATE_MSG) String dueFrom,
            @Pattern(regexp = DATE, message = DATE_MSG) String dueTo) {}

    /** {@code POST /manufacturing/planning/work-orders/{id}/reschedule}: a Gantt drag. */
    public record RescheduleInput(
            @NotNull(message = MfgCommands.DATE_MSG)
            @Pattern(regexp = MfgCommands.ISO_DATE, message = MfgCommands.DATE_MSG) String startAt,
            String workCenterId) {}

    /** {@code POST} and {@code PUT /manufacturing/planning/shift-plans}. */
    public record ShiftPlanInput(
            @NotNull(message = DATE_MSG) @Pattern(regexp = DATE, message = DATE_MSG) String shiftDate,
            @NotBlank String shiftId,
            @NotBlank String workCenterId,
            String productionOrderId,
            String workOrderId,
            String productId,
            @NotNull @Positive @Digits(integer = 14, fraction = 6) BigDecimal targetQty,
            @Min(0) Integer crewSize,
            @Size(max = 1000) String notes) {}

    /** {@code POST} and {@code PUT /manufacturing/planning/targets}. */
    public record TargetInput(
            @NotNull @OneOf({"work_center", "crushing_plant", "product"}) String scope,
            String workCenterId,
            String plantId,
            String productId,
            @NotNull(message = DATE_MSG) @Pattern(regexp = DATE, message = DATE_MSG) String periodFrom,
            @NotNull(message = DATE_MSG) @Pattern(regexp = DATE, message = DATE_MSG) String periodTo,
            @NotNull @Positive @DecimalMin("0") @Digits(integer = 14, fraction = 6) BigDecimal targetQty,
            @Size(max = 1000) String notes) {}
}
