package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.GlAutomationCommands.Allocation;
import com.inventoryerp.accounting.application.GlAutomationCommands.BankRuleInput;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bank matching rules and partial / grouped matches (ADR-S073, ACC-6 extension; Nest has no counterpart).
 * <ul>
 *   <li><b>Rules</b> per bank (GL) account, tried in priority order on unmatched lines: description / reference
 *       contains or regex, absolute amount range, direction. {@code match_payment} pairs the line with the closest
 *       posted payment of the same size and direction (D-B5) not yet matched anywhere; {@code post_entry} books the
 *       line against the rule's account (bank charges, interest, fees) with an optional tax included in the amount,
 *       posting key {@code bank_rule:<lineId>} — unless the movement is already in the books (review R5): a payment or
 *       an unreconciled GL line on the bank account of the same size and direction inside the rule's date window is
 *       matched instead of posting a second entry. Rules run after every import (after commit, one transaction per
 *       line) and on demand.</li>
 *   <li><b>Allocations</b> ({@code BankStatementMatch}): one line to several payments and several lines to one
 *       payment, partial on either side, direction-aware. A line stays {@code partial} until its allocations cover
 *       it.</li>
 * </ul>
 * Lines on or before a locked reconciliation statement are never touched (ACC-6).
 */
@Service
public class BankMatchingService {

    private static final Logger log = LoggerFactory.getLogger(BankMatchingService.class);

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AuditService audit;
    private final TransactionTemplate isolated;
    private CurrencyService currency;

    /** R-8 (ADR-S076): rate resolution for rule postings on a foreign-currency bank account. */
    @org.springframework.beans.factory.annotation.Autowired
    void setCurrency(CurrencyService currency) {
        this.currency = currency;
    }

    public BankMatchingService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.audit = audit;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ rules: CRUD

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules(String bankAccountId) {
        if (bankAccountId == null || bankAccountId.isEmpty()) {
            throw ApiException.badRequest("bankAccountId is required");
        }
        return SqlRows.list(jdbc.sql("SELECT r.*, a.code AS \"counterAccountCode\", a.name AS \"counterAccountName\""
                + " FROM \"BankMatchRule\" r LEFT JOIN \"Account\" a ON a.id = r.\"counterAccountId\" WHERE"
                + " r.\"organizationId\" = ? AND r.\"bankAccountId\" = ? AND r.\"deletedAt\" IS NULL ORDER BY"
                + " r.priority, r.\"createdAt\"").params(TenantContext.organizationId(), bankAccountId))
                .stream().map(BankMatchingService::renderRule).toList();
    }

    @Transactional
    public Map<String, Object> createRule(BankRuleInput in) {
        if (in.bankAccountId() == null || in.bankAccountId().isBlank()) {
            throw ApiException.badRequest("bankAccountId is required");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (in.action() == null) {
            throw ApiException.badRequest("action must be one of match_payment, post_entry");
        }
        String org = TenantContext.organizationId();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"BankMatchRule\" (id, \"organizationId\", \"bankAccountId\", name, action, \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?, ?)").params(id, org, in.bankAccountId(), in.name().trim(), in.action(),
                TenantContext.userId()).update();
        applyFields(id, in);
        Map<String, Object> out = rule(id);
        audit.recordInTx(AuditInput.of("BankMatchRule", id, "create", null, out));
        return out;
    }

    @Transactional
    public Map<String, Object> updateRule(String id, BankRuleInput in) {
        Map<String, Object> before = rule(id);
        applyFields(id, in);
        Map<String, Object> out = rule(id);
        audit.recordInTx(AuditInput.of("BankMatchRule", id, "update", before, out));
        return out;
    }

