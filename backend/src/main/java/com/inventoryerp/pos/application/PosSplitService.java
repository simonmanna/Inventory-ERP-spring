package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.pos.application.PosLines.Mod;
import com.inventoryerp.pos.application.PosLines.Resolved;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.pos.domain.SplitBill;
import com.inventoryerp.pos.domain.SplitBillItem;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Split bills on an open tab (port of {@code split/pos-split.service.ts}). */
@Service
public class PosSplitService {

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PosOrdersService orders;
    private final PosInvoiceService billing;
    private final PosReceiptsService receipts;
    private final DocumentBuilderService builder;
    private final TransactionTemplate tx;

    public PosSplitService(PosDb db, AuditService audit, PosOrdersService orders, PosInvoiceService billing,
            PosReceiptsService receipts, DocumentBuilderService builder, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.orders = orders;
        this.billing = billing;
        this.receipts = receipts;
        this.builder = builder;
        this.tx = new TransactionTemplate(txm);
    }

    /** {order, items} of the table's open tab (null order when none). */
    private Object[] loadOpenTab(String tableId) {
        Map<String, Object> link = SqlRows.one(jdbc.sql("SELECT \"orderId\" FROM \"PosTableOrder\" WHERE \"tableId\" = ?"
                + " AND \"closedAt\" IS NULL AND \"organizationId\" = ? ORDER BY \"openedAt\" DESC LIMIT 1").params(tableId, org()));
        Map<String, Object> o = link == null || link.get("orderId") == null ? null
                : db.first(Order.class, "id = ?", link.get("orderId"));
        boolean open = o != null && o.get("invoiceId") == null && TableStatus.isHeld(o.get("status"));
        if (!open) {
            return new Object[] {null, List.of()};
        }
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", o.get("id"));
        for (Map<String, Object> it : items) {
            it.put("modifiers", SqlRows.list(OrderItemModifier.class, jdbc.sql("SELECT * FROM \"OrderItemModifier\""
                    + " WHERE \"orderItemId\" = ? ORDER BY \"createdAt\", id").param(it.get("id"))));
        }
        o.put("items", items);
        return new Object[] {o, items};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> order(Object[] tab) {
        return (Map<String, Object>) tab[0];
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object[] tab) {
        return (List<Map<String, Object>>) tab[1];
    }

    private List<Map<String, Object>> bills(String sourceOrderId) {
        List<Map<String, Object>> bills = db.list(SplitBill.class, "\"sourceOrderId\" = ? AND \"organizationId\" = ?"
                + " AND status::text <> 'void' ORDER BY \"createdAt\" ASC", sourceOrderId, org());
        for (Map<String, Object> b : bills) {
            b.put("items", db.list(SplitBillItem.class, "\"splitBillId\" = ? ORDER BY \"createdAt\", id", b.get("id")));
        }
        return bills;
    }

    private Totals quoteSavedItems(List<Map<String, Object>> items, Map<String, Object> input) {
        return builder.prepareLines(PosLines.lineInputs(PricingPolicy.discountedLines(items, input)));
    }

    public Map<String, Object> getState(String tableId) {
        Object[] tab = loadOpenTab(tableId);
        if (order(tab) == null) {
            return Obj.of("tableId", tableId, "sourceOrderId", null, "lines", List.of(), "bills", List.of(),
                    "summary", emptySummary(), "splitActive", false);
        }
        return buildState(tableId, order(tab), items(tab), bills((String) order(tab).get("id")));
    }

    private <T> T withTableLock(String tableId, Supplier<T> fn) {
        return tx.execute(st -> {
            if (tableId != null) {
                jdbc.sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(tableId, org())
                        .query().listOfRows();
            }
            return fn.get();
        });
    }

    private Map<String, Object> reloadState(String tableId, String sourceOrderId) {
        Object[] tab = loadOpenTab(tableId);
        return buildState(tableId, order(tab), items(tab), bills(sourceOrderId));
    }

    public Map<String, Object> addBills(String tableId, double count) {
        String orgId = org();
        int n = (int) Math.max(1, Math.min(20, Math.floor(count)));
        return withTableLock(tableId, () -> {
            Object[] tab = loadOpenTab(tableId);
            if (order(tab) == null) {
                throw ApiException.badRequest("No open tab on this table to split");
            }
            if (items(tab).isEmpty()) {
                throw ApiException.badRequest("The tab is empty");
            }
            String orderId = (String) order(tab).get("id");
            long existing = db.count(SplitBill.class, "\"sourceOrderId\" = ? AND \"organizationId\" = ? AND status::text <> 'void'",
                    orderId, orgId);
            for (int i = 0; i < n; i++) {
                db.insert(SplitBill.class, Obj.of("organizationId", orgId, "tableId", tableId, "sourceOrderId", orderId,
                        "label", "Bill " + (existing + i + 1), "splitType", "item", "status", "open", "createdBy", user()));
            }
            audit.recordInTx(AuditInput.of("SplitBill", orderId, "create", null, Obj.of("tableId", tableId, "added", n)));
            return reloadState(tableId, orderId);
        });
    }

    /**
     * Atomically create N bills and distribute every source line equally.
     *
     * <p>Post-parity fix: Nest writes {@code splitType: 'equal'}, which is not a {@code SplitType} value
     * (item | even | percent), so Prisma rejects the insert and the route always answers 500. The bills are
     * written as {@code even} instead; everything else follows {@code pos-split.service.ts#splitEqually}.
     */
    public Map<String, Object> splitEqually(String tableId, double count) {
        String orgId = org();
        int n = (int) Math.max(2, Math.min(20, Math.floor(count)));
        return withTableLock(tableId, () -> {
            Object[] tab = loadOpenTab(tableId);
            if (order(tab) == null) {
                throw ApiException.badRequest("No open tab on this table to split");
            }
            if (items(tab).isEmpty()) {
                throw ApiException.badRequest("The tab is empty");
            }
            if (db.count(SplitBill.class, "\"sourceOrderId\" = ? AND \"organizationId\" = ? AND status::text <> 'void'",
                    order(tab).get("id"), orgId) > 0) {
                throw ApiException.conflict("A split is already in progress. Cancel it before splitting equally.");
            }
            String orderId = (String) order(tab).get("id");
            List<String> billIds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                billIds.add((String) db.insert(SplitBill.class, Obj.of("organizationId", orgId, "tableId", tableId,
                        "sourceOrderId", orderId, "label", "Bill " + (i + 1), "splitType", "even", "status", "open",
                        "createdBy", user())).get("id"));
            }
            for (Map<String, Object> item : items(tab)) {
                double total = num(item.get("quantity"));
                double allocated = 0;
                for (int i = 0; i < n; i++) {
                    double quantity = i == n - 1 ? fixed6(total - allocated) : fixed6(total / n);
                    allocated += quantity;
                    if (quantity > 0) {
                        upsertItem(billIds.get(i), (String) item.get("id"), quantity);
                    }
                }
            }
            for (String billId : billIds) {
                recomputeBill(billId);
            }
            audit.recordInTx(AuditInput.of("SplitBill", orderId, "create", null,
                    Obj.of("tableId", tableId, "count", n, "splitType", "even")));
            return reloadState(tableId, orderId);
        });
    }

