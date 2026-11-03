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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * balances in foreign currencies (account category {@code allowReconciliation}) are revalued to the closing rate; the
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
            BigDecimal alreadyBooked, BigDecimal toPost) {}

    /** The revaluation as of {@code asOf}, without posting anything. */
    @Transactional(readOnly = true)
    public List<Line> preview(Instant asOf) {
        return compute(TenantContext.organizationId(), asOf);
    }

    /** Revalue every monetary foreign-currency balance as of {@code asOf} and post the (incremental) adjustments. */
    @Transactional
    public Result run(Instant asOf) {
        String organizationId = TenantContext.organizationId();
        String fxGainAcct = determination.mapped("fx_gain");
        String fxLossAcct = determination.mapped("fx_loss");
        String periodId = periodId(organizationId, asOf);
        Instant reversalDate = LocalDate.ofInstant(asOf, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        int revalued = 0;
        BigDecimal totalGain = BigDecimal.ZERO;
        for (Line l : compute(organizationId, asOf)) {
            jdbc.sql("INSERT INTO \"FxRevaluation\" (id, \"organizationId\", \"fiscalPeriodId\", \"asOf\", \"accountId\","
                            + " \"currencyCode\", \"bookBalance\", \"revaluedBalance\", \"fxGain\", rate)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (\"organizationId\", \"fiscalPeriodId\", \"accountId\") DO UPDATE SET"
                            + " \"asOf\" = EXCLUDED.\"asOf\", \"currencyCode\" = EXCLUDED.\"currencyCode\","
                            + " \"bookBalance\" = EXCLUDED.\"bookBalance\", \"revaluedBalance\" = EXCLUDED.\"revaluedBalance\","
                            + " \"fxGain\" = EXCLUDED.\"fxGain\", rate = EXCLUDED.rate")
                    .params(UUID.randomUUID().toString(), organizationId, periodId, FiscalPeriodControl.ts(asOf),
                            l.accountId(), l.currencyCode(), l.carryingBase(), l.revaluedBase(), l.adjustment(), l.rate())
                    .update();
            BigDecimal delta = l.toPost();
            if (delta.signum() != 0) {
                BigDecimal amt = delta.abs();
                List<PostingRequest.Line> lines = delta.signum() > 0
                        ? List.of(PostingRequest.Line.debit(l.accountId(), amt, "FX reval " + l.currencyCode()),
                                PostingRequest.Line.credit(fxGainAcct, amt, "Unrealized FX gain"))
                        : List.of(PostingRequest.Line.debit(fxLossAcct, amt, "Unrealized FX loss"),
                                PostingRequest.Line.credit(l.accountId(), amt, "FX reval " + l.currencyCode()));
                String key = SOURCE + ":" + day(asOf) + ":" + l.accountId() + ":" + l.currencyCode() + ":"
                        + UUID.randomUUID();
                Map<String, Object> entry = posting.post(PostingRequest.of("GEN", asOf, "FX revaluation "
                        + l.currencyCode() + " @ " + plain(l.rate()), SOURCE, sourceId(l.accountId(), l.currencyCode(),
                        asOf), key, lines));
                posting.reverse((String) entry.get("id"), reversalDate, "Automatic reversal of FX revaluation "
                        + l.currencyCode() + " " + day(asOf));
                totalGain = totalGain.add(delta);
            }
            revalued++;
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
        Map<String, Map<String, BigDecimal[]>> balances = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", a.code, l.\"currencyId\","
                        + " sum(l.debit - l.credit) AS foreign, sum(l.\"baseDebit\" - l.\"baseCredit\") AS base"
                        + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                        + " JOIN \"Account\" a ON a.id = l.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                        + " AND e.\"postingDate\" <= ? AND c.\"allowReconciliation\" = true AND l.\"currencyId\" IS NOT"
                        + " NULL AND l.\"currencyId\" <> ? GROUP BY l.\"accountId\", a.code, l.\"currencyId\""
                        + " ORDER BY a.code, l.\"currencyId\"")
                .params(organizationId, FiscalPeriodControl.ts(asOf), baseCode))) {
            String account = (String) l.get("accountId");
            codes.put(account, (String) l.get("code"));
            balances.computeIfAbsent(account, k -> new LinkedHashMap<>()).put((String) l.get("currencyId"),
                    new BigDecimal[] {SqlRows.dec(l, "foreign"), SqlRows.dec(l, "base")});
        }
        List<Line> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal[]>> acct : balances.entrySet()) {
            for (Map.Entry<String, BigDecimal[]> byCurrency : acct.getValue().entrySet()) {
                BigDecimal foreign = byCurrency.getValue()[0];
                BigDecimal base = byCurrency.getValue()[1];
                if (foreign.signum() == 0 && base.signum() == 0) {
                    continue;
                }
                String ccy = byCurrency.getKey();
                BigDecimal rate = currency.getRate(ccy, baseCode, asOf);
                BigDecimal revaluedBase = foreign.multiply(rate).setScale(6, RoundingMode.HALF_UP);
                BigDecimal adjustment = revaluedBase.subtract(base);
                BigDecimal booked = booked(organizationId, sourceId(acct.getKey(), ccy, asOf), acct.getKey());
                out.add(new Line(acct.getKey(), codes.get(acct.getKey()), ccy, foreign, base, rate, revaluedBase,
                        adjustment, booked, adjustment.subtract(booked)));
            }
        }
        return out;
    }

    /** Net base already booked on {@code accountId} by revaluations of this (account, currency, date). */
    private BigDecimal booked(String org, String sourceId, String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = ? AND e.\"sourceId\" = ? AND l.\"accountId\" = ?")
                .params(org, SOURCE, sourceId, accountId).query(BigDecimal.class).single();
    }

    private static String sourceId(String accountId, String ccy, Instant asOf) {
        return accountId + ":" + ccy + ":" + day(asOf);
    }

    private static String day(Instant asOf) {
        return asOf.truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10);
    }

    private String periodId(String org, Instant asOf) {
        return jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"startDate\" <= ? AND"
                        + " \"endDate\" >= ? ORDER BY \"startDate\" DESC LIMIT 1")
                .params(org, FiscalPeriodControl.ts(asOf), FiscalPeriodControl.ts(asOf)).query(String.class).optional()
                .orElse("adhoc");
    }
}
