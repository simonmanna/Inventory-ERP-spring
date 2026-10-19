package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Shift evidence (port of {@code session-reconciliation.ts}): drawer ledger balance, provider (wallet / bank /
 * card) observations and expectations, the full monetary reconciliation of a session against payments,
 * drawer movements, journals and invoices, and provider settlements.
 */
@Component
public class SessionReconciliation {

    /** Most open orders returned for display; the blocker itself is a full count. */
    public static final int OPEN_ORDER_DISPLAY_LIMIT = 50;

    static final List<String> HELD_ORDER_STATUSES = List.of("draft", "confirmed", "in_progress");

    private final JdbcClient jdbc;
    private final PosPaymentMethodService paymentMethods;
    private final PostingService posting;
    private final AuditService audit;

    public SessionReconciliation(JdbcClient jdbc, PosPaymentMethodService paymentMethods, PostingService posting,
            AuditService audit) {
        this.jdbc = jdbc;
        this.paymentMethods = paymentMethods;
        this.posting = posting;
        this.audit = audit;
    }

    /** Σ(debit − credit) of an account over posted/reversed entries (transaction currency, like Nest). */
    public static BigDecimal accountLedgerBalance(JdbcClient jdbc, String organizationId, String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .params(organizationId, accountId).query(BigDecimal.class).single();
    }

