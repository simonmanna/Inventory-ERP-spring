package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.invoicing.application.StatementService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collections (ADR-S011 phase S8; roadmap 1.6): dunning levels and runs, promises to pay, the collections worklist and
 * statement runs.
 *
 * <p>A dunning run takes, per customer, the oldest overdue posted invoice, picks the highest active level whose
 * {@code daysOverdue} it reaches, and — unless an open promise to pay covers today, or that level was already sent
 * since the customer last reached it — records an entry, e-mails the reminder with the statement PDF (or marks a letter
 * to print), raises a draft late-fee invoice when the level has a fee, and puts the customer on credit hold when the
 * level says so.
 */
@Service
public class SalesCollectionsService {

    private final SalesDb db;
    private final StatementService statements;
    private final NotificationsService notifications;
    private final InvoiceService invoices;
    private final AccountResolverService accounts;
    private final CreditService credit;
    private final AuditService audit;

    public SalesCollectionsService(SalesDb db, StatementService statements, NotificationsService notifications,
            InvoiceService invoices, AccountResolverService accounts, CreditService credit, AuditService audit) {
        this.db = db;
        this.statements = statements;
        this.notifications = notifications;
        this.invoices = invoices;
        this.accounts = accounts;
        this.credit = credit;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ levels

    @Transactional(readOnly = true)
    public List<Map<String, Object>> levels() {
        return db.rows("SELECT * FROM \"DunningLevel\" WHERE \"organizationId\" = ? ORDER BY sequence", org());
    }

    @Transactional
    public Map<String, Object> saveLevel(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("sequence")) {
            int seq = in.get("sequence") instanceof Number n ? n.intValue() : 0;
            if (seq <= 0) {
                throw ApiException.badRequest("sequence must be positive");
            }
            d.put("sequence", seq);
        }
        if (id == null || in.containsKey("daysOverdue")) {
            int days = in.get("daysOverdue") instanceof Number n ? n.intValue() : -1;
            if (days < 0) {
                throw ApiException.badRequest("daysOverdue must not be less than 0");
            }
            d.put("daysOverdue", days);
        }
        for (String k : List.of("name", "subject", "body")) {
            if (id == null || in.containsKey(k)) {
                if (blank(str(in.get(k)))) {
                    throw ApiException.badRequest(k + " should not be empty");
                }
                d.put(k, str(in.get(k)));
            }
        }
        if (in.containsKey("channel")) {
            String c = str(in.get("channel"));
            if (!Set.of("email", "letter", "none").contains(c)) {
                throw ApiException.badRequest("channel must be one of: email, letter, none");
            }
            d.put("channel", c);
        }
        if (in.containsKey("feeAmount")) {
            BigDecimal f = dec(in.get("feeAmount"));
            if (f.signum() < 0) {
                throw ApiException.badRequest("feeAmount must not be less than 0");
            }
            d.put("feeAmount", f);
        }
        for (String k : List.of("setCreditHold", "isActive")) {
            if (in.containsKey(k)) {
                d.put(k, Boolean.TRUE.equals(in.get(k)));
            }
        }
        Map<String, Object> row;
        try {
            if (id == null) {
                row = db.insert("DunningLevel", d);
            } else {
                db.require("DunningLevel", id, "Dunning level not found");
                row = db.patch("DunningLevel", id, d, true);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("A dunning level with this sequence already exists");
        }
        audit.record(AuditInput.of("DunningLevel", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    // ------------------------------------------------------------------ run

    /** Run dunning as of {@code asOf} (default today). */
    @Transactional
    public Map<String, Object> run(LocalDate asOfIn, String trigger) {
        LocalDate asOf = asOfIn == null ? LocalDate.now() : asOfIn;
        List<Map<String, Object>> levels = db.rows("SELECT * FROM \"DunningLevel\" WHERE \"organizationId\" = ? AND"
                + " \"isActive\" ORDER BY \"daysOverdue\" DESC, sequence DESC", org());
        if (levels.isEmpty()) {
            throw ApiException.badRequest("Configure at least one active dunning level first");
        }
        expirePromises(asOf);
        String runId = SalesDb.newId();
        db.update("INSERT INTO \"DunningRun\" (id, \"organizationId\", \"runDate\", trigger, \"createdById\") VALUES (?, ?, ?,"
                + " ?, ?)", runId, org(), asOf, trigger, user());
        int entries = 0;
        int skipped = 0;
        for (Map<String, Object> c : overdueCustomers(asOf)) {
            String partnerId = str(c.get("partnerId"));
            int days = ((Number) c.get("maxDays")).intValue();
            Map<String, Object> level = levels.stream().filter(l -> ((Number) l.get("daysOverdue")).intValue() <= days)
                    .findFirst().orElse(null);
            if (level == null) {
                continue;
            }
            if (db.count("SELECT count(*) FROM \"PromiseToPay\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                    + " status = 'open' AND \"promisedDate\" >= ?", List.of(org(), partnerId, asOf)) > 0) {
                skipped++;
                continue;
            }
            // only reminders sent since the oldest invoice now overdue fell due count (a settled customer restarts at 1)
            Map<String, Object> last = db.row("SELECT \"levelSequence\", \"createdAt\" FROM \"DunningEntry\" WHERE"
                    + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"createdAt\" >= ? ORDER BY \"createdAt\" DESC"
                    + " LIMIT 1", org(), partnerId, c.get("oldestDue"));
            int seq = ((Number) level.get("sequence")).intValue();
            if (last != null && ((Number) last.get("levelSequence")).intValue() >= seq) {
                skipped++;
                continue;
            }
            entries++;
            entry(runId, c, level, asOf);
        }
        db.update("UPDATE \"DunningRun\" SET entries = ?, skipped = ? WHERE id = ?", entries, skipped, runId);
        audit.record(AuditInput.of("DunningRun", runId, "create", null, Obj.of("entries", entries, "skipped", skipped)));
        return run(runId);
    }

    private List<Map<String, Object>> overdueCustomers(LocalDate asOf) {
        return db.rows("""
                SELECT d."partnerId", p.name, p.email, COALESCE(sum(d."amountResidual"), 0) AS overdue,
                       max((?::date - d."dueDate"::date)) AS "maxDays",
                       min(d."dueDate") AS "oldestDue",
                       jsonb_agg(jsonb_build_object('id', d.id, 'number', d."documentNumber", 'dueDate', d."dueDate"::date,
                           'residual', d."amountResidual") ORDER BY d."dueDate") AS invoices
                  FROM "Document" d JOIN "Partner" p ON p.id = d."partnerId"
                 WHERE d."organizationId" = ? AND d."documentType"::text IN ('sales_invoice', 'debit_note')
                   AND d.status::text = 'posted' AND d."amountResidual" > 0 AND d."dueDate"::date < ?::date
                 GROUP BY d."partnerId", p.name, p.email""", asOf, org(), asOf);
    }

    private void entry(String runId, Map<String, Object> c, Map<String, Object> level, LocalDate asOf) {
        String partnerId = str(c.get("partnerId"));
        String channel = str(level.get("channel"));
        String delivery = "none";
        String recipient = null;
        String error = null;
        String body = render(str(level.get("body")), c);
        String subject = render(str(level.get("subject")), c);
        if ("email".equals(channel)) {
            recipient = str(c.get("email"));
            if (blank(recipient)) {
                delivery = "letter";
                error = "No e-mail on file — print the letter";
            } else {
                try {
                    boolean sent = notifications.sendExternalEmail(recipient, subject, body,
                            new NotificationsService.Attachment("statement.pdf", "application/pdf", statements.pdf("ar",
                                    partnerId, null, asOf.toString())));
                    delivery = sent ? "sent" : "logged";
                } catch (RuntimeException e) {
                    delivery = "failed";
                    error = e.getMessage();
                }
            }
        } else if ("letter".equals(channel)) {
            delivery = "letter";
        }
        String feeInvoiceId = null;
        BigDecimal fee = dec(level.get("feeAmount"));
        if (fee.signum() > 0) {
            String feeAccount = accounts.ensureByCode("4290", "Late Payment Fees", "other_income", "dunning_fee_income");
            Map<String, Object> inv = invoices.create(new InvoiceInput(partnerId, asOf.toString(), null, null, null, null,
                    null, null, null, null, null, null, "Dunning " + level.get("name"), null, null, null,
                    "Late payment fee — " + level.get("name"), List.of(LineInput.of(null, feeAccount, "Late payment fee ("
                            + level.get("name") + ")", BigDecimal.ONE, fee, null, null, null))));
            feeInvoiceId = str(inv.get("id"));
        }
        boolean hold = Boolean.TRUE.equals(level.get("setCreditHold"));
        if (hold) {
            credit.setHold(partnerId, true, "Dunning level " + level.get("sequence") + " (" + level.get("name") + ")");
        }
        db.update("INSERT INTO \"DunningEntry\" (id, \"organizationId\", \"runId\", \"partnerId\", \"levelId\","
                + " \"levelSequence\", \"overdueAmount\", \"maxDaysOverdue\", invoices, channel, delivery, recipient,"
                + " \"feeInvoiceId\", \"creditHoldSet\", error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)",
                SalesDb.newId(), org(), runId, partnerId, level.get("id"), level.get("sequence"), dec(c.get("overdue")),
                ((Number) c.get("maxDays")).intValue(), str(c.get("invoices")), channel, delivery, recipient, feeInvoiceId,
                hold, error);
    }

    private static String render(String template, Map<String, Object> c) {
        return template.replace("{{customer}}", str(c.get("name"))).replace("{{amount}}", dec(c.get("overdue"))
                .stripTrailingZeros().toPlainString()).replace("{{days}}", String.valueOf(c.get("maxDays")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> run(String runId) {
        Map<String, Object> r = db.row("SELECT * FROM \"DunningRun\" WHERE id = ? AND \"organizationId\" = ?", runId, org());
        if (r == null) {
            throw ApiException.notFound("Dunning run not found");
        }
        r.put("entries", db.rows("SELECT e.*, p.name AS \"partnerName\", l.name AS \"levelName\" FROM \"DunningEntry\" e"
                + " JOIN \"Partner\" p ON p.id = e.\"partnerId\" JOIN \"DunningLevel\" l ON l.id = e.\"levelId\" WHERE"
                + " e.\"runId\" = ? ORDER BY e.\"overdueAmount\" DESC", runId));
        return r;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return db.rows("SELECT * FROM \"DunningRun\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT 100",
                org());
    }

    // ------------------------------------------------------------------ promises to pay

    @Transactional
    public Map<String, Object> promise(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        if (partnerId == null || !db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        BigDecimal amount = dec(in.get("amount"));
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        LocalDate date = SalesDb.date(str(in.get("promisedDate")));
        if (date == null || date.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("promisedDate must be today or later");
        }
        String id = SalesDb.newId();
        db.update("INSERT INTO \"PromiseToPay\" (id, \"organizationId\", \"partnerId\", amount, \"promisedDate\", notes,"
                + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?)", id, org(), partnerId, amount, date, str(in.get("notes")),
                user());
        audit.record(AuditInput.of("PromiseToPay", id, "create", null, in));
        return db.row("SELECT * FROM \"PromiseToPay\" WHERE id = ?", id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> promises(String partnerId) {
        return db.rows("SELECT t.*, p.name AS \"partnerName\" FROM \"PromiseToPay\" t JOIN \"Partner\" p ON p.id ="
                + " t.\"partnerId\" WHERE t.\"organizationId\" = ? AND (?::text IS NULL OR t.\"partnerId\" = ?) ORDER BY"
                + " t.\"promisedDate\" DESC", org(), partnerId, partnerId);
    }

    @Transactional
    public Map<String, Object> cancelPromise(String id) {
        db.require("PromiseToPay", id, "Promise not found");
        db.update("UPDATE \"PromiseToPay\" SET status = 'cancelled', \"resolvedAt\" = now() WHERE id = ? AND status ="
                + " 'open'", id);
        return db.row("SELECT * FROM \"PromiseToPay\" WHERE id = ?", id);
    }

    /** Promises past their date: kept when the customer's receipts since the promise reach the amount, else broken. */
    @Transactional
    public int expirePromises(LocalDate asOf) {
        int n = 0;
        for (Map<String, Object> t : db.rows("SELECT * FROM \"PromiseToPay\" WHERE \"organizationId\" = ? AND status = 'open'"
                + " AND \"promisedDate\" < ?", org(), asOf)) {
            BigDecimal paid = dec(db.row("SELECT COALESCE(sum(amount), 0) AS paid FROM \"Payment\" WHERE \"organizationId\" ="
                    + " ? AND \"partnerId\" = ? AND direction::text = 'inbound' AND status::text = 'posted' AND"
                    + " \"createdAt\" >= ? AND \"paymentMethod\" <> 'customer_deposit'", org(), t.get("partnerId"),
                    t.get("paidSince")).get("paid"));
            db.update("UPDATE \"PromiseToPay\" SET status = ?, \"resolvedAt\" = now() WHERE id = ?", paid.compareTo(dec(t.get(
                    "amount"))) >= 0 ? "kept" : "broken", t.get("id"));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ worklist / statements

    /** Customers with open receivables by aging bucket, salesperson, last dunning level, open promise, hold. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> worklist(Map<String, String> p) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String extra = "";
        if (!blank(p.get("salespersonId"))) {
            extra += " AND pa.\"salespersonId\" = ?";
            params.add(p.get("salespersonId"));
        }
        if (!blank(p.get("branchId"))) {
            extra += " AND d.\"branchId\" = ?";
            params.add(p.get("branchId"));
        }
        List<Map<String, Object>> rows = db.rows("""
                SELECT d."partnerId", pa.name AS "partnerName", pa."creditHold", pa."salespersonId",
                       COALESCE(sum(d."amountResidual"), 0) AS open,
                       COALESCE(sum(d."amountResidual") FILTER (WHERE d."dueDate" IS NULL OR d."dueDate" >= now()), 0) AS "current",
                       COALESCE(sum(d."amountResidual") FILTER (WHERE now() - d."dueDate" BETWEEN interval '0' AND interval '30 days'), 0) AS d30,
                       COALESCE(sum(d."amountResidual") FILTER (WHERE now() - d."dueDate" > interval '30 days'
                           AND now() - d."dueDate" <= interval '60 days'), 0) AS d60,
                       COALESCE(sum(d."amountResidual") FILTER (WHERE now() - d."dueDate" > interval '60 days'
                           AND now() - d."dueDate" <= interval '90 days'), 0) AS d90,
                       COALESCE(sum(d."amountResidual") FILTER (WHERE now() - d."dueDate" > interval '90 days'), 0) AS over90,
                       (SELECT e."levelSequence" FROM "DunningEntry" e WHERE e."partnerId" = d."partnerId"
                          ORDER BY e."createdAt" DESC LIMIT 1) AS "lastLevel",
                       (SELECT max(e."createdAt") FROM "DunningEntry" e WHERE e."partnerId" = d."partnerId") AS "lastDunnedAt",
                       (SELECT t."promisedDate" FROM "PromiseToPay" t WHERE t."partnerId" = d."partnerId" AND t.status = 'open'
                          ORDER BY t."promisedDate" LIMIT 1) AS "promisedDate"
                  FROM "Document" d JOIN "Partner" pa ON pa.id = d."partnerId"
                 WHERE d."organizationId" = ? AND d."documentType"::text IN ('sales_invoice', 'debit_note')
                   AND d.status::text = 'posted' AND d."amountResidual" > 0""" + extra + " " + """
                 GROUP BY d."partnerId", pa.name, pa."creditHold", pa."salespersonId"
                 ORDER BY over90 DESC, d90 DESC, d60 DESC, open DESC""", params);
        String bucket = p.get("bucket");
        if (!blank(bucket)) {
            rows.removeIf(r -> dec(r.get(bucket)).signum() <= 0);
        }
        return rows;
    }

    /** E-mail statements to customers with an open balance (and an e-mail). Returns counts. */
    @Transactional(readOnly = true)
    public Map<String, Object> statementRun(LocalDate asOfIn) {
        LocalDate asOf = asOfIn == null ? LocalDate.now() : asOfIn;
        int sent = 0;
        int skipped = 0;
        for (Map<String, Object> c : db.rows("SELECT DISTINCT d.\"partnerId\", p.email FROM \"Document\" d JOIN \"Partner\" p"
                + " ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice' AND"
                + " d.status::text = 'posted' AND d.\"amountResidual\" > 0", org())) {
            if (blank(str(c.get("email")))) {
                skipped++;
                continue;
            }
            try {
                statements.email("ar", str(c.get("partnerId")), asOf.withDayOfMonth(1).minusMonths(1).toString(),
                        asOf.toString(), null, "Monthly statement");
                sent++;
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return Obj.of("sent", sent, "skipped", skipped, "asOf", asOf);
    }

}
