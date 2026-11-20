package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FX revaluation at period end (IAS 21; port of {@code revaluation.service.ts}, reworked in ACC-2 / B3). Monetary
 * balances in foreign currencies (FX-2 C: the account's setting, else its category default) are revalued per partner
 * to the closing rate of the company's revaluation rate policy; the
 * unrealised gain / loss posts against the {@code fx_gain} / {@code fx_loss} mappings.
 *
 * <p>Lifecycle (owner decision C1, docs/accounting/ACC-0-verification.md):
 * <ul>
 *   <li>the carrying basis is the original foreign-currency lines only — revaluation adjustments never feed it;</li>
 *   <li>each adjustment posts at {@code asOf} with a linked automatic reversal on the next day, so the books return to
 *       carrying values at the start of the next period and realised FX at settlement (ADR-S054) is unaffected;</li>
 *   <li>a re-run for the same date posts only the difference to what is already booked for that date (usually 0);</li>
 *   <li>{@link #preview} computes the same figures without posting.</li>
 * </ul>
 */
@Service
public class RevaluationService {

    static final String SOURCE = "fx_revaluation";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final CurrencyService currency;
    private final PostingService posting;
    private final AccountDeterminationService determination;

    public RevaluationService(JdbcClient jdbc, AuditService audit, EventBus events, CurrencyService currency,
            PostingService posting, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.currency = currency;
        this.posting = posting;
        this.determination = determination;
    }

    /** Result of one run: number of (account, currency) balances revalued and the net unrealized gain. */
    public record Result(int revalued, String totalGain) {}

    /** One (account, currency) line of a revaluation: foreign balance, carrying base, revalued base, adjustment. */
    public record Line(String accountId, String accountCode, String currencyCode, BigDecimal foreignBalance,
            BigDecimal carryingBase, BigDecimal rate, BigDecimal revaluedBase, BigDecimal adjustment,
            BigDecimal alreadyBooked, BigDecimal toPost, String partnerId, String partnerName) {}

    /** FX-2 C: a condition the run refuses on unless acknowledged ({@code acknowledgeWarnings}). */
    public record Warning(String code, String message, String accountId, String accountCode) {}

    /**
     * FX-2 C: SQL predicate (alias {@code a} = Account, {@code c} = AccountCategory) for an account whose foreign
     * balances are monetary: its own setting, else its category default ({@link #MONETARY_CATEGORIES}) unless it is one
     * of the {@link #NON_MONETARY_MAPPINGS}.
     */
    /**
     * FX-2 C: liability accounts that inherit as non-monetary whatever their category: the customer-deposit (advance)
     * account (IFRIC 22) and the GRNI and landed-cost clearing accounts (clearing balances mixing receipt-time base
     * lines with bill-time foreign lines; the open payable is revalued on accounts payable).
     */
    static final List<String> NON_MONETARY_MAPPINGS = List.of("customer_deposit", "grni_accrued",
            "landed_cost_clearing");

    static final List<String> MONETARY_CATEGORIES = List.of("cash", "bank", "mobile_money", "petty_cash", "receivable",
            "payable", "tax", "current_liability", "non_current_liability");

    static final String MONETARY = "(a.\"fxRevaluation\" = 'monetary' OR (a.\"fxRevaluation\" = 'inherit' AND"
            + " c.key IN ('" + String.join("', '", MONETARY_CATEGORIES) + "') AND NOT EXISTS (SELECT 1 FROM \"AccountMapping\" m WHERE m.\"accountId\" = a.id"
            + " AND m.key IN ('" + String.join("', '", NON_MONETARY_MAPPINGS) + "') AND m.\"deletedAt\" IS NULL)))";

    /**
     * The revaluation instant for a request {@code asOf}: a bare date is the end of that org-local day (ADR-S070),
     * a full ISO timestamp is taken as-is.
     */
    public Instant endOfDay(String asOf) {
        if (asOf.length() == 10) {
            return LocalDate.parse(asOf).atTime(23, 59, 59).atZone(AccountingDay.zone(jdbc)).toInstant();
        }
        return com.inventoryerp.kernel.crud.EntityPatch.toInstant(asOf);
    }

    /** The revaluation as of {@code asOf}, without posting anything. */
    @Transactional(readOnly = true)
    public List<Line> preview(Instant asOf) {
        return compute(TenantContext.organizationId(), asOf);
    }

    /**
     * FX-2 C: conditions to resolve before revaluing: foreign lines on receivable / payable accounts without a partner
     * (they cannot be revalued per partner), and accounts set monetary that hold no foreign lines.
     */
    @Transactional(readOnly = true)
    public List<Warning> warnings(Instant asOf) {
        String org = TenantContext.organizationId();
        String base = ForeignCash.functional(jdbc, org);
        List<Warning> out = new ArrayList<>();
        jdbc.sql("SELECT a.id, a.code, count(*) AS n FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" JOIN \"AccountCategory\" c"
                        + " ON c.id = a.\"categoryId\" WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted',"
                        + " 'reversed') AND e.\"postingDate\" <= ? AND c.key IN ('receivable', 'payable') AND "
                        + MONETARY + " AND l.\"currencyId\" IS NOT NULL AND upper(l.\"currencyId\") <> upper(?) AND"
                        + " l.\"partnerId\" IS NULL GROUP BY a.id, a.code ORDER BY a.code")
                .params(org, FiscalPeriodControl.ts(asOf), base)
                .query((rs, n) -> out.add(new Warning("partnerless_foreign_lines", rs.getLong("n") + " foreign-currency"
                        + " line(s) on " + rs.getString("code") + " have no partner and are revalued as one balance",
                        rs.getString("id"), rs.getString("code"))))
                .list();
        jdbc.sql("SELECT a.id, a.code FROM \"Account\" a WHERE a.\"organizationId\" = ? AND a.\"fxRevaluation\" ="
                        + " 'monetary' AND a.\"deletedAt\" IS NULL AND NOT EXISTS (SELECT 1 FROM \"JournalLine\" l WHERE"
                        + " l.\"accountId\" = a.id AND l.\"currencyId\" IS NOT NULL AND upper(l.\"currencyId\") <> upper(?))"
                        + " ORDER BY a.code")
                .params(org, base)
                .query((rs, n) -> out.add(new Warning("monetary_without_foreign_lines", "Account " + rs.getString("code")
                        + " is set monetary but holds no foreign-currency lines", rs.getString("id"),
                        rs.getString("code"))))
                .list();
        return out;
    }

    /** FX-2 C: set how an account's foreign balances are treated ({@code inherit|monetary|non_monetary}). */
    @Transactional
    public Map<String, Object> setAccountTreatment(String accountId, String mode) {
        if (!List.of("inherit", "monetary", "non_monetary").contains(mode)) {
            throw ApiException.badRequest("fxRevaluation must be one of the following values: inherit, monetary,"
                    + " non_monetary");
        }
        String org = TenantContext.organizationId();
        int n = jdbc.sql("UPDATE \"Account\" SET \"fxRevaluation\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(mode, accountId, org).update();
        if (n == 0) {
            throw ApiException.notFound("Account not found");
        }
        audit.recordInTx(AuditInput.of("Account", accountId, "update", null, Map.of("fxRevaluation", mode)));
        return accountTreatments().stream().filter(m -> accountId.equals(m.get("id"))).findFirst()
                .orElseGet(() -> Map.of("id", accountId, "fxRevaluation", mode));
    }

    /** FX-2 C: balance-sheet accounts with their setting and effective treatment. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> accountTreatments() {
        return SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.key AS \"categoryKey\", a.\"fxRevaluation\","
                + " c.key IN ('" + String.join("', '", MONETARY_CATEGORIES) + "') AS \"monetaryDefault\", " + MONETARY + " AS monetary FROM \"Account\" a JOIN \"AccountCategory\" c"
                + " ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND"
                + " c.classification::text IN ('asset', 'liability') ORDER BY a.code")
                .param(TenantContext.organizationId()));
    }

    /** Revalue every monetary foreign-currency balance as of {@code asOf} and post the (incremental) adjustments. */
    @Transactional
    public Result run(Instant asOf) {
        return run(asOf, true);
    }

    /** FX-2 C: as {@link #run(Instant)}, refused while {@link #warnings} exist unless {@code acknowledgeWarnings}. */
    @Transactional
    public Result run(Instant asOf, boolean acknowledgeWarnings) {
        String organizationId = TenantContext.organizationId();
        if (!acknowledgeWarnings) {
            List<Warning> w = warnings(asOf);
            if (!w.isEmpty()) {
                throw ApiException.badRequest("Resolve or acknowledge the revaluation warnings first: "
                        + String.join("; ", w.stream().map(Warning::message).toList()));
            }
        }
        // FX-1: one revaluation at a time per organisation; two concurrent runs both saw nothing booked and both posted.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))::text").param("fx-revaluation:" + organizationId)
                .query(String.class).single();
        String fxGainAcct = determination.mapped("fx_gain");
        String fxLossAcct = determination.mapped("fx_loss");
        ZoneId zone = AccountingDay.zone(jdbc, organizationId);
        String periodId = periodId(organizationId, AccountingDay.toWallClock(asOf, zone));
        // Date-only reversal on the next accounting day (PostingService places it on that org-local day).
        Instant reversalDate = AccountingDay.of(asOf, zone).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int revalued = 0;
        BigDecimal totalGain = BigDecimal.ZERO;
        // One entry per (account, currency); within it one line per partner (FX-2 C) plus one gain and one loss line.
        Map<String, List<Line>> groups = new LinkedHashMap<>();
        for (Line l : compute(organizationId, asOf)) {
            jdbc.sql("INSERT INTO \"FxRevaluation\" (id, \"organizationId\", \"fiscalPeriodId\", \"asOf\", \"accountId\","
                            + " \"currencyCode\", \"bookBalance\", \"revaluedBalance\", \"fxGain\", rate, \"partnerId\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (\"organizationId\", \"fiscalPeriodId\", \"accountId\", \"currencyCode\","
                            + " (coalesce(\"partnerId\", ''))) DO UPDATE SET \"asOf\" = EXCLUDED.\"asOf\","
                            + " \"bookBalance\" = EXCLUDED.\"bookBalance\", \"revaluedBalance\" = EXCLUDED.\"revaluedBalance\","
                            + " \"fxGain\" = EXCLUDED.\"fxGain\", rate = EXCLUDED.rate")
                    .params(UUID.randomUUID().toString(), organizationId, periodId, FiscalPeriodControl.ts(asOf),
                            l.accountId(), l.currencyCode(), l.carryingBase(), l.revaluedBase(), l.adjustment(), l.rate(),
                            l.partnerId())
                    .update();
            groups.computeIfAbsent(l.accountId() + ":" + l.currencyCode(), k -> new ArrayList<>()).add(l);
            revalued++;
        }
        for (List<Line> group : groups.values()) {
            Line first = group.get(0);
            List<PostingRequest.Line> lines = new ArrayList<>();
            BigDecimal gains = BigDecimal.ZERO;
            BigDecimal losses = BigDecimal.ZERO;
            for (Line l : group) {
                BigDecimal delta = l.toPost();
                if (delta.signum() == 0) {
                    continue;
                }
                String desc = "FX reval " + l.currencyCode();
                lines.add(delta.signum() > 0
                        ? new PostingRequest.Line(l.accountId(), delta, null, l.partnerId(), desc, null, null, null)
                        : new PostingRequest.Line(l.accountId(), null, delta.abs(), l.partnerId(), desc, null, null,
                                null));
                if (delta.signum() > 0) {
                    gains = gains.add(delta);
                } else {
                    losses = losses.add(delta.abs());
                }
                totalGain = totalGain.add(delta);
            }
            if (lines.isEmpty()) {
                continue;
            }
            if (gains.signum() > 0) {
                lines.add(PostingRequest.Line.credit(fxGainAcct, gains, "Unrealized FX gain"));
            }
            if (losses.signum() > 0) {
                lines.add(PostingRequest.Line.debit(fxLossAcct, losses, "Unrealized FX loss"));
            }
            String key = SOURCE + ":" + day(asOf) + ":" + first.accountId() + ":" + first.currencyCode() + ":"
                    + UUID.randomUUID();
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", asOf, "FX revaluation "
                    + first.currencyCode() + " @ " + plain(first.rate()), SOURCE, sourceId(first.accountId(),
                    first.currencyCode(), asOf), key, lines));
            posting.reverse((String) entry.get("id"), reversalDate, "Automatic reversal of FX revaluation "
                    + first.currencyCode() + " " + day(asOf));
        }

        int count = revalued;
        String gain = plain(totalGain);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("asOf", JsonConfig.toJsIso(asOf));
        payload.put("revalued", count);
        payload.put("totalGain", gain);
        AfterCommit.run(() -> events.publish("fx_revaluation.ran", payload));
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("asOf", asOf);
        newValues.put("revalued", count);
        newValues.put("totalGain", gain);
        audit.recordInTx(AuditInput.of("FxRevaluation", periodId, "update", null, newValues));
        return new Result(count, gain);
    }

    /** Revaluation rows recorded for the organization, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT r.*, a.code AS \"accountCode\", a.name AS \"accountName\" FROM"
                + " \"FxRevaluation\" r JOIN \"Account\" a ON a.id = r.\"accountId\" WHERE r.\"organizationId\" = ?"
                + " ORDER BY r.\"asOf\" DESC, a.code").param(TenantContext.organizationId()));
    }

    private List<Line> compute(String organizationId, Instant asOf) {
        String baseCode = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Organization not found"));
        // Per (account, currency): foreign balance and base carrying value of the ORIGINAL foreign-currency lines.
        // Revaluation adjustments and their reversals post in base (no currency) and so never enter the basis.
        // Per (account, currency, partner): foreign balance and base carrying value of the ORIGINAL foreign lines on
        // monetary accounts (FX-2 C: account setting, else category default). Revaluation adjustments and their
        // reversals post in base (no currency) and so never enter the basis.
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        List<Line> out = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", a.code, l.\"currencyId\","
                        + " l.\"partnerId\", p.name AS \"partnerName\","
                        + " sum(l.debit - l.credit) AS foreign, sum(l.\"baseDebit\" - l.\"baseCredit\") AS base"
                        + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                        + " JOIN \"Account\" a ON a.id = l.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " LEFT JOIN \"Partner\" p ON p.id = l.\"partnerId\""
                        + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                        + " AND e.\"postingDate\" <= ? AND " + MONETARY + " AND l.\"currencyId\" IS NOT"
                        + " NULL AND l.\"currencyId\" <> ? GROUP BY l.\"accountId\", a.code, l.\"currencyId\","
                        + " l.\"partnerId\", p.name ORDER BY a.code, l.\"currencyId\", p.name NULLS FIRST")
                .params(organizationId, FiscalPeriodControl.ts(asOf), baseCode))) {
            BigDecimal foreign = SqlRows.dec(l, "foreign");
            BigDecimal base = SqlRows.dec(l, "base");
            String account = (String) l.get("accountId");
            String ccy = (String) l.get("currencyId");
            String partner = (String) l.get("partnerId");
            BigDecimal booked = booked(organizationId, sourceId(account, ccy, asOf), account, partner);
            if (foreign.signum() == 0 && base.signum() == 0 && booked.signum() == 0) {
                continue;
            }
            BigDecimal rate = rates.computeIfAbsent(ccy, k -> currency.policyRate(k, baseCode, asOf, "revaluation"));
            BigDecimal revaluedBase = foreign.multiply(rate).setScale(6, RoundingMode.HALF_UP);
            BigDecimal adjustment = revaluedBase.subtract(base);
            out.add(new Line(account, (String) l.get("code"), ccy, foreign, base, rate, revaluedBase, adjustment, booked,
                    adjustment.subtract(booked), partner, (String) l.get("partnerName")));
        }
        return out;
    }

    /** Net base already booked on {@code accountId} (for {@code partnerId}) by revaluations of this date. */
    private BigDecimal booked(String org, String sourceId, String accountId, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = ? AND e.\"sourceId\" = ? AND l.\"accountId\" = ? AND l.\"partnerId\" IS NOT"
                + " DISTINCT FROM ?")
                .params(org, SOURCE, sourceId, accountId, partnerId).query(BigDecimal.class).single();
    }

    private String sourceId(String accountId, String ccy, Instant asOf) {
        return accountId + ":" + ccy + ":" + day(asOf);
    }

    /** The accounting (org-local) day of {@code asOf}; the UTC day for a UTC organisation. */
    private String day(Instant asOf) {
        return AccountingDay.of(asOf, AccountingDay.zone(jdbc)).toString();
    }

    private String periodId(String org, Instant asOf) {
        return jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"startDate\" <= ? AND"
                        + " \"endDate\" >= ? ORDER BY \"startDate\" DESC LIMIT 1")
                .params(org, FiscalPeriodControl.ts(asOf), FiscalPeriodControl.ts(asOf)).query(String.class).optional()
                .orElse("adhoc");
    }
}
