package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request bodies of fleet 1.5 (fuel &amp; energy), validated with Bean Validation at the controller. PATCH bodies treat
 * {@code null} as "unchanged"; a nullable field is cleared by naming it in {@code clear}.
 */
public final class FuelCommands {

    private FuelCommands() {}

    /**
     * Record a fuel log. {@code source = station}: bought outside, paid {@code cash} (from {@code paymentAccountId}),
     * by {@code fuel_card} ({@code fuelCardId}) or on supplier {@code credit} ({@code supplierId}); enter the amount
     * or the unit price. {@code source = tank}: issued from site tank {@code tankId} at stock cost. {@code post: true}
     * posts it at once (needs {@code fleet_fuel:post}).
     */
    public record FuelLogInput(
            @NotBlank String vehicleId,
            Instant fuelledAt,
            @NotBlank @OneOf({"station", "tank"}) String source,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal litres,
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            Boolean fullTank,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @OneOf({"diesel", "petrol", "lpg", "cng", "electric", "other"}) String fuelType,
            String supplierId,
            @Size(max = 120) String stationName,
            @OneOf({"cash", "fuel_card", "credit"}) String paymentMethod,
            String paymentAccountId,
            String fuelCardId,
            String tankId,
            @Size(max = 80) String receiptNumber,
            String employeeId,
            String costCenterId,
            @Size(max = 2000) String notes,
            Boolean post) {}

    /** Fields of a draft fuel log that a PATCH may clear. */
    public static final List<String> LOG_CLEARABLE = List.of("unitPrice", "amount", "odometer", "hours", "supplierId",
            "stationName", "paymentAccountId", "fuelCardId", "receiptNumber", "employeeId", "costCenterId", "notes");

    /** Edit a draft fuel log (posted logs are reversed, never edited). */
    public record FuelLogPatch(
            String vehicleId,
            Instant fuelledAt,
            @OneOf({"station", "tank"}) String source,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal litres,
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            Boolean fullTank,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @OneOf({"diesel", "petrol", "lpg", "cng", "electric", "other"}) String fuelType,
            String supplierId,
            @Size(max = 120) String stationName,
            @OneOf({"cash", "fuel_card", "credit"}) String paymentMethod,
            String paymentAccountId,
            String fuelCardId,
            String tankId,
            @Size(max = 80) String receiptNumber,
            String employeeId,
            String costCenterId,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Reverse a posted fuel log (its postings and stock movement). */
    public record ReverseInput(@NotBlank @Size(max = 500) String reason) {}

    /** Acknowledge an anomaly after review. */
    public record AcknowledgeInput(@NotBlank @Size(max = 1000) String note) {}

    /** Register a fuel card; only the masked number is kept. */
    public record FuelCardInput(
            @NotBlank @Size(max = 40) String cardNumber,
            @Size(max = 80) String label,
            String providerPartnerId,
            String vehicleId,
            String employeeId,
            @OneOf({"active", "blocked", "expired", "cancelled"}) String status,
            LocalDate expiresOn,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal limitAmount,
            @OneOf({"daily", "weekly", "monthly"}) String limitPeriod,
            @Size(max = 2000) String notes) {}

    /** Fields of a fuel card that a PATCH may clear. */
    public static final List<String> CARD_CLEARABLE = List.of("label", "providerPartnerId", "vehicleId", "employeeId",
            "expiresOn", "limitAmount", "notes");

    /** Edit a fuel card (the number cannot change: cancel the card and register the new one). */
    public record FuelCardPatch(
            @Size(max = 80) String label,
            String providerPartnerId,
            String vehicleId,
            String employeeId,
            @OneOf({"active", "blocked", "expired", "cancelled"}) String status,
            LocalDate expiresOn,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal limitAmount,
            @OneOf({"daily", "weekly", "monthly"}) String limitPeriod,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Register a site tank / bowser on an inventory location holding a fuel product. Blank code → TANK-001... */
    public record TankInput(
            @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"tank", "bowser"}) String kind,
            @NotBlank String locationId,
            @NotBlank String productId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal capacityL,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal reorderLevelL,
            String branchId,
            String bowserVehicleId,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    /** Fields of a tank that a PATCH may clear. */
    public static final List<String> TANK_CLEARABLE = List.of("reorderLevelL", "branchId", "bowserVehicleId", "notes");

    /** Edit a tank (its location and product are fixed once registered). */
    public record TankPatch(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 120) String name,
            @OneOf({"tank", "bowser"}) String kind,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal capacityL,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal reorderLevelL,
            String branchId,
            String bowserVehicleId,
            Boolean isActive,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** A dip reading (measured litres); {@code dippedAt} defaults to now. */
    public record DipInput(
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal dipLitres,
            Instant dippedAt,
            @Size(max = 1000) String notes) {}

    /** Reconcile a dip: adjust stock by its variance (a reason is required when there is one). */
    public record ReconcileInput(@Size(max = 500) String reason) {}

    /** Org thresholds (null = unchanged). */
    public record SettingsInput(
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal overfillTolerancePct,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal dipApprovalLitres,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal dipApprovalPct) {}

    /** Consumption band of a vehicle class (PUT: every bound is replaced; null = not checked). */
    public record BandInput(
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal minLPer100Km,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal maxLPer100Km,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal minLPerHour,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal maxLPerHour) {}

    /** Fuel log list filters ({@code anomaly}: {@code any}, {@code open} or one anomaly type). */
    public record FuelLogQuery(String vehicleId, String source, String status, String anomaly, String tankId,
            String fuelCardId, LocalDate dateFrom, LocalDate dateTo, String search, Integer page, Integer pageSize,
            String sortBy, String sortOrder) {}
}
