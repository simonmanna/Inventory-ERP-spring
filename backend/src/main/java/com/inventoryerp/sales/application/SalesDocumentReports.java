package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.bar;
import static com.inventoryerp.sales.application.SalesReportKit.line;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.num;
import static com.inventoryerp.sales.application.SalesReportKit.pct;
import static com.inventoryerp.sales.application.SalesReportKit.route;
import static com.inventoryerp.sales.application.SalesReportKit.table;
import static com.inventoryerp.sales.application.SalesReportSet.DOC;
import static com.inventoryerp.sales.application.SalesReportSet.SNAPSHOT;
import static com.inventoryerp.sales.application.SalesReportSet.def;
import static com.inventoryerp.sales.application.SalesReportSet.with;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.sales.application.SalesReportFacts.Dim;
import com.inventoryerp.sales.application.SalesReportKit.Bucket;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.sales.application.SalesReportKit.Scope;
import com.inventoryerp.sales.application.SalesReportKit.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Document reports of the report center (ADR-S057): sales orders, order lines, quotations, the CRM pipeline,
 * deliveries (fleet loads of customer delivery orders) and the customer views that read documents rather than
 * revenue (purchase history, new and inactive customers, open orders, non-moving products).
 */
@Component
class SalesDocumentReports implements SalesReportSet {

    static final String ORD = "orders";
    static final String QUO = "quotations";
    static final String DEL = "deliveries";
    static final String CUST = SalesRevenueReports.CUST;
    static final String PROD = SalesRevenueReports.PROD;
    static final String TEAM = SalesRevenueReports.TEAM;

