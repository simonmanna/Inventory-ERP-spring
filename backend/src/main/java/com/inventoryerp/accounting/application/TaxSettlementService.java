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
 * and locks it: {@link PostingService} then refuses tax postings dated inside a settled period. The latest settlement
 * can be undone ({@link #reverse}, finance audit A3): its entry is reversed, the row stays as the record of what was
 * filed, marked reversed, and the lock falls back to the settlement before it.
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
        // Output tax posts to each tax's own account when set, else tax_payable; input tax always to tax_receivable
        // (AccountDeterminationService.taxAccount). Measuring only tax_payable missed the taxes' own accounts (R-3).
        String receivable = determination.mapped("tax_receivable");
        BigDecimal output = movement(outputAccounts(receivable), from, to).negate();
        BigDecimal input = movement(List.of(receivable), from, to);
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
        if (last != null) {
            // no gaps: a skipped range would be locked (the lock covers everything up to the latest periodTo) but never
            // settled
            java.time.LocalDate next = last.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1);
            if (!from.atOffset(ZoneOffset.UTC).toLocalDate().equals(next)) {
                throw ApiException.badRequest("VAT is settled through " + FiscalPeriodControl.day(last)
                        + "; the next period must start on " + next);
            }
        }
        Map<String, Object> p = preview(from, to);
        BigDecimal input = new BigDecimal((String) p.get("inputTax"));
        BigDecimal output = new BigDecimal((String) p.get("outputTax"));
        String entryId = null;
        if (input.signum() > 0) {
            String payable = determination.mapped("tax_payable");
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN",
                    AccountingDay.fromWallClock(to, AccountingDay.zone(jdbc)), "VAT settlement "
                    + FiscalPeriodControl.day(from) + " – " + FiscalPeriodControl.day(to), "tax_settlement",
                    FiscalPeriodControl.day(to), "tax_settlement:" + FiscalPeriodControl.day(from) + ":"
                    + FiscalPeriodControl.day(to), List.of(
                            PostingRequest.Line.debit(payable, input, "Input VAT offset"),
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

    /**
     * Undo the latest settlement in force (finance audit A3, P2-4): reverse its offset entry, mark the row reversed, and
     * so lift the VAT lock back to the settlement before it. The period can then be corrected and settled again.
     */
    @Transactional
    public Map<String, Object> reverse(String id) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|vat-settlement', 0))").param(org()).query()
                .listOfRows();
        record Row(Instant from, Instant to, String journalEntryId, boolean reversed) {}
        Row row = jdbc.sql("SELECT \"periodFrom\", \"periodTo\", \"journalEntryId\", \"reversedAt\" IS NOT NULL AS reversed"
                        + " FROM \"TaxSettlement\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org())
                .query((rs, n) -> new Row(utc(rs.getTimestamp(1)), utc(rs.getTimestamp(2)), rs.getString(3),
                        rs.getBoolean(4)))
                .optional().orElseThrow(() -> ApiException.notFound("VAT settlement not found"));
        if (row.reversed()) {
            throw ApiException.badRequest("This VAT settlement is already reversed");
        }
        Instant last = lastSettled();
        if (last != null && row.to().isBefore(last)) {
            throw ApiException.badRequest("Only the latest VAT settlement can be reversed: VAT is settled through "
                    + FiscalPeriodControl.day(last) + "; reverse that settlement first");
        }
        String reversalId = null;
        if (row.journalEntryId() != null) {
            String status = jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(row.journalEntryId(), org()).query(String.class).optional().orElse(null);
            if ("posted".equals(status)) {
                reversalId = (String) posting.reverse(row.journalEntryId(), null, "Reversal of VAT settlement "
                        + FiscalPeriodControl.day(row.from()) + " – " + FiscalPeriodControl.day(row.to())).get("id");
            } else if ("reversed".equals(status)) {
                // reversed earlier through the generic journal reversal (possible before finance audit A2): not twice
                reversalId = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"reversalOfId\" = ?"
                                + " ORDER BY \"createdAt\" LIMIT 1")
                        .params(org(), row.journalEntryId()).query(String.class).optional().orElse(null);
            }
        }
        jdbc.sql("UPDATE \"TaxSettlement\" SET \"reversedAt\" = now(), \"reversedBy\" = ?, \"reversalJournalEntryId\" = ?"
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), reversalId, id, org()).update();
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", "reversed");
        newValues.put("reversalJournalEntryId", reversalId);
        audit.recordInTx(AuditInput.of("TaxSettlement", id, "update", Map.of("status", "settled"), newValues));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"TaxSettlement\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    private static Instant utc(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /** Settled periods, newest first; reversed ones carry {@code reversedAt}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"TaxSettlement\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"periodTo\" DESC").param(org()));
    }

    /** {@code tax_payable} and every tax's own account, never the input-tax account. */
    private List<String> outputAccounts(String receivable) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.add(determination.mapped("tax_payable"));
        ids.addAll(jdbc.sql("SELECT DISTINCT \"accountId\" FROM \"Tax\" WHERE \"organizationId\" = ? AND \"accountId\" IS"
                + " NOT NULL").param(org()).query(String.class).list());
        ids.remove(receivable);
        return List.copyOf(ids);
    }

    /**
     * Net base (debit − credit) on {@code accountIds} posted in the range. Settlement entries and payments of tax to the
     * authority (treasury "Tax payment") are excluded: paying January's VAT in February is not February's tax (R-3).
     * {@code from} and {@code to} are org-local wall-clock day bounds (ADR-S070).
     */
    private BigDecimal movement(List<String> accountIds, Instant from, Instant to) {
        java.time.ZoneId zone = AccountingDay.zone(jdbc);
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = :org AND"
                        + " l.\"accountId\" IN (:accounts) AND e.status::text IN ('posted', 'reversed')"
                        + " AND e.\"postingDate\" >= :from AND e.\"postingDate\" <= :to"
                        + " AND coalesce(e.\"sourceType\", '') <> 'tax_settlement'"
                        + " AND coalesce(e.dimensions ->> 'treasuryOperation', '') <> 'tax_payment'"
                        // and the reversal of either: an undone settlement's reversal is no VAT of the period (A3)
                        + " AND NOT EXISTS (SELECT 1 FROM \"JournalEntry\" o WHERE o.id = e.\"reversalOfId\""
                        + " AND (coalesce(o.dimensions ->> 'treasuryOperation', '') = 'tax_payment'"
                        + " OR coalesce(o.\"sourceType\", '') = 'tax_settlement'))")
                .param("org", org()).param("accounts", accountIds)
                .param("from", FiscalPeriodControl.ts(AccountingDay.fromWallClock(from, zone)))
                .param("to", FiscalPeriodControl.ts(AccountingDay.fromWallClock(to, zone)))
                .query(BigDecimal.class).single();
    }

    /** End of the latest settlement in force (reversed ones no longer lock anything). */
    private Instant lastSettled() {
        return jdbc.sql("SELECT max(\"periodTo\") FROM \"TaxSettlement\" WHERE \"organizationId\" = ?"
                        + " AND \"reversedAt\" IS NULL").param(org())
                .query((rs, n) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC))
                .optional().orElse(null);
    }
}
