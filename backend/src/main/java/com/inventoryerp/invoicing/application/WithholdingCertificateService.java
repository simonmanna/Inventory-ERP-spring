package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * Withholding tax certificates (ADR-S071). {@code issued}: we withheld tax from a supplier (vendor bill posting, or a
 * supplier payment that withheld) and owe it to the authority on {@code withholding_payable}. {@code received}: a
 * customer withheld tax from our receipt; the certificate supports the {@code withholding_receivable} claim. The
 * period report groups certificates by partner and tax for filing and ties them to the ledger.
 */
@Service
public class WithholdingCertificateService {

    /** Mapping key and default account code of the withholding tax receivable. */
    public static final String RECEIVABLE_KEY = "withholding_receivable";

    public static final String RECEIVABLE_CODE = "1455";

    /** A certificate to record ({@code number} null = next in sequence). */
    public record NewCertificate(String direction, String partnerId, String documentId, String paymentId, String taxId,
            Instant date, BigDecimal baseAmount, BigDecimal rate, BigDecimal amount, String currencyCode,
            String journalEntryId, String number, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final AuditService audit;
    private final AccountDeterminationService determination;

    public WithholdingCertificateService(JdbcClient jdbc, SequenceService sequence, AuditService audit,
            AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.sequence = sequence;
        this.audit = audit;
        this.determination = determination;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Record a certificate inside the caller's transaction; returns its id. */
    @Transactional
    public String issue(NewCertificate c, int year) {
        String number = c.number();
        if (number == null) {
            number = "received".equals(c.direction()) ? sequence.next("wht-cert-received:" + year, "WHTR-" + year + "-", 5)
                    : sequence.next("wht-cert:" + year, "WHT-" + year + "-", 5);
        } else if (jdbc.sql("SELECT count(*) FROM \"WithholdingCertificate\" WHERE \"organizationId\" = ? AND direction = ?"
                + " AND number = ?").params(org(), c.direction(), number).query(Long.class).single() > 0) {
            throw ApiException.badRequest("Withholding certificate " + number + " is already recorded");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"WithholdingCertificate\" (id, \"organizationId\", number, direction, \"partnerId\","
                        + " \"documentId\", \"paymentId\", \"taxId\", \"certificateDate\", \"baseAmount\", rate, amount,"
                        + " \"currencyCode\", \"journalEntryId\", notes, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, c.direction(), c.partnerId(), c.documentId(), c.paymentId(), c.taxId(),
                        SqlRows.ts(c.date()), Money.round(c.baseAmount(), 6), c.rate(), c.amount(), c.currencyCode(),
                        c.journalEntryId(), c.notes(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("WithholdingCertificate", id, "create", null, Obj.of("number", number,
                "direction", c.direction(), "amount", c.amount().toPlainString())));
        return id;
    }

    /** Certificates, newest first, filtered by direction, partner, status and date range (inclusive days). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String direction, String partnerId, String status, String from,
            String to, int pageIn, int pageSizeIn) {
        int page = Math.max(1, pageIn);
        int pageSize = Math.min(200, Math.max(1, pageSizeIn));
        List<Object> params = new ArrayList<>();
        String where = where(direction, partnerId, status, from, to, params);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT c.*, p.name AS \"partnerName\", p.\"taxNumber\""
                + " AS \"partnerTaxNumber\", t.name AS \"taxName\", d.\"documentNumber\", y.\"paymentNumber\" FROM"
                + " \"WithholdingCertificate\" c JOIN \"Partner\" p ON p.id = c.\"partnerId\" LEFT JOIN \"Tax\" t ON"
                + " t.id = c.\"taxId\" LEFT JOIN \"Document\" d ON d.id = c.\"documentId\" LEFT JOIN \"Payment\" y ON"
                + " y.id = c.\"paymentId\" WHERE " + where + " ORDER BY c.\"certificateDate\" DESC, c.number DESC LIMIT "
                + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"WithholdingCertificate\" c WHERE " + where).params(params)
                .query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** One certificate (tenant scoped). */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT c.*, p.name AS \"partnerName\", p.\"taxNumber\" AS"
                + " \"partnerTaxNumber\", t.name AS \"taxName\", d.\"documentNumber\", y.\"paymentNumber\" FROM"
                + " \"WithholdingCertificate\" c JOIN \"Partner\" p ON p.id = c.\"partnerId\" LEFT JOIN \"Tax\" t ON"
                + " t.id = c.\"taxId\" LEFT JOIN \"Document\" d ON d.id = c.\"documentId\" LEFT JOIN \"Payment\" y ON"
                + " y.id = c.\"paymentId\" WHERE c.id = ? AND c.\"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Withholding certificate not found");
        }
        return row;
    }

