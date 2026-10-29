package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

/**
 * Request bodies of fleet maintenance (1.6), validated with Bean Validation at the controller. PATCH bodies treat
 * {@code null} as "unchanged"; nullable fields are cleared by naming them in {@code clear}.
 */
public final class MaintenanceCommands {

    private MaintenanceCommands() {}

    // ------------------------------------------------------------------ service templates / schedules

    /** Service template of a vehicle class; at least one interval. */
    public record ServiceTemplateInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            String categoryId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalKm,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalHours,
            @Min(1) @Max(3650) Integer intervalDays,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadHours,
            @Min(0) @Max(365) Integer leadDays,
            @Size(max = 100) List<@NotBlank @Size(max = 300) String> tasks,
            Boolean isActive) {}

    public static final List<String> TEMPLATE_CLEARABLE = List.of("description", "categoryId", "intervalKm",
            "intervalHours", "intervalDays");

    public record ServiceTemplatePatch(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            String categoryId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalKm,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalHours,
            @Min(1) @Max(3650) Integer intervalDays,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadHours,
            @Min(0) @Max(365) Integer leadDays,
            @Size(max = 100) List<@NotBlank @Size(max = 300) String> tasks,
            Boolean isActive,
            List<String> clear) {}

    /** Apply a template: to the listed units, else to every live unit of {@code categoryId} / the template's class. */
    public record ApplyTemplateInput(
            @Size(max = 500) List<@NotBlank String> vehicleIds,
            String categoryId) {}

    /**
     * Schedule of one unit: from a template ({@code templateId}; its intervals are the defaults) or custom. The
     * baseline ({@code lastDone*}) defaults to today and the unit's current lifetime meters.
     */
    public record ScheduleInput(
            String templateId,
            @Size(max = 120) String name,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalKm,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalHours,
            @Min(1) @Max(3650) Integer intervalDays,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadHours,
            @Min(0) @Max(365) Integer leadDays,
            @Size(max = 100) List<@NotBlank @Size(max = 300) String> tasks,
            LocalDate lastDoneOn,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal lastDoneKm,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal lastDoneHours,
            @Size(max = 2000) String notes) {}

    public static final List<String> SCHEDULE_CLEARABLE = List.of("intervalKm", "intervalHours", "intervalDays",
            "lastDoneKm", "lastDoneHours", "notes");

    public record SchedulePatch(
            @Size(min = 1, max = 120) String name,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalKm,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 1) BigDecimal intervalHours,
            @Min(1) @Max(3650) Integer intervalDays,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal leadHours,
            @Min(0) @Max(365) Integer leadDays,
            @Size(max = 100) List<@NotBlank @Size(max = 300) String> tasks,
            LocalDate lastDoneOn,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal lastDoneKm,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal lastDoneHours,
            @OneOf({"active", "paused"}) String status,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Due list filters: {@code state} = attention (due + overdue, default), due, overdue, ok or all. */
    public record DueQuery(String state, String vehicleId, String categoryId, String branchId, LocalDate asOf,
            Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ work orders

    /** New work order; {@code draft = true} keeps it as a draft, else it is opened at once. */
    public record WorkOrderInput(
            @NotBlank String vehicleId,
            @NotBlank @OneOf({"preventive", "corrective", "breakdown", "inspection_defect"}) String type,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            LocalDate dueOn,
            String assignedEmployeeId,
            @Size(max = 100) List<@NotBlank @Size(max = 300) String> tasks,
            @Size(max = 50) List<@NotBlank String> defectIds,
            String downtimeId,
            Boolean draft) {}

    public static final List<String> WORK_ORDER_CLEARABLE = List.of("description", "dueOn", "assignedEmployeeId");

    public record WorkOrderPatch(
            @Size(min = 1, max = 200) String title,
            @Size(max = 4000) String description,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            LocalDate dueOn,
            String assignedEmployeeId,
            List<String> clear) {}

    /** Work order list filters. */
    public record WorkOrderQuery(String search, String vehicleId, String status, String type, String priority,
            LocalDate from, LocalDate to, Integer page, Integer pageSize, String sortBy, String sortOrder) {}

    /** Complete: optional completion meters (lifetime readings, recorded on the unit), resolution. */
    public record CompleteInput(
            Instant completedAt,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @Size(max = 4000) String resolution) {}

    public record ReasonInput(@NotBlank @Size(max = 500) String reason) {}

    public record TaskInput(@NotBlank @Size(max = 300) String description) {}

    public record TaskPatch(
            @Size(min = 1, max = 300) String description,
            @OneOf({"pending", "done", "skipped"}) String status,
            @Size(max = 2000) String notes) {}

    /** Issue a spare part from stock (base unit) onto the work order. */
    public record PartIssueInput(
            @NotBlank String productId,
            String variantId,
            @NotBlank String locationId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,
            @Size(max = 500) String description) {}

    /** Return unused quantity of an issued part to stock. */
    public record PartReturnInput(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
            BigDecimal quantity,
            @Size(max = 500) String description) {}

    /** Labour: hr employee, hours and rate (default: the employee's hourly rate). */
    public record LabourInput(
            @NotBlank String employeeId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") @Digits(integer = 4,
                    fraction = 2) BigDecimal hours,
            @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal rate,
            LocalDate workDate,
            @Size(max = 500) String description) {}

    /** External workshop / supplier bill: becomes a draft vendor bill in AP and accrues the cost on the unit. */
    public record ExternalBillInput(
            @NotBlank String partnerId,
            @NotBlank @Size(max = 80) String supplierReference,
            LocalDate billDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String description) {}

    // ------------------------------------------------------------------ downtime

    /** Record downtime: unplanned (breakdown) moves the unit to breakdown, planned to in workshop. */
    public record DowntimeInput(
            @NotBlank String vehicleId,
            @OneOf({"planned", "unplanned"}) String kind,
            @NotBlank @OneOf({"engine", "transmission", "hydraulic", "electrical", "brakes", "tyres", "suspension",
                    "body", "accident", "preventive_service", "inspection_defect", "waiting_parts", "operator_error",
                    "other"}) String reasonCode,
            @Size(max = 2000) String description,
            Instant startedAt,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            Boolean createWorkOrder) {}

    public record DowntimeEnd(Instant endedAt, @Size(max = 2000) String notes) {}

    public record DowntimePatch(
            @OneOf({"planned", "unplanned"}) String kind,
            @OneOf({"engine", "transmission", "hydraulic", "electrical", "brakes", "tyres", "suspension", "body",
                    "accident", "preventive_service", "inspection_defect", "waiting_parts", "operator_error", "other"})
            String reasonCode,
            @Size(max = 2000) String description,
            Instant startedAt,
            Instant endedAt) {}

    public record DowntimeQuery(String vehicleId, String kind, String reasonCode, Boolean open, LocalDate from,
            LocalDate to, Integer page, Integer pageSize) {}

    /** Reliability report: period (default the last 30 days), grouping and filters. */
    public record ReliabilityQuery(LocalDate from, LocalDate to, String groupBy, String vehicleId, String categoryId,
            String branchId) {}

    // ------------------------------------------------------------------ inspections / defects

    public record ChecklistItem(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 200) String label,
            @Size(max = 80) String section,
            @OneOf({"minor", "major", "critical"}) String severity) {}

    public record InspectionTemplateInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"pre_trip", "post_trip", "periodic"}) String kind,
            String categoryId,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull ChecklistItem> items,
            Boolean isActive) {}

    public record InspectionTemplatePatch(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 120) String name,
            @OneOf({"pre_trip", "post_trip", "periodic"}) String kind,
            String categoryId,
            @Size(min = 1, max = 200) List<@Valid @NotNull ChecklistItem> items,
            Boolean isActive,
            List<String> clear) {}

    public record ItemResult(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @OneOf({"pass", "fail", "na"}) String result,
            @OneOf({"minor", "major", "critical"}) String severity,
            @Size(max = 1000) String notes) {}

    /** Record an inspection: one result per checklist item; failed items raise defects. */
    public record InspectionInput(
            @NotBlank String vehicleId,
            @NotBlank String templateId,
            String inspectorEmployeeId,
            Instant inspectedAt,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull ItemResult> items) {}

    public record InspectionQuery(String vehicleId, String result, String kind, LocalDate from, LocalDate to,
            Integer page, Integer pageSize) {}

    /** A defect reported directly (driver, mechanic). */
    public record DefectInput(
            @NotBlank String vehicleId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotBlank @OneOf({"minor", "major", "critical"}) String severity,
            String reportedByEmployeeId) {}

    /** Raise a corrective work order for a defect, or attach it to a live one ({@code workOrderId}). */
    public record DefectWorkOrderInput(String workOrderId, @Size(max = 200) String title) {}

    /** Close a defect without a work order: resolved (fixed on the spot) or dismissed (not a defect). */
    public record DefectCloseInput(
            @NotBlank @OneOf({"resolved", "dismissed"}) String outcome,
            @NotBlank @Size(max = 2000) String resolution) {}

    public record DefectQuery(String vehicleId, String status, String severity, Integer page, Integer pageSize) {}
}
