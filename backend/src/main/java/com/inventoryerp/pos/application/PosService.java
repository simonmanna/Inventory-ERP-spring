package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.kernel.common.DetachedWrites;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.PosTableOrder;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Counter checkout and open-tab dine-in (port of {@code pos.service.ts}): every sale runs Order → Invoice → Receipt;
 * checkout / tab settle / order settle share one recoverable settle tail.
 */
@Service
public class PosService {

    private static final Logger log = LoggerFactory.getLogger(PosService.class);

    private final PosDb db;
    private final JdbcClient jdbc;
    private final EventBus events;
    private final PosOrdersService orders;
    private final PosInvoiceService billing;
    private final PosTablesService tables;
    private final PosLoyaltyService loyalty;
    private final PosReceiptsService receipts;
    private final PosOverridesService overrides;
    private final PosSettingsService settings;
    private final PricingPolicy pricing;
    private final TransactionTemplate tx;
    private final DetachedWrites detached;

    public PosService(PosDb db, EventBus events, PosOrdersService orders, PosInvoiceService billing,
            PosTablesService tables, PosLoyaltyService loyalty, PosReceiptsService receipts, PosOverridesService overrides,
            PosSettingsService settings, PricingPolicy pricing, PlatformTransactionManager txm, DetachedWrites detached) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.events = events;
        this.orders = orders;
        this.billing = billing;
        this.tables = tables;
        this.loyalty = loyalty;
        this.receipts = receipts;
        this.overrides = overrides;
        this.settings = settings;
        this.pricing = pricing;
        this.tx = new TransactionTemplate(txm);
        this.detached = detached;
    }

    private static String recoveryOrderId() {
        BusinessOutcome.Operation op = BusinessOutcome.current();
        if (op == null || op.recovery() == null) {
            return null;
        }
        JsonNode r = op.recovery().get("orderId");
        return r == null || r.isNull() ? null : r.asString();
    }

    /**
     * One sale request: configuration reads are memoized for its duration ({@link ReadMemo}) and its fire-and-forget
     * outbox / audit writes go out in one detached transaction when it ends ({@link DetachedWrites#batch}).
     */
    private <T> T saleScope(Supplier<T> body) {
        return ReadMemo.run(() -> detached.batch(body));
    }

    /** Counter sale: Order → kitchen → Invoice (GL + stock job) → payment (Receipt, close). */
    public Map<String, Object> checkout(Map<String, Object> inputIn) {
        return saleScope(() -> checkoutInScope(inputIn));
    }

    private Map<String, Object> checkoutInScope(Map<String, Object> inputIn) {
        Map<String, Object> input = new LinkedHashMap<>(inputIn);
        BusinessOutcome.Operation op = BusinessOutcome.current();
        String recovery = recoveryOrderId();
        Map<String, Object> previous = null;
        if (recovery != null) {
            previous = orders.getOrder(recovery);
        } else if (op != null) {
            previous = db.first(Order.class, "\"organizationId\" = ? AND \"clientOperationKey\" = ?", org(), op.key());
        }
        if (previous != null) {
            return settleResolvedOrder(previous, input);
        }
        List<Map<String, Object>> lines = PosOrdersService.maps(input.get("lines"));
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Cart is empty");
        }
        String orgId = org();
        OccurredAt.resolve((String) input.get("occurredAt"));
        String cashSessionId = requireCashSession(input);
        input.put("cashSessionId", cashSessionId);
        boolean isCredit = "credit".equals(input.get("settleMode"));
        if (isCredit) {
            assertCreditCustomer((String) input.get("partnerId"));
        }
        PosOrdersService.Quote quote = orders.quoteRaw(input);
        double effectiveDiscount = pricing.assertAuthority(PosOrdersService.pricingLines(quote.baseLines()), input);
        if (input.get("expectedTotal") != null && Math.abs(quote.total() - num(input.get("expectedTotal"))) > 0.000001) {
            throw ApiException.badRequest("The price changed. Refresh the quote before payment.");
        }
        billing.preflightPayment(quote.total(), input, (String) input.get("partnerId"));
        Map<String, Object> createDto = new LinkedHashMap<>();
        createDto.put("orderType", input.get("orderType") != null ? input.get("orderType")
                : input.get("tableId") != null ? "dine_in" : "takeaway");
        createDto.put("tableId", input.get("tableId"));
        createDto.put("partnerId", input.get("partnerId"));
        createDto.put("branchId", input.get("branchId"));
        createDto.put("cashSessionId", input.get("cashSessionId"));
        createDto.put("guestCount", input.get("guestCount"));
        createDto.put("notes", input.get("notes"));
        createDto.put("lines", toOrderLines(lines));
        Map<String, Object> order = orders.createOrder(createDto);
        String orderId = (String) order.get("id");
        try {
            orders.fireKitchen(orderId);
        } catch (RuntimeException e) {
            log.warn("fireKitchen failed: {}", e.getMessage());
        }
        Map<String, Object> genDto = invoiceDto(input);
        genDto.put("branchId", input.get("branchId"));
        if (!isCredit) {
            List<Object> tenders = PosSql.list(input.get("tenders"));
            genDto.put("paymentMode", !tenders.isEmpty() ? resolvePaymentMode(tenders)
                    : "bank".equals(input.get("paymentMethod")) ? "card"
                    : input.get("paymentMethod") != null ? input.get("paymentMethod") : "cash");
        }
        Map<String, Object> invoice = billing.generateInvoice(orderId, genDto);
        Map<String, Object> pay = isCredit ? billing.settleCredit((String) invoice.get("id"), Obj.of("partnerId",
                input.get("partnerId"))) : billing.receivePayment((String) invoice.get("id"), paymentDto(input));
        Map<String, Object> result = afterSettle(orgId, invoice, input, pay, isCredit);
        Map<String, Object> out = Obj.of("invoiceId", invoice.get("id"), "invoiceNumber", invoice.get("invoiceNumber"),
                "orderId", orderId, "orderNumber", order.get("orderNumber"));
        out.putAll(result);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : List.of("invoiceId", "invoiceNumber", "orderId", "orderNumber", "paymentIds", "total", "change",
                "settleMode", "amountDue")) {
            ordered.put(k, out.get(k));
        }
        ordered.put("discountPercent", PosSql.js(effectiveDiscount));
        putReceipt(ordered, out);
        return ordered;
    }

    private static void putReceipt(Map<String, Object> target, Map<String, Object> src) {
        if (src.get("receiptId") != null) {
            target.put("receiptId", src.get("receiptId"));
        }
        if (src.get("receiptText") != null) {
            target.put("receiptText", src.get("receiptText"));
        }
        if (src.get("receiptHtml") != null) {
            target.put("receiptHtml", src.get("receiptHtml"));
        }
    }

    private static Map<String, Object> invoiceDto(Map<String, Object> input) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("cashSessionId", "transactionDiscountPercent", "transactionDiscountType",
                "transactionDiscountAmount", "discountReason", "overrideById", "overridePin", "expectedTotal",
                "expectedVersion", "occurredAt")) {
            if (input.get(k) != null) {
                d.put(k, input.get(k));
            }
        }
        return d;
    }

    private static Map<String, Object> paymentDto(Map<String, Object> input) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("tenders", "paymentMethod", "amountTendered", "cashSessionId", "occurredAt")) {
            if (input.get(k) != null) {
                d.put(k, input.get(k));
            }
        }
        return d;
    }

    /** Loyalty, sale event, receipt render and the shared response fields. */
    private Map<String, Object> afterSettle(String orgId, Map<String, Object> invoice, Map<String, Object> input,
            Map<String, Object> pay, boolean isCredit) {
        double total = num(invoice.get("totalAmount"));
        try {
            Object partnerId = invoice.get("partnerId");
            if (partnerId != null) {
                String code = jdbc.sql("SELECT code FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                        .params(partnerId, orgId).query(String.class).optional().orElse(null);
                boolean exists = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(partnerId, orgId).query(Long.class).single() > 0;
                if (exists && !"WALKIN".equals(code)) {
                    loyalty.earnPoints((String) partnerId, (String) invoice.get("id"), total, "sale");
                }
            }
        } catch (RuntimeException e) {
            log.error("loyalty earnPoints failed: {}", e.getMessage());
        }
        events.publish(DomainEvents.POS_SALE_COMPLETED, Obj.of("organizationId", orgId, "invoiceId", invoice.get("id"),
                "invoiceNumber", invoice.get("invoiceNumber"), "cashSessionId", input.get("cashSessionId"),
                "total", PosSql.jsString(invoice.get("totalAmount"))));
        double tendered = input.get("amountTendered") != null ? num(input.get("amountTendered")) : total;
        String receiptText = null;
        String receiptHtml = null;
        try {
            Map<String, Object> resolved = receipts.resolveInvoice((String) invoice.get("id"));
            receiptText = receipts.buildTextReceipt(resolved, false, null);
            receiptHtml = receipts.buildHtmlReceipt(resolved, false, null);
        } catch (RuntimeException ignored) {
            // non-fatal
        }
        Object change = isCredit ? 0 : pay != null && pay.get("change") != null ? pay.get("change")
                : PosSql.js(Math.max(0, tendered - total));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentIds", pay != null && pay.get("paymentIds") != null ? pay.get("paymentIds") : List.of());
        out.put("total", PosSql.js(total));
        out.put("change", change);
        out.put("settleMode", isCredit ? "credit" : "tender");
        out.put("amountDue", isCredit ? PosSql.js(total) : 0);
        out.put("receiptId", pay == null ? null : pay.get("receiptId"));
        out.put("receiptText", receiptText);
        out.put("receiptHtml", receiptHtml);
        return out;
    }

    /** Map a POS cart line to the Order line (raw — server-side folding / pricing). */
    private static List<Map<String, Object>> toOrderLines(List<Map<String, Object>> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("productId", "menuItemId", "sku", "description", "quantity", "unitPrice", "taxId",
                    "discountPercent", "discountType", "discountAmount", "discountReason", "note", "modifiers", "variantId",
                    "accompanimentOptionIds", "comboId", "taxInclusive")) {
                m.put(k, l.get(k));
            }
            out.add(m);
        }
        return out;
    }

    // ─── tabs ───

    public Map<String, Object> getTab(String tableId) {
        Map<String, Object> order = orders.getOpenOrderForTable(tableId);
        return order == null ? null : toTabView(order);
    }

    public Map<String, Object> resumeOrder(String orderId) {
        return toTabView(orders.getOrder(orderId));
    }

    /** The terminal's tab view of an open order. */
    private Map<String, Object> toTabView(Map<String, Object> o) {
        String orgId = org();
        Map<String, Object> customer = o.get("partnerId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, code, name FROM"
                + " \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(o.get("partnerId"), orgId));
        List<Map<String, Object>> items = PosOrdersService.maps(o.get("items"));
        Set<String> variantIds = new LinkedHashSet<>();
        Set<String> optionIds = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            if (it.get("variantId") != null) {
                variantIds.add((String) it.get("variantId"));
            }
            optionIds.addAll(PosSql.strings(it.get("accompanimentOptionIds")));
        }
        Map<String, Object> variantPrice = new HashMap<>();
        for (String v : variantIds) {
            Object p = jdbc.sql("SELECT price FROM \"MenuItemVariant\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(v, orgId).query(PosSql.SCALAR).optional().orElse(null);
            if (p != null) {
                variantPrice.put(v, PosSql.js(p));
            }
        }
        Map<String, Double> optionImpact = new HashMap<>();
        for (String id : optionIds) {
            Object p = jdbc.sql("SELECT \"priceImpact\" FROM \"AccompanimentOption\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(id, orgId).query(PosSql.SCALAR).optional().orElse(null);
            if (p != null) {
                optionImpact.put(id, num(p));
            }
        }
        Set<String> staffIds = new LinkedHashSet<>();
        if (o.get("waiterId") != null) {
            staffIds.add((String) o.get("waiterId"));
        }
        for (Map<String, Object> it : items) {
            if (it.get("punchedById") != null && it.get("punchedByName") == null) {
                staffIds.add((String) it.get("punchedById"));
            }
        }
        Map<String, String> staffName = new HashMap<>();
        for (String id : staffIds) {
            Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"User\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (u != null) {
                staffName.put(id, (u.get("firstName") + (PosSql.truthy(u.get("lastName")) ? " " + u.get("lastName") : "")).trim());
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", o.get("id"));
        view.put("orderNumber", o.get("orderNumber"));
        view.put("status", o.get("status"));
        view.put("legacyStatus", OrderStatusUtil.toLegacy((String) o.get("status")));
        view.put("version", o.get("version") == null ? 0 : o.get("version"));
        view.put("subtotal", PosSql.jsString(o.get("subtotal") == null ? 0 : o.get("subtotal")));
        view.put("discountTotal", PosSql.jsString(o.get("discountTotal") == null ? 0 : o.get("discountTotal")));
        view.put("taxAmount", PosSql.jsString(o.get("taxAmount") == null ? 0 : o.get("taxAmount")));
        view.put("totalAmount", PosSql.jsString(o.get("totalAmount") == null ? 0 : o.get("totalAmount")));
        view.put("guestCount", o.get("guestCount"));
        view.put("partnerId", o.get("partnerId"));
        view.put("customer", customer);
        view.put("tableId", o.get("tableId"));
        view.put("orderType", o.get("orderType"));
        view.put("waiterId", o.get("waiterId"));
        view.put("waiterName", o.get("waiterId") == null ? null : staffName.get((String) o.get("waiterId")));
        view.put("transactionDiscountPercent", PosSql.js(o.get("transactionDiscountPercent")));
        view.put("transactionDiscountType", o.get("transactionDiscountType"));
        view.put("transactionDiscountAmount", PosSql.js(o.get("transactionDiscountAmount")));
        view.put("discountReason", o.get("discountReason"));
        view.put("cashSessionId", o.get("cashSessionId"));
        view.put("billPrintCount", PosSql.js(o.get("billPrintCount")));
        view.put("kotPrintCount", PosSql.js(o.get("kotPrintCount")));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> it : items) {
            double qty = num(it.get("quantity"));
            double unit = num(it.get("unitPrice"));
            double disc = num(it.get("discountPercent"));
            double total = qty * unit - ("fixed_amount".equals(it.get("discountType")) ? num(it.get("discountAmount"))
                    : qty * unit * disc / 100);
            List<String> accIds = PosSql.strings(it.get("accompanimentOptionIds"));
            List<String> parts = new ArrayList<>();
            for (String part : (it.get("note") == null ? "" : it.get("note").toString()).split(" \\| ", -1)) {
                if (!part.isEmpty() && !part.startsWith("+ ")) {
                    parts.add(part);
                }
            }
            String userNote = String.join(" | ", parts);
            double impact = 0;
            for (String id : accIds) {
                impact += optionImpact.getOrDefault(id, 0.0);
            }
            Object punchedById = it.get("punchedById") != null ? it.get("punchedById") : o.get("waiterId");
            Object punchedByName = it.get("punchedByName") != null ? it.get("punchedByName")
                    : it.get("punchedById") != null && staffName.get((String) it.get("punchedById")) != null
                            ? staffName.get((String) it.get("punchedById"))
                            : o.get("waiterId") != null ? staffName.get((String) o.get("waiterId")) : null;
            List<Map<String, Object>> mods = new ArrayList<>();
            for (Object m : PosSql.list(it.get("modifiers"))) {
                Map<String, Object> mm = PosSql.map(m);
                mods.add(Obj.of("modifierId", mm.get("modifierId"), "name", mm.get("name"),
                        "priceDelta", PosSql.jsString(mm.get("priceDelta"))));
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", it.get("id"));
            l.put("productId", it.get("productId"));
            l.put("menuItemId", it.get("menuItemId"));
            l.put("description", it.get("description"));
            l.put("quantity", PosSql.jsString(it.get("quantity")));
            l.put("unitPrice", PosSql.jsString(it.get("unitPrice")));
            l.put("total", JsFormat.str(total));
            l.put("taxId", it.get("taxId"));
            l.put("taxInclusive", it.get("taxInclusive") == null ? false : it.get("taxInclusive"));
            l.put("discountPercent", PosSql.jsString(it.get("discountPercent") == null ? 0 : it.get("discountPercent")));
            l.put("discountType", it.get("discountType"));
            l.put("discountAmount", PosSql.jsString(it.get("discountAmount") == null ? 0 : it.get("discountAmount")));
            l.put("discountReason", it.get("discountReason"));
            l.put("course", it.get("course"));
            l.put("kitchenPrintedQty", PosSql.js(it.get("kitchenPrintedQty")));
            l.put("kotPrintedQty", PosSql.js(it.get("kotPrintedQty")));
            l.put("billPrintedQty", PosSql.js(it.get("billPrintedQty")));
            l.put("kitchenStatus", it.get("kitchenStatus") == null ? "pending" : it.get("kitchenStatus"));
            l.put("punchedById", punchedById);
            l.put("punchedByName", punchedByName);
            l.put("note", userNote.isEmpty() ? null : userNote);
            l.put("variantId", it.get("variantId"));
            l.put("variantName", it.get("variantName"));
            l.put("variantPrice", it.get("variantId") != null ? variantPrice.get((String) it.get("variantId")) : null);
            l.put("accompanimentOptionIds", accIds);
            l.put("accompanimentNames", PosSql.strings(it.get("accompanimentNames")));
            l.put("accompanimentPriceImpact", PosSql.js(impact));
            l.put("modifiers", mods);
            lines.add(l);
        }
        view.put("lines", lines);
        return view;
    }

    private void linkTableOrder(String tableId, String orderId, Object guestCount) {
        db.insert(PosTableOrder.class, Obj.of("organizationId", org(), "tableId", tableId, "orderId", orderId,
                "guestCount", guestCount == null ? null : (int) num(guestCount), "openedAt", PosDb.raw(NOW)));
    }

    private void closeTableLink(String tableId, String orderId) {
        db.updateMany(PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)), "\"tableId\" = ? AND \"orderId\" = ?"
                + " AND \"closedAt\" IS NULL AND \"organizationId\" = ?", tableId, orderId, org());
    }

    /** Add a round to a table's tab (opens the order on the first round). */
    public Map<String, Object> addToTab(String tableId, Map<String, Object> input) {
        List<Map<String, Object>> rawLines = PosOrdersService.maps(input.get("lines"));
        if (rawLines.isEmpty()) {
            throw ApiException.badRequest("No items to add");
        }
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("lines", rawLines);
        for (String k : List.of("transactionDiscountPercent", "overrideById", "overridePin", "expectedTotal",
                "expectedVersion")) {
            authority.put(k, input.get(k));
        }
        PosOrdersService.Quote q = orders.quoteRaw(authority);
        pricing.assertAuthority(PosOrdersService.pricingLines(q.baseLines()), authority);
        List<Map<String, Object>> lines = toOrderLines(rawLines);
        Map<String, Object> existing = orders.getOpenOrderForTable(tableId);
        Map<String, Object> order;
        if (existing != null) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("lines", lines);
            dto.put("sendToKitchen", input.get("sendToKitchen"));
            dto.put("guestCount", input.get("guestCount"));
            dto.put("overrideById", input.get("overrideById"));
            if (input.containsKey("transactionDiscountPercent")) {
                dto.put("transactionDiscountPercent", input.get("transactionDiscountPercent"));
            }
            order = orders.addItems((String) existing.get("id"), dto);
        } else {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("orderType", "dine_in");
            dto.put("tableId", tableId);
            dto.put("cashSessionId", input.get("cashSessionId"));
            dto.put("partnerId", input.get("partnerId"));
            dto.put("guestCount", input.get("guestCount"));
            dto.put("overrideById", input.get("overrideById"));
            dto.put("lines", lines);
            order = orders.createOrder(dto);
            linkTableOrder(tableId, (String) order.get("id"), input.get("guestCount"));
            if (Boolean.TRUE.equals(input.get("sendToKitchen"))) {
                try {
                    orders.fireKitchen((String) order.get("id"));
                } catch (RuntimeException e) {
                    log.warn("fireKitchen failed: {}", e.getMessage());
                }
            } else {
                orders.autoSendRoutedLines((String) order.get("id"));
            }
        }
        return toTabView(order);
    }

    /** Auto-save: replace the table's open order with exactly these lines (empty = cancel + free the table). */
    public Map<String, Object> saveTabItems(String tableId, Map<String, Object> input) {
        Map<String, Object> existing = orders.getOpenOrderForTable(tableId);
        if (existing != null && splitInProgress((String) existing.get("id"))) {
            throw ApiException.badRequest("Split in progress — settle or cancel the split before changing this order.");
        }
        List<Map<String, Object>> lines = toOrderLines(PosOrdersService.maps(input.get("lines")));
        if (lines.isEmpty()) {
            if (existing != null) {
                if (input.get("expectedVersion") == null) {
                    throw ApiException.badRequest("An order version is required before clearing saved items");
                }
                String reason = input.get("cancelReason") != null ? (String) input.get("cancelReason") : "Order emptied";
                orders.cancelOrder((String) existing.get("id"), reason, input.get("expectedVersion"),
                        (String) input.get("overrideById"), (String) input.get("overridePin"));
                closeTableLink(tableId, (String) existing.get("id"));
            }
            return null;
        }
        Map<String, Object> order;
        if (existing != null) {
            Map<String, Object> dto = new LinkedHashMap<>(input);
            dto.put("lines", lines);
            order = orders.saveItems((String) existing.get("id"), dto);
        } else {
            if (!Boolean.TRUE.equals(input.get("newRound"))) {
                throw ApiException.conflict("This table has no open order to save into — it may have been settled or"
                        + " cancelled on another terminal. Reload the table before selling.");
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("orderType", "dine_in");
            dto.putAll(input);
            dto.put("tableId", tableId);
            dto.put("lines", lines);
            order = orders.createOrder(dto);
            linkTableOrder(tableId, (String) order.get("id"), input.get("guestCount"));
            orders.autoSendRoutedLines((String) order.get("id"));
        }
        return toTabView(order);
    }

    private boolean splitInProgress(String orderId) {
        return jdbc.sql("SELECT count(*) FROM \"SplitBill\" WHERE \"sourceOrderId\" = ? AND \"organizationId\" = ?"
                + " AND status::text <> 'void'").params(orderId, org()).query(Long.class).single() > 0;
    }

    public Map<String, Object> fireTabToKitchen(String tableId) {
        Map<String, Object> order = orders.getOpenOrderForTable(tableId);
        if (order == null) {
            throw ApiException.badRequest("No open order to send to the kitchen");
        }
        return orders.fireKitchen((String) order.get("id"));
    }

    /** Settle a table's open tab. */
    public Map<String, Object> settleTab(String tableId, Map<String, Object> inputIn) {
        return saleScope(() -> settleTabInScope(tableId, inputIn));
    }

    private Map<String, Object> settleTabInScope(String tableId, Map<String, Object> inputIn) {
        Map<String, Object> input = new LinkedHashMap<>(inputIn);
        String orgId = org();
        OccurredAt.resolve((String) input.get("occurredAt"));
        input.put("cashSessionId", requireCashSession(input));
        tx.executeWithoutResult(st -> jdbc.sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(tableId, orgId).query().listOfRows());
        String recovery = recoveryOrderId();
        Map<String, Object> order = recovery != null ? orders.getOrder(recovery) : orders.getOpenOrderForTable(tableId);
        if (order == null) {
            throw ApiException.badRequest("No open tab to settle on this table");
        }
        if (PosSql.list(order.get("items")).isEmpty()) {
            throw ApiException.badRequest("The tab is empty");
        }
        return settleResolvedOrder(order, input);
    }

    /** Settle any open order by id. */
    public Map<String, Object> settleOrder(String orderId, Map<String, Object> inputIn) {
        return saleScope(() -> settleOrderInScope(orderId, inputIn));
    }

    private Map<String, Object> settleOrderInScope(String orderId, Map<String, Object> inputIn) {
        Map<String, Object> input = new LinkedHashMap<>(inputIn);
        String orgId = org();
        OccurredAt.resolve((String) input.get("occurredAt"));
        input.put("cashSessionId", requireCashSession(input));
        tx.executeWithoutResult(st -> jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(orderId, orgId).query().listOfRows());
        Map<String, Object> order = orders.getOrder(orderId);
        if (order.get("invoiceId") != null && !orderId.equals(recoveryOrderId())) {
            throw ApiException.badRequest("This order is already billed; collect against its existing invoice");
        }
        if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
            throw ApiException.badRequest("Order is " + order.get("status"));
        }
        if (PosSql.list(order.get("items")).isEmpty()) {
            throw ApiException.badRequest("The order is empty");
        }
        return settleResolvedOrder(order, input);
    }

    /** Shared settle tail for an already-resolved open order. */
    private Map<String, Object> settleResolvedOrder(Map<String, Object> orderIn, Map<String, Object> input) {
        String orgId = org();
        Map<String, Object> order = new LinkedHashMap<>(orderIn);
        String orderId = (String) order.get("id");
        if (splitInProgress(orderId)) {
            throw ApiException.badRequest("This order has a split in progress — settle each split bill instead.");
        }
        if (order.get("cashSessionId") != null && input.get("cashSessionId") != null
                && !order.get("cashSessionId").equals(input.get("cashSessionId"))) {
            throw ApiException.badRequest("This order belongs to a different register session");
        }
        if (order.get("invoiceId") == null && input.get("expectedVersion") != null
                && PosSql.intOf(order.get("version")) != num(input.get("expectedVersion"))) {
            throw ApiException.badRequest("The saved order changed. Review it before paying.");
        }
        if (order.get("invoiceId") == null) {
            List<Map<String, Object>> priced = order.get("items") != null ? PosOrdersService.maps(order.get("items"))
                    : PosOrdersService.maps(orders.getOrder(orderId).get("items"));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> i : priced) {
                Map<String, Object> l = new LinkedHashMap<>(i);
                l.put("quantity", num(i.get("quantity")));
                l.put("unitPrice", num(i.get("unitPrice")));
                l.put("discountType", i.get("discountType") == null ? null : i.get("discountType").toString());
                lines.add(l);
            }
            pricing.assertAuthority(lines, input);
        }
        if (PosSql.truthy(input.get("overrideById"))) {
            overrides.verifyOperationApproval((String) input.get("overrideById"), (String) input.get("overridePin"),
                    "discount");
        }
        Object partnerId = input.get("partnerId");
        if (PosSql.truthy(partnerId) && !partnerId.equals(order.get("partnerId"))) {
            db.update(Order.class, Obj.of("partnerId", partnerId), "id = ? AND \"organizationId\" = ?", orderId, orgId);
            order.put("partnerId", partnerId);
        }
        boolean isCredit = "credit".equals(input.get("settleMode"));
        if (isCredit) {
            assertCreditCustomer((String) order.get("partnerId"));
        }
        if (order.get("invoiceId") == null) {
            billing.preflightOrderPayment(order, input);
        }
        Map<String, Object> invoice;
        try {
            invoice = billing.generateInvoice(orderId, invoiceDto(input));
        } catch (RuntimeException e) {
            log.error("[settle] invoice generation failed for order {}: {}", orderId, e.getMessage());
            throw e;
        }
        Map<String, Object> pay;
        try {
            pay = isCredit ? billing.settleCredit((String) invoice.get("id"), Obj.of("partnerId", order.get("partnerId")))
                    : billing.receivePayment((String) invoice.get("id"), paymentDto(input));
        } catch (RuntimeException e) {
            log.error("[settle] payment failed for invoice {} ({}): {}", invoice.get("invoiceNumber"), invoice.get("id"),
                    e.getMessage());
            throw e;
        }
        Object tableStatus = null;
        if (order.get("tableId") != null) {
            try {
                tableStatus = tables.closeTableOrder((String) order.get("tableId"), orderId).get("tableStatus");
            } catch (RuntimeException e) {
                log.warn("Close table failed: {}", e.getMessage());
            }
        }
        Map<String, Object> res = afterSettle(orgId, invoice, input, pay, isCredit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceId", invoice.get("id"));
        out.put("invoiceNumber", invoice.get("invoiceNumber"));
        out.put("orderId", orderId);
        out.put("orderNumber", order.get("orderNumber"));
        for (String k : List.of("paymentIds", "total", "change", "settleMode", "amountDue")) {
            out.put(k, res.get(k));
        }
        if (tableStatus != null) {
            out.put("tableStatus", tableStatus);
        }
        putReceipt(out, res);
        return out;
    }

    /** Credit needs a real, named customer within their limit. */
    private void assertCreditCustomer(String partnerId) {
        if (partnerId == null || partnerId.isEmpty()) {
            throw ApiException.badRequest("Select a customer to charge on account");
        }
        String orgId = org();
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT code FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(partnerId, orgId));
        if (partner == null) {
            throw ApiException.badRequest("Customer not found");
        }
        if ("WALKIN".equals(partner.get("code"))) {
            throw ApiException.badRequest("The walk-in customer cannot buy on account — select a named customer");
        }
        CreditStatus status = CreditStatus.resolve(jdbc, orgId, partnerId, false);
        if (status.creditHold()) {
            throw ApiException.badRequest("This customer is on credit hold — new credit sales are blocked");
        }
        if (status.creditLimit() > 0 && status.outstanding() >= status.creditLimit() - 0.01) {
            throw ApiException.badRequest("Credit limit reached: outstanding " + JsFormat.str(status.outstanding())
                    + " of limit " + JsFormat.str(status.creditLimit()));
        }
    }

    /** H1 / F17: an explicitly selected open register, owned by the caller unless the drawer is shared. */
    private String requireCashSession(Map<String, Object> input) {
        String orgId = org();
        String userId = user();
        Object id = input.get("cashSessionId");
        if (PosSql.truthy(id)) {
            Map<String, Object> session = SqlRows.one(jdbc.sql("SELECT cs.id, cs.status::text AS status, cs.\"userId\","
                    + " cr.\"isActive\", cr.\"deletedAt\", cr.id AS \"registerId\" FROM \"CashSession\" cs LEFT JOIN"
                    + " \"CashRegister\" cr ON cr.id = cs.\"cashRegisterId\" WHERE cs.id = ? AND cs.\"organizationId\" = ?")
                    .params(id, orgId));
            if (session == null || !"open".equals(session.get("status"))) {
                throw ApiException.badRequest("The original register session is not open. Resolve the pending sale"
                        + " before selecting another session.");
            }
            if (session.get("registerId") == null || !Boolean.TRUE.equals(session.get("isActive"))
                    || session.get("deletedAt") != null) {
                throw ApiException.badRequest("This register is no longer active. Close its session and select another register.");
            }
            if (userId == null || !userId.equals(session.get("userId"))) {
                if (!settings.sharedDrawer()) {
                    throw ApiException.badRequest("This register is open under another cashier. Complete a shift handover"
                            + " before selling on it, or enable the shared-drawer setting.");
                }
            }
            return (String) session.get("id");
        }
        throw ApiException.badRequest("Select an open register before completing any POS sale");
    }

    private static String resolvePaymentMode(List<Object> tenders) {
        Set<Object> methods = new LinkedHashSet<>();
        for (Object t : tenders) {
            methods.add(PosSql.map(t).get("method"));
        }
        if (methods.size() == 1) {
            Object m = methods.iterator().next();
            if ("cash".equals(m)) {
                return "cash";
            }
            if ("card".equals(m) || "bank".equals(m)) {
                return "card";
            }
            if ("mobile_money".equals(m)) {
                return "mobile_money";
            }
        }
        return "mixed";
    }
}
