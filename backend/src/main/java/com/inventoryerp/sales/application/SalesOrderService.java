package com.inventoryerp.sales.application;

import org.springframework.beans.factory.ObjectProvider;
import com.inventoryerp.core.application.ProductLifecycleGuard;
import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.core.application.PriceSnapshotService;
import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2B sales orders (ADR-S011, phase S1). Lifecycle {@code draft → confirmed → done}, {@code cancelled}; approval is
 * its own dimension ({@code approvalStatus}, ADR-S094 §4.2: submitted explicitly, bound to the approvable content);
 * {@code onHold} overlays an open order (credit / manual); delivery and invoice progress are {@code deliveryStatus} and
 * {@code invoiceStatus}, and the old single value comes back as the derived {@code stage}. Draft orders are edited in
 * place; confirmed orders change only through {@link SalesOrderChangeService} (immutable revisions). Ordered and
 * closed-short quantities are owned here; delivered / billable / invoiced / reserved / returned columns are
 * projections of the owning modules, maintained by later phases.
 */
@Service
public class SalesOrderService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> OPEN = Set.of("confirmed");

    /**
     * ADR-S094 §4.6: the lifecycle value of before C1, derived for one release so cached clients and reports keep
     * working ({@code %1$s} = the order's table alias).
     */
    public static final String STAGE_SQL = "CASE WHEN %1$s.status = 'draft' AND %1$s.\"approvalStatus\" = 'pending' THEN"
            + " 'pending_approval' WHEN %1$s.status = 'confirmed' AND (%1$s.\"deliveryStatus\" IN ('partial', 'full') OR"
            + " %1$s.\"invoiceStatus\" IN ('partial', 'invoiced')) THEN 'in_fulfilment' ELSE %1$s.status END";

    private static final String SELECT = """
            SELECT o.*, p.name AS "partnerName", q.number AS "quotationNumber", %s AS stage,
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "ownerName"
              FROM "SalesOrder" o
              LEFT JOIN "Partner" p ON p.id = o."partnerId"
              LEFT JOIN "SalesQuotation" q ON q.id = o."quotationId"
              LEFT JOIN "User" u ON u.id = o."ownerId"
            """.formatted(STAGE_SQL.formatted("o"));

    private final SalesDb db;
    private final SalesSettingsService settings;
    private final SalesLinePricing pricing;
    private final SalesPricingEngine engine;
    private final ApprovalsService approvals;
    private final CreditService credit;
    private final PriceSnapshotService snapshots;
    private final SalesAtpService atp;
    private final DocumentBuilderService builder;
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
    private SalesOutcomeService outcome;
    /** ADR-S094 §4.4: the financial outcome, refreshed with the derived statuses. */
    @org.springframework.beans.factory.annotation.Autowired
    void setOutcome(SalesOutcomeService outcome) {
        this.outcome = outcome;
    }
    private SalesAgreementService agreements;
    private SalesAccess access;
    private SalesAlerts alerts;
    private SalesCreditRequestService creditRequests;

    @org.springframework.beans.factory.annotation.Autowired
    void setAlerts(SalesAlerts alerts, @org.springframework.context.annotation.Lazy SalesCreditRequestService creditRequests) {
        this.alerts = alerts;
        this.creditRequests = creditRequests;
    }

    public SalesOrderService(SalesDb db, SalesSettingsService settings, SalesLinePricing pricing,
            SalesPricingEngine engine, ApprovalsService approvals, CreditService credit, SalesAtpService atp,
            DocumentBuilderService builder, SequenceService sequence, AuditService audit, EventBus events,
            PriceSnapshotService snapshots, ProductVariantService variants,
            ObjectProvider<ProductLifecycleGuard> lifecycleGuard) {
        this.variants = variants;
        this.lifecycleGuard = lifecycleGuard;
        this.snapshots = snapshots;
        this.credit = credit;
        this.atp = atp;
        this.engine = engine;
        this.approvals = approvals;
        this.db = db;
        this.settings = settings;
        this.pricing = pricing;
        this.builder = builder;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ queries

    /** Paginated list {@code {data, meta, totals}}. Filters: status (csv), partnerId, ownerId, onHold, q, from, to. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        if (!blank(p.get("status"))) {
            // ADR-S094 §4.6: the old values pending_approval / in_fulfilment still filter, through the derived stage.
            List<String> st = List.of(p.get("status").split(","));
            where.append(" AND (o.status IN (").append(SalesDb.marks(st.size())).append(") OR ")
                    .append(STAGE_SQL.formatted("o")).append(" IN (").append(SalesDb.marks(st.size())).append("))");
            params.addAll(st);
            params.addAll(st);
        }
        if (!blank(p.get("approvalStatus"))) {
            List<String> st = List.of(p.get("approvalStatus").split(","));
            where.append(" AND o.\"approvalStatus\" IN (").append(SalesDb.marks(st.size())).append(')');
            params.addAll(st);
        }
        // ADR-S094 §4.4: payment and settlement outcome
        for (String f : List.of("paymentStatus", "settlementStatus")) {
            if (!blank(p.get(f))) {
                List<String> st = List.of(p.get(f).split(","));
                where.append(" AND o.\"").append(f).append("\" IN (").append(SalesDb.marks(st.size())).append(')');
                params.addAll(st);
            }
        }
        for (String k : List.of("partnerId", "ownerId", "quotationId", "branchId")) {
            if (!blank(p.get(k))) {
                where.append(" AND o.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        if ("true".equals(p.get("onHold"))) {
            where.append(" AND o.\"onHold\"");
        }
        if ("true".equals(p.get("toFulfil"))) {
            where.append(" AND o.status = 'confirmed' AND o.\"deliveryStatus\" <> 'full'");
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (o.number ILIKE ? OR p.name ILIKE ? OR o.\"customerRef\" ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        if (!blank(p.get("from"))) {
            where.append(" AND o.\"createdAt\" >= ?");
            params.add(SalesDb.date(p.get("from")));
        }
        if (!blank(p.get("to"))) {
            where.append(" AND o.\"createdAt\" < ?::date + 1");
            params.add(SalesDb.date(p.get("to")));
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY o.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        // Sales readiness SO-13: amounts of different currencies are never added; `total` is in the base currency
        // (each order at its rate) and `byCurrency` gives the per-currency sums.
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(o.total * COALESCE(o.\"exchangeRate\","
                + " 1)), 0) AS total FROM \"SalesOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\"" + where,
                params.toArray());
        totals.put("byCurrency", db.rows("SELECT o.\"currencyCode\", count(*) AS count, COALESCE(sum(o.total), 0) AS total"
                + " FROM \"SalesOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\"" + where + " GROUP BY"
                + " o.\"currencyCode\" ORDER BY o.\"currencyCode\"", params.toArray()));
        long total = ((Number) totals.get("count")).longValue();
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Order with lines (+ quantity breakdown), linked documents (smart buttons) and permissions of the caller. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> o = require(id);
        List<Map<String, Object>> lines = lines(id);
        o.put("lines", lines);
        if ("draft".equals(o.get("status"))) {
            o.put("stockWarnings", atp.shortages(id));
        }
        o.put("relations", relations(o));
        o.put("canAmend", OPEN.contains(o.get("status")) && TenantContext.has(Permissions.SALES__ORDER_AMEND));
        Map<String, Object> rules = "draft".equals(o.get("status")) ? settings.stored() : null;
        if (rules != null) {
            // ADR-S094 §4.2: the approval state as it stands now — a rule changed since the last save shows here
            Approval a = effectiveApproval(db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                    org()), orderLines(id), rules);
            o.put("approvalStatus", a.status());
            if (!Set.of("pending", "rejected").contains(a.status())) {
                o.put("approvalReasons", a.reasons());
            }
        }
        // ADR-S094 §4.4: the outcome as the posted documents stand now (the stored copy follows events and the
        // nightly reconciliation)
        SalesOutcomeService.Outcome now = outcome.compute(id);
        if (now != null) {
            o.putAll(now.json());
        }
        o.put("actions", actions(o));
        return o;
    }

    /**
     * ADR-S094 §4.2: the lifecycle commands the caller may run on the order now (state, permission and separation of
     * duties), so the screens only offer what the API accepts.
     */
    private List<String> actions(Map<String, Object> o) {
        List<String> out = new ArrayList<>();
        if (!"draft".equals(o.get("status"))) {
            if (o.get("openCredit") instanceof Number n && n.doubleValue() > 0
                    && TenantContext.has(Permissions.SALES__CREDIT_NOTE_WRITE)) {
                out.add("keep_credit");
            }
            return out;
        }
        String approval = str(o.get("approvalStatus"));
        if (Set.of("required", "rejected").contains(approval) && TenantContext.has(Permissions.SALES__ORDER_WRITE)) {
            out.add("submit_approval");
        }
        if ("pending".equals(approval)) {
            if (user() != null && (user().equals(o.get("approvalSubmittedById")) || TenantContext.has(
                    Permissions.SALES__CONFIGURE))) {
                out.add("withdraw_approval");
            }
            if (TenantContext.has(Permissions.SALES__ORDER_APPROVE) && selfApprovalProblem(o) == null) {
                out.add("approve");
            }
            if (TenantContext.has(Permissions.SALES__ORDER_APPROVE)) {
                out.add("reject");
            }
        }
        if (Set.of("not_required", "approved").contains(approval) && TenantContext.has(Permissions.SALES__ORDER_CONFIRM)) {
            out.add("confirm");
        }
        return out;
    }

    List<Map<String, Object>> lines(String orderId) {
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\", (SELECT v.name FROM"
                + " \"ProductVariant\" v WHERE v.id = l.\"variantId\") AS \"variantName\","
                + " pr.\"trackInventory\", pr.\"sizingMode\", pr.\"sizingUnit\", t.name AS \"taxName\", t.rate AS"
                + " \"taxRate\", u.code AS \"uomCode\" FROM \"SalesOrderLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"Tax\" t ON t.id ="
                + " l.\"taxId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY"
                + " l.\"sortOrder\"", orderId);
        for (Map<String, Object> l : lines) {
            l.put("closedShort", l.get("closedShortAt") != null);
            if (l.get("lineDiscountPercent") != null) {
                l.put("netDiscountPercent", l.get("discountPercent"));
                l.put("discountPercent", l.get("lineDiscountPercent"));
            }
            l.put("qty", quantities(l));
        }
        return lines;
    }

    /** Commercial vs physical vs financial quantities of a line (sales.md §4). */
    static Map<String, Object> quantities(Map<String, Object> l) {
        BigDecimal ordered = dec(l.get("quantity"));
        BigDecimal cancelled = dec(l.get("qtyCancelled"));
        BigDecimal delivered = dec(l.get("qtyDelivered"));
        BigDecimal billable = dec(l.get("qtyBillable"));
        BigDecimal invoiced = dec(l.get("qtyInvoiced"));
        BigDecimal credited = dec(l.get("qtyCredited"));
        BigDecimal net = invoiced.subtract(credited);
        BigDecimal remaining = ordered.subtract(cancelled).subtract(delivered).max(BigDecimal.ZERO);
        return Obj.of("ordered", JsNumbers.js(ordered), "cancelled", JsNumbers.js(cancelled), "reserved",
                JsNumbers.js(dec(l.get("qtyReserved"))), "delivered", JsNumbers.js(delivered), "billable",
                JsNumbers.js(billable), "grossInvoiced", JsNumbers.js(invoiced), "credited", JsNumbers.js(credited),
                "netInvoiced", JsNumbers.js(net), "returned", JsNumbers.js(dec(l.get("qtyReturned"))), "remaining",
                JsNumbers.js(remaining), "outstandingBillable", JsNumbers.js(billable.subtract(net).max(BigDecimal.ZERO)));
    }

    private Map<String, Object> relations(Map<String, Object> o) {
        String id = str(o.get("id"));
        Map<String, Object> quote = o.get("quotationId") == null ? null
                : Obj.of("id", o.get("quotationId"), "number", o.get("quotationNumber"));
        List<Map<String, Object>> deliveries = db.rows("SELECT id, code AS number, status FROM \"FleetDeliveryOrder\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = 'sales_order' AND \"sourceId\" = ? ORDER BY \"createdAt\"",
                org(), id);
        List<Map<String, Object>> invoices = db.rows("SELECT id, \"documentNumber\" AS number, status::text AS status,"
                + " \"documentType\"::text AS type, \"totalAmount\" AS total FROM \"Document\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'sales_order' AND \"sourceId\" = ? ORDER BY \"createdAt\"", org(), id);
        long changes = db.count("SELECT count(*) FROM \"SalesOrderChangeRequest\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ?", List.of(id, org()));
        long revisions = db.count("SELECT count(*) FROM \"SalesOrderRevision\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ?", List.of(id, org()));
        Map<String, Object> agreement = o.get("agreementId") == null ? null : db.row("SELECT id, number, name, status"
                + " FROM \"SalesAgreement\" WHERE id = ? AND \"organizationId\" = ?", o.get("agreementId"), org());
        return Obj.of("quotation", quote, "agreement", agreement, "deliveries", deliveries, "invoices", invoices,
                "changeRequests", changes, "revisions", revisions);
    }

    /** Revision snapshots, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(String id) {
        require(id);
        return db.rows("SELECT id, revision, total, reason, diff, \"changeRequestId\", \"createdById\", \"createdAt\","
                + " snapshot FROM \"SalesOrderRevision\" WHERE \"orderId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " revision DESC", id, org());
    }

    // ------------------------------------------------------------------ create / edit (draft)

    @org.springframework.beans.factory.annotation.Autowired
    void setAgreements(SalesAgreementService agreements) {
        this.agreements = agreements;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    /** New draft order (direct order flow). */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        if (blank(partnerId)) {
            throw ApiException.badRequest("partnerId should not be empty");
        }
        requirePartner(partnerId);
        Map<String, Object> header = header(in, true);
        customerDefaults(header, partnerId);
        header.put("branchId", access.documentBranch(str(header.get("branchId"))));
        header.put("partnerId", partnerId);
        if (header.get("agreementId") == null) {
            String project = engine.projectAgreement(partnerId, str(header.get("siteId")), LocalDate.now(BusinessZone.get()));
            if (project != null) {
                header.put("agreementId", project);
            }
        }
        agreement(header, partnerId);
        header.put("number", nextNumber(str(header.get("branchId"))));
        header.putIfAbsent("currencyCode", orgCurrency());
        header.putIfAbsent("ownerId", user());
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        writeLines(id, partnerId, str(header.get("agreementId")), LocalDate.now(BusinessZone.get()), in.get("lines"),
                str(header.get("locationId")), PriceScope.of(header));
        evaluateApproval(id);
        audit.record(AuditInput.of("SalesOrder", id, "create", null, Obj.of("number", row.get("number"))));
        return get(id);
    }

    /**
     * Order from a quotation (called by {@link SalesQuotationService#convert} in its transaction): header and lines
     * are copied with their agreed prices (price source {@code quotation}).
     */
    @Transactional
    public Map<String, Object> createFromQuotation(Map<String, Object> q, List<Map<String, Object>> quoteLines,
            Map<String, Object> in) {
        Map<String, Object> header = header(in, true);
        header.put("partnerId", q.get("partnerId"));
        header.put("contactId", q.get("contactId"));
        header.put("quotationId", q.get("id"));
        header.put("dealId", q.get("dealId"));
        header.put("branchId", access.documentBranch(str(header.containsKey("branchId") ? header.get("branchId")
                : q.get("branchId"))));
        header.putIfAbsent("siteId", q.get("siteId"));
        header.putIfAbsent("deliveryAddress", q.get("deliveryAddress"));
        header.putIfAbsent("paymentTermId", q.get("paymentTermId"));
        header.putIfAbsent("pricelistId", q.get("pricelistId"));
        header.put("currencyCode", q.get("currencyCode"));
        header.putIfAbsent("exchangeRate", q.get("exchangeRate"));
        header.putIfAbsent("agreementId", q.get("agreementId"));
        header.putIfAbsent("ownerId", q.get("ownerId") == null ? user() : q.get("ownerId"));
        header.putIfAbsent("teamId", q.get("teamId"));
        header.putIfAbsent("notes", q.get("notes"));
        header.putIfAbsent("terms", q.get("terms"));
        header.putIfAbsent("commitmentDate", q.get("commitmentDate"));
        header.put("number", nextNumber(str(header.get("branchId"))));
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : quoteLines) {
            if (Boolean.TRUE.equals(l.get("isOptional"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("productId", "variantId", "description", "quantity", "uomId", "unitPrice",
                    "discountPercent", "taxId", "taxInclusive", "listPrice", "pieces", "length", "width", "height")) {
                m.put(k, l.get(k));
            }
            m.put("priceSource", "quotation");
            m.put("quotationLineId", l.get("id"));
            lines.add(m);
        }
        writeLines(id, str(q.get("partnerId")), null, LocalDate.now(BusinessZone.get()), lines, str(header.get("locationId")),
                PriceScope.of(header));
        // Review U-5: the order keeps each quote line's saved factor (same unit), not today's re-resolved one.
        for (Map<String, Object> l : quoteLines) {
            if (!Boolean.TRUE.equals(l.get("isOptional")) && l.get("uomFactor") != null) {
                String lineId = db.rows("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"quotationLineId\" = ?",
                        id, l.get("id")).stream().map(r -> str(r.get("id"))).findFirst().orElse(null);
                keepSavedFactor(lineId, l.get("uomId"), l.get("uomFactor"));
            }
        }
        evaluateApproval(id);
        audit.record(AuditInput.of("SalesOrder", id, "create", null, Obj.of("number", row.get("number"), "quotationId",
                q.get("id"))));
        return get(id);
    }

    /** Edit header and replace lines while draft. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be edited; request a change for a confirmed order");
        }
        // Sales readiness R4 (SO-11): a save based on an older copy would silently overwrite someone else's edit.
        if (in.get("version") instanceof Number v && v.intValue() != ((Number) o.get("version")).intValue()) {
            throw ApiException.conflict("This order was changed by someone else since you opened it; reload it and"
                    + " apply your changes again");
        }
        in = new LinkedHashMap<>(in);
        in.remove("version");
        Map<String, Object> d = header(in, false);
        if (d.containsKey("branchId")) {
            d.put("branchId", access.documentBranch(str(d.get("branchId"))));
        }
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            requirePartner(p);
            d.put("partnerId", p);
        }
        if (d.containsKey("agreementId") || d.containsKey("partnerId") && o.get("agreementId") != null) {
            if (!d.containsKey("agreementId")) {
                d.put("agreementId", o.get("agreementId"));
            }
            agreement(d, str(d.getOrDefault("partnerId", o.get("partnerId"))));
        }
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        if (in.containsKey("lines")) {
            writeLines(id, str(d.getOrDefault("partnerId", o.get("partnerId"))), str(d.getOrDefault("agreementId",
                    o.get("agreementId"))), LocalDate.now(BusinessZone.get()), in.get("lines"), str(d.getOrDefault("locationId",
                            o.get("locationId"))), PriceScope.of(o, d));
        }
        evaluateApproval(id);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("fields", d.keySet(), "lines",
                in.containsKey("lines"))));
        return get(id);
    }

    /** Soft-delete a draft. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be deleted; cancel it instead");
        }
        db.update("UPDATE \"SalesOrder\" SET \"deletedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        closeCreditRequests(id, "Order deleted");
        withdrawEngineRequest(o, "Order deleted");
        audit.record(AuditInput.of("SalesOrder", id, "delete"));
        return Obj.of("ok", true);
    }

    private org.springframework.beans.factory.ObjectProvider<com.inventoryerp.fleet.application.FleetDeliveryOrderService>
            deliveryOrders;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeliveryOrders(org.springframework.beans.factory.ObjectProvider<
            com.inventoryerp.fleet.application.FleetDeliveryOrderService> deliveryOrders) {
        this.deliveryOrders = deliveryOrders;
    }

    /** Sales readiness R4d (DEL-1): bring the order's live delivery order in step with its lines. */
    void resyncDeliveries(String id) {
        var service = deliveryOrders == null ? null : deliveryOrders.getIfAvailable();
        if (service != null) {
            service.resyncFromSource("sales_order", id);
        }
    }

    /** Sales readiness R3: a deleted or cancelled order leaves no credit request waiting for a decision. */
    private void closeCreditRequests(String id, String why) {
        db.update("UPDATE \"SalesCreditRequest\" SET status = 'cancelled', \"decisionComment\" = ?, \"decidedAt\" = " + NOW
                + ", \"updatedAt\" = " + NOW + " WHERE \"orderId\" = ? AND \"organizationId\" = ? AND status = 'pending'", why,
                id, org());
    }

    /** Copy of an order as a new draft (any status). */
    @Transactional
    public Map<String, Object> duplicate(String id) {
        Map<String, Object> o = require(id);
        Map<String, Object> in = new LinkedHashMap<>();
        for (String k : List.of("partnerId", "contactId", "branchId", "locationId", "siteId", "deliveryAddress",
                "deliveryMethod", "paymentTermId", "currencyCode", "incoterm", "notes", "terms", "agreementId",
                "orderDiscountPercent", "orderDiscountAmount", "paymentMode")) {
            in.put(k, o.get(k));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        LocalDate today = LocalDate.now(BusinessZone.get());
        for (Map<String, Object> l : lines(id)) {
            Map<String, Object> copy = Obj.of("productId", l.get("productId"), "description", l.get("description"),
                    "quantity", l.get("quantity"), "uomId", l.get("uomId"), "unitPrice", l.get("unitPrice"),
                    "discountPercent", l.get("discountPercent"), "taxId", l.get("taxId"), "taxInclusive",
                    l.get("taxInclusive"));
            // Sales readiness SO-9: the copy keeps every line attribute that decides what is sold and from where
            // (variant, source location, tolerance, size); a promised date already past is not carried over.
            for (String k : List.of("variantId", "sourceLocationId", "tolerancePct", "discountAmount", "pieces", "length",
                    "width", "height")) {
                if (l.get(k) != null) {
                    copy.put(k, l.get(k));
                }
            }
            LocalDate promised = l.get("promisedDate") == null ? null : SalesDb.date(str(l.get("promisedDate")));
            if (promised != null && !promised.isBefore(today)) {
                copy.put("promisedDate", promised.toString());
            }
            lines.add(copy);
        }
        in.put("lines", lines);
        return create(in);
    }

    private Map<String, Object> header(Map<String, Object> in, boolean create) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("contactId", "branchId", "locationId", "siteId", "deliveryAddress", "paymentTermId",
                "customerRef", "incoterm", "notes", "terms", "teamId", "agreementId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (in.containsKey("ownerId")) {
            d.put("ownerId", blank(str(in.get("ownerId"))) ? null : requireUser(str(in.get("ownerId"))));
        }
        if (in.containsKey("currencyCode") && !blank(str(in.get("currencyCode")))) {
            d.put("currencyCode", str(in.get("currencyCode")));
        }
        if (in.containsKey("paymentMode")) {
            String m = str(in.get("paymentMode"));
            if (!Set.of("credit", "cash").contains(m)) {
                throw ApiException.badRequest("paymentMode must be one of: cash, credit");
            }
            d.put("paymentMode", m);
        }
        if (in.containsKey("priority")) {
            d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        }
        for (String k : List.of("requestedDate", "commitmentDate")) {
            if (in.containsKey(k)) {
                d.put(k, SalesDb.date(str(in.get(k))));
            }
        }
        if (in.containsKey("deliveryMethod")) {
            String m = str(in.get("deliveryMethod"));
            if (!Set.of("delivery", "collect").contains(m)) {
                throw ApiException.badRequest("deliveryMethod must be one of: collect, delivery");
            }
            d.put("deliveryMethod", m);
        }
        for (String k : List.of("orderDiscountPercent", "orderDiscountAmount")) {
            if (in.containsKey(k)) {
                d.put(k, in.get(k) == null || dec(in.get(k)).signum() == 0 ? null : dec(in.get(k)));
            }
        }
        if (!create && (d.containsKey("orderDiscountPercent") || d.containsKey("orderDiscountAmount")) && !in.containsKey("lines")) {
            throw ApiException.badRequest("Send the lines with an order discount change");
        }
        if (in.containsKey("exchangeRate")) {
            BigDecimal r = dec(in.get("exchangeRate"));
            if (r.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than 0");
            }
            d.put("exchangeRate", r);
        }
        for (String k : List.of("branchId")) {
            if (d.get(k) != null && !db.exists("Branch", str(d.get(k)))) {
                throw ApiException.notFound("Branch not found");
            }
        }
        if (d.get("locationId") != null && !db.exists("InventoryLocation", str(d.get("locationId")))) {
            throw ApiException.notFound("Location not found");
        }
        if (d.get("paymentTermId") != null && !db.exists("PaymentTerm", str(d.get("paymentTermId")))) {
            throw ApiException.notFound("Payment term not found");
        }
        if (create && d.get("terms") == null) {
            d.put("terms", settings.settings().get("defaultQuoteTerms"));
        }
        // Sales readiness R4 (SO-12): every reference on the order belongs to the caller's organisation.
        for (String[] ref : new String[][] {{"locationId", "InventoryLocation", "Location not found"}, {"siteId", "FleetCustomerSite",
                        "Customer site not found"}, {"paymentTermId", "PaymentTerm", "Payment term not found"},
                {"teamId", "CrmTeam", "Team not found"}, {"branchId", "Branch", "Branch not found"}}) {
            if (d.get(ref[0]) != null && !db.exists(ref[1], str(d.get(ref[0])))) {
                throw ApiException.notFound(ref[2]);
            }
        }
        return d;
    }

    /** SG-5: the customer's home branch, currency and payment term fill what the order leaves empty. */
    private void customerDefaults(Map<String, Object> header, String partnerId) {
        Map<String, Object> p = db.row("SELECT \"homeBranchId\", \"defaultCurrencyCode\", \"paymentTermId\" FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?", partnerId, org());
        if (p == null) {
            return;
        }
        if (header.get("branchId") == null && p.get("homeBranchId") != null) {
            header.put("branchId", p.get("homeBranchId"));
        }
        if (header.get("currencyCode") == null && p.get("defaultCurrencyCode") != null) {
            header.put("currencyCode", p.get("defaultCurrencyCode"));
        }
        if (header.get("paymentTermId") == null && p.get("paymentTermId") != null) {
            header.put("paymentTermId", p.get("paymentTermId"));
        }
    }

    /** Validate the header's agreement for {@code partnerId}; its payment term applies when none is given. */
    private void agreement(Map<String, Object> header, String partnerId) {
        String agreementId = str(header.get("agreementId"));
        if (agreementId == null) {
            return;
        }
        Map<String, Object> a = agreements.requireReleasable(agreementId, partnerId);
        if (header.get("paymentTermId") == null && a.get("paymentTermId") != null) {
            header.put("paymentTermId", a.get("paymentTermId"));
        }
    }

    /** Replace the lines of a draft order (price resolved for product lines without an explicit price). */
    /** The order header fields that select price rules. */
    record PriceScope(String siteId, String branchId, String currencyCode) {

        static PriceScope of(Map<String, Object> order) {
            return new PriceScope(str(order.get("siteId")), str(order.get("branchId")), str(order.get("currencyCode")));
        }

        /** {@code changes} win over the stored order. */
        static PriceScope of(Map<String, Object> order, Map<String, Object> changes) {
            Map<String, Object> merged = new LinkedHashMap<>(order);
            merged.putAll(changes);
            return of(merged);
        }
    }

    @SuppressWarnings("unchecked")
    void writeLines(String orderId, String partnerId, String agreementId, LocalDate on, Object raw,
            String defaultLocation, PriceScope scope) {
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        // ADR-S086: the PLM lifecycle policy may refuse selling a product (obsolete) — checked before anything is
        // written, and only for products added to the document: lines it already had stay editable.
        lifecycleGuard.ifAvailable(g -> {
            java.util.Set<Object> had = new java.util.HashSet<>();
            db.rows("SELECT DISTINCT \"productId\" FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?",
                    orderId, org()).forEach(r -> had.add(r.get("productId")));
            g.requireAllowed(ProductLifecycleGuard.SELL, lines.stream()
                    .map(x -> x.get("productId") instanceof String p && !had.contains(p) ? p : null).toList());
        });
        db.update("DELETE FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId, org());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            rows.add(line(partnerId, agreementId, on, l, defaultLocation, scope));
        }
        applyOrderDiscount(orderId, rows);
        insertLines(orderId, rows, 0);
        retotal(orderId);
    }

    /** SG-6: spread the header discount over the lines. The line keeps its own discount for later edits. */
    private void applyOrderDiscount(String orderId, List<Map<String, Object>> rows) {
        Map<String, Object> h = db.row("SELECT \"orderDiscountPercent\" AS p, \"orderDiscountAmount\" AS a FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", orderId, org());
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, Object> m : rows) {
            net = net.add(SalesDiscounts.net(dec(m.get("quantity")), dec(m.get("unitPrice")), dec(m.get("discountPercent"))));
        }
        BigDecimal percent = SalesDiscounts.orderPercent(h.get("p") == null ? null : dec(h.get("p")), h.get("a") == null ? null
                : dec(h.get("a")), net);
        if (percent.signum() == 0) {
            return;
        }
        for (Map<String, Object> m : rows) {
            m.put("lineDiscountPercent", m.get("discountPercent"));
            m.put("discountPercent", SalesDiscounts.combine(dec(m.get("discountPercent")), percent));
        }
    }

    /** Validated, priced line values (not yet taxed). */
    Map<String, Object> line(String partnerId, String agreementId, LocalDate on, Map<String, Object> l,
            String defaultLocation, PriceScope scope) {
        String productId = blank(str(l.get("productId"))) ? null : str(l.get("productId"));
        // ADR-S066: a line sold by size gets its quantity from pieces × length [× width [× height]]
        ProductSizingService.Sizing size = sizing.fromLine(productId, l);
        BigDecimal qty = size != null ? size.quantity() : dec(l.get("quantity"));
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        ProductVariantService.VariantRef variant = variants.resolve(productId, str(l.get("variantId")));
        String variantId = variant == null ? null : variant.id();
        QuotationPricingPort.Price price = productId == null ? null : pricing.price(partnerId, productId, variantId, qty,
                blank(str(l.get("uomId"))) ? null : str(l.get("uomId")), on, agreementId, scope.siteId(), scope.branchId(),
                scope.currencyCode());
        BigDecimal unit;
        BigDecimal disc;
        String source;
        if (l.get("unitPrice") != null || l.get("discountPercent") != null || l.get("discountAmount") != null) {
            unit = l.get("unitPrice") == null ? price == null ? BigDecimal.ZERO : price.unitPrice() : dec(l.get("unitPrice"));
            disc = l.get("discountPercent") == null ? price == null ? BigDecimal.ZERO : price.discountPercent()
                    : dec(l.get("discountPercent"));
            if (l.get("discountAmount") != null) {
                disc = SalesDiscounts.lineAmountToPercent(dec(l.get("discountAmount")), qty, unit);
            }
            boolean same = price != null && unit.compareTo(price.unitPrice()) == 0
                    && disc.compareTo(price.discountPercent()) == 0;
            source = !blank(str(l.get("priceSource"))) ? str(l.get("priceSource")) : same ? price.source() : "manual";
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
        String location = blank(str(l.get("sourceLocationId"))) ? defaultLocation : str(l.get("sourceLocationId"));
        if (location != null && !db.exists("InventoryLocation", location)) {
            throw ApiException.notFound("Location not found");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("variantId", variantId);
        m.put("description", description);
        m.put("quantity", qty);
        m.put("uomId", blank(str(l.get("uomId"))) ? null : str(l.get("uomId")));
        m.put("listPrice", l.get("listPrice") != null ? dec(l.get("listPrice")) : price == null ? unit : price.listPrice());
        m.put("unitPrice", unit);
        m.put("discountPercent", disc);
        m.put("priceSource", source);
        m.put("priceRuleId", price == null || !"rule".equals(source) ? null : price.ruleId());
        m.put("taxId", l.containsKey("taxId") ? (blank(str(l.get("taxId"))) ? null : str(l.get("taxId")))
                : price == null ? null : price.taxId());
        m.put("taxInclusive", l.get("taxInclusive") instanceof Boolean b ? b : price != null && price.taxInclusive());
        m.put("promisedDate", SalesDb.date(str(l.get("promisedDate"))));
        m.put("sourceLocationId", location);
        m.put("tolerancePct", l.get("tolerancePct") == null ? BigDecimal.ZERO : dec(l.get("tolerancePct")));
        m.put("quotationLineId", str(l.get("quotationLineId")));
        m.putAll(ProductSizingService.Sizing.columns(size));
        return m;
    }

    /** Insert priced lines (taxed by the invoicing tax engine) starting at {@code sortFrom}; returns new ids. */
    List<String> insertLines(String orderId, List<Map<String, Object>> rows, int sortFrom) {
        List<String> ids = new ArrayList<>();
        if (rows.isEmpty()) {
            return ids;
        }
        List<LineInput> inputs = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            inputs.add(new LineInput(str(m.get("productId")), null, null, null, null, str(m.get("description")),
                    dec(m.get("quantity")), dec(m.get("unitPrice")), dec(m.get("discountPercent")), null, null, null,
                    "manual", str(m.get("taxId")), null, Boolean.TRUE.equals(m.get("taxInclusive")),
                    str(m.get("uomId"))).withUnitSnapshot(str(m.get("uomId")), frozenFactor(m))
                    .withSizing(ProductSizingService.Sizing.stored(m))); // U-10: the builder gives a sized line its unit
        }
        Totals totals = builder.prepareLines(inputs);
        int i = 0;
        for (PreparedLine pl : totals.prepared()) {
            Map<String, Object> m = rows.get(i);
            BigDecimal cost = pl.productId() == null ? null : costOf(pl.productId(), str(m.get("variantId")));
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("orderId", orderId);
            ins.put("sortOrder", sortFrom + i);
            ins.put("productId", pl.productId());
            ins.put("variantId", m.get("variantId"));
            ins.put("description", pl.description());
            ins.put("quantity", pl.quantity());
            ins.put("uomId", pl.uomId());
            // Phase I1b (U-1): a product line always carries its resolved factor; only lines without a product have none.
            if (pl.productId() != null && pl.uomFactor() == null) {
                throw ApiException.badRequest("The unit of measure of " + pl.description() + " could not be resolved");
            }
            ins.put("uomFactor", pl.uomFactor() == null ? BigDecimal.ONE : pl.uomFactor());
            ins.put("listPrice", m.get("listPrice"));
            ins.put("unitPrice", pl.unitPrice());
            ins.put("discountPercent", pl.discountPercent());
            ins.put("lineDiscountPercent", m.get("lineDiscountPercent"));
            ins.put("priceSource", m.get("priceSource"));
            ins.put("priceRuleId", m.get("priceRuleId"));
            ins.put("taxId", pl.taxId());
            ins.put("taxInclusive", pl.taxInclusive());
            ins.put("lineSubtotal", pl.subtotal());
            ins.put("lineTax", pl.taxAmount());
            ins.put("lineTotal", pl.total());
            ins.put("promisedDate", m.get("promisedDate"));
            ins.put("sourceLocationId", m.get("sourceLocationId"));
            ins.put("costSnapshot", cost);
            ins.put("tolerancePct", m.get("tolerancePct"));
            ins.put("quotationLineId", m.get("quotationLineId"));
            ins.putAll(ProductSizingService.Sizing.columns(ProductSizingService.Sizing.stored(m)));
            ids.add(str(db.insert("SalesOrderLine", ins).get("id")));
            i++;
        }
        return ids;
    }

    /**
     * Phase I2 (audit U-5): the unit factor a copied line keeps — the one saved on the line it splits
     * ({@code savedUomFactor}) or on the quotation line it comes from, when it is still in that unit. Null = resolve
     * the unit now (a new line, or the unit changed).
     */
    private BigDecimal frozenFactor(Map<String, Object> m) {
        String uomId = str(m.get("uomId"));
        if (blank(uomId)) {
            return null;
        }
        if (m.get("savedUomFactor") != null) {
            return dec(m.get("savedUomFactor"));
        }
        String quotationLineId = str(m.get("quotationLineId"));
        if (blank(quotationLineId)) {
            return null;
        }
        Map<String, Object> q = db.row("SELECT \"uomId\", \"uomFactor\" FROM \"SalesQuotationLine\" WHERE id = ? AND"
                + " \"organizationId\" = ?", quotationLineId, TenantContext.organizationId());
        return q != null && uomId.equals(str(q.get("uomId"))) && q.get("uomFactor") != null ? dec(q.get("uomFactor"))
                : null;
    }

    /** Validated variant id of a line for {@code productId} (ADR-S058; a product with variants needs one). */
    String variantFor(String productId, String variantId) {
        ProductVariantService.VariantRef v = variants.resolve(productId, variantId);
        return v == null ? null : v.id();
    }

    /**
     * Review U-5: a line copied from another document keeps that document's saved unit factor (the line's unit is
     * unchanged), instead of the factor re-resolved from today's product setup.
     */
    void keepSavedFactor(String lineId, Object uomId, Object uomFactor) {
        if (lineId == null || uomFactor == null || dec(uomFactor).signum() <= 0) {
            return;
        }
        db.update("UPDATE \"SalesOrderLine\" SET \"uomFactor\" = ? WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"uomId\" IS NOT DISTINCT FROM ?", dec(uomFactor), lineId, org(), uomId);
    }

    BigDecimal costOf(String productId, String variantId) {
        if (variantId != null) {
            ProductVariantService.VariantRef v = variants.find(variantId);
            if (v != null && v.costPrice() != null) {
                return v.costPrice();
            }
        }
        Map<String, Object> r = db.row("SELECT \"costPrice\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?",
                productId, org());
        return r == null || r.get("costPrice") == null ? null : dec(r.get("costPrice"));
    }

    /** Recompute header totals and the highest discount vs list price from the lines. */
    void retotal(String orderId) {
        Map<String, Object> t = db.row("SELECT COALESCE(sum(\"lineSubtotal\"), 0) AS sub, COALESCE(sum(\"lineTax\"), 0)"
                + " AS tax, COALESCE(sum(\"lineTotal\"), 0) AS tot, COALESCE(sum(quantity * \"unitPrice\" * \"discountPercent\""
                + " / 100), 0) AS disc, COALESCE(max(CASE WHEN \"listPrice\" > 0 AND \"unitPrice\" * (1 - \"discountPercent\""
                + " / 100) < \"listPrice\" THEN (1 - \"unitPrice\" * (1 - \"discountPercent\" / 100) / \"listPrice\") * 100"
                + " END), 0) AS maxdisc FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId,
                org());
        db.update("UPDATE \"SalesOrder\" SET subtotal = ?, \"taxAmount\" = ?, total = ?, \"discountTotal\" = ?,"
                + " \"maxDiscountPercent\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                dec(t.get("sub")), dec(t.get("tax")), dec(t.get("tot")), dec(t.get("disc")).setScale(6,
                        RoundingMode.HALF_UP), dec(t.get("maxdisc")).min(HUNDRED).setScale(4, RoundingMode.HALF_UP),
                orderId, org());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Approval entity of sales orders (kernel approval engine). */
    public static final String APPROVAL_ENTITY = "sales_order";

    /**
     * Confirm a draft order. ADR-S094 §4.2: when commercial policy (extra discount beyond the authorised price, order
     * amount, margin floor, agreement release) asks for approval, the order is confirmed only once an approval covers
     * its current content and reasons; it goes to approval through {@link #submitApproval}, never implicitly here. On
     * confirmation the exchange rate is locked and a customer on credit hold puts the order on hold (credit exposure
     * checks: S3).
     */
    @Transactional
    public Map<String, Object> confirm(String id) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be confirmed");
        }
        requirePartner(str(o.get("partnerId")));
        List<Map<String, Object>> lines = db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY"
                + " \"sortOrder\"", id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line before confirming");
        }
        Map<String, Object> policy = settings.settings();
        List<Map<String, Object>> shortages = "none".equals(policy.get("stockCheckPolicy")) ? List.of()
                : atp.shortages(id);
        if ("block".equals(policy.get("stockCheckPolicy")) && !shortages.isEmpty()) {
            throw ApiException.badRequest("Not enough stock to confirm this order: short by "
                    + shortages.get(0).get("shortage") + " on " + shortDescription(lines, shortages.get(0)));
        }
        Approval approval = effectiveApproval(o, lines);
        if ("pending".equals(approval.status())) {
            throw ApiException.badRequest("This sale is waiting for approval");
        }
        if (!Set.of("not_required", "approved").contains(approval.status())) {
            throw ApiException.badRequest("This sale needs approval: submit it for approval first");
        }
        return finishConfirm(id);
    }

    // ------------------------------------------------------------------ approval (ADR-S094 §4.2)

    /** The approval state of a draft as it is now, its current reasons and the hash of its approvable content. */
    private record Approval(String status, List<String> reasons, String hash) {}

    private List<Map<String, Object>> orderLines(String id) {
        return db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY \"sortOrder\", id", id);
    }

    /**
     * The approval state of the order as it stands: an approval holds while the approvable content is the one approved
     * and every current reason was approved (a stricter rule asks only for its new reasons; a relaxed one revokes
     * nothing); a rejection holds while the content is the one rejected; otherwise the current reasons decide.
     */
    private Approval effectiveApproval(Map<String, Object> o, List<Map<String, Object>> lines) {
        return effectiveApproval(o, lines, settings.settings());
    }

    private Approval effectiveApproval(Map<String, Object> o, List<Map<String, Object>> lines, Map<String, Object> rules) {
        List<String> reasons = approvalReasons(o, lines, rules);
        String hash = SalesApprovalContent.hash(o, lines);
        String need = reasons.isEmpty() ? "not_required" : "required";
        String status = switch (str(o.get("approvalStatus"))) {
            case "pending" -> "pending";
            case "rejected" -> hash.equals(o.get("approvalSubmittedHash")) ? "rejected" : need;
            case "approved" -> hash.equals(o.get("approvedHash")) && SalesApprovalContent.covered(reasons,
                    SalesDb.strings(o.get("approvedReasons"))) ? "approved" : need;
            case null, default -> need;
        };
        return new Approval(status, reasons, hash);
    }

    /**
     * Re-evaluate a draft's approval after a save: an approval or a rejection lapses when the approvable content
     * changed, the current reasons are recorded, and so is who last changed approvable content (separation of
     * duties). While the order waits for approval its approvable content cannot change.
     */
    void evaluateApproval(String id) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        if (o == null || !"draft".equals(o.get("status"))) {
            return;
        }
        Approval a = effectiveApproval(o, orderLines(id));
        if ("pending".equals(a.status()) && !a.hash().equals(o.get("approvalSubmittedHash"))) {
            throw ApiException.badRequest("This sale is waiting for approval: withdraw it before changing its customer,"
                    + " lines, prices or terms");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (!a.hash().equals(o.get("approvalContentHash"))) {
            d.put("approvalContentHash", a.hash());
            d.put("approvalContentEditedById", user());
        }
        d.put("approvalStatus", a.status());
        if (!Set.of("pending", "rejected").contains(a.status())) {
            d.put("approvalReasons", a.reasons());
        }
        db.patch("SalesOrder", id, d, false);
    }

    /**
     * Submit a draft for approval (explicit; ADR-S094 §4.2). Only an order that needs approval can be submitted; one
     * rejected and unchanged since needs a written justification. The approval request is bound to the approvable
     * content (hash), the reasons and the approval rules in force.
     */
    @Transactional
    public Map<String, Object> submitApproval(String id, String justification) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be submitted for approval");
        }
        List<Map<String, Object>> lines = orderLines(id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line before submitting it for approval");
        }
        Approval a = effectiveApproval(o, lines);
        switch (a.status()) {
            case "pending" -> throw ApiException.badRequest("This sale is already waiting for approval");
            case "not_required" -> throw ApiException.badRequest("This sale needs no approval; confirm it");
            case "approved" -> throw ApiException.badRequest("This sale is already approved; confirm it");
            case "rejected" -> {
                if (blank(justification)) {
                    throw ApiException.badRequest("This sale was rejected and has not changed since: give a"
                            + " justification to submit it again");
                }
            }
            default -> { }
        }
        Map<String, Object> snapshot = Obj.of("number", o.get("number"), "partnerId", o.get("partnerId"), "total",
                o.get("total"), "amount", o.get("total"), "maxDiscountPercent", o.get("maxDiscountPercent"), "reasons",
                a.reasons(), "justification", blank(justification) ? null : justification, "makerIds", String.join(",",
                        java.util.stream.Stream.of(str(o.get("createdById")), user(), str(o.get("approvalContentEditedById")))
                                .filter(m -> !blank(m)).distinct().toList()));
        Map<String, Object> req = approvals.requestApproval(APPROVAL_ENTITY, id, snapshot, null);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approvalStatus", "pending");
        d.put("approvalReasons", a.reasons());
        d.put("approvalSubmittedHash", a.hash());
        d.put("approvalSubmittedReasons", a.reasons());
        d.put("approvalRuleVersion", SalesApprovalContent.ruleVersion(settings.settings()));
        d.put("approvalSubmittedById", user());
        d.put("approvalSubmittedAt", Instant.now());
        d.put("approvalJustification", blank(justification) ? null : justification);
        d.put("approvalRequestId", req == null ? null : req.get("id"));
        d.put("approvalContentHash", a.hash());
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("approval", "submitted", "reasons", a.reasons(),
                "justification", blank(justification) ? null : justification)));
        return get(id);
    }

    /** Withdraw a submission (the submitter, or a sales administrator); the order can be edited again. */
    @Transactional
    public Map<String, Object> withdrawApproval(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status")) || !"pending".equals(o.get("approvalStatus"))) {
            throw ApiException.badRequest("This sale is not waiting for approval");
        }
        if (!java.util.Objects.equals(user(), o.get("approvalSubmittedById"))
                && !TenantContext.has(Permissions.SALES__CONFIGURE)) {
            throw ApiException.forbidden("Only the person who submitted this sale for approval can withdraw it");
        }
        withdrawEngineRequest(o, blank(reason) ? "Withdrawn by the submitter" : reason);
        List<String> reasons = approvalReasons(o, orderLines(id));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approvalStatus", reasons.isEmpty() ? "not_required" : "required");
        d.put("approvalReasons", reasons);
        d.put("approvalRequestId", null);
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("approval", "withdrawn", "reason", reason)));
        return get(id);
    }

    /** The order's approvals-engine request, if still pending, is withdrawn (its inbox item resolves). */
    private void withdrawEngineRequest(Map<String, Object> o, String reason) {
        if ("pending".equals(o.get("approvalStatus")) && !blank(str(o.get("approvalRequestId")))) {
            approvals.withdraw(str(o.get("approvalRequestId")), reason);
        }
    }

    /**
     * OD-8: an approver is not the order's creator, its submitter or the last person who changed its approvable
     * content — unless the organization allows sales administrators to approve their own (audited). Null = allowed.
     */
    private String selfApprovalProblem(Map<String, Object> o) {
        String me = user();
        if (me == null || !(me.equals(o.get("createdById")) || me.equals(o.get("approvalSubmittedById"))
                || me.equals(o.get("approvalContentEditedById")))) {
            return null;
        }
        Map<String, Object> s = db.row("SELECT \"allowSelfApproval\" FROM \"SalesSettings\" WHERE \"organizationId\" = ?",
                org());
        if (s != null && Boolean.TRUE.equals(s.get("allowSelfApproval")) && TenantContext.has(Permissions.SALES__CONFIGURE)) {
            return null;
        }
        return "You cannot approve your own order";
    }

    /** What followed an approval: the confirmed order, or why the confirm that followed was refused (both null: none). */
    private record Approved(Map<String, Object> confirmed, String confirmError) {}

    /**
     * Record the approval (bound to the submitted content, reasons and rules); an order (never a cash sale) then
     * confirms at once when the organization says so, with every confirm check — a refusal rolls back only the confirm
     * (savepoint) and leaves the order approved.
     */
    private Approved markApproved(String id, Map<String, Object> o, String comment, String via) {
        List<String> approved = new ArrayList<>(SalesDb.strings(o.get("approvalSubmittedReasons")));
        // a new approval of the same content (after a stricter rule) keeps what was approved before it
        if (o.get("approvedHash") != null && o.get("approvedHash").equals(o.get("approvalSubmittedHash"))) {
            SalesDb.strings(o.get("approvedReasons")).stream().filter(r -> !approved.contains(r)).forEach(approved::add);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approvalStatus", "approved");
        d.put("approvedHash", o.get("approvalSubmittedHash"));
        d.put("approvedReasons", approved);
        d.put("approvedRuleVersion", o.get("approvalRuleVersion"));
        d.put("approvedById", user());
        d.put("approvedAt", Instant.now());
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        // OD-8: an approval by one of the order's makers (allowed by the organization) is marked in the audit trail
        boolean self = user() != null && (user().equals(o.get("createdById")) || user().equals(o.get(
                "approvalSubmittedById")) || user().equals(o.get("approvalContentEditedById")));
        audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("comment", comment, "via", via,
                "selfApproval", self)));
        if ("order".equals(o.get("saleType")) && !Boolean.FALSE.equals(settings.settings().get("confirmOnApproval"))) {
            Map<String, Object>[] out = new Map[1];
            try {
                Savepoints.run(db.jdbc(), () -> out[0] = confirm(id));
                return new Approved(out[0], null);
            } catch (ApiException refused) {
                return new Approved(null, refused.getMessage());
            }
        }
        return new Approved(null, null);
    }

    private static String shortDescription(List<Map<String, Object>> lines, Map<String, Object> shortage) {
        return lines.stream().filter(l -> l.get("id").equals(shortage.get("lineId"))).map(l -> str(l.get("description")))
                .findFirst().orElse("a line");
    }

    /** Why this order needs commercial approval (empty = within policy). */
    List<String> approvalReasons(Map<String, Object> o, List<Map<String, Object>> lines) {
        return approvalReasons(o, lines, settings.settings());
    }

    private List<String> approvalReasons(Map<String, Object> o, List<Map<String, Object>> lines, Map<String, Object> s) {
        List<String> reasons = new ArrayList<>(engine.breaches(str(o.get("partnerId")), str(o.get("branchId")),
                LocalDate.now(BusinessZone.get()), lines.stream().filter(l -> !Set.of("quotation", "agreement").contains(str(l.get(
                        "priceSource")))).toList(), dec(s.get("orderMaxDiscountPercent")), Boolean.TRUE.equals(s.get(
                                "marginCheck"))));
        reasons.addAll(agreements.releaseReasons(o, lines));
        if (Boolean.TRUE.equals(s.get("orderApprovalAlways"))) {
            reasons.add("Every sales order needs approval");
        }
        BigDecimal amount = dec(s.get("orderApprovalAmount"));
        if (amount.signum() > 0 && dec(o.get("total")).compareTo(amount) > 0) {
            reasons.add("Total exceeds the approval amount of " + amount.stripTrailingZeros().toPlainString());
        }
        return reasons;
    }

    /**
     * Approve a submitted order (ADR-S094 §4.2; OD-8 separation of duties) — bound to the content submitted — and, for
     * an order, confirm it when the organization says so.
     */
    @Transactional
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status")) || !"pending".equals(o.get("approvalStatus"))) {
            throw ApiException.badRequest("Order is not awaiting approval");
        }
        String self = selfApprovalProblem(o);
        if (self != null) {
            throw ApiException.forbidden(self);
        }
        if (!SalesApprovalContent.hash(o, orderLines(id)).equals(o.get("approvalSubmittedHash"))) {
            throw ApiException.badRequest("This sale changed after it was submitted for approval; withdraw it and submit"
                    + " it again");
        }
        // Sales readiness SO-10: the order's approvals-engine request is decided through the engine (its steps,
        // limits and segregation of duties apply), so the inbox item resolves; a multi-step request that still waits
        // for another approver leaves the order pending.
        if (decideEngineRequest(o, "approved", comment)) {
            audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("comment", comment, "step", "partial")));
            return get(id);
        }
        Approved approved = markApproved(id, o, comment, "approver");
        if (approved.confirmed() != null) {
            return approved.confirmed();
        }
        Map<String, Object> out = get(id);
        if (approved.confirmError() != null) {
            out.put("confirmError", approved.confirmError());
        }
        return out;
    }

    /**
     * Decide the order's pending approvals-engine request, if it has one. Returns true when the request still waits for
     * another decision (the order stays pending approval).
     */
    private boolean decideEngineRequest(Map<String, Object> o, String status, String comment) {
        String reqId = str(o.get("approvalRequestId"));
        if (blank(reqId)) {
            return false;
        }
        Map<String, Object> req = approvals.findOne(reqId);
        if (req == null || !"pending".equalsIgnoreCase(String.valueOf(req.get("status")))) {
            return false;
        }
        Map<String, Object> decided = approvals.decide(reqId, status, comment);
        return "approved".equals(status) && "pending".equalsIgnoreCase(String.valueOf(decided.get("status")));
    }

    /** Reject a submitted order: it stays a draft, {@code rejected}, with the reason. */
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status")) || !"pending".equals(o.get("approvalStatus"))) {
            throw ApiException.badRequest("Order is not awaiting approval");
        }
        decideEngineRequest(o, "rejected", blank(reason) ? "no reason given" : reason);
        db.patch("SalesOrder", id, Obj.of("approvalStatus", "rejected", "approvalReasons", List.of("Rejected: "
                + (blank(reason) ? "no reason given" : reason)), "approvalRequestId", null, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /**
     * Apply a kernel approval-engine decision (idempotent: ignored unless still pending, and ignored when it answers a
     * request that is no longer the order's current one).
     */
    @Transactional
    public void applyDecision(String id, String requestId, boolean approved, String comment) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                id, org());
        if (o == null || !"draft".equals(o.get("status")) || !"pending".equals(o.get("approvalStatus"))) {
            return;
        }
        // A decision on a superseded request (the order was rejected and resubmitted since) must not decide the new one.
        if (!blank(requestId) && !requestId.equals(String.valueOf(o.get("approvalRequestId")))) {
            return;
        }
        if (approved) {
            // OD-8 / §4.2: an engine approval by one of the order's makers (separation of duties), or of content that
            // is no longer the content submitted, does not approve the sale: it has to be submitted again.
            String self = selfApprovalProblem(o);
            boolean changed = !SalesApprovalContent.hash(o, orderLines(id)).equals(o.get("approvalSubmittedHash"));
            if (self != null || changed) {
                db.patch("SalesOrder", id, Obj.of("approvalStatus", "required", "approvalRequestId", null), true);
                audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("approval", "refused", "via", "approval_engine",
                        "reason", self != null ? self : "content changed after it was submitted")));
                return;
            }
            Approved done = markApproved(id, o, comment, "approval_engine");
            if (done.confirmError() != null) {
                // nobody waits for this response: the refusal of the confirm that followed is kept in the audit trail
                audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("approval", "approved",
                        "confirmError", done.confirmError())));
            }
            return;
        }
        db.patch("SalesOrder", id, Obj.of("approvalStatus", "rejected", "approvalReasons", List.of("Rejected: "
                + (blank(comment) ? "rejected in approval workflow" : comment)), "approvalRequestId", null), true);
        audit.record(AuditInput.of("SalesOrder", id, "reject", null, Obj.of("via", "approval_engine")));
    }

    private Map<String, Object> finishConfirm(String id) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                id, org());
        // Sales readiness R3 (P1-1) / ADR-S094: only a live draft whose approval condition confirm() checked.
        if (o == null || o.get("deletedAt") != null || !"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be confirmed");
        }
        Map<String, Object> policy0 = settings.settings();
        boolean cash = "cash".equals(o.get("paymentMode")) && Boolean.TRUE.equals(policy0.get("cashBypassesCredit"));
        CreditService.Decision c = cash ? new CreditService.Decision(null, "pass", List.of(), Map.of())
                : credit.check("order_confirm", str(o.get("partnerId")), "sales_order", id, str(o.get("number")),
                        inBase(o, dec(o.get("total"))), str(o.get("branchId")));
        String reason = String.join("; ", c.reasons());
        // SG-8: a hold on the customer always stops the order; going over the limit follows the organization's policy.
        boolean overLimit = !c.passed() && !"blocked".equals(c.result());
        boolean covered = overLimit && creditRequests.approvedFor(id, dec(o.get("total")));
        if (overLimit && !covered) {
            String over = str(policy0.get("overLimitPolicy"));
            if ("block".equals(over)) {
                throw ApiException.badRequest("This order exceeds the customer's credit: " + reason + ". Ask for a credit approval"
                        + " or take payment first.");
            }
            if ("approve".equals(over)) {
                Map<String, Object> request = creditRequests.open(o, "Over the credit limit: " + reason);
                Map<String, Object> waiting = get(id);
                waiting.put("creditRequest", request);
                return waiting;
            }
        }
        boolean hold = !c.passed() && !covered;
        Map<String, Object> d = Obj.of("status", "confirmed", "confirmedAt", Instant.now(), "confirmedById", user(),
                "approvalReasons", List.of(), "version", ((Number) o.get("version")).intValue() + 1);
        if (!Set.of("not_required", "approved").contains(o.get("approvalStatus"))) {
            // confirm() found no reason for approval (a stale "required" from before a rule was relaxed)
            d.put("approvalStatus", "not_required");
        }
        if (hold) {
            d.put("onHold", true);
            d.put("holdReason", reason);
            d.put("holdKind", "blocked".equals(c.result()) ? "customer" : "credit");
        }
        db.patch("SalesOrder", id, d, true);
        audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("status", "confirmed", "onHold", hold,
                "creditDecision", c.id())));
        Map<String, Object> policy = settings.settings();
        if ("confirm".equals(policy.get("reservationTrigger"))) {
            atp.reserveOrder(id);
        }
        snapshots.record("sales_order", id);
        publish(DomainEvents.SALES_ORDER_CONFIRMED, id, Map.of());
        if (hold) {
            publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", reason));
            if (Boolean.TRUE.equals(policy.get("notifyOnHold"))) {
                alerts.order(o, "sales_order_hold", "Order " + o.get("number") + " is on hold", reason);
            }
        }
        List<Map<String, Object>> shortages = "none".equals(policy.get("stockCheckPolicy")) ? List.of()
                : atp.shortages(id);
        if (!shortages.isEmpty() && Boolean.TRUE.equals(policy.get("notifyOnShortage"))) {
            alerts.order(o, "sales_order_shortage", "Order " + o.get("number") + " is short of stock", shortages.size()
                    + " line(s) cannot be covered from stock today.");
        }
        Map<String, Object> out = get(id);
        out.put("stockWarnings", shortages);
        return out;
    }

    /**
     * SG-8: confirm a draft after its credit request was approved. Sales readiness R3 (P1-1): this runs the full
     * {@link #confirm} checks (lines, stock policy, commercial approval), so a credit approval never confirms an order
     * past them; the approved request then covers the credit check. An order waiting for commercial approval stays
     * there (its approval confirms it).
     */
    @Transactional
    public Map<String, Object> confirmAfterCredit(String id) {
        // A refusal rolls back only this attempt (savepoint, called on this instance so the transaction is not marked
        // rollback-only): the caller's credit decision still commits and the refusal is returned as confirmError.
        Map<String, Object>[] out = new Map[1];
        try {
            Savepoints.run(db.jdbc(), () -> out[0] = confirm(id));
            return out[0];
        } catch (ApiException refused) {
            Map<String, Object> o = get(id);
            o.put("confirmError", refused.getMessage());
            return o;
        }
    }

    /** Put an open order on hold (no fulfilment while held). */
    @Transactional
    public Map<String, Object> hold(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (!OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be put on hold");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        db.patch("SalesOrder", id, Obj.of("onHold", true, "holdReason", reason, "holdKind", "manual", "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("onHold", true, "reason", reason)));
        publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", reason));
        if (Boolean.TRUE.equals(settings.settings().get("notifyOnHold"))) {
            alerts.order(o, "sales_order_hold", "Order " + o.get("number") + " is on hold", reason);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> release(String id) {
        Map<String, Object> o = lock(id);
        if (!Boolean.TRUE.equals(o.get("onHold"))) {
            throw ApiException.badRequest("Order is not on hold");
        }
        Map<String, Object> partner = db.row("SELECT \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", o.get("partnerId"), org());
        if (partner != null && Boolean.TRUE.equals(partner.get("creditHold"))) {
            throw ApiException.badRequest("Customer is on credit hold; lift the hold on the customer first");
        }
        if (!"manual".equals(o.get("holdKind")) && o.get("holdKind") != null) {
            credit.override(str(o.get("partnerId")), "sales_order", id, str(o.get("number")), null);
        }
        db.patch("SalesOrder", id, Obj.of("onHold", false, "holdReason", null, "holdKind", null, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("onHold", false)));
        publish(DomainEvents.SALES_ORDER_RELEASED, id, Map.of());
        return get(id);
    }

    /** Cancel an order that has nothing delivered or invoiced (close short what remains otherwise). */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (Set.of("cancelled", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("Order cannot be cancelled");
        }
        Map<String, Object> moved = db.row("SELECT COALESCE(sum(\"qtyDelivered\"), 0) AS d, COALESCE(sum(\"qtyInvoiced\"),"
                + " 0) AS i FROM \"SalesOrderLine\" WHERE \"orderId\" = ?", id);
        if (dec(moved.get("d")).signum() > 0 || dec(moved.get("i")).signum() > 0) {
            throw ApiException.badRequest("Order has deliveries or invoices; close the remaining quantities short instead");
        }
        if (db.count("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'sales_order' AND \"sourceId\" = ? AND status NOT IN ('cancelled')", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Cancel the delivery orders of this order first");
        }
        if (OPEN.contains(o.get("status")) && blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        if (db.count("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND \"sourceType\" = 'sales_order'"
                + " AND \"sourceId\" = ? AND \"documentType\"::text <> 'proforma_invoice' AND status::text NOT IN"
                + " ('cancelled', 'rejected')", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Cancel the invoices of this order first");
        }
        if (db.count("SELECT count(*) FROM \"CustomerDeposit\" WHERE \"organizationId\" = ? AND \"salesOrderId\" = ? AND"
                + " status = 'open' AND amount - \"appliedAmount\" - \"refundedAmount\" > 0", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Refund or apply the deposits of this order first");
        }
        if (db.count("SELECT count(*) FROM \"SalesOrderChangeRequest\" WHERE \"organizationId\" = ? AND \"orderId\" = ? AND"
                + " status = 'pending_approval'", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Decide the pending change requests of this order first");
        }
        withdrawEngineRequest(o, "Order cancelled");
        Map<String, Object> cancelled = Obj.of("status", "cancelled", "cancelledAt", Instant.now(), "cancelledById",
                user(), "cancelReason", blank(reason) ? null : reason, "onHold", false, "version",
                ((Number) o.get("version")).intValue() + 1);
        if ("pending".equals(o.get("approvalStatus"))) {
            cancelled.put("approvalStatus", "required");
            cancelled.put("approvalRequestId", null);
        }
        db.patch("SalesOrder", id, cancelled, true);
        audit.record(AuditInput.of("SalesOrder", id, "cancel", null, Obj.of("reason", reason)));
        closeCreditRequests(id, "Order cancelled");
        atp.releaseOrder(id);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", blank(reason) ? null : reason);
        publish(DomainEvents.SALES_ORDER_CANCELLED, id, extra);
        return get(id);
    }

    /**
     * Close the undelivered remainder of a line short: {@code qtyCancelled = ordered − max(delivered, billable,
     * net invoiced)}. History (delivered / invoiced) is untouched.
     */
    @Transactional
    public Map<String, Object> closeShort(String id, String lineId, String reason) {
        Map<String, Object> o = lock(id);
        if (!OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be closed short");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> l = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ? AND"
                + " \"organizationId\" = ? FOR UPDATE", lineId, id, org());
        if (l == null) {
            throw ApiException.notFound("Order line not found");
        }
        if (l.get("closedShortAt") != null) {
            throw ApiException.badRequest("Line is already closed short");
        }
        BigDecimal floor = committedFloor(l);
        BigDecimal cancel = dec(l.get("quantity")).subtract(floor);
        if (cancel.signum() <= 0) {
            throw ApiException.badRequest("Nothing left to close on this line");
        }
        db.update("UPDATE \"SalesOrderLine\" SET \"qtyCancelled\" = ?, \"closedShortAt\" = " + NOW + ","
                + " \"closeShortReason\" = ? WHERE id = ? AND \"organizationId\" = ?", cancel, reason, lineId, org());
        db.patch("SalesOrder", id, Obj.of("version", ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("lineId", lineId, "closedShort", cancel,
                "reason", reason)));
        // Sales readiness R4d (DEL-1): the live delivery order follows (refused when deliveries already plan more).
        resyncDeliveries(id);
        publish(DomainEvents.SALES_ORDER_LINE_CLOSED_SHORT, id, Map.of("lineId", lineId, "quantity",
                JsNumbers.js(cancel)));
        refreshStatus(id);
        atp.followOrder(id);
        return get(id);
    }

    /** Quantity of a line that can no longer be reduced: max(delivered, billable, net invoiced). */
    static BigDecimal committedFloor(Map<String, Object> l) {
        return dec(l.get("qtyDelivered")).max(dec(l.get("qtyBillable")))
                .max(dec(l.get("qtyInvoiced")).subtract(dec(l.get("qtyCredited"))));
    }

    /**
     * Derive delivery / invoice status, the financial outcome, and move {@code confirmed → done} (called whenever a
     * projection, a document or a payment of the order changes). ADR-S094 §4.5: done = everything delivered (or closed
     * short), every line invoiced exactly as far as it is billable (B − N = 0), no return awaiting its outcome and no
     * advance held; a cash sale must also be settled. Payment does not hold an order back (§4.4 settlement does).
     */
    @Transactional
    public void refreshStatus(String id) {
        // Sales readiness R4 (SO-6): under the order lock, so concurrent fleet / invoice subscribers serialise and the
        // order turns done (and announces it) exactly once.
        Map<String, Object> o = db.row("SELECT status, \"saleType\" FROM \"SalesOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE", id, org());
        if (o == null) {
            return;
        }
        SalesOutcomeService.Outcome money = outcome.compute(id);
        Map<String, Object> d = new LinkedHashMap<>();
        boolean open = OPEN.contains(o.get("status"));
        String status = str(o.get("status"));
        if (open) {
            Map<String, Object> s = db.row("SELECT COALESCE(sum(l.quantity - l.\"qtyCancelled\"), 0) AS open,"
                    + " COALESCE(sum(l.\"qtyDelivered\"), 0) AS delivered, COALESCE(sum(" + BILLABLE + "), 0) AS billable,"
                    + " COALESCE(sum(l.\"qtyInvoiced\" - l.\"qtyCredited\"), 0) AS invoiced, bool_and(l.\"qtyDelivered\" >="
                    + " (l.quantity - l.\"qtyCancelled\") * (1 - l.\"tolerancePct\" / 100)) AS alldelivered, bool_and(("
                    + BILLABLE + ") = l.\"qtyInvoiced\" - l.\"qtyCredited\") AS balanced FROM \"SalesOrderLine\" l JOIN"
                    + " \"SalesOrder\" o ON o.id = l.\"orderId\" LEFT JOIN \"Product\" p ON p.id = l.\"productId\""
                    + " WHERE l.\"orderId\" = ?", id);
            BigDecimal delivered = dec(s.get("delivered"));
            BigDecimal billable = dec(s.get("billable"));
            BigDecimal invoiced = dec(s.get("invoiced"));
            boolean allDelivered = Boolean.TRUE.equals(s.get("alldelivered"));
            String delivery = delivered.signum() == 0 && !allDelivered ? "none" : allDelivered ? "full" : "partial";
            String invoice = invoiced.signum() == 0 ? billable.signum() > 0 ? "to_invoice" : "nothing"
                    : invoiced.compareTo(billable) >= 0 && allDelivered ? "invoiced" : "partial";
            long openReturns = db.count("SELECT count(*) FROM \"SalesReturn\" WHERE \"orderId\" = ? AND"
                    + " \"organizationId\" = ? AND status IN ('requested', 'approved', 'received', 'inspected')",
                    List.of(id, org()));
            // dispatched goods are billed on what the customer accepts: a load still awaiting its proof of delivery
            // keeps the order open (its billable quantity is not known yet)
            long awaitingPod = db.count("SELECT count(*) FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" d ON d.id ="
                    + " t.\"deliveryOrderId\" WHERE d.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order' AND"
                    + " d.\"sourceId\" = ? AND t.status IN ('dispatched', 'in_transit')", List.of(org(), id));
            boolean done = allDelivered && Boolean.TRUE.equals(s.get("balanced")) && openReturns == 0 && awaitingPod == 0
                    && money.advanceHeld().signum() == 0 && (!"cash".equals(o.get("saleType")) || money.settled());
            d.put("deliveryStatus", delivery);
            d.put("invoiceStatus", invoice);
            if (done) {
                status = "done";
                d.put("status", status);
                d.put("doneAt", Instant.now());
                d.put("onHold", false);
            }
            db.patch("SalesOrder", id, d, true);
        }
        // stored after the status change: a done order with nothing invoiced charged nothing
        outcome.refresh(id);
        if ("done".equals(status) && open) {
            publish(DomainEvents.SALES_ORDER_DONE, id, Map.of());
            atp.releaseOrder(id);
        }
    }

    /**
     * ADR-S094 §4.5 B per order line (aliases {@code l} line, {@code o} order, {@code p} product): what the line may be
     * invoiced for in total. Zero-value lines are never invoiced (AD-19: delivery note only); the ordered policy bills
     * ordered − closed short − returned and credited − written off; the delivered policy bills the billable quantity
     * (which already excludes those).
     */
    static final String BILLABLE = "CASE WHEN l.\"lineTotal\" = 0 THEN 0 WHEN COALESCE(o.\"billingPolicy\","
            + " p.\"invoicePolicy\", 'delivered') = 'ordered' THEN GREATEST(l.quantity - l.\"qtyCancelled\""
            + " - l.\"qtyReturnCredited\" - l.\"qtyWrittenOff\", 0) ELSE l.\"qtyBillable\" END";

    /**
     * ADR-S094 §4.4: keep the sale's open credit on the customer's account (explicit, permission
     * {@code sales:credit_note:write}, audited). The credit notes stay open for other invoices; the sale is settled.
     */
    @Transactional
    public Map<String, Object> keepCredit(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        SalesOutcomeService.Outcome c = outcome.compute(id);
        if (c == null || c.openCredit().signum() <= 0) {
            throw ApiException.badRequest("This sale has no open credit to keep");
        }
        db.patch("SalesOrder", id, Obj.of("creditKept", dec(o.get("creditKept")).add(c.openCredit()), "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("creditKept", JsNumbers.js(c.openCredit()),
                "reason", reason)));
        refreshStatus(id);
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    /** An order-currency amount in the base currency (credit limits are in base; SO-13). */
    static BigDecimal inBase(Map<String, Object> order, BigDecimal amount) {
        BigDecimal rate = order.get("exchangeRate") == null ? BigDecimal.ONE : dec(order.get("exchangeRate"));
        return amount.multiply(rate.signum() > 0 ? rate : BigDecimal.ONE).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    void publish(String event, String id, Map<String, Object> extra) {
        Map<String, Object> o = db.row("SELECT number, \"partnerId\", total FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", id, org());
        Map<String, Object> payload = Obj.of("v", 1, "organizationId", org(), "orderId", id, "number", o.get("number"),
                "partnerId", o.get("partnerId"), "total", o.get("total"));
        payload.putAll(extra);
        events.publishOnCommit(event, payload);
    }

    /** SG-9: numbers run per branch when the setting says so; the branch code in the prefix keeps series apart. */
    String nextNumber(String branchId) {
        int year = LocalDate.now(BusinessZone.get()).getYear();
        String code = branchCode(branchId);
        return code == null ? sequence.next("sales_order:" + year, "SO-" + year + "-", 5)
                : sequence.next("sales_order:" + code + ":" + year, "SO-" + code + "-" + year + "-", 5);
    }

    private String branchCode(String branchId) {
        if (branchId == null || !"branch".equals(settings.settings().get("numberingScope"))) {
            return null;
        }
        Map<String, Object> b = db.row("SELECT code FROM \"Branch\" WHERE id = ?"
                + " AND \"organizationId\" = ?", branchId, org());
        return b == null ? null : str(b.get("code"));
    }

    private void visibility(StringBuilder where, List<Object> params) {
        if (user() == null) {
            return; // scheduled work has no user and is not limited to one
        }
        access.filterDocument(where, params, "o");
        if (TenantContext.has(Permissions.SALES__ORDER_READ_ALL)) {
            return;
        }
        where.append(" AND (o.\"ownerId\" = ? OR o.\"createdById\" = ? OR o.\"ownerId\" IS NULL)");
        params.add(user());
        params.add(user());
    }

    public Map<String, Object> require(String id) {
        StringBuilder where = new StringBuilder(" WHERE o.id = ? AND o.\"organizationId\" = ? AND o.\"deletedAt\" IS"
                + " NULL");
        List<Object> params = new ArrayList<>(List.of(id, org()));
        visibility(where, params);
        Map<String, Object> o = db.row(SELECT + where, params.toArray());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        return o;
    }

    Map<String, Object> lock(String id) {
        require(id);
        return db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
    }

    private void requirePartner(String partnerId) {
        db.requireActivePartner(partnerId);
    }

    private String requireUser(String id) {
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                List.of(id, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        return id;
    }

    String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }
}