    @Transactional
    public void deleteRule(String id) {
        rule(id);
        jdbc.sql("UPDATE \"BankMatchRule\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, TenantContext.organizationId()).update();
        audit.recordInTx(AuditInput.of("BankMatchRule", id, "delete"));
    }

    private void applyFields(String id, BankRuleInput in) {
        String org = TenantContext.organizationId();
        jdbc.sql("UPDATE \"BankMatchRule\" SET name = coalesce(?, name), priority = coalesce(?, priority),"
                        + " \"isActive\" = coalesce(?, \"isActive\"), \"matchField\" = coalesce(?, \"matchField\"),"
                        + " \"matchType\" = coalesce(?, \"matchType\"), pattern = coalesce(?, pattern),"
                        + " \"minAmount\" = coalesce(?, \"minAmount\"), \"maxAmount\" = coalesce(?, \"maxAmount\"),"
                        + " direction = coalesce(?, direction), action = coalesce(?, action),"
                        + " \"counterAccountId\" = coalesce(?, \"counterAccountId\"), \"taxId\" = coalesce(?, \"taxId\"),"
                        + " \"partnerId\" = coalesce(?, \"partnerId\"), \"journalCode\" = coalesce(?, \"journalCode\"),"
                        + " \"dateToleranceDays\" = coalesce(?, \"dateToleranceDays\"),"
                        + " \"entryDescription\" = coalesce(?, \"entryDescription\"), \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(in.name(), in.priority(), in.isActive(), in.matchField(), in.matchType(), in.pattern(),
                        in.minAmount(), in.maxAmount(), in.direction(), in.action(), in.counterAccountId(), in.taxId(),
                        in.partnerId(), in.journalCode(), in.dateToleranceDays(), in.entryDescription(), id, org)
                .update();
        Map<String, Object> r = rawRule(id);
        if ("regex".equals(r.get("matchType"))) {
            String p = (String) r.get("pattern");
            if (p == null || p.isEmpty()) {
                throw ApiException.badRequest("pattern is required for a regex rule");
            }
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                throw ApiException.badRequest("Invalid regular expression: " + e.getDescription());
            }
        }
        BigDecimal min = (BigDecimal) r.get("minAmount");
        BigDecimal max = (BigDecimal) r.get("maxAmount");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.badRequest("minAmount must not exceed maxAmount");
        }
        if ("post_entry".equals(r.get("action"))) {
            String counter = (String) r.get("counterAccountId");
            if (counter == null) {
                throw ApiException.badRequest("counterAccountId is required for a post_entry rule");
            }
            if (jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS"
                    + " NULL").params(counter, org).query(Long.class).single() == 0) {
                throw ApiException.badRequest("One or more accounts do not exist in this organization");
            }
            // Control-account policy (V518): a statement line is matched to the customer / supplier payment
            // (match_payment), never booked by a rule straight to an AR / AP control account.
            posting.assertNotReceivablePayableControl(List.of(counter));
        }
        if (r.get("taxId") != null && jdbc.sql("SELECT count(*) FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(r.get("taxId"), org).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Tax not found");
        }
    }

    private Map<String, Object> rawRule(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"BankMatchRule\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, TenantContext.organizationId()));
        if (r == null) {
            throw ApiException.notFound("Bank rule not found");
        }
        return r;
    }

    private Map<String, Object> rule(String id) {
        return renderRule(rawRule(id));
    }

    // ------------------------------------------------------------------ rules: apply

    /** On-demand run of the account's rules over its unmatched lines (each line commits on its own). */
    public Map<String, Object> applyRules(String bankAccountId) {
        if (bankAccountId == null || bankAccountId.isEmpty()) {
            throw ApiException.badRequest("bankAccountId is required");
        }
        return apply(TenantContext.organizationId(), bankAccountId);
    }

    /** Import hook: runs the rules once the import committed; never fails the import. */
    void afterImport(String org, String bankAccountId) {
        try {
            Map<String, Object> result = apply(org, bankAccountId);
            log.debug("[bank-rules] after import {}: {}", bankAccountId, result);
        } catch (RuntimeException e) {
            log.warn("[bank-rules] after import {} failed: {}", bankAccountId, e.toString());
        }
    }

    private Map<String, Object> apply(String org, String bankAccountId) {
        List<Map<String, Object>> rules = isolated.execute(s -> SqlRows.list(jdbc.sql("SELECT * FROM \"BankMatchRule\""
                + " WHERE \"organizationId\" = ? AND \"bankAccountId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL"
                + " ORDER BY priority, \"createdAt\"").params(org, bankAccountId)));
        int matched = 0;
        int posted = 0;
        int skipped = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        if (!rules.isEmpty()) {
            List<Map<String, Object>> lines = isolated.execute(s -> {
                Instant locked = lockedThrough(org, bankAccountId);
                return SqlRows.list(jdbc.sql("SELECT id, \"postedAt\", \"externalRef\", description, amount FROM"
                        + " \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ? AND status ="
                        + " 'unmatched'" + (locked == null ? "" : " AND \"postedAt\" > ?") + " ORDER BY \"postedAt\", id")
                        .params(locked == null ? List.of(org, bankAccountId)
                                : List.of(org, bankAccountId, FiscalPeriodControl.ts(locked))));
            });
            for (Map<String, Object> line : lines) {
                Map<String, Object> rule = rules.stream().filter(r -> matches(r, line)).findFirst().orElse(null);
                if (rule == null) {
                    skipped++;
                    continue;
                }
                try {
                    String outcome = isolated.execute(s -> applyOne(org, bankAccountId, rule, (String) line.get("id")));
                    if ("matched".equals(outcome)) {
                        matched++;
                    } else if ("posted".equals(outcome)) {
                        posted++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("lineId", line.get("id"));
                    f.put("ruleId", rule.get("id"));
                    f.put("error", e.getMessage());
                    failures.add(f);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", rules.size());
        out.put("matched", matched);
        out.put("posted", posted);
        out.put("skipped", skipped);
        out.put("failures", failures);
        return out;
    }

    /** Whether a rule's text, amount and direction conditions all hold for a line. */
    static boolean matches(Map<String, Object> rule, Map<String, Object> line) {
        BigDecimal amount = SqlRows.dec(line, "amount");
        String direction = (String) rule.get("direction");
        if ("inbound".equals(direction) && amount.signum() <= 0 || "outbound".equals(direction) && amount.signum() >= 0) {
            return false;
        }
        BigDecimal abs = amount.abs();
        BigDecimal min = rule.get("minAmount") == null ? null : SqlRows.dec(rule, "minAmount");
        BigDecimal max = rule.get("maxAmount") == null ? null : SqlRows.dec(rule, "maxAmount");
        if (min != null && abs.compareTo(min) < 0 || max != null && abs.compareTo(max) > 0) {
            return false;
        }
        String pattern = (String) rule.get("pattern");
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String field = (String) rule.get("matchField");
        List<String> texts = new ArrayList<>();
        if (!"reference".equals(field)) {
            texts.add((String) line.get("description"));
        }
        if (!"description".equals(field)) {
            texts.add((String) line.get("externalRef"));
        }
        boolean regex = "regex".equals(rule.get("matchType"));
        for (String t : texts) {
            if (t == null) {
                continue;
            }
            if (regex ? Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(t).find()
                    : t.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String applyOne(String org, String bankAccountId, Map<String, Object> rule, String lineId) {
        BankReconciliationService.lockBankAccount(jdbc, org, bankAccountId);
        Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT * FROM \"BankStatementLine\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND status = 'unmatched' FOR UPDATE").params(lineId, org));
        if (line == null) {
            return "skipped";
        }
        BigDecimal amount = SqlRows.dec(line, "amount");
        Instant at = (Instant) line.get("postedAt");
        long tolMs = ((Number) rule.get("dateToleranceDays")).longValue() * 24 * 60 * 60 * 1000;
        if ("match_payment".equals(rule.get("action"))) {
            String payment = paymentCandidate(org, bankAccountId, amount, at, tolMs, (String) rule.get("partnerId"));
            if (payment == null) {
                return "skipped";
            }
            matchRulePayment(lineId, rule, payment, "Rule: " + rule.get("name"));
            return "matched";
        }
        // Review R5: a movement already in the books (a payment, or a transfer / expense / petty-cash top-up / payroll
        // line journalled on the bank account) is matched, never booked a second time by the rule.
        String payment = paymentCandidate(org, bankAccountId, amount, at, tolMs, (String) rule.get("partnerId"));
        if (payment != null) {
            matchRulePayment(lineId, rule, payment, "Rule: " + rule.get("name") + " · already booked by a payment");
            return "matched";
        }
        String journalLine = journalLineCandidate(jdbc, org, bankAccountId, ForeignCash.of(jdbc, org, bankAccountId),
                amount, at, tolMs);
        if (journalLine != null) {
            jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedJournalLineId\" = ?,"
                    + " \"matchedRuleId\" = ?, note = ? WHERE id = ?").params(journalLine, rule.get("id"), "Rule: "
                    + rule.get("name") + " · already in the ledger", lineId).update();
            audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, Map.of("matchedJournalLineId",
                    journalLine, "matchedRuleId", rule.get("id"))));
            return "matched";
        }
        if (receivablePayableControl(org, (String) rule.get("counterAccountId"))) {
            // The counter account was designated AR / AP after the rule was saved: leave the line for a payment match.
            return "skipped";
        }
        String entryId = postRuleEntry(org, bankAccountId, rule, line);
        jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"journalEntryId\" = ?, \"matchedRuleId\" = ?,"
                + " note = ? WHERE id = ?").params(entryId, rule.get("id"), "Rule: " + rule.get("name"), lineId).update();
        audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, Map.of("journalEntryId", entryId,
                "matchedRuleId", rule.get("id"))));
        return "posted";
    }

    /** Whether {@code accountId} is a designated AR / AP control account (control-account policy, V518). */
    private boolean receivablePayableControl(String org, String accountId) {
        return accountId != null && jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"controlAccountType\"::text IN ('ar', 'ap')").params(accountId, org).query(Long.class).single() > 0;
    }

    /** Closest unmatched posted payment of the line's size (in the account's currency) and direction in the window. */
    private String paymentCandidate(String org, String bankAccountId, BigDecimal amount, Instant at, long tolMs,
            String partnerId) {
        List<Object> params = new ArrayList<>(List.of(org, bankAccountId, amount.signum() < 0 ? "outbound" : "inbound",
                amount.abs(), FiscalPeriodControl.ts(at.minus(Duration.ofMillis(tolMs))),
                FiscalPeriodControl.ts(at.plus(Duration.ofMillis(tolMs)))));
        String partnerFilter = "";
        if (partnerId != null) {
            partnerFilter = " AND p.\"partnerId\" = ?";
            params.add(partnerId);
        }
        params.add(FiscalPeriodControl.ts(at));
        return jdbc.sql("SELECT p.id FROM \"Payment\" p WHERE p.\"organizationId\" = ? AND p.\"accountId\" = ?"
                        + " AND p.status = 'posted' AND p.direction::text = ? AND " + accountAmountSql("p") + " = ?"
                        + " AND p.\"paymentDate\" >= ? AND p.\"paymentDate\" <= ?" + partnerFilter + " AND "
                        + UNMATCHED_PAYMENT + " ORDER BY abs(extract(epoch FROM p.\"paymentDate\" - ?::timestamp)), p.id"
                        + " LIMIT 1 FOR UPDATE")
                .params(params).query(String.class).optional().orElse(null);
    }

    private void matchRulePayment(String lineId, Map<String, Object> rule, String payment, String note) {
        jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedPaymentId\" = ?, \"matchedRuleId\" = ?,"
                + " note = ? WHERE id = ?").params(payment, rule.get("id"), note, lineId).update();
        audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, Map.of("matchedPaymentId",
                payment, "matchedRuleId", rule.get("id"))));
    }

    /**
     * Charge (negative line): Dr counter account net, Dr input tax, Cr bank. Income (positive line): Dr bank, Cr counter
     * account net, Cr output tax. The tax is included in the line amount.
     */
    private String postRuleEntry(String org, String bankAccountId, Map<String, Object> rule, Map<String, Object> line) {
        BigDecimal amount = SqlRows.dec(line, "amount");
        BigDecimal gross = amount.abs();
        boolean outflow = amount.signum() < 0;
        BigDecimal net = gross;
        BigDecimal tax = BigDecimal.ZERO;
        String taxAccount = null;
        if (rule.get("taxId") != null) {
            Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT rate, \"accountId\" FROM \"Tax\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(rule.get("taxId"), org));
            if (t != null) {
                BigDecimal[] split = GlSchedules.splitInclusive(gross, SqlRows.dec(t, "rate"));
                net = split[0];
                tax = split[1];
                taxAccount = determination.taxAccount((String) t.get("accountId"),
                        outflow ? "tax_receivable" : "tax_payable");
            }
        }
        String counter = (String) rule.get("counterAccountId");
        String partner = (String) rule.get("partnerId");
        ForeignCash.Denomination fx = ForeignCash.of(jdbc, org, bankAccountId);
        if (fx != null) {
            return postForeignRuleEntry(org, bankAccountId, rule, line, fx, outflow, gross, net, tax, taxAccount,
                    counter, partner);
        }
        String text = rule.get("entryDescription") != null ? (String) rule.get("entryDescription")
                : (String) line.get("description");
        List<Line> lines = new ArrayList<>();
        if (outflow) {
            lines.add(new Line(counter, plain(net), null, partner, text, null, null, null));
            if (tax.signum() > 0) {
                lines.add(new Line(taxAccount, plain(tax), null, partner, "Tax on " + text, null, null, null));
            }
            lines.add(new Line(bankAccountId, null, plain(gross), partner, text, null, null, null));
        } else {
            lines.add(new Line(bankAccountId, plain(gross), null, partner, text, null, null, null));
            lines.add(new Line(counter, null, plain(net), partner, text, null, null, null));
            if (tax.signum() > 0) {
                lines.add(new Line(taxAccount, null, plain(tax), partner, "Tax on " + text, null, null, null));
            }
        }
        String journal = (String) rule.get("journalCode");
        if (jdbc.sql("SELECT count(*) FROM \"Journal\" WHERE \"organizationId\" = ? AND code = ?").params(org, journal)
                .query(Long.class).single() == 0) {
            journal = "GEN";
        }
        Map<String, Object> entry = posting.post(new PostingRequest(journal, (Instant) line.get("postedAt"),
                "Bank rule " + rule.get("name") + ": " + line.get("description"), null, null, "bank_statement_line",
                (String) line.get("id"), null, "bank_rule:" + line.get("id"), null, null, null, lines));
        return (String) entry.get("id");
    }

    /**
     * A rule posting on a foreign-currency bank account (R-8, ADR-S076): the statement amount is in the account's
     * currency; the counter and tax lines take the functional value at the line date's rate; money out is relieved at
     * the account's carrying value and the difference is realised FX.
     */
    private String postForeignRuleEntry(String org, String bankAccountId, Map<String, Object> rule,
            Map<String, Object> line, ForeignCash.Denomination fx, boolean outflow, BigDecimal gross, BigDecimal net,
            BigDecimal tax, String taxAccount, String counter, String partner) {
        ForeignCash.requireDesignated(fx);
        Instant at = (Instant) line.get("postedAt");
        CurrencyService.Resolution rate = currency.resolve(fx.currency(), ForeignCash.functional(jdbc, org), null, at);
        BigDecimal netBase = net.multiply(rate.rate()).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal taxBase = tax.multiply(rate.rate()).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal valued = netBase.add(taxBase);
        BigDecimal bankBase = valued;
        if (outflow) {
            TreasuryGuards.lockAccounts(jdbc, org, List.of(bankAccountId));
            bankBase = ForeignCash.relieve(ForeignCash.balance(jdbc, org, bankAccountId, fx.currency()), gross,
                    rate.rate());
        }
        BigDecimal gain = outflow ? valued.subtract(bankBase) : BigDecimal.ZERO;
        String text = rule.get("entryDescription") != null ? (String) rule.get("entryDescription")
                : (String) line.get("description");
        List<Line> lines = new ArrayList<>();
        Line bank = new Line(bankAccountId, outflow ? null : plain(bankBase), outflow ? plain(bankBase) : null, partner,
                text, null, null, null).inCurrency(fx.currency(), plain(gross));
        lines.add(bank);
        lines.add(new Line(counter, outflow ? plain(netBase) : null, outflow ? null : plain(netBase), partner, text,
                null, null, null));
        if (taxBase.signum() > 0) {
            lines.add(new Line(taxAccount, outflow ? plain(taxBase) : null, outflow ? null : plain(taxBase), partner,
                    "Tax on " + text, null, null, null));
        }
        String fxLabel = "Realised FX · " + fx.currency() + " @ " + plain(rate.rate());
        if (gain.signum() > 0) {
            lines.add(Line.credit(determination.mapped("fx_gain"), plain(gain), fxLabel));
        } else if (gain.signum() < 0) {
            lines.add(Line.debit(determination.mapped("fx_loss"), plain(gain.negate()), fxLabel));
        }
        String journal = (String) rule.get("journalCode");
        if (jdbc.sql("SELECT count(*) FROM \"Journal\" WHERE \"organizationId\" = ? AND code = ?").params(org, journal)
                .query(Long.class).single() == 0) {
            journal = "GEN";
        }
        Map<String, Object> entry = posting.post(new PostingRequest(journal, at, "Bank rule " + rule.get("name") + ": "
                        + line.get("description"), null, null, "bank_statement_line", (String) line.get("id"), null,
                "bank_rule:" + line.get("id"), null, null, null, lines), List.of(rate));
        return (String) entry.get("id");
    }

    // ------------------------------------------------------------------ partial and grouped matches

    /**
     * A payment counts as matched (for auto-match and candidates) when a line is matched to it in full or any
     * allocation uses it. Callers alias the payment as {@code p}.
     */
    static final String UNMATCHED_PAYMENT = "NOT EXISTS (SELECT 1 FROM \"BankStatementLine\" b WHERE b.\"organizationId\""
            + " = p.\"organizationId\" AND b.\"matchedPaymentId\" = p.id) AND NOT EXISTS (SELECT 1 FROM"
            + " \"BankStatementMatch\" m WHERE m.\"organizationId\" = p.\"organizationId\" AND m.\"paymentId\" = p.id)";

    /**
     * Payment {@code alias}'s cash movement in its bank account's currency (review R9), the figure a statement line
     * shows: the amount net of withholding for a functional-currency payment (its cash line), the amount itself for a
     * foreign payment through an account held in that currency, and amount × payment rate (its functional cash line)
     * for a foreign payment through a functional account.
     */
    static String accountAmountSql(String alias) {
        return "(CASE WHEN " + alias + ".\"currencyId\" IS NULL THEN " + alias + ".amount - " + alias
                + ".\"withholdingAmount\" WHEN EXISTS (SELECT 1 FROM \"Account\" pxa WHERE pxa.id = " + alias
                + ".\"accountId\" AND upper(pxa.\"currencyId\") = upper(" + alias + ".\"currencyId\")) THEN " + alias
                + ".amount ELSE round(" + alias + ".amount * " + alias + ".\"exchangeRate\", 6) END)";
    }

    /**
     * A GL line counts as unreconciled (review R5) when no statement line is matched to it and its entry is neither a
     * payment's (those reconcile through the payment), a statement line's rule posting, a revaluation or designation
     * (base-only adjustments, not bank movements). Callers alias the line {@code l} and its entry {@code e}.
     */
    static final String UNMATCHED_JOURNAL_LINE = "NOT EXISTS (SELECT 1 FROM \"BankStatementLine\" b WHERE"
            + " b.\"organizationId\" = l.\"organizationId\" AND b.\"matchedJournalLineId\" = l.id) AND NOT EXISTS (SELECT 1"
            + " FROM \"BankStatementLine\" b WHERE b.\"organizationId\" = l.\"organizationId\" AND b.\"journalEntryId\" ="
            + " e.id) AND NOT EXISTS (SELECT 1 FROM \"Payment\" p WHERE p.\"organizationId\" = l.\"organizationId\" AND"
            + " p.\"journalEntryId\" = e.id) AND coalesce(e.\"sourceType\", '') NOT IN ('payment', 'bank_statement_line',"
            + " 'fx_revaluation', 'fx_designation')";

    /**
     * Closest unreconciled posted GL line on the bank account for a statement line (review R5): money in pairs with a
     * debit, money out with a credit, of the line's size in the account's currency (the line's own amount on a
     * designated foreign account, else the functional amount) and posted within {@code tolMs} of {@code at}. Reversed
     * entries and reversals are never candidates. Null when there is none.
     */
    static String journalLineCandidate(JdbcClient jdbc, String org, String bankAccountId, ForeignCash.Denomination fx,
            BigDecimal amount, Instant at, long tolMs) {
        boolean in = amount.signum() > 0;
        boolean own = fx != null && fx.designated();
        List<Object> params = new ArrayList<>(List.of(org, bankAccountId));
        String size;
        if (own) {
            size = "upper(l.\"currencyId\") = ? AND l." + (in ? "debit" : "credit") + " = ?";
            params.add(fx.currency());
        } else {
            size = "l.\"" + (in ? "baseDebit" : "baseCredit") + "\" = ?";
        }
        params.add(amount.abs());
        params.add(FiscalPeriodControl.ts(at.minus(Duration.ofMillis(tolMs))));
        params.add(FiscalPeriodControl.ts(at.plus(Duration.ofMillis(tolMs))));
        params.add(FiscalPeriodControl.ts(at));
        return jdbc.sql("SELECT l.id FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE"
                        + " l.\"organizationId\" = ? AND l.\"accountId\" = ? AND " + size + " AND e.status::text = 'posted'"
                        + " AND e.\"reversalOfId\" IS NULL AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ? AND "
                        + UNMATCHED_JOURNAL_LINE + " ORDER BY abs(extract(epoch FROM e.\"postingDate\" - ?::timestamp)),"
                        + " l.id LIMIT 1")
                .params(params).query(String.class).optional().orElse(null);
    }

    /** Payments of the line's account and direction with an open amount (for grouped / partial matching). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openPayments(String lineId) {
        String org = TenantContext.organizationId();
        Map<String, Object> line = requireLine(org, lineId, false);
        BigDecimal amount = SqlRows.dec(line, "amount");
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM (SELECT p.id, p.\"paymentNumber\","
                + " p.\"paymentDate\", p.amount, p.direction::text AS direction, p.reference, pa.name AS \"partnerName\","
                + " " + accountAmountSql("p") + " - " + allocatedSql("p") + " AS \"openAmount\" FROM \"Payment\" p LEFT"
                + " JOIN \"Partner\" pa ON"
                + " pa.id = p.\"partnerId\" WHERE p.\"organizationId\" = ? AND p.\"accountId\" = ? AND p.status = 'posted'"
                + " AND p.direction::text = ?) x WHERE \"openAmount\" > 0 ORDER BY abs(extract(epoch FROM"
                + " \"paymentDate\" - ?::timestamp)), id LIMIT 50")
                .params(org, line.get("bankAccountId"), amount.signum() < 0 ? "outbound" : "inbound",
                        FiscalPeriodControl.ts((Instant) line.get("postedAt"))));
        rows.forEach(r -> {
            r.put("amount", plain(SqlRows.dec(r, "amount")));
            r.put("openAmount", plain(SqlRows.dec(r, "openAmount")));
        });
        return rows;
    }

    /** Allocations of a line. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> allocations(String lineId) {
        String org = TenantContext.organizationId();
        requireLine(org, lineId, false);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT m.id, m.\"paymentId\", p.\"paymentNumber\","
                + " m.amount, m.\"groupId\", m.\"createdAt\" FROM \"BankStatementMatch\" m LEFT JOIN \"Payment\" p ON"
                + " p.id = m.\"paymentId\" WHERE m.\"organizationId\" = ? AND m.\"statementLineId\" = ? ORDER BY"
                + " m.\"createdAt\"").params(org, lineId));
        rows.forEach(r -> r.put("amount", plain(SqlRows.dec(r, "amount"))));
        return rows;
    }

    /** One line to one or several payments; partial on either side; the line is matched once fully allocated. */
    @Transactional
    public Map<String, Object> allocateLine(String lineId, List<Allocation> allocations) {
        String org = TenantContext.organizationId();
        lockLineAccount(org, lineId);
        Map<String, Object> line = requireLine(org, lineId, true);
        BigDecimal remaining = lineOpen(org, line);
        String groupId = UUID.randomUUID().toString();
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> rows = new ArrayList<>();
        for (Allocation a : allocations) {
            Map<String, Object> p = requirePayment(org, a.paymentId(), line);
            BigDecimal open = SqlRows.dec(p, "accountAmount").subtract(allocated(org, a.paymentId()));
            if (open.signum() <= 0) {
                throw ApiException.conflict("Payment is already matched to another statement line");
            }
            BigDecimal amount = a.amount() != null ? a.amount() : open.min(remaining.subtract(total));
            if (amount.compareTo(open) > 0) {
                throw ApiException.badRequest("Allocation of " + plain(amount) + " exceeds the open amount "
                        + plain(open) + " of payment " + p.get("paymentNumber"));
            }
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("Allocations total exceeds the open amount " + plain(remaining)
                        + " of the statement line");
            }
            total = total.add(amount);
            rows.add(new Object[] {a.paymentId(), amount});
        }
        if (total.compareTo(remaining) > 0) {
            throw ApiException.badRequest("Allocations total " + plain(total) + " exceeds the open amount "
                    + plain(remaining) + " of the statement line");
        }
        for (Object[] r : rows) {
            insertAllocation(org, lineId, (String) r[0], (BigDecimal) r[1], groupId);
        }
        String status = settle(org, lineId, line);
        audit.recordInTx(AuditInput.of("BankStatementLine", lineId, "update", null, Map.of("groupId", groupId,
                "status", status)));
        return lineResult(org, lineId, status, groupId);
    }

    /** Several lines to one payment: each line is allocated its open amount; their total may not exceed the payment. */
    @Transactional
    public Map<String, Object> allocatePayment(String paymentId, List<String> lineIds) {
        String org = TenantContext.organizationId();
        jdbc.sql("SELECT \"accountId\" FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ?").params(paymentId, org)
                .query(String.class).optional()
                .ifPresent(account -> BankReconciliationService.lockBankAccount(jdbc, org, account));
        Map<String, Object> payment = SqlRows.one(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.amount, "
                + accountAmountSql("p") + " AS \"accountAmount\", p.direction::text AS direction, p.status::text AS"
                + " status, p.\"accountId\" FROM \"Payment\" p WHERE p.id = ? AND p.\"organizationId\" = ? FOR UPDATE OF p")
                .params(paymentId, org));
        if (payment == null) {
            throw ApiException.notFound("Payment not found");
        }
        BigDecimal open = SqlRows.dec(payment, "accountAmount").subtract(allocated(org, paymentId));
        String groupId = UUID.randomUUID().toString();
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> rows = new ArrayList<>();
        for (String lineId : lineIds.stream().distinct().toList()) {
            Map<String, Object> line = requireLine(org, lineId, true);
            checkPayment(payment, line);
            BigDecimal amount = lineOpen(org, line);
            total = total.add(amount);
            rows.add(new Object[] {lineId, amount, line});
        }
        if (total.compareTo(open) > 0) {
            throw ApiException.badRequest("Statement lines total " + plain(total) + " exceeds the open amount "
                    + plain(open) + " of the payment");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            insertAllocation(org, (String) r[0], paymentId, (BigDecimal) r[1], groupId);
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) r[2];
            String status = settle(org, (String) r[0], line);
            out.add(lineResult(org, (String) r[0], status, groupId));
        }
        audit.recordInTx(AuditInput.of("Payment", paymentId, "update", null, Map.of("groupId", groupId, "lines",
                lineIds)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", paymentId);
        result.put("groupId", groupId);
        result.put("openAmount", plain(open.subtract(total)));
        result.put("lines", out);
        return result;
    }

    /**
     * Unmatch hook: drops the line's allocations and reverses a rule-posted entry, so a line goes back to unrecorded
     * and the ledger no longer holds its charge.
     */
    void undo(String org, String lineId) {
        jdbc.sql("DELETE FROM \"BankStatementMatch\" WHERE \"organizationId\" = ? AND \"statementLineId\" = ?")
                .params(org, lineId).update();
        String entry = jdbc.sql("SELECT \"journalEntryId\" FROM \"BankStatementLine\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(lineId, org).query(String.class).optional().orElse(null);
        if (entry != null) {
            String status = jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entry)
                    .query(String.class).optional().orElse(null);
            if ("posted".equals(status)) {
                posting.reverse(entry, null, "Bank rule match undone");
            }
        }
        jdbc.sql("UPDATE \"BankStatementLine\" SET \"journalEntryId\" = NULL, \"matchedRuleId\" = NULL WHERE id = ? AND"
                + " \"organizationId\" = ?").params(lineId, org).update();
    }

    private void insertAllocation(String org, String lineId, String paymentId, BigDecimal amount, String groupId) {
        jdbc.sql("INSERT INTO \"BankStatementMatch\" (id, \"organizationId\", \"statementLineId\", \"paymentId\", amount,"
                        + " \"groupId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org, lineId, paymentId, amount, groupId, TenantContext.userId())
                .update();
    }

    /** Line status from its allocations: matched when covered, else partial. */
    private String settle(String org, String lineId, Map<String, Object> line) {
        BigDecimal open = SqlRows.dec(line, "amount").abs().subtract(lineAllocated(org, lineId));
        String status = open.signum() <= 0 ? "matched" : "partial";
        jdbc.sql("UPDATE \"BankStatementLine\" SET status = ?, \"matchedRunId\" = NULL WHERE id = ? AND"
                + " \"organizationId\" = ?").params(status, lineId, org).update();
        return status;
    }

    private Map<String, Object> lineResult(String org, String lineId, String status, String groupId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", lineId);
        out.put("status", status);
        out.put("groupId", groupId);
        out.put("allocated", plain(lineAllocated(org, lineId)));
        return out;
    }

    /** Takes the bank account's reconciliation lock (review R-7) for the statement line, before the line is read. */
    private void lockLineAccount(String org, String lineId) {
        jdbc.sql("SELECT \"bankAccountId\" FROM \"BankStatementLine\" WHERE id = ? AND \"organizationId\" = ?")
                .params(lineId, org).query(String.class).optional()
                .ifPresent(account -> BankReconciliationService.lockBankAccount(jdbc, org, account));
    }

    private Map<String, Object> requireLine(String org, String lineId, boolean forChange) {
        Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT id, \"bankAccountId\", \"postedAt\", amount, status FROM"
                + " \"BankStatementLine\" WHERE id = ? AND \"organizationId\" = ?" + (forChange ? " FOR UPDATE" : ""))
                .params(lineId, org));
        if (line == null) {
            throw ApiException.notFound("Statement line not found");
        }
        if (forChange) {
            Instant locked = lockedThrough(org, (String) line.get("bankAccountId"));
            Instant at = (Instant) line.get("postedAt");
            if (locked != null && !at.isAfter(locked)) {
                throw ApiException.badRequest("Bank reconciliation is locked through " + FiscalPeriodControl.day(locked)
                        + "; statement lines on or before that date cannot change");
            }
            if (!"unmatched".equals(line.get("status")) && !"partial".equals(line.get("status"))) {
                throw ApiException.badRequest("Only an unmatched or partially matched line can be allocated");
            }
        }
        return line;
    }

    private Map<String, Object> requirePayment(String org, String paymentId, Map<String, Object> line) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.amount, " + accountAmountSql("p")
                + " AS \"accountAmount\", p.direction::text AS direction, p.status::text AS status, p.\"accountId\" FROM"
                + " \"Payment\" p WHERE p.id = ? AND p.\"organizationId\" = ? FOR UPDATE OF p").params(paymentId, org));
        if (p == null) {
            throw ApiException.notFound("Payment not found");
        }
        checkPayment(p, line);
        return p;
    }

    private static void checkPayment(Map<String, Object> p, Map<String, Object> line) {
        if (!"posted".equals(p.get("status")) || !line.get("bankAccountId").equals(p.get("accountId"))) {
            throw ApiException.badRequest("Payment is not a posted payment of this account");
        }
        String direction = SqlRows.dec(line, "amount").signum() < 0 ? "outbound" : "inbound";
        if (!direction.equals(p.get("direction"))) {
            throw ApiException.badRequest("Payment direction must match the statement line");
        }
    }

    private BigDecimal lineOpen(String org, Map<String, Object> line) {
        BigDecimal open = SqlRows.dec(line, "amount").abs().subtract(lineAllocated(org, (String) line.get("id")));
        if (open.signum() <= 0) {
            throw ApiException.badRequest("Statement line is already fully allocated");
        }
        return open;
    }

    private BigDecimal lineAllocated(String org, String lineId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"BankStatementMatch\" WHERE \"organizationId\" = ? AND"
                + " \"statementLineId\" = ?").params(org, lineId).query(BigDecimal.class).single();
    }

    private BigDecimal allocated(String org, String paymentId) {
        return jdbc.sql("SELECT " + allocatedSql("p") + " FROM \"Payment\" p WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(paymentId, org).query(BigDecimal.class).single();
    }

    /** Amount of payment {@code alias} already taken: full single matches plus allocations. */
    static String allocatedSql(String alias) {
        return "(coalesce((SELECT sum(abs(b.amount)) FROM \"BankStatementLine\" b WHERE b.\"organizationId\" = " + alias
                + ".\"organizationId\" AND b.\"matchedPaymentId\" = " + alias + ".id), 0) + coalesce((SELECT"
                + " sum(m.amount) FROM \"BankStatementMatch\" m WHERE m.\"organizationId\" = " + alias
                + ".\"organizationId\" AND m.\"paymentId\" = " + alias + ".id), 0))";
    }

    private Instant lockedThrough(String org, String bankAccountId) {
        return jdbc.sql("SELECT max(\"statementDate\") FROM \"BankReconciliationStatement\" WHERE \"organizationId\" = ?"
                        + " AND \"bankAccountId\" = ?").params(org, bankAccountId)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC))
                .optional().orElse(null);
    }

    private static Map<String, Object> renderRule(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.remove("organizationId");
        out.remove("deletedAt");
        out.put("minAmount", r.get("minAmount") == null ? null : plain(SqlRows.dec(r, "minAmount")));
        out.put("maxAmount", r.get("maxAmount") == null ? null : plain(SqlRows.dec(r, "maxAmount")));
        return out;
    }
}
