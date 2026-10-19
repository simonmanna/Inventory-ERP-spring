package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.accounting.application.MoneySettlementService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /cash-sessions} (port of {@code cash-session.controller.ts}). */
@RestController
@RequestMapping("/cash-sessions")
public class CashSessionController {

    static final String ISO = AccountingDtos.ISO_DATE;

    /** {@code OpenSessionDto}. */
    public record OpenSessionRequest(
            String openingSourceAccountId,
            Map<String, Object> openingAccounts,
            @NotNull(message = "must be a string") String cashRegisterId,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal openingFloat,
            String notes,
            Boolean autoAdjust,
            Map<String, Object> openingDenomination,
            @Pattern(regexp = ISO, message = "must be a valid ISO 8601 date string") String occurredAt) {}

    /** {@code CloseSessionDto}. */
    public record CloseSessionRequest(
            Map<String, Object> closingAccounts,
            Map<String, Object> uncountedAccounts,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal pendingSyncCount,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal closingCounted,
            String notes,
            String varianceReason,
            Boolean autoAdjustCash,
            @OneOf({"pending_review", "approved", "rejected"}) String varianceStatus,
            String approvedById,
            String approverEmail,
            String managerPin,
            Map<String, Object> closingDenomination,
            @Pattern(regexp = ISO, message = "must be a valid ISO 8601 date string") String occurredAt,
            String sessionId) {}

    /** {@code RecordMovementDto}. */
    public record RecordMovementRequest(
            @NotNull(message = "must be a string") String counterpartAccountId,
            String correctionOfSessionId,
            String sessionId,
            @NotNull(message = "movementType must be one of the following values: pay_in, pay_out, adjustment")
            @OneOf({"pay_in", "pay_out", "adjustment"}) String movementType,
            @NotNull(message = "must be a number conforming to the specified constraints") BigDecimal amount,
            String reason,
            String approvedById,
            String approverEmail,
            String managerPin,
            @Pattern(regexp = ISO, message = "must be a valid ISO 8601 date string") String occurredAt) {}

    /** {@code ForceCloseSessionDto}. */
    public record ForceCloseRequest(
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal closingCounted,
            @NotNull(message = "must be a string") String notes,
            String varianceReason,
            Map<String, Object> closingAccounts,
            Map<String, Object> uncountedAccounts,
            Map<String, Object> closingDenomination) {}

    /** {@code BankDepositDto}. */
    public record BankDepositRequest(
            @NotNull(message = "must be a string") String destinationAccountId,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal amount,
            @NotNull(message = "must be a string") String bankName,
            String reference,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal remainingFloat,
            String notes) {}

    /** {@code VarianceUpdateDto}. */
    public record VarianceUpdateRequest(
            @NotNull(message = "must be a string") String reason,
            @OneOf({"pending_review", "approved", "rejected"}) String status) {}

    /** {@code ReconcileSessionDto}. */
    public record ReconcileSessionRequest(
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal depositAmount,
            String bankName,
            String reference,
            String notes) {}

    /** {@code DailyResetDto}. */
    public record DailyResetRequest(@NotNull(message = "must be a string") String date, Boolean force) {}