    /** Correct a certificate's notes, or the number of a received one (the customer's own certificate number). */
    @Transactional
    public Map<String, Object> update(String id, String number, String notes) {
        Map<String, Object> before = findOne(id);
        if (number != null && !number.isBlank() && !number.trim().equals(before.get("number"))) {
            if (!"received".equals(before.get("direction"))) {
                throw ApiException.badRequest("Issued certificate numbers come from the sequence and cannot be changed");
            }
            if (jdbc.sql("SELECT count(*) FROM \"WithholdingCertificate\" WHERE \"organizationId\" = ? AND direction ="
                    + " 'received' AND number = ? AND id <> ?").params(org(), number.trim(), id).query(Long.class)
                    .single() > 0) {
                throw ApiException.badRequest("Withholding certificate " + number.trim() + " is already recorded");
            }
            jdbc.sql("UPDATE \"WithholdingCertificate\" SET number = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(number.trim(), id, org()).update();
        }
        if (notes != null) {
            jdbc.sql("UPDATE \"WithholdingCertificate\" SET notes = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(notes, id, org()).update();
        }
        Map<String, Object> after = findOne(id);
        audit.recordInTx(AuditInput.of("WithholdingCertificate", id, "update", Obj.of("number", before.get("number"),
                "notes", before.get("notes")), Obj.of("number", after.get("number"), "notes", after.get("notes"))));
        return after;
    }

    /**
     * Filing report for a period: issued (or received) certificates by partner and tax with base, amount and count,
     * the totals, and the movement on the withholding account in the ledger for the same period.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String direction, String from, String to) {
        String dir = direction == null || direction.isBlank() ? "issued" : direction;
        if (!"issued".equals(dir) && !"received".equals(dir)) {
            throw ApiException.badRequest("direction must be issued or received");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw ApiException.badRequest("from and to are required");
        }
        List<Object> params = new ArrayList<>();
        String where = where(dir, null, "issued", from, to, params);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        long count = 0;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT c.\"partnerId\", p.name AS \"partnerName\","
                + " p.\"taxNumber\" AS \"partnerTaxNumber\", c.\"taxId\", t.name AS \"taxName\", c.rate,"
                + " sum(c.\"baseAmount\") AS \"baseAmount\", sum(c.amount) AS amount, count(*) AS certificates FROM"
                + " \"WithholdingCertificate\" c JOIN \"Partner\" p ON p.id = c.\"partnerId\" LEFT JOIN \"Tax\" t ON"
                + " t.id = c.\"taxId\" WHERE " + where + " GROUP BY c.\"partnerId\", p.name, p.\"taxNumber\","
                + " c.\"taxId\", t.name, c.rate ORDER BY p.name, t.name").params(params))) {
            base = base.add(SqlRows.dec(r, "baseAmount"));
            amount = amount.add(SqlRows.dec(r, "amount"));
            count += ((Number) r.get("certificates")).longValue();
            r.put("baseAmount", plain(SqlRows.dec(r, "baseAmount")));
            r.put("amount", plain(SqlRows.dec(r, "amount")));
            rows.add(r);
        }
        String key = "issued".equals(dir) ? "withholding_payable" : RECEIVABLE_KEY;
        BigDecimal gl = BigDecimal.ZERO;
        String accountId = null;
        try {
            accountId = determination.mapped(key);
        } catch (RuntimeException ignored) {
            // not mapped yet (no withholding recorded): the ledger side is zero
        }
        if (accountId != null) {
            BigDecimal net = jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l"
                            + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                            + " l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ?"
                            + " AND e.\"postingDate\" < ? AND coalesce(e.\"sourceType\", '') <> 'withholding_remittance'")
                    .params(org(), accountId, start(from), endExclusive(to)).query(BigDecimal.class).single();
            gl = "issued".equals(dir) ? net : net.negate();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("direction", dir);
        out.put("from", from);
        out.put("to", to);
        out.put("lines", rows);
        out.put("baseTotal", plain(base));
        out.put("amountTotal", plain(amount));
        out.put("certificates", count);
        out.put("glAccountId", accountId);
        out.put("glMovement", plain(gl));
        out.put("glDifference", plain(amount.subtract(gl)));
        return out;
    }

    private static String plain(BigDecimal v) {
        return com.inventoryerp.accounting.application.PostingService.plain(v);
    }

    private static String where(String direction, String partnerId, String status, String from, String to,
            List<Object> params) {
        StringBuilder w = new StringBuilder("c.\"organizationId\" = ?");
        params.add(org());
        if (direction != null && !direction.isBlank()) {
            w.append(" AND c.direction = ?");
            params.add(direction);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            w.append(" AND c.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (status != null && !status.isBlank()) {
            w.append(" AND c.status = ?");
            params.add(status);
        }
        if (from != null && !from.isBlank()) {
            w.append(" AND c.\"certificateDate\" >= ?");
            params.add(start(from));
        }
        if (to != null && !to.isBlank()) {
            w.append(" AND c.\"certificateDate\" < ?");
            params.add(endExclusive(to));
        }
        return w.toString();
    }

    private static Timestamp start(String day) {
        return SqlRows.ts(parse(day).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static Timestamp endExclusive(String day) {
        return SqlRows.ts(parse(day).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static LocalDate parse(String day) {
        try {
            return LocalDate.parse(day.substring(0, Math.min(10, day.length())));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Dates must be ISO dates (YYYY-MM-DD)");
        }
    }
}
