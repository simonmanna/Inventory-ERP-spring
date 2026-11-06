package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.StatementService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer statements for Sales (SG-10): the one AR statement of {@link StatementService}, plus filters, the merged records of
 * a customer, aging reconciled to the closing balance, what was sent to whom, confirmation and disputes.
 */
@Service
public class SalesStatementService {

    private final SalesDb db;
    private final StatementService statements;
    private final SalesCustomerService customers;
    private final SalesAccess access;
    private final AuditService audit;

    public SalesStatementService(SalesDb db, StatementService statements, SalesCustomerService customers, SalesAccess access,
            AuditService audit) {
        this.db = db;
        this.statements = statements;
        this.customers = customers;
        this.access = access;
        this.audit = audit;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Statement with optional {@code from}, {@code to}, {@code branchId}, {@code currencyCode}, {@code type}
     * (invoice, credit_note, payment, ...). A filtered statement shows a filtered balance, and says so.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> statement(String partnerId, Map<String, String> q) {
        Map<String, Object> base = statements.statement("ar", partnerId, q.get("from"), q.get("to"));
        String branch = q.get("branchId");
        var scope = access.scope();
        boolean filtered = !blank(branch) || !blank(q.get("currencyCode")) || !blank(q.get("type")) || scope.restricted();
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("filtered", filtered);
        List<String> set = customers.mergedSet(partnerId);
        out.put("mergedRecords", set.size() - 1);
        if (filtered) {
            List<Object> params = new ArrayList<>(List.of(org(), set.toArray(String[]::new)));
            StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"partnerId\" = ANY(?::text[]) AND e.status::text IN ('posted',"
                    + " 'reversed') AND a.\"controlAccountType\"::text = 'ar'");
            if (!blank(branch)) {
                where.append(" AND l.\"branchId\" = ?");
                params.add(branch);
            }
            if (scope.restricted()) {
                where.append(" AND l.\"branchId\" = ANY(?::text[])");
                params.add(scope.branchArray());
            }
            if (!blank(q.get("currencyCode"))) {
                where.append(" AND c.code = ?");
                params.add(q.get("currencyCode"));
            }
            if (!blank(q.get("type"))) {
                where.append(" AND lower(e.\"sourceType\") LIKE ?");
                params.add("%" + q.get("type").toLowerCase(java.util.Locale.ROOT) + "%");
            }
            if (!blank(q.get("from"))) {
                where.append(" AND e.\"postingDate\" >= ?::timestamp");
                params.add(q.get("from"));
            }
            if (!blank(q.get("to"))) {
                where.append(" AND e.\"postingDate\" < ?::timestamp + interval '1 day'");
                params.add(q.get("to"));
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            BigDecimal running = BigDecimal.ZERO;
            for (Map<String, Object> r : db.rows("SELECT e.\"postingDate\" AS date, e.\"entryNumber\", e.\"sourceType\", l.description, l.\"baseDebit\" AS debit,"
                    + " l.\"baseCredit\" AS credit, l.\"branchId\", d.\"documentNumber\" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                    + " JOIN \"Account\" a ON a.id = l.\"accountId\" LEFT JOIN \"Currency\" c ON c.id = l.\"currencyId\" LEFT JOIN \"Document\" d ON d.id = e.\"sourceId\""
                    + where + " ORDER BY e.\"postingDate\", e.\"createdAt\", l.\"lineNumber\"", params)) {
                running = running.add(dec(r.get("debit"))).subtract(dec(r.get("credit")));
                r.put("reference", r.get("documentNumber") != null ? r.get("documentNumber") : r.get("entryNumber"));
                r.put("balance", money(running).toPlainString());
                lines.add(r);
            }
            out.put("lines", lines);
            out.put("closingBalance", money(running).toPlainString());
        }
        out.put("reconciliation", reconciliation(partnerId, set, filtered, dec(out.get("closingBalance"))));
        out.put("disputes", db.rows("SELECT * FROM \"SalesStatementDispute\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC", org(),
                partnerId));
        out.put("log", db.rows("SELECT * FROM \"SalesStatementLog\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC LIMIT 50", org(),
                partnerId));
        return out;
    }

    /** Open documents in base currency against the closing balance, with what explains a difference. */
    private Map<String, Object> reconciliation(String partnerId, List<String> set, boolean filtered, BigDecimal closing) {
        Map<String, Object> d = db.row("SELECT COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE \"documentType\"::text IN ('sales_invoice', 'debit_note')), 0) AS"
                + " invoices, COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE \"documentType\"::text = 'credit_note'), 0) AS credits FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ANY(?::text[]) AND status::text IN ('posted', 'paid') AND \"amountResidual\" > 0", org(), set.toArray(String[]::new));
        Map<String, Object> pos = db.row("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"partnerId\" = ANY(?::text[]) AND"
                + " status::text IN ('posted', 'paid') AND \"amountResidual\" > 0", org(), set.toArray(String[]::new));
        BigDecimal open = dec(d.get("invoices")).add(dec(pos.get("open")));
        BigDecimal credits = dec(d.get("credits"));
        BigDecimal documents = open.subtract(credits);
        BigDecimal difference = filtered ? BigDecimal.ZERO : closing.subtract(documents);
        return Obj.of("openInvoices", JsNumbers.js(money(open)), "openCredits", JsNumbers.js(money(credits)), "documentsTotal",
                JsNumbers.js(money(documents)), "closingBalance", JsNumbers.js(money(closing)), "difference", JsNumbers.js(money(difference)),
                "reconciled", filtered ? null : difference.abs().compareTo(new BigDecimal("0.05")) < 0, "note", filtered
                        ? "A filtered statement is not compared with open documents" : difference.abs().compareTo(new BigDecimal("0.05")) < 0 ? null
                                : "The difference is receivable posted without an open document, such as an opening balance, a deposit or a manual journal");
    }

    /** Email a statement and log it, delivered or not. */
    @Transactional
    public Map<String, Object> send(String partnerId, Map<String, Object> in) {
        return sendOne(partnerId, str(in.get("from")), str(in.get("to")), str(in.get("recipient")));
    }

    private Map<String, Object> sendOne(String partnerId, String from, String to, String recipient) {
        BigDecimal closing = dec(statements.statement("ar", partnerId, from, to).get("closingBalance"));
        String sentTo = recipient;
        boolean delivered = false;
        String error = null;
        // Checked here because a refusal inside the email call would roll back the whole batch.
        Map<String, Object> p = db.row("SELECT email FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?", partnerId, org());
        if (blank(recipient) && (p == null || blank(str(p.get("email"))))) {
            error = "Customer has no email on file";
        } else {
            Map<String, Object> r = statements.email("ar", partnerId, from, to, recipient, null);
            sentTo = str(r.get("sentTo"));
            delivered = Boolean.TRUE.equals(r.get("delivered"));
        }
        return db.insert("SalesStatementLog", Obj.of("partnerId", partnerId, "periodFrom", SalesDb.date(from), "periodTo", SalesDb.date(to), "closingBalance", closing,
                "sentTo", sentTo, "delivered", delivered, "error", error, "createdById", user()));
    }

    /** Statements to many customers: {@code partnerIds}, or everyone with an open balance. Never stops at one failure. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendBulk(Map<String, Object> in) {
        access.assertAllBranches();
        List<String> ids = in.get("partnerIds") instanceof List<?> l && !l.isEmpty() ? (List<String>) l : outstanding(Map.of()).stream()
                .map(r -> str(r.get("partnerId"))).toList();
        int sent = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> log = sendOne(id, str(in.get("from")), str(in.get("to")), null);
            if (log.get("error") != null) {
                failed++;
            } else {
                sent++;
            }
            results.add(log);
        }
        return Obj.of("total", ids.size(), "sent", sent, "failed", failed, "results", results);
    }

    /** Customers with an open balance, in base currency, oldest debt first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outstanding(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String scope = access.branchClause("d.\"branchId\"", params);
        if (!blank(q.get("branchId"))) {
            scope += " AND d.\"branchId\" = ?";
            params.add(q.get("branchId"));
        }
        return db.rows("SELECT d.\"partnerId\", p.name AS \"partnerName\", p.email, count(*) AS documents, COALESCE(sum(d.\"amountResidual\" * d.\"exchangeRate\"), 0) AS outstanding,"
                + " COALESCE(sum(d.\"amountResidual\" * d.\"exchangeRate\") FILTER (WHERE d.\"dueDate\" < now()), 0) AS overdue, min(d.\"dueDate\") AS \"oldestDue\", (SELECT max(l.\"createdAt\")"
                + " FROM \"SalesStatementLog\" l WHERE l.\"partnerId\" = d.\"partnerId\") AS \"lastSentAt\" FROM \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\"::text = 'sales_invoice' AND d.status::text = 'posted' AND d.\"amountResidual\" > 0" + scope
                + " GROUP BY d.\"partnerId\", p.name, p.email ORDER BY overdue DESC, outstanding DESC LIMIT 1000", params);
    }

    /** The customer agreed the balance: who said so, and when. */
    @Transactional
    public Map<String, Object> confirm(String logId, Map<String, Object> in) {
        String by = str(in.get("confirmedBy"));
        if (blank(by)) {
            throw ApiException.badRequest("confirmedBy should not be empty");
        }
        if (db.update("UPDATE \"SalesStatementLog\" SET \"confirmedAt\" = " + NOW + ", \"confirmedBy\" = ?, \"confirmNote\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?", by, str(in.get("note")), logId, org()) == 0) {
            throw ApiException.notFound("Statement not found");
        }
        return db.row("SELECT * FROM \"SalesStatementLog\" WHERE id = ?", logId);
    }

    @Transactional
    public Map<String, Object> dispute(String partnerId, Map<String, Object> in) {
        if (!db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> row = db.insert("SalesStatementDispute", Obj.of("partnerId", partnerId, "documentNumber", str(in.get("documentNumber")), "amount",
                dec(in.get("amount")), "reason", reason, "createdById", user()));
        audit.record(AuditInput.of("SalesStatementDispute", str(row.get("id")), "create", null, Obj.of("reason", reason)));
        return row;
    }

    @Transactional
    public Map<String, Object> resolve(String id, Map<String, Object> in) {
        String status = str(in.get("status"));
        if (!java.util.Set.of("resolved", "rejected").contains(String.valueOf(status))) {
            throw ApiException.badRequest("status must be one of: rejected, resolved");
        }
        String resolution = str(in.get("resolution"));
        if (blank(resolution)) {
            throw ApiException.badRequest("resolution should not be empty");
        }
        if (db.update("UPDATE \"SalesStatementDispute\" SET status = ?, resolution = ?, \"resolvedById\" = ?, \"resolvedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND status = 'open'", status, resolution, user(), id, org()) == 0) {
            throw ApiException.notFound("Open dispute not found");
        }
        return db.row("SELECT * FROM \"SalesStatementDispute\" WHERE id = ?", id);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(String partnerId, String from, String to) {
        return statements.pdf("ar", partnerId, from, to);
    }
}
