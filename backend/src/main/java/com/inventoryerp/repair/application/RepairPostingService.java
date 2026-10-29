package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.modules.ModuleRegistry;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosOrdersService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.repair.domain.RepairLabourType;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairQuotation;
import com.inventoryerp.repair.domain.RepairQuotationLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The financial spine of a repair order (port of {@code repair-posting.service.ts}). Billing rides the standard
 * Order → Invoice spine: one Order ({@code transactionKind: repair}, {@code repairOrderId} set) plus one Invoice
 * through {@link PosInvoiceService}. Labour lines bill service products; part lines carry {@code repairOrderLineId},
 * so the POS stock posting skips them (the parts service already relieved stock at issue time).
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairPostingService {

    private final RepairDb db;
    private final SequenceService seq;
    private final PosInvoiceService posInvoice;
    private final PosOrdersService posOrders;
    private final ModuleRegistry modules;

    public RepairPostingService(RepairDb db, SequenceService seq, PosInvoiceService posInvoice,
            PosOrdersService posOrders, ModuleRegistry modules) {
        this.db = db;
        this.seq = seq;
        this.posInvoice = posInvoice;
        this.posOrders = posOrders;
        this.modules = modules;
    }

    /** One billable line after product resolution (exact decimals). */
    record Resolved(String productId, Object description, BigDecimal quantity, BigDecimal unitPrice, Object taxId,
            Object repairFeeType) {}

    /**
     * Bill a repair order from its approved quotation (or the given {@code lines}). Returns
     * {@code {orderId, invoiceId, order, invoice}}.
     *
     * <p>Validation runs first, exactly as in Nest. The Order, its Invoice (with the balanced sales journal posted by
     * {@link PosInvoiceService}) and the repair order's link and totals then commit in one transaction under a row
     * lock on the repair order: a failed invoice no longer leaves the repair order marked as billed, and two
     * concurrent bills cannot both pass the "already billed" check.
     */
    public Map<String, Object> billRepair(String repairOrderId, Map<String, Object> inputIn) {
        Map<String, Object> input = RepairDb.body(inputIn);
        String orgId = RepairDb.org();
        Map<String, Object> order = db.byId(RepairOrder.class, repairOrderId);
        assertBillable(order);

        List<Map<String, Object>> lines = new ArrayList<>();
        if (input.get("lines") != null) {
            for (Object o : PosSql.list(input.get("lines"))) {
                lines.add(o instanceof Map<?, ?> ? PosSql.map(o) : Map.of());
            }
        } else {
            Map<String, Object> quotation = PosSql.truthy(order.get("quotationId"))
                    ? db.where(RepairQuotation.class).eq("id", order.get("quotationId")).eq("status", "approved")
                            .first()
                    : null;
            if (quotation == null) {
                quotation = db.where(RepairQuotation.class).eq("repairOrderId", repairOrderId).eq("status", "approved")
                        .first("\"createdAt\" DESC");
                if (quotation == null) {
                    throw ApiException.badRequest("No approved quotation to bill — approve one first");
                }
            }
            for (Map<String, Object> l : db.many(RepairQuotationLine.class, "quotationId", quotation.get("id"),
                    "\"lineNumber\" ASC")) {
                lines.add(Obj.of("kind", l.get("kind"), "description", l.get("description"),
                        "productId", l.get("productId"), "labourTypeId", l.get("labourTypeId"),
                        "quantity", l.get("quantity"), "unitPrice", l.get("unitPrice"), "taxId", l.get("taxId")));
            }
        }

        List<Resolved> resolved = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Object productId = l.get("productId");
            if (!PosSql.truthy(productId) && "labour".equals(l.get("kind"))) {
                Map<String, Object> lt = PosSql.truthy(l.get("labourTypeId"))
                        ? db.byId(RepairLabourType.class, l.get("labourTypeId"))
                        : l.get("description") == null ? db.where(RepairLabourType.class).first()
                                : db.where(RepairLabourType.class).and("lower(name) = lower(?)",
                                        l.get("description").toString()).first();
                productId = lt == null || lt.get("productId") == null ? "" : lt.get("productId");
            }
            if (!PosSql.truthy(productId)) {
                throw ApiException.badRequest("Line \"" + RepairDb.s(l.get("description"))
                        + "\" has no billable product — labour types need a billing product; parts need a productId");
            }
            BigDecimal qty = RepairDb.jsNum(nz(l.get("quantity"), 1));
            if (!RepairDb.positive(qty)) {
                throw ApiException.badRequest("Line \"" + RepairDb.s(l.get("description"))
                        + "\": quantity must be positive");
            }
            BigDecimal unitPrice = RepairDb.finite(RepairDb.jsNum(nz(l.get("unitPrice"), 0)), "unitPrice");
            resolved.add(new Resolved(productId.toString(), l.get("description"), qty, unitPrice, l.get("taxId"),
                    l.get("kind")));
        }
        if (resolved.isEmpty()) {
            throw ApiException.badRequest("No billable lines");
        }

        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal parts = BigDecimal.ZERO;
        BigDecimal all = BigDecimal.ZERO;
        for (Resolved l : resolved) {
            BigDecimal v = l.unitPrice().multiply(l.quantity());
            if ("labour".equals(l.repairFeeType())) {
                labour = labour.add(v);
            }
            if ("part".equals(l.repairFeeType())) {
                parts = parts.add(v);
            }
            all = all.add(v);
        }
        BigDecimal labourTotal = labour;
        BigDecimal partsTotal = parts;
        BigDecimal linesTotal = all;

        Map<String, Object> billed = db.inTx(() -> {
            Map<String, Object> locked = db.lockById(RepairOrder.class, repairOrderId);
            assertBillable(locked);
            String orderNumber = seq.next("pos_order", "OR-", 6);
            // Invoice.partnerId is required: walk-in repairs fall back to the org's WALKIN partner (POS convention).
            String partnerId = locked.get("partnerId") != null ? (String) locked.get("partnerId")
                    : posOrders.ensureWalkInCustomer(orgId);
            modules.assertKnownOrderKind("repair");
            Map<String, Object> o = db.db().insert(Order.class, Obj.of("organizationId", orgId,
                    "orderNumber", orderNumber, "orderType", "takeaway", "status", "confirmed", "partnerId", partnerId,
                    "branchId", nz(input.get("branchId"), locked.get("branchId")),
                    "notes", "Repair " + locked.get("repairNumber"), "transactionKind", "repair",
                    "sourceDocumentType", "repair_order", "sourceDocumentId", locked.get("id"),
                    "repairOrderId", locked.get("id")));
            for (int idx = 0; idx < resolved.size(); idx++) {
                Resolved l = resolved.get(idx);
                db.db().insert(OrderItem.class, Obj.of("organizationId", orgId, "orderId", o.get("id"),
                        "productId", l.productId(), "description", l.description(),
                        "quantity", l.quantity(), "unitPrice", l.unitPrice(),
                        "discountPercent", BigDecimal.ZERO, "discountType", "percentage",
                        "discountAmount", BigDecimal.ZERO, "taxId", l.taxId(), "taxInclusive", false,
                        "lineNumber", idx, "repairOrderLineId", locked.get("id"), "repairFeeType", l.repairFeeType()));
            }
            db.update(RepairOrder.class, repairOrderId, Obj.of("orderId", o.get("id"), "updatedBy", RepairDb.user()));

            Map<String, Object> invDto = new LinkedHashMap<>();
            invDto.put("paymentMode", nz(input.get("paymentMode"), "cash"));
            if (input.get("occurredAt") != null) {
                invDto.put("occurredAt", input.get("occurredAt"));
            }
            Map<String, Object> invoice = posInvoice.generateInvoice((String) o.get("id"), invDto);

            // Nest reads invoice.grandTotal ?? invoice.total — neither exists on Invoice — so totalAmount is the
            // pre-tax line total (kept on purpose, docs/spec/repair.md).
            BigDecimal tax = RepairDb.jsNumOrZero(nz(invoice.get("taxAmount"), 0));
            BigDecimal total = RepairDb.jsNumOrZero(nz(nz(invoice.get("grandTotal"), invoice.get("total")), 0));
            db.update(RepairOrder.class, repairOrderId, Obj.of("invoiceId", invoice.get("id"),
                    "labourTotal", labourTotal, "partsTotal", partsTotal, "taxTotal", tax,
                    "totalAmount", total.signum() != 0 ? total : linesTotal, "updatedBy", RepairDb.user()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", o.get("id"));
            out.put("invoiceId", invoice.get("id"));
            out.put("order", o);
            out.put("invoice", invoice);
            return out;
        });

        Map<String, Object> invoice = PosSql.map(billed.get("invoice"));
        db.notify(orgId, "Repair billed — " + nz(invoice.get("invoiceNumber"), invoice.get("id")),
                order.get("repairNumber") + " invoiced (" + resolved.size() + " lines)",
                Obj.of("repairOrderId", order.get("id"), "invoiceId", invoice.get("id"),
                        "repairNumber", order.get("repairNumber")));
        return billed;
    }

    /** The order exists, is not closed / cancelled and has no Order / Invoice yet (Nest's three guards). */
    private static void assertBillable(Map<String, Object> order) {
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
            throw ApiException.badRequest("Repair order is closed/cancelled — cannot bill");
        }
        if (PosSql.truthy(order.get("orderId")) || PosSql.truthy(order.get("invoiceId"))) {
            throw ApiException.badRequest("Repair order already billed");
        }
    }

    /** Record payment tenders against the repair invoice (the POS settlement flow). */
    public Map<String, Object> receivePayment(String invoiceId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> pay = new LinkedHashMap<>();
        if (dto.get("tenders") != null) {
            List<Object> tenders = new ArrayList<>();
            for (Object o : PosSql.list(dto.get("tenders"))) {
                Map<String, Object> t = o instanceof Map<?, ?> ? PosSql.map(o) : Map.of();
                Map<String, Object> m = new LinkedHashMap<>();
                if (t.get("method") != null) {
                    m.put("method", t.get("method"));
                }
                if (t.get("amount") != null) {
                    m.put("amount", t.get("amount"));
                }
                if (t.get("reference") != null) {
                    m.put("reference", t.get("reference"));
                }
                tenders.add(m);
            }
            pay.put("tenders", tenders);
        }
        for (String k : List.of("paymentMethod", "amountTendered", "cashSessionId")) {
            if (dto.get(k) != null) {
                pay.put(k, dto.get(k));
            }
        }
        return posInvoice.receivePayment(invoiceId, pay);
    }
}
