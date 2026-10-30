package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request bodies and queries of the weighbridge screens (fleet 1.4). */
public final class WeighbridgeCommands {

    private WeighbridgeCommands() {}

    public record StationInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            String branchId,
            String locationId,
            @DecimalMin("0") @DecimalMax("100") BigDecimal tolerancePct,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal toleranceKg,
            @Min(1) @Max(3650) Integer tareValidityDays,
            @DecimalMin("0") BigDecimal minWeightKg,
            @DecimalMin("1") BigDecimal maxWeightKg,
            @Size(max = 2000) String notes) {}

    public static final List<String> STATION_CLEARABLE = List.of("branchId", "locationId", "notes");

    public record StationPatch(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 120) String name,
            String branchId,
            String locationId,
            @DecimalMin("0") @DecimalMax("100") BigDecimal tolerancePct,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal toleranceKg,
            @Min(1) @Max(3650) Integer tareValidityDays,
            @DecimalMin("0") BigDecimal minWeightKg,
            @DecimalMin("1") BigDecimal maxWeightKg,
            Boolean isActive,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    public record DeviceInput(
            @NotBlank String stationId,
            @NotBlank @Size(max = 40) String code,
            @Size(max = 120) String name,
            @NotBlank @Size(max = 40) String adapterType,
            Map<String, Object> config,
            @Min(500) @Max(60000) Integer readTimeoutMs,
            @Size(max = 2000) String notes) {}

    public static final List<String> DEVICE_CLEARABLE = List.of("name", "notes");

    /** {@code config} replaces the stored configuration. */
    public record DevicePatch(
            String stationId,
            @Size(min = 1, max = 40) String code,
            @Size(max = 120) String name,
            Map<String, Object> config,
            @Min(500) @Max(60000) Integer readTimeoutMs,
            Boolean isActive,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Demo / test: put a weight on a simulator's platform (and optionally its behaviour). */
    public record SimulatorWeight(
            @NotNull @DecimalMin("-100000") @DecimalMax("9999999") BigDecimal weightKg,
            @Size(max = 20) String mode,
            @Min(0) @Max(50) Integer motionFrames) {}

    /** Replaces the product's weighbridge settings (null = not set). */
    public record ProductSettingInput(
            @DecimalMin(value = "0", inclusive = false) BigDecimal kgPerUnit,
            @DecimalMin("0") @DecimalMax("100") BigDecimal tolerancePct,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal toleranceKg,
            @Size(max = 2000) String notes) {}

    /** Stored-tare weighing of an empty truck: from a device, or keyed in (manual entry permission + reason). */
    public record TareInput(
            String vehicleId,
            @Size(max = 40) String registrationPlate,
            @NotBlank String stationId,
            String deviceId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal manualKg,
            @Size(max = 500) String manualReason) {}

    public record ReasonInput(@NotBlank @Size(max = 500) String reason) {}

    public record TicketInput(
            @NotBlank String stationId,
            @NotBlank @Size(max = 30) String subjectType,
            String vehicleId,
            @Size(max = 40) String registrationPlate,
            @Size(max = 40) String trailerPlate,
            String driverId,
            @Size(max = 120) String driverName,
            String transporterPartnerId,
            String partnerId,
            String salesOrderId,
            @Size(max = 64) String deliveryOrderId,
            @Size(max = 64) String tripId,
            String purchaseOrderId,
            String purchaseOrderLineId,
            @Size(max = 80) String referenceNumber,
            String productId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal orderedQty,
            String orderedUomId,
            String locationId,
            @Size(max = 2000) String notes) {}

    public static final List<String> TICKET_CLEARABLE = List.of("trailerPlate", "driverId", "driverName",
            "transporterPartnerId", "partnerId", "salesOrderId", "deliveryOrderId", "tripId", "purchaseOrderId",
            "purchaseOrderLineId", "referenceNumber", "productId", "orderedQty", "orderedUomId", "locationId", "notes");

    /** Header changes while the ticket is not completed (and the corrections of a reissue). */
    public record TicketPatch(
            @Size(max = 30) String subjectType,
            String vehicleId,
            @Size(max = 40) String registrationPlate,
            @Size(max = 40) String trailerPlate,
            String driverId,
            @Size(max = 120) String driverName,
            String transporterPartnerId,
            String partnerId,
            String salesOrderId,
            @Size(max = 64) String deliveryOrderId,
            @Size(max = 64) String tripId,
            String purchaseOrderId,
            String purchaseOrderLineId,
            @Size(max = 80) String referenceNumber,
            String productId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal orderedQty,
            String orderedUomId,
            String locationId,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /**
     * A weighing: {@code deviceId} reads the indicator (stable weight + raw frame); otherwise {@code manualKg} +
     * {@code manualReason} (needs {@code weighbridge:manual_entry}). {@code saveTare} on the weighing that completes
     * the ticket stores the weighed tare as the vehicle's stored tare.
     */
    public record WeighInput(
            String deviceId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal manualKg,
            @Size(max = 500) String manualReason,
            Boolean saveTare,
            @Size(max = 500) String note) {}

    /** Use the vehicle's valid stored tare instead of weighing it ({@code tareId} defaults to the plate's). */
    public record StoredTareInput(String tareId) {}

    public record VoidInput(
            @NotBlank @Size(max = 500) String reason,
            Boolean reissue,
            Boolean copyWeights,
            @Valid TicketPatch corrections) {}

    public record GoodsReceiptInput(String locationId) {}

    public record ExceptionDecision(@Size(max = 500) String notes) {}

    /** Ticket queue: {@code queue} = open / awaiting_second / exceptions / completed / void / cancelled / live. */
    public record TicketQuery(String queue, String stationId, String subjectType, String partnerId, String productId,
            String vehicleId, String search, Boolean manual, Boolean outOfTolerance, LocalDate from, LocalDate to,
            Integer page, Integer pageSize) {}

    /**
     * "Weighbridge required for dispatch" of the organization ({@code branchId} empty) or one branch (fleet 1.4b):
     * {@code off}, {@code all} products, {@code bulk} products (sold by weight / volume, or with weighbridge product
     * settings) or the listed product {@code categories} (and their sub-categories).
     */
    public record DispatchRequirementInput(
            String branchId,
            @NotBlank @OneOf({"off", "all", "bulk", "categories"}) String mode,
            @Size(max = 200) List<@NotBlank String> categoryIds,
            @Size(max = 1000) String notes) {}

    public record ReportQuery(String groupBy, String stationId, String subjectType, String partnerId,
            String productId, LocalDate from, LocalDate to) {}
}
