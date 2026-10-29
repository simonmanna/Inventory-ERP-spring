package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
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
 * Request bodies of the fleet registry (1.1), validated with Bean Validation at the controller. PATCH bodies treat
 * {@code null} as "unchanged"; a nullable field is cleared by naming it in {@code clear}.
 */
public final class FleetCommands {

    private FleetCommands() {}

    /** Create a vehicle / equipment class. */
    public record CategoryInput(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"road_vehicle", "trailer", "mobile_plant", "fixed_plant", "other"}) String kind,
            @OneOf({"odometer", "hours", "both", "none"}) String defaultMeterType,
            @Size(max = 500) String description,
            @Min(0) @Max(100000) Integer sortOrder,
            Boolean isActive) {}

    /** Patch a class ({@code description: ""} clears it). */
    public record CategoryPatch(
            @Size(min = 1, max = 40) String code,
            @Size(min = 1, max = 120) String name,
            @OneOf({"road_vehicle", "trailer", "mobile_plant", "fixed_plant", "other"}) String kind,
            @OneOf({"odometer", "hours", "both", "none"}) String defaultMeterType,
            @Size(max = 500) String description,
            @Min(0) @Max(100000) Integer sortOrder,
            Boolean isActive) {}

    /** Register a vehicle or piece of equipment. A blank {@code code} is numbered {@code VEH-0001}... */
    public record VehicleInput(
            @Size(max = 40) String code,
            @Size(max = 120) String name,
            @Size(max = 20) String registrationPlate,
            @Size(max = 40) String vin,
            @Size(max = 40) String engineNumber,
            @Size(max = 80) String make,
            @Size(max = 80) String model,
            @Min(1950) @Max(2100) Integer year,
            @Size(max = 40) String color,
            @NotBlank String categoryId,
            @OneOf({"owned", "leased", "hired", "third_party"}) String ownership,
            String ownerPartnerId,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal payloadCapacityT,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal bodyVolumeM3,
            @Size(max = 20) String axleConfig,
            @OneOf({"diesel", "petrol", "electric", "hybrid", "lpg", "cng", "none"}) String fuelType,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal tankCapacityL,
            @OneOf({"odometer", "hours", "both", "none"}) String meterType,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal openingOdometer,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal openingHours,
            String branchId,
            String homeLocationId,
            String costCenterId,
            String assetId,
            LocalDate acquiredOn,
            @Size(max = 2000) String notes) {}

    /** Fields of a vehicle that a PATCH may clear. */
    public static final List<String> VEHICLE_CLEARABLE = List.of("name", "registrationPlate", "vin", "engineNumber",
            "make", "model", "year", "color", "ownerPartnerId", "payloadCapacityT", "bodyVolumeM3", "axleConfig",
            "tankCapacityL", "branchId", "homeLocationId", "costCenterId", "assetId", "acquiredOn", "notes");

    /** Edit a vehicle (meters move through readings, status through the status route). */
    public record VehiclePatch(
            @Size(min = 1, max = 40) String code,
            @Size(max = 120) String name,
            @Size(max = 20) String registrationPlate,
            @Size(max = 40) String vin,
            @Size(max = 40) String engineNumber,
            @Size(max = 80) String make,
            @Size(max = 80) String model,
            @Min(1950) @Max(2100) Integer year,
            @Size(max = 40) String color,
            String categoryId,
            @OneOf({"owned", "leased", "hired", "third_party"}) String ownership,
            String ownerPartnerId,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal payloadCapacityT,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal bodyVolumeM3,
            @Size(max = 20) String axleConfig,
            @OneOf({"diesel", "petrol", "electric", "hybrid", "lpg", "cng", "none"}) String fuelType,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal tankCapacityL,
            @OneOf({"odometer", "hours", "both", "none"}) String meterType,
            String branchId,
            String homeLocationId,
            String costCenterId,
            String assetId,
            LocalDate acquiredOn,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Move a vehicle to another status. */
    public record StatusChange(
            @NotBlank @OneOf({"active", "in_workshop", "breakdown", "idle", "disposed"}) String status,
            @Size(max = 500) String reason) {}

    /** Record an odometer (km) or hour-meter reading; {@code readingAt} defaults to now. */
    public record MeterReadingInput(
            @NotBlank @OneOf({"odometer", "hours"}) String meterType,
            @NotNull @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1)
            BigDecimal value,
            Instant readingAt,
            @Size(max = 500) String notes) {}

    /**
     * Request a meter correction: {@code adjust} replaces the value of reading {@code readingId}; {@code replace}
     * records a meter replacement / rollover starting at {@code correctedValue} at {@code readingAt} (default now).
     */
    public record MeterCorrectionInput(
            @NotBlank @OneOf({"odometer", "hours"}) String meterType,
            @NotBlank @OneOf({"adjust", "replace"}) String kind,
            String readingId,
            @NotNull @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1)
            BigDecimal correctedValue,
            Instant readingAt,
            @NotBlank @Size(max = 500) String reason) {}

    /** Add a compliance document; {@code reminderLeadDays} defaults to the org setting fleet.complianceReminderDays. */
    public record ComplianceInput(
            @NotBlank @OneOf({"registration", "insurance", "road_licence", "inspection", "operator_permit", "other"})
            String docType,
            @Size(max = 80) String documentNumber,
            @Size(max = 120) String issuer,
            LocalDate issuedOn,
            LocalDate expiresOn,
            @Min(0) @Max(365) Integer reminderLeadDays,
            @Size(max = 2000) String notes) {}

    /** Fields of a compliance document that a PATCH may clear. */
    public static final List<String> COMPLIANCE_CLEARABLE = List.of("documentNumber", "issuer", "issuedOn",
            "expiresOn", "notes");

    /** Edit a compliance document. */
    public record CompliancePatch(
            @OneOf({"registration", "insurance", "road_licence", "inspection", "operator_permit", "other"})
            String docType,
            @Size(max = 80) String documentNumber,
            @Size(max = 120) String issuer,
            LocalDate issuedOn,
            LocalDate expiresOn,
            @Min(0) @Max(365) Integer reminderLeadDays,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    /** Renew a document: the new one supersedes it (status {@code renewed}). */
    public record ComplianceRenewal(
            @Size(max = 80) String documentNumber,
            @Size(max = 120) String issuer,
            LocalDate issuedOn,
            @NotNull LocalDate expiresOn,
            @Min(0) @Max(365) Integer reminderLeadDays,
            @Size(max = 2000) String notes) {}

    /** Vehicle list filters. */
    public record VehicleQuery(String search, String categoryId, String kind, String status, String branchId,
            String ownership, Integer page, Integer pageSize, String sortBy, String sortOrder) {}

    /** Org-wide compliance list: {@code due} = attention (expired + expiring), expiring, expired or all. */
    public record ComplianceQuery(String due, String vehicleId, String docType, String branchId, LocalDate asOf,
            Integer page, Integer pageSize) {}
}