    public Map<String, Object> assign(String billId, List<Map<String, Object>> items) {
        return mutateBill(billId, (bill, order, srcItems) -> {
            Map<String, Double> assigned = assignedByItem((String) order.get("id"));
            for (Map<String, Object> it : items) {
                Map<String, Object> src = srcItems.stream().filter(l -> l.get("id").equals(it.get("sourceItemId")))
                        .findFirst().orElse(null);
                if (src == null) {
                    throw ApiException.badRequest("Unknown source item " + it.get("sourceItemId"));
                }
                double qty = num(it.get("quantity"));
                if (!(qty > 0)) {
                    continue;
                }
                double pool = num(src.get("quantity")) - assigned.getOrDefault((String) src.get("id"), 0.0);
                if (qty > pool + 1e-6) {
                    throw ApiException.badRequest("Only " + JsFormat.str(pool) + " of \"" + src.get("description")
                            + "\" left to assign");
                }
                upsertItem((String) bill.get("id"), (String) src.get("id"), qty);
                assigned.merge((String) src.get("id"), qty, Double::sum);
            }
            audit.recordInTx(AuditInput.of("SplitBill", (String) bill.get("id"), "assign", null, Obj.of("items", items)));
        });
    }

    public Map<String, Object> unassign(String billId, List<Map<String, Object>> items) {
        return mutateBill(billId, (bill, order, srcItems) -> {
            for (Map<String, Object> it : items) {
                decrementItem((String) bill.get("id"), (String) it.get("sourceItemId"), num(it.get("quantity")));
            }
            audit.recordInTx(AuditInput.of("SplitBill", (String) bill.get("id"), "unassign", null, Obj.of("items", items)));
        });
    }

