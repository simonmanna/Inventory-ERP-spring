package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request bodies of fleet 1.2 (drivers &amp; operators, assignments, check-out / check-in, allowance rules, driver
 * claims), validated with Bean Validation at the controller. PATCH bodies treat {@code null} as "unchanged"; a
 * nullable field is cleared by naming it in {@code clear}.
 */
public final class DriverCommands {

    private DriverCommands() {}

    public static final List<String> DAMAGE_FLAGS = List.of("body", "glass", "tyres", "lights", "mirrors", "interior",
            "mechanical", "leak", "load_body", "other");

    /** Register a driver / operator profile for an hr employee. A blank {@code code} is numbered DRV-0001... */
    public record DriverInput(
            @Size(max = 40) String code,
            @NotBlank String employeeId,
            @OneOf({"driver", "operator", "driver_operator"}) String driverType,
            @Size(max = 40) String licenceNumber,
            @Size(max = 12) List<@NotBlank @Size(max = 10) String> licenceClasses,
            @Size(max = 120) String licenceIssuer,
            LocalDate licenceIssuedOn,
            LocalDate licenceExpiresOn,
            LocalDate medicalExpiresOn,
            @Size(max = 40) String permitNumber,
            LocalDate permitExpiresOn,
            @Min(0) @Max(365) Integer reminderLeadDays,
            @Size(max = 50) List<@NotBlank String> qualifiedCategoryIds,
            @Size(max = 2000) String notes) {}

    /** Fields of a driver profile that a PATCH may clear. */
    public static final List<String> DRIVER_CLEARABLE = List.of("licenceNumber", "licenceIssuer", "licenceIssuedOn",
            "licenceExpiresOn", "medicalExpiresOn", "permitNumber", "permitExpiresOn", "statusReason", "notes");

    /** Edit a profile; {@code qualifiedCategoryIds} (when given) replaces the qualified classes. */
    public record DriverPatch(
            @Size(min = 1, max = 40) String code,
            @OneOf({"driver", "operator", "driver_operator"}) String driverType,
            @Size(max = 40) String licenceNumber,
            @Size(max = 12) List<@NotBlank @Size(max = 10) String> licenceClasses,
            @Size(max = 120) String licenceIssuer,
            LocalDate licenceIssuedOn,
            LocalDate licenceExpiresOn,
            LocalDate medicalExpiresOn,
            @Size(max = 40) String permitNumber,
            LocalDate permitExpiresOn,
            @Min(0) @Max(365) Integer reminderLeadDays,
            @OneOf({"active", "suspended", "inactive"}) String status,
            @Size(max = 500) String statusReason,
            @Size(max = 50) List<@NotBlank String> qualifiedCategoryIds,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Driver list filters; {@code documents} = attention / expired / expiring narrows to drivers with due papers. */
    public record DriverQuery(String search, String status, String driverType, String categoryId, String documents,
            Integer page, Integer pageSize, String sortBy, String sortOrder) {}

    /** Assign a driver to a vehicle: primary (open-ended unless {@code endsAt}) or temporary (needs {@code endsAt}). */
    public record AssignmentInput(
            @NotBlank String vehicleId,
            @NotBlank String driverId,
            @NotBlank @OneOf({"primary", "temporary"}) String kind,
            Instant startsAt,
            Instant endsAt,
            @Size(max = 1000) String notes) {}

    /** End an active assignment ({@code endsAt} defaults to now). */
    public record AssignmentEnd(Instant endsAt, @Size(max = 500) String reason) {}

    /** Check a vehicle out to a driver: meters, fuel level (0–100 %), condition, damage flags. */
    public record CheckOutInput(
            @NotBlank String driverId,
            Instant checkedOutAt,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal fuelLevelPct,
            @OneOf({"good", "fair", "poor"}) String condition,
            @Size(max = 10) List<@NotBlank String> damageFlags,
            @Size(max = 2000) String notes,
            @Size(max = 200) String purpose,
            @Size(max = 64) String tripId) {}

    /** Check the vehicle back in; {@code reportBreakdown} moves it to breakdown with the notes as the reason. */
    public record CheckInInput(
            Instant checkedInAt,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal fuelLevelPct,
            @OneOf({"good", "fair", "poor"}) String condition,
            @Size(max = 10) List<@NotBlank String> damageFlags,
            @Size(max = 2000) String notes,
            Boolean reportBreakdown) {}

    /** Check-out / assignment history filters. */
    public record HistoryQuery(String vehicleId, String driverId, String status, Integer page, Integer pageSize) {}

    /** Create an allowance rule (the amount is in the organization's currency). */
    public record AllowanceRuleInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @OneOf({"per_trip", "per_day", "per_night"}) String basis,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String categoryId,
            @Size(max = 60) String routeZone,
            Boolean isActive,
            @Size(max = 1000) String notes) {}