    /** Observed provider balances at open/close: each must be a bank, wallet or the card-clearing account. */
    public Map<String, Map<String, Object>> accountObservations(String organizationId, Map<String, Object> input) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        if (input == null) {
            return rows;
        }
        for (Map.Entry<String, Object> e : input.entrySet()) {
            String id = e.getKey();
            BigDecimal amount;
            try {
                amount = Money.dec(e.getValue());
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("Account balances must be non-negative numbers");
            }
            if (amount.signum() < 0) {
                throw ApiException.badRequest("Account balances must be non-negative numbers");
            }
            Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT a.id, a.code, a.name, c.key FROM \"Account\" a"
                            + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?"
                            + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL").params(id, organizationId));
            String key = account == null ? null : (String) account.get("key");
            if (account == null || !List.of("bank", "mobile_money", "current_asset").contains(key == null ? "" : key)) {
                throw ApiException.badRequest("Select a bank, wallet or card clearing account");
            }
            if ("current_asset".equals(key)) {
                String clearing = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ?"
                        + " AND key = 'card_clearing' LIMIT 1").param(organizationId).query(String.class).optional().orElse(null);
                if (!id.equals(clearing)) {
                    throw ApiException.badRequest("Select the configured card clearing account");
                }
            }
            BigDecimal ledger = accountLedgerBalance(jdbc, organizationId, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ledger", plain(ledger));
            row.put("difference", plain(amount.subtract(ledger)));
            row.put("accountId", id);
            row.put("code", account.get("code"));
            row.put("name", account.get("name"));
            row.put("accountType", key);
            row.put("observed", plain(amount));
            rows.put(id, row);
        }
        return rows;
    }

    /** The shift-open observation for a provider account (object or legacy bare number). */
    static BigDecimal[] openingObservation(Map<String, Object> session, String accountId) {
        Object accounts = session.get("openingAccounts");
        JsonNode raw = accounts instanceof JsonNode n ? n.get(accountId) : null;
        if (raw == null || raw.isNull()) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }
        JsonNode value = raw.isObject() ? raw.get("observed") : raw;
        BigDecimal v = value == null || value.isNull() ? BigDecimal.ZERO : new BigDecimal(value.asString());
        return new BigDecimal[] {v, BigDecimal.ONE};
    }

    /** THE definition of a provider account's expected closing balance for one shift. */
    public Map<String, Map<String, Object>> sessionProviderExpectations(String organizationId, Map<String, Object> session,
            java.util.Collection<String> accountIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : accountIds) {
            if (id != null && !id.isEmpty()) {
                ids.add(id);
            }
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String sessionId = (String) session.get("id");
        List<Map<String, Object>> payments = SqlRows.list(jdbc.sql("SELECT * FROM \"Payment\" WHERE \"organizationId\" = :org"
                        + " AND \"cashSessionId\" = :sid AND status::text <> 'cancelled' AND \"accountId\" IN (:ids)")
                .param("org", organizationId).param("sid", sessionId).param("ids", ids));
        List<Map<String, Object>> settlements = SqlRows.list(jdbc.sql("SELECT * FROM \"TenderSettlement\""
                        + " WHERE \"organizationId\" = :org AND \"cashSessionId\" = :sid"
                        + " AND (\"sourceAccountId\" IN (:ids) OR \"destinationAccountId\" IN (:ids))")
                .param("org", organizationId).param("sid", sessionId).param("ids", ids));
        List<Map<String, Object>> movements = SqlRows.list(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE \"organizationId\" = :org"
                        + " AND \"cashSessionId\" = :sid AND \"paymentId\" IS NULL AND \"counterpartAccountId\" IN (:ids)")
                .param("org", organizationId).param("sid", sessionId).param("ids", ids));
        for (String id : ids) {
            BigDecimal[] obs = openingObservation(session, id);
            BigDecimal opening = obs[0];
            BigDecimal receipts = BigDecimal.ZERO;
            BigDecimal refunds = BigDecimal.ZERO;
            BigDecimal settledOut = BigDecimal.ZERO;
            BigDecimal settledIn = BigDecimal.ZERO;
            BigDecimal movementsIn = BigDecimal.ZERO;
            BigDecimal movementsOut = BigDecimal.ZERO;
            for (Map<String, Object> p : payments) {
                if (!id.equals(p.get("accountId"))) {
                    continue;
                }
                BigDecimal settled = SqlRows.dec(p, "amount").subtract(SqlRows.dec(p, "withholdingAmount"));
                if ("inbound".equals(p.get("direction"))) {
                    receipts = receipts.add(settled);
                } else {
                    refunds = refunds.add(settled);
                }
            }
            for (Map<String, Object> s : settlements) {
                if (id.equals(s.get("sourceAccountId"))) {
                    settledOut = settledOut.add(SqlRows.dec(s, "grossAmount"));
                }
                if (id.equals(s.get("destinationAccountId"))) {
                    settledIn = settledIn.add(SqlRows.dec(s, "grossAmount").subtract(SqlRows.dec(s, "feeAmount")));
                }
            }
            for (Map<String, Object> m : movements) {
                if (!id.equals(m.get("counterpartAccountId"))) {
                    continue;
                }
                BigDecimal drawerDelta = SqlRows.dec(m, "amount").multiply("pay_out".equals(m.get("movementType"))
                        ? BigDecimal.ONE.negate() : BigDecimal.ONE);
                if (drawerDelta.signum() < 0) {
                    movementsIn = movementsIn.add(drawerDelta.abs());
                } else {
                    movementsOut = movementsOut.add(drawerDelta);
                }
            }
            BigDecimal expected = opening.add(receipts).subtract(refunds).subtract(settledOut).add(settledIn)
                    .add(movementsIn).subtract(movementsOut);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("accountId", id);
            e.put("opening", plain(opening));
            e.put("openingKnown", obs[1].signum() > 0);
            e.put("receipts", plain(receipts));
            e.put("refunds", plain(refunds));
            e.put("settledOut", plain(settledOut));
            e.put("settledIn", plain(settledIn));
            e.put("movementsIn", plain(movementsIn));
            e.put("movementsOut", plain(movementsOut));
            e.put("expected", plain(expected));
            out.put(id, e);
        }
        return out;
    }

    /** Accounts the terminal asks the cashier to count at close. */
    List<String> trackedTenderAccountIds() {
        return paymentMethods.terminalPaymentMethods().stream()
                .filter(m -> m.trackInShift() && m.accountId() != null)
                .map(PosPaymentMethodService.View::accountId).toList();
    }

    private String heldOrderSql() {
        return " FROM \"Order\" o WHERE o.\"organizationId\" = :org AND o.status::text IN ('draft', 'confirmed', 'in_progress')"
                + " AND o.\"invoiceId\" IS NULL AND EXISTS (SELECT 1 FROM \"OrderItem\" i WHERE i.\"orderId\" = o.id"
                + " AND i.cancelled = false)";
    }

    private List<Map<String, Object>> openOrderSummaries(String organizationId, String sessionId) {
        var spec = jdbc.sql("SELECT o.*" + heldOrderSql() + (sessionId == null ? "" : " AND o.\"cashSessionId\" = :sid")
                + " ORDER BY o.\"openedAt\" ASC LIMIT " + OPEN_ORDER_DISPLAY_LIMIT).param("org", organizationId);
        if (sessionId != null) {
            spec = spec.param("sid", sessionId);
        }
        List<Map<String, Object>> orders = SqlRows.list(spec);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : orders) {
            String tableName = o.get("tableId") == null ? null : jdbc.sql("SELECT name FROM \"PosTable\" WHERE id = ?")
                    .param(o.get("tableId")).query(String.class).optional().orElse(null);
            String waiterName = o.get("waiterId") == null ? null : jdbc.sql("SELECT \"firstName\" || coalesce(' ' ||"
                            + " nullif(\"lastName\", ''), '') FROM \"User\" WHERE id = ?")
                    .param(o.get("waiterId")).query(String.class).optional().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.get("id"));
            row.put("orderNumber", o.get("orderNumber"));
            row.put("orderType", o.get("orderType"));
            row.put("tableName", tableName);
            row.put("waiterName", waiterName);
            row.put("cashSessionId", o.get("cashSessionId"));
            row.put("openedAt", o.get("openedAt") != null ? o.get("openedAt") : o.get("createdAt"));
            row.put("totalAmount", plain(SqlRows.dec(o, "totalAmount")));
            out.add(row);
        }
        return out;
    }

    /** Compare monetary evidence, not merely whether a journal balances. */
    public Map<String, Object> reconcileSession(String organizationId, Map<String, Object> session) {
        String sessionId = (String) session.get("id");
        boolean open = "open".equals(session.get("status"));
        String orderScope = open ? null : sessionId;
        var countSpec = jdbc.sql("SELECT count(*)" + heldOrderSql() + (orderScope == null ? "" : " AND o.\"cashSessionId\" = :sid"))
                .param("org", organizationId);
        if (orderScope != null) {
            countSpec = countSpec.param("sid", orderScope);
        }
        long unsettledOrders = countSpec.query(Long.class).single();
        List<Map<String, Object>> payments = SqlRows.list(jdbc.sql("SELECT * FROM \"Payment\" WHERE \"organizationId\" = ?"
                + " AND \"cashSessionId\" = ? AND status::text <> 'cancelled'").params(organizationId, sessionId));
        List<Map<String, Object>> movements = SqlRows.list(jdbc.sql("SELECT * FROM \"CashMovement\" WHERE \"organizationId\" = ?"
                + " AND \"cashSessionId\" = ? ORDER BY \"createdAt\" ASC").params(organizationId, sessionId));
        List<Map<String, Object>> invoices = SqlRows.list(jdbc.sql("SELECT * FROM \"Invoice\" WHERE \"organizationId\" = ?"
                + " AND \"cashSessionId\" = ? AND status::text <> 'cancelled'").params(organizationId, sessionId));
        long postingJobs = jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND status <> 'done'"
                        + " AND \"invoiceId\" IN (SELECT id FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"cashSessionId\" = ?)")
                .params(organizationId, organizationId, sessionId).query(Long.class).single();
        Map<String, Object> register = SqlRows.one(jdbc.sql("SELECT * FROM \"CashRegister\" WHERE id = ? AND \"organizationId\" = ?")
                .params(session.get("cashRegisterId"), organizationId));
        List<Map<String, Object>> openOrders = unsettledOrders > 0 ? openOrderSummaries(organizationId, orderScope) : List.of();
        List<String> issues = new ArrayList<>();
        String drawerAccountId = session.get("drawerAccountId") != null ? (String) session.get("drawerAccountId")
                : register == null ? null : (String) register.get("defaultAccountId");
        if (drawerAccountId == null) {
            issues.add("Session has no immutable drawer account");
        }
        Map<String, Map<String, Object>> byAccount = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byMethod = new LinkedHashMap<>();
        long pendingPayments = invoices.stream().filter(i -> !"credit".equals(i.get("paymentMode"))
                && List.of("unsettled", "partially_settled").contains(String.valueOf(i.get("settlementStatus")))
                && SqlRows.dec(i, "amountResidual").signum() > 0).count();
        for (Map<String, Object> p : payments) {
            int sign = "inbound".equals(p.get("direction")) ? 1 : -1;
            BigDecimal settled = SqlRows.dec(p, "amount").subtract(SqlRows.dec(p, "withholdingAmount"));
            String method = (String) p.get("paymentMethod");
            Map<String, Object> bucket = byMethod.computeIfAbsent(method, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("method", k);
                b.put("count", 0);
                b.put("total", "0");
                return b;
            });
            bucket.put("count", (Integer) bucket.get("count") + 1);
            bucket.put("total", plain(new BigDecimal((String) bucket.get("total"))
                    .add(SqlRows.dec(p, "amount").multiply(BigDecimal.valueOf(sign)))));
            String accountId = (String) p.get("accountId");
            Map<String, Object> account = byAccount.computeIfAbsent(accountId, SessionReconciliation::emptyAccount);
            String field = sign == 1 ? "receipts" : "refunds";
            account.put(field, plain(new BigDecimal((String) account.get(field)).add(settled)));
            account.put("net", plain(new BigDecimal((String) account.get("net")).add(settled.multiply(BigDecimal.valueOf(sign)))));
            @SuppressWarnings("unchecked")
            List<Object> paymentIds = (List<Object>) account.get("paymentIds");
            paymentIds.add(p.get("id"));
            String number = (String) p.get("paymentNumber");
            List<Map<String, Object>> journalLines = p.get("journalEntryId") == null ? null
                    : postedLines(organizationId, (String) p.get("journalEntryId"), "e.status::text = 'posted'");
            if (journalLines == null) {
                issues.add("Payment " + number + " has no active posted journal");
                continue;
            }
            BigDecimal postedAmount = BigDecimal.ZERO;
            for (Map<String, Object> l : journalLines) {
                if (accountId != null && accountId.equals(l.get("accountId"))) {
                    postedAmount = postedAmount.add(SqlRows.dec(l, "debit").subtract(SqlRows.dec(l, "credit")));
                }
            }
            if (postedAmount.compareTo(settled.multiply(BigDecimal.valueOf(sign))) != 0) {
                issues.add("Payment " + number + " differs from its account journal");
            }
            Map<String, BigDecimal> allocatedAr = new LinkedHashMap<>();
            for (Map<String, Object> allocation : SqlRows.list(jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                    + " WHERE \"paymentId\" = ? AND \"invoiceId\" IS NOT NULL").param(p.get("id")))) {
                String receivable = jdbc.sql("SELECT \"receivableAccountId\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(allocation.get("invoiceId"), organizationId).query(String.class).optional().orElse(null);
                if (receivable == null) {
                    issues.add("Payment " + number + " has an unverified invoice receivable");
                } else {
                    allocatedAr.merge(receivable, SqlRows.dec(allocation, "amount"), BigDecimal::add);
                }
            }
            for (Map.Entry<String, BigDecimal> ar : allocatedAr.entrySet()) {
                BigDecimal credited = BigDecimal.ZERO;
                for (Map<String, Object> l : journalLines) {
                    if (ar.getKey().equals(l.get("accountId")) && Objects.equals(l.get("partnerId"), p.get("partnerId"))) {
                        credited = credited.add(SqlRows.dec(l, "credit").subtract(SqlRows.dec(l, "debit")));
                    }
                }
                if (credited.compareTo(ar.getValue()) != 0) {
                    issues.add("Payment " + number + " does not clear the allocated receivable amount");
                }
            }
            List<Map<String, Object>> cashMoves = movements.stream().filter(m -> Objects.equals(m.get("paymentId"), p.get("id"))).toList();
            if ("cash".equals(method)) {
                List<String> allowed = sign == 1 ? List.of("sale")
                        : p.get("refundOfId") != null ? List.of("refund") : List.of("supplier_payment", "refund");
                if (!Objects.equals(accountId, drawerAccountId) || cashMoves.size() != 1
                        || SqlRows.dec(cashMoves.getFirst(), "amount").compareTo(settled) != 0
                        || !allowed.contains(String.valueOf(cashMoves.getFirst().get("movementType")))) {
                    issues.add("Payment " + number + " differs from the physical drawer movement");
                }
            } else if (!cashMoves.isEmpty()) {
                issues.add("Electronic payment " + number + " incorrectly moved drawer cash");
            }
        }
        for (Map<String, Object> m : movements) {
            if (m.get("paymentId") != null) {
                continue;
            }
            List<Map<String, Object>> lines = m.get("journalEntryId") != null
                    ? postedLines(organizationId, (String) m.get("journalEntryId"), "e.status::text IN ('posted', 'reversed')")
                    : postedLinesBySource(organizationId, (String) m.get("id"));
            if (lines == null) {
                issues.add("Drawer movement " + m.get("id") + " has no posted journal");
                continue;
            }
            BigDecimal expected = SqlRows.dec(m, "amount").multiply("pay_out".equals(m.get("movementType"))
                    ? BigDecimal.ONE.negate() : BigDecimal.ONE);
            BigDecimal actual = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                if (Objects.equals(l.get("accountId"), drawerAccountId)) {
                    actual = actual.add(SqlRows.dec(l, "debit").subtract(SqlRows.dec(l, "credit")));
                }
            }
            if (expected.compareTo(actual) != 0) {
                issues.add("Drawer movement " + m.get("id") + " differs from the register cash account");
            }
        }
        List<Map<String, Object>> settlements = SqlRows.list(jdbc.sql("SELECT * FROM \"TenderSettlement\""
                + " WHERE \"organizationId\" = ? AND \"cashSessionId\" = ?").params(organizationId, sessionId));
        List<String> trackedIds = trackedTenderAccountIds();
        for (String id : trackedIds) {
            byAccount.computeIfAbsent(id, SessionReconciliation::emptyAccount);
        }
        Map<String, Map<String, Object>> expectations = sessionProviderExpectations(organizationId, session, trackedIds);
        for (Map.Entry<String, Map<String, Object>> e : expectations.entrySet()) {
            Map<String, Object> a = byAccount.get(e.getKey());
            Map<String, Object> x = e.getValue();
            for (String k : List.of("opening", "openingKnown", "settledOut", "settledIn", "movementsIn", "movementsOut", "expected")) {
                a.put(k, x.get(k));
            }
            a.put("tracked", true);
        }
        for (Map<String, Object> account : byAccount.values()) {
            Map<String, Object> master = SqlRows.one(jdbc.sql("SELECT code, name FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(account.get("accountId"), organizationId));
            account.put("name", master == null ? null : master.get("name"));
            account.put("code", master == null ? null : master.get("code"));
            BigDecimal settled = BigDecimal.ZERO;
            for (Map<String, Object> s : settlements) {
                if (Objects.equals(s.get("sourceAccountId"), account.get("accountId"))) {
                    settled = settled.add(SqlRows.dec(s, "grossAmount"));
                }
            }
            account.put("settled", plain(settled));
            account.put("pendingSettlement", plain(new BigDecimal((String) account.get("net")).subtract(settled)));
        }
        Set<Object> supplierPaymentIds = new HashSet<>();
        for (Map<String, Object> p : payments) {
            if ("outbound".equals(p.get("direction")) && p.get("refundOfId") == null) {
                supplierPaymentIds.add(p.get("id"));
            }
        }
        BigDecimal legacySupplierPayouts = BigDecimal.ZERO;
        for (Map<String, Object> m : movements) {
            if ("refund".equals(m.get("movementType")) && supplierPaymentIds.contains(m.get("paymentId"))) {
                legacySupplierPayouts = legacySupplierPayouts.add(SqlRows.dec(m, "amount"));
            }
        }
        BigDecimal supplierPayouts = movementTotal(movements, "supplier_payment").add(legacySupplierPayouts);
        BigDecimal expectedCash = SqlRows.dec(session, "openingFloat").add(movementTotal(movements, "sale"))
                .add(movementTotal(movements, "pay_in")).add(movementTotal(movements, "adjustment"))
                .subtract(movementTotal(movements, "pay_out")).subtract(movementTotal(movements, "refund"))
                .subtract(movementTotal(movements, "supplier_payment"));
        BigDecimal ledgerCash = drawerAccountId == null ? BigDecimal.ZERO
                : accountLedgerBalance(jdbc, organizationId, drawerAccountId);
        BigDecimal expectedLedger = open ? expectedCash
                : session.get("closingCounted") == null ? expectedCash : SqlRows.dec(session, "closingCounted");
        if (open && ledgerCash.compareTo(expectedLedger) != 0) {
            issues.add("Physical drawer movements differ from the register ledger balance");
        }
        List<Map<String, Object>> refundEvents = SqlRows.list(jdbc.sql("SELECT * FROM \"PosRefund\" WHERE \"organizationId\" = ?"
                + " AND \"cashSessionId\" = ?").params(organizationId, sessionId));
        BigDecimal refundedRevenue = BigDecimal.ZERO;
        BigDecimal refundedTax = BigDecimal.ZERO;
        BigDecimal refundedTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : refundEvents) {
            refundedTotal = refundedTotal.add(SqlRows.dec(r, "amount"));
            if (r.get("items") instanceof JsonNode items && items.isArray()) {
                for (JsonNode it : items) {
                    refundedRevenue = refundedRevenue.add(num(it.get("subtotal")));
                    refundedTax = refundedTax.add(num(it.get("taxAmount")));
                }
            }
        }
        Map<String, Map<String, Object>> categories = new LinkedHashMap<>();
        BigDecimal approvedDiscounts = BigDecimal.ZERO;
        for (Map<String, Object> invoice : invoices) {
            for (Map<String, Object> item : SqlRows.list(jdbc.sql("SELECT * FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?"
                    + " ORDER BY \"lineNumber\" ASC").param(invoice.get("id")))) {
                Map<String, Object> category = null;
                if (item.get("menuItemId") != null) {
                    category = SqlRows.one(jdbc.sql("SELECT c.id, c.name FROM \"MenuItem\" m JOIN \"MenuCategory\" c ON c.id = m.\"categoryId\""
                            + " WHERE m.id = ? AND m.\"organizationId\" = ?").params(item.get("menuItemId"), organizationId));
                } else if (item.get("productId") != null) {
                    category = SqlRows.one(jdbc.sql("SELECT c.id, c.name FROM \"Product\" p JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\""
                            + " WHERE p.id = ? AND p.\"organizationId\" = ?").params(item.get("productId"), organizationId));
                }
                String key = category == null ? "uncategorised" : (String) category.get("id");
                Map<String, Object> cat = category;
                Map<String, Object> bucket = categories.computeIfAbsent(key, k -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("categoryId", cat == null ? null : cat.get("id"));
                    b.put("categoryName", cat == null ? "Uncategorised" : cat.get("name"));
                    b.put("count", BigDecimal.ZERO);
                    b.put("total", "0");
                    return b;
                });
                bucket.put("count", ((BigDecimal) bucket.get("count")).add(SqlRows.dec(item, "quantity")));
                bucket.put("total", plain(new BigDecimal((String) bucket.get("total")).add(SqlRows.dec(item, "total"))));
                if (item.get("discountApprovedBy") != null) {
                    approvedDiscounts = approvedDiscounts.add(SqlRows.dec(item, "discountAmount"));
                }
            }
        }
        for (Map<String, Object> bucket : categories.values()) {
            bucket.put("count", jsNumber((BigDecimal) bucket.get("count")));
        }
        Map<String, Object> cashier = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"User\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(session.get("userId"), organizationId));

        Map<String, Object> cashSession = new LinkedHashMap<>();
        cashSession.put("registerName", register == null ? null : register.get("name"));
        cashSession.put("cashierName", cashier == null ? session.get("userId")
                : (cashier.get("firstName") + " " + (cashier.get("lastName") == null ? "" : cashier.get("lastName"))).trim());
        cashSession.put("id", sessionId);
        cashSession.put("cashRegisterId", session.get("cashRegisterId"));
        cashSession.put("userId", session.get("userId"));
        cashSession.put("openedAt", session.get("openedAt"));
        cashSession.put("openingFloat", plain(SqlRows.dec(session, "openingFloat")));

        BigDecimal grossSales = sumInvoices(invoices, "totalAmount");
        BigDecimal netRevenue = sumInvoices(invoices, "subtotal");
        BigDecimal taxTotal = sumInvoices(invoices, "taxAmount");
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("grossSales", plain(grossSales));
        totals.put("netRevenue", plain(netRevenue));
        totals.put("taxTotal", plain(taxTotal));
        totals.put("discountTotal", plain(sumInvoices(invoices, "discountTotal")));
        totals.put("overridesTotal", plain(approvedDiscounts));
        totals.put("refundedTotal", plain(refundedTotal));
        totals.put("refundedRevenue", plain(refundedRevenue));
        totals.put("refundedTax", plain(refundedTax));
        totals.put("netRevenueAfterRefunds", plain(netRevenue.subtract(refundedRevenue)));
        totals.put("taxAfterRefunds", plain(taxTotal.subtract(refundedTax)));
        totals.put("netSalesAfterRefunds", plain(grossSales.subtract(refundedTotal)));
        totals.put("saleCount", invoices.size());
        totals.put("salesTotal", plain(grossSales));
        totals.put("cashCollected", plain(movementTotal(movements, "sale")));
        totals.put("cashRefunds", plain(movementTotal(movements, "refund").subtract(legacySupplierPayouts)));
        totals.put("supplierPayouts", plain(supplierPayouts));
        totals.put("payInsTotal", plain(movementTotal(movements, "pay_in")));
        totals.put("payOutsTotal", plain(movementTotal(movements, "pay_out")));
        totals.put("adjustments", plain(movementTotal(movements, "adjustment")));
        totals.put("expectedCash", plain(expectedCash));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("asOf", Instant.now());
        report.put("cashSession", cashSession);
        report.put("totals", totals);
        report.put("byMethod", new ArrayList<>(byMethod.values()));
        report.put("byCategory", new ArrayList<>(categories.values()));
        report.put("refunds", refundEvents);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledgerCash", plain(ledgerCash));
        out.put("unsettledOrders", unsettledOrders);
        out.put("openOrderCount", unsettledOrders);
        out.put("openOrders", openOrders);
        out.put("pendingPayments", pendingPayments);
        out.put("pendingPostings", postingJobs);
        out.put("issues", issues);
        out.put("accounts", new ArrayList<>(byAccount.values()));
        out.put("settlements", settlements);
        out.put("byMethod", new ArrayList<>(byMethod.values()));
        out.put("report", report);
        return out;
    }

    /** Provider settlement: sweep a wallet / card-clearing balance into a bank, net of an optional fee. */
    public Map<String, Object> settleTender(Map<String, Object> input) {
        String org = TenantContext.organizationId();
        BigDecimal gross = Money.dec(input.get("grossAmount"));
        BigDecimal fee = Money.dec(input.get("feeAmount") == null ? 0 : input.get("feeAmount"));
        String reference = input.get("reference") == null ? null : String.valueOf(input.get("reference"));
        if (gross.signum() <= 0 || fee.signum() < 0 || fee.compareTo(gross) > 0 || reference == null || reference.trim().isEmpty()) {
            throw ApiException.badRequest("Enter a positive gross settlement, valid fee and provider reference");
        }
        String source = (String) input.get("sourceAccountId");
        String destination = (String) input.get("destinationAccountId");
        String cashSessionId = (String) input.get("cashSessionId");
        String feeAccountId = (String) input.get("feeAccountId");
        Instant settledAt = OrgDates.parse(String.valueOf(input.get("settledAt")));
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put(source, 0);
        obs.put(destination, 0);
        Map<String, Map<String, Object>> accounts = accountObservations(org, obs);
        if (source.equals(destination) || !"bank".equals(accounts.get(destination).get("accountType"))) {
            throw ApiException.badRequest("Settlement must move to a different bank account");
        }
        if (cashSessionId != null) {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(cashSessionId, org)
                    .query(String.class).list();
            long exists = jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(cashSessionId, org).query(Long.class).single();
            if (exists == 0) {
                throw ApiException.badRequest("Session is unavailable");
            }
        }
        jdbc.sql("SELECT id FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(source, org)
                .query(String.class).list();
        Map<String, Object> original = SqlRows.one(jdbc.sql("SELECT * FROM \"TenderSettlement\" WHERE \"organizationId\" = ?"
                + " AND \"sourceAccountId\" = ? AND reference = ? LIMIT 1").params(org, source, reference.trim()));
        if (original != null) {
            if (gross.compareTo(SqlRows.dec(original, "grossAmount")) != 0 || fee.compareTo(SqlRows.dec(original, "feeAmount")) != 0
                    || !Objects.equals(original.get("destinationAccountId"), destination)
                    || !Objects.equals(original.get("cashSessionId"), cashSessionId)
                    || !Objects.equals(original.get("feeAccountId"), feeAccountId)
                    || !Objects.equals(original.get("settledAt"), settledAt)) {
                throw ApiException.badRequest("Provider reference already belongs to a different settlement");
            }
            BusinessOutcome.record(jdbc, original, true);
            return original;
        }
        if (gross.compareTo(accountLedgerBalance(jdbc, org, source)) > 0) {
            throw ApiException.badRequest("Settlement exceeds the available source-account balance");
        }
        if (cashSessionId != null) {
            BigDecimal net = jdbc.sql("SELECT coalesce(sum(CASE WHEN direction::text = 'inbound' THEN amount ELSE -amount END), 0)"
                            + " FROM \"Payment\" WHERE \"organizationId\" = ? AND \"cashSessionId\" = ? AND \"accountId\" = ?"
                            + " AND status::text <> 'cancelled'")
                    .params(org, cashSessionId, source).query(BigDecimal.class).single();
            BigDecimal prior = jdbc.sql("SELECT coalesce(sum(\"grossAmount\"), 0) FROM \"TenderSettlement\" WHERE \"organizationId\" = ?"
                            + " AND \"cashSessionId\" = ? AND \"sourceAccountId\" = ?")
                    .params(org, cashSessionId, source).query(BigDecimal.class).single();
            if (gross.compareTo(net.subtract(prior)) > 0) {
                throw ApiException.badRequest("Settlement exceeds this session’s outstanding provider receipts");
            }
        }
        List<PostingRequest.Line> lines = new ArrayList<>();
        lines.add(PostingRequest.Line.credit(source, plain(gross), null));
        lines.add(PostingRequest.Line.debit(destination, plain(gross.subtract(fee)), null));
        if (fee.signum() > 0) {
            String classification = feeAccountId == null ? null : jdbc.sql("SELECT c.classification::text FROM \"Account\" a"
                            + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?"
                            + " AND a.\"isActive\" = true")
                    .params(feeAccountId, org).query(String.class).optional().orElse(null);
            if (!"expense".equals(classification)) {
                throw ApiException.badRequest("Select an expense account for provider fees");
            }
            lines.add(PostingRequest.Line.debit(feeAccountId, plain(fee), null));
        }
        String id = UUID.randomUUID().toString();
        Map<String, Object> entry = posting.post(PostingRequest.of("BANK", settledAt, "Provider settlement " + reference,
                "tender_settlement", id, null, lines));
        jdbc.sql("INSERT INTO \"TenderSettlement\" (id, \"organizationId\", \"cashSessionId\", \"sourceAccountId\","
                        + " \"destinationAccountId\", \"grossAmount\", \"feeAmount\", \"feeAccountId\", reference, \"settledAt\","
                        + " \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org, cashSessionId, source, destination, gross, fee, feeAccountId, reference.trim(),
                        FiscalPeriodControl.ts(settledAt), entry.get("id"), TenantContext.userId())
                .update();
        Map<String, Object> result = SqlRows.one(jdbc.sql("SELECT * FROM \"TenderSettlement\" WHERE id = ?").param(id));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceAccountId", source);
        values.put("destinationAccountId", destination);
        values.put("gross", plain(gross));
        values.put("fee", plain(fee));
        values.put("reference", reference);
        audit.recordInTx(AuditInput.of("TenderSettlement", id, "create", null, values));
        BusinessOutcome.record(jdbc, result, true);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> postedLines(String organizationId, String entryId, String statusClause) {
        long n = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" e WHERE e.id = ? AND e.\"organizationId\" = ? AND " + statusClause)
                .params(entryId, organizationId).query(Long.class).single();
        if (n == 0) {
            return null;
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId));
    }

    private List<Map<String, Object>> postedLinesBySource(String organizationId, String movementId) {
        String entryId = jdbc.sql("SELECT id FROM \"JournalEntry\" e WHERE e.\"organizationId\" = ? AND e.\"sourceType\" = 'cash_movement'"
                        + " AND e.\"sourceId\" = ? AND e.status::text IN ('posted', 'reversed') LIMIT 1")
                .params(organizationId, movementId).query(String.class).optional().orElse(null);
        return entryId == null ? null
                : SqlRows.list(jdbc.sql("SELECT * FROM \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId));
    }

    private static Map<String, Object> emptyAccount(String accountId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("accountId", accountId);
        a.put("receipts", "0");
        a.put("refunds", "0");
        a.put("net", "0");
        a.put("paymentIds", new ArrayList<>());
        return a;
    }

    static BigDecimal movementTotal(List<Map<String, Object>> movements, String type) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> m : movements) {
            if (type.equals(m.get("movementType"))) {
                total = total.add(SqlRows.dec(m, "amount"));
            }
        }
        return total;
    }

    private static BigDecimal sumInvoices(List<Map<String, Object>> invoices, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> i : invoices) {
            total = total.add(SqlRows.dec(i, field));
        }
        return total;
    }

    private static BigDecimal num(JsonNode n) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(n.isNumber() ? n.numberValue().toString() : n.asString());
    }

    static Number jsNumber(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? (Number) s.longValue() : (Number) v.doubleValue();
    }
}
