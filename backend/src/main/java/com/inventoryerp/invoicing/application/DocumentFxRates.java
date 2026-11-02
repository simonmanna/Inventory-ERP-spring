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

    public DocumentFxRates(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
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
        BigDecimal snap = jdbc.sql("SELECT rate FROM \"JournalEntryFxRate\" WHERE \"journalEntryId\" = ?")
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
    public List<Line> realiseOnReceivable(BigDecimal fx, String receivableAccount, String partnerId, String description) {
        BigDecimal v = fx.abs();
        if (v.signum() == 0) {
            return List.of();
        }
        String amount = JsNumbers.dec(v);
        if (fx.signum() > 0) {
            return List.of(new Line(receivableAccount, amount, null, partnerId, description, null, null, null),
                    Line.credit(determination.mapped("fx_gain"), amount, description));
        }
        return List.of(Line.debit(determination.mapped("fx_loss"), amount, description),
                new Line(receivableAccount, null, amount, partnerId, description, null, null, null));
    }
}
