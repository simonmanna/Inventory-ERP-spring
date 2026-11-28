package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.SourceRef;
import com.inventoryerp.core.application.ProductLifecycleGuard;
import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.orders.application.OrdersService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales quotations (ADR-S011, moved from the CRM suite of ADR-S010 in phase S0; same ids, numbers and JSON). Lines
 * are priced through {@link QuotationPricingPort} (CRM customer price rules until S2) and taxed
 * by the invoicing tax engine. Lifecycle: {@code draft → (pending_approval → approved) → sent → accepted →
 * converted}, with {@code rejected} (internal approval), {@code declined}-as-{@code rejected} by the customer,
 * {@code expired} and {@code cancelled}. Submitting checks the discount threshold, the amount threshold and the
 * customer's credit (hold, limit vs. open receivables + this quote); any breach needs approval through the kernel
 * approval engine when a workflow exists, else by a {@code crm:quotation:approve} holder. An accepted quote converts
 * into a back-office Order or a proforma invoice; the opportunity can be marked won automatically.
 */
@Service
public class SalesQuotationService {

    /** Approval entity of quotations; the legacy CRM name is kept until S2 rebuilds the approval rules. */
    public static final String APPROVAL_ENTITY = "crm_quotation";
    /** Entity type accepted from S2 on. */
    public static final String SALES_APPROVAL_ENTITY = "sales_quotation";
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> EDITABLE = Set.of("draft", "rejected");
    static final String DECLINED_LOCKED = "The customer declined this quotation; revise it to make a new offer";
    static final Set<String> DISPLAY_TYPES = Set.of("line_section", "line_note");

    private static final Logger log = LoggerFactory.getLogger(SalesQuotationService.class);

    private final SalesDb db;
    private final SalesSettingsService config;
    private final ObjectProvider<QuotationPricingPort> pricing;
    private final ObjectProvider<QuotationOpportunityPort> opportunities;
    private final ObjectProvider<SalesOrderService> salesOrders;
    private final SalesPricingEngine engine;
    private final CreditService creditChecks;
    private final DocumentBuilderService builder;
    private final OrdersService orders;
    private final ApprovalsService approvals;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final ProductVariantService variants;
    private final ObjectProvider<ProductLifecycleGuard> lifecycleGuard;
    private ProductSizingService sizing;

    /** Sell by size (ADR-S066). */
    @org.springframework.beans.factory.annotation.Autowired
    void setSizing(ProductSizingService sizing) {
        this.sizing = sizing;
    }
    private SalesAccess access;
    private SalesAlerts alerts;
    private com.inventoryerp.kernel.notifications.NotificationsService mail;
    private SalesLostReasonService lostReasons;
    private SalesProformaService proformas;

    private SalesProductImages images;
    private org.springframework.transaction.support.TransactionTemplate tx;
    private org.springframework.transaction.support.TransactionTemplate readTx;

