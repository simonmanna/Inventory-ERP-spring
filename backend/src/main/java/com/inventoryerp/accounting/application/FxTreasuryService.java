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
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foreign-currency treasury administration (R-8, ADR-S076): the state of each foreign cash account, the designation of
 * legacy balances, and the realised / unrealised FX summary.
 */
@Service
public class FxTreasuryService {

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AuditService audit;

    public FxTreasuryService(JdbcClient jdbc, PostingService posting, AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.audit = audit;
    }

    /** Input of {@code POST /fx-treasury/designations}. */
    public record DesignationInput(String accountId, BigDecimal foreignBalance, String date, String note) {}

    /**
     * Every foreign-denominated cash, bank and mobile-money account with its state: {@code designated} (in FX treasury)
     * or {@code undesignated} (holds balances of unknown currency), its foreign balance, carrying value and the
     * unknown-currency balance a designation would take over.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> accounts() {
        String org = TenantContext.organizationId();
        String functional = ForeignCash.functional(jdbc, org);
        List<String> ids = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND c.\"isCashEquivalent\" AND"
                        + " a.\"currencyId\" IS NOT NULL AND upper(a.\"currencyId\") <> upper(?) ORDER BY a.code")
                .params(org, functional).query(String.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            ForeignCash.Denomination d = ForeignCash.of(jdbc, org, id);
            if (d == null) {
                continue;
            }
            ForeignCash.Balance bal = ForeignCash.balance(jdbc, org, id, d.currency());
            BigDecimal rate = bal.carryingRate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", id);
            row.put("code", d.code());
            row.put("name", d.name());
            row.put("currencyCode", d.currency());
            row.put("functionalCurrency", functional);
            row.put("state", d.designated() ? "designated" : "undesignated");
            row.put("designatedAt", d.designatedAt());
            row.put("foreignBalance", plain(bal.foreign()));
            row.put("carryingValue", plain(bal.base()));
            row.put("carryingRate", rate == null ? null : plain(rate));
            row.put("unknownCurrencyBalance", plain(untaggedBase(org, id, d.currency())));
            out.add(row);
        }
        return out;
    }

    /**
     * Designate a legacy account's balance (plan P-11): the user states the foreign balance; one balanced,
     * functional-neutral entry moves the account's unknown-currency book value onto a line in the denomination. No
     * historical line changes and functional totals do not move; any gap to the current rate is recognised by the next
     * period-end revaluation.
     */
    @Transactional
    public Map<String, Object> designate(DesignationInput in) {
        if (in == null || in.accountId() == null || in.accountId().isBlank()) {
            throw ApiException.badRequest("accountId is required");
        }
        if (in.foreignBalance() == null) {
            throw ApiException.badRequest("foreignBalance is required");
        }
        String org = TenantContext.organizationId();
        TreasuryGuards.lockAccounts(jdbc, org, List.of(in.accountId()));
        ForeignCash.Denomination d = ForeignCash.of(jdbc, org, in.accountId());
        if (d == null) {
            throw ApiException.badRequest("Only a cash, bank or mobile-money account held in a foreign currency can be"
                    + " designated");
        }
        if (d.designatedAt() != null) {
            throw ApiException.badRequest("Account " + d.code() + " is already designated in " + d.currency());
        }
        BigDecimal stated = Money.dec(in.foreignBalance());
        ForeignCash.assertDecimals(jdbc, d.currency(), stated.abs());
        String functional = ForeignCash.functional(jdbc, org);
        BigDecimal unknown = untaggedBase(org, d.accountId(), d.currency());
        ForeignCash.Balance tagged = ForeignCash.balance(jdbc, org, d.accountId(), d.currency());
        BigDecimal delta = stated.subtract(tagged.foreign());
        String entryId = null;
        if (unknown.signum() != 0 || delta.signum() != 0) {
            if (unknown.signum() == 0 || delta.signum() != unknown.signum()) {
                throw ApiException.badRequest("Account " + d.code() + " holds " + plain(unknown) + " " + functional
                        + " of unknown currency; state a " + d.currency() + " balance of the same sign (it holds "
                        + plain(tagged.foreign()) + " " + d.currency() + " already)");
            }
            Instant date = in.date() == null || in.date().isBlank() ? Instant.now()
                    : OrgDates.orgPostingInstant(in.date(), OrgDates.orgTimezone(jdbc, org), Instant.now());
            String base = plain(unknown.abs());
            String foreign = plain(delta.abs());
            String label = "Designation of " + d.code() + " as " + d.currency() + " " + plain(stated);
            List<PostingRequest.Line> lines = unknown.signum() > 0
                    ? List.of(PostingRequest.Line.debit(d.accountId(), base, label).inCurrency(d.currency(), foreign),
                            PostingRequest.Line.credit(d.accountId(), base, label + " (unknown-currency balance)"))
                    : List.of(PostingRequest.Line.debit(d.accountId(), base, label + " (unknown-currency balance)"),
                            PostingRequest.Line.credit(d.accountId(), base, label).inCurrency(d.currency(), foreign));
            BigDecimal rate = unknown.abs().divide(delta.abs(), 10, RoundingMode.HALF_UP);
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", date, label, "fx_designation",
                            d.accountId(), "fx-designation:" + d.accountId(), lines),
                    List.of(new CurrencyService.Resolution(d.currency(), functional, rate, "carrying", date)));
            entryId = (String) entry.get("id");
        }
        jdbc.sql("UPDATE \"Account\" SET \"currencyDesignatedAt\" = ?, \"currencyDesignationEntryId\" = ?,"
                        + " \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(Timestamp.from(Instant.now()), entryId, d.accountId(), org).update();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("change", "fx_designation");
        values.put("currencyCode", d.currency());
        values.put("foreignBalance", plain(stated));
        values.put("carryingValue", plain(tagged.base().add(unknown)));
        values.put("journalEntryId", entryId);
        values.put("note", in.note());
        // AuditAction is a closed DB enum: the designation is an account update marked by "change"
        audit.recordInTx(AuditInput.of("Account", d.accountId(), "update", null, values));
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.remove("change");
        result.put("accountId", d.accountId());
        result.put("code", d.code());
        result.put("state", "designated");
        BusinessOutcome.record(jdbc, result, true);
        return result;
    }

    /**
     * Realised and unrealised FX (functional currency) between two dates, by origin: realised FX from payments,
     * treasury operations and credit notes; unrealised from period-end revaluations and their reversals.
     */
    /**
     * FX-7 (ADR-S082): FX gain / loss in a period grouped by {@code groupBy} = {@code partner}, {@code account},
     * {@code currency}, {@code origin} (source type) or {@code document} (source document). Realised = FX lines of
     * settlements; unrealised = revaluations and their reversals. Partner and currency come from the entry's own
     * partner / currency lines (the FX line carries neither). Positive = gain.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String fromDate, String toDate, String groupBy) {
        String org = TenantContext.organizationId();
        Instant[] range = range(fromDate, toDate, org);
        String key = switch (groupBy == null || groupBy.isBlank() ? "partner" : groupBy) {
            case "partner" -> "coalesce(px.\"partnerId\", '')";
            case "account" -> "coalesce(ax.\"accountId\", '')";
            case "currency" -> "coalesce(cx.ccy, '')";
            case "origin" -> "coalesce(o.\"sourceType\", e.\"sourceType\")";
            case "document" -> "coalesce(o.\"sourceType\", e.\"sourceType\") || ':' || coalesce(o.\"sourceId\","
                    + " e.\"sourceId\", e.id)";
            default -> throw ApiException.badRequest("groupBy must be one of: partner, account, currency, origin,"
                    + " document");
        };
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + key + " AS key, sum(CASE WHEN " + UNREALISED
                        + " THEN 0 ELSE l.\"baseCredit\" - l.\"baseDebit\" END) AS realised, sum(CASE WHEN " + UNREALISED
                        + " THEN l.\"baseCredit\" - l.\"baseDebit\" ELSE 0 END) AS unrealised, count(DISTINCT e.id) AS entries"
                        + FX_FROM + " GROUP BY 1 ORDER BY abs(sum(l.\"baseCredit\" - l.\"baseDebit\")) DESC, 1")
                .params(org, Timestamp.from(range[0]), Timestamp.from(range[1])));
        Map<String, String> labels = labels(groupBy == null || groupBy.isBlank() ? "partner" : groupBy, rows, org);
        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal re = SqlRows.dec(r, "realised");
            BigDecimal un = SqlRows.dec(r, "unrealised");
            realised = realised.add(re);
            unrealised = unrealised.add(un);
            String k = String.valueOf(r.get("key"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k.isEmpty() ? null : k);
            row.put("label", k.isEmpty() ? "(none)" : labels.getOrDefault(k, k));
            row.put("realised", plain(re));
            row.put("unrealised", plain(un));
            row.put("total", plain(re.add(un)));
            row.put("entries", ((Number) r.get("entries")).longValue());
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("functionalCurrency", ForeignCash.functional(jdbc, org));
        result.put("groupBy", groupBy == null || groupBy.isBlank() ? "partner" : groupBy);
        result.put("realised", plain(realised));
        result.put("unrealised", plain(unrealised));
        result.put("total", plain(realised.add(unrealised)));
        result.put("rows", out);
        return result;
    }

    /**
     * FX-7: the FX lines behind a report row: one per FX journal line with its entry, source, partner, currency and the
     * payment allocations that realised it (when the source is a payment). Filters are optional.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reportLines(String fromDate, String toDate, String kind, String partnerId,
            String accountId, String currency) {
        String org = TenantContext.organizationId();
        Instant[] range = range(fromDate, toDate, org);
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>(List.of(org, Timestamp.from(range[0]), Timestamp.from(range[1])));
        if ("realised".equals(kind)) {
            where.append(" AND NOT ").append(UNREALISED);
        } else if ("unrealised".equals(kind)) {
            where.append(" AND ").append(UNREALISED);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND px.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (accountId != null && !accountId.isBlank()) {
            where.append(" AND ax.\"accountId\" = ?");
            params.add(accountId);
        }
        if (currency != null && !currency.isBlank()) {
            where.append(" AND cx.ccy = ?");
            params.add(currency.trim().toUpperCase());
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id AS \"journalEntryId\", e.\"entryNumber\","
                        + " e.\"postingDate\", e.description, coalesce(o.\"sourceType\", e.\"sourceType\") AS \"sourceType\","
                        + " coalesce(o.\"sourceId\", e.\"sourceId\") AS \"sourceId\", CASE WHEN " + UNREALISED + " THEN"
                        + " 'unrealised' ELSE 'realised' END AS kind, px.\"partnerId\", p.name AS \"partnerName\","
                        + " ax.\"accountId\", ac.code AS \"accountCode\", ac.name AS \"accountName\", cx.ccy AS currency,"
                        + " l.\"baseCredit\" - l.\"baseDebit\" AS gain" + FX_FROM + where
                        + " ORDER BY e.\"postingDate\" DESC, e.\"entryNumber\" DESC LIMIT 1000")
                .params(params.toArray()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("gain", plain(SqlRows.dec(r, "gain")));
            if ("payment".equals(r.get("sourceType")) || "payment_allocation".equals(r.get("sourceType"))) {
                row.put("allocations", SqlRows.list(jdbc.sql("SELECT a.\"documentId\", d.\"documentNumber\", a.amount,"
                        + " a.\"carryingRate\", a.\"settlementRate\", a.\"fxAmount\" FROM \"PaymentAllocation\" a LEFT JOIN"
                        + " \"Document\" d ON d.id = a.\"documentId\" WHERE a.\"paymentId\" = ? AND a.\"organizationId\" = ?")
                        .params(r.get("sourceId"), org)));
            }
            out.add(row);
        }
        return out;
    }

    /** Revaluation entries and their reversals (FX-7 classification, same as the summary). */
    private static final String UNREALISED = "(coalesce(o.\"sourceType\", e.\"sourceType\") = 'fx_revaluation')";

    /**
     * FX lines (on the designated fx_gain / fx_loss accounts) in [from, to) with, per entry, its first partner line,
     * first partner-line account and first foreign currency (lateral lookups: an FX line carries none of these).
     */
    private static final String FX_FROM = " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
            + " LEFT JOIN \"JournalEntry\" o ON o.id = e.\"reversalOfId\" JOIN \"AccountMapping\" m ON m.\"accountId\" ="
            + " l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key IN ('fx_gain', 'fx_loss') AND"
            + " m.\"deletedAt\" IS NULL"
            + " LEFT JOIN LATERAL (SELECT x.\"partnerId\" FROM \"JournalLine\" x WHERE x.\"journalEntryId\" = e.id AND"
            + " x.\"partnerId\" IS NOT NULL ORDER BY x.\"lineNumber\" LIMIT 1) px ON true"
            + " LEFT JOIN LATERAL (SELECT x.\"accountId\" FROM \"JournalLine\" x WHERE x.\"journalEntryId\" = e.id AND"
            + " x.\"currencyId\" IS NOT NULL AND x.\"accountId\" <> l.\"accountId\" ORDER BY x.\"partnerId\" IS NULL,"
            + " x.\"lineNumber\" LIMIT 1) ax ON true"
            + " LEFT JOIN LATERAL (SELECT upper(x.\"currencyId\") AS ccy FROM \"JournalLine\" x JOIN \"Organization\" g ON"
            + " g.id = x.\"organizationId\" WHERE x.\"journalEntryId\" = e.id AND x.\"currencyId\" IS NOT NULL AND"
            + " upper(x.\"currencyId\") <> upper(g.\"currencyCode\") ORDER BY x.\"lineNumber\" LIMIT 1) cx ON true"
            + " LEFT JOIN \"Partner\" p ON p.id = px.\"partnerId\" LEFT JOIN \"Account\" ac ON ac.id = ax.\"accountId\""
            + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND"
            + " e.\"postingDate\" < ?";

    private Map<String, String> labels(String groupBy, List<Map<String, Object>> rows, String org) {
        Map<String, String> out = new HashMap<>();
        List<String> keys = rows.stream().map(r -> String.valueOf(r.get("key"))).filter(k -> !k.isEmpty()).toList();
        if (keys.isEmpty()) {
            return out;
        }
        String sql = switch (groupBy) {
            case "partner" -> "SELECT id, name FROM \"Partner\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])";
            case "account" -> "SELECT id, code || ' ' || name FROM \"Account\" WHERE \"organizationId\" = ? AND"
                    + " id = ANY(?::text[])";
            default -> null;
        };
        if (sql != null) {
            jdbc.sql(sql).params(org, keys.toArray(String[]::new))
                    .query((rs, n) -> out.put(rs.getString(1), rs.getString(2))).list();
        }
        return out;
    }

    private Instant[] range(String fromDate, String toDate, String org) {
        java.time.ZoneId zone = AccountingDay.zone(jdbc, org);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        try {
            return new Instant[] {AccountingDay.start(fromDate == null || fromDate.isBlank() ? today.withDayOfMonth(1)
                    : java.time.LocalDate.parse(fromDate.substring(0, 10)), zone),
                AccountingDay.endExclusive(toDate == null || toDate.isBlank() ? today
                    : java.time.LocalDate.parse(toDate.substring(0, 10)), zone)};
        } catch (RuntimeException e) {
            throw ApiException.badRequest("from and to must be dates (YYYY-MM-DD)");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String fromDate, String toDate) {
        String org = TenantContext.organizationId();
        java.time.ZoneId zone = AccountingDay.zone(jdbc, org);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        Instant from;
        Instant to;
        try {
            from = AccountingDay.start(fromDate == null || fromDate.isBlank() ? today.withDayOfMonth(1)
                    : java.time.LocalDate.parse(fromDate.substring(0, 10)), zone);
            to = AccountingDay.endExclusive(toDate == null || toDate.isBlank() ? today
                    : java.time.LocalDate.parse(toDate.substring(0, 10)), zone);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("from and to must be dates (YYYY-MM-DD)");
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT CASE WHEN e.\"sourceType\" = 'fx_revaluation' OR"
                        + " o.\"sourceType\" = 'fx_revaluation' THEN 'unrealised' ELSE 'realised' END AS kind,"
                        + " coalesce(o.\"sourceType\", e.\"sourceType\") AS origin, coalesce(sum(l.\"baseCredit\" -"
                        + " l.\"baseDebit\"), 0) AS gain FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" LEFT JOIN \"JournalEntry\" o ON o.id = e.\"reversalOfId\" JOIN"
                        + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" ="
                        + " l.\"organizationId\" AND m.key IN ('fx_gain', 'fx_loss') AND m.\"deletedAt\" IS NULL WHERE"
                        + " l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ?"
                        + " AND e.\"postingDate\" < ? GROUP BY 1, 2 ORDER BY 1, 2")
                .params(org, Timestamp.from(from), Timestamp.from(to)));
        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;
        List<Map<String, Object>> byOrigin = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal gain = SqlRows.dec(r, "gain");
            if ("unrealised".equals(r.get("kind"))) {
                unrealised = unrealised.add(gain);
            } else {
                realised = realised.add(gain);
            }
            byOrigin.add(Map.of("kind", r.get("kind"), "origin", String.valueOf(r.get("origin")), "gain", plain(gain)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("functionalCurrency", ForeignCash.functional(jdbc, org));
        out.put("realised", plain(realised));
        out.put("unrealised", plain(unrealised));
        out.put("total", plain(realised.add(unrealised)));
        out.put("byOrigin", byOrigin);
        return out;
    }

    /**
     * Book value (functional) of the account's lines that are not in its denomination, revaluations excluded; the base
     * side of a designation offsets it to zero.
     */
    private BigDecimal untaggedBase(String org, String accountId, String currency) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed') AND (l.\"currencyId\" IS NULL"
                        + " OR upper(l.\"currencyId\") <> ?) AND coalesce(e.\"sourceType\", '') <> 'fx_revaluation' AND NOT EXISTS"
                        + " (SELECT 1 FROM \"JournalEntry\" o WHERE o.id = e.\"reversalOfId\" AND o.\"sourceType\" ="
                        + " 'fx_revaluation')")
                .params(org, accountId, currency).query(BigDecimal.class).single();
    }
}
