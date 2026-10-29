package com.inventoryerp.fleet.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request bodies and queries of the telematics screens (fleet 1.8). */
public final class FleetTelematicsCommands {

    private FleetTelematicsCommands() {}

    /** Map a provider unit to a vehicle; {@code provider} defaults to the configured one. */
    public record DeviceInput(
            String vehicleId,
            @Size(max = 40) String provider,
            @NotBlank @Size(max = 80) String externalUnitId,
            @Size(max = 120) String name,
            @Size(max = 40) String imei,
            @Size(max = 40) String simNumber,
            Boolean syncOdometer,
            Boolean syncHours,
            @Size(max = 2000) String notes) {}

    public static final List<String> DEVICE_CLEARABLE = List.of("vehicleId", "name", "imei", "simNumber", "notes");

    /** Re-map / edit a device ({@code clear: ["vehicleId"]} unmaps it). */
    public record DevicePatch(
            String vehicleId,
            @Size(min = 1, max = 80) String externalUnitId,
            @Size(max = 120) String name,
            @Size(max = 40) String imei,
            @Size(max = 40) String simNumber,
            Boolean isActive,
            Boolean syncOdometer,
            Boolean syncHours,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    public record AcknowledgeInput(@Size(max = 500) String note) {}

    /** Telematics events: {@code alertsOnly}, {@code open} (unacknowledged alerts), type, vehicle, period. */
    public record EventQuery(String vehicleId, String eventType, Boolean alertsOnly, Boolean open, Instant from,
            Instant to, Integer page, Integer pageSize) {}
}
