package com.inventoryerp.sales.application;

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
    static final Set<String> DISPLAY_TYPES = Set.of("line_section", "line_note");

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
            ProductVariantService variants) {
        this.variants = variants;
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

    /** Paginated list {@code {data, meta, totals}}; expired quotes are flagged on read. */
    @Transactional
    public Map<String, Object> list(Map<String, String> p) {
        expireOverdue();
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
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY q.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(q.total), 0) AS total FROM"
                + " \"SalesQuotation\" q LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" LEFT JOIN \"Deal\" d ON d.id ="
                + " q.\"dealId\"" + where, params.toArray());
        long total = ((Number) totals.get("count")).longValue();
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
                + " pr.\"sizingUnit\", t.name AS \"taxName\","
                + " t.rate AS \"taxRate\" FROM \"SalesQuotationLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\""
                + " LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"quotationId\" = ? ORDER BY l.\"sortOrder\"", id);
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
        q.put("credit", credit(str(q.get("partnerId")), BigDecimal.ZERO));
        q.put("canApprove", canApprove());
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
        LocalDate issue = in.get("issueDate") == null ? LocalDate.now() : SalesDb.date(str(in.get("issueDate")));
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
        db.update("DELETE FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?", id, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
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
                layout.add(Obj.of("displayType", displayType, "description", str(l.get("description"))));
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
                    disc, null, null, null, "manual", blank(taxId) ? null : taxId, null, incl, null, size));
            meta.add(Obj.of("listPrice", list, "source", source, "ruleId", price == null || !"rule".equals(source)
                    ? null : price.ruleId(), "variantId", variant == null ? null : variant.id(), "uomId", uomId,
                    "leadTimeDays", leadTime));
            layout.add(inputs.size() - 1);
        }
        if (inputs.isEmpty()) {
            insertLayoutRows(id, layout, List.of());
            db.update("UPDATE \"SalesQuotation\" SET subtotal = 0, \"discountTotal\" = 0, \"taxAmount\" = 0, total = 0,"
                    + " \"maxDiscountPercent\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?", id);
            return;
        }
        BigDecimal orderNet = BigDecimal.ZERO;
        for (LineInput li : inputs) {
            orderNet = orderNet.add(SalesDiscounts.net(li.quantity(), li.unitPrice(), li.discountPercent()));
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
                    "variantId", m.get("variantId"), "uomId", m.get("uomId"), "description", pl.description(), "quantity",
                    pl.quantity(), "listPrice", list, "unitPrice",
                    pl.unitPrice(), "discountPercent", pl.discountPercent(), "priceSource", m.get("source"),
                    "priceRuleId", m.get("ruleId"), "lineDiscountPercent", m.get("lineDiscountPercent"), "taxId", pl.taxId(), "taxInclusive", pl.taxInclusive(),
                    "leadTimeDays", m.get("leadTimeDays"), "lineSubtotal", pl.subtotal(), "lineTax", pl.taxAmount(),
                    "lineTotal", pl.total()));
            priced.getLast().putAll(ProductSizingService.Sizing.columns(pl.sizing()));
            i++;
        }
        insertLayoutRows(id, layout, priced);
        db.update("UPDATE \"SalesQuotation\" SET subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?, total = ?,"
                + " \"maxDiscountPercent\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?", totals.subtotal(),
                totals.discountTotal(), totals.taxAmount(), totals.total(), maxDisc.setScale(4, RoundingMode.HALF_UP), id);
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
                        "quantity", BigDecimal.ONE, "unitPrice", BigDecimal.ZERO, "priceSource", "manual");
            }
            row.put("sortOrder", sort++);
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
        if (db.count("SELECT count(*) FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"displayType\" IS NULL",
                List.of(id)) == 0) {
            throw ApiException.badRequest("Add at least one line before submitting");
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
        BigDecimal extra = extraDiscount(str(q.get("id")), str(q.get("partnerId")), (LocalDate) q.get("issueDate"));
        if (extra.compareTo(limit) > 0) {
            reasons.add("Discount " + extra.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + "% exceeds the " + limit.stripTrailingZeros().toPlainString() + "% limit");
        }
        BigDecimal amount = dec(s.get("quoteApprovalAmount"));
        BigDecimal total = dec(q.get("total"));
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

    private BigDecimal extraDiscount(String id, String partnerId, LocalDate on) {
        BigDecimal max = BigDecimal.ZERO;
        for (Map<String, Object> l : db.rows("SELECT \"productId\", \"variantId\", quantity, \"unitPrice\","
                + " \"discountPercent\", \"listPrice\" FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND"
                + " \"displayType\" IS NULL", id)) {
            BigDecimal net = dec(l.get("unitPrice")).multiply(BigDecimal.ONE.subtract(dec(l.get("discountPercent"))
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP)));
            // ADR-S058: a variant line is authorized at the variant's price, not the product's
            BigDecimal authorized = l.get("productId") == null ? dec(l.get("unitPrice"))
                    : l.get("variantId") != null ? engine.resolve(new SalesPricingEngine.Context(partnerId,
                            str(l.get("productId")), dec(l.get("quantity")), null, null, null, null, on, null,
                            str(l.get("variantId")))).price().net()
                    : price(partnerId, str(l.get("productId")), dec(l.get("quantity")), on).net();
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
        audit.record(AuditInput.of("SalesQuotation", id, "approve", null, Obj.of("comment", comment)));
        events.publish(DomainEvents.CRM_QUOTATION_APPROVED, Obj.of("organizationId", org(), "quotationId", id,
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
        audit.record(AuditInput.of("SalesQuotation", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /** Apply a kernel approval-engine decision (idempotent: ignored unless still pending). */
    @Transactional
    public void applyDecision(String id, boolean approved, String comment) {
        Map<String, Object> q = db.row("SELECT status FROM \"SalesQuotation\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        if (q == null || !"pending_approval".equals(q.get("status"))) {
            return;
        }
        db.patch("SalesQuotation", id, approved ? Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                java.time.Instant.now()) : Obj.of("status", "rejected", "rejectedById", user(), "rejectionReason",
                blank(comment) ? "Rejected in approval workflow" : comment), true);
        audit.record(AuditInput.of("SalesQuotation", id, approved ? "approve" : "reject", null, Obj.of("via",
                "approval_engine")));
    }

    // ------------------------------------------------------------------ customer lifecycle

    /** Mark sent to the customer (approved quotes only); moves an opportunity in an early stage to proposal. */
    @Transactional
    public Map<String, Object> send(String id) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved quotations can be sent");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "sent", "sentAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "issue", null, Obj.of("status", "sent")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> accept(String id) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be accepted");
        }
        if (q.get("validUntil") instanceof LocalDate v && v.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Quotation has expired; revise it first");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "accepted", "acceptedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "accepted")));
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
        db.patch("SalesQuotation", id, Obj.of("status", "rejected", "lostReasonId", blank(lostReasonId) ? null : lostReasonId,
                "rejectionReason", blank(reason) ? "Declined by customer" : reason), true);
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
        Map<String, Object> s = config.settings();
        db.patch("SalesQuotation", id, Obj.of("status", "draft", "revision", ((Number) q.get("revision")).intValue() + 1,
                "approvalReasons", List.of(), "approvalRequestId", null, "approvedById", null, "approvedAt", null,
                "rejectionReason", null, "sentAt", null, "acceptedAt", null, "validUntil", LocalDate.now().plusDays(
                        ((Number) s.get("quoteValidityDays")).longValue())), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("revision", "new")));
        return get(id);
    }

    /** Hard-delete a draft that was never submitted. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> q = lock(id);
        if (!"draft".equals(q.get("status")) || q.get("approvalRequestId") != null) {
            throw ApiException.badRequest("Only draft quotations can be deleted; cancel it instead");
        }
        db.update("UPDATE \"SalesQuotation\" SET \"deletedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", id,
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
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.\"trackInventory\" FROM \"SalesQuotationLine\" l LEFT"
                + " JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE l.\"quotationId\" = ? AND l.\"displayType\" IS NULL"
                + " ORDER BY l.\"sortOrder\"", id);
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
            dto.put("notes", "From quotation " + q.get("number") + (q.get("notes") == null ? "" : " — " + q.get("notes")));
            dto.put("lines", orderLines);
            Map<String, Object> order = orders.createOrder(dto);
            convertedId = str(order.get("id"));
            convertedNumber = str(order.get("orderNumber"));
        } else {
            List<LineInput> inputs = new ArrayList<>();
            for (Map<String, Object> l : lines) {
                inputs.add(new LineInput(str(l.get("productId")), null, null, null, null, str(l.get("description")),
                        dec(l.get("quantity")), dec(l.get("unitPrice")), dec(l.get("discountPercent")), null, null, null,
                        "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")), null,
                        ProductSizingService.Sizing.stored(l)));
            }
            HeaderInput h = new HeaderInput(str(q.get("partnerId")), LocalDate.now().toString(), q.get("validUntil") == null
                    ? null : q.get("validUntil").toString(), null, null, null, null, null, null, null, str(q.get("ownerId")),
                    null, null, null, null, null, str(q.get("number")), null, null, str(q.get("number")),
                    str(q.get("notes")), null, "crm_quotation", str(q.get("branchId")));
            Map<String, Object> doc = builder.createDocument("proforma_invoice", h, inputs);
            convertedId = str(doc.get("id"));
            proformas.applyTerms(convertedId, in, null);
            convertedNumber = str(doc.get("documentNumber"));
        }
        db.patch("SalesQuotation", id, Obj.of("status", "converted", "acceptedAt", q.get("acceptedAt") == null
                ? java.time.Instant.now() : q.get("acceptedAt"), "convertedType", target, "convertedId", convertedId,
                "convertedNumber", convertedNumber, "convertedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "converted", "target", target,
                "convertedId", convertedId)));
        events.publish(DomainEvents.CRM_QUOTATION_CONVERTED, Obj.of("organizationId", org(), "quotationId", id,
                "convertedType", target, "convertedId", convertedId));
        String dealId = str(q.get("dealId"));
        if (!blank(dealId) && Boolean.TRUE.equals(config.settings().get("autoWinOnConversion"))) {
            QuotationOpportunityPort port = opportunities.getIfAvailable();
            if (port != null) {
                port.markWonIfOpen(dealId);
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

    /** Mark approved / sent quotes past their validity date as expired. */
    /** Expire overdue quotations and tell their owners; returns how many expired. */
    @Transactional
    public int expireAndNotify() {
        List<Map<String, Object>> expired = db.rows("UPDATE \"SalesQuotation\" SET status = 'expired', \"updatedAt\" = " + NOW
                + " WHERE \"organizationId\" = ? AND status IN ('draft', 'approved', 'sent') AND \"validUntil\" < CURRENT_DATE"
                + " AND \"deletedAt\" IS NULL RETURNING id, number, \"ownerId\", \"createdById\"", org());
        for (Map<String, Object> e : expired) {
            alerts.send(str(e.get("ownerId") != null ? e.get("ownerId") : e.get("createdById")), "sales_quotation_expired",
                    "Quotation " + e.get("number") + " has expired", "Revise it to send it again.", Obj.of("entityType",
                            "sales_quotation", "entityId", e.get("id")));
        }
        return expired.size();
    }

    /** Customer-facing PDF; internal notes never appear. */
    @Transactional(readOnly = true)
    public byte[] pdf(String id) {
        Map<String, Object> q = require(id);
        List<Map<String, Object>> lines = db.rows("SELECT l.description, l.quantity, l.\"unitPrice\", l.\"discountPercent\","
                + " l.\"lineTotal\" AS total, l.pieces, l.length, l.width, l.height, p.\"sizingUnit\" FROM"
                + " \"SalesQuotationLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"quotationId\" = ? AND"
                + " l.\"organizationId\" = ? AND NOT COALESCE(l.\"isOptional\", false) AND l.\"displayType\" IS NULL ORDER BY"
                + " l.\"sortOrder\"", id, org());
        String company = str(db.row("SELECT COALESCE(NULLIF(\"receiptHeader\"->>'businessName', ''), name) AS n FROM"
                + " \"Organization\" WHERE id = ?", org()).get("n"));
        return SalesDocumentPdf.render(company, "QUOTATION", str(q.get("number")), List.of("Customer: " + q.get("partnerName"),
                "Date: " + q.get("issueDate"), "Valid until: " + q.get("validUntil")), lines, str(q.get("currencyCode")), List.of(
                        new SalesDocumentPdf.Total("Untaxed", dec(q.get("subtotal"))), new SalesDocumentPdf.Total("Tax",
                                dec(q.get("taxAmount"))), new SalesDocumentPdf.Total("Total", dec(q.get("total")))), List.of(
                                        new SalesDocumentPdf.Block("Notes", str(q.get("notes"))),
                                        new SalesDocumentPdf.Block("Terms", str(q.get("terms")))));
    }

    /** Email the PDF to the customer; an approved quotation becomes sent. */
    @Transactional
    public Map<String, Object> email(String id, String to) {
        Map<String, Object> q = require(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved quotations can be sent");
        }
        Map<String, Object> p = db.row("SELECT email FROM \"Partner\" WHERE id = ?", q.get("partnerId"));
        String address = !blank(to) ? to.trim() : p == null ? null : str(p.get("email"));
        if (blank(address)) {
            throw ApiException.badRequest("Customer has no email on file. Add one in Partners or pass \"to\".");
        }
        boolean sent = mail.sendExternalEmail(address, "Quotation " + q.get("number"), "Please find attached quotation "
                + q.get("number") + ", valid until " + q.get("validUntil") + ".", new com.inventoryerp.kernel.notifications
                        .NotificationsService.Attachment(q.get("number") + ".pdf", "application/pdf", pdf(id)));
        send(id);
        return Obj.of("ok", true, "sentTo", address, "delivered", sent);
    }

    void expireOverdue() {
        db.update("UPDATE \"SalesQuotation\" SET status = 'expired', \"updatedAt\" = " + NOW + " WHERE \"organizationId\" ="
                + " ? AND status IN ('draft', 'approved', 'sent') AND \"validUntil\" < CURRENT_DATE AND \"deletedAt\" IS"
                + " NULL", org());
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

    private QuotationPricingPort.Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        QuotationPricingPort port = pricing.getIfAvailable();
        if (port != null) {
            return port.price(partnerId, productId, qty, on);
        }
        Map<String, Object> p = db.row("SELECT name, \"salesPrice\", \"taxId\", \"taxInclusive\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", productId, org());
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal list = dec(p.get("salesPrice"));
        return new QuotationPricingPort.Price(list, list, BigDecimal.ZERO, "list", null, str(p.get("taxId")),
                Boolean.TRUE.equals(p.get("taxInclusive")), str(p.get("name")));
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