    private static final String SP_NAME = "trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", ''))";

    private static final String ORDERS = """
            SELECT o.id, o.number, COALESCE(o."confirmedAt", o."createdAt")::date AS date, o."createdAt",
                   o."confirmedAt", o."doneAt", o."cancelledAt"::date AS "cancelledOn", o.status, o."deliveryStatus",
                   o."invoiceStatus", o."onHold", o."holdReason", o."partnerId", p.name AS customer, o."ownerId",
                   trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson, o."branchId",
                   b.name AS branch, o."requestedDate", o."commitmentDate",
                   COALESCE(o."commitmentDate", o."requestedDate") AS "dueDate", ROUND(o.total * o."exchangeRate", 6) AS total,
                   o."currencyCode",
                   o."cancelReason", o."quotationId", o."approvalReasons", o."maxDiscountPercent", o.priority,
                   ol.ordered, ol.delivered, ol.dispatched, ol.invoiced, ol.cancelled, ol.remaining, ol."toDispatch",
                   ol."openValue", ol.lines
              FROM "SalesOrder" o
              LEFT JOIN "Partner" p ON p.id = o."partnerId"
              LEFT JOIN "User" u ON u.id = o."ownerId"
              LEFT JOIN "Branch" b ON b.id = o."branchId"
              LEFT JOIN LATERAL (
                   SELECT count(*) AS lines, COALESCE(sum(l.quantity), 0) AS ordered,
                          COALESCE(sum(l."qtyDelivered"), 0) AS delivered, COALESCE(sum(l."qtyDispatched"), 0) AS dispatched,
                          COALESCE(sum(l."qtyInvoiced" - l."qtyCredited"), 0) AS invoiced,
                          COALESCE(sum(l."qtyCancelled"), 0) AS cancelled,
                          COALESCE(sum(GREATEST(l.quantity - l."qtyCancelled" - l."qtyDelivered", 0)), 0) AS remaining,
                          COALESCE(sum(GREATEST(l.quantity - l."qtyCancelled" - l."qtyDispatched", 0)), 0) AS "toDispatch",
                          COALESCE(sum(GREATEST(l.quantity - l."qtyCancelled" - l."qtyDelivered", 0) * l."unitPrice"
                                       * (1 - l."discountPercent" / 100)), 0) AS "openValue"
                     FROM "SalesOrderLine" l WHERE l."orderId" = o.id) ol ON true
             WHERE o."organizationId" = ? AND o."deletedAt" IS NULL""";

    private static final Scope ORDER_SCOPE = new Scope("COALESCE(o.\"confirmedAt\", o.\"createdAt\")::date",
            "o.\"branchId\"", "o.\"ownerId\"", "o.\"partnerId\"",
            "EXISTS (SELECT 1 FROM \"SalesOrderLine\" x WHERE x.\"orderId\" = o.id AND x.\"productId\" = ?)",
            "EXISTS (SELECT 1 FROM \"SalesOrderLine\" x JOIN \"Product\" xp ON xp.id = x.\"productId\" WHERE"
                    + " x.\"orderId\" = o.id AND xp.\"categoryId\" = ?)", "p.\"categoryId\"");

    private static final String ORDER_LINES = """
            SELECT l.id, o.id AS "orderId", o.number, COALESCE(o."confirmedAt", o."createdAt")::date AS date, o.status,
                   o."deliveryStatus", p.name AS customer, trim(u."firstName" || ' ' || COALESCE(u."lastName", ''))
                   AS salesperson, COALESCE(NULLIF(pr.code, '') || ' · ', '') || COALESCE(pr.name, l.description)
                   AS product, COALESCE(um.symbol, um.name) AS unit, l.quantity, l."qtyCancelled", l."qtyReserved",
                   l."qtyLoaded", l."qtyDispatched", l."qtyDelivered", l."qtyReceived", l."qtyAccepted",
                   l."qtyInvoiced" - l."qtyCredited" AS invoiced,
                   GREATEST(l.quantity - l."qtyCancelled" - l."qtyDelivered", 0) AS remaining,
                   COALESCE(l."promisedDate", o."commitmentDate", o."requestedDate") AS "promisedDate",
                   l."lineSubtotal", l."unitPrice", l."listPrice", l."discountPercent", l."promiseAtRisk"
              FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
              LEFT JOIN "Partner" p ON p.id = o."partnerId"
              LEFT JOIN "User" u ON u.id = o."ownerId"
              LEFT JOIN "Product" pr ON pr.id = l."productId"
              LEFT JOIN "UnitOfMeasure" um ON um.id = l."uomId"
             WHERE o."organizationId" = ? AND o."deletedAt" IS NULL""";

    private static final Scope LINE_SCOPE = new Scope("COALESCE(o.\"confirmedAt\", o.\"createdAt\")::date",
            "o.\"branchId\"", "o.\"ownerId\"", "o.\"partnerId\"", "l.\"productId\" = ?", "pr.\"categoryId\" = ?",
            "p.\"categoryId\"");

    private static final String QUOTES = """
            SELECT q.id, q.number, q."issueDate" AS date, q."validUntil", q.status, q."partnerId", p.name AS customer,
                   q."ownerId", trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson,
                   b.name AS branch, q.subtotal, q.total, q."rejectionReason", q."convertedType", q."convertedId",
                   q."convertedNumber", q."convertedAt", q."sentAt", q."acceptedAt", q."approvedAt",
                   q."approvalReasons", q."maxDiscountPercent", q."createdAt", q."currencyCode"
              FROM "SalesQuotation" q
              LEFT JOIN "Partner" p ON p.id = q."partnerId"
              LEFT JOIN "User" u ON u.id = q."ownerId"
              LEFT JOIN "Branch" b ON b.id = q."branchId"
             WHERE q."organizationId" = ? AND q."deletedAt" IS NULL""";

    private static final Scope QUOTE_SCOPE = new Scope("q.\"issueDate\"", "q.\"branchId\"", "q.\"ownerId\"",
            "q.\"partnerId\"",
            "EXISTS (SELECT 1 FROM \"SalesQuotationLine\" x WHERE x.\"quotationId\" = q.id AND x.\"productId\" = ?)",
            "EXISTS (SELECT 1 FROM \"SalesQuotationLine\" x JOIN \"Product\" xp ON xp.id = x.\"productId\" WHERE"
                    + " x.\"quotationId\" = q.id AND xp.\"categoryId\" = ?)", "p.\"categoryId\"");

    private static final String LOADS = """
            SELECT t.id, t.code, t.status, t."plannedDate", t."dispatchedAt", t."deliveredAt",
                   COALESCE(t."dispatchedAt"::date, t."plannedDate") AS date, t."loadedTonnes", t."deliveredTonnes",
                   t."plannedTonnes", t."weighedNetQty", t."weighedUom", t."freightAmount", t."distanceKm",
                   t."deliveryNoteNumber", t."deliveryNotePrints", t.carrier, t."returnReason", t."cancelReason",
                   t.notes, t."vehicleId", COALESCE(v."registrationPlate", v.name, t."transporterPlate") AS vehicle,
                   t."driverId", COALESCE(NULLIF(trim(e."firstName" || ' ' || COALESCE(e."lastName", '')), ''),
                   t."transporterDriverName") AS driver, d.id AS "deliveryOrderId", d.code AS "deliveryOrder",
                   d."sourceType", d."sourceNumber" AS "orderNumber", o.id AS "orderId", d."partnerId",
                   p.name AS customer, s.name AS site, d."siteId",
                   COALESCE(o."commitmentDate", d."requestedDate", o."requestedDate") AS "dueDate", o."ownerId",
                   b.name AS branch
              FROM "FleetLoad" t JOIN "FleetDeliveryOrder" d ON d.id = t."deliveryOrderId"
              LEFT JOIN "SalesOrder" o ON d."sourceType" = 'sales_order' AND o.id = d."sourceId"
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
              LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId"
              LEFT JOIN "FleetDriver" dr ON dr.id = t."driverId"
              LEFT JOIN "HrEmployee" e ON e.id = dr."employeeId"
              LEFT JOIN "FleetCustomerSite" s ON s.id = d."siteId"
              LEFT JOIN "Branch" b ON b.id = COALESCE(o."branchId", d."branchId")
             WHERE t."organizationId" = ? AND t."cancelledAt" IS NULL AND d."partnerId" IS NOT NULL""";

    private static final Scope LOAD_SCOPE = new Scope("COALESCE(t.\"dispatchedAt\"::date, t.\"plannedDate\")",
            "COALESCE(o.\"branchId\", d.\"branchId\")", "o.\"ownerId\"", "d.\"partnerId\"",
            "EXISTS (SELECT 1 FROM \"SalesOrderLine\" x WHERE x.\"orderId\" = o.id AND x.\"productId\" = ?)",
            "EXISTS (SELECT 1 FROM \"SalesOrderLine\" x JOIN \"Product\" xp ON xp.id = x.\"productId\" WHERE"
                    + " x.\"orderId\" = o.id AND xp.\"categoryId\" = ?)", "p.\"categoryId\"");

    private final SalesDb db;
    private final SalesReportFacts facts;

    SalesDocumentReports(SalesDb db, SalesReportFacts facts) {
        this.db = db;
        this.facts = facts;
    }

    @Override
    public List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        List<String> withStatus = with(DOC, "status");
        // ---- orders
        d.add(def("order-register", ORD, "Sales Order Register", "All sales orders of the period.", withStatus,
                f -> orders(f, null, true, "register")));
        d.add(def("open-orders", ORD, "Open Sales Orders", "Confirmed orders still being fulfilled.", SNAPSHOT,
                f -> orders(f, " AND o.status IN ('confirmed', 'in_fulfilment')", false, "open")));
        d.add(def("pending-orders", ORD, "Pending Sales Orders", "Draft orders and orders awaiting approval.",
                SNAPSHOT, f -> orders(f, " AND o.status IN ('draft', 'pending_approval')", false, "pending")));
        d.add(def("approved-orders", ORD, "Approved Sales Orders", "Orders confirmed in the period.", DOC,
                f -> orders(f, " AND o.status IN ('confirmed', 'in_fulfilment', 'done')", true, "approved")));
        d.add(def("partially-fulfilled-orders", ORD, "Partially Fulfilled Orders",
                "Orders with some quantity delivered.", SNAPSHOT,
                f -> orders(f, " AND o.\"deliveryStatus\" = 'partial' AND o.status <> 'cancelled'", false, "open")));
        d.add(def("fully-fulfilled-orders", ORD, "Fully Fulfilled Orders", "Orders with every quantity delivered.",
                DOC, f -> orders(f, " AND o.\"deliveryStatus\" = 'full'", true, "done")));
        d.add(def("cancelled-orders", ORD, "Cancelled Sales Orders", "Cancelled orders and the reasons given.", DOC,
                f -> orders(f, " AND o.status = 'cancelled'", true, "cancelled")));
        d.add(def("overdue-orders", ORD, "Overdue Sales Orders",
                "Open orders past their commitment (or requested) date.", with(SNAPSHOT, "asOf"),
                f -> orders(f, " AND o.status IN ('confirmed', 'in_fulfilment') AND o.\"deliveryStatus\" <> 'full' AND"
                        + " COALESCE(o.\"commitmentDate\", o.\"requestedDate\") < '" + f.asOf() + "'", false,
                        "overdue")));
        d.add(def("backorders", ORD, "Backorders", "Ordered quantities not yet delivered on open orders.", SNAPSHOT,
                this::backorders));
        d.add(def("order-fulfilment", ORD, "Order Fulfilment", "Ordered vs delivered vs invoiced vs remaining per line.",
                DOC, this::fulfilment));
        d.add(def("order-processing-time", ORD, "Order Processing Time",
                "Days from order creation to confirmation, first dispatch, delivery and completion.", DOC,
                this::processingTime));
        d.add(def("orders-by-status", ORD, "Orders by Status", "Count and value of orders per status in the period.",
                DOC, this::ordersByStatus));
        // ---- customer document views
        d.add(def("customer-history", CUST, "Customer Purchase History",
                "Quotations, orders, deliveries, invoices, receipts, credit notes and returns of one customer.",
                List.of("period", "customer"), this::customerHistory));
        d.add(def("new-customers", CUST, "New Customers", "Customers registered in the period and their first purchase.",
                List.of("period", "salesperson", "customerCategory"), this::newCustomers));
        d.add(def("customer-activity", CUST, "Active vs Inactive Customers",
                "Every customer's last purchase: active, at risk or inactive by days since (default 90).",
                List.of("salesperson", "customerCategory", "days", "asOf", "status"), this::customerActivity));
        d.add(def("customer-open-orders", CUST, "Customer Outstanding Orders",
                "Open order value and undelivered quantity per customer.", SNAPSHOT, this::customerOpenOrders));
        d.add(def("non-moving-products", PROD, "Non-Moving Products",
                "Active products with no sales in the period, with stock on hand and its value.",
                List.of("period", "category"), this::nonMoving));
        // ---- quotations
        d.add(def("quotation-register", QUO, "Quotation Register", "All quotations issued in the period.", withStatus,
                f -> quotes(f, null, true, "register")));
        d.add(def("pending-quotations", QUO, "Pending Quotations", "Quotations awaiting approval or customer response.",
                SNAPSHOT, f -> quotes(f, " AND q.status IN ('pending_approval', 'approved', 'sent')", false,
                        "pending")));
        d.add(def("approved-quotations", QUO, "Approved Quotations", "Quotations approved or accepted in the period.",
                DOC, f -> quotes(f, " AND (q.status IN ('approved', 'accepted') OR q.\"approvedAt\" IS NOT NULL)", true,
                        "approved")));
        d.add(def("rejected-quotations", QUO, "Rejected Quotations", "Rejected quotations and the reasons.", DOC,
                f -> quotes(f, " AND q.status = 'rejected'", true, "rejected")));
        d.add(def("expired-quotations", QUO, "Expired Quotations", "Quotations past their validity date.",
                with(DOC, "asOf"), f -> quotes(f, " AND (q.status = 'expired' OR (q.status IN ('draft',"
                        + " 'pending_approval', 'approved', 'sent') AND q.\"validUntil\" < '" + f.asOf() + "'))", true,
                        "expired")));
        d.add(def("quotation-conversion", QUO, "Quotation Conversion",
                "Quotations converted into sales orders and the days it took.", DOC, this::conversions));
        d.add(def("quotation-conversion-rate", QUO, "Quotation Conversion Rate",
                "Converted ÷ eligible quotations by salesperson, customer, branch or month.", with(DOC, "dimension"),
                f -> conversionRate(f, f.dimension() == null ? "salesperson" : f.dimension())));
        d.add(def("quotation-followup", QUO, "Quotation Follow-up",
                "Sent quotations without an answer after N days (default 7) or expiring within N days.",
                with(SNAPSHOT, "days", "asOf"), this::followUp));
        d.add(def("quotation-value", QUO, "Quotation Value Analysis", "Quotation count and value by status and month.",
                DOC, this::quotationValue));
        d.add(def("lost-opportunities", QUO, "Lost Sales Opportunities", "Deals lost in the period and the reasons.",
                List.of("period", "salesperson", "customer"), this::lostDeals));
        d.add(def("sales-pipeline", QUO, "Sales Pipeline", "Open deals by stage: count, value and weighted value.",
                List.of("salesperson", "customer"), this::pipeline));
        d.add(def("opportunity-forecast", QUO, "Sales Opportunity Forecast",
                "Open deal value and probability-weighted value by expected close month.",
                List.of("salesperson", "customer"), this::opportunityForecast));
        d.add(def("quotation-performance", TEAM, "Salesperson Quotation Performance",
                "Quotations issued, value and conversion per representative.", DOC,
                f -> conversionRate(f, "salesperson")));
        d.add(def("sales-activity", TEAM, "Sales Activity",
                "Calls, meetings, emails, tasks and notes logged per representative.", List.of("period", "salesperson"),
                this::activity));
        // ---- deliveries
        d.add(def("delivery-register", DEL, "Sales Delivery Register",
                "Every load dispatched to customers with vehicle, driver and quantities.", DOC,
                f -> loads(f, null, "register")));
        d.add(def("pending-deliveries", DEL, "Pending Deliveries", "Open orders with quantity still to dispatch.",
                SNAPSHOT, f -> orders(f, " AND o.status IN ('confirmed', 'in_fulfilment') AND ol.\"toDispatch\" > 0",
                        false, "dispatch")));
        d.add(def("partial-deliveries", DEL, "Partial Deliveries", "Orders delivered in part.", SNAPSHOT,
                f -> orders(f, " AND o.\"deliveryStatus\" = 'partial' AND o.status <> 'cancelled'", false, "open")));
        d.add(def("completed-deliveries", DEL, "Completed Deliveries", "Fully delivered orders.", DOC,
                f -> orders(f, " AND o.\"deliveryStatus\" = 'full'", true, "done")));
        d.add(def("undelivered-orders", DEL, "Undelivered Orders", "Confirmed orders with nothing delivered yet.",
                SNAPSHOT, f -> orders(f, " AND o.status IN ('confirmed', 'in_fulfilment') AND o.\"deliveryStatus\" ="
                        + " 'none'", false, "open")));
        d.add(def("delivery-performance", DEL, "Delivery Performance", "On-time vs late deliveries by period.",
                with(DOC, "groupBy"), this::deliveryPerformance));
        d.add(def("delivery-delays", DEL, "Delivery Delay Report", "Loads delivered after the due date and the notes.",
                DOC, f -> loads(f, " AND t.\"deliveredAt\" IS NOT NULL AND t.\"deliveredAt\"::date > COALESCE("
                        + "o.\"commitmentDate\", d.\"requestedDate\", o.\"requestedDate\")", "late")));
        d.add(def("delivery-notes", DEL, "Delivery Note Register", "Delivery notes issued.", DOC,
                f -> loads(f, " AND t.\"deliveryNoteNumber\" IS NOT NULL", "notes")));
        d.add(def("delivered-vs-ordered", DEL, "Delivered vs Ordered Quantity",
                "Ordered, loaded, dispatched, received and accepted quantities per order line.", DOC,
                this::deliveredVsOrdered));
        d.add(def("delivery-by-vehicle", DEL, "Delivery by Vehicle", "Trips, tonnes and freight per vehicle.", DOC,
                f -> loadGroup(f, "vehicle", "Vehicle")));
        d.add(def("delivery-by-driver", DEL, "Delivery by Driver", "Trips, tonnes and on-time rate per driver.", DOC,
                f -> loadGroup(f, "driver", "Driver")));
        d.add(def("customer-deliveries", DEL, "Customer Delivery History",
                "Trips, tonnes per trip, freight and on-time rate per customer.", DOC,
                f -> loadGroup(f, "customer", "Customer")));
        d.add(def("delivery-by-route", DEL, "Delivery by Route / Site", "Trips, tonnes, distance and freight per"
                + " delivery site.", DOC, f -> loadGroup(f, "site", "Delivery site")));
        return d;
    }

    // ------------------------------------------------------------------ orders

    List<Map<String, Object>> orderRows(Filter f, String where, boolean period) {
        Sql s = new Sql(ORDERS, org());
        SalesReportKit.apply(s, f, ORDER_SCOPE, period);
        if (f.status() != null) {
            s.add(" AND o.status = ?", f.status());
        }
        s.add(where == null ? "" : where).add(" ORDER BY COALESCE(o.\"confirmedAt\", o.\"createdAt\") DESC LIMIT 2000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            BigDecimal net = dec(r.get("ordered")).subtract(dec(r.get("cancelled")));
            r.put("fulfilment", pct(dec(r.get("delivered")), net));
            Long late = SalesReportKit.daysBetween(r.get("dueDate"), f.asOf());
            r.put("daysOverdue", late != null && late > 0 ? late : null);
            r.put("age", SalesReportKit.daysBetween(r.get("date"), f.asOf()));
            route(r, "/sales/orders/" + r.get("id"));
        }
        return rows;
    }

    Report orders(Filter f, String where, boolean period, String variant) {
        List<Map<String, Object>> rows = orderRows(f, where, period);
        List<String> spec = new ArrayList<>(List.of("number|Order|text", "date|Date|date", "customer|Customer|text",
                "salesperson|Salesperson|text", "branch|Branch|text", "status|Status|status"));
        switch (variant) {
            case "cancelled" -> spec.addAll(List.of("cancelledOn|Cancelled|date", "cancelReason|Reason|text",
                    "total|Order value|money"));
            case "pending" -> spec.addAll(List.of("age|Age (days)|days", "approvalReasons|Approval reasons|text",
                    "maxDiscountPercent|Max disc %|percent", "total|Order value|money"));
            case "overdue" -> spec.addAll(List.of("dueDate|Due|date", "daysOverdue|Days overdue|days",
                    "deliveryStatus|Delivery|status", "remaining|Qty remaining|qty", "openValue|Open value|money"));
            case "dispatch" -> spec.addAll(List.of("dueDate|Due|date", "ordered|Ordered|qty",
                    "dispatched|Dispatched|qty", "toDispatch|To dispatch|qty", "openValue|Open value|money"));
            default -> spec.addAll(List.of("deliveryStatus|Delivery|status", "invoiceStatus|Invoicing|status",
                    "dueDate|Due|date", "ordered|Ordered|qty", "delivered|Delivered|qty", "remaining|Remaining|qty",
                    "fulfilment|Fulfilled %|percent", "total|Order value|money", "openValue|Open value|money"));
        }
        Report rep = table(rows, spec.toArray(String[]::new)).sumTotals("total", "openValue", "ordered", "delivered",
                "remaining", "dispatched", "toDispatch");
        rep.total("number", "Total");
        rep.kpi("count", "Orders", rows.size(), "count").kpi("value", "Order value", rep.totals.get("total"), "money")
                .kpi("open", "Open value", rep.totals.get("openValue"), "money");
        if ("overdue".equals(variant)) {
            rep.kpi("avgLate", "Avg days overdue", avg(rows, "daysOverdue"), "days");
        } else {
            rep.kpi("avg", "Average order", rows.isEmpty() ? null : money(dec(rep.totals.get("total"))
                    .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP)), "money");
        }
        List<Map<String, Object>> byStatus = SalesReportKit.group(rows, r -> SalesReportKit.label(str(r.get(
                "overdue".equals(variant) || "dispatch".equals(variant) ? "deliveryStatus" : "status"))), "total");
        rep.chart("pie", "label", bar("total", "Order value")).chartData(byStatus);
        if (rows.size() >= 2000) {
            rep.note("Showing the latest 2000 orders; narrow the filter.");
        }
        return rep;
    }

    static Object avg(List<Map<String, Object>> rows, String key) {
        List<BigDecimal> v = rows.stream().map(r -> r.get(key)).filter(x -> x != null).map(SalesDb::dec).toList();
        return v.isEmpty() ? null : num(v.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(v.size()), 1, RoundingMode.HALF_UP));
    }

    Report ordersByStatus(Filter f) {
        List<Map<String, Object>> rows = orderRows(f, null, true);
        List<Map<String, Object>> g = SalesReportKit.group(rows, r -> SalesReportKit.label(str(r.get("status"))),
                "total", "openValue");
        Report rep = table(g, "label|Status|status", "count|Orders|count", "total|Value|money",
                "openValue|Open value|money").sumTotals("count", "total", "openValue");
        rep.total("label", "Total");
        rep.kpi("count", "Orders", rows.size(), "count").kpi("value", "Value", rep.totals.get("total"), "money");
        List<Map<String, Object>> byMonth = new ArrayList<>();
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "orders", 0, "value", 0));
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.put("orders", ((Number) b.get("orders")).intValue() + 1);
                b.put("value", money(dec(b.get("value")).add(dec(r.get("total")))));
            }
        }
        byMonth.addAll(m.values());
        rep.chart("combo", "label", bar("value", "Order value"), line("orders", "Orders")).chartData(byMonth);
        return rep;
    }

    List<Map<String, Object>> lineRows(Filter f, String where, boolean period) {
        Sql s = new Sql(ORDER_LINES, org());
        SalesReportKit.apply(s, f, LINE_SCOPE, period);
        s.add(where == null ? "" : where).add(" ORDER BY COALESCE(o.\"confirmedAt\", o.\"createdAt\") DESC, l.\"sortOrder\""
                + " LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            BigDecimal net = dec(r.get("quantity")).subtract(dec(r.get("qtyCancelled")));
            r.put("fulfilment", pct(dec(r.get("qtyDelivered")), net));
            r.put("variance", num(dec(r.get("qtyDispatched")).subtract(dec(r.get("qtyReceived")))));
            Long late = SalesReportKit.daysBetween(r.get("promisedDate"), f.asOf());
            r.put("daysLate", late != null && late > 0 ? late : null);
            r.put("openValue", money(dec(r.get("remaining")).multiply(dec(r.get("unitPrice")))
                    .multiply(BigDecimal.ONE.subtract(dec(r.get("discountPercent")).movePointLeft(2)))));
            route(r, "/sales/orders/" + r.get("orderId"));
        }
        return rows;
    }

    Report backorders(Filter f) {
        List<Map<String, Object>> rows = lineRows(f, " AND o.status IN ('confirmed', 'in_fulfilment') AND"
                + " l.quantity - l.\"qtyCancelled\" - l.\"qtyDelivered\" > 0", false);
        Report rep = table(rows, "number|Order|text", "date|Date|date", "customer|Customer|text",
                "product|Product|text", "unit|Unit|text", "quantity|Ordered|qty", "qtyReserved|Reserved|qty",
                "qtyDelivered|Delivered|qty", "remaining|Backordered|qty", "promisedDate|Promised|date",
                "daysLate|Days late|days", "openValue|Open value|money").sumTotals("openValue");
        rep.total("number", "Total");
        rep.kpi("lines", "Backordered lines", rows.size(), "count")
                .kpi("value", "Backorder value", rep.totals.get("openValue"), "money")
                .kpi("late", "Past promise date", rows.stream().filter(r -> r.get("daysLate") != null).count(), "count");
        List<Map<String, Object>> byProduct = SalesReportKit.group(rows, r -> r.get("product"), "remaining",
                "openValue");
        byProduct.sort(SalesReportKit.byDesc("openValue"));
        rep.chart("hbar", "label", bar("openValue", "Open value")).chartData(byProduct.subList(0, Math.min(15,
                byProduct.size())));
        return rep;
    }

    Report fulfilment(Filter f) {
        List<Map<String, Object>> rows = lineRows(f, " AND o.status <> 'cancelled'", true);
        Report rep = table(rows, "number|Order|text", "date|Date|date", "customer|Customer|text",
                "product|Product|text", "unit|Unit|text", "quantity|Ordered|qty", "qtyCancelled|Cancelled|qty",
                "qtyDelivered|Delivered|qty", "invoiced|Invoiced|qty", "remaining|Remaining|qty",
                "fulfilment|Fulfilled %|percent", "status|Order status|status");
        rep.kpi("lines", "Order lines", rows.size(), "count")
                .kpi("full", "Fully delivered", rows.stream().filter(r -> dec(r.get("remaining")).signum() == 0).count(),
                        "count")
                .kpi("rate", "Line fill rate", pct(BigDecimal.valueOf(rows.stream()
                        .filter(r -> dec(r.get("remaining")).signum() == 0).count()), BigDecimal.valueOf(rows.size())),
                        "percent");
        List<Map<String, Object>> byProduct = SalesReportKit.group(rows, r -> r.get("product"), "quantity",
                "qtyDelivered", "remaining");
        rep.stacked("hbar", "label", bar("qtyDelivered", "Delivered"), bar("remaining", "Remaining"))
                .chartData(byProduct.subList(0, Math.min(15, byProduct.size())));
        return rep;
    }

    Report deliveredVsOrdered(Filter f) {
        List<Map<String, Object>> rows = lineRows(f, " AND o.status <> 'cancelled'", true);
        Report rep = table(rows, "number|Order|text", "customer|Customer|text", "product|Product|text",
                "unit|Unit|text", "quantity|Ordered|qty", "qtyLoaded|Loaded|qty", "qtyDispatched|Dispatched|qty",
                "qtyReceived|Received|qty", "qtyAccepted|Accepted|qty", "variance|Dispatched − received|qty",
                "remaining|Remaining|qty");
        rep.kpi("lines", "Order lines", rows.size(), "count")
                .kpi("variance", "Lines with transit variance", rows.stream()
                        .filter(r -> dec(r.get("variance")).signum() != 0).count(), "count");
        List<Map<String, Object>> byProduct = SalesReportKit.group(rows, r -> r.get("product"), "quantity",
                "qtyDispatched", "qtyReceived");
        rep.chart("bar", "label", bar("quantity", "Ordered"), bar("qtyDispatched", "Dispatched"),
                bar("qtyReceived", "Received")).chartData(byProduct.subList(0, Math.min(12, byProduct.size())));
        return rep;
    }

    Report processingTime(Filter f) {
        Sql s = new Sql("SELECT o.id, o.number, o.\"createdAt\", o.\"confirmedAt\", o.\"doneAt\", p.name AS customer, "
                + SP_NAME + " AS salesperson, (SELECT min(t.\"dispatchedAt\") FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\""
                + " d ON d.id = t.\"deliveryOrderId\" WHERE d.\"sourceType\" = 'sales_order' AND d.\"sourceId\" = o.id AND"
                + " t.\"cancelledAt\" IS NULL) AS \"firstDispatch\", (SELECT max(t.\"deliveredAt\") FROM \"FleetLoad\" t"
                + " JOIN \"FleetDeliveryOrder\" d ON d.id = t.\"deliveryOrderId\" WHERE d.\"sourceType\" = 'sales_order'"
                + " AND d.\"sourceId\" = o.id AND t.\"cancelledAt\" IS NULL) AS \"lastDelivery\" FROM \"SalesOrder\" o LEFT"
                + " JOIN \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN \"User\" u ON u.id = o.\"ownerId\" WHERE"
                + " o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status <> 'cancelled'", org());
        SalesReportKit.apply(s, f, new Scope("o.\"createdAt\"::date", "o.\"branchId\"", "o.\"ownerId\"",
                "o.\"partnerId\"", ORDER_SCOPE.product(), ORDER_SCOPE.category(), "p.\"categoryId\""), true);
        s.add(" ORDER BY o.\"createdAt\" DESC LIMIT 2000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            r.put("date", SalesReportKit.asDate(r.get("createdAt")));
            r.put("toConfirm", hours(r.get("createdAt"), r.get("confirmedAt")));
            r.put("toDispatch", hours(r.get("confirmedAt"), r.get("firstDispatch")));
            r.put("toDeliver", hours(r.get("confirmedAt"), r.get("lastDelivery")));
            r.put("total", hours(r.get("createdAt"), r.get("doneAt")));
            route(r, "/sales/orders/" + r.get("id"));
        }
        Report rep = table(rows, "number|Order|text", "date|Created|date", "customer|Customer|text",
                "salesperson|Salesperson|text", "toConfirm|Create → confirm (days)|number",
                "toDispatch|Confirm → first dispatch (days)|number", "toDeliver|Confirm → delivered (days)|number",
                "total|Create → done (days)|number");
        rep.kpi("confirm", "Avg days to confirm", avg(rows, "toConfirm"), "days")
                .kpi("dispatch", "Avg days to dispatch", avg(rows, "toDispatch"), "days")
                .kpi("deliver", "Avg days to deliver", avg(rows, "toDeliver"), "days")
                .kpi("total", "Avg order cycle", avg(rows, "total"), "days");
        Map<String, List<Map<String, Object>>> byMonth = new LinkedHashMap<>();
        Bucket.MONTH.keys(f.from(), f.to()).forEach(k -> byMonth.put(k, new ArrayList<>()));
        rows.forEach(r -> {
            List<Map<String, Object>> b = byMonth.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.add(r);
            }
        });
        List<Map<String, Object>> data = new ArrayList<>();
        byMonth.forEach((k, v) -> data.add(Obj.of("label", k, "toConfirm", avg(v, "toConfirm"), "toDispatch",
                avg(v, "toDispatch"), "toDeliver", avg(v, "toDeliver"))));
        rep.chart("line", "label", line("toConfirm", "To confirm"), line("toDispatch", "To dispatch"),
                line("toDeliver", "To deliver")).chartData(data);
        return rep;
    }

    /** Days (one decimal) between two timestamps, or null. */
    static Object hours(Object a, Object b) {
        if (!(a instanceof java.time.Instant x) || !(b instanceof java.time.Instant y)) {
            return null;
        }
        return num(BigDecimal.valueOf(java.time.Duration.between(x, y).toMinutes())
                .divide(BigDecimal.valueOf(1440), 1, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ customers

    Report customerHistory(Filter f) {
        if (f.partnerId() == null) {
            Report top = facts.breakdown(f, Dim.CUSTOMER, false, 50);
            top.note("Pick a customer (filter or click a row) to see the full purchase history.");
            return top;
        }
        String pid = f.partnerId();
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate from = f.from();
        LocalDate to = f.to().plusDays(1);
        add(rows, "SELECT id, number, \"issueDate\" AS date, status, total AS amount, 'Quotation' AS type,"
                + " \"validUntil\"::text AS detail FROM \"SalesQuotation\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"issueDate\" >= ? AND \"issueDate\" < ?", "/crm/quotations/", pid, from,
                to);
        add(rows, "SELECT id, number, COALESCE(\"confirmedAt\", \"createdAt\")::date AS date, status, total AS amount,"
                + " 'Sales order' AS type, \"deliveryStatus\" || ' / ' || \"invoiceStatus\" AS detail FROM \"SalesOrder\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND \"deletedAt\" IS NULL AND COALESCE(\"confirmedAt\","
                + " \"createdAt\") >= ? AND COALESCE(\"confirmedAt\", \"createdAt\") < ?", "/sales/orders/", pid, from, to);
        add(rows, "SELECT d.id, t.code AS number, COALESCE(t.\"dispatchedAt\"::date, t.\"plannedDate\") AS date, t.status,"
                + " NULL::numeric AS amount, 'Delivery' AS type, COALESCE(t.\"deliveredTonnes\", t.\"loadedTonnes\")::text"
                + " || ' t · ' || COALESCE(t.\"deliveryNoteNumber\", d.code) AS detail FROM \"FleetLoad\" t JOIN"
                + " \"FleetDeliveryOrder\" d ON d.id = t.\"deliveryOrderId\" WHERE t.\"organizationId\" = ? AND"
                + " d.\"partnerId\" = ? AND t.\"cancelledAt\" IS NULL AND COALESCE(t.\"dispatchedAt\"::date,"
                + " t.\"plannedDate\") >= ? AND COALESCE(t.\"dispatchedAt\"::date, t.\"plannedDate\") < ?",
                "/fleet/delivery-orders/", pid, from, to);
        add(rows, "SELECT id, \"documentNumber\" AS number, \"issueDate\"::date AS date, status::text AS status,"
                + " CASE WHEN \"documentType\" = 'credit_note' THEN -\"totalAmount\" ELSE \"totalAmount\" END"
                + " * \"exchangeRate\" AS amount,"
                + " CASE WHEN \"documentType\" = 'credit_note' THEN 'Credit note' ELSE 'Invoice' END AS type,"
                + " 'balance ' || (\"amountResidual\" * \"exchangeRate\")::numeric(18,2)::text AS detail FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" IN ('sales_invoice', 'credit_note')"
                + " AND status NOT IN ('draft') AND \"issueDate\" >= ? AND \"issueDate\" < ?", "/invoices/", pid, from, to);
        add(rows, "SELECT id, \"paymentNumber\" AS number, \"paymentDate\"::date AS date, status::text AS status, amount,"
                + " 'Receipt' AS type, \"paymentMethod\" AS detail FROM \"Payment\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND direction = 'inbound' AND \"voidedAt\" IS NULL AND \"paymentDate\" >= ? AND"
                + " \"paymentDate\" < ?", "/payments/", pid, from, to);
        add(rows, "SELECT id, number, \"createdAt\"::date AS date, status, NULL::numeric AS amount, 'Return' AS type,"
                + " reason AS detail FROM \"SalesReturn\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                + " \"createdAt\" >= ? AND \"createdAt\" < ?", "/sales/returns/", pid, from, to);
        rows.stream().filter(r -> "Credit note".equals(r.get("type")))
                .forEach(r -> r.put("_route", "/credit-notes/" + r.get("id")));
        rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("date"))).reversed());
        Report rep = table(rows, "date|Date|date", "type|Document|status", "number|Number|text",
                "status|Status|status", "amount|Amount|money", "detail|Detail|text");
        Map<String, Object> bal = db.row("SELECT COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE \"documentType\" ="
                + " 'sales_invoice'), 0) AS outstanding, COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE"
                + " \"documentType\" ="
                + " 'sales_invoice' AND \"dueDate\" < CURRENT_DATE), 0) AS overdue FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND status = 'posted'", org(), pid);
        BigDecimal invoiced = sumType(rows, "Invoice");
        BigDecimal paid = sumType(rows, "Receipt");
        rep.kpi("quoted", "Quoted", money(sumType(rows, "Quotation")), "money")
                .kpi("ordered", "Ordered", money(sumType(rows, "Sales order")), "money")
                .kpi("invoiced", "Invoiced", money(invoiced), "money")
                .kpi("paid", "Received", money(paid), "money")
                .kpi("outstanding", "Outstanding", bal.get("outstanding"), "money", "Overdue " + bal.get("overdue"));
        rep.note("Customer: " + SalesReportFacts.fmt(facts.name(Dim.CUSTOMER, pid)));
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "invoiced", 0, "paid", 0));
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            String key = "Invoice".equals(r.get("type")) ? "invoiced" : "Receipt".equals(r.get("type")) ? "paid" : null;
            if (b != null && key != null) {
                b.put(key, money(dec(b.get(key)).add(dec(r.get("amount")))));
            }
        }
        rep.chart("bar", "label", bar("invoiced", "Invoiced"), bar("paid", "Received"))
                .chartData(new ArrayList<>(m.values()));
        return rep;
    }

    private void add(List<Map<String, Object>> rows, String sql, String route, Object... params) {
        Object[] p = new Object[params.length + 1];
        p[0] = org();
        System.arraycopy(params, 0, p, 1, params.length);
        for (Map<String, Object> r : db.rows(sql, p)) {
            rows.add(route(r, route + r.get("id")));
        }
    }

    private static BigDecimal sumType(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.get("type"))).map(r -> dec(r.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Report newCustomers(Filter f) {
        Sql s = new Sql("""
                SELECT p.id, p.code, p.name, c.name AS category, p."createdAt"::date AS created,
                       trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson,
                       fi.date AS "firstInvoice", fi.amount AS "firstValue",
                       (SELECT count(*) FROM "Document" d WHERE d."organizationId" = p."organizationId"
                           AND d."partnerId" = p.id AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')
                           AND d."issueDate" >= ? AND d."issueDate" < ?) AS invoices,
                       (SELECT COALESCE(sum(d.subtotal * d."exchangeRate"), 0) FROM "Document" d WHERE d."organizationId" = p."organizationId"
                           AND d."partnerId" = p.id AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')
                           AND d."issueDate" >= ? AND d."issueDate" < ?) AS revenue
                  FROM "Partner" p
                  LEFT JOIN "PartnerCategory" c ON c.id = p."categoryId"
                  LEFT JOIN "User" u ON u.id = p."salespersonId"
                  LEFT JOIN LATERAL (SELECT d."issueDate"::date AS date, d.subtotal * d."exchangeRate" AS amount FROM "Document" d
                         WHERE d."organizationId" = p."organizationId" AND d."partnerId" = p.id
                           AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')
                         ORDER BY d."issueDate" LIMIT 1) fi ON true
                 WHERE p."organizationId" = ? AND p."isCustomer" AND p."deletedAt" IS NULL
                   AND p."createdAt" >= ? AND p."createdAt" < ?""", f.from(), f.to().plusDays(1), f.from(),
                f.to().plusDays(1), org(), f.from(), f.to().plusDays(1));
        s.eq("p.\"salespersonId\"", f.salespersonId());
        s.eq("p.\"categoryId\"", f.customerCategoryId());
        s.add(" ORDER BY p.\"createdAt\" DESC");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("daysToFirst", SalesReportKit.daysBetween(r.get("created"), r.get("firstInvoice")));
            route(r, "/customers/" + r.get("id"));
        });
        Report rep = table(rows, "code|Code|text", "name|Customer|text", "category|Type|text",
                "salesperson|Salesperson|text", "created|Registered|date", "firstInvoice|First purchase|date",
                "daysToFirst|Days to first sale|days", "firstValue|First invoice value|money",
                "invoices|Invoices in period|count", "revenue|Net sales in period|money")
                .sumTotals("invoices", "revenue");
        rep.total("code", "Total");
        long buying = rows.stream().filter(r -> r.get("firstInvoice") != null).count();
        rep.kpi("new", "New customers", rows.size(), "count").kpi("buying", "Already bought", buying, "count")
                .kpi("conversion", "Converted to buyers", pct(BigDecimal.valueOf(buying), BigDecimal.valueOf(rows.size())),
                        "percent")
                .kpi("revenue", "Their net sales", rep.totals.get("revenue"), "money");
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "count", 0));
        }
        rows.forEach(r -> {
            Map<String, Object> b = m.get(String.valueOf(r.get("created")).substring(0, 7));
            if (b != null) {
                b.put("count", ((Number) b.get("count")).intValue() + 1);
            }
        });
        rep.chart("bar", "label", bar("count", "New customers")).chartData(new ArrayList<>(m.values()));
        return rep;
    }

    Report customerActivity(Filter f) {
        int days = f.days(90);
        Sql s = new Sql("""
                SELECT p.id, p.code, p.name, c.name AS category,
                       trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson,
                       a.last AS "lastPurchase", a.invoices, a.revenue
                  FROM "Partner" p
                  LEFT JOIN "PartnerCategory" c ON c.id = p."categoryId"
                  LEFT JOIN "User" u ON u.id = p."salespersonId"
                  LEFT JOIN LATERAL (SELECT max(d."issueDate")::date AS last, count(*) AS invoices,
                                            COALESCE(sum(d.subtotal * d."exchangeRate") FILTER (WHERE d."issueDate" >= ?), 0) AS revenue
                         FROM "Document" d WHERE d."organizationId" = p."organizationId" AND d."partnerId" = p.id
                          AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')
                          AND d."issueDate" < ?) a ON true
                 WHERE p."organizationId" = ? AND p."isCustomer" AND p."deletedAt" IS NULL""",
                f.asOf().minusMonths(12), f.asOf().plusDays(1), org());
        s.eq("p.\"salespersonId\"", f.salespersonId());
        s.eq("p.\"categoryId\"", f.customerCategoryId());
        s.add(" ORDER BY a.last DESC NULLS LAST, p.name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.rows(s.toString(), s.params)) {
            Long since = SalesReportKit.daysBetween(r.get("lastPurchase"), f.asOf());
            String status = since == null ? "never" : since <= days ? "active" : since <= 2L * days ? "at_risk"
                    : "inactive";
            r.put("daysSince", since);
            r.put("status", status);
            if (f.status() == null || f.status().equals(status)) {
                rows.add(route(r, "/customers/" + r.get("id")));
            }
        }
        Report rep = table(rows, "code|Code|text", "name|Customer|text", "category|Type|text",
                "salesperson|Salesperson|text", "lastPurchase|Last purchase|date", "daysSince|Days since|days",
                "invoices|Invoices (all time)|count", "revenue|Net sales last 12 months|money", "status|Status|status")
                .sumTotals("revenue");
        rep.total("code", "Total");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : List.of("active", "at_risk", "inactive", "never")) {
            counts.put(k, rows.stream().filter(r -> k.equals(r.get("status"))).count());
        }
        rep.kpi("active", "Active (≤ " + days + " days)", counts.get("active"), "count")
                .kpi("at_risk", "At risk", counts.get("at_risk"), "count")
                .kpi("inactive", "Inactive", counts.get("inactive"), "count")
                .kpi("never", "Never bought", counts.get("never"), "count");
        List<Map<String, Object>> data = new ArrayList<>();
        counts.forEach((k, v) -> data.add(Obj.of("label", SalesReportKit.label(k), "count", v)));
        rep.chart("pie", "label", bar("count", "Customers")).chartData(data);
        return rep;
    }

    Report customerOpenOrders(Filter f) {
        List<Map<String, Object>> rows = orderRows(f, " AND o.status IN ('confirmed', 'in_fulfilment') AND"
                + " o.\"deliveryStatus\" <> 'full'", false);
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = g.computeIfAbsent(String.valueOf(r.get("partnerId")), k -> Obj.of("partnerId",
                    r.get("partnerId"), "customer", r.get("customer"), "orders", 0, "remaining", 0, "openValue", 0,
                    "oldest", r.get("date"), "overdue", 0));
            c.put("orders", ((Number) c.get("orders")).intValue() + 1);
            c.put("remaining", num(dec(c.get("remaining")).add(dec(r.get("remaining")))));
            c.put("openValue", money(dec(c.get("openValue")).add(dec(r.get("openValue")))));
            if (String.valueOf(r.get("date")).compareTo(String.valueOf(c.get("oldest"))) < 0) {
                c.put("oldest", r.get("date"));
            }
            if (r.get("daysOverdue") != null) {
                c.put("overdue", ((Number) c.get("overdue")).intValue() + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> SalesReportKit.drill(r, "customer-history", "partnerId", r.get("partnerId")));
        out.sort(SalesReportKit.byDesc("openValue"));
        Report rep = table(out, "customer|Customer|text", "orders|Open orders|count", "overdue|Overdue|count",
                "remaining|Undelivered qty|qty", "openValue|Open value|money", "oldest|Oldest order|date")
                .sumTotals("orders", "overdue", "openValue");
        rep.total("customer", "Total");
        rep.kpi("customers", "Customers waiting", out.size(), "count")
                .kpi("orders", "Open orders", rep.totals.get("orders"), "count")
                .kpi("value", "Open value", rep.totals.get("openValue"), "money");
        rep.chart("hbar", "customer", bar("openValue", "Open value")).chartData(out.subList(0, Math.min(15,
                out.size())));
        return rep;
    }

    Report nonMoving(Filter f) {
        Sql s = new Sql("""
                SELECT pr.id, pr.code, pr.name, c.name AS category, COALESCE(um.symbol, um.name) AS unit,
                       ls.last AS "lastSale", st.qty AS "onHand", st.value AS "stockValue"
                  FROM "Product" pr
                  LEFT JOIN "ProductCategory" c ON c.id = pr."categoryId"
                  LEFT JOIN "UnitOfMeasure" um ON um.id = pr."uomId"
                  LEFT JOIN LATERAL (SELECT max(d."issueDate")::date AS last FROM "DocumentLine" l
                         JOIN "Document" d ON d.id = l."documentId" WHERE l."productId" = pr.id
                          AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')) ls ON true
                  LEFT JOIN LATERAL (SELECT COALESCE(sum(si.quantity), 0) AS qty,
                                            COALESCE(sum(si.quantity * COALESCE(si."runningAverageCost", 0)), 0) AS value
                         FROM "StockItem" si WHERE si."productId" = pr.id) st ON true
                 WHERE pr."organizationId" = ? AND pr."deletedAt" IS NULL AND pr."isActive"
                   AND pr."productType" <> 'service'
                   AND NOT EXISTS (SELECT 1 FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
                         WHERE l."productId" = pr.id AND d."documentType" = 'sales_invoice'
                           AND d.status IN ('posted', 'paid') AND d."issueDate" >= ? AND d."issueDate" < ?)""", org(),
                f.from(), f.to().plusDays(1));
        s.eq("pr.\"categoryId\"", f.categoryId());
        s.add(" ORDER BY st.value DESC NULLS LAST, pr.name");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("daysSince", SalesReportKit.daysBetween(r.get("lastSale"), f.to()));
            route(r, "/products/" + r.get("id") + "/edit");
        });
        Report rep = table(rows, "code|Code|text", "name|Product|text", "category|Category|text", "unit|Unit|text",
                "lastSale|Last sale (ever)|date", "daysSince|Days since|days", "onHand|On hand|qty",
                "stockValue|Stock value|money").sumTotals("stockValue");
        rep.total("code", "Total");
        rep.kpi("count", "Non-moving products", rows.size(), "count")
                .kpi("never", "Never sold", rows.stream().filter(r -> r.get("lastSale") == null).count(), "count")
                .kpi("value", "Stock value tied up", rep.totals.get("stockValue"), "money");
        List<Map<String, Object>> byCat = SalesReportKit.group(rows, r -> r.get("category"), "stockValue");
        byCat.sort(SalesReportKit.byDesc("stockValue"));
        rep.chart("hbar", "label", bar("stockValue", "Stock value")).chartData(byCat);
        return rep;
    }

    // ------------------------------------------------------------------ quotations & pipeline

    List<Map<String, Object>> quoteRows(Filter f, String where, boolean period) {
        Sql s = new Sql(QUOTES, org());
        SalesReportKit.apply(s, f, QUOTE_SCOPE, period);
        if (f.status() != null) {
            s.add(" AND q.status = ?", f.status());
        }
        s.add(where == null ? "" : where).add(" ORDER BY q.\"issueDate\" DESC, q.number DESC LIMIT 2000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            r.put("age", SalesReportKit.daysBetween(r.get("date"), f.asOf()));
            r.put("daysToExpiry", SalesReportKit.daysBetween(f.asOf(), r.get("validUntil")));
            r.put("sentDays", SalesReportKit.daysBetween(r.get("sentAt"), f.asOf()));
            r.put("daysToConvert", SalesReportKit.daysBetween(r.get("date"), r.get("convertedAt")));
            route(r, "/crm/quotations/" + r.get("id"));
        }
        return rows;
    }

    Report quotes(Filter f, String where, boolean period, String variant) {
        List<Map<String, Object>> rows = quoteRows(f, where, period);
        List<String> spec = new ArrayList<>(List.of("number|Quotation|text", "date|Date|date",
                "customer|Customer|text", "salesperson|Salesperson|text", "status|Status|status"));
        switch (variant) {
            case "rejected" -> spec.add("rejectionReason|Reason|text");
            case "expired" -> spec.add("validUntil|Valid until|date");
            case "pending" -> spec.addAll(List.of("age|Age (days)|days", "validUntil|Valid until|date",
                    "daysToExpiry|Days to expiry|days"));
            default -> spec.addAll(List.of("validUntil|Valid until|date", "convertedNumber|Converted to|text"));
        }
        spec.addAll(List.of("subtotal|Value (excl. tax)|money", "total|Total|money"));
        Report rep = table(rows, spec.toArray(String[]::new)).sumTotals("subtotal", "total");
        rep.total("number", "Total");
        rep.kpi("count", "Quotations", rows.size(), "count").kpi("value", "Value", rep.totals.get("subtotal"), "money")
                .kpi("avg", "Average value", rows.isEmpty() ? null : money(dec(rep.totals.get("subtotal"))
                        .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP)), "money");
        if ("register".equals(variant)) {
            long converted = rows.stream().filter(r -> "converted".equals(r.get("status"))).count();
            long eligible = rows.stream().filter(r -> !List.of("draft", "cancelled").contains(r.get("status"))).count();
            rep.kpi("rate", "Conversion rate", pct(BigDecimal.valueOf(converted), BigDecimal.valueOf(eligible)),
                    "percent");
        }
        List<Map<String, Object>> byStatus = SalesReportKit.group(rows, r -> SalesReportKit.label(str(r.get("status"))),
                "subtotal");
        rep.chart("pie", "label", bar("subtotal", "Value")).chartData(byStatus);
        return rep;
    }

    Report conversions(Filter f) {
        List<Map<String, Object>> rows = quoteRows(f, " AND q.status = 'converted'", true);
        Map<String, Map<String, Object>> orders = new HashMap<>();
        List<String> ids = rows.stream().map(r -> str(r.get("convertedId"))).filter(i -> i != null).toList();
        if (!ids.isEmpty()) {
            List<Object> p = new ArrayList<>();
            p.add(org());
            p.addAll(ids);
            for (Map<String, Object> o : db.rows("SELECT id, total, status FROM \"SalesOrder\" WHERE \"organizationId\" = ?"
                    + " AND id IN (" + SalesDb.marks(ids.size()) + ")", p)) {
                orders.put(str(o.get("id")), o);
            }
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> o = orders.get(str(r.get("convertedId")));
            r.put("orderTotal", o == null ? null : o.get("total"));
            r.put("orderStatus", o == null ? null : o.get("status"));
            r.put("convertedOn", SalesReportKit.asDate(r.get("convertedAt")));
            if (o != null && "sales_order".equals(r.get("convertedType"))) {
                r.put("_route", "/sales/orders/" + r.get("convertedId"));
            }
        }
        Report rep = table(rows, "number|Quotation|text", "date|Quoted|date", "customer|Customer|text",
                "salesperson|Salesperson|text", "total|Quoted value|money", "convertedNumber|Order|text",
                "convertedOn|Converted|date", "daysToConvert|Days to convert|days", "orderTotal|Order value|money",
                "orderStatus|Order status|status").sumTotals("total", "orderTotal");
        rep.total("number", "Total");
        rep.kpi("count", "Converted quotations", rows.size(), "count")
                .kpi("value", "Converted value", rep.totals.get("total"), "money")
                .kpi("days", "Avg days to convert", avg(rows, "daysToConvert"), "days");
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "count", 0, "value", 0));
        }
        rows.forEach(r -> {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.put("count", ((Number) b.get("count")).intValue() + 1);
                b.put("value", money(dec(b.get("value")).add(dec(r.get("total")))));
            }
        });
        rep.chart("combo", "label", bar("value", "Converted value"), line("count", "Quotations"))
                .chartData(new ArrayList<>(m.values()));
        return rep;
    }

    Report conversionRate(Filter f, String dim) {
        Function<Map<String, Object>, Object> key = switch (dim) {
            case "customer" -> r -> r.get("customer");
            case "branch" -> r -> r.get("branch");
            case "month" -> r -> String.valueOf(r.get("date")).substring(0, 7);
            default -> r -> r.get("salesperson");
        };
        List<Map<String, Object>> rows = quoteRows(f, " AND q.status NOT IN ('draft', 'cancelled')", true);
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = key.apply(r);
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(k), z -> Obj.of("label", k == null ? "(none)" : k,
                    "issued", 0, "converted", 0, "open", 0, "lost", 0, "value", 0, "convertedValue", 0));
            x.put("issued", ((Number) x.get("issued")).intValue() + 1);
            x.put("value", money(dec(x.get("value")).add(dec(r.get("subtotal")))));
            String st = str(r.get("status"));
            String bucket = "converted".equals(st) ? "converted" : List.of("rejected", "expired").contains(st) ? "lost"
                    : "open";
            x.put(bucket, ((Number) x.get(bucket)).intValue() + 1);
            if ("converted".equals(st)) {
                x.put("convertedValue", money(dec(x.get("convertedValue")).add(dec(r.get("subtotal")))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> {
            r.put("rate", pct(dec(r.get("converted")), dec(r.get("issued"))));
            r.put("valueRate", pct(dec(r.get("convertedValue")), dec(r.get("value"))));
        });
        if (!"month".equals(dim)) {
            out.sort(SalesReportKit.byDesc("issued"));
        } else {
            out.sort(Comparator.comparing(r -> String.valueOf(r.get("label"))));
        }
        String title = switch (dim) {
            case "customer" -> "Customer";
            case "branch" -> "Branch";
            case "month" -> "Month";
            default -> "Salesperson";
        };
        Report rep = table(out, "label|" + title + "|text", "issued|Issued|count", "converted|Converted|count",
                "open|Open|count", "lost|Rejected / expired|count", "rate|Conversion %|percent",
                "value|Quoted value|money", "convertedValue|Converted value|money", "valueRate|Value conversion %|percent")
                .sumTotals("issued", "converted", "open", "lost", "value", "convertedValue");
        rep.total("label", "Total");
        rep.total("rate", pct(dec(rep.totals.get("converted")), dec(rep.totals.get("issued"))));
        rep.total("valueRate", pct(dec(rep.totals.get("convertedValue")), dec(rep.totals.get("value"))));
        rep.kpi("issued", "Eligible quotations", rep.totals.get("issued"), "count")
                .kpi("converted", "Converted", rep.totals.get("converted"), "count")
                .kpi("rate", "Conversion rate", rep.totals.get("rate"), "percent", "Converted ÷ eligible × 100")
                .kpi("valueRate", "Value conversion", rep.totals.get("valueRate"), "percent");
        rep.stacked("bar", "label", bar("converted", "Converted"), bar("open", "Open"),
                bar("lost", "Rejected / expired"));
        rep.note("Eligible quotations exclude drafts and cancelled quotations.");
        return rep;
    }

    Report followUp(Filter f) {
        int days = f.days(7);
        List<Map<String, Object>> rows = quoteRows(f, " AND q.status IN ('approved', 'sent') AND ((q.\"sentAt\" IS NOT"
                + " NULL AND q.\"sentAt\" < '" + f.asOf().minusDays(days) + "') OR (q.\"validUntil\" IS NOT NULL AND"
                + " q.\"validUntil\" <= '" + f.asOf().plusDays(days) + "'))", false);
        for (Map<String, Object> r : rows) {
            Long exp = (Long) r.get("daysToExpiry");
            r.put("action", exp != null && exp < 0 ? "Expired — renew or close" : exp != null && exp <= days
                    ? "Expiring — call customer" : "No response — follow up");
        }
        Report rep = table(rows, "number|Quotation|text", "date|Date|date", "customer|Customer|text",
                "salesperson|Salesperson|text", "status|Status|status", "sentDays|Days since sent|days",
                "validUntil|Valid until|date", "daysToExpiry|Days to expiry|days", "subtotal|Value|money",
                "action|Next step|text").sumTotals("subtotal");
        rep.total("number", "Total");
        rep.kpi("count", "Need follow-up", rows.size(), "count").kpi("value", "Value at stake", rep.totals.get(
                "subtotal"), "money");
        List<Map<String, Object>> bySp = SalesReportKit.group(rows, r -> r.get("salesperson"), "subtotal");
        rep.chart("hbar", "label", bar("subtotal", "Value")).chartData(bySp);
        return rep;
    }

    Report quotationValue(Filter f) {
        List<Map<String, Object>> rows = quoteRows(f, null, true);
        List<Map<String, Object>> g = SalesReportKit.group(rows, r -> SalesReportKit.label(str(r.get("status"))),
                "subtotal", "total");
        BigDecimal total = SalesReportKit.sum(g, "subtotal");
        g.forEach(r -> {
            r.put("avg", money(dec(r.get("subtotal")).divide(dec(r.get("count")), 2, RoundingMode.HALF_UP)));
            r.put("share", pct(dec(r.get("subtotal")), total));
        });
        Report rep = table(g, "label|Status|status", "count|Quotations|count", "subtotal|Value (excl. tax)|money",
                "total|Value incl. tax|money", "avg|Average|money", "share|Share %|percent")
                .sumTotals("count", "subtotal", "total");
        rep.total("label", "Total");
        rep.kpi("count", "Quotations", rows.size(), "count").kpi("value", "Total quoted", rep.totals.get("subtotal"),
                "money");
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "count", 0, "value", 0));
        }
        rows.forEach(r -> {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.put("count", ((Number) b.get("count")).intValue() + 1);
                b.put("value", money(dec(b.get("value")).add(dec(r.get("subtotal")))));
            }
        });
        rep.chart("combo", "label", bar("value", "Quoted value"), line("count", "Quotations"))
                .chartData(new ArrayList<>(m.values()));
        return rep;
    }

    private static final String DEALS = """
            SELECT dl.id, dl.name, dl.amount, dl.probability, dl."expectedClose", dl.status, dl."lostAt", dl."wonAt",
                   dl."lostNote", COALESCE(p.name, dl."companyName", dl."contactName") AS customer,
                   trim(u."firstName" || ' ' || COALESCE(u."lastName", '')) AS salesperson, st.name AS stage,
                   st.sequence AS "stageSequence", COALESCE(dl.probability, st.probability, 0) AS prob,
                   lr.name AS "lostReason", dl."createdAt"
              FROM "Deal" dl
              LEFT JOIN "Partner" p ON p.id = dl."partnerId"
              LEFT JOIN "User" u ON u.id = dl."ownerId"
              LEFT JOIN "CrmStage" st ON st.id = dl."stageId"
              LEFT JOIN "CrmLostReason" lr ON lr.id = dl."lostReasonId"
             WHERE dl."organizationId" = ? AND dl."deletedAt" IS NULL""";

    List<Map<String, Object>> deals(Filter f, String where, Object... params) {
        Sql s = new Sql(DEALS, org());
        s.eq("dl.\"ownerId\"", f.salespersonId());
        s.eq("dl.\"partnerId\"", f.partnerId());
        s.add(where, params).add(" ORDER BY dl.amount DESC NULLS LAST LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        rows.forEach(r -> {
            r.put("weighted", money(dec(r.get("amount")).multiply(dec(r.get("prob"))).movePointLeft(2)));
            route(r, "/crm/deals/" + r.get("id"));
        });
        return rows;
    }

    Report lostDeals(Filter f) {
        List<Map<String, Object>> rows = deals(f, " AND dl.status = 'lost' AND dl.\"lostAt\" >= ? AND dl.\"lostAt\" < ?",
                f.from(), f.to().plusDays(1));
        rows.forEach(r -> r.put("lostOn", SalesReportKit.asDate(r.get("lostAt"))));
        Report rep = table(rows, "name|Opportunity|text", "customer|Customer|text", "salesperson|Salesperson|text",
                "stage|Stage when lost|text", "amount|Value|money", "lostOn|Lost on|date", "lostReason|Reason|text",
                "lostNote|Note|text").sumTotals("amount");
        rep.total("name", "Total");
        rep.kpi("count", "Lost opportunities", rows.size(), "count").kpi("value", "Value lost", rep.totals.get("amount"),
                "money");
        List<Map<String, Object>> byReason = SalesReportKit.group(rows, r -> r.get("lostReason"), "amount");
        rep.chart("pie", "label", bar("amount", "Value lost")).chartData(byReason);
        return rep;
    }

    Report pipeline(Filter f) {
        List<Map<String, Object>> rows = deals(f, " AND dl.status = 'open'");
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        rows.sort(Comparator.comparing(r -> dec(r.get("stageSequence"))));
        for (Map<String, Object> r : rows) {
            Object st = r.get("stage");
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(st), k -> Obj.of("stage", st == null ? "(no stage)"
                    : st, "deals", 0, "amount", 0, "weighted", 0, "probability", r.get("prob")));
            x.put("deals", ((Number) x.get("deals")).intValue() + 1);
            x.put("amount", money(dec(x.get("amount")).add(dec(r.get("amount")))));
            x.put("weighted", money(dec(x.get("weighted")).add(dec(r.get("weighted")))));
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> SalesReportKit.route(r, "/crm/pipeline"));
        Report rep = table(out, "stage|Stage|text", "deals|Deals|count", "amount|Pipeline value|money",
                "probability|Stage probability %|percent", "weighted|Weighted value|money")
                .sumTotals("deals", "amount", "weighted");
        rep.total("stage", "Total");
        rep.kpi("deals", "Open deals", rep.totals.get("deals"), "count")
                .kpi("amount", "Pipeline value", rep.totals.get("amount"), "money")
                .kpi("weighted", "Weighted value", rep.totals.get("weighted"), "money");
        rep.chart("bar", "stage", bar("amount", "Pipeline value"), bar("weighted", "Weighted value"));
        return rep;
    }

    Report opportunityForecast(Filter f) {
        List<Map<String, Object>> rows = deals(f, " AND dl.status = 'open'");
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        g.put("overdue", Obj.of("label", "Overdue / no date", "deals", 0, "amount", 0, "weighted", 0));
        for (String k : Bucket.MONTH.keys(first, first.plusMonths(11))) {
            g.put(k, Obj.of("label", k, "deals", 0, "amount", 0, "weighted", 0));
        }
        g.put("later", Obj.of("label", "Later", "deals", 0, "amount", 0, "weighted", 0));
        for (Map<String, Object> r : rows) {
            LocalDate d = SalesReportKit.asDate(r.get("expectedClose"));
            String k = d == null || d.isBefore(first) ? "overdue" : g.containsKey(d.toString().substring(0, 7))
                    ? d.toString().substring(0, 7) : "later";
            Map<String, Object> x = g.get(k);
            x.put("deals", ((Number) x.get("deals")).intValue() + 1);
            x.put("amount", money(dec(x.get("amount")).add(dec(r.get("amount")))));
            x.put("weighted", money(dec(x.get("weighted")).add(dec(r.get("weighted")))));
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        Report rep = table(out, "label|Expected close|text", "deals|Deals|count", "amount|Value|money",
                "weighted|Weighted value|money").sumTotals("deals", "amount", "weighted");
        rep.total("label", "Total");
        rep.kpi("amount", "Open opportunity value", rep.totals.get("amount"), "money")
                .kpi("weighted", "Probability-weighted", rep.totals.get("weighted"), "money");
        rep.chart("combo", "label", bar("amount", "Value"), line("weighted", "Weighted value"));
        return rep;
    }

    Report activity(Filter f) {
        Sql s = new Sql("SELECT COALESCE(a.\"assignedToId\", a.\"createdById\") AS uid, " + SP_NAME + " AS salesperson,"
                + " a.type, count(*) AS n, count(*) FILTER (WHERE a.completed) AS done FROM \"Activity\" a LEFT JOIN"
                + " \"User\" u ON u.id = COALESCE(a.\"assignedToId\", a.\"createdById\") WHERE a.\"organizationId\" = ?"
                + " AND a.\"deletedAt\" IS NULL AND a.type <> 'deal_stage_change' AND COALESCE(a.\"occurredAt\","
                + " a.\"createdAt\") >= ? AND COALESCE(a.\"occurredAt\", a.\"createdAt\") < ?", org(), f.from(),
                f.to().plusDays(1));
        s.eq("COALESCE(a.\"assignedToId\", a.\"createdById\")", f.salespersonId());
        s.add(" GROUP BY 1, 2, 3");
        List<String> types = List.of("call", "meeting", "email", "task", "note");
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(s.toString(), s.params)) {
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(r.get("uid")), k -> {
                Map<String, Object> n = Obj.of("salesperson", r.get("salesperson") == null ? "(none)"
                        : r.get("salesperson"), "total", 0, "completed", 0, "other", 0);
                types.forEach(t -> n.put(t, 0));
                return n;
            });
            String t = types.contains(str(r.get("type"))) ? str(r.get("type")) : "other";
            x.put(t, ((Number) x.get(t)).intValue() + ((Number) r.get("n")).intValue());
            x.put("total", ((Number) x.get("total")).intValue() + ((Number) r.get("n")).intValue());
            x.put("completed", ((Number) x.get("completed")).intValue() + ((Number) r.get("done")).intValue());
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        out.forEach(r -> r.put("completion", pct(dec(r.get("completed")), dec(r.get("total")))));
        out.sort(SalesReportKit.byDesc("total"));
        Report rep = table(out, "salesperson|Salesperson|text", "call|Calls|count", "meeting|Meetings|count",
                "email|Emails|count", "task|Tasks|count", "note|Notes|count", "other|Other|count",
                "total|Total|count", "completion|Completed %|percent")
                .sumTotals("call", "meeting", "email", "task", "note", "other", "total", "completed");
        rep.total("salesperson", "Total");
        rep.kpi("total", "Activities", rep.totals.get("total"), "count")
                .kpi("calls", "Calls", rep.totals.get("call"), "count")
                .kpi("meetings", "Meetings / visits", rep.totals.get("meeting"), "count")
                .kpi("completion", "Completed", pct(dec(rep.totals.get("completed")), dec(rep.totals.get("total"))),
                        "percent");
        rep.stacked("bar", "salesperson", bar("call", "Calls"), bar("meeting", "Meetings"), bar("email", "Emails"),
                bar("task", "Tasks"));
        return rep;
    }

    // ------------------------------------------------------------------ deliveries

    List<Map<String, Object>> loadRows(Filter f, String where) {
        Sql s = new Sql(LOADS, org());
        SalesReportKit.apply(s, f, LOAD_SCOPE, true);
        s.add(where == null ? "" : where).add(" ORDER BY COALESCE(t.\"dispatchedAt\"::date, t.\"plannedDate\") DESC,"
                + " t.code DESC LIMIT 3000");
        List<Map<String, Object>> rows = db.rows(s.toString(), s.params);
        for (Map<String, Object> r : rows) {
            LocalDate delivered = SalesReportKit.asDate(r.get("deliveredAt"));
            LocalDate due = SalesReportKit.asDate(r.get("dueDate"));
            r.put("deliveredOn", delivered);
            r.put("dispatchedOn", SalesReportKit.asDate(r.get("dispatchedAt")));
            String timing = delivered == null ? "in_transit" : due == null ? "no_due_date" : delivered.isAfter(due)
                    ? "late" : "on_time";
            r.put("timing", timing);
            r.put("daysLate", delivered != null && due != null && delivered.isAfter(due)
                    ? SalesReportKit.daysBetween(due, delivered) : null);
            r.put("tonnes", r.get("deliveredTonnes") != null ? r.get("deliveredTonnes") : r.get("loadedTonnes"));
            r.put("transit", num(dec(r.get("loadedTonnes")).subtract(dec(r.get("deliveredTonnes")))));
            route(r, "/fleet/delivery-orders/" + r.get("deliveryOrderId"));
        }
        return rows;
    }

    Report loads(Filter f, String where, String variant) {
        List<Map<String, Object>> rows = loadRows(f, where);
        List<String> spec = new ArrayList<>(List.of("code|Load|text", "dispatchedOn|Dispatched|date",
                "deliveryOrder|Delivery order|text", "orderNumber|Sales order|text", "customer|Customer|text",
                "site|Site|text", "vehicle|Vehicle|text", "driver|Driver|text"));
        switch (variant) {
            case "late" -> spec.addAll(List.of("dueDate|Due|date", "deliveredOn|Delivered|date",
                    "daysLate|Days late|days", "tonnes|Tonnes|qty", "notes|Notes|text"));
            case "notes" -> spec.addAll(List.of("deliveryNoteNumber|Delivery note|text",
                    "deliveryNotePrints|Prints|count", "tonnes|Tonnes|qty", "status|Status|status"));
            default -> spec.addAll(List.of("loadedTonnes|Loaded t|qty", "deliveredTonnes|Delivered t|qty",
                    "weighedNetQty|Weighed net|qty", "freightAmount|Freight|money", "deliveredOn|Delivered|date",
                    "timing|On time?|status", "status|Status|status"));
        }
        Report rep = table(rows, spec.toArray(String[]::new)).sumTotals("loadedTonnes", "deliveredTonnes", "tonnes",
                "freightAmount", "weighedNetQty");
        rep.total("code", "Total");
        long late = rows.stream().filter(r -> "late".equals(r.get("timing"))).count();
        long onTime = rows.stream().filter(r -> "on_time".equals(r.get("timing"))).count();
        rep.kpi("trips", "Trips", rows.size(), "count").kpi("tonnes", "Tonnes delivered", rep.totals.get("tonnes"),
                "qty")
                .kpi("perTrip", "Tonnes per trip", rows.isEmpty() ? null : num(dec(rep.totals.get("tonnes"))
                        .divide(BigDecimal.valueOf(rows.size()), 3, RoundingMode.HALF_UP)), "qty")
                .kpi("onTime", "On time", pct(BigDecimal.valueOf(onTime), BigDecimal.valueOf(onTime + late)), "percent");
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : Bucket.MONTH.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", k, "trips", 0, "tonnes", 0));
        }
        rows.forEach(r -> {
            Map<String, Object> b = m.get(String.valueOf(r.get("date")).substring(0, 7));
            if (b != null) {
                b.put("trips", ((Number) b.get("trips")).intValue() + 1);
                b.put("tonnes", num(dec(b.get("tonnes")).add(dec(r.get("tonnes")))));
            }
        });
        rep.chart("combo", "label", bar("tonnes", "Tonnes"), line("trips", "Trips"))
                .chartData(new ArrayList<>(m.values()));
        return rep;
    }

    Report deliveryPerformance(Filter f) {
        Bucket b = Bucket.of(f.groupBy(), Bucket.MONTH);
        List<Map<String, Object>> rows = loadRows(f, " AND t.\"deliveredAt\" IS NOT NULL");
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : b.keys(f.from(), f.to())) {
            m.put(k, Obj.of("label", b.label(k), "delivered", 0, "on_time", 0, "late", 0, "no_due_date", 0, "tonnes",
                    0));
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = m.get(b.key(SalesReportKit.asDate(r.get("date"))));
            if (x == null) {
                continue;
            }
            String t = str(r.get("timing"));
            x.put("delivered", ((Number) x.get("delivered")).intValue() + 1);
            x.put(t, ((Number) x.get(t)).intValue() + 1);
            x.put("tonnes", num(dec(x.get("tonnes")).add(dec(r.get("tonnes")))));
        }
        List<Map<String, Object>> out = new ArrayList<>(m.values());
        out.forEach(r -> r.put("onTimeRate", pct(dec(r.get("on_time")), dec(r.get("on_time")).add(dec(r.get(
                "late"))))));
        Report rep = table(out, "label|Period|text", "delivered|Delivered loads|count", "on_time|On time|count",
                "late|Late|count", "no_due_date|No due date|count", "onTimeRate|On-time %|percent",
                "tonnes|Tonnes|qty").sumTotals("delivered", "on_time", "late", "no_due_date", "tonnes");
        rep.total("label", "Total");
        rep.total("onTimeRate", pct(dec(rep.totals.get("on_time")), dec(rep.totals.get("on_time"))
                .add(dec(rep.totals.get("late")))));
        rep.kpi("delivered", "Delivered loads", rep.totals.get("delivered"), "count")
                .kpi("onTime", "On-time rate", rep.totals.get("onTimeRate"), "percent")
                .kpi("late", "Late loads", rep.totals.get("late"), "count")
                .kpi("avgLate", "Avg days late", avg(rows, "daysLate"), "days");
        rep.stacked("bar", "label", bar("on_time", "On time"), bar("late", "Late"), bar("no_due_date", "No due date"));
        return rep;
    }

    Report loadGroup(Filter f, String key, String title) {
        List<Map<String, Object>> rows = loadRows(f, null);
        Map<String, Map<String, Object>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = r.get(key);
            Map<String, Object> x = g.computeIfAbsent(String.valueOf(k), z -> Obj.of("label", k == null ? "(none)" : k,
                    "trips", 0, "loaded", 0, "delivered", 0, "freight", 0, "distance", 0, "on_time", 0, "late", 0,
                    "customers", new java.util.HashSet<>()));
            x.put("trips", ((Number) x.get("trips")).intValue() + 1);
            x.put("loaded", num(dec(x.get("loaded")).add(dec(r.get("loadedTonnes")))));
            x.put("delivered", num(dec(x.get("delivered")).add(dec(r.get("tonnes")))));
            x.put("freight", money(dec(x.get("freight")).add(dec(r.get("freightAmount")))));
            x.put("distance", num(dec(x.get("distance")).add(dec(r.get("distanceKm")))));
            String t = str(r.get("timing"));
            if ("on_time".equals(t) || "late".equals(t)) {
                x.put(t, ((Number) x.get(t)).intValue() + 1);
            }
            @SuppressWarnings("unchecked")
            java.util.Set<Object> cs = (java.util.Set<Object>) x.get("customers");
            cs.add(r.get("partnerId"));
        }
        List<Map<String, Object>> out = new ArrayList<>(g.values());
        for (Map<String, Object> r : out) {
            r.put("customers", ((java.util.Set<?>) r.get("customers")).size());
            BigDecimal trips = dec(r.get("trips"));
            r.put("perTrip", num(dec(r.get("delivered")).divide(trips, 3, RoundingMode.HALF_UP)));
            BigDecimal del = dec(r.get("delivered"));
            r.put("freightPerTonne", del.signum() == 0 ? null : money(dec(r.get("freight")).divide(del, 2,
                    RoundingMode.HALF_UP)));
            r.put("onTimeRate", pct(dec(r.get("on_time")), dec(r.get("on_time")).add(dec(r.get("late")))));
        }
        out.sort(SalesReportKit.byDesc("delivered"));
        Report rep = table(out, "label|" + title + "|text", "trips|Trips|count", "customers|Customers|count",
                "loaded|Loaded t|qty", "delivered|Delivered t|qty", "perTrip|Tonnes / trip|qty",
                "distance|Distance km|number", "freight|Freight|money", "freightPerTonne|Freight / t|money",
                "onTimeRate|On-time %|percent").sumTotals("trips", "loaded", "delivered", "distance", "freight");
        rep.total("label", "Total");
        rep.kpi("trips", "Trips", rep.totals.get("trips"), "count")
                .kpi("tonnes", "Tonnes delivered", rep.totals.get("delivered"), "qty")
                .kpi("freight", "Freight", rep.totals.get("freight"), "money")
                .kpi("count", title + "s", out.size(), "count");
        rep.chart("hbar", "label", bar("delivered", "Delivered t"), bar("trips", "Trips"))
                .chartData(out.subList(0, Math.min(15, out.size())));
        return rep;
    }
}