    /** Email (audit fix 4): the PDF is read in one transaction, mailed outside any, and the quote marked sent after. */
    @org.springframework.beans.factory.annotation.Autowired
    void setTransactions(org.springframework.transaction.PlatformTransactionManager transactions) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.readTx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
    }

    /** Product images on quotation lines (sales setting {@code quoteLineImages}). */
    @org.springframework.beans.factory.annotation.Autowired
    void setImages(SalesProductImages images) {
        this.images = images;
    }

    private SalesLetterhead letterhead;

    /** Company name, contact lines and logo printed on quotations. */
    @org.springframework.beans.factory.annotation.Autowired
    void setLetterhead(SalesLetterhead letterhead) {
        this.letterhead = letterhead;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setCollaborators(SalesAlerts alerts, com.inventoryerp.kernel.notifications.NotificationsService mail,
            SalesLostReasonService lostReasons, @org.springframework.context.annotation.Lazy SalesProformaService proformas) {
        this.alerts = alerts;
        this.mail = mail;
        this.lostReasons = lostReasons;
        this.proformas = proformas;
    }

    public SalesQuotationService(SalesDb db, SalesSettingsService config, ObjectProvider<QuotationPricingPort> pricing,
            ObjectProvider<QuotationOpportunityPort> opportunities, DocumentBuilderService builder,
            OrdersService orders, ObjectProvider<SalesOrderService> salesOrders, SalesPricingEngine engine,
            CreditService creditChecks,
            ApprovalsService approvals, SequenceService sequence, AuditService audit, EventBus events,
            ProductVariantService variants, ObjectProvider<ProductLifecycleGuard> lifecycleGuard) {
        this.variants = variants;
        this.lifecycleGuard = lifecycleGuard;
        this.db = db;
        this.config = config;
        this.pricing = pricing;
        this.opportunities = opportunities;
        this.salesOrders = salesOrders;
        this.engine = engine;
        this.creditChecks = creditChecks;
        this.builder = builder;
        this.orders = orders;
        this.approvals = approvals;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    private static final String SELECT = """
            SELECT q.*, p.name AS "partnerName", d.name AS "dealName",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "ownerName"
              FROM "SalesQuotation" q
              LEFT JOIN "Partner" p ON p.id = q."partnerId"
              LEFT JOIN "Deal" d ON d.id = q."dealId"
              LEFT JOIN "User" u ON u.id = q."ownerId"
            """;

    // ------------------------------------------------------------------ queries

    /**
     * Paginated list {@code {data, meta, totals}}. Overdue quotes expire on read, and their owners are told exactly as
     * the daily job tells them: whichever runs first expires (and notifies) a quote once, so opening the list after
     * midnight no longer swallows the expiry notification.
     */
    @Transactional
    public Map<String, Object> list(Map<String, String> p) {
        expireAndNotify();
        StringBuilder where = new StringBuilder(" WHERE q.\"organizationId\" = ? AND q.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        for (String k : List.of("status", "partnerId", "dealId", "ownerId")) {
            if (!blank(p.get(k))) {
                if ("status".equals(k)) {
                    List<String> st = List.of(p.get(k).split(","));
                    where.append(" AND q.status IN (").append(SalesDb.marks(st.size())).append(')');
                    params.addAll(st);
                } else {
                    where.append(" AND q.\"").append(k).append("\" = ?");
                    params.add(p.get(k));
                }
            }
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (q.number ILIKE ? OR p.name ILIKE ? OR d.name ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        if (!blank(p.get("from"))) {
            where.append(" AND q.\"issueDate\" >= ?");
            params.add(SalesDb.date(p.get("from")));
        }
        if (!blank(p.get("to"))) {
            where.append(" AND q.\"issueDate\" <= ?");
            params.add(SalesDb.date(p.get("to")));
        }
        if (!blank(p.get("expiringWithin"))) {
            // Expiry follow-up: open offers (approved / sent) whose validity ends within N days, today included.
            int days = Math.max(0, Math.min(365, SalesDb.parseInt(p.get("expiringWithin"), 7)));
            where.append(" AND q.status IN ('approved', 'sent') AND q.\"validUntil\" >= ? AND q.\"validUntil\" <= ?");
            params.add(java.sql.Date.valueOf(today()));
            params.add(java.sql.Date.valueOf(today().plusDays(days)));
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY q.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(q.total), 0) AS total FROM"
                + " \"SalesQuotation\" q LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" LEFT JOIN \"Deal\" d ON d.id ="
                + " q.\"dealId\"" + where, params.toArray());
        long total = ((Number) totals.get("count")).longValue();
        // Audit fix 10: one grand total per currency (the single "total" adds currencies together; kept for old clients).
        totals.put("byCurrency", db.rows("SELECT q.\"currencyCode\", count(*) AS count, COALESCE(sum(q.total), 0) AS total"
                + " FROM \"SalesQuotation\" q LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" LEFT JOIN \"Deal\" d ON d.id ="
                + " q.\"dealId\"" + where + " GROUP BY q.\"currencyCode\" ORDER BY q.\"currencyCode\"", params.toArray()));
        for (Map<String, Object> r : rows) {
            r.put("validityDaysLeft", daysLeft(r.get("validUntil")));
        }
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Quote with lines (+ stock availability), customer credit position and approval state. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> q = require(id);
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\", (SELECT v.name FROM"
                + " \"ProductVariant\" v WHERE v.id = l.\"variantId\") AS \"variantName\","
                + " pr.\"trackInventory\", pr.\"productType\"::text AS \"productType\", pr.\"sizingMode\","
                + " pr.\"sizingUnit\", pr.image AS \"productImage\", t.name AS \"taxName\","
                + " t.rate AS \"taxRate\" FROM \"SalesQuotationLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\""
                + " LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"quotationId\" = ? ORDER BY l.\"sortOrder\"", id);
        // Product images only when the organization turned them on; a fresh signed URL per line.
        boolean withImages = config.quoteLineImages();
        for (Map<String, Object> l : lines) {
            l.put("productImage", withImages ? images.url(str(l.get("productImage"))) : null);
        }
        Map<String, Map<String, Object>> stock = stock(lines.stream().map(l -> str(l.get("productId")))
                .filter(s -> s != null).distinct().toList());
        for (Map<String, Object> l : lines) {
            Map<String, Object> s = stock.get(str(l.get("productId")));
            if (s != null && Boolean.TRUE.equals(l.get("trackInventory"))) {
                double available = ((Number) s.get("available")).doubleValue();
                double qty = ((Number) l.get("quantity")).doubleValue();
                l.put("stock", Obj.of("onHand", s.get("onHand"), "reserved", s.get("reserved"), "available",
                        s.get("available"), "sufficient", available >= qty));
            } else {
                l.put("stock", null);
            }
        }
        q.put("lines", lines);
        q.put("lineImages", withImages);
        q.put("credit", credit(str(q.get("partnerId")), BigDecimal.ZERO));
        q.put("canApprove", canApprove());
        // Audit fix 9 / expiry follow-up: days of validity left on the business calendar (negative = past its date).
        q.put("validityDaysLeft", daysLeft(q.get("validUntil")));
        return q;
    }

    /** Available-to-promise per product (all locations): on hand − active reservations. */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> stock(List<String> productIds) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        List<Object> params = new ArrayList<>(List.of(org(), org()));
        params.addAll(productIds);
        for (Map<String, Object> r : db.rows("SELECT pid AS \"productId\", COALESCE(oh.qty, 0) AS \"onHand\","
                + " COALESCE(rs.qty, 0) AS reserved, COALESCE(oh.qty, 0) - COALESCE(rs.qty, 0) AS available FROM"
                + " unnest(?::text[]) pid LEFT JOIN (SELECT \"productId\", sum(quantity) AS qty FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? GROUP BY 1) oh ON oh.\"productId\" = pid LEFT JOIN (SELECT \"productId\","
                + " sum(quantity) AS qty FROM \"StockReservation\" WHERE \"organizationId\" = ? AND status = 'active' GROUP"
                + " BY 1) rs ON rs.\"productId\" = pid", productIds.toArray(String[]::new), org(), org())) {
            out.put(str(r.get("productId")), r);
        }
        return out;
    }

    /** Customer credit position: limit, hold, open receivables (posted, unpaid sales invoices) and headroom. */
    @Transactional(readOnly = true)
    public Map<String, Object> credit(String partnerId, BigDecimal extra) {
        Map<String, Object> p = db.row("SELECT \"creditLimit\", \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", partnerId, org());
        if (p == null) {
            return null;
        }
        Map<String, Object> ar = db.row("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open, COALESCE(sum("
                + "\"amountResidual\") FILTER (WHERE \"dueDate\" < now()), 0) AS overdue FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text = 'sales_invoice' AND"
                + " status::text NOT IN ('draft', 'cancelled', 'rejected') AND \"amountResidual\" > 0", org(), partnerId);
        BigDecimal limit = dec(p.get("creditLimit"));
        BigDecimal open = dec(ar.get("open"));
        BigDecimal exposure = open.add(extra);
        boolean exceeded = limit.signum() > 0 && exposure.compareTo(limit) > 0;
        return Obj.of("creditLimit", JsNumbers.js(limit), "creditHold", Boolean.TRUE.equals(p.get("creditHold")),
                "openReceivables", JsNumbers.js(open), "overdueReceivables", ar.get("overdue"), "headroom",
                limit.signum() > 0 ? JsNumbers.js(limit.subtract(open)) : null, "exceeded", exceeded);
    }

    // ------------------------------------------------------------------ create / edit

    /** New draft quote; lines without a price are priced by the customer's price rules. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String dealId = str(in.get("dealId"));
        Map<String, Object> deal = blank(dealId) ? null : opportunity(dealId);
        String partnerId = !blank(str(in.get("partnerId"))) ? str(in.get("partnerId"))
                : deal == null ? null : str(deal.get("partnerId"));
        if (blank(partnerId)) {
            throw ApiException.badRequest("A quotation needs a customer; convert the lead or set the customer first");
        }
        db.requireActivePartner(partnerId);
        Map<String, Object> settings = config.settings();
        LocalDate issue = in.get("issueDate") == null ? today() : SalesDb.date(str(in.get("issueDate")));
        LocalDate valid = in.get("validUntil") == null
                ? issue.plusDays(((Number) settings.get("quoteValidityDays")).longValue())
                : SalesDb.date(str(in.get("validUntil")));
        int year = issue.getYear();
        String number = sequence.next("crm_quotation:" + year, "QT-" + year + "-", 5);
        String currency = !blank(str(in.get("currencyCode"))) ? str(in.get("currencyCode"))
                : deal != null ? str(deal.get("currencyCode")) : orgCurrency();
        String owner = !blank(str(in.get("ownerId"))) ? str(in.get("ownerId"))
                : deal != null && deal.get("ownerId") != null ? str(deal.get("ownerId")) : user();
        Map<String, Object> row = db.insert("SalesQuotation", Obj.of("number", number, "dealId", blank(dealId) ? null
                : dealId, "partnerId", partnerId, "contactId", blank(str(in.get("contactId"))) ? null
                : str(in.get("contactId")), "ownerId", owner, "issueDate", issue, "validUntil", valid, "currencyCode",
                currency, "notes", str(in.get("notes")), "terms", in.containsKey("terms") ? str(in.get("terms"))
                : str(settings.get("defaultQuoteTerms")), "createdById", user()));
        Map<String, Object> commercial = new LinkedHashMap<>(commercial(in));
        if (commercial.get("agreementId") == null) {
            String project = engine.projectAgreement(partnerId, str(commercial.get("siteId")), issue);
            if (project != null) {
                commercial.put("agreementId", project);
            }
        }
        commercial.put("branchId", access.documentBranch(str(commercial.get("branchId"))));
        row = db.patch("SalesQuotation", str(row.get("id")), commercial, true);
        String id = str(row.get("id"));
        writeLines(id, partnerId, issue, in.get("lines"), row);
        audit.record(AuditInput.of("SalesQuotation", id, "create", null, Obj.of("number", number, "dealId", dealId)));
        return get(id);
    }

    /** Edit header and replace lines while draft (a rejected quote returns to draft). */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> q = lock(id);
        if (!EDITABLE.contains(q.get("status"))) {
            throw ApiException.badRequest("Only draft quotations can be edited; revise it to make changes");
        }
        if (declined(q)) {
            throw ApiException.badRequest(DECLINED_LOCKED);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            if (blank(p) || !db.exists("Partner", p)) {
                throw ApiException.notFound("Partner not found");
            }
            d.put("partnerId", p);
        }
        for (String k : List.of("notes", "terms", "contactId", "currencyCode")) {
            if (in.containsKey(k)) {
                d.put(k, str(in.get(k)));
            }
        }
        if (in.containsKey("ownerId")) {
            d.put("ownerId", blank(str(in.get("ownerId"))) ? null : requireUser(str(in.get("ownerId"))));
        }
        if (in.containsKey("issueDate")) {
            d.put("issueDate", SalesDb.date(str(in.get("issueDate"))));
        }
        if (in.containsKey("validUntil")) {
            d.put("validUntil", SalesDb.date(str(in.get("validUntil"))));
            d.put("expiryRemindedAt", null);
        }
        d.putAll(commercial(in));
        if (d.containsKey("branchId")) {
            d.put("branchId", access.documentBranch(str(d.get("branchId"))));
        }
        d.put("status", "draft");
        d.put("approvalReasons", List.of());
        d.put("rejectionReason", null);
        d.put("approvalRequestId", null);
        db.patch("SalesQuotation", id, d, true);
        if (in.containsKey("lines")) {
            String partner = str(d.getOrDefault("partnerId", q.get("partnerId")));
            LocalDate issue = (LocalDate) d.getOrDefault("issueDate", q.get("issueDate"));
            Map<String, Object> scope = new LinkedHashMap<>(q);
            scope.putAll(d);
            writeLines(id, partner, issue, in.get("lines"), scope);
        }
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("fields", d.keySet(), "lines",
                in.containsKey("lines"))));
        return get(id);
    }

    /** The quotation's branch, terms and delivery details, which pass on to the order it converts into. */
    private Map<String, Object> commercial(Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("branchId", "paymentTermId", "siteId", "deliveryAddress", "internalNotes", "agreementId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (in.containsKey("commitmentDate")) {
            d.put("commitmentDate", blank(str(in.get("commitmentDate"))) ? null : SalesDb.date(str(in.get("commitmentDate"))));
        }
        if (in.containsKey("exchangeRate")) {
            BigDecimal r = dec(in.get("exchangeRate"));
            if (r.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than 0");
            }
            d.put("exchangeRate", r);
        }
        for (String k : List.of("orderDiscountPercent", "orderDiscountAmount")) {
            if (in.containsKey(k)) {
                d.put(k, in.get(k) == null || dec(in.get(k)).signum() == 0 ? null : dec(in.get(k)));
            }
        }
        if (d.get("branchId") != null && !db.exists("Branch", str(d.get("branchId")))) {
            throw ApiException.notFound("Branch not found");
        }
        if (d.get("paymentTermId") != null && !db.exists("PaymentTerm", str(d.get("paymentTermId")))) {
            throw ApiException.notFound("Payment term not found");
        }
        if (d.get("siteId") != null && !db.exists("FleetCustomerSite", str(d.get("siteId")))) {
            throw ApiException.notFound("Customer site not found");
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private void writeLines(String id, String partnerId, LocalDate on, Object raw, Map<String, Object> header) {
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        // ADR-S086: the PLM lifecycle policy may refuse selling a product (obsolete) — checked before anything is
        // written, and only for products added to the document: lines it already had stay editable.
        lifecycleGuard.ifAvailable(g -> {
            java.util.Set<Object> had = new java.util.HashSet<>();
            db.rows("SELECT DISTINCT \"productId\" FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?",
                    id, org()).forEach(r -> had.add(r.get("productId")));
            g.requireAllowed(ProductLifecycleGuard.SELL, lines.stream()
                    .map(x -> x.get("productId") instanceof String p && !had.contains(p) ? p : null).toList());
        });
        // Audit fix 13: a line sent back with its id keeps it (the rows are rewritten under the same ids), so line ids
        // stay stable across saves; an id that is not a line of this quotation is ignored and the row gets a new one.
        Set<String> known = new java.util.HashSet<>();
        db.rows("SELECT id FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?", id, org())
                .forEach(r -> known.add(str(r.get("id"))));
        Set<String> reused = new java.util.HashSet<>();
        db.update("DELETE FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?", id, org());
        List<LineInput> inputs = new ArrayList<>();
        List<Map<String, Object>> meta = new ArrayList<>();
        // Display order: an Integer is the index of a priced line in `inputs`; a Map is a section or note row.
        List<Object> layout = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            String displayType = blank(str(l.get("displayType"))) ? null : str(l.get("displayType"));
            if (displayType != null) {
                if (!DISPLAY_TYPES.contains(displayType)) {
                    throw ApiException.badRequest("displayType must be one of: line_section, line_note");
                }
                if (blank(str(l.get("description")))) {
                    throw ApiException.badRequest("description should not be empty");
                }
                layout.add(Obj.of("displayType", displayType, "description", str(l.get("description")), "id",
                        keepId(l, known, reused)));
                continue;
            }
            Integer leadTime = l.get("leadTimeDays") == null || blank(str(l.get("leadTimeDays"))) ? null
                    : dec(l.get("leadTimeDays")).intValue();
            if (leadTime != null && leadTime < 0) {
                throw ApiException.badRequest("leadTimeDays must not be less than 0");
            }
            String productId = str(l.get("productId"));
            // ADR-S066: a line sold by size gets its quantity from pieces × length [× width [× height]]
            ProductSizingService.Sizing size = sizing.fromLine(blank(productId) ? null : productId, l);
            BigDecimal qty = size != null ? size.quantity() : dec(l.get("quantity"));
            if (qty.signum() <= 0) {
                throw ApiException.badRequest("quantity must be greater than 0");
            }
            ProductVariantService.VariantRef variant = variants.resolve(blank(productId) ? null : productId,
                    str(l.get("variantId")));
            String uomId = blank(str(l.get("uomId"))) ? null : str(l.get("uomId"));
            QuotationPricingPort.Price price = blank(productId) ? null : engine.resolve(new SalesPricingEngine.Context(
                    partnerId, productId, qty, uomId, str(header.get("siteId")), str(header.get("branchId")),
                    str(header.get("currencyCode")), on, str(header.get("agreementId")), variant == null ? null
                            : variant.id())).price();
            BigDecimal list = price == null ? decOr(l.get("unitPrice"), BigDecimal.ZERO) : price.listPrice();
            BigDecimal unit;
            BigDecimal disc;
            String source;
            if (l.get("unitPrice") != null || l.get("discountPercent") != null) {
                unit = decOr(l.get("unitPrice"), price == null ? BigDecimal.ZERO : price.unitPrice());
                disc = decOr(l.get("discountPercent"), price == null ? BigDecimal.ZERO : price.discountPercent());
                boolean same = price != null && unit.compareTo(price.unitPrice()) == 0
                        && disc.compareTo(price.discountPercent()) == 0;
                source = same ? price.source() : "manual";
            } else {
                unit = price == null ? BigDecimal.ZERO : price.unitPrice();
                disc = price == null ? BigDecimal.ZERO : price.discountPercent();
                source = price == null ? "manual" : price.source();
            }
            if (unit.signum() < 0) {
                throw ApiException.badRequest("unitPrice must not be less than 0");
            }
            if (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("discountPercent must be between 0 and 100");
            }
            String description = !blank(str(l.get("description"))) ? str(l.get("description"))
                    : price != null ? price.description() : null;
            if (blank(description)) {
                throw ApiException.badRequest("description should not be empty");
            }
            String taxId = l.containsKey("taxId") ? str(l.get("taxId")) : price == null ? null : price.taxId();
            Boolean incl = l.get("taxInclusive") instanceof Boolean b ? b : price != null && price.taxInclusive();
            inputs.add(new LineInput(blank(productId) ? null : productId, null, null, null, null, description, qty, unit,
                    disc, null, null, null, "manual", blank(taxId) ? null : taxId, null, incl, uomId, null, size));
            meta.add(Obj.of("listPrice", list, "source", source, "ruleId", price == null || !"rule".equals(source)
                    ? null : price.ruleId(), "variantId", variant == null ? null : variant.id(), "uomId", uomId,
                    "leadTimeDays", leadTime, "isOptional", Boolean.TRUE.equals(l.get("isOptional")), "id",
                    keepId(l, known, reused)));
            layout.add(inputs.size() - 1);
        }
        if (inputs.isEmpty()) {
            insertLayoutRows(id, layout, List.of());
            db.update("UPDATE \"SalesQuotation\" SET subtotal = 0, \"discountTotal\" = 0, \"taxAmount\" = 0, total = 0,"
                    + " \"maxDiscountPercent\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?"
                            + " AND \"organizationId\" = ?", id, org());
            return;
        }
        // Optional products (shown to the customer, taken only when chosen) are priced like any line but stay out of
        // the totals; the order discount is sized on the lines that count and applies to a chosen option alike.
        BigDecimal orderNet = BigDecimal.ZERO;
        for (int k = 0; k < inputs.size(); k++) {
            if (!Boolean.TRUE.equals(meta.get(k).get("isOptional"))) {
                LineInput li = inputs.get(k);
                orderNet = orderNet.add(SalesDiscounts.net(li.quantity(), li.unitPrice(), li.discountPercent()));
            }
        }
        BigDecimal orderPercent = SalesDiscounts.orderPercent(header.get("orderDiscountPercent") == null ? null
                : dec(header.get("orderDiscountPercent")), header.get("orderDiscountAmount") == null ? null
                        : dec(header.get("orderDiscountAmount")), orderNet);
        if (orderPercent.signum() > 0) {
            for (int k = 0; k < inputs.size(); k++) {
                LineInput li = inputs.get(k);
                meta.get(k).put("lineDiscountPercent", li.discountPercent());
                inputs.set(k, li.withDiscountPercent(SalesDiscounts.combine(li.discountPercent(), orderPercent)));
            }
        }
        Totals totals = builder.prepareLines(inputs);
        List<LineInput> counted = new ArrayList<>();
        for (int k = 0; k < inputs.size(); k++) {
            if (!Boolean.TRUE.equals(meta.get(k).get("isOptional"))) {
                counted.add(inputs.get(k));
            }
        }
        Totals sums = counted.size() == inputs.size() ? totals : counted.isEmpty() ? null : builder.prepareLines(counted);
        BigDecimal maxDisc = BigDecimal.ZERO;
        List<Map<String, Object>> priced = new ArrayList<>();
        int i = 0;
        for (PreparedLine pl : totals.prepared()) {
            Map<String, Object> m = meta.get(i);
            BigDecimal list = (BigDecimal) m.get("listPrice");
            BigDecimal net = pl.unitPrice().multiply(BigDecimal.ONE.subtract(pl.discountPercent().divide(HUNDRED, 10,
                    RoundingMode.HALF_UP)));
            if (list.signum() > 0 && net.compareTo(list) < 0) {
                maxDisc = maxDisc.max(BigDecimal.ONE.subtract(net.divide(list, 10, RoundingMode.HALF_UP)).multiply(HUNDRED));
            }
            priced.add(Obj.of("quotationId", id, "productId", pl.productId(),
                    "variantId", m.get("variantId"), "uomId", m.get("uomId") != null && pl.sizing() == null ? m.get("uomId") : pl.uomId(),
                    "description", pl.description(), "quantity",
                    pl.quantity(), "listPrice", list, "unitPrice",
                    pl.unitPrice(), "discountPercent", pl.discountPercent(), "priceSource", m.get("source"),
                    "priceRuleId", m.get("ruleId"), "lineDiscountPercent", m.get("lineDiscountPercent"), "taxId", pl.taxId(), "taxInclusive", pl.taxInclusive(),
                    "leadTimeDays", m.get("leadTimeDays"), "lineSubtotal", pl.subtotal(), "lineTax", pl.taxAmount(),
                    "lineTotal", pl.total(), "isOptional", m.get("isOptional"), "id", m.get("id")));
            priced.getLast().putAll(ProductSizingService.Sizing.columns(pl.sizing()));
            // Phase I1b (U-1): keep the resolved factor (the column defaulted to 1 for every line).
            if (pl.uomFactor() != null) {
                priced.getLast().put("uomFactor", pl.uomFactor());
            }
            i++;
        }
        insertLayoutRows(id, layout, priced);
        db.update("UPDATE \"SalesQuotation\" SET subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?, total = ?,"
                + " \"maxDiscountPercent\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                sums == null ? BigDecimal.ZERO : sums.subtotal(), sums == null ? BigDecimal.ZERO
                        : sums.discountTotal(), sums == null ? BigDecimal.ZERO : sums.taxAmount(),
                sums == null ? BigDecimal.ZERO : sums.total(), maxDisc.setScale(4, RoundingMode.HALF_UP), id, org());
    }

    /** The line's own id when it is a line of this quotation (once); else null, so the row gets a new id. */
    private static String keepId(Map<String, Object> line, Set<String> known, Set<String> reused) {
        String id = str(line.get("id"));
        return id != null && known.contains(id) && reused.add(id) ? id : null;
    }

    /**
     * Header totals again from the stored lines at their stored prices (no re-pricing): after the customer took
     * optional products, which then count like any line.
     */
    private void retotal(String id) {
        List<LineInput> inputs = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND"
                + " \"organizationId\" = ? AND \"displayType\" IS NULL AND NOT \"isOptional\" ORDER BY \"sortOrder\"", id,
                org())) {
            inputs.add(new LineInput(str(l.get("productId")), null, str(l.get("variantId")), null, null,
                    str(l.get("description")), dec(l.get("quantity")), dec(l.get("unitPrice")),
                    dec(l.get("discountPercent")), null, null, null, "manual", str(l.get("taxId")), null,
                    Boolean.TRUE.equals(l.get("taxInclusive")), str(l.get("uomId")), null,
                    ProductSizingService.Sizing.stored(l)).withUnitSnapshot(str(l.get("uomId")), SalesDb.decOrNull(
                            l.get("uomFactor"))));
        }
        Totals t = inputs.isEmpty() ? null : builder.prepareLines(inputs);
        db.update("UPDATE \"SalesQuotation\" SET subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?, total = ?,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ?", t == null ? BigDecimal.ZERO : t.subtotal(), t == null
                        ? BigDecimal.ZERO : t.discountTotal(), t == null ? BigDecimal.ZERO : t.taxAmount(), t == null
                                ? BigDecimal.ZERO : t.total(), id, org());
    }

    /** Writes the priced lines and the section / note rows in display order; layout rows carry no amounts. */
    private void insertLayoutRows(String id, List<Object> layout, List<Map<String, Object>> priced) {
        int sort = 0;
        for (Object entry : layout) {
            Map<String, Object> row;
            if (entry instanceof Integer k) {
                row = priced.get(k);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) entry;
                row = Obj.of("quotationId", id, "displayType", l.get("displayType"), "description", l.get("description"),
                        "quantity", BigDecimal.ONE, "unitPrice", BigDecimal.ZERO, "priceSource", "manual", "id",
                        l.get("id"));
            }
            row.put("sortOrder", sort++);
            if (row.get("id") == null) {
                row.remove("id"); // insert() assigns a new id
            }
            db.insert("SalesQuotationLine", row);
        }
    }

    // ------------------------------------------------------------------ approval

    /**
     * Submit for approval. Returns the quote: {@code approved} directly when no threshold is breached, else
     * {@code pending_approval} with the reasons.
     */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> q = lock(id);
        if (!EDITABLE.contains(q.get("status"))) {
            throw ApiException.badRequest("Only draft quotations can be submitted");
        }
        if (declined(q)) {
            throw ApiException.badRequest(DECLINED_LOCKED);
        }
        if (db.count("SELECT count(*) FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"displayType\" IS NULL"
                + " AND NOT \"isOptional\"", List.of(id)) == 0) {
            throw ApiException.badRequest("Add at least one line before submitting");
        }
        // Audit fix 8: drafts no longer expire; one past its date cannot go out until it gets a new one.
        if (expired(q)) {
            throw ApiException.badRequest("The expiration date has passed; set a new one before submitting");
        }
        List<String> reasons = approvalReasons(q);
        if (reasons.isEmpty()) {
            db.patch("SalesQuotation", id, Obj.of("status", "approved", "approvalReasons", List.of(), "approvedAt",
                    java.time.Instant.now(), "approvedById", user()), true);
            audit.record(AuditInput.of("SalesQuotation", id, "approve", null, Obj.of("auto", true)));
            return get(id);
        }
        Map<String, Object> snapshot = Obj.of("number", q.get("number"), "partnerId", q.get("partnerId"), "total",
                q.get("total"), "amount", q.get("total"), "maxDiscountPercent", q.get("maxDiscountPercent"), "reasons",
                reasons);
        Map<String, Object> req = approvals.requestApproval(APPROVAL_ENTITY, id, snapshot, null);
        db.patch("SalesQuotation", id, Obj.of("status", "pending_approval", "approvalReasons", reasons,
                "approvalRequestId", req == null ? null : req.get("id"), "rejectionReason", null), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "pending_approval", "reasons",
                reasons)));
        return get(id);
    }

    /** Why this quote needs approval (empty = within policy). */
    List<String> approvalReasons(Map<String, Object> q) {
        Map<String, Object> s = config.settings();
        List<String> reasons = new ArrayList<>();
        BigDecimal maxDisc = dec(q.get("maxDiscountPercent"));
        BigDecimal limit = dec(s.get("quoteMaxDiscountPercent"));
        // Discounts granted by price rules are pre-approved: only the part beyond the resolved price counts.
        BigDecimal extra = extraDiscount(q);
        if (extra.compareTo(limit) > 0) {
            reasons.add("Discount " + extra.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + "% exceeds the " + limit.stripTrailingZeros().toPlainString() + "% limit");
        }
        BigDecimal amount = dec(s.get("quoteApprovalAmount"));
        // The customer may still take the optional products: amount and credit are checked at the largest total.
        BigDecimal total = dec(q.get("total")).add(dec(db.row("SELECT COALESCE(sum(\"lineTotal\"), 0) AS t FROM"
                + " \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"displayType\" IS NULL AND \"isOptional\"",
                str(q.get("id"))).get("t")));
        if (amount.signum() > 0 && total.compareTo(amount) > 0) {
            reasons.add("Total exceeds the approval amount of " + amount.stripTrailingZeros().toPlainString());
        }
        if (Boolean.TRUE.equals(s.get("creditCheck"))) {
            CreditService.Decision c = creditChecks.check("quote_submit", str(q.get("partnerId")), "sales_quotation",
                    str(q.get("id")), str(q.get("number")), total, str(q.get("branchId")));
            reasons.addAll(c.reasons());
        }
        if (maxDisc.signum() < 0) {
            reasons.add("Invalid discount");
        }
        if (Boolean.TRUE.equals(s.get("marginCheck"))) {
            reasons.addAll(engine.breaches(str(q.get("partnerId")), str(q.get("branchId")), (LocalDate) q.get(
                    "issueDate"), db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"displayType\""
                    + " IS NULL", str(q.get("id"))), HUNDRED, true));
        }
        return reasons;
    }

    private BigDecimal extraDiscount(Map<String, Object> q) {
        String partnerId = str(q.get("partnerId"));
        LocalDate on = (LocalDate) q.get("issueDate");
        BigDecimal max = BigDecimal.ZERO;
        // Lines repeating a product / variant / unit / quantity are authorized once (one engine call per distinct line).
        Map<List<String>, BigDecimal> resolved = new HashMap<>();
        for (Map<String, Object> l : db.rows("SELECT \"productId\", \"variantId\", \"uomId\", quantity, \"unitPrice\","
                + " \"discountPercent\", \"listPrice\" FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND"
                + " \"displayType\" IS NULL", str(q.get("id")))) {
            BigDecimal net = dec(l.get("unitPrice")).multiply(BigDecimal.ONE.subtract(dec(l.get("discountPercent"))
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP)));
            // ADR-S058: a variant line is authorized at the variant's price, not the product's. Audit S-7: the line is
            // re-priced as it was priced (its unit, the quotation's site, branch, currency and agreement), so a rule
            // or agreement price is not mistaken for a discount, nor a unit's price for one.
            BigDecimal authorized = l.get("productId") == null ? dec(l.get("unitPrice"))
                    : resolved.computeIfAbsent(java.util.Arrays.asList(str(l.get("productId")), str(l.get("variantId")),
                            str(l.get("uomId")), dec(l.get("quantity")).stripTrailingZeros().toPlainString()),
                            k -> engine.resolve(new SalesPricingEngine.Context(partnerId, str(l.get("productId")),
                                    dec(l.get("quantity")), str(l.get("uomId")), str(q.get("siteId")),
                                    str(q.get("branchId")), str(q.get("currencyCode")), on, str(q.get("agreementId")),
                                    str(l.get("variantId")))).price().net());
            if (authorized.signum() > 0 && net.compareTo(authorized) < 0) {
                max = max.max(BigDecimal.ONE.subtract(net.divide(authorized, 10, RoundingMode.HALF_UP)).multiply(HUNDRED));
            }
        }
        return max;
    }

    /** Approve (needs {@code crm:quotation:approve}); also applied by the approval engine subscriber. */
    @Transactional
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> q = lock(id);
        if (!"pending_approval".equals(q.get("status"))) {
            throw ApiException.badRequest("Quotation is not awaiting approval");
        }
        if (user() != null && user().equals(q.get("createdById")) && !TenantContext.has(Permissions.CRM__CONFIGURE)
                && !TenantContext.has(Permissions.SALES__CONFIGURE)) {
            throw ApiException.forbidden("You cannot approve your own quotation");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                java.time.Instant.now()), true);
        withdrawApproval(q, "Approved directly on the quotation");
        audit.record(AuditInput.of("SalesQuotation", id, "approve", null, Obj.of("comment", comment)));
        events.publishOnCommit(DomainEvents.CRM_QUOTATION_APPROVED, Obj.of("organizationId", org(), "quotationId", id,
                "number", q.get("number")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> q = lock(id);
        if (!"pending_approval".equals(q.get("status"))) {
            throw ApiException.badRequest("Quotation is not awaiting approval");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "rejected", "rejectedById", user(), "rejectionReason",
                blank(reason) ? "Rejected" : reason), true);
        withdrawApproval(q, "Rejected directly on the quotation");
        audit.record(AuditInput.of("SalesQuotation", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /**
     * Apply a kernel approval-engine decision (idempotent: ignored unless still pending). A decision on a request that
     * is no longer the quote's current one (the quote was revised, edited or cancelled and resubmitted since) is
     * ignored, so a late answer on an old request never approves a revision nobody reviewed.
     */
    @Transactional
    public void applyDecision(String id, String requestId, boolean approved, String comment) {
        Map<String, Object> q = db.row("SELECT status, number, \"approvalRequestId\" FROM \"SalesQuotation\" WHERE id = ?"
                + " AND \"organizationId\" = ? FOR UPDATE", id, org());
        if (q == null || !"pending_approval".equals(q.get("status"))) {
            return;
        }
        if (!blank(requestId) && !requestId.equals(str(q.get("approvalRequestId")))) {
            log.info("Ignoring decision of stale approval request {} for quotation {}", requestId, id);
            return;
        }
        db.patch("SalesQuotation", id, approved ? Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                java.time.Instant.now()) : Obj.of("status", "rejected", "rejectedById", user(), "rejectionReason",
                blank(comment) ? "Rejected in approval workflow" : comment), true);
        audit.record(AuditInput.of("SalesQuotation", id, approved ? "approve" : "reject", null, Obj.of("via",
                "approval_engine")));
        if (approved) {
            events.publishOnCommit(DomainEvents.CRM_QUOTATION_APPROVED, Obj.of("organizationId", org(), "quotationId", id,
                    "number", q.get("number")));
        }
    }

    /** Cancel the quote's still-pending engine request: the decision it asks for no longer applies. */
    private void withdrawApproval(Map<String, Object> q, String reason) {
        String requestId = str(q.get("approvalRequestId"));
        if (!blank(requestId)) {
            approvals.withdraw(requestId, reason);
        }
    }

    // ------------------------------------------------------------------ customer lifecycle

    /**
     * Mark sent to the customer (approved quotes only) and record it on the CRM timeline of the opportunity /
     * customer (audit fix 12: "Mark as sent" left no trace there; the opportunity stage is not changed).
     */
    @Transactional
    public Map<String, Object> send(String id) {
        Map<String, Object> q = markSent(id);
        QuotationOpportunityPort port = opportunities.getIfAvailable();
        if (port != null) {
            port.quotationSent(str(q.get("dealId")), str(q.get("partnerId")), str(q.get("number")));
        }
        return get(id);
    }

    private Map<String, Object> markSent(String id) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved quotations can be sent");
        }
        if (expired(q)) {
            throw ApiException.badRequest("Quotation has expired; revise it first");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "sent", "sentAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "issue", null, Obj.of("status", "sent")));
        return q;
    }

    @Transactional
    public Map<String, Object> accept(String id) {
        return accept(id, null);
    }

    /**
     * Customer acceptance. {@code optionalLineIds}: the optional products the customer took; they become ordinary
     * lines and the totals include them (unknown ids or lines that are not optional are refused).
     */
    @Transactional
    public Map<String, Object> accept(String id, List<String> optionalLineIds) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be accepted");
        }
        if (expired(q)) {
            throw ApiException.badRequest("Quotation has expired; revise it first");
        }
        List<String> chosen = optionalLineIds == null ? List.of() : optionalLineIds.stream().filter(x -> !blank(x))
                .distinct().toList();
        if (!chosen.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(id, org()));
            params.addAll(chosen);
            int n = db.update("UPDATE \"SalesQuotationLine\" SET \"isOptional\" = false WHERE \"quotationId\" = ? AND"
                    + " \"organizationId\" = ? AND \"isOptional\" AND \"displayType\" IS NULL AND id IN ("
                    + SalesDb.marks(chosen.size()) + ")", params.toArray());
            if (n != chosen.size()) {
                throw ApiException.badRequest("optionalLineIds must be optional lines of this quotation");
            }
            retotal(id);
        }
        db.patch("SalesQuotation", id, Obj.of("status", "accepted", "acceptedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, chosen.isEmpty() ? Obj.of("status", "accepted")
                : Obj.of("status", "accepted", "optionalLineIds", chosen)));
        return get(id);
    }

    /** Customer declined. */
    @Transactional
    public Map<String, Object> decline(String id, String reason) {
        return decline(id, reason, null);
    }

    /** Customer declined, with an optional catalog reason. */
    @Transactional
    public Map<String, Object> decline(String id, String reason, String lostReasonId) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be declined");
        }
        if (!blank(lostReasonId)) {
            lostReasons.assertActive(lostReasonId);
        }
        // declinedAt tells a customer decline (terms the customer saw: revise to change them) from an internal
        // approval rejection (still editable in place)
        db.patch("SalesQuotation", id, Obj.of("status", "rejected", "lostReasonId", blank(lostReasonId) ? null : lostReasonId,
                "rejectionReason", blank(reason) ? "Declined by customer" : reason, "declinedAt", java.time.Instant.now()),
                true);
        audit.record(AuditInput.of("SalesQuotation", id, "reject", null, Obj.of("byCustomer", true, "reason", reason)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> q = lock(id);
        if (Set.of("converted", "cancelled").contains(q.get("status"))) {
            throw ApiException.badRequest("Quotation cannot be cancelled");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "cancelled"), true);
        withdrawApproval(q, "Quotation cancelled");
        revokeShareLinks(id, "cancelled");
        audit.record(AuditInput.of("SalesQuotation", id, "cancel"));
        return get(id);
    }

    /** New revision back in draft (any state except converted). */
    @Transactional
    public Map<String, Object> revise(String id) {
        Map<String, Object> q = lock(id);
        if ("converted".equals(q.get("status"))) {
            throw ApiException.badRequest("A converted quotation cannot be revised");
        }
        snapshot(q, "revise");
        withdrawApproval(q, "Quotation revised");
        revokeShareLinks(id, "revised");
        Map<String, Object> s = config.settings();
        db.patch("SalesQuotation", id, Obj.of("status", "draft", "revision", ((Number) q.get("revision")).intValue() + 1,
                "approvalReasons", List.of(), "approvalRequestId", null, "approvedById", null, "approvedAt", null,
                "rejectionReason", null, "declinedAt", null, "sentAt", null, "acceptedAt", null, "expiryRemindedAt", null,
                "validUntil", today().plusDays(
                        ((Number) s.get("quoteValidityDays")).longValue())), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("revision", "new")));
        return get(id);
    }

    /**
     * Production-readiness fix P0-4: a customer link was issued for one revision's terms, so a new revision (or a
     * cancellation) stops it; the customer needs a new link for the new terms. Answered links stay as the record.
     */
    private void revokeShareLinks(String id, String why) {
        int n = db.update("UPDATE \"SalesQuoteLink\" SET \"revokedAt\" = " + NOW + " WHERE \"quotationId\" = ? AND"
                + " \"organizationId\" = ? AND \"revokedAt\" IS NULL", id, org());
        if (n > 0) {
            audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("shareLink", "revoked", "reason",
                    why)));
        }
    }

    /**
     * Rejected by the customer (not by an internal approver): the declined terms stay as they were sent, so the quote
     * is changed only through a new revision, which snapshots them first.
     */
    private static boolean declined(Map<String, Object> q) {
        return "rejected".equals(q.get("status")) && q.get("declinedAt") != null;
    }

    /** Past {@code validUntil} on the business calendar (the quote is valid through that day). */
    private static boolean expired(Map<String, Object> q) {
        return q.get("validUntil") instanceof LocalDate v && v.isBefore(today());
    }

    /** Today in the business time zone, not the JVM's UTC. */
    static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    /** Hard-delete a draft that was never submitted. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> q = lock(id);
        if (!"draft".equals(q.get("status")) || q.get("approvalRequestId") != null) {
            throw ApiException.badRequest("Only draft quotations can be deleted; cancel it instead");
        }
        db.update("UPDATE \"SalesQuotation\" SET \"deletedAt\" = " + NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?", id,
                org());
        audit.record(AuditInput.of("SalesQuotation", id, "delete"));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ conversion

    /**
     * Convert an accepted (or approved / sent, which accepts it) quote into an Order ({@code target = order}) or a
     * proforma invoice ({@code target = proforma}). Stock is re-checked: products short of stock are reported, and
     * block the conversion unless {@code allowBackorder}.
     */
    @Transactional
    public Map<String, Object> convert(String id, Map<String, Object> in) {
        Map<String, Object> q = lock(id);
        String target = blank(str(in.get("target"))) ? "order" : str(in.get("target"));
        if (!Set.of("order", "proforma", "sales_order").contains(target)) {
            throw ApiException.badRequest("target must be one of: order, proforma, sales_order");
        }
        if (!Set.of("approved", "sent", "accepted").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved, sent or accepted quotations can be converted");
        }
        if (expired(q)) {
            throw ApiException.badRequest("Quotation has expired; revise it first");
        }
        // Optional products the customer did not take never reach the order or proforma.
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.\"trackInventory\" FROM \"SalesQuotationLine\" l LEFT"
                + " JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE l.\"quotationId\" = ? AND l.\"displayType\" IS NULL"
                + " AND NOT l.\"isOptional\" ORDER BY l.\"sortOrder\"", id);
        if ("order".equals(target)) {
            List<String> shortages = shortages(lines);
            if (!shortages.isEmpty() && !Boolean.TRUE.equals(in.get("allowBackorder"))) {
                throw ApiException.withBody(org.springframework.http.HttpStatus.CONFLICT, Obj.of("statusCode", 409,
                        "message", "Insufficient stock for: " + String.join(", ", shortages), "error", "Conflict",
                        "shortages", shortages));
            }
        }
        String convertedId;
        String convertedNumber;
        if ("sales_order".equals(target)) {
            Map<String, Object> so = salesOrders.getObject().createFromQuotation(q, lines, in);
            convertedId = str(so.get("id"));
            convertedNumber = str(so.get("number"));
        } else if ("order".equals(target)) {
            List<Map<String, Object>> orderLines = new ArrayList<>();
            for (Map<String, Object> l : lines) {
                Map<String, Object> ol = new LinkedHashMap<>();
                ol.put("productId", l.get("productId"));
                // Phase I2 review N6: the quoted variant and unit carry over (Orders converts the unit to the sales unit).
                ol.put("variantId", l.get("variantId"));
                ol.put("uomId", l.get("uomId"));
                ol.put("description", l.get("description"));
                ol.put("quantity", l.get("quantity"));
                ol.put("unitPrice", l.get("unitPrice"));
                ol.put("discountPercent", l.get("discountPercent"));
                ol.put("taxId", l.get("taxId"));
                ol.put("taxInclusive", l.get("taxInclusive"));
                ol.put("note", null);
                orderLines.add(ol);
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("partnerId", q.get("partnerId"));
            dto.put("orderType", "delivery");
            // Audit fix 3: the branch and payment terms carry over; Orders has no delivery fields, so the address and
            // promised date go into the notes the order prints.
            dto.put("branchId", q.get("branchId"));
            dto.put("paymentTermId", q.get("paymentTermId"));
            StringBuilder notes = new StringBuilder("From quotation " + q.get("number"));
            if (q.get("notes") != null) {
                notes.append(" — ").append(q.get("notes"));
            }
            if (q.get("deliveryAddress") != null) {
                notes.append("\nDeliver to: ").append(q.get("deliveryAddress"));
            }
            if (q.get("commitmentDate") != null) {
                notes.append("\nDelivery date: ").append(q.get("commitmentDate"));
            }
            dto.put("notes", notes.toString());
            dto.put("lines", orderLines);
            Map<String, Object> order = orders.createOrder(dto);
            convertedId = str(order.get("id"));
            convertedNumber = str(order.get("orderNumber"));
        } else {
            List<LineInput> inputs = new ArrayList<>();
            for (Map<String, Object> l : lines) {
                // The quoted variant and unit carry over, as for the other targets: a price quoted per box stays per
                // box. The unit factor is resolved again (as Orders and sales orders do), not copied from the line.
                inputs.add(new LineInput(str(l.get("productId")), null, str(l.get("variantId")), null, null,
                        str(l.get("description")), dec(l.get("quantity")), dec(l.get("unitPrice")),
                        dec(l.get("discountPercent")), null, null, null, "manual", str(l.get("taxId")), null,
                        Boolean.TRUE.equals(l.get("taxInclusive")), str(l.get("uomId")), null,
                        ProductSizingService.Sizing.stored(l)));
            }
            // Audit fix 3: payment terms, delivery date / address, currency and rate carry over (as from a sales order).
            Map<String, Object> cur = db.row("SELECT id FROM \"Currency\" WHERE code = ?", q.get("currencyCode"));
            BigDecimal rate = dec(q.get("exchangeRate"));
            HeaderInput h = new HeaderInput(str(q.get("partnerId")), today().toString(), q.get("validUntil") == null
                    ? null : q.get("validUntil").toString(), str(q.get("paymentTermId")), null, null, null, null, null,
                    null, str(q.get("ownerId")), null, q.get("commitmentDate") == null ? null
                            : q.get("commitmentDate").toString(), str(q.get("deliveryAddress")), null, null,
                    str(q.get("number")), cur == null ? null : str(cur.get("id")), rate.signum() > 0 ? rate
                            : BigDecimal.ONE, str(q.get("number")), str(q.get("notes")), null, "crm_quotation",
                    str(q.get("branchId")));
            Map<String, Object> doc = builder.createDocument("proforma_invoice", new SourceRef.Quotation(id), h, inputs);
            convertedId = str(doc.get("id"));
            proformas.applyTerms(convertedId, in, null);
            convertedNumber = str(doc.get("documentNumber"));
        }
        db.patch("SalesQuotation", id, Obj.of("status", "converted", "acceptedAt", q.get("acceptedAt") == null
                ? java.time.Instant.now() : q.get("acceptedAt"), "convertedType", target, "convertedId", convertedId,
                "convertedNumber", convertedNumber, "convertedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "converted", "target", target,
                "convertedId", convertedId)));
        events.publishOnCommit(DomainEvents.CRM_QUOTATION_CONVERTED, Obj.of("organizationId", org(), "quotationId", id,
                "convertedType", target, "convertedId", convertedId));
        String dealId = str(q.get("dealId"));
        if (!blank(dealId) && Boolean.TRUE.equals(config.settings().get("autoWinOnConversion"))) {
            QuotationOpportunityPort port = opportunities.getIfAvailable();
            if (port != null) {
                port.markWonIfOpen(dealId, str(q.get("number")));
            }
        }
        Map<String, Object> out = get(id);
        out.put("converted", Obj.of("type", target, "id", convertedId, "number", convertedNumber));
        return out;
    }

    private List<String> shortages(List<Map<String, Object>> lines) {
        Map<String, BigDecimal> need = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null && Boolean.TRUE.equals(l.get("trackInventory"))) {
                need.merge(str(l.get("productId")), dec(l.get("quantity")), BigDecimal::add);
                names.put(str(l.get("productId")), str(l.get("description")));
            }
        }
        Map<String, Map<String, Object>> stock = stock(new ArrayList<>(need.keySet()));
        List<String> out = new ArrayList<>();
        need.forEach((pid, qty) -> {
            Map<String, Object> s = stock.get(pid);
            BigDecimal avail = s == null ? BigDecimal.ZERO : dec(s.get("available"));
            if (avail.compareTo(qty) < 0) {
                out.add(names.get(pid));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Expire quotations past {@code validUntil} on the business calendar (not the database's UTC date) and tell their
     * owners; returns how many expired. Called by the daily job and on every list read. The conditional UPDATE locks
     * each row it expires, so two concurrent callers never expire (or notify about) the same quote twice.
     */
    @Transactional
    public int expireAndNotify() {
        // Audit fix 8: only offers made to the customer expire; a draft past its date is refused on submit instead.
        List<Map<String, Object>> expired = db.rows("UPDATE \"SalesQuotation\" SET status = 'expired', \"updatedAt\" = " + NOW
                + " WHERE \"organizationId\" = ? AND status IN ('approved', 'sent') AND \"validUntil\" < ?"
                + " AND \"deletedAt\" IS NULL RETURNING id, number, \"ownerId\", \"createdById\"", org(),
                java.sql.Date.valueOf(today()));
        for (Map<String, Object> e : expired) {
            alerts.send(str(e.get("ownerId") != null ? e.get("ownerId") : e.get("createdById")), "sales_quotation_expired",
                    "Quotation " + e.get("number") + " has expired", "Revise it to send it again.", Obj.of("entityType",
                            "sales_quotation", "entityId", e.get("id")));
        }
        return expired.size();
    }

    /**
     * Expiry follow-up: tell the owner once when an open offer (approved / sent) is within the sales setting
     * {@code quoteExpiryReminderDays} of its expiration date (0 = off). A new validity date re-arms the reminder.
     */
    @Transactional
    public int remindExpiring() {
        int days = ((Number) config.settings().getOrDefault("quoteExpiryReminderDays", 0)).intValue();
        if (days <= 0) {
            return 0;
        }
        List<Map<String, Object>> due = db.rows("UPDATE \"SalesQuotation\" SET \"expiryRemindedAt\" = " + NOW
                + " WHERE \"organizationId\" = ? AND status IN ('approved', 'sent') AND \"deletedAt\" IS NULL AND"
                + " \"expiryRemindedAt\" IS NULL AND \"validUntil\" >= ? AND \"validUntil\" <= ? RETURNING id, number,"
                + " \"validUntil\", \"ownerId\", \"createdById\"", org(), java.sql.Date.valueOf(today()),
                java.sql.Date.valueOf(today().plusDays(days)));
        for (Map<String, Object> e : due) {
            alerts.send(str(e.get("ownerId") != null ? e.get("ownerId") : e.get("createdById")),
                    "sales_quotation_expiring", "Quotation " + e.get("number") + " expires on " + e.get("validUntil"),
                    "Follow up with the customer, or revise it to extend the offer.", Obj.of("entityType",
                            "sales_quotation", "entityId", e.get("id")));
        }
        return due.size();
    }

    /** Customer-facing PDF; internal notes never appear. Product images follow the sales setting. */
    @Transactional(readOnly = true)
    public byte[] pdf(String id) {
        return pdf(id, null);
    }

    /**
     * Customer-facing PDF. {@code withImages} = the printing user's Image column choice; {@code null} follows the sales
     * setting. Images never print while the setting is off.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(String id, Boolean withImages) {
        return render(require(id), config.quoteLayout(), withImages);
    }

    /**
     * Preview of a quotation layout before it is saved (Sales settings): {@code layout} (partial, validated like the
     * settings update; {@code null} = the saved layout) applied to the latest quotation this user can see, or to sample
     * lines when there is none.
     */
    @Transactional(readOnly = true)
    public byte[] layoutPreview(Object layout) {
        QuoteLayout saved = config.quoteLayout();
        QuoteLayout l = layout == null ? saved : QuoteLayout.validate(layout, saved);
        StringBuilder where = new StringBuilder(" WHERE q.\"organizationId\" = ? AND q.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        Map<String, Object> latest = db.row(SELECT + where + " ORDER BY q.\"createdAt\" DESC LIMIT 1",
                params.toArray());
        if (latest != null) {
            return render(latest, l, null);
        }
        SalesLetterhead.Head head = letterhead.company(l.companyName());
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(new HashMap<>(Map.of("displayType", "line_section", "description", "Sample section")));
        lines.add(new HashMap<>(Map.of("description", "Sample product", "quantity", 2, "unit", "pc", "unitPrice", 50000,
                "discountPercent", 0, "total", 100000)));
        lines.add(new HashMap<>(Map.of("description", "Sample service", "quantity", 1, "unit", "h", "unitPrice", 25000,
                "discountPercent", 10, "total", 22500)));
        return SalesQuotationPdf.render(new SalesQuotationPdf.Quote(new SalesQuotationPdf.Company(head.name(),
                head.lines(), head.logo()), "QT-SAMPLE", List.of(new SalesQuotationPdf.Fact("Customer",
                        "Sample Customer Ltd"), new SalesQuotationPdf.Fact("Date", java.time.LocalDate.now(BusinessZone.get()).toString())),
                lines, orgCurrency(), List.of(new SalesDocumentPdf.Total("Untaxed", new BigDecimal("122500")),
                        new SalesDocumentPdf.Total("Tax", BigDecimal.ZERO), new SalesDocumentPdf.Total("Total",
                                new BigDecimal("122500"))), "Sample notes.", "Sample terms.", false), l);
    }

    /** The customer PDF of quotation row {@code q} in {@code layout}. */
    private byte[] render(Map<String, Object> q, QuoteLayout layout, Boolean withImages) {
        boolean showImages = config.quoteLineImages() && !Boolean.FALSE.equals(withImages);
        List<Map<String, Object>> lines = db.rows("SELECT l.\"displayType\", l.\"isOptional\" AS optional, l.description,"
                + " l.quantity, l.\"unitPrice\","
                + " l.\"discountPercent\", l.\"lineTotal\" AS total, l.pieces, l.length, l.width, l.height,"
                + " p.\"sizingUnit\", p.image, COALESCE(NULLIF(u.symbol, ''), u.code) AS unit, t.name AS tax FROM"
                + " \"SalesQuotationLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u"
                + " ON u.id = l.\"uomId\" LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"quotationId\" = ? AND"
                + " l.\"organizationId\" = ? ORDER BY l.\"sortOrder\"", str(q.get("id")), org());
        // Optional products print, marked as such; the totals below never include them.
        for (Map<String, Object> l : lines) {
            if (Boolean.TRUE.equals(l.remove("optional"))) {
                l.put("description", "Optional: " + l.get("description"));
            }
        }
        if (showImages) {
            Map<String, byte[]> loaded = new HashMap<>();
            for (Map<String, Object> l : lines) {
                String stored = str(l.get("image"));
                if (stored != null && l.get("displayType") == null) {
                    l.put("imageBytes", loaded.computeIfAbsent(stored, images::bytes));
                }
            }
        }
        SalesLetterhead.Head head = letterhead.company(layout.companyName());
        List<SalesQuotationPdf.Fact> facts = new ArrayList<>();
        facts.add(new SalesQuotationPdf.Fact("Customer", str(q.get("partnerName"))));
        facts.add(new SalesQuotationPdf.Fact("Date", str(q.get("issueDate"))));
        facts.add(new SalesQuotationPdf.Fact("Valid until", str(q.get("validUntil"))));
        if (layout.showSalesperson()) {
            facts.add(new SalesQuotationPdf.Fact("Salesperson", str(q.get("ownerName"))));
        }
        return SalesQuotationPdf.render(new SalesQuotationPdf.Quote(new SalesQuotationPdf.Company(head.name(),
                head.lines(), head.logo()), str(q.get("number")), facts, lines, str(q.get("currencyCode")), List.of(
                        new SalesDocumentPdf.Total("Untaxed", dec(q.get("subtotal"))), new SalesDocumentPdf.Total("Tax",
                                dec(q.get("taxAmount"))), new SalesDocumentPdf.Total("Total", dec(q.get("total")))),
                str(q.get("notes")), str(q.get("terms")), showImages), layout);
    }

    /** Email the PDF to the customer; an approved quotation becomes sent. */
    public Map<String, Object> email(String id, String to) {
        return email(id, Obj.of("to", to));
    }

    /**
     * Email the PDF to the customer ({@code to}, optional {@code cc} list, {@code subject}, {@code body}; defaults: the
     * quotation contact's or customer's email and a standard text); an approved quotation becomes sent and the CRM
     * timeline records it. Audit fix 4: the PDF is rendered in a read transaction, the mail goes out with no
     * transaction open, and only then is the quote marked sent (a failure after the mail never leaves a customer
     * holding an email for a rollback; a failed mail changes nothing).
     */
    public Map<String, Object> email(String id, Map<String, Object> in) {
        record Outgoing(Map<String, Object> q, String to, List<String> cc, String subject, String body, byte[] pdf) {}
        Outgoing out = readTx.execute(status -> {
            Map<String, Object> q = require(id);
            if (!Set.of("approved", "sent").contains(q.get("status"))) {
                throw ApiException.badRequest("Only approved quotations can be sent");
            }
            if (expired(q)) {
                throw ApiException.badRequest("Quotation has expired; revise it first");
            }
            String address = str(in.get("to"));
            if (blank(address) && q.get("contactId") != null) {
                Map<String, Object> c = db.row("SELECT email FROM \"Contact\" WHERE id = ? AND \"organizationId\" = ?",
                        q.get("contactId"), org());
                address = c == null ? null : str(c.get("email"));
            }
            if (blank(address)) {
                Map<String, Object> p = db.row("SELECT email FROM \"Partner\" WHERE id = ?"
                        + " AND \"organizationId\" = ?", q.get("partnerId"), org());
                address = p == null ? null : str(p.get("email"));
            }
            if (blank(address)) {
                throw ApiException.badRequest("Customer has no email on file. Add one in Partners or pass \"to\".");
            }
            List<String> cc = SalesDb.strings(in.get("cc")).stream().map(String::trim).filter(x -> !x.isEmpty())
                    .distinct().toList();
            for (String a : java.util.stream.Stream.concat(java.util.stream.Stream.of(address.trim()), cc.stream())
                    .toList()) {
                if (!a.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    throw ApiException.badRequest("Not an email address: " + a);
                }
            }
            if (cc.size() > 10) {
                throw ApiException.badRequest("At most 10 cc addresses");
            }
            String subject = blank(str(in.get("subject"))) ? "Quotation " + q.get("number") : str(in.get("subject"));
            String body = blank(str(in.get("body"))) ? "Please find attached quotation " + q.get("number")
                    + ", valid until " + q.get("validUntil") + "." : str(in.get("body"));
            if (subject.length() > 300 || body.length() > 20_000) {
                throw ApiException.badRequest("The subject or message is too long");
            }
            return new Outgoing(q, address.trim(), cc, subject, body, render(q, config.quoteLayout(), null));
        });
        boolean sent = mail.sendExternalEmail(out.to(), out.cc(), out.subject(), out.body(),
                new com.inventoryerp.kernel.notifications.NotificationsService.Attachment(out.q().get("number") + ".pdf",
                        "application/pdf", out.pdf()));
        tx.executeWithoutResult(status -> {
            markSent(id);
            QuotationOpportunityPort port = opportunities.getIfAvailable();
            if (port != null) {
                port.quotationEmailed(str(out.q().get("dealId")), str(out.q().get("partnerId")), str(out.q().get("number")),
                        out.to(), sent);
            }
        });
        return Obj.of("ok", true, "sentTo", out.to(), "cc", out.cc(), "delivered", sent);
    }

    // ------------------------------------------------------------------ pricing preview, duplicate

    /**
     * Audit fix 5: price lines while the quotation is edited exactly as saving will price them: the customer, the
     * quotation date (business calendar when absent), branch, site, currency and agreement of the header, and each
     * line's variant, unit and quantity. Answers {@code {lines: [{listPrice, unitPrice, discountPercent, source,
     * taxId, taxInclusive, description}]}} in input order.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewPrices(Map<String, Object> in) {
        String partnerId = blank(str(in.get("partnerId"))) ? null : str(in.get("partnerId"));
        LocalDate on = blank(str(in.get("issueDate"))) ? today() : SalesDb.date(str(in.get("issueDate")));
        String currency = blank(str(in.get("currencyCode"))) ? orgCurrency() : str(in.get("currencyCode"));
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> lines = in.get("lines") instanceof List<?> l ? l : List.of();
        if (lines.size() > 500) {
            throw ApiException.badRequest("At most 500 lines");
        }
        for (Object o : lines) {
            Map<String, Object> l = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String productId = str(l.get("productId"));
            if (blank(productId)) {
                throw ApiException.badRequest("productId should not be empty");
            }
            BigDecimal qty = l.get("quantity") == null ? BigDecimal.ONE : dec(l.get("quantity"));
            QuotationPricingPort.Price price = engine.resolve(new SalesPricingEngine.Context(partnerId, productId,
                    qty.signum() > 0 ? qty : BigDecimal.ONE, blank(str(l.get("uomId"))) ? null : str(l.get("uomId")),
                    blank(str(in.get("siteId"))) ? null : str(in.get("siteId")), blank(str(in.get("branchId"))) ? null
                            : str(in.get("branchId")), currency, on, blank(str(in.get("agreementId"))) ? null
                                    : str(in.get("agreementId")), blank(str(l.get("variantId"))) ? null
                                            : str(l.get("variantId")))).price();
            out.add(Obj.of("productId", productId, "listPrice", JsNumbers.js(price.listPrice()), "unitPrice",
                    JsNumbers.js(price.unitPrice()), "discountPercent", JsNumbers.js(price.discountPercent()), "source",
                    price.source(), "taxId", price.taxId(), "taxInclusive", price.taxInclusive(), "description",
                    price.description()));
        }
        return Obj.of("partnerId", partnerId, "lines", out);
    }

    /**
     * A new draft with this quotation's customer, terms and lines (enhancement: repeat offers). Prices follow today's
     * rules except manual ones, which are kept; the date and validity start again; nothing of the source's approval,
     * sending or answers is copied.
     */
    @Transactional
    public Map<String, Object> duplicate(String id) {
        Map<String, Object> q = require(id);
        Map<String, Object> in = new LinkedHashMap<>();
        for (String k : List.of("partnerId", "contactId", "notes", "terms", "internalNotes", "branchId", "paymentTermId",
                "siteId", "deliveryAddress", "agreementId", "currencyCode", "exchangeRate", "orderDiscountPercent",
                "orderDiscountAmount")) {
            if (q.get(k) != null) {
                in.put(k, q.get(k) instanceof BigDecimal b ? b : q.get(k).toString());
            }
        }
        if (q.get("dealId") != null && opportunities.getIfAvailable() != null) {
            try {
                opportunity(str(q.get("dealId")));
                in.put("dealId", q.get("dealId"));
            } catch (ApiException e) {
                // the opportunity is closed to this user (or gone): the copy simply has none
            }
        }
        in.put("lines", linesAsInput(id));
        Map<String, Object> copy = create(in);
        audit.record(AuditInput.of("SalesQuotation", str(copy.get("id")), "update", null, Obj.of("duplicateOf",
                q.get("number"))));
        return copy;
    }

    /**
     * The quotation's lines as an editor would send them (no ids): manual prices kept, rule / list prices left for
     * re-pricing, sizes, options, sections and notes as they are. Also the content of a quotation template.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> linesAsInput(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"sortOrder\"", id, org())) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (l.get("displayType") != null) {
                m.put("displayType", l.get("displayType"));
                m.put("description", l.get("description"));
                out.add(m);
                continue;
            }
            for (String k : List.of("productId", "variantId", "uomId", "description", "taxId", "taxInclusive",
                    "leadTimeDays", "isOptional", "pieces", "length", "width", "height")) {
                if (l.get(k) != null) {
                    m.put(k, l.get(k) instanceof BigDecimal b ? JsNumbers.js(b) : l.get(k));
                }
            }
            m.put("quantity", JsNumbers.js(dec(l.get("quantity"))));
            if (l.get("productId") == null || "manual".equals(l.get("priceSource"))) {
                m.put("unitPrice", JsNumbers.js(dec(l.get("unitPrice"))));
                m.put("discountPercent", JsNumbers.js(l.get("lineDiscountPercent") != null
                        ? dec(l.get("lineDiscountPercent")) : dec(l.get("discountPercent"))));
            }
            out.add(m);
        }
        return out;
    }

    /** {@code optionalLineIds} of an accept body (absent = none). */
    public static List<String> optionalLineIds(Map<String, Object> b) {
        Object v = b == null ? null : b.get("optionalLineIds");
        if (v != null && !(v instanceof List<?>)) {
            throw ApiException.badRequest("optionalLineIds must be an array");
        }
        return v == null ? List.of() : ((List<?>) v).stream().map(String::valueOf).toList();
    }

    /** Days of validity left on the business calendar ({@code validUntil} − today; null without a date). */
    static Integer daysLeft(Object validUntil) {
        LocalDate v = validUntil instanceof LocalDate d ? d : validUntil == null ? null
                : LocalDate.parse(validUntil.toString().substring(0, 10));
        return v == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(today(), v);
    }

    private void visibility(StringBuilder where, List<Object> params) {
        access.filterDocument(where, params, "q");
        if (TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL) || TenantContext.has(Permissions.SALES__ORDER_READ_ALL)) {
            return;
        }
        where.append(" AND (q.\"ownerId\" = ? OR q.\"createdById\" = ? OR q.\"ownerId\" IS NULL)");
        params.add(user());
        params.add(user());
    }

    private Map<String, Object> require(String id) {
        StringBuilder where = new StringBuilder(" WHERE q.id = ? AND q.\"organizationId\" = ? AND q.\"deletedAt\" IS"
                + " NULL");
        List<Object> params = new ArrayList<>(List.of(id, org()));
        visibility(where, params);
        Map<String, Object> q = db.row(SELECT + where, params.toArray());
        if (q == null) {
            throw ApiException.notFound("Quotation not found");
        }
        return q;
    }

    private Map<String, Object> lock(String id) {
        require(id);
        return db.row("SELECT * FROM \"SalesQuotation\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
    }

    private boolean canApprove() {
        return TenantContext.has(Permissions.CRM__QUOTATION_APPROVE)
                || TenantContext.has(Permissions.SALES__QUOTATION_APPROVE);
    }

    private Map<String, Object> opportunity(String dealId) {
        QuotationOpportunityPort port = opportunities.getIfAvailable();
        if (port == null) {
            throw ApiException.notFound("Opportunity not found");
        }
        return port.visibleOpportunity(dealId);
    }

    String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }

    private String requireUser(String id) {
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL", List.of(id, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        return id;
    }

    /** Immutable snapshot (header + lines) of the current revision, taken before a new revision starts. */
    private void snapshot(Map<String, Object> q, String reason) {
        String id = str(q.get("id"));
        Map<String, Object> header = SalesDb.json(q);
        header.put("lines", db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? ORDER BY"
                + " \"sortOrder\"", id));
        db.update("INSERT INTO \"SalesQuotationRevision\" (id, \"organizationId\", \"quotationId\", revision, status,"
                + " total, snapshot, reason, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT"
                + " (\"quotationId\", revision) DO NOTHING", SalesDb.newId(), org(), id,
                ((Number) q.get("revision")).intValue(), str(q.get("status")), dec(q.get("total")),
                SalesDb.toJson(header), reason, user());
    }

    /** Revision snapshots, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(String id) {
        require(id);
        return db.rows("SELECT id, revision, status, total, reason, \"createdById\", \"createdAt\", snapshot FROM"
                + " \"SalesQuotationRevision\" WHERE \"quotationId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " revision DESC", id, org());
    }

    private static BigDecimal decOr(Object v, BigDecimal fallback) {
        BigDecimal d = SalesDb.decOrNull(v);
        return d == null ? fallback : d;
    }
}