    /** {@code TenderSettlementDto}. */
    public record TenderSettlementRequest(
            @NotNull(message = "must be a string") String sourceAccountId,
            @NotNull(message = "must be a string") String destinationAccountId,
            String cashSessionId,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0.000001", message = "must not be less than 0.000001") BigDecimal grossAmount,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal feeAmount,
            String feeAccountId,
            @NotNull(message = "must be a string") String reference,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = ISO, message = "must be a valid ISO 8601 date string") String settledAt) {}

    private final CashSessionService sessions;
    private final MoneySettlementService settlements;

    public CashSessionController(CashSessionService sessions, MoneySettlementService settlements) {
        this.sessions = sessions;
        this.settlements = settlements;
    }

    @GetMapping("/open")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> findOpen(@RequestParam(required = false) String registerId) {
        return sessions.findOpen(registerId);
    }

    @GetMapping("/history")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> history(@RequestParam(required = false) String page, @RequestParam(required = false) String perPage,
            @RequestParam(required = false) String registerId) {
        int p = page == null || page.isEmpty() ? 1 : (int) Math.max(1, JsNumbers.number(page));
        int pp = perPage == null || perPage.isEmpty() ? 20 : (int) Math.min(100, Math.max(1, JsNumbers.number(perPage)));
        return sessions.history(p, pp, registerId);
    }

    @GetMapping("/report/daily")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> dailyReconciliation(@RequestParam(required = false) String date) {
        return sessions.dailyReconciliation(date == null ? "" : date);
    }

    @GetMapping("/{id}/reconciliation")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> reconciliation(@PathVariable String id) {
        return sessions.reconciliation(id);
    }

    @PostMapping("/tender-settlements")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__RECONCILE)
    public Map<String, Object> settleTender(@Valid @RequestBody TenderSettlementRequest dto) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceAccountId", dto.sourceAccountId());
        input.put("destinationAccountId", dto.destinationAccountId());
        input.put("cashSessionId", dto.cashSessionId());
        input.put("grossAmount", dto.grossAmount());
        input.put("feeAmount", dto.feeAmount());
        input.put("feeAccountId", dto.feeAccountId());
        input.put("reference", dto.reference());
        input.put("settledAt", dto.settledAt());
        return sessions.settleTender(input);
    }

    @GetMapping("/tender-settlements")
    @RequirePermissions(Permissions.CASH_SESSION__RECONCILE)
    public Map<String, Object> settlementHistory(@RequestParam Map<String, String> query) {
        QueryParams.only(query, "sourceAccountId", "from", "to", "page", "pageSize");
        List<String> errors = new ArrayList<>();
        for (String k : List.of("from", "to")) {
            String v = query.get(k);
            if (v != null && !v.matches(ISO)) {
                errors.add(k + " must be a valid ISO 8601 date string");
            }
        }
        for (String k : List.of("page", "pageSize")) {
            String v = query.get(k);
            if (v != null && Double.isNaN(JsNumbers.number(v))) {
                errors.add(k + " must be a number conforming to the specified constraints");
            }
        }
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return settlements.history(query.get("sourceAccountId"), query.get("from"), query.get("to"),
                query.get("page") == null ? null : JsNumbers.number(query.get("page")),
                query.get("pageSize") == null ? null : JsNumbers.number(query.get("pageSize")));
    }

    @GetMapping("/tender-settlements/sources")
    @RequirePermissions(Permissions.CASH_SESSION__RECONCILE)
    public Map<String, Object> settlementSources() {
        return settlements.sources();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return sessions.findById(id);
    }

    @GetMapping("/{id}/movements")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> getMovements(@PathVariable String id) {
        return sessions.getMovements(id);
    }

    @GetMapping("/{id}/expected")
    @RequirePermissions(Permissions.CASH_SESSION__READ)
    public Map<String, Object> expected(@PathVariable String id) {
        BigDecimal value = sessions.expectedCash(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        out.put("expectedCash", com.inventoryerp.accounting.application.PostingService.plain(value));
        return out;
    }

    @PostMapping("/open")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__OPEN)
    public Map<String, Object> open(@Valid @RequestBody OpenSessionRequest dto) {
        return sessions.open(new CashSessionService.OpenInput(dto.openingSourceAccountId(), dto.openingAccounts(),
                dto.cashRegisterId(), dto.openingFloat(), dto.notes(), dto.openingDenomination(), dto.autoAdjust(),
                dto.occurredAt()));
    }

    @PostMapping("/close")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__CLOSE)
    public Map<String, Object> close(@Valid @RequestBody CloseSessionRequest dto) {
        // Nest maps every field except autoAdjustCash (not forwarded to the service).
        return sessions.close(new CashSessionService.CloseInput(dto.closingAccounts(), dto.uncountedAccounts(),
                dto.pendingSyncCount(), dto.closingCounted(), dto.notes(), dto.varianceReason(), dto.varianceStatus(), null,
                dto.approvedById(), dto.approverEmail(), dto.managerPin(), dto.closingDenomination(), dto.occurredAt(),
                dto.sessionId()), false);
    }

    @PostMapping("/movement")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__OPEN)
    public Map<String, Object> recordMovement(@Valid @RequestBody RecordMovementRequest dto) {
        return sessions.recordMovement(dto.sessionId(), new CashSessionService.MovementInput(dto.counterpartAccountId(),
                dto.correctionOfSessionId(), dto.movementType(), dto.amount(), dto.reason(), dto.occurredAt(),
                dto.approvedById(), dto.approverEmail(), dto.managerPin()));
    }

    @PostMapping("/{id}/force-close")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__FORCE_CLOSE)
    public Map<String, Object> forceClose(@PathVariable String id, @Valid @RequestBody ForceCloseRequest dto) {
        return sessions.close(new CashSessionService.CloseInput(dto.closingAccounts(), dto.uncountedAccounts(), null,
                dto.closingCounted(), dto.notes(), dto.varianceReason(), null, null, null, null, null,
                dto.closingDenomination(), null, id), true);
    }

    @PostMapping("/{id}/banking")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__CLOSE)
    public Map<String, Object> banking(@PathVariable String id, @Valid @RequestBody BankDepositRequest dto) {
        return sessions.recordBankDeposit(id, new CashSessionService.BankDepositInput(dto.destinationAccountId(), dto.amount(),
                dto.bankName(), dto.reference(), dto.remainingFloat(), dto.notes()));
    }

    @PatchMapping("/{id}/variance")
    @RequirePermissions(Permissions.CASH_SESSION__APPROVE_VARIANCE)
    public Map<String, Object> updateVariance(@PathVariable String id, @Valid @RequestBody VarianceUpdateRequest dto) {
        return sessions.updateVariance(id, dto.reason(), dto.status());
    }

    @PatchMapping("/{id}/reconcile")
    @Idempotent
    @RequirePermissions(Permissions.CASH_SESSION__RECONCILE)
    public Map<String, Object> reconcile(@PathVariable String id, @Valid @RequestBody(required = false) ReconcileSessionRequest dto) {
        return sessions.reconcile(id, dto == null ? null : dto.depositAmount(), dto == null ? null : dto.notes());
    }

    @PostMapping("/reconcile/daily")
    @Idempotent
    @RequirePermissions(Permissions.CASH_SESSION__RECONCILE)
    public Map<String, Object> dailyReset(@Valid @RequestBody DailyResetRequest dto) {
        return sessions.dailyReset(dto.date());
    }
}