    private Map<String, Object> bill(String id) {
        return db.first(SplitBill.class, "id = ? AND \"organizationId\" = ?", id, org());
    }

    public Map<String, Object> move(String fromBillId, String toBillId, List<Map<String, Object>> items) {
        if (fromBillId.equals(toBillId)) {
            throw ApiException.badRequest("Source and target bills are the same");
        }
        Map<String, Object> from = bill(fromBillId);
        Map<String, Object> to = bill(toBillId);
        if (from == null || to == null) {
            throw ApiException.notFound("Bill not found");
        }
        if (!from.get("tableId").equals(to.get("tableId"))) {
            throw ApiException.badRequest("Bills belong to different tables");
        }
        return withTableLock((String) from.get("tableId"), () -> {
            assertOpen(bill(fromBillId));
            assertOpen(bill(toBillId));
            for (Map<String, Object> it : items) {
                double qty = num(it.get("quantity"));
                decrementItem(fromBillId, (String) it.get("sourceItemId"), qty);
                upsertItem(toBillId, (String) it.get("sourceItemId"), qty);
            }
            recomputeBill(fromBillId);
            recomputeBill(toBillId);
            audit.recordInTx(AuditInput.of("SplitBill", toBillId, "assign", null, Obj.of("from", fromBillId, "items", items)));
            return reloadState((String) from.get("tableId"), (String) from.get("sourceOrderId"));
        });
    }

    public Map<String, Object> merge(String targetBillId, String sourceBillId) {
        if (targetBillId.equals(sourceBillId)) {
            throw ApiException.badRequest("Cannot merge a bill into itself");
        }
        Map<String, Object> target = bill(targetBillId);
        Map<String, Object> source = bill(sourceBillId);
        if (target == null || source == null) {
            throw ApiException.notFound("Bill not found");
        }
        if (!target.get("tableId").equals(source.get("tableId"))) {
            throw ApiException.badRequest("Bills belong to different tables");
        }
        return withTableLock((String) target.get("tableId"), () -> {
            assertOpen(bill(targetBillId));
            assertOpen(bill(sourceBillId));
            for (Map<String, Object> it : db.list(SplitBillItem.class, "\"splitBillId\" = ?", sourceBillId)) {
                upsertItem(targetBillId, (String) it.get("sourceItemId"), num(it.get("quantity")));
            }
            jdbc.sql("DELETE FROM \"SplitBill\" WHERE id = ?").param(sourceBillId).update();
            recomputeBill(targetBillId);
            audit.recordInTx(AuditInput.of("SplitBill", targetBillId, "merge", null, Obj.of("sourceBillId", sourceBillId)));
            return reloadState((String) target.get("tableId"), (String) target.get("sourceOrderId"));
        });
    }

