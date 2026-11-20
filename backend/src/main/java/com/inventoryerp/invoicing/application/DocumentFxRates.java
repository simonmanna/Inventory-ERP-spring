package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Carrying rates and realised FX for settling foreign-currency documents (PC-0, ADR-S054, owner decision D-PC2).
 *
 * <p>A document's carrying rate is the rate its own journal entry was posted at: the recorded rate snapshot, else
 * Σ base ÷ Σ transaction amount of the entry's lines (entries posted before the snapshot existed), else the document's
 * stated rate. Settling an amount at another rate realises the difference to the {@code fx_gain} / {@code fx_loss}
 * accounts — never to stock, landed cost, production cost or COGS.
 */
@Component
public class DocumentFxRates {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final com.inventoryerp.accounting.application.CurrencyService currency;

    public DocumentFxRates(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            com.inventoryerp.accounting.application.CurrencyService currency) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.currency = currency;
    }

    /**
     * FX-1: the rate a document posts at on {@code date}, resolved like {@code PostingService} does (stated rate, the
     * stored default 1 meaning "not stated", else the rate table; refused when there is none); 1 for base documents.
     * Documents created through the API store rate 1, so their stored rate is not the rate they post at.
     */
    public BigDecimal effectiveRate(Map<String, Object> doc, java.time.Instant date) {
        String cur = currencyOf(doc);
        if (cur.equals(base())) {
            return BigDecimal.ONE;
        }
        return currency.resolve(cur, base(), doc.get("exchangeRate"), date,
                "vendor_bill".equals(String.valueOf(doc.get("documentType"))) ? "purchase" : "sales").rate();
    }

    /** The organisation's base currency. */
    public String base() {
        return posting.baseCurrency();
    }

    /** Currency of a document (null = base). */
    public String currencyOf(Map<String, Object> doc) {
        Object c = doc.get("currencyId");
        return c == null || c.toString().isBlank() ? base() : c.toString().trim().toUpperCase();
    }

    /** Rate a journal entry was posted at, or null when it cannot be told. */
    public BigDecimal entryRate(String journalEntryId) {
        if (journalEntryId == null) {
            return null;
        }
        // the entry's own pair: an entry may also snapshot its foreign legs' rates (R-8, ADR-S076)
        BigDecimal snap = jdbc.sql("SELECT r.rate FROM \"JournalEntryFxRate\" r JOIN \"JournalEntry\" e ON e.id ="
                        + " r.\"journalEntryId\" WHERE r.\"journalEntryId\" = ? ORDER BY (upper(r.\"fromCode\") ="
                        + " upper(coalesce(e.\"currencyId\", ''))) DESC, r.\"createdAt\" LIMIT 1")
                .param(journalEntryId).query(BigDecimal.class).optional().orElse(null);
        if (snap != null) {
            return snap;
        }
        Map<String, Object> t = jdbc.sql("SELECT coalesce(sum(debit + credit), 0) AS txn, coalesce(sum(\"baseDebit\""
                + " + \"baseCredit\"), 0) AS base FROM \"JournalLine\" WHERE \"journalEntryId\" = ?").param(journalEntryId)
                .query().singleRow();
        BigDecimal txn = Money.dec(t.get("txn"));
        return txn.signum() == 0 ? null : Money.dec(t.get("base")).divide(txn, MC);
    }

    /** Carrying rate of a posted document. */
    public BigDecimal documentRate(Map<String, Object> doc) {
        BigDecimal r = entryRate((String) doc.get("journalEntryId"));
        if (r != null) {
            return r;
        }
        return doc.get("exchangeRate") == null ? BigDecimal.ONE : Money.dec(doc.get("exchangeRate"));
    }

    /**
     * Lines realising {@code fx} (base currency) on a partner's receivable: positive = the receivable was
     * over-credited at settlement (Dr receivable / Cr FX gain), negative = under-credited (Dr FX loss / Cr receivable).
     */
    public List<Line> realiseOnReceivable(BigDecimal fx, String receivableAccount, String partnerId, String description,
            String currency) {
        BigDecimal v = fx.abs();
        if (v.signum() == 0) {
            return List.of();
        }
        String amount = JsNumbers.dec(v);
        // FX-1: the receivable line keeps the document currency with a zero foreign amount, so the base adjustment
        // stays in that currency's bucket; untagged, period-end revaluation re-counted it.
        Line receivable = fx.signum() > 0
                ? new Line(receivableAccount, amount, null, partnerId, description, null, null, null)
                : new Line(receivableAccount, null, amount, partnerId, description, null, null, null);
        if (currency != null && !currency.equals(base())) {
            receivable = receivable.inCurrency(currency, "0");
        }
        if (fx.signum() > 0) {
            return List.of(receivable, Line.credit(determination.mapped("fx_gain"), amount, description));
        }
        return List.of(Line.debit(determination.mapped("fx_loss"), amount, description), receivable);
    }
}
