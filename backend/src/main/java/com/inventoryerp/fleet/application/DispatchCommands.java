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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request bodies of fleet 1.3 (trips, delivery planning &amp; dispatch), validated with Bean Validation at the
 * controller. PATCH bodies treat {@code null} as "unchanged"; a nullable field is cleared by naming it in {@code clear}.
 */
public final class DispatchCommands {

    private DispatchCommands() {}

    static final String HHMM = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    /** Reason codes of a delivery discrepancy. */
    public static final List<String> REASONS = List.of("spillage", "weighing_variance", "damaged", "customer_rejected",
            "wrong_product", "theft", "site_closed", "other");

    // ------------------------------------------------------------------ customer sites

    public record SiteInput(
            @NotBlank String partnerId,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 40) String code,
            @Size(max = 500) String address,
            @Size(max = 120) String city,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @Size(max = 120) String contactName,
            @Size(max = 40) String contactPhone,
            @Size(max = 2000) String accessNotes,
            @Size(max = 80) String zone,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal distanceKm,
            @Size(max = 2000) String notes) {}

    public static final List<String> SITE_CLEARABLE = List.of("address", "city", "latitude", "longitude",
            "contactName", "contactPhone", "accessNotes", "zone", "distanceKm", "notes");

    public record SitePatch(
            @Size(max = 160) String name,
            @Size(max = 500) String address,
            @Size(max = 120) String city,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @Size(max = 120) String contactName,
            @Size(max = 40) String contactPhone,
            @Size(max = 2000) String accessNotes,
            @Size(max = 80) String zone,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal distanceKm,
            Boolean isActive,
            @Size(max = 2000) String notes,
            List<@OneOf({"address", "city", "latitude", "longitude", "contactName", "contactPhone", "accessNotes",
                "zone", "distanceKm", "notes"}) String> clear) {}

    // ------------------------------------------------------------------ delivery orders

    public record DeliveryLineInput(
            @NotBlank String productId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            String uomId,
            @DecimalMin("0") @Digits(integer = 14, fraction = 6) BigDecimal unitPrice,
            String taxId,
            @Size(max = 500) String description,
            @Size(max = 80) String batchNumber,
            @Size(max = 1000) String notes) {}

    public record DeliveryOrderInput(
            @NotBlank String partnerId,
            String siteId,
            String locationId,
            @NotNull LocalDate requestedDate,
            @Pattern(regexp = HHMM, message = "windowFrom must be a time like 08:00") String windowFrom,
            @Pattern(regexp = HHMM, message = "windowTo must be a time like 17:30") String windowTo,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @Size(max = 120) String contactName,
            @Size(max = 40) String contactPhone,
            String costCenterId,
            String branchId,
            @Size(max = 2000) String notes,
            @NotEmpty @Valid List<DeliveryLineInput> lines) {}

    /** Delivery order from a confirmed sales order ({@code sourceId} = orders {@code Order}) or a sales invoice. */
    public record FromSourceInput(
            @NotBlank String sourceId,
            String siteId,
            String locationId,
            LocalDate requestedDate,
            @Pattern(regexp = HHMM, message = "windowFrom must be a time like 08:00") String windowFrom,
            @Pattern(regexp = HHMM, message = "windowTo must be a time like 17:30") String windowTo,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @Size(max = 120) String contactName,
            @Size(max = 40) String contactPhone,
            String costCenterId,
            @Size(max = 2000) String notes) {}

    public record DeliveryOrderPatch(
            String siteId,
            String locationId,
            LocalDate requestedDate,
            @Pattern(regexp = HHMM, message = "windowFrom must be a time like 08:00") String windowFrom,
            @Pattern(regexp = HHMM, message = "windowTo must be a time like 17:30") String windowTo,
            @OneOf({"low", "normal", "high", "urgent"}) String priority,
            @Size(max = 120) String contactName,
            @Size(max = 40) String contactPhone,
            String costCenterId,
            @Size(max = 2000) String notes,
            List<@OneOf({"siteId", "windowFrom", "windowTo", "contactName", "contactPhone", "costCenterId",
                "notes"}) String> clear) {}

    public record DeliveryOrderQuery(String search, String status, String partnerId, String siteId, String sourceType,
            LocalDate dateFrom, LocalDate dateTo, Integer page, Integer pageSize) {}

    public record ReasonInput(@NotBlank @Size(max = 500) String reason) {}

    public record CloseOrderInput(@Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ planning

    /**
     * Split the remaining quantity into loads. Capacity from {@code vehicleId} (+ {@code trailerId}) or explicit
     * {@code payloadT} / {@code volumeM3}; with a vehicle the loads are assigned to it (and {@code driverId}).
     */
    public record PlanInput(
            String vehicleId,
            String trailerId,
            String driverId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal payloadT,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal volumeM3,
            LocalDate plannedDate,
            @Min(1) @Max(200) Integer maxLoads) {}

    public record LoadLineInput(
            @NotBlank String deliveryOrderLineId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @Size(max = 80) String batchNumber) {}

    public record LoadInput(
            LocalDate plannedDate,
            @NotEmpty @Valid List<LoadLineInput> lines,
            @Size(max = 200) String purpose,
            @Size(max = 2000) String notes) {}

    /** Own truck ({@code vehicleId}, {@code driverId}, optional {@code trailerId}) or a third-party transporter. */
    public record AssignInput(
            @NotBlank @OneOf({"own", "transporter"}) String carrier,
            String vehicleId,
            String trailerId,
            String driverId,
            String transporterPartnerId,
            String transporterRateId,
            @OneOf({"per_trip", "per_tonne", "per_km", "per_m3", "per_tonne_km"}) String freightBasis,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal freightRate,
            @Size(max = 40) String transporterPlate,
            @Size(max = 120) String transporterDriverName) {}

    public record TripPatch(
            LocalDate plannedDate,
            String locationId,
            String costCenterId,
            @Size(max = 200) String purpose,
            @Size(max = 2000) String notes) {}

    public record MeterInput(
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal odometer) {}

    public record LoadedLineInput(
            @NotBlank String loadLineId,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @Size(max = 80) String batchNumber) {}

    /** Manual "loaded quantity" (no weighbridge ticket): the trip becomes {@code weighed}. */
    public record LoadedQuantityInput(@NotEmpty @Valid List<LoadedLineInput> lines) {}

    public record PodLineInput(
            @NotBlank String loadLineId,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal deliveredQty,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal returnedQty,
            @OneOf({"spillage", "weighing_variance", "damaged", "customer_rejected", "wrong_product", "theft",
                "site_closed", "other"}) String reason,
            @Size(max = 1000) String notes) {}

    /** Proof of delivery; {@code signature} is a {@code data:image/png;base64,…} URL from the signature pad. */
    public record PodInput(
            @NotBlank @Size(max = 160) String receiverName,
            @Size(max = 40) String receiverPhone,
            Instant receivedAt,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @NotEmpty @Valid List<PodLineInput> lines,
            @Size(max = 1_500_000) String signature,
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal endOdometer,
            @Size(max = 2000) String notes) {}

    public record CloseTripInput(
            @DecimalMin("0") @DecimalMax("9999999999999.9") @Digits(integer = 13, fraction = 1) BigDecimal endOdometer,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal distanceKm) {}

    public record ReturnInput(
            @NotBlank @Size(max = 500) String reason,
            @OneOf({"spillage", "weighing_variance", "damaged", "customer_rejected", "wrong_product", "theft",
                "site_closed", "other"}) String reasonCode) {}

    public record TripQuery(String search, String status, LocalDate dateFrom, LocalDate dateTo, String vehicleId,
            String driverId, String deliveryOrderId, String partnerId, Boolean mine, Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ discrepancies

    public record DecisionInput(@Size(max = 1000) String notes) {}

    public record DiscrepancyQuery(String status, String kind, String deliveryOrderId, String partnerId,
            Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ settings

    public record TransporterRateInput(
            @NotBlank String partnerId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @OneOf({"per_trip", "per_tonne", "per_km", "per_m3", "per_tonne_km"}) String basis,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rate,
            @Size(max = 80) String zone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Size(max = 1000) String notes,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minimumCharge) {}

    public record TransporterRatePatch(
            @Size(max = 120) String name,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rate,
            @Size(max = 80) String zone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Boolean isActive,
            @Size(max = 1000) String notes,
            List<@OneOf({"zone", "effectiveFrom", "effectiveTo", "notes"}) String> clear) {}

    public record ChargeRuleInput(
            @NotBlank @Size(max = 120) String name,
            String partnerId,
            @Size(max = 80) String zone,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal minDistanceKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal maxDistanceKm,
            @NotBlank @OneOf({"flat", "per_trip", "per_tonne", "per_m3", "per_km"}) String basis,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rate,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minAmount,
            String taxId,
            @Size(max = 1000) String notes) {}

    public record ChargeRulePatch(
            @Size(max = 120) String name,
            String partnerId,
            @Size(max = 80) String zone,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal minDistanceKm,
            @DecimalMin("0") @Digits(integer = 9, fraction = 1) BigDecimal maxDistanceKm,
            @OneOf({"flat", "per_trip", "per_tonne", "per_m3", "per_km"}) String basis,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rate,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minAmount,
            String taxId,
            Boolean isActive,
            @Size(max = 1000) String notes,
            List<@OneOf({"partnerId", "zone", "minDistanceKm", "maxDistanceKm", "minAmount", "taxId", "notes"}) String>
                    clear) {}

    public record LoadFactorInput(
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 6) BigDecimal tonnesPerUnit,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 6) BigDecimal cubicMetresPerUnit,
            @Size(max = 1000) String notes) {}

    /** Customer invoice of a delivery order: delivered goods (unless invoiced already) + transport charges. */
    public record InvoiceRequest(LocalDate issueDate, Boolean includeCharges) {}
}
