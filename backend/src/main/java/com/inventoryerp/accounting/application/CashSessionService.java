package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.CredentialGuard;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cashier shifts (port of {@code cash-session.service.ts}): open / close / force-close / handover, drawer
 * movements, banking, variance review, reconcile and daily reports. Every drawer movement, deposit and closing
 * variance posts to the GL in the same transaction as the drawer write; money operations record their
 * idempotency outcome in that transaction too.
 */
@Service
public class CashSessionService {

    private static final Logger log = LoggerFactory.getLogger(CashSessionService.class);

    /** Open body. */
    public record OpenInput(String openingSourceAccountId, Map<String, Object> openingAccounts, String cashRegisterId,
            BigDecimal openingFloat, String notes, Map<String, Object> openingDenomination, Boolean autoAdjust,
            String occurredAt, String adjustmentReason, String approvedById, String approverEmail, String managerPin) {}

    /** Close / force-close body. */
    public record CloseInput(Map<String, Object> closingAccounts, Map<String, Object> uncountedAccounts,
            BigDecimal pendingSyncCount, BigDecimal closingCounted, String notes, String varianceReason,
            String varianceStatus, Boolean autoAdjustCash, String approvedById, String approverEmail, String managerPin,
            Map<String, Object> closingDenomination, String occurredAt, String sessionId) {}

    /** Manual drawer movement. */
    public record MovementInput(String counterpartAccountId, String correctionOfSessionId, String movementType,
            BigDecimal amount, String reason, String occurredAt, String approvedById, String approverEmail,
            String managerPin) {}

    /** Banking body. */
    public record BankDepositInput(String destinationAccountId, BigDecimal amount, String bankName, String reference,
            BigDecimal remainingFloat, String notes) {}

    /** Handover body (POS shift handover). */
    public record HandoverInput(String cashRegisterId, BigDecimal closingCounted, String incomingUserId,
            String varianceReason, BigDecimal openingFloat, String notes, String approvedById,
            Map<String, Object> closingDenomination, Map<String, Object> closingAccounts,
            Map<String, Object> uncountedAccounts) {}

    private record Approval(String verifiedManagerId, String approverId, String approverEmail, String managerPin) {}

    private record Closing(Map<String, Object> reconciliation, Map<String, Map<String, Object>> closingAccounts,
            BigDecimal expected, BigDecimal difference, String reason, String varianceStatus, String approvedById,
            String autoDirection, BigDecimal autoAmount) {

        Closing withRecomputed(Map<String, Object> rec, BigDecimal exp, BigDecimal diff) {
            return new Closing(rec, closingAccounts, exp, diff, reason, varianceStatus, approvedById, autoDirection, autoAmount);
        }
    }

    private final JdbcClient jdbc;
    private final EventBus events;
    private final AuditService audit;
    private final PasswordService password;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final SessionReconciliation recon;
    private final PosPaymentMethodService paymentMethods;
    private final ObjectProvider<CashSessionHooks> hooks;
    private final TransactionTemplate tx;
    private final BigDecimal largeVarianceThreshold;

    private final CredentialGuard credentialGuard;

    public CashSessionService(JdbcClient jdbc, EventBus events, AuditService audit, PasswordService password,
            PostingService posting, AccountDeterminationService determination, SessionReconciliation recon,
            PosPaymentMethodService paymentMethods, ObjectProvider<CashSessionHooks> hooks,
            PlatformTransactionManager txm,
            @Value("${CASH_VARIANCE_APPROVAL_THRESHOLD:}") String threshold, CredentialGuard credentialGuard) {
        this.credentialGuard = credentialGuard;
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.password = password;
        this.posting = posting;
        this.determination = determination;
        this.recon = recon;
        this.paymentMethods = paymentMethods;
        this.hooks = hooks;
        this.tx = new TransactionTemplate(txm);
        double raw = com.inventoryerp.kernel.common.JsNumbers.number(threshold);
        this.largeVarianceThreshold = Money.dec(!Double.isNaN(raw) && !Double.isInfinite(raw) && raw > 0 ? raw : 20000);
    }

