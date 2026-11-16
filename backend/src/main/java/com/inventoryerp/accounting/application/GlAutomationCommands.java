package com.inventoryerp.accounting.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs of the GL automation extensions (ADR-S073): recurring journal templates, deferral schedules, bank matching
 * rules and grouped / partial bank matches. Nest has no counterpart; dates are {@code YYYY-MM-DD}.
 */
public final class GlAutomationCommands {

    private GlAutomationCommands() {}

    static final String DAY = "^\\d{4}-\\d{2}-\\d{2}.*$";

    // ------------------------------------------------------------------ recurring journal templates

    /** One template line: exactly one of debit / credit is positive. */
    public record TemplateLine(
            @NotBlank String accountId,
            @DecimalMin("0") BigDecimal debit,
            @DecimalMin("0") BigDecimal credit,
            String partnerId,
            @Size(max = 500) String description,
            String branchId,
            String costCenterId) {}

    /** {@code POST /recurring-entries} and {@code PATCH /recurring-entries/:id} (all fields optional on PATCH). */
    public record TemplateInput(
            @Size(min = 1, max = 200) String name,
            @Size(max = 20) String journalCode,
            @Size(max = 500) String description,
            @Pattern(regexp = "daily|weekly|monthly|quarterly|yearly") String frequency,
            @Min(1) @Max(365) Integer intervalCount,
            @Pattern(regexp = DAY) String startDate,
            @Pattern(regexp = DAY) String endDate,
            @Pattern(regexp = "post|draft") String mode,
            String currencyId,
            String branchId,
            String costCenterId,
            @Size(min = 2, max = 200) List<@Valid @NotNull TemplateLine> lines) {}

    /** {@code POST /recurring-entries/:id/run}: occurrence date, default today. */
    public record RunInput(@Pattern(regexp = DAY) String date) {}

    // ------------------------------------------------------------------ deferral schedules

    /** {@code POST /deferrals}: a schedule over a balance that already sits on {@code sourceAccountId}. */
    public record DeferralInput(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "revenue|expense") String kind,
            String sourceAccountId,
            @NotBlank String targetAccountId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal totalAmount,
            @NotNull @Pattern(regexp = DAY) String startDate,
            @NotNull @Min(1) @Max(600) Integer months,
            @Pattern(regexp = "monthly|daily") String method,
            String partnerId,
            String branchId,
            String costCenterId,
            @Size(max = 20) String journalCode,
            @Size(max = 1000) String notes) {}

    /**
     * {@code POST /deferrals/from-journal-line}: defer (part of) a posted invoice / bill line. A line on a P&amp;L
     * account is first reclassified to the deferral account; a line already on a balance-sheet account is the balance.
     */
    public record DeferralFromLineInput(
            @NotBlank String journalLineId,
            @Size(max = 200) String name,
            String sourceAccountId,
            String targetAccountId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotNull @Pattern(regexp = DAY) String startDate,
            @NotNull @Min(1) @Max(600) Integer months,
            @Pattern(regexp = "monthly|daily") String method,
            @Size(max = 1000) String notes) {}

    /** {@code POST /deferrals/:id/release} and {@code POST /deferrals/run}: release slices due up to {@code asOf}. */
    public record ReleaseInput(@Pattern(regexp = DAY) String asOf) {}

    /** {@code POST /deferrals/:id/cancel}. */
    public record CancelInput(@Size(max = 1000) String reason) {}

    // ------------------------------------------------------------------ bank matching rules

    /** {@code POST /bank-reconciliation/rules} and {@code PATCH /bank-reconciliation/rules/:id}. */
    public record BankRuleInput(
            String bankAccountId,
            @Size(min = 1, max = 200) String name,
            @Min(0) @Max(100000) Integer priority,
            Boolean isActive,
            @Pattern(regexp = "description|reference|any") String matchField,
            @Pattern(regexp = "contains|regex") String matchType,
            @Size(max = 500) String pattern,
            @DecimalMin("0") BigDecimal minAmount,
            @DecimalMin("0") BigDecimal maxAmount,
            @Pattern(regexp = "inbound|outbound|any") String direction,
            @Pattern(regexp = "match_payment|post_entry") String action,
            String counterAccountId,
            String taxId,
            String partnerId,
            @Size(max = 20) String journalCode,
            @Min(0) @Max(60) Integer dateToleranceDays,
            @Size(max = 500) String entryDescription) {}

    /** One payment share of a statement line; {@code amount} defaults to what is still open on both sides. */
    public record Allocation(@NotBlank String paymentId, @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {}

    /** {@code POST /bank-reconciliation/lines/:id/allocate}: one line to one or several payments. */
    public record LineAllocationInput(@NotEmpty @Size(max = 100) List<@Valid @NotNull Allocation> allocations) {}

    /** {@code POST /bank-reconciliation/payments/:id/allocate}: several lines to one payment. */
    public record PaymentAllocationInput(@NotEmpty @Size(max = 100) List<@NotBlank String> lineIds) {}
}
