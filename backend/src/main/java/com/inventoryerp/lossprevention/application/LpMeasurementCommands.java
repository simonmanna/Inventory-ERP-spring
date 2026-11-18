package com.inventoryerp.lossprevention.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Inputs of the pour meters (LP3). */
public final class LpMeasurementCommands {

    private LpMeasurementCommands() {}

    /** {@code POST /loss-prevention/measurement-devices}. */
    public record CreateDeviceInput(
            @NotBlank(message = "branchId is required") String branchId,
            String agentId,
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @NotBlank(message = "kind is required") String kind,
            @Size(max = 100, message = "vendor must hold at most 100 characters") String vendor,
            @NotBlank(message = "externalId must be 1 to 200 characters") @Size(max = 200, message = "externalId must"
                    + " be 1 to 200 characters") String externalId,
            String productId,
            @Size(max = 200, message = "station must hold at most 200 characters") String station,
            BigDecimal standardPourMl,
            Boolean enabled) {}

    /** {@code PUT /loss-prevention/measurement-devices/:id}: branch, kind and externalId are fixed. */
    public record UpdateDeviceInput(
            String agentId,
            @NotBlank(message = "name is required") @Size(max = 100, message = "name must hold at most 100"
                    + " characters") String name,
            @Size(max = 100, message = "vendor must hold at most 100 characters") String vendor,
            String productId,
            @Size(max = 200, message = "station must hold at most 200 characters") String station,
            BigDecimal standardPourMl,
            Boolean enabled) {}
}