    private <T> T inTx(Supplier<T> body) {
        return tx.execute(s -> body.get());
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Cash variance at or above which a manager must approve the close. Post-parity (owner-approved 2026-11-10): the
     * organization setting {@code cash.varianceApprovalThreshold} (a positive number in the organization's currency)
     * wins over the deployment-wide {@code CASH_VARIANCE_APPROVAL_THRESHOLD} (default 20000), so a USD or EUR
     * organization is not left with a 20,000 threshold.
     */
    BigDecimal varianceApprovalThreshold(String organizationId) {
        String raw = jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"organizationId\" = ? AND \"scopeType\" = 'organization'"
                        + " AND \"scopeId\" = '' AND key = 'cash.varianceApprovalThreshold' LIMIT 1")
                .param(organizationId).query(String.class).optional().orElse(null);
        if (raw != null) {
            try {
                BigDecimal v = new BigDecimal(raw.replace("\"", "").trim());
                if (v.signum() > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // fall back to the deployment default
            }
        }
        return largeVarianceThreshold;
    }

    /** The first argument with non-blank text, trimmed; {@code null} when none. */
    private static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private static ApiException bad(Map<String, Object> body) {
        return ApiException.withBody(HttpStatus.BAD_REQUEST, body);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> session(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
    }

    private void drainStockPostings(String cashSessionId) {
        if (cashSessionId == null) {
            return;
        }
        CashSessionHooks h = hooks.getIfAvailable();
        if (h == null) {
            return;
        }
        try {
            h.drainStockPostings(cashSessionId);
        } catch (RuntimeException e) {
            log.warn("stock posting drain for session {} failed: {}", cashSessionId, e.toString());
        }
    }

    // ------------------------------------------------------------------ open

    /** Open a new session; fails if the register already has one open. */
    public Map<String, Object> open(OpenInput dto) {
        String organizationId = org();
        String userId = TenantContext.userId();
        if (userId == null) {
            throw ApiException.badRequest("No user in tenant context");
        }
        BigDecimal openingFloat = dto.openingFloat() == null ? BigDecimal.ZERO : dto.openingFloat();
        assertDenominationTotal(dto.openingDenomination(), openingFloat, "opening float");
        return inTx(() -> {
            if (openingFloat.signum() < 0) {
                throw ApiException.badRequest("Invalid counted opening float");
            }
            jdbc.sql("SELECT id FROM \"CashRegister\" WHERE \"id\" = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(dto.cashRegisterId(), organizationId).query(String.class).list();
            Map<String, Object> register = SqlRows.one(jdbc.sql("SELECT * FROM \"CashRegister\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(dto.cashRegisterId(), organizationId));
            if (register == null || !Boolean.TRUE.equals(register.get("isActive"))) {
                throw ApiException.notFound("Active cash register not found");
            }
            long existing = jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"cashRegisterId\" = ?"
                    + " AND status = 'open'").params(organizationId, dto.cashRegisterId()).query(Long.class).single();
            if (existing > 0) {
                throw ApiException.badRequest("A session is already open on register " + register.get("code"));
            }
            String drawerId = (String) register.get("defaultAccountId");
            String drawerCategory = drawerId == null ? null : categoryOf(drawerId);
            if (drawerCategory == null || !List.of("cash", "petty_cash").contains(drawerCategory)) {
                throw ApiException.badRequest("Configure an active drawer cash account before opening");
            }
            long shared = jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE \"organizationId\" = ? AND id <> ?"
                            + " AND \"isActive\" = true AND \"defaultAccountId\" = ? AND \"deletedAt\" IS NULL")
                    .params(organizationId, register.get("id"), drawerId).query(Long.class).single();
            if (shared > 0) {
                throw ApiException.badRequest("Each register needs a distinct drawer cash account");
            }
            lockAccount(drawerId);
            BigDecimal ledger = SessionReconciliation.accountLedgerBalance(jdbc, organizationId, drawerId);
            BigDecimal funding = openingFloat.subtract(ledger);
            boolean autoAdjust = Boolean.TRUE.equals(dto.autoAdjust());
            String clearingId = autoAdjust ? drawerAdjustmentClearing(drawerId) : null;
            if (funding.signum() < 0 && !autoAdjust) {
                throw ApiException.badRequest("Opening count is below the drawer ledger. Reconcile the prior count or"
                        + " record the removal before opening");
            }
            // Post-parity hardening (owner-approved 2026-11-10): an opening count below the drawer ledger moves the
            // missing cash to the clearing account. Nest let any opener do that silently (and the web ticks
            // auto-adjust by default), so cash missing between shifts vanished with no reason or approver. It now needs
            // a reason and a cash_session:approve_variance holder: the opener when they hold it, else a manager PIN.
            String adjustmentReason = null;
            String adjustmentApprover = null;
            if (funding.signum() < 0) {
                adjustmentReason = firstText(dto.adjustmentReason(), dto.notes());
                if (adjustmentReason == null) {
                    throw bad(body("code", "OPENING_SHORTAGE_REASON_REQUIRED", "message",
                            "Explain why the drawer holds less than its ledger before opening",
                            "drawerLedger", plain(ledger), "shortage", plain(funding.abs())));
                }
                if (permissionsOf(userId).contains(Permissions.CASH_SESSION__APPROVE_VARIANCE)) {
                    adjustmentApprover = userId;
                } else if (dto.approvedById() == null && dto.approverEmail() == null) {
                    throw bad(body("code", "MANAGER_APPROVAL_REQUIRED", "message",
                            "Removing cash from the drawer at opening requires manager approval",
                            "drawerLedger", plain(ledger), "shortage", plain(funding.abs())));
                } else {
                    adjustmentApprover = (String) assertManagerApproval(dto.approvedById(), dto.approverEmail(), dto.managerPin(), userId,
                            Permissions.CASH_SESSION__APPROVE_VARIANCE, "removing cash from the drawer at opening").get("id");
                }
            }
            if (funding.signum() > 0 && dto.openingSourceAccountId() != null) {
                String sourceCategory = categoryOf(dto.openingSourceAccountId());
                if (sourceCategory == null || dto.openingSourceAccountId().equals(drawerId)
                        || !List.of("cash", "petty_cash", "bank").contains(sourceCategory)) {
                    throw ApiException.badRequest("Choose a different cash safe or bank funding account");
                }
                lockAccount(dto.openingSourceAccountId());
                if (funding.compareTo(SessionReconciliation.accountLedgerBalance(jdbc, organizationId,
                        dto.openingSourceAccountId())) > 0) {
                    throw ApiException.badRequest("The funding account has insufficient recorded funds");
                }
            } else if (funding.signum() > 0 && !autoAdjust) {
                throw ApiException.badRequest("Choose the source of added float and enter its reason");
            }
            Instant occurredAt = OccurredAt.resolve(dto.occurredAt());
            Instant at = occurredAt != null ? occurredAt : Instant.now();
            ZoneId zone = OrgDates.orgTimezone(jdbc, organizationId);
            String id = UUID.randomUUID().toString();
            Map<String, Map<String, Object>> openingAccounts = recon.accountObservations(organizationId, dto.openingAccounts());
            jdbc.sql("INSERT INTO \"CashSession\" (id, \"organizationId\", \"businessDate\", \"cashRegisterId\", \"branchId\","
                            + " \"drawerAccountId\", \"registerLocationId\", \"userId\", status, \"openingFloat\", \"openingAccounts\","
                            + " \"openingDenomination\", notes, \"openedAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?::jsonb, ?::jsonb, ?, ?, now())")
                    .params(id, organizationId, java.sql.Date.valueOf(OrgDates.tradingDate(at, zone).atZone(java.time.ZoneOffset.UTC).toLocalDate()),
                            dto.cashRegisterId(), register.get("branchId"), drawerId, register.get("locationId"), userId,
                            openingFloat, EntityJson.toJson(openingAccounts), sanitizeDenomination(dto.openingDenomination()),
                            dto.notes(), occurredAt != null ? FiscalPeriodControl.ts(occurredAt)
                                    : FiscalPeriodControl.ts(Instant.now()))
                    .update();
            if (funding.signum() > 0) {
                if (dto.openingSourceAccountId() != null) {
                    posting.post(PostingRequest.of("CASH", at, "Opening float: " + dto.notes(), "cash_session_opening", id,
                            "cash_session_opening:" + id, List.of(PostingRequest.Line.debit(drawerId, plain(funding), null),
                                    PostingRequest.Line.credit(dto.openingSourceAccountId(), plain(funding), null))));
                } else {
                    posting.post(PostingRequest.of("CASH", at, "Opening adjustment: cash added to the drawer (owner cash-in)"
                            + " to match the declared opening float", "cash_session_opening", id, "cash_session_opening:" + id,
                            List.of(PostingRequest.Line.debit(drawerId, plain(funding), null),
                                    PostingRequest.Line.credit(clearingId, plain(funding), null))));
                }
            } else if (funding.signum() < 0) {
                posting.post(PostingRequest.of("CASH", at, "Opening adjustment: cash withdrawn from the drawer to match the"
                        + " declared opening float — " + adjustmentReason, "cash_session_opening", id, "cash_session_opening:" + id,
                        List.of(PostingRequest.Line.debit(clearingId, plain(funding.abs()), null),
                                PostingRequest.Line.credit(drawerId, plain(funding.abs()), null))));
            }
            Map<String, Object> values = body("cashRegisterId", dto.cashRegisterId(), "openingFloat", plain(openingFloat));
            if (funding.signum() != 0) {
                values.put("autoAdjusted", autoAdjust);
                values.put("drawerLedger", plain(ledger));
                values.put("adjustment", plain(funding.abs()));
                values.put("adjustmentKind", funding.signum() > 0 ? "cash_in" : "withdrawal");
                if (adjustmentReason != null) {
                    values.put("adjustmentReason", adjustmentReason);
                    values.put("adjustmentApprovedById", adjustmentApprover);
                }
            }
            audit.recordInTx(AuditInput.of("CashSession", id, "create", null, values));
            AfterCommit.run(() -> events.publish("cash.session.opened", Map.of("organizationId", organizationId,
                    "sessionId", id, "cashRegisterId", dto.cashRegisterId())));
            Map<String, Object> session = session(id);
            BusinessOutcome.record(jdbc, session, true);
            return session;
        });
    }

    // ------------------------------------------------------------------ close

    /** Close a shift (any {@code cash_session:close} holder may close any open shift); {@code force} for managers. */
    public Map<String, Object> close(CloseInput dto, boolean force) {
        String organizationId = org();
        String actorId = TenantContext.userId();
        if (actorId == null) {
            throw ApiException.badRequest("No user in tenant context");
        }
        BigDecimal counted = dto.closingCounted();
        if (counted == null || counted.signum() < 0) {
            throw ApiException.badRequest("Counted cash cannot be negative");
        }
        assertDenominationTotal(dto.closingDenomination(), counted, "closing count");
        if (force) {
            if (dto.sessionId() == null) {
                throw ApiException.badRequest("Choose the shift to force-close");
            }
            if (dto.notes() == null || dto.notes().trim().isEmpty()) {
                throw ApiException.badRequest("A reason is required to force-close a shift");
            }
        }
        drainStockPostings(dto.sessionId() != null ? dto.sessionId()
                : jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND status = 'open'"
                        + " ORDER BY \"openedAt\" LIMIT 1")
                        .params(organizationId, actorId).query(String.class).optional().orElse(null));
        return inTx(() -> {
            Map<String, Object> found = dto.sessionId() != null ? session(dto.sessionId()) : requireOpenSession();
            if (found == null) {
                throw ApiException.notFound("No open cash session");
            }
            if (force && actorId.equals(found.get("userId"))) {
                throw ApiException.forbidden("Close your own shift normally; force-close is for another cashier's shift");
            }
            boolean onBehalf = !force && !actorId.equals(found.get("userId"));
            Map<String, Object> s = lockOpenSession((String) found.get("id"));
            long openDeadLetters = jdbc.sql("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE \"organizationId\" = ? AND status = 'open'")
                    .param(organizationId).query(Long.class).single();
            double pending = dto.pendingSyncCount() == null ? 0 : dto.pendingSyncCount().doubleValue();
            if (pending > 0 || openDeadLetters > 0) {
                log.warn("Closing shift {} with {} client-reported pending sync(s) and {} open dead-letter operation(s)",
                        s.get("id"), pending, openDeadLetters);
            }
            Closing closing = validateClosing(s, counted, dto.closingAccounts(), dto.uncountedAccounts(),
                    dto.varianceReason(), Boolean.TRUE.equals(dto.autoAdjustCash()),
                    force ? new Approval(actorId, null, null, null)
                            : new Approval(null, dto.approvedById(), dto.approverEmail(), dto.managerPin()), false);
            if (closing.autoDirection() != null) {
                applyClosingCashAdjustment(s, closing.autoDirection(), closing.autoAmount());
                BigDecimal expected = computeExpected(s);
                closing = closing.withRecomputed(recon.reconcileSession(organizationId, s), expected, counted.subtract(expected));
            }
            Instant occurred = OccurredAt.resolve(dto.occurredAt());
            Instant closedAt = occurred != null ? occurred : Instant.now();
            String closedForNote = onBehalf ? closedOnBehalfNote(actorId, (String) s.get("userId")) : null;
            String notes;
            if (force) {
                notes = (s.get("notes") != null ? s.get("notes") + " | " : "") + "Force-closed by manager: " + dto.notes().trim();
            } else {
                List<String> parts = new ArrayList<>();
                String base = dto.notes() != null ? dto.notes() : (String) s.get("notes");
                if (base != null && !base.isEmpty()) {
                    parts.add(base);
                }
                if (closedForNote != null) {
                    parts.add(closedForNote);
                }
                notes = parts.isEmpty() ? null : String.join(" | ", parts);
            }
            finishClosing(s, closing, counted, closedAt, notes, dto.closingDenomination(),
                    body("kind", force ? "force_close" : "close", "actorId", actorId));
            Closing done = closing;
            String sid = (String) s.get("id");
            AfterCommit.run(() -> events.publish("cash.session.closed", Map.of("organizationId", organizationId,
                    "sessionId", sid, "expected", plain(done.expected()), "counted", plain(counted),
                    "variance", plain(done.difference()))));
            Map<String, Object> result = session(sid);
            BusinessOutcome.record(jdbc, result, true);
            return result;
        });
    }

    /** Shift handover: close the outgoing shift and open the incoming one carrying the counted cash forward. */
    public Map<String, Object> handover(HandoverInput dto) {
        String organizationId = org();
        BigDecimal counted = dto.closingCounted();
        if (counted == null || counted.signum() < 0) {
            throw ApiException.badRequest("Counted cash cannot be negative");
        }
        assertDenominationTotal(dto.closingDenomination(), counted, "closing count");
        BigDecimal opening = dto.openingFloat() != null ? dto.openingFloat() : counted;
        if (opening.compareTo(counted) != 0) {
            throw ApiException.badRequest("Handover must carry the counted physical cash; record float changes as a separate movement");
        }
        if (dto.approvedById() == null) {
            throw ApiException.badRequest("A manager must approve the handover");
        }
        drainStockPostings(jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"cashRegisterId\" = ?"
                + " AND status = 'open' LIMIT 1").params(organizationId, dto.cashRegisterId()).query(String.class).optional().orElse(null));
        return inTx(() -> {
            jdbc.sql("SELECT id FROM \"CashRegister\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(dto.cashRegisterId(), organizationId).query(String.class).list();
            String foundId = jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"cashRegisterId\" = ?"
                    + " AND status = 'open' LIMIT 1").params(organizationId, dto.cashRegisterId()).query(String.class).optional().orElse(null);
            if (foundId == null) {
                throw ApiException.notFound("No open session on this register");
            }
            Map<String, Object> outgoing = lockOpenSession(foundId);
            if (dto.incomingUserId().equals(outgoing.get("userId"))) {
                throw ApiException.badRequest("The incoming cashier must be a different person");
            }
            if (dto.approvedById().equals(outgoing.get("userId")) || dto.approvedById().equals(dto.incomingUserId())) {
                throw ApiException.forbidden("The approving manager must be neither the outgoing nor the incoming cashier");
            }
            long incomingUser = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\" = true"
                    + " AND \"deletedAt\" IS NULL").params(dto.incomingUserId(), organizationId).query(Long.class).single();
            if (incomingUser == 0) {
                throw ApiException.badRequest("Incoming cashier not found");
            }
            Closing closing = validateClosing(outgoing, counted, dto.closingAccounts(), dto.uncountedAccounts(),
                    dto.varianceReason(), false, new Approval(dto.approvedById(), null, null, null), true);
            finishClosing(outgoing, closing, counted, Instant.now(), (String) outgoing.get("notes"), dto.closingDenomination(),
                    body("kind", "handover_out", "actorId", TenantContext.userId(), "incomingUserId", dto.incomingUserId()));
            Map<String, Object> openingAccounts = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : closing.closingAccounts().entrySet()) {
                Map<String, Object> row = e.getValue();
                if (Boolean.TRUE.equals(row.get("notCounted"))) {
                    continue;
                }
                openingAccounts.put(e.getKey(), body("accountId", e.getKey(), "code", row.get("code"), "name", row.get("name"),
                        "accountType", row.get("accountType"), "observed", row.get("observed"), "ledger", row.get("ledger"),
                        "carriedFromSessionId", outgoing.get("id")));
            }
            String incomingId = UUID.randomUUID().toString();
            Object businessDate = outgoing.get("businessDate");
            jdbc.sql("INSERT INTO \"CashSession\" (id, \"organizationId\", \"cashRegisterId\", \"drawerAccountId\","
                            + " \"registerLocationId\", \"branchId\", \"businessDate\", \"userId\", status, \"openingFloat\","
                            + " \"openingAccounts\", \"openingDenomination\", notes, \"openedAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?::jsonb, ?::jsonb, ?, now(), now())")
                    .params(incomingId, organizationId, outgoing.get("cashRegisterId"), outgoing.get("drawerAccountId"),
                            outgoing.get("registerLocationId"), outgoing.get("branchId"),
                            businessDate == null ? null : java.sql.Date.valueOf((java.time.LocalDate) businessDate),
                            dto.incomingUserId(), opening, EntityJson.toJson(openingAccounts),
                            sanitizeDenomination(dto.closingDenomination()),
                            dto.notes() != null ? dto.notes() : "Opened by handover from session " + outgoing.get("id"))
                    .update();
            // Post-parity (owner-approved 2026-11-10): open tabs of the outgoing shift move to the incoming one, so a
            // handover works in the middle of service (Nest refused it while any table was open in the organization).
            int carried = jdbc.sql("UPDATE \"Order\" SET \"cashSessionId\" = ?, \"updatedAt\" = now()"
                            + " WHERE \"organizationId\" = ? AND \"cashSessionId\" = ? AND \"invoiceId\" IS NULL"
                            + " AND status::text IN ('draft', 'confirmed', 'in_progress')")
                    .params(incomingId, organizationId, outgoing.get("id")).update();
            jdbc.sql("UPDATE \"PosHold\" SET \"cashSessionId\" = ?, \"updatedAt\" = now()"
                            + " WHERE \"organizationId\" = ? AND \"cashSessionId\" = ? AND status::text = 'open'")
                    .params(incomingId, organizationId, outgoing.get("id")).update();
            audit.recordInTx(AuditInput.of("CashSession", incomingId, "create", null, body("kind", "handover_in",
                    "handoverFromSessionId", outgoing.get("id"), "userId", dto.incomingUserId(), "openingFloat", plain(opening),
                    "approvedById", dto.approvedById(), "carriedOpenOrders", carried)));
            Map<String, Object> result = body("outgoingSessionId", outgoing.get("id"), "incomingSessionId", incomingId,
                    "expected", plain(closing.expected()), "counted", plain(counted), "variance", plain(closing.difference()));
            AfterCommit.run(() -> events.publish("cash.session.handover", body("organizationId", organizationId,
                    "outgoingSessionId", outgoing.get("id"), "incomingSessionId", incomingId,
                    "cashRegisterId", outgoing.get("cashRegisterId"), "incomingUserId", dto.incomingUserId(),
                    "variance", plain(closing.difference()))));
            BusinessOutcome.record(jdbc, result, true);
            return result;
        });
    }

