package com.inventoryerp.fleet.application;

import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
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

/** Request bodies of incidents, insurance claims, traffic fines and fine deductions (fleet 1.7). */
public final class FleetIncidentCommands {

    private FleetIncidentCommands() {}

    /** A third party involved in an incident. */
    public record ThirdParty(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 40) String phone,
            @Size(max = 20) String vehiclePlate,
            @Size(max = 120) String insurer,
            @Size(max = 80) String policyNumber,
            @Size(max = 500) String notes) {}

    /** Report an incident / accident; {@code markVehicleBreakdown} also moves the vehicle to breakdown. */
    public record IncidentInput(
            @NotBlank String vehicleId,
            String driverEmployeeId,
            @NotNull Instant occurredAt,
            @Size(max = 200) String location,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @OneOf({"collision", "rollover", "theft", "vandalism", "fire", "injury", "property_damage", "load_spill",
                    "other"}) String incidentType,
            @OneOf({"minor", "major", "critical"}) String severity,
            @NotBlank @Size(max = 4000) String description,
            @Size(max = 4000) String damageDescription,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal estimatedCost,
            @OneOf({"own", "third_party", "shared", "unknown"}) String atFault,
            @Size(max = 2000) String injuries,
            @Valid @Size(max = 20) List<ThirdParty> thirdParties,
            @Size(max = 80) String policeReportNumber,
            @Size(max = 120) String policeStation,
            String workOrderId,
            @Size(max = 120) String repairReference,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal repairCost,
            @Size(max = 2000) String notes,
            Boolean markVehicleBreakdown) {}

    /** Fields of an incident that a PATCH may clear. */
    public static final List<String> INCIDENT_CLEARABLE = List.of("driverEmployeeId", "location", "latitude",
            "longitude", "damageDescription", "estimatedCost", "injuries", "policeReportNumber", "policeStation",
            "workOrderId", "repairReference", "repairCost", "notes");

    /** Edit an incident ({@code thirdParties} replaces the list when given). */
    public record IncidentPatch(
            String driverEmployeeId,
            Instant occurredAt,
            @Size(max = 200) String location,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @OneOf({"collision", "rollover", "theft", "vandalism", "fire", "injury", "property_damage", "load_spill",
                    "other"}) String incidentType,
            @OneOf({"minor", "major", "critical"}) String severity,
            @Size(min = 1, max = 4000) String description,
            @Size(max = 4000) String damageDescription,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal estimatedCost,
            @OneOf({"own", "third_party", "shared", "unknown"}) String atFault,
            @Size(max = 2000) String injuries,
            @Valid @Size(max = 20) List<ThirdParty> thirdParties,
            @Size(max = 80) String policeReportNumber,
            @Size(max = 120) String policeStation,
            String workOrderId,
            @Size(max = 120) String repairReference,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal repairCost,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    public record IncidentStatusChange(
            @NotBlank @OneOf({"reported", "investigating", "repairing", "closed", "cancelled"}) String status,
            @Size(max = 500) String reason) {}

    public record IncidentQuery(String search, String status, String vehicleId, String incidentType, LocalDate from,
            LocalDate to, Integer page, Integer pageSize) {}

    /** Open an insurance claim on an incident. */
    public record ClaimInput(
            @Size(max = 80) String claimNumber,
            String insurerPartnerId,
            @Size(max = 120) String insurerName,
            @Size(max = 80) String policyNumber,
            LocalDate submittedOn,
            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amountClaimed,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal excessAmount,
            @Size(max = 2000) String notes) {}

    /** Edit a claim's reference data (amounts move with the status route once submitted). */
    public record ClaimPatch(
            @Size(max = 80) String claimNumber,
            String insurerPartnerId,
            @Size(max = 120) String insurerName,
            @Size(max = 80) String policyNumber,
            LocalDate submittedOn,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amountClaimed,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal excessAmount,
            @Size(max = 2000) String notes) {}

    /** Move a claim: submitted, approved (amountApproved), rejected / withdrawn (reason), settled (amountReceived). */
    public record ClaimStatusChange(
            @NotBlank @OneOf({"submitted", "approved", "rejected", "settled", "withdrawn"}) String status,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amountApproved,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amountReceived,
            LocalDate date,
            @Size(max = 500) String reason) {}

    public record ClaimQuery(String status, String incidentId, String search, Integer page, Integer pageSize) {}

    /** Record a traffic fine. */
    public record FineInput(
            @NotBlank String vehicleId,
            String driverEmployeeId,
            String incidentId,
            @NotNull Instant offenceAt,
            @Size(max = 200) String location,
            @NotBlank @Size(max = 300) String offence,
            @Size(max = 40) String offenceCode,
            @Size(max = 120) String authority,
            @Size(max = 80) String noticeNumber,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal amount,
            LocalDate dueDate,
            @Size(max = 2000) String notes) {}

    /** Fields of a fine that a PATCH may clear. */
    public static final List<String> FINE_CLEARABLE = List.of("driverEmployeeId", "incidentId", "location",
            "offenceCode", "authority", "noticeNumber", "dueDate", "notes");

    /** Edit an open or disputed fine. */
    public record FinePatch(
            String driverEmployeeId,
            String incidentId,
            Instant offenceAt,
            @Size(max = 200) String location,
            @Size(min = 1, max = 300) String offence,
            @Size(max = 40) String offenceCode,
            @Size(max = 120) String authority,
            @Size(max = 80) String noticeNumber,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal amount,
            LocalDate dueDate,
            @Size(max = 2000) String notes,
            List<String> clear) {}

    public record FineStatusChange(
            @NotBlank @OneOf({"open", "disputed", "cancelled"}) String status,
            @Size(max = 500) String reason) {}

    /**
     * Charge a fine. {@code company}: an expense through the expenses module ({@code paymentType} CASH pays now
     * from {@code accountId}, CREDIT waits for approval / payment in Expenses) on the vehicle's cost centre.
     * {@code driver}: a salary-deduction request for {@code employeeId} (default the fine's driver).
     */
    public record ChargeInput(
            @NotBlank @OneOf({"company", "driver"}) String chargeTo,
            String employeeId,
            @OneOf({"CASH", "CREDIT"}) String paymentType,
            @Size(max = 40) String paymentMethod,
            String accountId,
            @Size(max = 120) String paymentReference,
            @Size(max = 500) String notes) {}

    public record ReverseChargeInput(@NotBlank @Size(max = 500) String reason) {}

    public record FineQuery(String search, String status, String vehicleId, String driverEmployeeId, String chargedTo,
            Boolean overdue, Integer page, Integer pageSize) {}

    /** Payroll export / settlement of a deduction request (until hr payroll consumes the event itself). */
    public record DeductionStatusChange(
            @NotBlank @OneOf({"exported", "settled"}) String status,
            @Size(max = 120) String reference) {}

    public record DeductionQuery(String status, String employeeId, Integer page, Integer pageSize) {}
}
