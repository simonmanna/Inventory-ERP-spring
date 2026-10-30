package com.inventoryerp.fleet.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Queries and commands of fleet 1.9 — costing, profitability, reports, freight bills, reminders. */
public final class FleetReportCommands {

    private FleetReportCommands() {
    }

    /** Vehicle cost / TCO report: {@code groupBy} vehicle (default), category, branch, month. */
    public record CostQuery(LocalDate from, LocalDate to, String groupBy, String vehicleId, String categoryId,
            String branchId) {}

    /**
     * Delivery profitability: {@code groupBy} trip (default), delivery_order, customer, site, day, month;
     * {@code carrier} own / transporter.
     */
    public record ProfitQuery(LocalDate from, LocalDate to, String groupBy, String partnerId, String siteId,
            String vehicleId, String carrier, String transporterPartnerId, String deliveryOrderId) {}

    /** Utilisation / availability and fuel reports per unit: {@code groupBy} vehicle (default), category, month. */
    public record UnitQuery(LocalDate from, LocalDate to, String groupBy, String vehicleId, String categoryId,
            String branchId) {}

    /**
     * Deliveries (completed, on time), tonnes delivered and transport cost per tonne: {@code groupBy} day, month,
     * customer, site, vehicle, driver, transporter, carrier, product (tonnes only).
     */
    public record DeliveryQuery(LocalDate from, LocalDate to, String groupBy, String partnerId, String siteId,
            String vehicleId, String driverId, String transporterPartnerId, String carrier) {}

    /** Upcoming renewals and services: everything expired or due within {@code days} (default 30) of {@code asOf}. */
    public record RenewalQuery(LocalDate asOf, Integer days, String kind) {}

    // ------------------------------------------------------------------ freight bills

    /** Freight bill list filters. */
    public record FreightBillQuery(String status, String direction, String transporterPartnerId, String search,
            Integer page, Integer pageSize) {}

    /** Closed transporter trips not on a live bill (preview of {@link GenerateFreightBills}). */
    public record FreightCandidateQuery(String transporterPartnerId, LocalDate from, LocalDate to) {}

    /**
     * Collect the unbilled closed trips of {@code [from, to]} (closing day) into one draft bill per transporter (or
     * only {@code transporterPartnerId}).
     */
    public record GenerateFreightBills(String transporterPartnerId, @NotNull LocalDate from, @NotNull LocalDate to,
            @Size(max = 2000) String notes) {}

    /**
     * Match the supplier's invoice: within the tolerance the AP vendor bill and the freight accrual are raised;
     * outside it the bill is {@code disputed} unless {@code acceptVariance} (needs
     * {@code fleet_freight:approve_variance}).
     */
    public record MatchFreightBill(@NotBlank @Size(max = 80) String supplierReference,
            @NotNull LocalDate supplierInvoiceDate,
            @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal supplierAmount,
            LocalDate dueDate, Boolean acceptVariance, @Size(max = 2000) String notes) {}

    /** Cancel a bill: lines released (trips billable again); a billed one reverses its accrual. */
    public record CancelFreightBill(@NotBlank @Size(max = 500) String reason) {}

    /**
     * Inbound freight on a posted goods receipt, capitalised as landed cost (ADR-S007 voucher, supplier bill
     * generated): {@code allocationMethod} value (default), quantity, weight, volume, equal.
     */
    public record InboundFreight(@NotBlank String goodsReceiptId, @NotBlank String transporterPartnerId,
            @NotBlank @Size(max = 80) String supplierReference, @NotNull LocalDate invoiceDate,
            @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
            String allocationMethod, String costCenterId, LocalDate dueDate, @Size(max = 2000) String notes) {}

    // ------------------------------------------------------------------ reminders

    /** Reminder log filters. */
    public record ReminderQuery(String itemType, LocalDate from, LocalDate to, Integer page, Integer pageSize) {}
}
