package com.inventoryerp.quarry.application;

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
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request bodies of the quarry module (ADR-S012), validated with Bean Validation at the controller. Master-data
 * {@code PATCH} routes take the same body as their {@code POST} and replace every editable field (the screens send the
 * whole form; a {@code null} optional field is cleared). Fields that identify the row (site of a zone, inventory
 * location of a stockpile / magazine) cannot change.
 */
public final class QuarryCommands {

    private QuarryCommands() {}

    public static final String[] SHIFTS = {"day", "night", "morning", "afternoon"};

    // ------------------------------------------------------------------ settings & rates

    /** Org settings; {@code null} keeps the current value. */
    public record SettingsInput(
            @OneOf({"net_tonnes", "gross_tonnes"}) String allocationBasis,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal fuelCostPerLitre,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal labourRatePerHour,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal equipmentRatePerHour,
            Boolean distinctExplosiveIssuer) {}

    /** Hourly rate of a fleet vehicle class ({@code categoryId}) or of one vehicle ({@code vehicleId}). */
    public record EquipmentRateInput(
            String categoryId,
            String vehicleId,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal ratePerHour,
            @Size(max = 500) String notes) {}

    public record RoyaltyRateInput(
            @NotBlank @Size(max = 120) String name,
            @OneOf({"royalty", "levy", "fee"}) String kind,
            String siteId,
            String materialId,
            @OneOf({"net_tonnes", "gross_tonnes"}) String basis,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal ratePerTonne,
            String authorityPartnerId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Boolean isActive,
            @Size(max = 1000) String notes) {}

    // ------------------------------------------------------------------ sites, zones, materials, stockpiles

