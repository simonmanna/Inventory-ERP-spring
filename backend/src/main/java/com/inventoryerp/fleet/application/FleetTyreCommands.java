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
 * Request bodies of the tyre register (fleet 1.7). Meter values are the raw odometer (km) / hour meter as read; blank
 * means "the vehicle's current meters". Times default to now.
 */
public final class FleetTyreCommands {

    private FleetTyreCommands() {}

    /** Register a tyre (bought into stock, or a stocked inventory product issued on first fitment). */
    public record TyreInput(
            @NotBlank @Size(max = 60) String serialNumber,
            @Size(max = 80) String brand,
            @Size(max = 80) String model,
            @Size(max = 40) String size,
            @OneOf({"steer", "drive", "trailer", "all_position", "offroad", "other"}) String tyreType,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal purchaseCost,
            String supplierPartnerId,
            LocalDate purchasedOn,
            @Size(max = 80) String invoiceReference,
            String productId,
            String stockLocationId,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("99.9") @Digits(integer = 3, fraction = 1)
            BigDecimal newTreadDepthMm,
            @DecimalMin("0") @DecimalMax("99.9") @Digits(integer = 3, fraction = 1) BigDecimal minTreadDepthMm,
            @Size(max = 2000) String notes) {}

    /** Fields of a tyre that a PATCH may clear. */
    public static final List<String> TYRE_CLEARABLE = List.of("brand", "model", "size", "purchaseCost",
            "supplierPartnerId", "purchasedOn", "invoiceReference", "productId", "stockLocationId", "newTreadDepthMm",
            "notes");

    /** Edit a tyre's master data ({@code null} = unchanged, {@code clear} empties fields). */
    public record TyrePatch(
            @Size(min = 1, max = 60) String serialNumber,
            @Size(max = 80) String brand,
            @Size(max = 80) String model,
            @Size(max = 40) String size,
            @OneOf({"steer", "drive", "trailer", "all_position", "offroad", "other"}) String tyreType,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal purchaseCost,
            String supplierPartnerId,
            LocalDate purchasedOn,
            @Size(max = 80) String invoiceReference,
            String productId,
            String stockLocationId,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("99.9") @Digits(integer = 3, fraction = 1)
            BigDecimal newTreadDepthMm,
            @DecimalMin("0") @DecimalMax("99.9") @Digits(integer = 3, fraction = 1) BigDecimal minTreadDepthMm,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Fit a tyre to a wheel position of a vehicle. */
    public record FitInput(
            @NotBlank String vehicleId,
            @NotBlank @Size(max = 10) String position,
            Instant at,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @DecimalMin("0") @DecimalMax("99.9") @Digits(integer = 3, fraction = 1) BigDecimal treadDepthMm,
            @Size(max = 500) String notes) {}

    /** Move a fitted tyre to another position of the same vehicle (swaps with the tyre already there). */
    public record RotateInput(
            @NotBlank @Size(max = 10) String position,
            Instant at,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @Size(max = 500) String notes) {}

    /** Take a tyre off its vehicle; {@code scrap = true} scraps it at once. */
    public record RemoveInput(
            @NotBlank @Size(max = 500) String reason,
            Instant at,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @DecimalMin("0") @DecimalMax("99.9") @Digits(integer = 3, fraction = 1) BigDecimal treadDepthMm,
            Boolean scrap,
            @Size(max = 500) String notes) {}

    /** Record a retread of a removed tyre: its cost adds to the tyre's cost, the tread starts again. */
    public record RetreadInput(
            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal cost,
            String partnerId,
            Instant at,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99.9") @Digits(integer = 3, fraction = 1)
            BigDecimal newTreadDepthMm,
            @Size(max = 500) String notes) {}

    /** Scrap a tyre that is not fitted. */
    public record ScrapInput(@NotBlank @Size(max = 500) String reason, Instant at) {}

    /** Tread-depth (and optional pressure) reading. */
    public record TreadReadingInput(
            @NotNull @DecimalMin("0") @DecimalMax("99.9") @Digits(integer = 3, fraction = 1) BigDecimal treadDepthMm,
            @DecimalMin("0") @DecimalMax("9999.9") @Digits(integer = 4, fraction = 1) BigDecimal pressureKpa,
            Instant readingAt,
            @Size(max = 500) String notes) {}

    /** Tyre list filters. */
    public record TyreQuery(String search, String status, String vehicleId, String tyreType, Integer page,
            Integer pageSize, String sortBy, String sortOrder) {}
}
