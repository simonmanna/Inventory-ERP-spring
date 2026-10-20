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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FX revaluation at period close (IAS 21; port of {@code revaluation.service.ts}). Monetary balances in foreign
 * currencies are revalued to the closing rate; the unrealized gain/loss posts against the {@code fx_gain} /
 * {@code fx_loss} mappings. "Monetary" = the account category's {@code allowReconciliation} flag. Exposed as module
 * API only — Nest registers no route for it.
 */
@Service
public class RevaluationService {

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

    /**
     * Revalue every monetary foreign-currency balance as of {@code asOf}: one {@code FxRevaluation} row (upserted
     * under {@code fiscalPeriodId = 'adhoc'}) and one balanced entry per non-zero adjustment.
     */
    @Transactional
    public Result run(Instant asOf) {
        String organizationId = TenantContext.organizationId();
        String baseCode = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Organization not found"));

        // Per (account, currency): foreign balance (debit/credit) and base carrying value (baseDebit/baseCredit).
        Map<String, Map<String, BigDecimal[]>> balances = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", l.\"currencyId\", l.debit, l.credit,"
                        + " l.\"baseDebit\", l.\"baseCredit\" FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                        + " JOIN \"Account\" a ON a.id = l.\"accountId\""
                        + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE l.\"organizationId\" = ? AND e.status = 'posted' AND e.\"postingDate\" <= ?"
                        + " AND a.\"isActive\" = true AND c.\"allowReconciliation\" = true")
                .params(organizationId, FiscalPeriodControl.ts(asOf)))) {
            String currencyCode = (String) l.get("currencyId");
            if (currencyCode == null || currencyCode.isEmpty() || currencyCode.equals(baseCode)) {
                continue;
            }
            BigDecimal[] cur = balances.computeIfAbsent((String) l.get("accountId"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(currencyCode, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            cur[0] = cur[0].add(SqlRows.dec(l, "debit")).subtract(SqlRows.dec(l, "credit"));
            cur[1] = cur[1].add(SqlRows.dec(l, "baseDebit")).subtract(SqlRows.dec(l, "baseCredit"));
        }

        // adjustment = foreign × closingRate − base; > 0 → Dr account / Cr FX gain, < 0 → Dr FX loss / Cr account.
        String fxGainAcct = determination.mapped("fx_gain");
        String fxLossAcct = determination.mapped("fx_loss");
        int revalued = 0;
        BigDecimal totalGain = BigDecimal.ZERO;
        for (Map.Entry<String, Map<String, BigDecimal[]>> acct : balances.entrySet()) {
            String accountId = acct.getKey();
            for (Map.Entry<String, BigDecimal[]> byCurrency : acct.getValue().entrySet()) {
                String currencyCode = byCurrency.getKey();
                BigDecimal foreign = byCurrency.getValue()[0];
                BigDecimal base = byCurrency.getValue()[1];
                if (foreign.signum() == 0 && base.signum() == 0) {
                    continue;
                }
                BigDecimal closingRate = currency.getRate(currencyCode, baseCode, asOf);
                BigDecimal revaluedBase = foreign.multiply(closingRate);
                BigDecimal adjustment = revaluedBase.subtract(base);
                jdbc.sql("INSERT INTO \"FxRevaluation\" (id, \"organizationId\", \"fiscalPeriodId\", \"asOf\", \"accountId\","
                                + " \"currencyCode\", \"bookBalance\", \"revaluedBalance\", \"fxGain\", rate)"
                                + " VALUES (?, ?, 'adhoc', ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT (\"organizationId\", \"fiscalPeriodId\", \"accountId\") DO UPDATE SET"
                                + " \"asOf\" = EXCLUDED.\"asOf\", \"currencyCode\" = EXCLUDED.\"currencyCode\","
                                + " \"bookBalance\" = EXCLUDED.\"bookBalance\", \"revaluedBalance\" = EXCLUDED.\"revaluedBalance\","
                                + " \"fxGain\" = EXCLUDED.\"fxGain\", rate = EXCLUDED.rate")
                        .params(UUID.randomUUID().toString(), organizationId, FiscalPeriodControl.ts(asOf), accountId,
                                currencyCode, base, revaluedBase, adjustment, closingRate)
                        .update();
                if (adjustment.signum() != 0) {
                    double amt = adjustment.abs().doubleValue();
                    List<PostingRequest.Line> lines = adjustment.signum() > 0
                            ? List.of(PostingRequest.Line.debit(accountId, amt, "FX reval " + currencyCode),
                                    PostingRequest.Line.credit(fxGainAcct, amt, "Unrealized FX gain"))
                            : List.of(PostingRequest.Line.debit(fxLossAcct, amt, "Unrealized FX loss"),
                                    PostingRequest.Line.credit(accountId, amt, "FX reval " + currencyCode));
                    posting.post(PostingRequest.of("GEN", asOf, "FX revaluation " + currencyCode + " @ " + plain(closingRate),
                            "fx_revaluation", accountId, null, lines));
                    totalGain = totalGain.add(adjustment);
                }
                revalued++;
            }
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
        audit.recordInTx(AuditInput.of("FxRevaluation", "adhoc", "update", null, newValues));
        return new Result(count, gain);
    }
}