    public Map<String, Object> deleteBill(String billId) {
        Map<String, Object> b = bill(billId);
        if (b == null) {
            throw ApiException.notFound("Bill not found");
        }
        return withTableLock((String) b.get("tableId"), () -> {
            assertOpen(bill(billId));
            jdbc.sql("DELETE FROM \"SplitBill\" WHERE id = ?").param(billId).update();
            audit.recordInTx(AuditInput.of("SplitBill", billId, "delete", null, Obj.of("tableId", b.get("tableId"))));
            return reloadState((String) b.get("tableId"), (String) b.get("sourceOrderId"));
        });
    }

    public Map<String, Object> cancelSplit(String tableId) {
        return withTableLock(tableId, () -> {
            Object[] tab = loadOpenTab(tableId);
            if (order(tab) == null) {
                return buildState(tableId, null, List.of(), List.of());
            }
            String orderId = (String) order(tab).get("id");
            jdbc.sql("DELETE FROM \"SplitBill\" WHERE \"sourceOrderId\" = ? AND status = 'open' AND \"organizationId\" = ?")
                    .params(orderId, org()).update();
            audit.recordInTx(AuditInput.of("SplitBill", orderId, "cancel", null, Obj.of("kind", "cancel_split",
                    "tableId", tableId)));
            return reloadState(tableId, orderId);
        });
    }

