package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.BranchSaleRecognitionService;
import com.inventoryerp.invoicing.application.BranchSaleRecognitionService.SaleLine;
import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.invoicing.application.CustomerDepositService.CashSaleDeposit;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch cash sales submissions (ADR-S056, docs/spec/cash-sales.md). A showroom records the sale (draft), captures
 * and verifies payments, and <b>confirms</b> it when the goods are handed over: one transaction issues the stock
 * (P1), books each verified payment as a cash-sale customer deposit on the branch tender account (P2) and accrues
 * revenue and VAT to {@code unbilled_receivable} (P3). Head office then reviews, returns, rejects or approves and,
 * as a separate action, generates the invoice: a reclassification of the accrual to AR plus application of the
 * linked deposits (P4, P5). Submit / review / return / approve / reject never touch GL or stock. Void (P6) and
 * invoice cancel (P7) are the only reversals; both pass the fiscal gate first.
 */
@Service
public class CashSaleService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> HEADER = Set.of("partnerId", "customerName", "customerPhone", "customerTin",
            "customerAddress", "salespersonId", "saleDate", "currencyCode", "remarks", "branchId", "lines");
    static final Set<String> AMENDABLE = Set.of("customerName", "customerPhone", "customerTin", "customerAddress",
            "remarks");

    private static final String SELECT = """
            SELECT s.*, b.name AS "branchName", b.code AS "branchCode", p.name AS "partnerName",
                   l.name AS "locationName",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "salespersonName",
                   COALESCE(NULLIF(trim(concat_ws(' ', o."firstName", o."lastName")), ''), o.email) AS "ownerName",
                   COALESCE(NULLIF(trim(concat_ws(' ', dv."firstName", dv."lastName")), ''), dv.email) AS "deliveredByName",
                   d."documentNumber" AS "invoiceNumber"
              FROM "SalesSubmission" s
              LEFT JOIN "Branch" b ON b.id = s."branchId"
              LEFT JOIN "Partner" p ON p.id = s."partnerId"
              LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
              LEFT JOIN "User" u ON u.id = s."salespersonId"
              LEFT JOIN "User" o ON o.id = s."ownerUserId"
              LEFT JOIN "User" dv ON dv.id = s."deliveredById"
              LEFT JOIN "Document" d ON d.id = s."currentInvoiceId"
            """;

    private static final Set<String> CASH_SALE_METHODS = Set.of("cash", "mobile_money", "bank");

    private final SalesDb db;
    private final BranchSalesProfileService profiles;
    private final BranchScopeService scopes;
    private final SalesLinePricing pricing;
    private final DocumentBuilderService builder;
    private final StockService stock;
    private final CustomerDepositService deposits;
    private final BranchSaleRecognitionService recognition;
    private final InvoiceService invoices;
    private final CashSaleFiscalService fiscal;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final NotificationsService notifications;
    private final PosPaymentMethodService paymentModes;
    private final AccountDeterminationService determination;

    public CashSaleService(SalesDb db, BranchSalesProfileService profiles, BranchScopeService scopes,
            SalesLinePricing pricing, DocumentBuilderService builder, StockService stock,
            CustomerDepositService deposits, BranchSaleRecognitionService recognition, InvoiceService invoices,
            CashSaleFiscalService fiscal, SequenceService sequence, AuditService audit, EventBus events,
            NotificationsService notifications, PosPaymentMethodService paymentModes,
            AccountDeterminationService determination) {
        this.db = db;
        this.paymentModes = paymentModes;
        this.determination = determination;
        this.profiles = profiles;
        this.scopes = scopes;
        this.pricing = pricing;
        this.builder = builder;
        this.stock = stock;
        this.deposits = deposits;
        this.recognition = recognition;
        this.invoices = invoices;
        this.fiscal = fiscal;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ queries

    /** Page {@code {data, meta, totals}}; filters branchId, executionStatus, processingStatus (csv), from, to, q. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        for (String k : List.of("executionStatus", "processingStatus", "fiscalStatus")) {
            if (!blank(p.get(k))) {
                List<String> st = List.of(p.get(k).split(","));
                where.append(" AND s.\"").append(k).append("\" IN (").append(SalesDb.marks(st.size())).append(')');
                params.addAll(st);
            }
        }
        for (String k : List.of("branchId", "salespersonId", "partnerId")) {
            if (!blank(p.get(k))) {
                where.append(" AND s.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (s.number ILIKE ? OR s.\"customerName\" ILIKE ? OR p.name ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        if (!blank(p.get("from"))) {
            where.append(" AND s.\"saleDate\" >= ?");
            params.add(SalesDb.date(p.get("from")));
        }
        if (!blank(p.get("to"))) {
            where.append(" AND s.\"saleDate\" <= ?");
            params.add(SalesDb.date(p.get("to")));
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY s.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(s.\"grandTotal\"), 0) AS total FROM"
                + " \"SalesSubmission\" s LEFT JOIN \"Partner\" p ON p.id = s.\"partnerId\"" + where, params.toArray());
        long total = ((Number) totals.get("count")).longValue();
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Submission with lines, payments, deposits, invoice attempts, fiscal documents and the caller's actions. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = require(id);
        // Handover delivers every line at confirm; a void puts the goods back. Invoiced once HO generates the invoice.
        boolean delivered = "confirmed".equals(str(s.get("executionStatus")));
        boolean invoiced = s.get("currentInvoiceId") != null;
        List<Map<String, Object>> lines = lines(id);
        for (Map<String, Object> l : lines) {
            Object qty = l.get("quantity");
            l.put("deliveredQty", delivered ? qty : 0);
            l.put("invoicedQty", invoiced ? qty : 0);
        }
        s.put("lines", lines);
        s.put("payments", db.rows("SELECT y.*, a.code AS \"accountCode\", a.name AS \"accountName\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', c.\"firstName\", c.\"lastName\")), ''), c.email) AS \"collectedByName\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', v.\"firstName\", v.\"lastName\")), ''), v.email) AS \"verifiedByName\""
                + " FROM \"SalesSubmissionPayment\" y LEFT JOIN \"Account\" a ON a.id = y.\"accountId\" LEFT JOIN \"User\" c"
                + " ON c.id = y.\"collectedById\" LEFT JOIN \"User\" v ON v.id = y.\"verifiedById\" WHERE y.\"submissionId\" = ?"
                + " ORDER BY y.\"createdAt\"", id));
        s.put("invoices", db.rows("SELECT i.*, d.\"documentNumber\", d.status::text AS \"documentStatus\","
                + " d.\"totalAmount\", d.\"amountResidual\" FROM \"SalesSubmissionInvoice\" i LEFT JOIN \"Document\" d ON"
                + " d.id = i.\"invoiceId\" WHERE i.\"submissionId\" = ? ORDER BY i.attempt", id));
        s.put("deposits", db.rows("SELECT id, number, amount, \"appliedAmount\", \"refundedAmount\", status, source FROM"
                + " \"CustomerDeposit\" WHERE \"organizationId\" = ? AND \"cashSaleId\" = ? ORDER BY \"createdAt\"", org(), id));
        s.put("fiscalDocuments", db.rows("SELECT id, operation, provider, \"requestRef\", status, attempts, \"lastError\","
                + " \"externalRef\", \"fiscalNumber\", reason, \"createdAt\", \"updatedAt\" FROM \"FiscalDocument\" WHERE"
                + " \"submissionId\" = ? ORDER BY \"createdAt\"", id));
        s.put("actions", actions(s));
        return s;
    }

    private List<Map<String, Object>> lines(String id) {
        return db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\", pr.\"trackInventory\","
                + " t.name AS \"taxName\", t.rate AS \"taxRate\", u.code AS \"uomCode\" FROM \"SalesSubmissionLine\" l LEFT"
                + " JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" LEFT JOIN"
                + " \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"submissionId\" = ? ORDER BY l.\"sortOrder\"", id);
    }

    /** The transitions open to the caller now (drives the buttons of the web screens). */
    private static List<String> actions(Map<String, Object> s) {
        String ex = str(s.get("executionStatus"));
        String pr = str(s.get("processingStatus"));
        List<String> out = new ArrayList<>();
        if ("draft".equals(ex) && TenantContext.has(Permissions.SALES__CASH_SALE_CREATE)) {
            out.addAll(List.of("edit", "payments", "discard"));
        }
        if ("draft".equals(ex) && TenantContext.has(Permissions.SALES__CASH_SALE_CONFIRM)) {
            out.add("confirm");
        }
        if ("confirmed".equals(ex) && TenantContext.has(Permissions.SALES__CASH_SALE_SUBMIT)) {
            if ("not_submitted".equals(pr)) {
                out.add("submit");
            }
            if ("returned_for_correction".equals(pr)) {
                out.addAll(List.of("amend", "resubmit"));
            }
        }
        if ("submitted".equals(pr) && TenantContext.has(Permissions.SALES__CASH_SALE_REVIEW)) {
            out.add("start_review");
        }
        if ("under_review".equals(pr)) {
            if (TenantContext.has(Permissions.SALES__CASH_SALE_REVIEW)) {
                out.add("return");
            }
            if (TenantContext.has(Permissions.SALES__CASH_SALE_APPROVE)) {
                out.addAll(List.of("approve", "reject"));
            }
        }
        if ("rejected".equals(pr) && TenantContext.has(Permissions.SALES__CASH_SALE_APPROVE)) {
            out.addAll(List.of("reopen", "assign"));
        }
        if ("approved".equals(pr) && TenantContext.has(Permissions.SALES__CASH_SALE_INVOICE)) {
            out.add("generate_invoice");
        }
        if ("invoiced".equals(pr) && TenantContext.has(Permissions.SALES__CASH_SALE_INVOICE)) {
            out.add("cancel_invoice");
        }
        if ("confirmed".equals(ex) && !"invoiced".equals(pr) && TenantContext.has(Permissions.SALES__CASH_SALE_VOID)) {
            out.add("void");
        }
        return out;
    }

    /** Timeline: transitions, amendments and fiscal operations, oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(String id) {
        require(id);
        return db.rows("SELECT e.id, e.action, e.\"fromExecution\", e.\"toExecution\", e.\"fromProcessing\","
                + " e.\"toProcessing\", e.reason, e.field, e.\"oldValue\", e.\"newValue\", e.\"userId\", e.\"createdAt\","
                + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"userName\""
                + " FROM \"SalesSubmissionEvent\" e LEFT JOIN \"User\" u ON u.id = e.\"userId\" WHERE e.\"submissionId\" = ?"
                + " AND e.\"organizationId\" = ? ORDER BY e.\"createdAt\", e.id", id, org());
    }

    /**
     * Dashboard tiles + queues. {@code scope=branch}: the caller's branches; {@code head_office}: every branch
     * (needs read_all), with totals per branch, the rejected / uninvoiced exception queue and processing time.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(String scope) {
        boolean ho = "head_office".equals(scope);
        if (ho && !TenantContext.has(Permissions.SALES__CASH_SALE_READ_ALL)) {
            throw ApiException.forbidden("The head-office dashboard needs sales:cash_sale:read_all");
        }
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.\"executionStatus\" <> 'cancelled'");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!ho) {
            visibility(where, params);
        }
        Map<String, Object> tiles = db.row("SELECT count(*) AS total,"
                + " count(*) FILTER (WHERE s.\"executionStatus\" = 'draft') AS drafts,"
                + " count(*) FILTER (WHERE s.\"processingStatus\" IN ('submitted', 'under_review')) AS pending,"
                + " count(*) FILTER (WHERE s.\"processingStatus\" = 'approved') AS \"awaitingInvoice\","
                + " count(*) FILTER (WHERE s.\"processingStatus\" = 'invoiced') AS invoiced,"
                + " count(*) FILTER (WHERE s.\"processingStatus\" = 'returned_for_correction') AS returned,"
                + " count(*) FILTER (WHERE s.\"processingStatus\" = 'rejected') AS rejected,"
                + " COALESCE(sum(s.\"grandTotal\") FILTER (WHERE s.\"executionStatus\" = 'confirmed'), 0) AS \"confirmedTotal\","
                + " COALESCE(sum(s.\"grandTotal\") FILTER (WHERE s.\"executionStatus\" = 'confirmed' AND s.\"processingStatus\""
                + " <> 'invoiced'), 0) AS \"uninvoicedTotal\" FROM \"SalesSubmission\" s" + where, params.toArray());
        List<Map<String, Object>> recent = db.rows(SELECT + where.toString() + " ORDER BY s.\"updatedAt\" DESC LIMIT 10",
                params);
        Map<String, Object> out = Obj.of("scope", ho ? "head_office" : "branch", "tiles", tiles, "recent", recent);
        List<Map<String, Object>> exceptions = db.rows(SELECT + where + " AND s.\"executionStatus\" = 'confirmed' AND"
                + " s.\"processingStatus\" = 'rejected' ORDER BY s.\"rejectedAt\"", params);
        for (Map<String, Object> e : exceptions) {
            Instant at = e.get("rejectedAt") instanceof Instant i ? i : Instant.now();
            long days = java.time.Duration.between(at, Instant.now()).toDays();
            Map<String, Object> prof = db.row("SELECT \"rejectedAlertDays\" FROM \"BranchSalesProfile\" WHERE id = ?",
                    e.get("profileId"));
            e.put("ageDays", days);
            e.put("overdue", prof != null && days >= ((Number) prof.get("rejectedAlertDays")).longValue());
        }
        out.put("exceptions", exceptions);
        if (ho) {
            out.put("byBranch", db.rows("SELECT s.\"branchId\", b.name AS \"branchName\", count(*) AS count,"
                    + " COALESCE(sum(s.\"grandTotal\") FILTER (WHERE s.\"executionStatus\" = 'confirmed'), 0) AS total,"
                    + " count(*) FILTER (WHERE s.\"processingStatus\" IN ('submitted', 'under_review')) AS pending FROM"
                    + " \"SalesSubmission\" s LEFT JOIN \"Branch\" b ON b.id = s.\"branchId\"" + where
                    + " GROUP BY s.\"branchId\", b.name ORDER BY b.name", params.toArray()));
            out.put("bySalesperson", db.rows("SELECT s.\"salespersonId\", COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\","
                    + " u.\"lastName\")), ''), u.email) AS name, count(*) AS count, COALESCE(sum(s.\"grandTotal\"), 0) AS total"
                    + " FROM \"SalesSubmission\" s LEFT JOIN \"User\" u ON u.id = s.\"salespersonId\"" + where
                    + " AND s.\"executionStatus\" = 'confirmed' GROUP BY s.\"salespersonId\", u.\"firstName\", u.\"lastName\","
                    + " u.email ORDER BY total DESC LIMIT 20", params.toArray()));
            out.put("processing", db.row("SELECT COALESCE(avg(EXTRACT(EPOCH FROM (s.\"invoicedAt\" - s.\"submittedAt\")) /"
                    + " 3600), 0) AS \"avgHoursToInvoice\", count(*) AS invoiced FROM \"SalesSubmission\" s" + where
                    + " AND s.\"invoicedAt\" IS NOT NULL AND s.\"submittedAt\" IS NOT NULL", params.toArray()));
        }
        return out;
    }

    // ------------------------------------------------------------------ draft

    /** New draft: branch from the caller's scope, its profile's location; customer defaults to the walk-in partner. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        unknownFields(in, HEADER);
        String branchId = scopes.documentBranch(blank(str(in.get("branchId"))) ? null : str(in.get("branchId")));
        if (branchId == null) {
            branchId = scopes.branchFilter();
        }
        if (branchId == null) {
            throw ApiException.badRequest("branchId should not be empty");
        }
        Map<String, Object> profile = profiles.forBranch(branchId);
        Map<String, Object> d = header(in, profile, true);
        d.put("branchId", branchId);
        d.put("profileId", profile.get("id"));
        d.put("locationId", profile.get("locationId"));
        LocalDate saleDate = (LocalDate) d.getOrDefault("saleDate", LocalDate.now());
        d.put("saleDate", saleDate);
        d.put("number", nextNumber(branchId, str(profile.get("numberPrefix")), saleDate.getYear()));
        d.put("currencyCode", orgCurrency());
        d.putIfAbsent("salespersonId", user());
        d.put("createdById", user());
        Map<String, Object> row = db.insert("SalesSubmission", d);
        String id = str(row.get("id"));
        writeLines(id, str(d.get("partnerId")), saleDate, in.get("lines"));
        event(id, "create", null, "draft", null, "not_submitted", null);
        audit.record(AuditInput.of("SalesSubmission", id, "create", null, Obj.of("number", row.get("number"))));
        return get(id);
    }

    /** Edit a draft (header and / or lines). */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        unknownFields(in, HEADER);
        Map<String, Object> s = lock(id);
        requireDraft(s);
        if (in.containsKey("branchId") && !Objects.equals(str(in.get("branchId")), str(s.get("branchId")))) {
            throw ApiException.badRequest("The branch of a cash sale cannot change");
        }
        Map<String, Object> profile = profiles.get(str(s.get("profileId")));
        Map<String, Object> d = header(in, profile, false);
        d.put("version", ((Number) s.get("version")).intValue() + 1);
        db.patch("SalesSubmission", id, d, true);
        if (in.containsKey("lines") || d.containsKey("partnerId")) {
            Object lines = in.containsKey("lines") ? in.get("lines") : existingLines(id);
            writeLines(id, str(d.getOrDefault("partnerId", s.get("partnerId"))), (LocalDate) d.getOrDefault("saleDate",
                    s.get("saleDate")), lines);
        }
        audit.record(AuditInput.of("SalesSubmission", id, "update", null, Obj.of("fields", d.keySet())));
        return get(id);
    }

    /** Cancel a draft (nothing was posted). */
    @Transactional
    public Map<String, Object> discard(String id, String reason) {
        Map<String, Object> s = lock(id);
        requireDraft(s);
        db.patch("SalesSubmission", id, Obj.of("executionStatus", "cancelled"), true);
        event(id, "discard", "draft", "cancelled", null, null, reason);
        return get(id);
    }

    private Map<String, Object> header(Map<String, Object> in, Map<String, Object> profile, boolean create) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("partnerId") || create) {
            String partnerId = blank(str(in.get("partnerId"))) ? str(profile.get("walkInPartnerId")) : str(in.get("partnerId"));
            if (partnerId == null) {
                throw ApiException.badRequest("partnerId should not be empty (the branch has no walk-in customer)");
            }
            Map<String, Object> partner = db.row("SELECT id, name, phone, \"taxNumber\" FROM \"Partner\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", partnerId, org());
            if (partner == null) {
                throw ApiException.notFound("Partner not found");
            }
            d.put("partnerId", partnerId);
            boolean walkIn = partnerId.equals(str(profile.get("walkInPartnerId")));
            if (!walkIn) {
                d.put("customerName", partner.get("name"));
                d.put("customerPhone", partner.get("phone"));
                d.put("customerTin", partner.get("taxNumber"));
            }
        }
        for (String k : List.of("customerName", "customerPhone", "customerTin", "customerAddress", "remarks")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)).trim());
            }
        }
        if (in.containsKey("salespersonId")) {
            String sp = blank(str(in.get("salespersonId"))) ? null : str(in.get("salespersonId"));
            if (sp != null && db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL", List.of(sp, org())) == 0) {
                throw ApiException.notFound("User not found");
            }
            d.put("salespersonId", sp);
        }
        if (in.containsKey("saleDate")) {
            LocalDate date = SalesDb.date(str(in.get("saleDate")));
            if (date != null && date.isAfter(LocalDate.now())) {
                throw ApiException.badRequest("saleDate must not be in the future");
            }
            if (date != null) {
                d.put("saleDate", date);
            }
        }
        if (in.containsKey("currencyCode") && !blank(str(in.get("currencyCode")))
                && !orgCurrency().equals(str(in.get("currencyCode")))) {
            throw ApiException.badRequest("Cash sales are recorded in the organization currency");
        }
        return d;
    }

    private List<Map<String, Object>> existingLines(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lines(id)) {
            out.add(Obj.of("productId", l.get("productId"), "description", l.get("description"), "quantity",
                    l.get("quantity"), "uomId", l.get("uomId"), "unitPrice", l.get("unitPrice"), "discountPercent",
                    l.get("discountPercent"), "taxId", l.get("taxId"), "taxInclusive", l.get("taxInclusive")));
        }
        return out;
    }

    /** Replace the lines of a draft: priced (explicit price or the pricing engine) and taxed by the tax engine. */
    @SuppressWarnings("unchecked")
    private void writeLines(String id, String partnerId, LocalDate on, Object raw) {
        db.update("DELETE FROM \"SalesSubmissionLine\" WHERE \"submissionId\" = ? AND \"organizationId\" = ?", id, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<LineInput> inputs = new ArrayList<>();
        List<Map<String, Object>> meta = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            unknownFields(l, Set.of("productId", "description", "quantity", "uomId", "unitPrice", "discountPercent",
                    "taxId", "taxInclusive"));
            String productId = str(l.get("productId"));
            if (blank(productId)) {
                throw ApiException.badRequest("productId should not be empty");
            }
            Map<String, Object> product = db.row("SELECT id, name, \"costPrice\" FROM \"Product\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", productId, org());
            if (product == null) {
                throw ApiException.notFound("Product not found");
            }
            BigDecimal qty = dec(l.get("quantity"));
            if (qty.signum() <= 0) {
                throw ApiException.badRequest("quantity must be greater than 0");
            }
            String uomId = blank(str(l.get("uomId"))) ? null : str(l.get("uomId"));
            QuotationPricingPort.Price price = pricing.price(partnerId, productId, qty, uomId, on);
            BigDecimal unit = l.get("unitPrice") != null ? dec(l.get("unitPrice")) : price.unitPrice();
            BigDecimal disc = l.get("discountPercent") != null ? dec(l.get("discountPercent")) : price.discountPercent();
            if (unit.signum() < 0) {
                throw ApiException.badRequest("unitPrice must not be less than 0");
            }
            if (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("discountPercent must be between 0 and 100");
            }
            boolean same = unit.compareTo(price.unitPrice()) == 0 && disc.compareTo(price.discountPercent()) == 0;
            String taxId = l.containsKey("taxId") ? (blank(str(l.get("taxId"))) ? null : str(l.get("taxId"))) : price.taxId();
            boolean incl = l.get("taxInclusive") instanceof Boolean b ? b : price.taxInclusive();
            String description = !blank(str(l.get("description"))) ? str(l.get("description"))
                    : price.description() != null ? price.description() : str(product.get("name"));
            inputs.add(new LineInput(productId, null, null, null, null, description, qty, unit, disc, null, null, null,
                    "manual", taxId, null, incl, uomId));
            meta.add(Obj.of("listPrice", price.listPrice(), "priceSource", same ? price.source() : "manual", "cost",
                    product.get("costPrice")));
        }
        if (!inputs.isEmpty()) {
            Totals totals = builder.prepareLines(inputs);
            int i = 0;
            for (PreparedLine pl : totals.prepared()) {
                Map<String, Object> m = meta.get(i);
                Map<String, Object> ins = new LinkedHashMap<>();
                ins.put("submissionId", id);
                ins.put("sortOrder", i);
                ins.put("productId", pl.productId());
                ins.put("description", pl.description());
                ins.put("quantity", pl.quantity());
                ins.put("uomId", pl.uomId());
                ins.put("uomFactor", pl.uomFactor() == null ? BigDecimal.ONE : pl.uomFactor());
                ins.put("listPrice", m.get("listPrice"));
                ins.put("unitPrice", pl.unitPrice());
                ins.put("discountPercent", pl.discountPercent());
                ins.put("priceSource", m.get("priceSource"));
                ins.put("taxId", pl.taxId());
                ins.put("taxInclusive", pl.taxInclusive());
                ins.put("lineSubtotal", pl.subtotal());
                ins.put("lineTax", pl.taxAmount());
                ins.put("lineTotal", pl.total());
                ins.put("costSnapshot", m.get("cost") == null ? null : dec(m.get("cost")));
                db.insert("SalesSubmissionLine", ins);
                i++;
            }
        }
        Map<String, Object> t = db.row("SELECT COALESCE(sum(\"lineSubtotal\"), 0) AS sub, COALESCE(sum(\"lineTax\"), 0) AS tax,"
                + " COALESCE(sum(\"lineTotal\"), 0) AS tot, COALESCE(sum(quantity * \"unitPrice\" * \"discountPercent\" / 100), 0)"
                + " AS disc FROM \"SalesSubmissionLine\" WHERE \"submissionId\" = ? AND \"organizationId\" = ?", id, org());
        db.update("UPDATE \"SalesSubmission\" SET subtotal = ?, \"taxTotal\" = ?, \"grandTotal\" = ?, \"discountTotal\" = ?,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?", dec(t.get("sub")), dec(t.get("tax")), dec(t.get("tot")),
                dec(t.get("disc")).setScale(6, RoundingMode.HALF_UP), id);
    }

    // ------------------------------------------------------------------ payments

    /** Payment modes a cash sale can take: the organization's active cash, mobile-money and bank modes. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> paymentModes() {
        return paymentModes.terminalPaymentMethods().stream().filter(m -> CASH_SALE_METHODS.contains(m.kind()))
                .map(PosPaymentMethodService.View::toMap).toList();
    }

    /**
     * Capture a payment line on a draft. With {@code paymentModeId} the organization's payment mode decides the
     * method, provider and receiving account; otherwise the tender account comes from the branch profile. A
     * client-sent account is never trusted.
     */
    @Transactional
    public Map<String, Object> addPayment(String id, Map<String, Object> in) {
        unknownFields(in, Set.of("method", "provider", "amount", "reference", "accountId", "paymentModeId"));
        Map<String, Object> s = lock(id);
        requireDraft(s);
        String method;
        String provider;
        String account;
        String modeId = blank(str(in.get("paymentModeId"))) ? null : str(in.get("paymentModeId"));
        String modeLabel = null;
        if (modeId != null) {
            PosPaymentMethodService.View mode = paymentModes.terminalPaymentMethods().stream()
                    .filter(m -> m.id().equals(modeId)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Payment mode not found"));
            method = mode.kind();
            if (!CASH_SALE_METHODS.contains(method)) {
                throw ApiException.badRequest("Payment mode " + mode.label() + " cannot be used for cash sales");
            }
            modeLabel = mode.label();
            provider = "mobile_money".equals(method)
                    ? (blank(mode.provider()) ? mode.code() : mode.provider()).trim().toLowerCase() : null;
            account = mode.accountId();
            if (account == null) {
                Map<String, Object> profile = profiles.get(str(s.get("profileId")));
                account = "cash".equals(method) ? str(profile.get("cashAccountId")) : null;
                if (account == null) {
                    account = determination.mapped("cash".equals(method) ? "default_cash" : "mobile_money"
                            .equals(method) ? "mobile_money" : "default_bank");
                }
            }
            List<String> problems = recognition.tenderProblems(mode.label(), method, account);
            if (!problems.isEmpty()) {
                throw ApiException.badRequest(String.join("; ", problems));
            }
        } else {
            method = str(in.get("method"));
            provider = blank(str(in.get("provider"))) ? null : str(in.get("provider"));
            if (!"mobile_money".equals(method)) {
                provider = null;
            }
            Map<String, Object> profile = profiles.get(str(s.get("profileId")));
            account = BranchSalesProfileService.tenderAccount(profile, method, provider);
        }
        BigDecimal amount = dec(in.get("amount")).setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        String reference = blank(str(in.get("reference"))) ? null : str(in.get("reference")).trim();
        if (!"cash".equals(method) && reference == null) {
            throw ApiException.badRequest("reference should not be empty for " + method.replace('_', ' ') + " payments");
        }
        try {
            db.insert("SalesSubmissionPayment", Obj.of("submissionId", id, "method", method, "provider", provider, "amount",
                    amount, "reference", reference, "accountId", account, "collectedById", user(), "paymentModeId",
                    modeId, "paymentModeLabel", modeLabel));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Payment reference " + reference + " is already recorded");
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> removePayment(String id, String paymentId) {
        Map<String, Object> s = lock(id);
        requireDraft(s);
        if (db.update("DELETE FROM \"SalesSubmissionPayment\" WHERE id = ? AND \"submissionId\" = ? AND \"organizationId\" = ?",
                paymentId, id, org()) == 0) {
            throw ApiException.notFound("Payment not found");
        }
        return get(id);
    }

    /**
     * Verify (or fail) a payment line. Cash: the confirmer (sales:cash_sale:confirm) or a verifier; mobile money and
     * bank: a verifier only (sales:cash_sale:verify_payment). With segregation the verifier is never the collector.
     */
    @Transactional
    public Map<String, Object> verifyPayment(String id, String paymentId, Map<String, Object> in) {
        unknownFields(in, Set.of("status", "note"));
        Map<String, Object> s = lock(id);
        requireDraft(s);
        Map<String, Object> pay = db.row("SELECT * FROM \"SalesSubmissionPayment\" WHERE id = ? AND \"submissionId\" = ?",
                paymentId, id);
        if (pay == null) {
            throw ApiException.notFound("Payment not found");
        }
        String status = blank(str(in.get("status"))) ? "verified" : str(in.get("status"));
        if (!Set.of("verified", "failed").contains(status)) {
            throw ApiException.badRequest("status must be one of: verified, failed");
        }
        boolean verifier = TenantContext.has(Permissions.SALES__CASH_SALE_VERIFY_PAYMENT);
        String source;
        if ("cash".equals(pay.get("method"))) {
            if (!verifier && !TenantContext.has(Permissions.SALES__CASH_SALE_CONFIRM)) {
                throw ApiException.forbidden("Verifying cash needs sales:cash_sale:confirm or sales:cash_sale:verify_payment");
            }
            source = verifier ? "verifier" : "confirmer_cash";
        } else {
            if (!verifier) {
                throw ApiException.forbidden("Verifying " + str(pay.get("method")).replace('_', ' ')
                        + " needs sales:cash_sale:verify_payment");
            }
            source = "verifier";
        }
        if (segregated(s) && Objects.equals(str(pay.get("collectedById")), user())) {
            throw ApiException.forbidden("Segregation of duties: the collector cannot verify the payment");
        }
        db.patch("SalesSubmissionPayment", paymentId, Obj.of("verificationStatus", status, "verifiedById", user(),
                "verifiedAt", Instant.now(), "verificationSource", source, "verificationNote", str(in.get("note"))),
                false);
        event(id, "payment_" + status, null, null, null, null, str(pay.get("method")) + " " + JsNumbers.js(dec(pay.get(
                "amount"))) + (pay.get("reference") == null ? "" : " ref " + pay.get("reference")));
        return get(id);
    }

    // ------------------------------------------------------------------ confirm (P1 + P2 + P3)

    /**
     * Confirm the sale and handover (cash-sales.md §2): every payment line verified and Σ payments = grand total;
     * then, in one transaction, the stock issue at the branch location (P1), one cash-sale deposit per payment (P2)
     * and the revenue / VAT accrual (P3). Financial fields are frozen from here on.
     */
    @Transactional
    public Map<String, Object> confirm(String id) {
        Map<String, Object> s = lock(id);
        requireDraft(s);
        List<Map<String, Object>> lines = lines(id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("A cash sale needs at least one line");
        }
        List<Map<String, Object>> payments = db.rows("SELECT * FROM \"SalesSubmissionPayment\" WHERE \"submissionId\" = ?"
                + " ORDER BY \"createdAt\", id", id);
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> p : payments) {
            if (!"verified".equals(p.get("verificationStatus"))) {
                throw ApiException.badRequest("Every payment must be verified before the sale is confirmed ("
                        + str(p.get("method")).replace('_', ' ') + " " + JsNumbers.js(dec(p.get("amount"))) + " is "
                        + p.get("verificationStatus") + ")");
            }
            if (segregated(s) && (Objects.equals(str(p.get("verifiedById")), str(p.get("collectedById")))
                    || Objects.equals(str(p.get("verifiedById")), user()))) {
                throw ApiException.forbidden("Segregation of duties: payments must be verified by someone other than"
                        + " the collector and the confirmer");
            }
            paid = paid.add(dec(p.get("amount")));
        }
        BigDecimal total = dec(s.get("grandTotal"));
        if (paid.compareTo(total) != 0) {
            throw ApiException.badRequest("Payments (" + JsNumbers.js(paid) + ") must equal the sale total ("
                    + JsNumbers.js(total) + ")");
        }
        Map<String, Object> profile = profiles.get(str(s.get("profileId")));
        if (!Boolean.TRUE.equals(profile.get("isActive"))) {
            throw ApiException.badRequest("This branch has no active branch sales profile");
        }
        profiles.requireValid(profile);
        String partnerId = str(s.get("partnerId"));
        String branchId = str(s.get("branchId"));
        List<SaleLine> sale = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            sale.add(new SaleLine(str(l.get("productId")), str(l.get("taxId")), dec(l.get("lineSubtotal")),
                    dec(l.get("lineTax"))));
        }
        List<String> problems = recognition.lineAccountProblems(partnerId, sale);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Branch sales profile is not valid: " + String.join("; ", problems));
        }
        String number = str(s.get("number"));
        LocalDate saleDate = s.get("saleDate") instanceof LocalDate d ? d : LocalDate.now();
        Instant postDate = saleDate.equals(LocalDate.now()) ? Instant.now() : saleDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        // P1 — stock leaves the showroom at handover
        for (Map<String, Object> l : lines) {
            if (!Boolean.TRUE.equals(l.get("trackInventory"))) {
                continue;
            }
            StockMove.Issued issued = stock.issue(StockMove.of(str(l.get("productId")), str(s.get("locationId")),
                    dec(l.get("quantity"))).uom(str(l.get("uomId")), dec(l.get("uomFactor"))).source("cash_sale", id)
                    .reference(number).date(postDate).notes("Cash sale " + number));
            BigDecimal unitCost = issued.quantity().signum() == 0 ? BigDecimal.ZERO
                    : issued.totalValue().divide(dec(l.get("quantity")), 6, RoundingMode.HALF_UP);
            db.update("UPDATE \"SalesSubmissionLine\" SET \"costSnapshot\" = ?, \"issueLedgerCode\" = ? WHERE id = ?",
                    unitCost, issued.ledgerCode(), l.get("id"));
        }
        // P2 — verified money, held as a cash-sale deposit on the branch tender account
        for (Map<String, Object> p : payments) {
            Map<String, Object> dep = deposits.receiveForCashSale(new CashSaleDeposit(partnerId, id, number, branchId,
                    dec(p.get("amount")), str(p.get("method")), str(p.get("accountId")), saleDate, str(p.get("reference")),
                    "css:" + id + ":pay:" + p.get("id")));
            db.update("UPDATE \"SalesSubmissionPayment\" SET \"customerDepositId\" = ? WHERE id = ?", dep.get("id"),
                    p.get("id"));
        }
        // P3 — revenue and VAT, once
        Map<String, Object> entry = recognition.accrue(new BranchSaleRecognitionService.Accrual(id, number, partnerId,
                branchId, postDate, sale));
        db.update("UPDATE \"SalesSubmission\" SET \"executionStatus\" = 'confirmed', \"confirmedAt\" = " + NOW + ","
                + " \"confirmedById\" = ?, \"paidTotal\" = ?, \"accrualJournalEntryId\" = ?, version = version + 1,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?", user(), paid, entry.get("id"), id);
        event(id, "confirm", "draft", "confirmed", null, null, null);
        if ("at_confirm".equals(str(profile.get("fiscalTrigger")))) {
            fiscal.request(db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ?", id), "issue", null, fiscalPayload(id),
                    null);
        }
        publish(DomainEvents.SALES_CASH_SALE_CONFIRMED, id, null);
        audit.record(AuditInput.of("SalesSubmission", id, "update", null, Obj.of("confirmed", number, "total", total)));
        return get(id);
    }

    // ------------------------------------------------------------------ head office workflow (no GL, no stock)

    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> s = lock(id);
        requireExecution(s, "confirmed");
        requireProcessing(s, "not_submitted");
        return processing(id, s, "submit", "submitted", Map.of("submittedAt", Instant.now(), "submittedById", user()),
                null, DomainEvents.SALES_CASH_SALE_SUBMITTED);
    }

    @Transactional
    public Map<String, Object> resubmit(String id) {
        Map<String, Object> s = lock(id);
        requireExecution(s, "confirmed");
        requireProcessing(s, "returned_for_correction");
        return processing(id, s, "resubmit", "submitted", Map.of("submittedAt", Instant.now(), "submittedById", user()),
                null, DomainEvents.SALES_CASH_SALE_SUBMITTED);
    }

    @Transactional
    public Map<String, Object> startReview(String id) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "submitted");
        return processing(id, s, "start_review", "under_review", Map.of("reviewStartedAt", Instant.now(),
                "reviewStartedById", user()), null, null);
    }

    @Transactional
    public Map<String, Object> returnForCorrection(String id, String reason) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "under_review");
        requireReason(reason);
        Map<String, Object> out = processing(id, s, "return", "returned_for_correction", Map.of(), reason,
                DomainEvents.SALES_CASH_SALE_RETURNED);
        notify(str(s.get("submittedById")), "Cash sale " + s.get("number") + " returned for correction", reason, id);
        return out;
    }

    static final List<String> DELIVERY_FIELDS = List.of("deliveryMethod", "deliveryStatus", "deliveryAddress",
            "deliveryContactName", "deliveryContactPhone", "deliveryScheduledDate", "deliveryDriver", "deliveryVehicle",
            "deliveryReceivedBy", "deliveryNotes");
    private static final List<String> DELIVERY_STATUSES = List.of("pending", "scheduled", "dispatched", "delivered",
            "failed");

    /**
     * Delivery and fulfilment details (logistics only; stock is issued at confirm). Editable on a draft or confirmed
     * sale; dispatch and delivery need the sale confirmed; a completed delivery keeps only its receipt fields open.
     * Every changed field is written to the timeline.
     */
    @Transactional
    public Map<String, Object> updateDelivery(String id, Map<String, Object> in) {
        unknownFields(in, Set.copyOf(DELIVERY_FIELDS));
        Map<String, Object> s = lock(id);
        String execution = str(s.get("executionStatus"));
        if ("voided".equals(execution) || "cancelled".equals(execution)) {
            throw ApiException.badRequest("Delivery cannot change on a voided or discarded sale");
        }
        Map<String, Object> next = new LinkedHashMap<>();
        for (String k : DELIVERY_FIELDS) {
            Object current = s.get(k);
            next.put(k, current == null ? null : "deliveryScheduledDate".equals(k) ? current.toString() : str(current));
        }
        for (String k : in.keySet()) {
            next.put(k, blank(str(in.get(k))) ? null : str(in.get(k)).trim());
        }
        String method = str(next.get("deliveryMethod"));
        String status = str(next.get("deliveryStatus"));
        if (!List.of("pickup", "delivery").contains(method)) {
            throw ApiException.badRequest("deliveryMethod must be one of: pickup, delivery");
        }
        if (!DELIVERY_STATUSES.contains(status)) {
            throw ApiException.badRequest("deliveryStatus must be one of: " + String.join(", ", DELIVERY_STATUSES));
        }
        LocalDate scheduled = null;
        if (next.get("deliveryScheduledDate") != null) {
            try {
                scheduled = LocalDate.parse(str(next.get("deliveryScheduledDate")).substring(0,
                        Math.min(10, str(next.get("deliveryScheduledDate")).length())));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("deliveryScheduledDate must be a valid ISO 8601 date string");
            }
            next.put("deliveryScheduledDate", scheduled.toString());
        }
        String before = str(s.get("deliveryStatus"));
        if ("delivered".equals(before)) {
            for (String k : DELIVERY_FIELDS) {
                if (!Set.of("deliveryReceivedBy", "deliveryNotes").contains(k)
                        && !Objects.equals(next.get(k), k.equals("deliveryScheduledDate") && s.get(k) != null
                                ? s.get(k).toString() : str(s.get(k)))) {
                    throw ApiException.badRequest("Delivery is already completed; only the receiver and notes can change");
                }
            }
        }
        if (List.of("dispatched", "delivered").contains(status) && !"confirmed".equals(execution)) {
            throw ApiException.badRequest("Confirm the sale before the goods are dispatched or delivered");
        }
        if ("delivery".equals(method) && !"pending".equals(status) && next.get("deliveryAddress") == null) {
            throw ApiException.badRequest("deliveryAddress should not be empty for a delivery");
        }
        if ("scheduled".equals(status) && scheduled == null) {
            throw ApiException.badRequest("deliveryScheduledDate should not be empty when the delivery is scheduled");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String k : DELIVERY_FIELDS) {
            Object old = s.get(k) == null ? null : "deliveryScheduledDate".equals(k) ? s.get(k).toString() : str(s.get(k));
            if (!Objects.equals(old, next.get(k))) {
                patch.put(k, "deliveryScheduledDate".equals(k) && scheduled != null ? scheduled : next.get(k));
                if (!"deliveryStatus".equals(k)) {
                    db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action,"
                            + " field, \"oldValue\", \"newValue\", \"userId\") VALUES (?, ?, ?, 'delivery_update', ?, ?, ?, ?)",
                            SalesDb.newId(), org(), id, k, str(old), str(next.get(k)), user());
                }
            }
        }
        if (patch.isEmpty()) {
            return get(id);
        }
        if (!Objects.equals(before, status)) {
            event(id, "delivery_" + status, null, null, null, null, null);
            if ("delivered".equals(status)) {
                db.update("UPDATE \"SalesSubmission\" SET \"deliveredAt\" = " + NOW + ", \"deliveredById\" = ? WHERE id = ?",
                        user(), id);
            }
        }
        patch.put("version", ((Number) s.get("version")).intValue() + 1);
        db.patch("SalesSubmission", id, patch, true);
        return get(id);
    }

    /** Non-financial corrections while returned (audited one row per changed field). */
    @Transactional
    public Map<String, Object> amend(String id, Map<String, Object> in) {
        for (String k : in.keySet()) {
            if (!AMENDABLE.contains(k)) {
                throw ApiException.badRequest(k + " cannot change after the sale is confirmed; void the sale and record"
                        + " a new one");
            }
        }
        Map<String, Object> s = lock(id);
        requireProcessing(s, "returned_for_correction");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String k : in.keySet()) {
            String v = blank(str(in.get(k))) ? null : str(in.get(k)).trim();
            if (!Objects.equals(v, str(s.get(k)))) {
                changes.put(k, v);
            }
        }
        fiscal.assertIdentityEditable(s, changes);
        for (Map.Entry<String, Object> c : changes.entrySet()) {
            db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action, field,"
                    + " \"oldValue\", \"newValue\", \"userId\") VALUES (?, ?, ?, 'amendment', ?, ?, ?, ?)", SalesDb.newId(),
                    org(), id, c.getKey(), str(s.get(c.getKey())), str(c.getValue()), user());
        }
        if (!changes.isEmpty()) {
            Map<String, Object> patch = new LinkedHashMap<>(changes);
            patch.put("version", ((Number) s.get("version")).intValue() + 1);
            db.patch("SalesSubmission", id, patch, true);
        }
        return get(id);
    }

    /** Correct fiscal identity fields through the fiscal authority (applied when the correction succeeds). */
    @Transactional
    public Map<String, Object> fiscalCorrect(String id, Map<String, Object> in) {
        unknownFields(in, Set.of("customerName", "customerTin", "customerAddress", "reason"));
        Map<String, Object> s = lock(id);
        requireProcessing(s, "returned_for_correction");
        if (!"issued".equals(s.get("fiscalStatus"))) {
            throw ApiException.badRequest("No issued fiscal document to correct; amend the submission instead");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String k : CashSaleFiscalService.IDENTITY) {
            if (in.containsKey(k)) {
                payload.put(k, blank(str(in.get(k))) ? null : str(in.get(k)).trim());
            }
        }
        if (payload.isEmpty()) {
            throw ApiException.badRequest("Nothing to correct");
        }
        fiscal.request(s, "correct", str(s.get("currentInvoiceId")), payload, str(in.get("reason")));
        return get(id);
    }

    /** Retry the open fiscal operations of a submission now (each attempt commits on its own). */
    @Transactional
    public Map<String, Object> fiscalRetry(String id) {
        require(id);
        fiscal.retry(id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "under_review");
        if (segregated(s) && (Objects.equals(str(s.get("confirmedById")), user())
                || Objects.equals(str(s.get("submittedById")), user()))) {
            throw ApiException.forbidden("Segregation of duties: the confirmer or submitter cannot approve the sale");
        }
        return processing(id, s, "approve", "approved", Map.of("approvedAt", Instant.now(), "approvedById", user()), null,
                DomainEvents.SALES_CASH_SALE_APPROVED);
    }

    /** Reject: no GL or stock change; the sale joins the head-office exception queue, owned by the rejecting user. */
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "under_review");
        requireReason(reason);
        Map<String, Object> out = processing(id, s, "reject", "rejected", Map.of("rejectedAt", Instant.now(),
                "rejectedById", user(), "rejectionReason", reason, "ownerUserId", user()), reason,
                DomainEvents.SALES_CASH_SALE_REJECTED);
        notify(str(s.get("submittedById")), "Cash sale " + s.get("number") + " rejected", reason, id);
        return out;
    }

    @Transactional
    public Map<String, Object> reopen(String id, String reason) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "rejected");
        return processing(id, s, "reopen", "under_review", Map.of("reviewStartedAt", Instant.now(), "reviewStartedById",
                user()), reason, null);
    }

    /** Hand a rejected sale to another head-office user. */
    @Transactional
    public Map<String, Object> assign(String id, String ownerUserId) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "rejected");
        if (blank(ownerUserId) || db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL", List.of(ownerUserId, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        db.patch("SalesSubmission", id, Obj.of("ownerUserId", ownerUserId), true);
        event(id, "assign", null, null, null, null, "owner " + ownerUserId);
        return get(id);
    }

    // ------------------------------------------------------------------ invoice (P4 + P5) and its cancel (P7)

    /**
     * Generate the official invoice of an approved submission (separate from approval): a draft from the frozen lines,
     * stamped with the submission's branch, posted as a reclassification of the accrual to AR, then settled by the
     * submission's deposits. Exactly one active invoice per submission (row lock + unique index).
     */
    @Transactional
    public Map<String, Object> generateInvoice(String id) {
        Map<String, Object> s = lock(id);
        requireProcessing(s, "approved");
        requireExecution(s, "confirmed");
        if (segregated(s) && (Objects.equals(str(s.get("confirmedById")), user())
                || Objects.equals(str(s.get("approvedById")), user()))) {
            throw ApiException.forbidden("Segregation of duties: the confirmer or approver cannot invoice the sale");
        }
        if (db.count("SELECT count(*) FROM \"SalesSubmissionInvoice\" WHERE \"submissionId\" = ? AND status = 'active'",
                List.of(id)) > 0) {
            throw ApiException.conflict("This cash sale already has an active invoice");
        }
        String number = str(s.get("number"));
        List<LineInput> lines = new ArrayList<>();
        for (Map<String, Object> l : lines(id)) {
            lines.add(new LineInput(str(l.get("productId")), null, null, null, null, str(l.get("description")),
                    dec(l.get("quantity")), dec(l.get("unitPrice")), dec(l.get("discountPercent")), null, null, null,
                    "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")), str(l.get("uomId"))));
        }
        String issue = (s.get("saleDate") instanceof LocalDate d ? d : LocalDate.now()).toString();
        Map<String, Object> doc = invoices.createForCashSale(new InvoiceInput(str(s.get("partnerId")), issue, issue, null,
                "cash", null, null, str(s.get("salespersonId")), issue, str(s.get("customerAddress")), null, null, number,
                null, null, number, "Cash sale " + number + (s.get("customerName") == null ? "" : " — "
                        + s.get("customerName")), lines), str(s.get("branchId")), id, number,
                str(s.get("accrualJournalEntryId")));
        String docId = str(doc.get("id"));
        invoices.post(docId);
        BigDecimal residual = dec(db.row("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?", docId)
                .get("amountResidual"));
        for (Map<String, Object> dep : deposits.cashSaleDeposits(id)) {
            BigDecimal available = dec(dep.get("available"));
            if (residual.signum() <= 0 || available.signum() <= 0 || !"open".equals(dep.get("status"))) {
                continue;
            }
            BigDecimal amount = available.min(residual);
            deposits.apply(str(dep.get("id")), docId, amount);
            residual = residual.subtract(amount);
        }
        int attempt = (int) db.count("SELECT count(*) FROM \"SalesSubmissionInvoice\" WHERE \"submissionId\" = ?",
                List.of(id)) + 1;
        try {
            db.insert("SalesSubmissionInvoice", Obj.of("submissionId", id, "attempt", attempt, "invoiceId", docId,
                    "createdById", user()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This cash sale already has an active invoice");
        }
        Map<String, Object> out = processing(id, s, "generate_invoice", "invoiced", Map.of("invoicedAt", Instant.now(),
                "invoicedById", user(), "currentInvoiceId", docId), null, DomainEvents.SALES_CASH_SALE_INVOICED);
        Map<String, Object> profile = profiles.get(str(s.get("profileId")));
        if ("at_invoice".equals(str(profile.get("fiscalTrigger")))) {
            fiscal.request(db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ?", id), "issue", docId,
                    fiscalPayload(id), null);
            out = get(id);
        }
        notify(str(s.get("submittedById")), "Cash sale " + number + " invoiced", "Invoice " + doc.get("documentNumber"),
                id);
        return out;
    }

    /**
     * Cancel the active invoice (P7): fiscal gate first, then — in one transaction — unapply the deposits, cancel the
     * invoice (reverses P4) and send the submission back to {@code approved}. The cancelled attempt stays linked; a
     * replacement invoice reuses the original accrual.
     */
    @Transactional
    public Map<String, Object> cancelInvoice(String id, String reason) {
        requireReason(reason);
        Map<String, Object> current = require(id);
        requireProcessing(current, "invoiced");
        fiscal.gateReversal(id, reason);
        Map<String, Object> s = lock(id);
        requireProcessing(s, "invoiced");
        String docId = str(s.get("currentInvoiceId"));
        deposits.unapplyCashSale(id, docId, "Invoice cancelled: " + reason);
        invoices.cancel(docId);
        db.update("UPDATE \"SalesSubmissionInvoice\" SET status = 'cancelled', \"cancelledAt\" = " + NOW + ","
                + " \"cancelledById\" = ?, \"cancelReason\" = ? WHERE \"submissionId\" = ? AND \"invoiceId\" = ? AND status ="
                + " 'active'", user(), reason, id, docId);
        resetFiscalAfterCancel(id);
        return processing(id, s, "cancel_invoice", "approved", Map.of("invoicedAt", "", "currentInvoiceId", ""), reason,
                DomainEvents.SALES_CASH_SALE_INVOICE_CANCELLED);
    }

    // ------------------------------------------------------------------ void (P6)

    /**
     * Void a confirmed, not invoiced sale whose goods physically came back (authorised; reason + attestation): the
     * accrual is reversed, the goods return at their issue cost, and each deposit is refunded or kept as a documented
     * customer advance ({@code depositAction} {@code refund} | {@code keep}).
     */
    @Transactional
    public Map<String, Object> voidSale(String id, Map<String, Object> in) {
        unknownFields(in, Set.of("reason", "goodsReturned", "depositAction"));
        String reason = str(in.get("reason"));
        requireReason(reason);
        if (!Boolean.TRUE.equals(in.get("goodsReturned"))) {
            throw ApiException.badRequest("Void is only for goods physically returned to stock: confirm goodsReturned;"
                    + " otherwise record a sales return");
        }
        String action = blank(str(in.get("depositAction"))) ? "refund" : str(in.get("depositAction"));
        if (!Set.of("refund", "keep").contains(action)) {
            throw ApiException.badRequest("depositAction must be one of: keep, refund");
        }
        Map<String, Object> current = require(id);
        requireExecution(current, "confirmed");
        if ("invoiced".equals(current.get("processingStatus"))) {
            throw ApiException.badRequest("An invoiced cash sale is reversed by cancelling its invoice first");
        }
        fiscal.gateReversal(id, reason);
        Map<String, Object> s = lock(id);
        requireExecution(s, "confirmed");
        String number = str(s.get("number"));
        recognition.reverseAccrual(str(s.get("accrualJournalEntryId")), "Void of cash sale " + number + ": " + reason);
        for (Map<String, Object> issue : db.rows("SELECT \"productId\", \"variantId\", \"locationId\", \"quantityChange\","
                + " \"unitCost\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" = 'cash_sale' AND"
                + " \"referenceId\" = ? AND \"quantityChange\" < 0 ORDER BY \"createdAt\", id", org(), id)) {
            stock.receiveReturn(StockMove.of(str(issue.get("productId")), str(issue.get("locationId")),
                    dec(issue.get("quantityChange")).negate()).variant(str(issue.get("variantId")))
                    .unitCost(dec(issue.get("unitCost"))).source("cash_sale_void", id).reference(number)
                    .notes("Goods returned on void of cash sale " + number));
        }
        for (Map<String, Object> dep : deposits.cashSaleDeposits(id)) {
            if (!"open".equals(dep.get("status")) || !"cash_sale".equals(dep.get("source"))) {
                continue;
            }
            if ("refund".equals(action)) {
                deposits.refundCashSale(str(dep.get("id")), "Void of cash sale " + number + ": " + reason);
            } else {
                deposits.releaseCashSale(str(dep.get("id")), "Void of cash sale " + number + ": " + reason);
            }
        }
        String fromProcessing = str(s.get("processingStatus"));
        db.update("UPDATE \"SalesSubmission\" SET \"executionStatus\" = 'voided', \"voidedAt\" = " + NOW + ","
                + " \"voidedById\" = ?, \"voidReason\" = ?, version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ?",
                user(), reason, id);
        event(id, "void", "confirmed", "voided", fromProcessing, fromProcessing, reason + " (deposits: " + action + ")");
        publish(DomainEvents.SALES_CASH_SALE_VOIDED, id, reason);
        audit.record(AuditInput.of("SalesSubmission", id, "cancel", null, Obj.of("void", number, "reason", reason)));
        return get(id);
    }

    /** After a successful fiscal cancel of an invoice, a replacement invoice may be fiscalised again. */
    private void resetFiscalAfterCancel(String id) {
        db.update("UPDATE \"SalesSubmission\" SET \"fiscalStatus\" = CASE WHEN \"fiscalStatus\" = 'cancelled' THEN 'none'"
                + " ELSE \"fiscalStatus\" END WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ rejected-sale alerts (job)

    /** Publish one overdue event per rejected sale older than its branch's alert threshold. */
    @Transactional
    public int alertOverdueRejections() {
        int n = 0;
        for (Map<String, Object> s : db.rows("SELECT s.id, s.\"ownerUserId\", s.number FROM \"SalesSubmission\" s JOIN"
                + " \"BranchSalesProfile\" p ON p.id = s.\"profileId\" WHERE s.\"organizationId\" = ? AND"
                + " s.\"processingStatus\" = 'rejected' AND s.\"executionStatus\" = 'confirmed' AND s.\"rejectedAt\" <"
                + " now() - make_interval(days => p.\"rejectedAlertDays\") AND NOT EXISTS (SELECT 1 FROM"
                + " \"SalesSubmissionEvent\" e WHERE e.\"submissionId\" = s.id AND e.action = 'overdue_alert' AND"
                + " e.\"createdAt\" > s.\"rejectedAt\")", org())) {
            String id = str(s.get("id"));
            event(id, "overdue_alert", null, null, null, null, null);
            publish(DomainEvents.SALES_CASH_SALE_REJECTED_OVERDUE, id, null);
            notify(str(s.get("ownerUserId")), "Rejected cash sale " + s.get("number") + " is still unresolved",
                    "Revenue, stock and the customer's payment stay posted until it is reopened or returned.", id);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> processing(String id, Map<String, Object> s, String action, String to,
            Map<String, Object> extra, String reason, String eventName) {
        String from = str(s.get("processingStatus"));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("processingStatus", to);
        extra.forEach((k, v) -> patch.put(k, "".equals(v) ? null : v));
        patch.put("version", ((Number) s.get("version")).intValue() + 1);
        db.patch("SalesSubmission", id, patch, true);
        event(id, action, null, null, from, to, reason);
        if (eventName != null) {
            publish(eventName, id, reason);
        }
        return get(id);
    }

    private void event(String id, String action, String fromEx, String toEx, String fromPr, String toPr, String reason) {
        db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action, \"fromExecution\","
                + " \"toExecution\", \"fromProcessing\", \"toProcessing\", reason, \"userId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?)", SalesDb.newId(), org(), id, action, fromEx, toEx, fromPr, toPr, reason, user());
    }

    private void publish(String event, String id, String reason) {
        Map<String, Object> s = db.row("SELECT number, \"branchId\", \"partnerId\", \"grandTotal\", \"currentInvoiceId\""
                + " FROM \"SalesSubmission\" WHERE id = ?", id);
        Map<String, Object> payload = Obj.of("v", 1, "organizationId", org(), "submissionId", id, "number",
                s.get("number"), "branchId", s.get("branchId"), "partnerId", s.get("partnerId"), "grandTotal",
                s.get("grandTotal"), "invoiceId", s.get("currentInvoiceId"), "reason", reason);
        events.publish(event, payload);
    }

    private void notify(String userId, String title, String body, String id) {
        if (userId == null || Objects.equals(userId, user())) {
            return;
        }
        try {
            notifications.send(new NotificationsService.SendInput(org(), userId, "in_app", "sales", title, body,
                    Obj.of("link", "/sales/cash-sales/" + id)));
        } catch (RuntimeException ignored) {
            // a notification never blocks the workflow
        }
    }

    private Map<String, Object> fiscalPayload(String id) {
        Map<String, Object> s = db.row("SELECT number, \"customerName\", \"customerTin\", \"customerAddress\", \"grandTotal\","
                + " \"taxTotal\", \"currencyCode\", \"saleDate\" FROM \"SalesSubmission\" WHERE id = ?", id);
        Map<String, Object> p = new LinkedHashMap<>();
        s.forEach((k, v) -> p.put(k, v == null ? null : String.valueOf(v)));
        p.put("lines", EntityJson.toJson(db.rows("SELECT description, quantity, \"unitPrice\", \"lineTax\", \"lineTotal\""
                + " FROM \"SalesSubmissionLine\" WHERE \"submissionId\" = ? ORDER BY \"sortOrder\"", id)));
        return p;
    }

    private boolean segregated(Map<String, Object> s) {
        Map<String, Object> p = db.row("SELECT \"requireSegregation\" FROM \"BranchSalesProfile\" WHERE id = ?",
                s.get("profileId"));
        return p != null && Boolean.TRUE.equals(p.get("requireSegregation"));
    }

    private static void requireDraft(Map<String, Object> s) {
        if (!"draft".equals(s.get("executionStatus"))) {
            throw ApiException.badRequest("Only a draft cash sale can be edited; financial fields are frozen after"
                    + " confirmation");
        }
    }

    private static void requireExecution(Map<String, Object> s, String status) {
        if (!status.equals(s.get("executionStatus"))) {
            throw ApiException.badRequest("Cash sale " + s.get("number") + " is " + s.get("executionStatus"));
        }
    }

    private static void requireProcessing(Map<String, Object> s, String status) {
        if (!status.equals(s.get("processingStatus"))) {
            throw ApiException.badRequest("Cash sale " + s.get("number") + " is " + str(s.get("processingStatus"))
                    .replace('_', ' '));
        }
    }

    private static void requireReason(String reason) {
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
    }

    private static void unknownFields(Map<String, Object> in, Set<String> allowed) {
        for (String k : in.keySet()) {
            if (!allowed.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
    }

    /**
     * Visibility: head office (read_all) sees every branch; a branch-restricted user sees their branches; an
     * unrestricted user without read_all sees their active branch (or their own submissions when they have none).
     */
    private void visibility(StringBuilder where, List<Object> params) {
        if (TenantContext.has(Permissions.SALES__CASH_SALE_READ_ALL)) {
            return;
        }
        DataScope scope = scopes.scope();
        if (scope.restricted()) {
            List<String> branches = List.copyOf(scope.branchIds());
            if (branches.isEmpty()) {
                where.append(" AND false");
                return;
            }
            where.append(" AND s.\"branchId\" IN (").append(SalesDb.marks(branches.size())).append(')');
            params.addAll(branches);
            return;
        }
        String active = scopes.branchFilter();
        if (active != null) {
            where.append(" AND s.\"branchId\" = ?");
            params.add(active);
        } else {
            where.append(" AND s.\"createdById\" = ?");
            params.add(user());
        }
    }

    Map<String, Object> require(String id) {
        StringBuilder where = new StringBuilder(" WHERE s.id = ? AND s.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(id, org()));
        visibility(where, params);
        Map<String, Object> s = db.row(SELECT + where, params.toArray());
        if (s == null) {
            throw ApiException.notFound("Cash sale not found");
        }
        return s;
    }

    private Map<String, Object> lock(String id) {
        require(id);
        return db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
    }

    private String nextNumber(String branchId, String prefix, int year) {
        return sequence.next("css:" + branchId + ":" + year, "CSS-" + prefix + "-" + year + "-", 5);
    }

    private String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }
}