    public record SiteInput(
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"active", "suspended", "closed"}) String status,
            String branchId,
            String costCenterId,
            @Size(max = 500) String address,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 7) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7) BigDecimal longitude,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal areaHectares,
            @Size(max = 80) String licenceNumber,
            @Size(max = 120) String licenceAuthority,
            LocalDate licenceExpiresOn,
            @Size(max = 2000) String notes) {}

    public record ZoneInput(
            @NotBlank String siteId,
            String parentId,
            @OneOf({"pit", "zone", "bench", "face"}) String kind,
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            String materialId,
            @Size(max = 500) String geology,
            @Digits(integer = 8, fraction = 2) BigDecimal elevationM,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal benchHeightM,
            @OneOf({"active", "inactive", "exhausted"}) String status,
            @Size(max = 2000) String notes) {}

    public record MaterialInput(
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 60) String rockType,
            @Size(max = 60) String grade,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 3) BigDecimal densityTPerM3,
            @NotBlank String productId,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    /** A stockpile; without {@code locationId} a new inventory location is created for it. */
    public record StockpileInput(
            @NotBlank String siteId,
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @OneOf({"rom", "product"}) String kind,
            String locationId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal capacityT,
            Boolean isDefault,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    // ------------------------------------------------------------------ plans

    public record PlanInput(
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String siteId,
            String zoneId,
            String materialId,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal targetTonnes,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal budgetCostPerTonne,
            @OneOf({"draft", "active", "closed"}) String status,
            @Size(max = 2000) String notes) {}

    // ------------------------------------------------------------------ haulage

    public record HaulageInput(
            @NotBlank String siteId,
            String fromZoneId,
            @OneOf({"rom_stockpile", "crusher", "waste_dump", "other"}) String destination,
            String stockpileId,
            @Size(max = 200) String destinationNote,
            String materialId,
            @NotNull LocalDate hauledOn,
            @NotBlank @OneOf({"day", "night", "morning", "afternoon"}) String shift,
            String truckVehicleId,
            String driverEmployeeId,
            @NotBlank @OneOf({"loads", "weighbridge"}) String method,
            @Min(1) @Max(10000) Integer loadCount,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal capacityT,
            String weighbridgeTicketId,
            String fleetTripId,
            @Size(max = 2000) String notes) {}

    public record HaulageQuery(String siteId, String truckVehicleId, String destination, LocalDate dateFrom,
            LocalDate dateTo, Boolean unassigned, String search, Integer page, Integer pageSize) {}

    // ------------------------------------------------------------------ extraction logs & losses

    /** A quantity line: loads × capacity, a completed weighbridge ticket, a haulage record or a survey figure. */
    public record LoadInput(
            @NotBlank @OneOf({"loads", "weighbridge", "haulage", "survey"}) String method,
            String truckVehicleId,
            @Min(1) @Max(10000) Integer loadCount,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal capacityT,
            String weighbridgeTicketId,
            String haulageId,
            String fleetTripId,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal tonnes,
            @Size(max = 500) String notes) {}

    /** Equipment (fleet mobile plant, optional operator) or labour (hr employee) of the shift. */
    public record ResourceInput(
            @NotBlank @OneOf({"equipment", "labour"}) String kind,
            String vehicleId,
            String employeeId,
            @Size(max = 60) String role,
            @DecimalMin("0") @DecimalMax("24") @Digits(integer = 2, fraction = 2) BigDecimal hours,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal meterStart,
            @DecimalMin("0") @Digits(integer = 13, fraction = 1) BigDecimal meterEnd,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal fuelLitres,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal ratePerHour,
            @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal fuelCostPerLitre,
            @Size(max = 500) String notes) {}

    public record LossLineInput(
            @NotBlank @OneOf({"overburden", "unusable_rock", "waste", "spillage", "other"}) String kind,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal tonnes,
            @Size(max = 500) String reason) {}

    /**
     * A daily extraction log (one shift). Without {@code stockpileId} the site's default ROM stockpile is used.
     * {@code post: true} also posts it (needs {@code quarry_extraction:post}).
     */
    public record ExtractionLogInput(
            @NotBlank String siteId,
            String zoneId,
            @NotBlank String materialId,
            String stockpileId,
            String blastId,
            @NotNull LocalDate extractedOn,
            @NotBlank @OneOf({"day", "night", "morning", "afternoon"}) String shift,
            String hrShiftId,
            String supervisorEmployeeId,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal otherCost,
            @Size(max = 200) String otherCostNote,
            @Size(max = 2000) String notes,
            @Size(max = 200) List<@Valid LoadInput> loads,
            @Size(max = 100) List<@Valid ResourceInput> resources,
            @Size(max = 50) List<@Valid LossLineInput> losses,
            Boolean post) {}

    public record ExtractionLogQuery(String siteId, String zoneId, String materialId, String status, String shift,
            LocalDate dateFrom, LocalDate dateTo, String search, Integer page, Integer pageSize, String sortBy,
            String sortOrder) {}

    public record ReverseInput(@NotBlank @Size(max = 500) String reason) {}

    /** A standalone loss (overburden stripping, waste removal) outside an extraction log. */
    public record LossInput(
            @NotBlank String siteId,
            String zoneId,
            String materialId,
            @NotNull LocalDate lossOn,
            @NotBlank @OneOf({"overburden", "unusable_rock", "waste", "spillage", "other"}) String kind,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal tonnes,
            @Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ explosives & blasting

    /** A magazine; without {@code locationId} a new inventory location is created for it. */
    public record MagazineInput(
            String siteId,
            @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            String locationId,
            @Size(max = 80) String licenceNumber,
            @Size(max = 120) String licenceAuthority,
            LocalDate licenceExpiresOn,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal capacityKg,
            String keeperEmployeeId,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    public record ExplosiveInput(
            @NotBlank String productId,
            @OneOf({"high_explosive", "blasting_agent", "detonator", "booster", "cord", "other"}) String kind,
            @Size(max = 20) String hazardClass,
            @Size(max = 20) String unNumber,
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal netExplosiveKg,
            Boolean controlled,
            Boolean isActive,
            @Size(max = 2000) String notes) {}

    public record BlastInput(
            @NotBlank String siteId,
            String zoneId,
            String materialId,
            Instant plannedAt,
            @OneOf({"square", "staggered", "other"}) String patternType,
            @Min(1) @Max(100000) Integer holeCount,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 1) BigDecimal holeDiameterMm,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 2) BigDecimal averageDepthM,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 2) BigDecimal burdenM,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 2) BigDecimal spacingM,
            @DecimalMin("0") @Digits(integer = 5, fraction = 2) BigDecimal subDrillM,
            @DecimalMin("0") @Digits(integer = 5, fraction = 2) BigDecimal stemmingM,
            String drillVehicleId,
            @DecimalMin("0") @Digits(integer = 7, fraction = 2) BigDecimal drillHours,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal drillingCost,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal otherCost,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal estimatedTonnes,
            String shotfirerEmployeeId,
            @Size(max = 80) String shotfirerLicenceNo,
            @Size(max = 80) String blastPermitNo,
            LocalDate permitExpiresOn,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 1) BigDecimal exclusionZoneM,
            @Size(max = 2000) String notes) {}

    /** Firing record (compliance): when, warning / all-clear, misfires, vibration and airblast readings, weather. */
    public record FireInput(
            Instant firedAt,
            Instant warningSignalAt,
            Instant allClearAt,
            @Min(0) @Max(10000) Integer misfireCount,
            @Size(max = 2000) String misfireNotes,
            @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal vibrationPpvMmS,
            @DecimalMin("0") @Digits(integer = 6, fraction = 2) BigDecimal airblastDb,
            @Size(max = 200) String weather,
            @Size(max = 2000) String notes) {}

    public record BlastQuery(String siteId, String status, LocalDate dateFrom, LocalDate dateTo, String search,
            Integer page, Integer pageSize) {}

    public record IssueLineInput(
            @NotBlank String explosiveId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity) {}

    /** Request explosives for a blast from a magazine; goes to the approval engine before anything is issued. */
    public record ExplosiveIssueInput(
            @NotBlank String blastId,
            @NotBlank String magazineId,
            @Size(max = 500) String purpose,
            String shotfirerEmployeeId,
            @Size(max = 80) String shotfirerLicenceNo,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 30) List<@Valid IssueLineInput> lines) {}

    public record ReturnLineInput(
            @NotBlank String lineId,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal returnedQty) {}

    /** Unused explosives returned to the magazine after the blast (zero for a line fully used). */
    public record ReconcileInput(
            @NotNull @Size(max = 30) List<@Valid ReturnLineInput> lines,
            @Size(max = 2000) String notes) {}

    public record CancelInput(@NotBlank @Size(max = 500) String reason) {}

    public record IssueQuery(String status, String blastId, String magazineId, String search, Integer page,
            Integer pageSize) {}

    // ------------------------------------------------------------------ public API for crushing (2.3)

    /**
     * Take ROM stone out of a stockpile into a process (quantity only; the caller posts its own GL, e.g. Dr WIP /
     * Cr stock with {@code StockPostingService#postProductionConsume}).
     */
    public record ConsumeRequest(String stockpileId, String productId, BigDecimal tonnes, String sourceType,
            String sourceId, String reference, Instant date, String notes) {}
}
