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

    /** One billable line after product resolution. */
    record Resolved(String productId, Object description, double quantity, double unitPrice, Object taxId,
            Object repairFeeType) {}

    /**
     * Bill a repair order from its approved quotation (or the given {@code lines}). Returns
     * {@code {orderId, invoiceId, order, invoice}}.
     */
    public Map<String, Object> billRepair(String repairOrderId, Map<String, Object> inputIn) {
        Map<String, Object> input = RepairDb.body(inputIn);
        String orgId = RepairDb.org();
        Map<String, Object> order = db.byId(RepairOrder.class, repairOrderId);
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        if ("cancelled".equals(order.get("status")) || "closed".equals(order.get("status"))) {
            throw ApiException.badRequest("Repair order is closed/cancelled — cannot bill");
        }
        if (PosSql.truthy(order.get("orderId")) || PosSql.truthy(order.get("invoiceId"))) {
            throw ApiException.badRequest("Repair order already billed");
        }

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
                        "quantity", PosSql.num(l.get("quantity")), "unitPrice", PosSql.num(l.get("unitPrice")),
                        "taxId", l.get("taxId")));
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
                throw ApiException.badRequest("Line \"" + RepairDb.s(l.get("description")) + "\" has no billable product"
                        + " — labour types need a billing product; parts need a productId");
            }
            double qty = PosSql.num(nz(l.get("quantity"), 1));
            if (!(qty > 0)) {
                throw ApiException.badRequest("Line \"" + RepairDb.s(l.get("description"))
                        + "\": quantity must be positive");
            }
            resolved.add(new Resolved(productId.toString(), l.get("description"), qty,
                    PosSql.num(nz(l.get("unitPrice"), 0)), l.get("taxId"), l.get("kind")));
        }
        if (resolved.isEmpty()) {
            throw ApiException.badRequest("No billable lines");
        }

        String orderNumber = seq.next("pos_order", "OR-", 6);
        String partnerId = order.get("partnerId") != null ? (String) order.get("partnerId")
                : posOrders.ensureWalkInCustomer(orgId);
        Map<String, Object> createdOrder = db.inTx(() -> {
            modules.assertKnownOrderKind("repair");
            Map<String, Object> o = db.db().insert(Order.class, Obj.of("organizationId", orgId,
                    "orderNumber", orderNumber, "orderType", "takeaway", "status", "confirmed", "partnerId", partnerId,
                    "branchId", nz(input.get("branchId"), order.get("branchId")),
                    "notes", "Repair " + order.get("repairNumber"), "transactionKind", "repair",
                    "sourceDocumentType", "repair_order", "sourceDocumentId", order.get("id"),
                    "repairOrderId", order.get("id")));
            for (int idx = 0; idx < resolved.size(); idx++) {
                Resolved l = resolved.get(idx);
                db.db().insert(OrderItem.class, Obj.of("organizationId", orgId, "orderId", o.get("id"),
                        "productId", l.productId(), "description", l.description(),
                        "quantity", PosSql.decOf(l.quantity()), "unitPrice", PosSql.decOf(l.unitPrice()),
                        "discountPercent", 0, "discountType", "percentage", "discountAmount", 0, "taxId", l.taxId(),
                        "taxInclusive", false, "lineNumber", idx, "repairOrderLineId", order.get("id"),
                        "repairFeeType", l.repairFeeType()));
            }
            db.update(RepairOrder.class, (String) order.get("id"), Obj.of("orderId", o.get("id"),
                    "updatedBy", RepairDb.user()));
            return o;
        });

        Map<String, Object> invDto = new LinkedHashMap<>();
        invDto.put("paymentMode", nz(input.get("paymentMode"), "cash"));
        if (input.get("occurredAt") != null) {
            invDto.put("occurredAt", input.get("occurredAt"));
        }
        Map<String, Object> invoice = posInvoice.generateInvoice((String) createdOrder.get("id"), invDto);

        double labour = 0;
        double parts = 0;
        double all = 0;
        for (Resolved l : resolved) {
            double v = l.unitPrice() * l.quantity();
            if ("labour".equals(l.repairFeeType())) {
                labour += v;
            }
            if ("part".equals(l.repairFeeType())) {
                parts += v;
            }
            all += v;
        }
        double tax = RepairDiagnosisService.orZero(PosSql.num(nz(invoice.get("taxAmount"), 0)));
        double total = RepairDiagnosisService.orZero(PosSql.num(nz(nz(invoice.get("grandTotal"), invoice.get("total")), 0)));
        db.update(RepairOrder.class, (String) order.get("id"), Obj.of("invoiceId", invoice.get("id"),
                "labourTotal", PosSql.decOf(labour), "partsTotal", PosSql.decOf(parts), "taxTotal", PosSql.decOf(tax),
                "totalAmount", PosSql.decOf(total != 0 ? total : all), "updatedBy", RepairDb.user()));

        db.notify(orgId, "Repair billed — " + nz(invoice.get("invoiceNumber"), invoice.get("id")),
                order.get("repairNumber") + " invoiced (" + resolved.size() + " lines)",
                Obj.of("repairOrderId", order.get("id"), "invoiceId", invoice.get("id"),
                        "repairNumber", order.get("repairNumber")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", createdOrder.get("id"));
        out.put("invoiceId", invoice.get("id"));
        out.put("order", createdOrder);
        out.put("invoice", invoice);
        return out;
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
