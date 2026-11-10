package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.documents.domain.DocumentPrintLog;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.OrderItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Print counters, copy numbers, KOT deltas and the print log (port of {@code pos-print-lifecycle.service.ts}). */
@Service
public class PosPrintLifecycleService {

    /** A KOT delta entry: the OrderItem row (with {@code modifiers}) and the quantity change. */
    public record Delta(Map<String, Object> line, double delta) {}

    /** {@code getKitchenDeltas} result. */
    public record KitchenDelta(List<Delta> addLines, List<Delta> removeLines, List<Delta> unchangedLines) {}

    private final PosDb db;
    private final JdbcClient jdbc;
    public PosPrintLifecycleService(PosDb db) {
        this.db = db;
        this.jdbc = db.jdbc();
    }

    /** {@code "Invoice"}, {@code "Order"} or {@code "Document"} for a print-record id. */
    String target(String recordId) {
        String orgId = org();
        // An existing Invoice / Order id keeps its table for the request; "not found" is never memoized.
        String found = ReadMemo.get(() -> exists("Invoice", recordId, orgId) ? "Invoice"
                : exists("Order", recordId, orgId) ? "Order" : null, "printTarget", orgId, recordId);
        return found == null ? "Document" : found;
    }

    private boolean exists(String table, String id, String orgId) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, orgId).query(Long.class).single() > 0;
    }

    private void bump(String table, String id, String sets, Object... params) {
        List<Object> p = new ArrayList<>(List.of(params));
        p.add(id);
        p.add(org());
        int n = jdbc.sql("UPDATE \"" + table + "\" SET " + sets + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?").params(p).update();
        if (n == 0) {
            throw new IllegalStateException("No record was found for an update.");
        }
    }

    public void markBillPrinted(String recordId, String userId) {
        bump(target(recordId), recordId, "\"billPrintCount\" = \"billPrintCount\" + 1, \"billLastPrintedAt\" = " + NOW
                + ", \"lastPrintedById\" = ?", userId);
    }

    public void markLinesBilled(String recordId, String userId) {
        String t = target(recordId);
        if ("Document".equals(t)) {
            jdbc.sql("UPDATE \"DocumentLine\" SET \"billPrintedAt\" = " + NOW + " WHERE \"documentId\" = ?"
                    + " AND \"billPrintedAt\" IS NULL AND \"organizationId\" = ?").params(recordId, org()).update();
            return;
        }
        if (!"Order".equals(t)) {
            return;
        }
        jdbc.sql("UPDATE \"OrderItem\" SET \"billPrintedQty\" = quantity, \"billLastPrintedAt\" = " + NOW
                + ", \"lastBillPrintedById\" = ?, \"updatedAt\" = " + NOW + " WHERE \"orderId\" = ? AND cancelled = false"
                + " AND \"organizationId\" = ?").params(userId, recordId, org()).update();
    }

    private int counter(String table, String id, String column) {
        Integer v = jdbc.sql("SELECT \"" + column + "\" FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Integer.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public int getBillCopyNumber(String recordId) {
        return counter(target(recordId), recordId, "billPrintCount") + 1;
    }

    public void markReceiptPrinted(String recordId, String userId) {
        String t = target(recordId);
        if ("Order".equals(t)) {
            return;
        }
        bump(t, recordId, "\"receiptPrintCount\" = \"receiptPrintCount\" + 1, \"receiptLastPrintedAt\" = " + NOW
                + ", \"lastPrintedById\" = ?", userId);
    }

    /**
     * Mark the <em>original</em> receipt printed only if no receipt was printed yet; {@code false} when another request
     * already printed it. Post-parity hardening (2026-11-10): Nest read the counter and bumped it in two steps, so two
     * simultaneous "print receipt" taps both printed an unmarked original. The conditional update is atomic.
     */
    public boolean claimOriginalReceipt(String recordId, String userId) {
        String t = target(recordId);
        if (!"Invoice".equals(t)) {
            markReceiptPrinted(recordId, userId);
            return true;
        }
        return jdbc.sql("UPDATE \"Invoice\" SET \"receiptPrintCount\" = \"receiptPrintCount\" + 1, \"receiptLastPrintedAt\" = "
                        + NOW + ", \"lastPrintedById\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"receiptPrintCount\" = 0")
                .params(userId, recordId, org()).update() > 0;
    }

    public int getReceiptCopyNumber(String recordId) {
        String t = target(recordId);
        if ("Order".equals(t)) {
            return 1;
        }
        return counter(t, recordId, "receiptPrintCount") + 1;
    }

    public int getKotCopyNumber(String recordId) {
        return counter(target(recordId), recordId, "kotPrintCount") + 1;
    }

    public void markKotPrinted(String recordId, String userId) {
        bump(target(recordId), recordId, "\"kotPrintCount\" = \"kotPrintCount\" + 1, \"lastPrintedById\" = ?", userId);
    }

    /** One {@code DocumentPrintLog} row; the id lands in documentId / invoiceId by its model. */
    public void recordPrintLog(String organizationId, String documentId, String type, String printedById, String action,
            Integer copies, String reason, String printer, String idempotencyKey, String documentLineId) {
        String t = target(documentId);
        db.insert(DocumentPrintLog.class, Obj.of("organizationId", organizationId,
                "documentId", "Document".equals(t) ? documentId : null,
                "invoiceId", "Invoice".equals(t) ? documentId : null,
                "documentLineId", documentLineId, "type", type, "action", action == null ? "PRINT" : action,
                "copies", copies == null ? 1 : copies, "printedById", printedById, "reason", reason, "printer", printer,
                "idempotencyKey", idempotencyKey));
    }

    public void recordPrintLog(String organizationId, String documentId, String type, String printedById) {
        recordPrintLog(organizationId, documentId, type, printedById, null, null, null, null, null, null);
    }

    /** Paper-KOT deltas: current quantities vs {@code kotPrintedQty}. Lines carry their {@code modifiers}. */
    public KitchenDelta getKitchenDeltas(String orderId) {
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " AND \"organizationId\" = ? ORDER BY \"lineNumber\" ASC", orderId, org());
        List<Delta> add = new ArrayList<>();
        List<Delta> remove = new ArrayList<>();
        List<Delta> same = new ArrayList<>();
        PosModifiers.attach(jdbc, items, com.inventoryerp.pos.domain.OrderItemModifier.class, "OrderItemModifier",
                "orderItemId");
        for (Map<String, Object> ln : items) {
            double current = PosSql.num(ln.get("quantity"));
            double printed = PosSql.num(ln.get("kotPrintedQty"));
            if (current > printed + 0.000001) {
                add.add(new Delta(ln, current - printed));
            } else if (current < printed - 0.000001) {
                remove.add(new Delta(ln, printed - current));
            } else {
                same.add(new Delta(ln, 0));
            }
        }
        return new KitchenDelta(add, remove, same);
    }

    /** These lines' current quantities are now on a paper KOT. */
    public void markKitchenPrinted(List<String> itemIds, Map<String, Double> qtyMap, String userId) {
        if (itemIds.isEmpty()) {
            return;
        }
        for (String id : itemIds) {
            jdbc.sql("UPDATE \"OrderItem\" SET \"kitchenPrintCount\" = \"kitchenPrintCount\" + 1, \"kitchenLastPrintedAt\" = "
                    + NOW + ", \"lastKitchenPrintedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(userId, id, org()).update();
        }
        for (String id : itemIds) {
            Double qty = qtyMap.get(id);
            if (qty != null) {
                jdbc.sql("UPDATE \"OrderItem\" SET \"kotPrintedQty\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?").params(PosSql.decOf(qty), id, org()).update();
            }
        }
    }

    public void markCancelPrinted(List<String> itemIds, String userId) {
        for (String id : itemIds) {
            jdbc.sql("UPDATE \"OrderItem\" SET \"cancelPrintCount\" = \"cancelPrintCount\" + 1, \"cancelLastPrintedAt\" = "
                    + NOW + ", \"lastKitchenPrintedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(userId, id, org()).update();
        }
    }

    /**
     * Nest parity: {@code settings.get()} returns the Setting ROW, which is never a string, so this always answers
     * {@code null} (the real printer target is resolved by the receipts service).
     */
    public Map<String, Object> getPrinterSettings() {
        return null;
    }
}
