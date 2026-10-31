package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.MaintenanceCommands.ItemResult;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request bodies of the driver web app (DRIVER-1); the signed-in driver fills in who did it. */
public final class DriverAppCommands {

    private DriverAppCommands() {}

    /** A fuel log from the phone; always a draft, reviewed and posted in the office. */
    public record DriverFuelInput(
            @NotBlank String vehicleId,
            Instant fuelledAt,
            @NotBlank @OneOf({"station", "tank"}) String source,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal litres,
            @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            Boolean fullTank,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @Size(max = 120) String stationName,
            @OneOf({"cash", "fuel_card", "credit"}) String paymentMethod,
            String paymentAccountId,
            String fuelCardId,
            String supplierId,
            String tankId,
            @Size(max = 80) String receiptNumber,
            @Size(max = 2000) String notes) {}

    /** A pre-trip checklist from the phone. */
    public record DriverInspectionInput(
            @NotBlank String vehicleId,
            @NotBlank String templateId,
            Instant inspectedAt,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal odometer,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal hours,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull ItemResult> items) {}
}