    /** Settle one bill into its own invoice and take payment (idempotent per bill). */
    public Map<String, Object> settleBill(String billId, Map<String, Object> dto) {
        if (Boolean.TRUE.equals(dto.get("allowPartial"))) {
            throw ApiException.badRequest("Pay each split bill in full");
        }
        Object cashSessionId = dto.get("cashSessionId");
        if (!PosSql.truthy(cashSessionId)) {
            throw ApiException.badRequest("Select an open register before settling a split bill");
        }
        String orgId = org();
        Map<String, Object> result = tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(cashSessionId, orgId).query().listOfRows();
            String status = jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(cashSessionId, orgId).query(String.class).optional().orElse(null);
            if (!"open".equals(status)) {
                throw ApiException.badRequest("The selected register is closed");
            }
            Map<String, Object> header = bill(billId);
            if (header == null) {
                throw ApiException.notFound("Bill not found");
            }
            jdbc.sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(header.get("tableId"), orgId).query().listOfRows();
            jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(header.get("sourceOrderId"), orgId).query().listOfRows();
            Map<String, Object> bill = bill(billId);
            bill.put("items", db.list(SplitBillItem.class, "\"splitBillId\" = ? ORDER BY \"createdAt\", id", billId));
            if ("void".equals(bill.get("status"))) {
                throw ApiException.badRequest("Bill was cancelled");
            }
            if (bill.get("invoiceId") != null) {
                Map<String, Object> invoice = SqlRows.one(jdbc.sql("SELECT \"invoiceNumber\", \"settlementStatus\"::text AS s,"
                        + " \"amountTendered\", \"amountPaid\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(bill.get("invoiceId"), orgId));
                if (invoice == null || !"settled".equals(invoice.get("s"))) {
                    throw ApiException.conflict("Review this legacy split invoice before collecting more payment");
                }
                double paid = num(invoice.get("amountPaid"));
                double tendered = invoice.get("amountTendered") != null ? num(invoice.get("amountTendered")) : paid;
                return Obj.of("billId", billId, "invoiceId", bill.get("invoiceId"), "invoiceNumber", invoice.get("invoiceNumber"),
                        "settlementStatus", "settled", "change", PosSql.js(Math.max(0, tendered - paid)), "tableClosed", false,
                        "alreadySettled", true);
            }
            Object[] tab = loadOpenTab((String) bill.get("tableId"));
            Map<String, Object> order = order(tab);
            List<Map<String, Object>> items = items(tab);
            if (order == null || !order.get("id").equals(bill.get("sourceOrderId"))) {
                throw ApiException.conflict("The tab changed since this split started");
            }
            if (order.get("cashSessionId") != null && !order.get("cashSessionId").equals(cashSessionId)) {
                throw ApiException.conflict("The tab belongs to a different register session");
            }
            Map<String, Double> assigned = assignedByItem((String) order.get("id"));
            for (Map<String, Object> item : items) {
                if (assigned.getOrDefault((String) item.get("id"), 0.0) > num(item.get("quantity")) + 0.000001) {
                    throw ApiException.conflict("Split quantities exceed the current saved order");
                }
            }
            List<Map<String, Object>> lines = resolvedBillLines(bill, items, order);
            if (lines.isEmpty()) {
                throw ApiException.badRequest("This bill has no items");
            }
            double due = quoteSavedItems(lines, Map.of()).total().doubleValue();
            if (dto.get("expectedTotal") != null && Math.abs(due - num(dto.get("expectedTotal"))) > 0.000001) {
                throw ApiException.conflict("The split price changed. Refresh the bill before paying");
            }
            List<Resolved> resolved = new ArrayList<>();
            lines.forEach(l -> resolved.add(toResolved(l)));
            Map<String, Object> billOrder = orders.createOrderFromResolved("dine_in", (String) bill.get("tableId"),
                    "pos_split_bill", bill.get("partnerId") != null ? (String) bill.get("partnerId") : (String) order.get("partnerId"),
                    (String) cashSessionId, (String) order.get("branchId"), null, resolved);
            Map<String, Object> genDto = new LinkedHashMap<>();
            genDto.put("expectedTotal", due);
            genDto.put("cashSessionId", cashSessionId);
            if (dto.get("occurredAt") != null) {
                genDto.put("occurredAt", dto.get("occurredAt"));
            }
            Map<String, Object> invoice = billing.generateInvoice((String) billOrder.get("id"), genDto, true);
            Map<String, Object> pay = billing.receivePayment((String) invoice.get("id"), dto, true);
            db.update(SplitBill.class, Obj.of("status", "settled", "invoiceId", invoice.get("id"), "settledAt", PosDb.raw(NOW),
                    "subtotal", invoice.get("subtotal"), "totalAmount", invoice.get("totalAmount"),
                    "amountPaid", invoice.get("totalAmount")), "id = ?", billId);
            audit.recordInTx(AuditInput.of("SplitBill", billId, "post", null, Obj.of("invoiceId", invoice.get("id"))));
            long open = db.count(SplitBill.class, "\"sourceOrderId\" = ? AND \"organizationId\" = ? AND status = 'open'",
                    order.get("id"), orgId);
            boolean tableClosed = open == 0 && items.stream().allMatch(item -> Math.abs(num(item.get("quantity"))
                    - assigned.getOrDefault((String) item.get("id"), 0.0)) < 0.000001);
            if (tableClosed) {
                db.update(Order.class, Obj.of("status", "closed", "closedAt", PosDb.raw(NOW), "version", PosDb.raw("version + 1")),
                        "id = ?", order.get("id"));
                db.updateMany(com.inventoryerp.pos.domain.PosTableOrder.class, Obj.of("closedAt", PosDb.raw(NOW)),
                        "\"orderId\" = ? AND \"closedAt\" IS NULL AND \"organizationId\" = ?", order.get("id"), orgId);
                TableStatus.recompute(jdbc, orgId, (String) bill.get("tableId"));
            }
            Map<String, Object> outcome = Obj.of("billId", billId, "invoiceId", invoice.get("id"),
                    "invoiceNumber", invoice.get("invoiceNumber"), "settlementStatus", pay.get("settlementStatus"),
                    "change", pay.get("change"), "tableClosed", tableClosed);
            BusinessOutcome.record(jdbc, outcome, true);
            return outcome;
        });
        billing.afterExternalPaymentCommit((String) result.get("invoiceId"));
        Map<String, Object> out = new LinkedHashMap<>(result);
        try {
            out.put("receiptHtml", receipts.buildHtmlReceipt((String) result.get("invoiceId")));
        } catch (RuntimeException ignored) {
            // committed already
        }
        return out;
    }

