package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VAT settlement (ACC-7). For a period, output VAT (credit balance movement on {@code tax_payable}) and input VAT
 * (debit balance movement on {@code tax_receivable}) are taken from the ledger; settling offsets the input against the
 * output (Dr tax payable / Cr tax receivable), leaving the net due (or refundable) on tax payable, records the period
 * and locks it: {@link PostingService} then refuses tax postings dated inside a settled period.
 */
@Service
public class TaxSettlementService {

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AuditService audit;

    public TaxSettlementService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Output / input VAT and the net for {@code from … to} (inclusive days), without posting. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("from and to are required and to must not be before from");
        }
        String payable = determination.mapped("tax_payable");
        String receivable = determination.mapped("tax_receivable");
        BigDecimal output = movement(payable, from, to).negate();
        BigDecimal input = movement(receivable, from, to);
        Instant last = lastSettled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", JsonConfig.toJsIso(from));
        out.put("to", JsonConfig.toJsIso(to));
        out.put("outputTax", plain(output));
        out.put("inputTax", plain(input));
        out.put("netPayable", plain(output.subtract(input)));
        out.put("settledThrough", last == null ? null : JsonConfig.toJsIso(last));
        return out;
    }

    /** Settle and lock the period: periods are settled in order, without overlap. */
    @Transactional
    public Map<String, Object> settle(Instant from, Instant to) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|vat-settlement', 0))").param(org()).query()
                .listOfRows();
        Instant last = lastSettled();
        if (last != null && !from.isAfter(last)) {
            throw ApiException.badRequest("VAT is already settled through " + FiscalPeriodControl.day(last)
                    + "; the next period starts after it");
        }
        Map<String, Object> p = preview(from, to);
        BigDecimal input = new BigDecimal((String) p.get("inputTax"));
        BigDecimal output = new BigDecimal((String) p.get("outputTax"));
        String entryId = null;
        if (input.signum() > 0) {
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", to, "VAT settlement "
                    + FiscalPeriodControl.day(from) + " – " + FiscalPeriodControl.day(to), "tax_settlement",
                    FiscalPeriodControl.day(to), "tax_settlement:" + FiscalPeriodControl.day(from) + ":"
                    + FiscalPeriodControl.day(to), List.of(
                            PostingRequest.Line.debit(determination.mapped("tax_payable"), input, "Input VAT offset"),
                            PostingRequest.Line.credit(determination.mapped("tax_receivable"), input,
                                    "Input VAT offset"))));
            entryId = (String) entry.get("id");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"TaxSettlement\" (id, \"organizationId\", \"periodFrom\", \"periodTo\", \"outputTax\","
                        + " \"inputTax\", \"netPayable\", \"journalEntryId\", \"settledBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), FiscalPeriodControl.ts(from), FiscalPeriodControl.ts(to), output, input,
                        output.subtract(input), entryId, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("TaxSettlement", id, "create", null, p));
        Map<String, Object> out = new LinkedHashMap<>(p);
        out.put("id", id);
        out.put("journalEntryId", entryId);
        out.put("settledThrough", JsonConfig.toJsIso(to));
        return out;
    }

    /** Settled periods, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"TaxSettlement\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"periodTo\" DESC").param(org()));
    }

    /** Net base (debit − credit) on {@code accountId} posted in the range, settlement entries excluded. */
    private BigDecimal movement(String accountId, Instant from, Instant to) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ?"
                        + " AND e.\"postingDate\" <= ? AND coalesce(e.\"sourceType\", '') <> 'tax_settlement'")
                .params(org(), accountId, FiscalPeriodControl.ts(from), FiscalPeriodControl.ts(to))
                .query(BigDecimal.class).single();
    }

    private Instant lastSettled() {
        return jdbc.sql("SELECT max(\"periodTo\") FROM \"TaxSettlement\" WHERE \"organizationId\" = ?").param(org())
                .query((rs, n) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC))
                .optional().orElse(null);
    }
}
