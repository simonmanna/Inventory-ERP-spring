package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request bodies of the manufacturing costing routes (Track C 2.8, ADR-S018). */
public final class CostingCommands {

    private CostingCommands() {}

    /** {@code PUT /manufacturing/costing/rates/{workCenterId}}: replaces every field. */
    public record RateInput(
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal labourRatePerHour,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal machineRatePerHour,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal overheadRatePerHour,
            @OneOf({"machine_hours", "labour_hours"}) String overheadBasis,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal energyRatePerUnit,
            @Size(max = 50) String energyMeterCode,
            String fleetVehicleId,
            @Min(1) @Max(730) Integer fleetCostDays,
            String creditAccountId,
            @Size(max = 1000) String notes) {}
}