    private static Resolved toResolved(Map<String, Object> l) {
        List<Mod> mods = new ArrayList<>();
        for (Object o : PosSql.list(l.get("modifiers"))) {
            Map<String, Object> m = PosSql.map(o);
            mods.add(new Mod((String) m.get("modifierId"), (String) m.get("name"), (String) m.get("kitchenPrintName"),
                    num(m.get("priceDelta"))));
        }
        return new Resolved((String) l.get("productId"), (String) l.get("menuItemId"), (String) l.get("comboId"),
                (String) l.get("description"), num(l.get("quantity")), num(l.get("unitPrice")), (String) l.get("taxId"),
                l.get("discountPercent") == null ? 0 : num(l.get("discountPercent")), (String) l.get("discountType"),
                l.get("discountAmount") == null ? null : num(l.get("discountAmount")), (String) l.get("discountReason"),
                (String) l.get("note"), (Boolean) l.get("taxInclusive"), mods, (String) l.get("variantId"),
                (String) l.get("variantName"), PosSql.strings(l.get("accompanimentNames")),
                PosSql.strings(l.get("accompanimentOptionIds")), l.get("course") == null ? null : num(l.get("course")), false,
                true);
    }

    private List<Map<String, Object>> resolvedBillLines(Map<String, Object> bill, List<Map<String, Object>> items,
            Map<String, Object> order) {
        List<Map<String, Object>> in = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> l = new LinkedHashMap<>(item);
            l.put("quantity", num(item.get("quantity")));
            l.put("unitPrice", num(item.get("unitPrice")));
            l.put("discountPercent", num(item.get("discountPercent")));
            l.put("discountAmount", num(item.get("discountAmount")));
            in.add(l);
        }
        List<Map<String, Object>> all = PricingPolicy.discountedLines(in, order);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : PosSql.list(bill.get("items"))) {
            Map<String, Object> a = PosSql.map(o);
            Map<String, Object> src = all.stream().filter(i -> i.get("id").equals(a.get("sourceItemId"))).findFirst().orElse(null);
            double quantity = num(a.get("quantity"));
            if (src == null || !Double.isFinite(quantity) || quantity <= 0 || quantity > num(src.get("quantity"))) {
                throw ApiException.conflict("Invalid split quantity or missing source item");
            }
            Map<String, Object> l = new LinkedHashMap<>(src);
            l.put("quantity", quantity);
            l.put("discountAmount", Money.div(PosSql.dec(src.get("discountAmount")).multiply(PosSql.decOf(quantity)),
                    PosSql.dec(src.get("quantity"))).setScale(6, RoundingMode.HALF_UP).doubleValue());
            l.put("discountReason", src.get("discountReason") != null ? src.get("discountReason") : order.get("discountReason"));
            List<Map<String, Object>> mods = new ArrayList<>();
            for (Object m : PosSql.list(src.get("modifiers"))) {
                Map<String, Object> mm = new LinkedHashMap<>(PosSql.map(m));
                mm.put("priceDelta", num(mm.get("priceDelta")));
                mods.add(mm);
            }
            l.put("modifiers", mods);
            out.add(l);
        }
        return out;
    }

    interface BillFn {
        void run(Map<String, Object> bill, Map<String, Object> order, List<Map<String, Object>> items);
    }

    private Map<String, Object> mutateBill(String billId, BillFn fn) {
        Map<String, Object> b = bill(billId);
        if (b == null) {
            throw ApiException.notFound("Bill not found");
        }
        return withTableLock((String) b.get("tableId"), () -> {
            Map<String, Object> locked = bill(billId);
            assertOpen(locked);
            Object[] tab = loadOpenTab((String) b.get("tableId"));
            if (order(tab) == null || !order(tab).get("id").equals(b.get("sourceOrderId"))) {
                throw ApiException.conflict("The tab changed — cancel the split and retry");
            }
            fn.run(locked, order(tab), items(tab));
            recomputeBill(billId);
            return reloadState((String) b.get("tableId"), (String) b.get("sourceOrderId"));
        });
    }

    private static void assertOpen(Map<String, Object> bill) {
        if (bill == null) {
            throw ApiException.notFound("Bill not found");
        }
        if ("settled".equals(bill.get("status"))) {
            throw ApiException.conflict("That bill is already paid and cannot be changed");
        }
        if ("void".equals(bill.get("status"))) {
            throw ApiException.conflict("That bill was cancelled");
        }
    }

    /** JS {@code Number(x.toFixed(6))}. */
    private static double fixed6(double x) {
        return BigDecimal.valueOf(x).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private void upsertItem(String billId, String sourceItemId, double qty) {
        if (!(qty > 0)) {
            return;
        }
        Map<String, Object> existing = db.first(SplitBillItem.class, "\"splitBillId\" = ? AND \"sourceItemId\" = ?", billId,
                sourceItemId);
        if (existing != null) {
            jdbc.sql("UPDATE \"SplitBillItem\" SET quantity = ? WHERE id = ?")
                    .params(SqlRows.dec(existing, "quantity").add(PosSql.decOf(qty)), existing.get("id")).update();
        } else {
            db.insert(SplitBillItem.class, Obj.of("organizationId", org(), "splitBillId", billId, "sourceItemId", sourceItemId,
                    "quantity", PosSql.decOf(qty)));
        }
    }

    private void decrementItem(String billId, String sourceItemId, double qty) {
        if (!(qty > 0)) {
            return;
        }
        Map<String, Object> existing = db.first(SplitBillItem.class, "\"splitBillId\" = ? AND \"sourceItemId\" = ?", billId,
                sourceItemId);
        if (existing == null) {
            throw ApiException.badRequest("That item is not on this bill");
        }
        double next = num(existing.get("quantity")) - qty;
        if (next < -1e-6) {
            throw ApiException.badRequest("Bill only has " + JsFormat.str(num(existing.get("quantity"))) + " of that item");
        }
        if (next <= 1e-6) {
            jdbc.sql("DELETE FROM \"SplitBillItem\" WHERE id = ?").param(existing.get("id")).update();
        } else {
            jdbc.sql("UPDATE \"SplitBillItem\" SET quantity = ? WHERE id = ?").params(PosSql.decOf(next), existing.get("id"))
                    .update();
        }
    }

    private void recomputeBill(String billId) {
        Map<String, Object> bill = bill(billId);
        if (bill == null) {
            return;
        }
        bill.put("items", db.list(SplitBillItem.class, "\"splitBillId\" = ? ORDER BY \"createdAt\", id", billId));
        Object[] tab = loadOpenTab((String) bill.get("tableId"));
        Map<String, Object> order = order(tab) == null ? Map.of() : order(tab);
        Totals totals = quoteSavedItems(resolvedBillLines(bill, items(tab), order), Map.of());
        db.update(SplitBill.class, Obj.of("subtotal", totals.subtotal(), "totalAmount", totals.total()), "id = ?", billId);
    }

    private Map<String, Double> assignedByItem(String sourceOrderId) {
        Map<String, Double> map = new HashMap<>();
        for (Map<String, Object> b : bills(sourceOrderId)) {
            for (Object o : PosSql.list(b.get("items"))) {
                Map<String, Object> it = PosSql.map(o);
                map.merge((String) it.get("sourceItemId"), num(it.get("quantity")), Double::sum);
            }
        }
        return map;
    }

    private Map<String, Object> buildState(String tableId, Map<String, Object> order, List<Map<String, Object>> items,
            List<Map<String, Object>> bills) {
        Totals priced = quoteSavedItems(items, order == null ? Map.of() : order);
        Map<String, Double> lineTotals = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            lineTotals.put((String) items.get(i).get("id"), priced.prepared().get(i).total().doubleValue());
        }
        Map<String, Double> assigned = new HashMap<>();
        for (Map<String, Object> b : bills) {
            for (Object o : PosSql.list(b.get("items"))) {
                Map<String, Object> it = PosSql.map(o);
                assigned.merge((String) it.get("sourceItemId"), num(it.get("quantity")), Double::sum);
            }
        }
        List<Map<String, Object>> outLines = new ArrayList<>();
        double tableTotal = 0;
        double unassignedTotal = 0;
        boolean fully = !items.isEmpty();
        for (Map<String, Object> l : items) {
            double a = assigned.getOrDefault((String) l.get("id"), 0.0);
            double qty = num(l.get("quantity"));
            double lineTotal = lineTotals.getOrDefault((String) l.get("id"), 0.0);
            double un = Math.max(0, qty - a);
            List<Object> mods = new ArrayList<>();
            PosSql.list(l.get("modifiers")).forEach(m -> mods.add(PosSql.map(m).get("name")));
            outLines.add(Obj.of("id", l.get("id"), "description", l.get("description"), "quantity", PosSql.js(qty),
                    "unitPrice", PosSql.js(l.get("unitPrice")), "discountPercent", PosSql.js(l.get("discountPercent")),
                    "lineTotal", PosSql.js(lineTotal), "assignedQty", PosSql.js(a), "unassignedQty", PosSql.js(un),
                    "modifiers", mods));
            tableTotal += lineTotal;
            unassignedTotal += (un / (qty == 0 ? 1 : qty)) * lineTotal;
            if (!(un < 1e-6)) {
                fully = false;
            }
        }
        List<Map<String, Object>> outBills = new ArrayList<>();
        double assignedTotal = 0;
        double paidTotal = 0;
        int openBills = 0;
        for (Map<String, Object> b : bills) {
            List<Map<String, Object>> bi = new ArrayList<>();
            for (Object o : PosSql.list(b.get("items"))) {
                Map<String, Object> it = PosSql.map(o);
                Map<String, Object> src = items.stream().filter(l -> l.get("id").equals(it.get("sourceItemId"))).findFirst()
                        .orElse(null);
                double qty = num(it.get("quantity"));
                double srcQty = src == null ? 0 : num(src.get("quantity"));
                double ratio = src != null && srcQty != 0 ? qty / srcQty : 0;
                bi.add(Obj.of("sourceItemId", it.get("sourceItemId"), "description", src == null ? "(removed)"
                        : src.get("description"), "quantity", PosSql.js(qty), "lineTotal",
                        PosSql.js(src == null ? 0 : lineTotals.getOrDefault((String) src.get("id"), 0.0) * ratio)));
            }
            double total = num(b.get("totalAmount"));
            assignedTotal += total;
            if ("settled".equals(b.get("status"))) {
                paidTotal += total;
            }
            if ("open".equals(b.get("status"))) {
                openBills++;
            }
            outBills.add(Obj.of("id", b.get("id"), "label", b.get("label"), "status", b.get("status"),
                    "splitType", b.get("splitType"), "partnerId", b.get("partnerId"), "invoiceId", b.get("invoiceId"),
                    "totalAmount", PosSql.js(total), "amountPaid", PosSql.js(b.get("amountPaid")), "items", bi));
        }
        return Obj.of("tableId", tableId, "sourceOrderId", order == null ? null : order.get("id"), "lines", outLines,
                "bills", outBills, "summary", Obj.of("tableTotal", PosSql.js(tableTotal), "assignedTotal", PosSql.js(assignedTotal),
                        "unassignedTotal", PosSql.js(Math.max(0, unassignedTotal)), "paidTotal", PosSql.js(paidTotal),
                        "outstandingTotal", PosSql.js(Math.max(0, tableTotal - paidTotal)), "fullyAssigned", fully,
                        "openBills", openBills),
                "splitActive", !bills.isEmpty());
    }

    private static Map<String, Object> emptySummary() {
        return Obj.of("tableTotal", 0, "assignedTotal", 0, "unassignedTotal", 0, "paidTotal", 0, "outstandingTotal", 0,
                "fullyAssigned", false, "openBills", 0);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