    public static final List<String> RULE_CLEARABLE = List.of("effectiveTo", "categoryId", "routeZone", "notes");

    /** Edit an allowance rule. */
    public record AllowanceRulePatch(
            @Size(min = 1, max = 120) String name,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String categoryId,
            @Size(max = 60) String routeZone,
            Boolean isActive,
            @Size(max = 1000) String notes,
            List<String> clear) {}

    /**
     * A claim line: {@code allowance} takes the rate of the matching allowance rule for its {@code basis} × the
     * {@code quantity} (trips / days / nights); {@code expense} is an amount the driver paid ({@code expenseType}).
     */
    public record ClaimLineInput(
            @NotBlank @OneOf({"allowance", "expense"}) String lineType,
            @OneOf({"per_trip", "per_day", "per_night"}) String basis,
            @OneOf({"fuel", "toll", "parking", "meals", "lodging", "repairs", "loading", "other"}) String expenseType,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal quantity,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 500) String description) {}

    /** Open a claim (draft), optionally with its lines. {@code tripId} is filled by 1.3 trips (no foreign key yet). */
    public record ClaimInput(
            @NotBlank String driverId,
            String vehicleId,
            @Size(max = 64) String tripId,
            @NotNull LocalDate claimDate,
            @Size(max = 60) String routeZone,
            @Size(max = 500) String description,
            @Size(max = 2000) String notes,
            @Size(max = 50) List<@Valid ClaimLineInput> lines) {}

    public static final List<String> CLAIM_CLEARABLE = List.of("vehicleId", "tripId", "routeZone", "description",
            "notes");

    /** Edit a draft claim; a new date / vehicle / zone re-prices its allowance lines. */
    public record ClaimPatch(
            String vehicleId,
            @Size(max = 64) String tripId,
            LocalDate claimDate,
            @Size(max = 60) String routeZone,
            @Size(max = 500) String description,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Approve / reject a submitted claim. */
    public record ClaimDecision(@Size(max = 1000) String notes) {}

    /** Pay an approved claim through expenses from a cash / bank / mobile-money account. */
    public record ClaimPayment(
            @NotBlank @OneOf({"CASH", "BANK_TRANSFER", "MTN_MOBILE_MONEY", "AIRTEL_MONEY", "CHEQUE"}) String paymentMethod,
            @NotBlank String accountId,
            @Size(max = 120) String reference,
            @Size(max = 500) String notes) {}

    /** Export an approved claim to hr payroll as an earning ({@code earnedOn} defaults to the claim date). */
    public record ClaimPayrollExport(LocalDate earnedOn) {}

    /** Reverse a paid / exported claim back to approved. */
    public record ClaimReversal(@NotBlank @Size(max = 500) String reason) {}

    /** Claim list filters. */
    public record ClaimQuery(String search, String status, String driverId, String vehicleId, String settlement,
            LocalDate from, LocalDate to, Integer page, Integer pageSize) {}
}