    private String closedOnBehalfNote(String actorId, String cashierId) {
        return "Closed by " + userName(actorId) + " on behalf of " + userName(cashierId);
    }

    private String userName(String id) {
        if (id == null) {
            return "unknown";
        }
        Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\", email FROM \"User\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (u == null) {
            return "unknown";
        }
        String name = (String.valueOf(u.get("firstName") == null ? "" : u.get("firstName")) + " "
                + (u.get("lastName") == null ? "" : u.get("lastName"))).trim();
        return name.isEmpty() ? (String) u.get("email") : name;
    }

    /** The single closing rule set (close, force-close, handover). */
    @SuppressWarnings("unchecked")
    private Closing validateClosing(Map<String, Object> session, BigDecimal counted, Map<String, Object> observedInput,
            Map<String, Object> uncountedInput, String varianceReason, boolean autoAdjustCash, Approval approval,
            boolean carryOpenOrders) {
        String organizationId = org();
        // One floor per branch (see SessionReconciliation#SAME_BRANCH): closes of different branches no longer queue.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))::text")
                .param("pos-floor-close:" + organizationId + ":" + session.get("branchId"))
                .query(String.class).single();
        Map<String, Object> reconciliation = recon.reconcileSession(organizationId, session);
        long openOrderCount = ((Number) reconciliation.get("openOrderCount")).longValue();
        if (openOrderCount > 0 && !carryOpenOrders) {
            throw bad(body("code", "OPEN_ORDERS", "message", openOrderCount + " open order(s) must be settled or voided before"
                    + " closing — resolve unsettled orders first", "openOrderCount", openOrderCount,
                    "openOrders", reconciliation.get("openOrders"), "reconciliation", reconciliation));
        }
        if (((Number) reconciliation.get("pendingPayments")).longValue() > 0
                || !((List<String>) reconciliation.get("issues")).isEmpty()) {
            throw bad(body("code", "RECONCILIATION_ISSUES", "message",
                    "Resolve unsettled orders, payments and posting differences before closing", "reconciliation", reconciliation));
        }
        Map<String, Object>[] manager = new Map[1];
        java.util.function.Function<String, Map<String, Object>> approve = actionLabel -> {
            if (manager[0] != null) {
                return manager[0];
            }
            if (approval.verifiedManagerId() != null) {
                Map<String, Object> m = SqlRows.one(jdbc.sql("SELECT id FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(approval.verifiedManagerId(), organizationId));
                if (m == null) {
                    throw ApiException.notFound("Approving manager not found");
                }
                if (m.get("id").equals(session.get("userId"))) {
                    throw ApiException.forbidden("The session cashier cannot approve " + actionLabel);
                }
                if (!permissionsOf((String) m.get("id")).contains("cash_session:approve_variance")) {
                    throw ApiException.forbidden("Approver does not hold cash_session:approve_variance");
                }
                manager[0] = m;
            } else {
                manager[0] = assertManagerApproval(approval.approverId(), approval.approverEmail(), approval.managerPin(),
                        (String) session.get("userId"), "cash_session:approve_variance", actionLabel);
            }
            return manager[0];
        };
        if (approval.verifiedManagerId() != null) {
            approve.apply("this shift close");
        }
        String reason = varianceReason == null || varianceReason.trim().isEmpty() ? null : varianceReason.trim();
        List<Map<String, Object>> tracked = SqlRows.list(jdbc.sql("SELECT \"accountId\", label FROM \"PosPaymentMethod\""
                + " WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL AND \"trackInShift\" = true"
                + " AND \"accountId\" IS NOT NULL").param(organizationId));
        Map<String, Object> observed = observedInput == null ? Map.of() : observedInput;
        Map<String, Object> uncounted = uncountedInput == null ? Map.of() : uncountedInput;
        Set<String> trackedIds = new HashSet<>(recon.trackedTenderAccountIds());
        for (String id : uncounted.keySet()) {
            if (!trackedIds.contains(id)) {
                throw bad(body("code", "TENDER_NOT_COUNTED", "message", "Only tracked tender accounts can be marked as not counted"));
            }
            if (observed.containsKey(id)) {
                throw bad(body("code", "TENDER_NOT_COUNTED", "message", "An account cannot be both counted and not counted"));
            }
            Object why = uncounted.get(id);
            if (why == null || String.valueOf(why).trim().isEmpty()) {
                throw bad(body("code", "TENDER_NOT_COUNTED", "message", "Give a reason for every tender account that was not counted"));
            }
        }
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> m : tracked) {
            if (!observed.containsKey(m.get("accountId")) && !uncounted.containsKey(m.get("accountId"))) {
                missing.add((String) m.get("label"));
            }
        }
        if (!missing.isEmpty()) {
            throw bad(body("code", "TENDER_NOT_COUNTED", "message", "Enter closing balances for: " + String.join(", ", missing)
                    + " (or mark them not counted with a reason)"));
        }
        Map<String, Map<String, Object>> closingAccounts = recon.accountObservations(organizationId, observed);
        Map<String, Map<String, Object>> expectations = recon.sessionProviderExpectations(organizationId, session,
                closingAccounts.keySet());
        for (Map.Entry<String, Map<String, Object>> e : closingAccounts.entrySet()) {
            Map<String, Object> x = expectations.get(e.getKey());
            Map<String, Object> row = e.getValue();
            row.putAll(x);
            row.put("difference", plain(new BigDecimal((String) row.get("observed")).subtract(new BigDecimal((String) x.get("expected")))));
        }
        if (!uncounted.isEmpty()) {
            Map<String, Object> m = approve.apply("uncounted tender balances");
            for (Map.Entry<String, Object> e : uncounted.entrySet()) {
                Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT code, name FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(e.getKey(), organizationId));
                closingAccounts.put(e.getKey(), body("accountId", e.getKey(), "code", account == null ? null : account.get("code"),
                        "name", account == null ? null : account.get("name"), "notCounted", true,
                        "reason", String.valueOf(e.getValue()).trim(), "approvedById", m.get("id")));
            }
        }
        List<Map<String, Object>> providerDifferences = closingAccounts.values().stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("notCounted")) && new BigDecimal((String) r.get("difference")).signum() != 0)
                .toList();
        if (!providerDifferences.isEmpty()) {
            if (reason == null) {
                throw bad(body("code", "PROVIDER_BALANCE_VARIANCE", "message",
                        "Explain card, bank or mobile-money balance differences before closing", "requiresReason", true,
                        "requiresManagerApproval", true, "accounts", providerDifferences.stream().map(r -> body(
                                "accountId", r.get("accountId"), "name", r.get("name"), "expected", r.get("expected"),
                                "observed", r.get("observed"), "difference", r.get("difference"))).toList()));
            }
            approve.apply("electronic tender balance differences");
        }
        BigDecimal expected = computeExpected(session);
        BigDecimal difference = counted.subtract(expected);
        String varianceStatus = null;
        String approvedById = manager[0] == null ? null : (String) manager[0].get("id");
        String autoDirection = null;
        BigDecimal autoAmount = null;
        // Post-parity hardening (owner-approved 2026-11-10): an auto-adjusted close (offline/legacy callers only — the
        // HTTP route never forwards autoAdjustCash) still needs the reason and the threshold approval; the shortage is
        // booked as a pay-out AND the variance status is kept, so it cannot vanish from review.
        if (difference.signum() != 0) {
            if (reason == null) {
                throw bad(body("code", "CASH_VARIANCE_REASON_REQUIRED", "message",
                        "A variance reason is required when counted cash differs from expected"));
            }
            boolean managerPresent = approval.verifiedManagerId() != null;
            if (managerPresent || difference.abs().compareTo(varianceApprovalThreshold(organizationId)) >= 0) {
                approvedById = (String) approve.apply(managerPresent ? "the shift variance" : "a large cash variance").get("id");
                varianceStatus = "approved";
            } else {
                varianceStatus = "pending_review";
            }
            if (autoAdjustCash) {
                autoDirection = difference.signum() > 0 ? "pay_in" : "pay_out";
                autoAmount = difference.abs();
            }
        }
        return new Closing(reconciliation, closingAccounts, expected, difference, reason, varianceStatus, approvedById,
                autoDirection, autoAmount);
    }

    /** Freeze the count, the Z snapshot and the over/short journal. */
    @SuppressWarnings("unchecked")
    private void finishClosing(Map<String, Object> session, Closing closing, BigDecimal counted, Instant closedAt,
            String notes, Map<String, Object> closingDenomination, Map<String, Object> auditKind) {
        String organizationId = org();
        String sid = (String) session.get("id");
        Map<String, String> byMethod = computeByMethod(sid);
        int updated = jdbc.sql("UPDATE \"CashSession\" SET status = 'closed', \"closedAt\" = ?, \"closingCounted\" = ?,"
                        + " \"closingExpected\" = ?, \"closingDifference\" = ?, \"closingDenomination\" = ?::jsonb,"
                        + " \"closingByMethod\" = ?::jsonb, \"closingAccounts\" = ?::jsonb, \"varianceReason\" = ?,"
                        + " \"varianceStatus\" = ?, \"approvedById\" = ?, notes = ?, \"updatedAt\" = now()"
                        + " WHERE id = ? AND status = 'open'")
                .params(FiscalPeriodControl.ts(closedAt), counted, closing.expected(), closing.difference(),
                        sanitizeDenomination(closingDenomination), EntityJson.toJson(byMethod),
                        EntityJson.toJson(closing.closingAccounts()), closing.reason(), closing.varianceStatus(),
                        closing.approvedById(), notes, sid)
                .update();
        if (updated == 0) {
            throw ApiException.badRequest("The register session is no longer open");
        }
        Map<String, Object> report = new LinkedHashMap<>((Map<String, Object>) closing.reconciliation().get("report"));
        report.put("accounts", closing.reconciliation().get("accounts"));
        report.put("openingAccounts", session.get("openingAccounts"));
        report.put("closingAccounts", closing.closingAccounts());
        report.put("closingDenomination", closingDenomination);
        report.put("closingCounted", plain(counted));
        report.put("closingExpected", plain(closing.expected()));
        report.put("closingDifference", plain(closing.difference()));
        report.put("varianceReason", closing.reason());
        report.put("varianceStatus", closing.varianceStatus());
        report.put("approvedById", closing.approvedById());
        report.put("closeKind", auditKind.get("kind"));
        jdbc.sql("INSERT INTO \"PosReportSnapshot\" (id, \"organizationId\", \"cashSessionId\", \"reportData\", kind)"
                        + " VALUES (?, ?, ?, ?::jsonb, 'z')")
                .params(UUID.randomUUID().toString(), organizationId, sid, EntityJson.toJson(report))
                .update();
        if (closing.difference().signum() != 0) {
            postVarianceGl(session, closing.difference());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "closed");
        values.putAll(auditKind);
        values.put("counted", plain(counted));
        values.put("expected", plain(closing.expected()));
        values.put("closingDifference", plain(closing.difference()));
        values.put("varianceReason", closing.reason());
        values.put("varianceStatus", closing.varianceStatus());
        values.put("approvedById", closing.approvedById());
        audit.recordInTx(AuditInput.of("CashSession", sid, "update", Map.of("status", "open"), values));
    }

    // ------------------------------------------------------------------ movements

    /** Record a manual movement (pay-in, pay-out, adjustment) on the caller's own open session. */
    public Map<String, Object> recordMovement(String sessionId, MovementInput dto) {
        String organizationId = org();
        BigDecimal amount = dto.amount();
        if (dto.movementType() == null || !List.of("pay_in", "pay_out", "adjustment").contains(dto.movementType())) {
            throw ApiException.badRequest("Movement type must be pay_in, pay_out or adjustment");
        }
        if (amount == null || amount.signum() == 0) {
            throw ApiException.badRequest("Amount cannot be zero or non-finite");
        }
        if (("pay_in".equals(dto.movementType()) || "pay_out".equals(dto.movementType())) && amount.signum() < 0) {
            throw ApiException.badRequest(dto.movementType() + " amount must be positive");
        }
        if (dto.reason() == null || dto.reason().trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required for a cash movement");
        }
        if (dto.counterpartAccountId() == null || dto.counterpartAccountId().isEmpty()) {
            throw ApiException.badRequest("Select the expense, safe or transfer account for this movement");
        }
        if (dto.correctionOfSessionId() != null && !"adjustment".equals(dto.movementType())) {
            throw ApiException.badRequest("Corrections to a closed shift are recorded as adjustments");
        }
        return inTx(() -> {
            Map<String, Object> s = sessionId != null ? session(sessionId) : requireOpenSession();
            if (s == null) {
                throw ApiException.notFound("No open cash session");
            }
            lockOpenSession((String) s.get("id"));
            if (!"open".equals(s.get("status"))) {
                throw ApiException.badRequest("Session is not open");
            }
            if (!Objects.equals(s.get("userId"), TenantContext.userId())) {
                throw ApiException.forbidden("This cash session belongs to a different cashier");
            }
            if (("pay_out".equals(dto.movementType()) || amount.signum() < 0)
                    && amount.abs().compareTo(computeExpected(s)) > 0) {
                throw ApiException.badRequest("The drawer does not contain enough cash for this movement");
            }
            assertMovementCounterpart(s, dto.movementType(), dto.counterpartAccountId());
            if (dto.correctionOfSessionId() != null) {
                Map<String, Object> corrected = session(dto.correctionOfSessionId());
                if (corrected == null || "open".equals(corrected.get("status"))) {
                    throw ApiException.badRequest("Corrections must reference a closed shift");
                }
                if (corrected.get("id").equals(s.get("id"))) {
                    throw ApiException.badRequest("A shift cannot correct itself");
                }
                if (!TenantContext.has("cash_session:correct")) {
                    throw ApiException.forbidden("Posting a correction to a closed shift requires cash_session:correct");
                }
            }
            if ("pay_out".equals(dto.movementType()) || "adjustment".equals(dto.movementType())) {
                assertManagerApproval(dto.approvedById(), dto.approverEmail(), dto.managerPin(), (String) s.get("userId"),
                        "cash_session:cash_out", "adjustment".equals(dto.movementType()) ? "a cash adjustment" : "a cash pay-out");
            }
            Instant occurredAt = OccurredAt.resolve(dto.occurredAt());
            String movementId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount, reason,"
                            + " \"counterpartAccountId\", \"correctionOfSessionId\", \"performedBy\", \"createdAt\")"
                            + " VALUES (?, ?, ?, ?::\"CashMovementType\", ?, ?, ?, ?, ?, ?)")
                    .params(movementId, organizationId, s.get("id"), dto.movementType(), amount, dto.reason(),
                            dto.counterpartAccountId(), dto.correctionOfSessionId(), TenantContext.userId(),
                            FiscalPeriodControl.ts(occurredAt != null ? occurredAt : Instant.now()))
                    .update();
            postMovementGl(s, dto.movementType(), amount, movementId, dto.reason(), dto.counterpartAccountId());
            audit.recordInTx(AuditInput.of("CashMovement", movementId, "create", null, body("cashSessionId", s.get("id"),
                    "movementType", dto.movementType(), "amount", plain(amount), "approvedById", dto.approvedById(),
                    "correctionOfSessionId", dto.correctionOfSessionId())));
            AfterCommit.run(() -> events.publish("cash.movement.recorded", body("organizationId", organizationId,
                    "sessionId", s.get("id"), "movementId", movementId, "movementType", dto.movementType(),
                    "amount", plain(amount))));
            Map<String, Object> movement = movement(movementId);
            BusinessOutcome.record(jdbc, movement, true);
            return movement;
        });
    }

    private Map<String, Object> movement(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE id = ?").param(id));
    }

    /** Record a sale or refund against an open session (called by payments, inside their transaction). */
    public Map<String, Object> recordSaleOrRefund(String sessionId, String paymentId, String movementType, BigDecimal amount) {
        lockOpenSession(sessionId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount, \"paymentId\","
                        + " \"performedBy\") VALUES (?, ?, ?, ?::\"CashMovementType\", ?, ?, ?)")
                .params(id, org(), sessionId, movementType, amount, paymentId, TenantContext.userId()).update();
        return movement(id);
    }

    /** Drawer pay-out settling an external document whose own journal owns the GL leg. */
    public Map<String, Object> recordExternalPayOut(String sessionId, BigDecimal amount, String reason, String journalEntryId) {
        Map<String, Object> s = lockOpenSession(sessionId);
        if (amount.compareTo(computeExpected(s)) > 0) {
            throw ApiException.badRequest("The drawer has insufficient cash for this payout");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount, reason,"
                        + " \"journalEntryId\", \"performedBy\") VALUES (?, ?, ?, 'pay_out', ?, ?, ?, ?)")
                .params(id, org(), sessionId, amount, reason, journalEntryId, TenantContext.userId()).update();
        return movement(id);
    }

    // ------------------------------------------------------------------ reads

    /** The open session on a register (or the caller's own), with register and movements; {@code null} when none. */
    public Map<String, Object> findOpen(String cashRegisterId) {
        return inTx(() -> {
            Map<String, Object> s = cashRegisterId != null
                    ? SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE \"organizationId\" = ? AND status = 'open'"
                            + " AND \"cashRegisterId\" = ? LIMIT 1").params(org(), cashRegisterId))
                    : SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE \"organizationId\" = ? AND status = 'open'"
                            + " AND \"userId\" = ? LIMIT 1").params(org(), TenantContext.userId()));
            if (s == null) {
                return null;
            }
            s.put("cashRegister", SqlRows.one(jdbc.sql("SELECT * FROM \"CashRegister\" WHERE id = ?").param(s.get("cashRegisterId"))));
            s.put("movements", SqlRows.list(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE \"cashSessionId\" = ?"
                    + " ORDER BY \"createdAt\" ASC").param(s.get("id"))));
            return s;
        });
    }

    /** Read-only expected cash. */
    public BigDecimal expectedCash(String sessionId) {
        return inTx(() -> {
            Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(sessionId, org()));
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            return computeExpected(s);
        });
    }

    /** Movements with running total (audit trail). */
    public Map<String, Object> getMovements(String sessionId) {
        return inTx(() -> {
            Map<String, Object> s = session(sessionId);
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            BigDecimal rt = SqlRows.dec(s, "openingFloat");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT m.*, p.\"paymentMethod\" AS p_method, p.reference AS p_ref"
                    + " FROM \"CashMovement\" m LEFT JOIN \"Payment\" p ON p.id = m.\"paymentId\""
                    + " WHERE m.\"cashSessionId\" = ? ORDER BY m.\"createdAt\" ASC").param(sessionId))) {
                BigDecimal amt = SqlRows.dec(m, "amount");
                String type = (String) m.get("movementType");
                rt = List.of("sale", "pay_in", "adjustment").contains(type) ? rt.add(amt) : rt.subtract(amt);
                rows.add(body("id", m.get("id"), "movementType", type, "amount", plain(amt), "reason", m.get("reason"),
                        "paymentMethod", m.get("p_method"), "paymentReference", m.get("p_ref"),
                        "performedBy", m.get("performedBy"), "createdAt", m.get("createdAt"), "runningTotal", plain(rt)));
            }
            Map<String, Object> view = body("id", s.get("id"), "cashRegister", registerBrief(s.get("cashRegisterId")),
                    "status", s.get("status"), "openedAt", s.get("openedAt"), "closedAt", s.get("closedAt"),
                    "openingFloat", plain(SqlRows.dec(s, "openingFloat")),
                    "closingCounted", decOrNull(s, "closingCounted"), "closingExpected", decOrNull(s, "closingExpected"),
                    "closingDifference", decOrNull(s, "closingDifference"), "notes", s.get("notes"),
                    "bankedAmount", decOrNull(s, "bankedAmount"), "bankName", s.get("bankName"),
                    "varianceReason", s.get("varianceReason"), "varianceStatus", s.get("varianceStatus"),
                    "approvedById", s.get("approvedById"));
            return body("session", view, "movements", rows);
        });
    }

    private Map<String, Object> registerBrief(Object registerId) {
        return SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"CashRegister\" WHERE id = ?").param(registerId));
    }

    /** decimal or null; Nest: {@code x ? dec(x).toString() : null} (a zero decimal object is truthy). */
    private static String decOrNull(Map<String, Object> s, String key) {
        return s.get(key) == null ? null : plain(SqlRows.dec(s, key));
    }

    /** Paginated session history (newest first). */
    public Map<String, Object> history(int page, int perPage, String registerId) {
        return inTx(() -> {
            String where = " WHERE s.\"organizationId\" = :org" + (registerId != null ? " AND s.\"cashRegisterId\" = :reg" : "");
            var q = jdbc.sql("SELECT s.*, (SELECT count(*) FROM \"CashMovement\" m WHERE m.\"cashSessionId\" = s.id) AS mcount"
                    + " FROM \"CashSession\" s" + where + " ORDER BY s.\"openedAt\" DESC LIMIT :lim OFFSET :off")
                    .param("org", org()).param("lim", perPage).param("off", (page - 1) * perPage);
            var c = jdbc.sql("SELECT count(*) FROM \"CashSession\" s" + where).param("org", org());
            if (registerId != null) {
                q = q.param("reg", registerId);
                c = c.param("reg", registerId);
            }
            long total = c.query(Long.class).single();
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> s : SqlRows.list(q)) {
                data.add(body("id", s.get("id"), "cashRegister", registerBrief(s.get("cashRegisterId")), "status", s.get("status"),
                        "openedAt", s.get("openedAt"), "closedAt", s.get("closedAt"),
                        "openingFloat", plain(SqlRows.dec(s, "openingFloat")), "closingCounted", decOrNull(s, "closingCounted"),
                        "closingExpected", decOrNull(s, "closingExpected"), "closingDifference", decOrNull(s, "closingDifference"),
                        "bankedAmount", decOrNull(s, "bankedAmount"), "movementCount", ((Number) s.get("mcount")).longValue(),
                        "varianceReason", s.get("varianceReason"), "varianceStatus", s.get("varianceStatus"), "notes", s.get("notes")));
            }
            return body("data", data, "total", total, "page", page, "perPage", perPage,
                    "totalPages", (long) Math.ceil(total / (double) perPage));
        });
    }

    /** Session by id with its register brief and movement count; decimals as strings. */
    public Map<String, Object> findById(String id) {
        return inTx(() -> {
            Map<String, Object> s = session(id);
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            s.put("cashRegister", registerBrief(s.get("cashRegisterId")));
            s.put("_count", Map.of("movements", jdbc.sql("SELECT count(*) FROM \"CashMovement\" WHERE \"cashSessionId\" = ?")
                    .param(id).query(Long.class).single()));
            s.put("openingFloat", plain(SqlRows.dec(s, "openingFloat")));
            s.put("closingCounted", decOrNull(s, "closingCounted"));
            s.put("closingExpected", decOrNull(s, "closingExpected"));
            s.put("closingDifference", decOrNull(s, "closingDifference"));
            return s;
        });
    }

    // ------------------------------------------------------------------ banking / variance / reconcile

    /** Bank the drawer's cash from an open shift (movement) or after close (journal from the drawer). */
    public Map<String, Object> recordBankDeposit(String sessionId, BankDepositInput dto) {
        String organizationId = org();
        String userId = TenantContext.userId();
        BigDecimal amt = dto.amount();
        if (amt == null || amt.signum() <= 0) {
            throw ApiException.badRequest("Deposit amount must be positive");
        }
        if (dto.destinationAccountId() == null || dto.destinationAccountId().isEmpty()) {
            throw ApiException.badRequest("Select the destination bank account");
        }
        return inTx(() -> {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(sessionId, organizationId)
                    .query(String.class).list();
            Map<String, Object> s = session(sessionId);
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            String status = (String) s.get("status");
            if ("reconciled".equals(status)) {
                throw ApiException.badRequest("This session is reconciled; bank its cash from the register once no shift is open");
            }
            if ("open".equals(status) && !Objects.equals(s.get("userId"), userId)) {
                throw ApiException.forbidden("Only the session cashier can bank from an open shift");
            }
            if ("closed".equals(status) && !TenantContext.has("cash_session:reconcile")) {
                throw ApiException.forbidden("Banking a closed shift requires cash_session:reconcile");
            }
            String drawer = registerCashAccount(s);
            TreasuryGuards.lockAccounts(jdbc, organizationId, List.of(drawer, dto.destinationAccountId()));
            TreasuryGuards.AccountRow destination = TreasuryGuards.requireAccount(jdbc, organizationId,
                    dto.destinationAccountId(), "Destination account");
            if (!"bank".equals(destination.categoryKey())) {
                throw ApiException.badRequest("Select a bank account for the deposit");
            }
            String reason = "Bank deposit: " + dto.bankName() + (dto.reference() != null && !dto.reference().isEmpty()
                    ? " ref:" + dto.reference() : "") + (dto.notes() != null && !dto.notes().isEmpty() ? " - " + dto.notes() : "");
            if ("open".equals(status)) {
                BigDecimal onHand = computeExpected(s);
                if (amt.compareTo(onHand) > 0) {
                    throw ApiException.badRequest("Deposit " + plain(amt) + " exceeds cash on hand " + plain(onHand));
                }
                String movementId = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount, reason,"
                                + " \"counterpartAccountId\", \"performedBy\") VALUES (?, ?, ?, 'pay_out', ?, ?, ?, ?)")
                        .params(movementId, organizationId, s.get("id"), amt, reason, destination.id(), userId).update();
                postBankDepositGl(s, amt, movementId, dto.bankName(), destination.id());
                jdbc.sql("UPDATE \"CashSession\" SET \"bankedAmount\" = coalesce(\"bankedAmount\", 0) + ?, \"bankName\" = ?,"
                        + " \"updatedAt\" = now() WHERE id = ?").params(amt, dto.bankName(), s.get("id")).update();
                audit.recordInTx(AuditInput.of("CashMovement", movementId, "create", null, body("cashSessionId", s.get("id"),
                        "movementType", "pay_out", "amount", plain(amt), "reason", "bank_deposit")));
                AfterCommit.run(() -> events.publish("cash.banking.recorded", body("organizationId", organizationId,
                        "sessionId", s.get("id"), "amount", plain(amt), "bankName", dto.bankName())));
                Map<String, Object> outcome = body("movement", movement(movementId), "sessionId", s.get("id"));
                BusinessOutcome.record(jdbc, outcome, true);
                return outcome;
            }
            long openShift = jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"cashRegisterId\" = ?"
                    + " AND status = 'open'").params(organizationId, s.get("cashRegisterId")).query(Long.class).single();
            if (openShift > 0) {
                throw ApiException.badRequest("A shift is open on this register; bank the cash from that shift");
            }
            BigDecimal drawerBalance = SessionReconciliation.accountLedgerBalance(jdbc, organizationId, drawer);
            if (amt.compareTo(drawerBalance) > 0) {
                throw ApiException.badRequest("Deposit " + plain(amt) + " exceeds the drawer balance " + plain(drawerBalance));
            }
            String id = TreasuryGuards.operationId();
            Map<String, Object> entry = posting.post(new PostingRequest("BANK", Instant.now(),
                    reason + " (after close of shift " + s.get("id") + ")", null, null, "cash_session_banking",
                    (String) s.get("id"), null, "cash_session_banking:" + id, (String) s.get("branchId"), null, null,
                    List.of(PostingRequest.Line.debit(destination.id(), plain(amt), null),
                            PostingRequest.Line.credit(drawer, plain(amt), null))));
            audit.recordInTx(AuditInput.of("CashSession", (String) s.get("id"), "update", null, body("kind", "banking_after_close",
                    "amount", plain(amt), "journalEntryId", entry.get("id"), "destinationAccountId", destination.id(),
                    "reason", reason)));
            AfterCommit.run(() -> events.publish("cash.banking.recorded", body("organizationId", organizationId,
                    "sessionId", s.get("id"), "amount", plain(amt), "bankName", dto.bankName())));
            Map<String, Object> outcome = body("journalEntryId", entry.get("id"), "sessionId", s.get("id"));
            BusinessOutcome.record(jdbc, outcome, true);
            return outcome;
        });
    }

    /** Manager review of a closed shift's variance (never the session cashier). */
    public Map<String, Object> updateVariance(String sessionId, String reasonInput, String status) {
        String organizationId = org();
        return inTx(() -> {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(sessionId, organizationId)
                    .query(String.class).list();
            Map<String, Object> s = session(sessionId);
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            if (!"closed".equals(s.get("status"))) {
                throw ApiException.badRequest("Only a closed, unreconciled session may have its variance reviewed");
            }
            if (reasonInput == null || reasonInput.trim().isEmpty()) {
                throw ApiException.badRequest("A variance review reason is required");
            }
            if (SqlRows.dec(s, "closingDifference").signum() == 0) {
                throw ApiException.badRequest("This shift has no variance to review");
            }
            Map<String, Object> manager = assertManagerApproval(TenantContext.userId(), null, null, (String) s.get("userId"),
                    "cash_session:approve_variance", "a cash variance review");
            String previous = (String) s.get("varianceReason");
            String varianceReason = (previous == null ? "" : previous) + (previous != null && !previous.isEmpty() ? " | " : "")
                    + "Review (" + FiscalPeriodControl.day(Instant.now()) + "): " + reasonInput.trim();
            Map<String, Object> updateData = body("varianceReason", varianceReason, "approvedById", manager.get("id"));
            if (status != null) {
                updateData.put("varianceStatus", status);
            }
            jdbc.sql("UPDATE \"CashSession\" SET \"varianceReason\" = ?, \"approvedById\" = ?, \"varianceStatus\" = coalesce(?, \"varianceStatus\"),"
                            + " \"updatedAt\" = now() WHERE id = ?")
                    .params(varianceReason, manager.get("id"), status, sessionId).update();
            audit.recordInTx(AuditInput.of("CashSession", sessionId, "update",
                    body("varianceReason", s.get("varianceReason"), "varianceStatus", s.get("varianceStatus")), updateData));
            Map<String, Object> result = session(sessionId);
            BusinessOutcome.record(jdbc, result, true);
            return result;
        });
    }

    /** {@code closed → reconciled} (variance approved, Z-report present, not the session cashier). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reconcile(String sessionId, BigDecimal depositAmount, String notes) {
        String organizationId = org();
        String userId = TenantContext.userId();
        return inTx(() -> {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(sessionId, organizationId)
                    .query(String.class).list();
            Map<String, Object> s = session(sessionId);
            if (s == null) {
                throw ApiException.notFound("Cash session not found");
            }
            if (!"closed".equals(s.get("status"))) {
                throw ApiException.badRequest("Cannot reconcile session with status \"" + s.get("status") + "\" — must be closed first");
            }
            if (userId != null && userId.equals(s.get("userId"))) {
                throw ApiException.forbidden("The session cashier cannot reconcile their own session");
            }
            if (SqlRows.dec(s, "closingDifference").signum() != 0 && !"approved".equals(s.get("varianceStatus"))) {
                throw ApiException.badRequest("Session has an unresolved variance. Approve or explain the variance before reconciling.");
            }
            long snapshot = jdbc.sql("SELECT count(*) FROM \"PosReportSnapshot\" WHERE \"cashSessionId\" = ? AND kind = 'z'")
                    .param(sessionId).query(Long.class).single();
            if (snapshot == 0) {
                throw ApiException.badRequest("Z-report has not been generated for this session. Generate a Z-report before reconciling.");
            }
            if (depositAmount != null && depositAmount.signum() > 0) {
                throw ApiException.badRequest("The drawer is closed. Record its deposit in a new shift or treasury transfer;"
                        + " the frozen Z-report cannot be changed.");
            }
            Map<String, Object> check = recon.reconcileSession(organizationId, s);
            if (((Number) check.get("pendingPayments")).longValue() > 0 || ((Number) check.get("unsettledOrders")).longValue() > 0
                    || ((Number) check.get("pendingPostings")).longValue() > 0 || !((List<String>) check.get("issues")).isEmpty()) {
                throw bad(body("message", "Resolve reconciliation differences first", "reconciliation", check));
            }
            jdbc.sql("UPDATE \"CashSession\" SET status = 'reconciled', notes = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(notes != null ? notes : s.get("notes"), sessionId).update();
            audit.recordInTx(AuditInput.of("CashSession", sessionId, "reconcile", Map.of("status", "closed"),
                    body("status", "reconciled", "depositRecorded", depositAmount != null && depositAmount.signum() != 0)));
            AfterCommit.run(() -> events.publish("cash.session.reconciled", body("organizationId", organizationId,
                    "sessionId", sessionId, "cashRegisterId", s.get("cashRegisterId"))));
            Map<String, Object> result = session(sessionId);
            BusinessOutcome.record(jdbc, result, true);
            return result;
        });
    }

    /** Reconcile every closed session of a business date (open sessions block; unresolved ones are skipped). */
    public Map<String, Object> dailyReset(String dateStr) {
        String organizationId = org();
        Instant[] range = orgDayRange(dateStr);
        List<Map<String, Object>> sessions = inTx(() -> SqlRows.list(jdbc.sql("SELECT s.*, r.name AS register_name FROM \"CashSession\" s"
                + " JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\" WHERE s.\"organizationId\" = ? AND s.\"openedAt\" >= ?"
                + " AND s.\"openedAt\" < ? ORDER BY s.\"openedAt\" ASC")
                .params(organizationId, FiscalPeriodControl.ts(range[0]), FiscalPeriodControl.ts(range[1]))));
        List<Map<String, Object>> open = sessions.stream().filter(s -> "open".equals(s.get("status"))).toList();
        if (!open.isEmpty()) {
            throw bad(body("code", "OPEN_SESSIONS_BLOCK_RESET", "message",
                    "Cannot reset: one or more sessions are still open. Close all sessions first.",
                    "openSessions", open.stream().map(s -> body("id", s.get("id"), "cashRegister",
                            s.get("register_name") != null ? s.get("register_name") : s.get("cashRegisterId"),
                            "openedAt", s.get("openedAt"))).toList()));
        }
        List<String> reconciled = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        CashSessionHooks h = hooks.getIfAvailable();
        for (Map<String, Object> s : sessions) {
            if ("reconciled".equals(s.get("status"))) {
                continue;
            }
            String sid = (String) s.get("id");
            long snapshot = jdbc.sql("SELECT count(*) FROM \"PosReportSnapshot\" WHERE \"cashSessionId\" = ? AND kind = 'z'")
                    .param(sid).query(Long.class).single();
            if (snapshot == 0) {
                if (h == null) {
                    skipped.add(body("sessionId", sid, "reason", "No Z-report snapshot and POS reports service unavailable"));
                    continue;
                }
                try {
                    if (!h.generateZReport(sid)) {
                        skipped.add(body("sessionId", sid, "reason", "No Z-report snapshot and POS reports service unavailable"));
                        continue;
                    }
                } catch (RuntimeException e) {
                    skipped.add(body("sessionId", sid, "reason", "Failed to generate Z-report snapshot"));
                    continue;
                }
            }
            if (SqlRows.dec(s, "closingDifference").signum() != 0 && !"approved".equals(s.get("varianceStatus"))) {
                skipped.add(body("sessionId", sid, "reason", "Unresolved variance — must be approved before daily reset"));
                continue;
            }
            try {
                reconcile(sid, null, null);
            } catch (RuntimeException e) {
                skipped.add(body("sessionId", sid, "reason", e.getMessage() == null || e.getMessage().isEmpty()
                        ? "Reconciliation failed" : e.getMessage()));
                continue;
            }
            reconciled.add(sid);
        }
        return body("date", dateStr.trim().substring(0, Math.min(10, dateStr.trim().length())), "totalSessions", sessions.size(),
                "reconciledCount", reconciled.size(), "reconciled", reconciled, "skippedCount", skipped.size(),
                "skipped", skipped);
    }

    /** Daily reconciliation report for a business date. */
    public Map<String, Object> dailyReconciliation(String dateStr) {
        String organizationId = org();
        Instant[] range = orgDayRange(dateStr);
        return inTx(() -> {
            List<Map<String, Object>> sessions = SqlRows.list(jdbc.sql("SELECT s.*, r.name AS register_name FROM \"CashSession\" s"
                    + " JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\" WHERE s.\"organizationId\" = ? AND s.\"openedAt\" >= ?"
                    + " AND s.\"openedAt\" < ? ORDER BY s.\"openedAt\" ASC")
                    .params(organizationId, FiscalPeriodControl.ts(range[0]), FiscalPeriodControl.ts(range[1])));
            List<Map<String, Object>> rows = new ArrayList<>();
            BigDecimal gOpening = BigDecimal.ZERO;
            BigDecimal gSales = BigDecimal.ZERO;
            BigDecimal gPayIns = BigDecimal.ZERO;
            BigDecimal gPayOuts = BigDecimal.ZERO;
            BigDecimal gRefunds = BigDecimal.ZERO;
            BigDecimal gBanked = BigDecimal.ZERO;
            BigDecimal gAdjust = BigDecimal.ZERO;
            for (Map<String, Object> s : sessions) {
                BigDecimal sales = BigDecimal.ZERO;
                BigDecimal payIns = BigDecimal.ZERO;
                BigDecimal payOuts = BigDecimal.ZERO;
                BigDecimal refunds = BigDecimal.ZERO;
                BigDecimal banked = BigDecimal.ZERO;
                BigDecimal adjustments = BigDecimal.ZERO;
                for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE \"cashSessionId\" = ?")
                        .param(s.get("id")))) {
                    BigDecimal amt = SqlRows.dec(m, "amount");
                    switch (String.valueOf(m.get("movementType"))) {
                        case "sale" -> sales = sales.add(amt);
                        case "pay_in" -> payIns = payIns.add(amt);
                        case "pay_out" -> {
                            if (String.valueOf(m.get("reason") == null ? "" : m.get("reason")).startsWith("Bank deposit:")) {
                                banked = banked.add(amt);
                            } else {
                                payOuts = payOuts.add(amt);
                            }
                        }
                        case "refund" -> refunds = refunds.add(amt);
                        case "supplier_payment" -> payOuts = payOuts.add(amt);
                        case "adjustment" -> adjustments = adjustments.add(amt);
                        default -> { }
                    }
                }
                BigDecimal opening = SqlRows.dec(s, "openingFloat");
                BigDecimal expected = opening.add(sales).add(payIns).add(adjustments).subtract(payOuts).subtract(refunds);
                BigDecimal actual = s.get("closingCounted") == null ? null : SqlRows.dec(s, "closingCounted");
                BigDecimal variance = actual == null ? null : actual.subtract(expected);
                gOpening = gOpening.add(opening);
                gSales = gSales.add(sales);
                gPayIns = gPayIns.add(payIns);
                gPayOuts = gPayOuts.add(payOuts);
                gRefunds = gRefunds.add(refunds);
                gBanked = gBanked.add(banked);
                gAdjust = gAdjust.add(adjustments);
                String cashier = s.get("userId") == null ? null : jdbc.sql("SELECT \"firstName\" || coalesce(' ' ||"
                        + " nullif(\"lastName\", ''), '') FROM \"User\" WHERE id = ?").param(s.get("userId"))
                        .query(String.class).optional().orElse(null);
                rows.add(body("sessionId", s.get("id"), "cashRegisterName", s.get("register_name"),
                        "cashierName", cashier == null ? "(unknown)" : cashier, "openedAt", s.get("openedAt"),
                        "closedAt", s.get("closedAt"), "openingFloat", plain(opening), "salesTotal", plain(sales),
                        "payInsTotal", plain(payIns), "payOutsTotal", plain(payOuts), "refundsTotal", plain(refunds),
                        "expectedCash", plain(expected), "actualCash", actual == null ? null : plain(actual),
                        "variance", variance == null ? null : plain(variance), "varianceReason", s.get("varianceReason"),
                        "bankedAmount", plain(banked)));
            }
            BigDecimal gExpected = gOpening.add(gSales).add(gPayIns).add(gAdjust).subtract(gPayOuts).subtract(gRefunds);
            return body("date", dateStr.trim().substring(0, Math.min(10, dateStr.trim().length())), "sessionCount", sessions.size(),
                    "sessions", rows, "totals", body("openingFloat", plain(gOpening), "salesTotal", plain(gSales),
                            "payInsTotal", plain(gPayIns), "payOutsTotal", plain(gPayOuts), "refundsTotal", plain(gRefunds),
                            "bankedAmount", plain(gBanked), "adjustmentsTotal", plain(gAdjust), "expectedCash", plain(gExpected)));
        });
    }

    /** Live reconciliation for the close dialog. */
    public Map<String, Object> reconciliation(String sessionId) {
        drainStockPostings(sessionId);
        return inTx(() -> {
            Map<String, Object> s = session(sessionId);
            if (s == null) {
                throw ApiException.notFound("Session not found");
            }
            return recon.reconcileSession(org(), s);
        });
    }

    /** Provider settlement (idempotent on the provider reference). */
    public Map<String, Object> settleTender(Map<String, Object> input) {
        return inTx(() -> recon.settleTender(input));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The caller's open session when no {@code sessionId} was sent. Post-parity: a cashier with shifts open on two
     * registers got an arbitrary one ({@code LIMIT 1} without order); the request must now name the shift.
     */
    private Map<String, Object> requireOpenSession() {
        List<Map<String, Object>> open = SqlRows.list(jdbc.sql("SELECT * FROM \"CashSession\" WHERE \"organizationId\" = ?"
                + " AND \"userId\" = ? AND status = 'open' ORDER BY \"openedAt\" LIMIT 2").params(org(), TenantContext.userId()));
        if (open.isEmpty()) {
            throw ApiException.notFound("No open cash session");
        }
        if (open.size() > 1) {
            throw ApiException.badRequest("You have shifts open on more than one register; choose the shift");
        }
        return open.getFirst();
    }

    /** expected = opening + sales + pay-ins − pay-outs − refunds − supplier payouts ± adjustments. */
    BigDecimal computeExpected(Map<String, Object> session) {
        BigDecimal total = SqlRows.dec(session, "openingFloat");
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"movementType\", amount FROM \"CashMovement\""
                + " WHERE \"cashSessionId\" = ?").param(session.get("id")))) {
            BigDecimal amt = SqlRows.dec(m, "amount");
            switch (String.valueOf(m.get("movementType"))) {
                case "sale", "pay_in", "adjustment" -> total = total.add(amt);
                case "refund", "supplier_payment", "pay_out" -> total = total.subtract(amt);
                default -> { }
            }
        }
        return total;
    }

    private Map<String, String> computeByMethod(String sessionId) {
        Map<String, String> totals = new LinkedHashMap<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT \"paymentMethod\", direction, amount FROM \"Payment\""
                + " WHERE \"organizationId\" = ? AND \"cashSessionId\" = ? AND status::text <> 'cancelled'").params(org(), sessionId))) {
            String method = (String) p.get("paymentMethod");
            BigDecimal signed = SqlRows.dec(p, "amount").multiply("inbound".equals(p.get("direction")) ? BigDecimal.ONE
                    : BigDecimal.ONE.negate());
            totals.put(method, plain(new BigDecimal(totals.getOrDefault(method, "0")).add(signed)));
        }
        return totals;
    }

    private Map<String, Object> lockOpenSession(String sessionId) {
        jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(sessionId, org())
                .query(String.class).list();
        Map<String, Object> s = session(sessionId);
        if (s == null || !"open".equals(s.get("status"))) {
            throw ApiException.badRequest("The register session is no longer open");
        }
        return s;
    }

    private Instant[] orgDayRange(String dateStr) {
        String d = dateStr == null ? "" : dateStr.trim();
        if (!d.matches("^(\\d{4})-(\\d{2})-(\\d{2})$")) {
            throw ApiException.badRequest("Invalid date — expected YYYY-MM-DD");
        }
        return OrgDates.zonedDayRange(d, OrgDates.orgTimezone(jdbc, org()));
    }

    /** Denomination map → JSON (null when empty). */
    private static String sanitizeDenomination(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            double f = com.inventoryerp.kernel.common.JsNumbers.number(e.getKey());
            double c = e.getValue() instanceof Number n ? n.doubleValue()
                    : com.inventoryerp.kernel.common.JsNumbers.number(String.valueOf(e.getValue()));
            if (Double.isNaN(f) || Double.isInfinite(f) || f <= 0 || Double.isNaN(c) || Double.isInfinite(c) || c < 0
                    || c != Math.rint(c)) {
                throw ApiException.badRequest("Denominations require positive face values and whole, non-negative counts");
            }
            out.put(com.inventoryerp.kernel.common.JsNumbers.toJsString(f), (long) c);
        }
        return out.isEmpty() ? null : EntityJson.toJson(out);
    }

    private static void assertDenominationTotal(Map<String, Object> input, BigDecimal total, String label) {
        if (input == null) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            double f = com.inventoryerp.kernel.common.JsNumbers.number(e.getKey());
            double c = e.getValue() instanceof Number n ? n.doubleValue()
                    : com.inventoryerp.kernel.common.JsNumbers.number(String.valueOf(e.getValue()));
            if (Double.isNaN(f) || Double.isInfinite(f) || f <= 0 || Double.isNaN(c) || Double.isInfinite(c) || c < 0
                    || c != Math.rint(c)) {
                throw ApiException.badRequest("Denominations require positive face values and whole, non-negative counts");
            }
            sum = sum.add(BigDecimal.valueOf(f).multiply(BigDecimal.valueOf((long) c)));
        }
        if (sum.compareTo(total) != 0) {
            throw ApiException.badRequest("Denomination total must equal the " + label);
        }
    }

    private Set<String> permissionsOf(String userId) {
        Set<String> perms = new LinkedHashSet<>();
        jdbc.sql("SELECT r.permissions FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\" WHERE ur.\"B\" = ?")
                .param(userId)
                .query((rs, n) -> {
                    java.sql.Array a = rs.getArray(1);
                    if (a != null) {
                        perms.addAll(List.of((String[]) a.getArray()));
                    }
                    return null;
                }).list();
        return perms;
    }

    /** Verify a manager approving a privileged cash action (permission, segregation of duties, PIN). */
    private Map<String, Object> assertManagerApproval(String approverId, String approverEmail, String managerPin,
            String cashierUserId, String permission, String actionLabel) {
        String orgId = org();
        Map<String, Object> manager = approverId != null
                ? SqlRows.one(jdbc.sql("SELECT id, \"pinHash\" FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(approverId, orgId))
                : approverEmail != null
                        ? SqlRows.one(jdbc.sql("SELECT id, \"pinHash\" FROM \"User\" WHERE email = ? AND \"organizationId\" = ?"
                                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(approverEmail.toLowerCase(), orgId))
                        : null;
        if (approverId == null && approverEmail == null) {
            throw bad(body("code", "MANAGER_APPROVAL_REQUIRED", "message", actionLabel + " requires manager approval"));
        }
        if (manager == null) {
            throw ApiException.notFound("Approving manager not found");
        }
        String managerId = (String) manager.get("id");
        if (managerId.equals(cashierUserId)) {
            throw ApiException.forbidden("The session cashier cannot approve " + actionLabel);
        }
        if ((managerPin == null || managerPin.isEmpty()) && !managerId.equals(TenantContext.userId())) {
            throw bad(body("code", "MANAGER_APPROVAL_REQUIRED", "message", "Manager PIN is required"));
        }
        // Post-parity hardening (owner-approved 2026-11-10): the permission is checked before the PIN (Nest compared
        // the PIN first, so the two error messages told a caller whether a non-manager's PIN was right), and PIN
        // attempts count on the shared manager-PIN lockout, atomically and outside this transaction.
        if (!permissionsOf(managerId).contains(permission)) {
            throw ApiException.unauthorized("Approver does not hold " + permission);
        }
        if (managerPin != null && !managerPin.isEmpty()) {
            String pinHash = (String) manager.get("pinHash");
            if (pinHash == null) {
                throw ApiException.badRequest("Manager has not set a PIN");
            }
            CredentialGuard.Attempt attempt = credentialGuard.lockedFor(orgId, CredentialGuard.MANAGER_PIN, managerId)
                    .map(left -> new CredentialGuard.Attempt(true, left))
                    .orElseGet(() -> credentialGuard.attempt(orgId, CredentialGuard.MANAGER_PIN, managerId));
            if (attempt.locked()) {
                throw ApiException.unauthorized("Manager approvals are temporarily locked after repeated failed attempts."
                        + " Try again in " + CredentialGuard.minutes(attempt.remaining()) + " min");
            }
            if (!password.compare(managerPin, pinHash)) {
                throw ApiException.unauthorized("Invalid manager PIN");
            }
            credentialGuard.succeeded(orgId, CredentialGuard.MANAGER_PIN, managerId);
        }
        return manager;
    }

    private void assertMovementCounterpart(Map<String, Object> session, String movementType, String counterpartAccountId) {
        String organizationId = org();
        String drawer = registerCashAccount(session);
        if (counterpartAccountId.equals(drawer)) {
            throw ApiException.badRequest("The counterpart must differ from the drawer account");
        }
        TreasuryGuards.AccountRow account = TreasuryGuards.requireAccount(jdbc, organizationId, counterpartAccountId,
                "Counterpart account");
        TreasuryGuards.assertNotDrawerAccount(jdbc, organizationId, account.id(), "A drawer movement");
        if ("adjustment".equals(movementType)) {
            return;
        }
        String classification = jdbc.sql("SELECT c.classification::text FROM \"Account\" a LEFT JOIN \"AccountCategory\" c"
                        + " ON c.id = a.\"categoryId\" WHERE a.id = ?").param(account.id()).query(String.class)
                .optional().orElse(null);
        boolean allowed = "pay_in".equals(movementType)
                ? account.isCashEquivalent() || List.of("equity", "liability").contains(String.valueOf(classification))
                : account.isCashEquivalent() || List.of("expense", "liability", "equity").contains(String.valueOf(classification));
        if (!allowed) {
            throw ApiException.badRequest("pay_in".equals(movementType)
                    ? "A pay-in must come from a safe/bank account, owner equity or a liability - sales are recorded only"
                            + " through payments"
                    : "A pay-out must go to an expense, a safe/bank account, owner equity or a liability");
        }
    }

    private String registerCashAccount(Map<String, Object> session) {
        if (session.get("drawerAccountId") != null) {
            return (String) session.get("drawerAccountId");
        }
        String def = jdbc.sql("SELECT \"defaultAccountId\" FROM \"CashRegister\" WHERE id = ?").param(session.get("cashRegisterId"))
                .query(String.class).optional().orElse(null);
        return def != null ? def : determination.mapped("default_cash");
    }

    private String categoryOf(String accountId) {
        return jdbc.sql("SELECT c.key FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL")
                .params(accountId, org()).query(String.class).optional().orElse(null);
    }

    private void lockAccount(String accountId) {
        jdbc.sql("SELECT id FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(accountId, org())
                .query(String.class).list();
    }

    private String drawerAdjustmentClearing(String drawerAccountId) {
        String clearing;
        try {
            clearing = determination.mapped("cash_clearing");
        } catch (ApiException e) {
            throw ApiException.badRequest("Configure the cash clearing account to allow automatic drawer adjustments");
        }
        if (clearing.equals(drawerAccountId)) {
            throw ApiException.badRequest("Configure a cash clearing account different from the drawer");
        }
        return clearing;
    }

    private void applyClosingCashAdjustment(Map<String, Object> session, String direction, BigDecimal amount) {
        String clearing = drawerAdjustmentClearing(registerCashAccount(session));
        String reason = "pay_in".equals(direction)
                ? "Closing adjustment: cash added to the drawer to match the counted amount"
                : "Closing adjustment: cash withdrawn from the drawer to match the counted amount";
        String movementId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CashMovement\" (id, \"organizationId\", \"cashSessionId\", \"movementType\", amount, reason,"
                        + " \"counterpartAccountId\", \"performedBy\") VALUES (?, ?, ?, ?::\"CashMovementType\", ?, ?, ?, ?)")
                .params(movementId, org(), session.get("id"), direction, amount, reason, clearing, TenantContext.userId())
                .update();
        postMovementGl(session, direction, amount, movementId, reason, clearing);
        audit.recordInTx(AuditInput.of("CashMovement", movementId, "create", null, body("cashSessionId", session.get("id"),
                "movementType", direction, "amount", plain(amount), "autoAdjustment", true)));
    }

    private void postMovementGl(Map<String, Object> session, String movementType, BigDecimal amount, String movementId,
            String reason, String counterpartAccountId) {
        String cash = registerCashAccount(session);
        String branchId = (String) session.get("branchId");
        String amt = plain(amount);
        List<PostingRequest.Line> lines;
        String description;
        if ("pay_in".equals(movementType) || "pay_out".equals(movementType)) {
            String clearing = counterpartAccountId != null ? counterpartAccountId : determination.mapped("cash_clearing");
            long active = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\" = true"
                    + " AND \"deletedAt\" IS NULL").params(clearing, org()).query(Long.class).single();
            if (active == 0 || clearing.equals(cash)) {
                throw ApiException.badRequest("Invalid cash movement counterpart account");
            }
            if ("pay_in".equals(movementType)) {
                description = reason != null ? reason : "Cash pay-in";
                lines = List.of(PostingRequest.Line.debit(cash, amt, null), PostingRequest.Line.credit(clearing, amt, null));
            } else {
                description = reason != null ? reason : "Cash pay-out";
                lines = List.of(PostingRequest.Line.debit(clearing, amt, null), PostingRequest.Line.credit(cash, amt, null));
            }
        } else {
            String shortOver = determination.mapped("cash_short_over");
            if (!shortOver.equals(counterpartAccountId)) {
                throw ApiException.badRequest("Cash adjustments must use the configured cash short/over account");
            }
            String abs = plain(amount.abs());
            description = reason != null ? reason : "Cash adjustment";
            lines = amount.signum() > 0
                    ? List.of(PostingRequest.Line.debit(cash, abs, null), PostingRequest.Line.credit(shortOver, abs, null))
                    : List.of(PostingRequest.Line.debit(shortOver, abs, null), PostingRequest.Line.credit(cash, abs, null));
        }
        posting.post(new PostingRequest("CASH", Instant.now(), description, null, null, "cash_movement", movementId, null,
                "cash_movement:" + movementId, branchId, null, null, lines));
    }

    private void postBankDepositGl(Map<String, Object> session, BigDecimal amount, String movementId, String bankName,
            String destinationAccountId) {
        String cash = registerCashAccount(session);
        String bank = destinationAccountId != null ? destinationAccountId : determination.mapped("default_bank");
        String category = categoryOf(bank);
        if (!"bank".equals(category)) {
            throw ApiException.badRequest("Select a bank account for the deposit");
        }
        String amt = plain(amount);
        posting.post(new PostingRequest("BANK", Instant.now(), "Bank deposit: " + bankName, null, null, "cash_movement",
                movementId, null, "cash_movement:" + movementId, (String) session.get("branchId"), null, null,
                List.of(PostingRequest.Line.debit(bank, amt, null), PostingRequest.Line.credit(cash, amt, null))));
    }

    /** difference = counted − expected: short (&lt;0) → Dr short/over Cr cash; over → Dr cash Cr short/over. */
    private void postVarianceGl(Map<String, Object> session, BigDecimal difference) {
        String cash = registerCashAccount(session);
        String shortOver = determination.mapped("cash_short_over");
        String abs = plain(difference.abs());
        List<PostingRequest.Line> lines = difference.signum() < 0
                ? List.of(PostingRequest.Line.debit(shortOver, abs, null), PostingRequest.Line.credit(cash, abs, null))
                : List.of(PostingRequest.Line.debit(cash, abs, null), PostingRequest.Line.credit(shortOver, abs, null));
        String sid = (String) session.get("id");
        posting.post(new PostingRequest("CASH", Instant.now(), "Cash over/short — session " + sid, null, null,
                "cash_session_variance", sid, null, "cash_session_variance:" + sid, (String) session.get("branchId"), null,
                null, lines));
    }
}
