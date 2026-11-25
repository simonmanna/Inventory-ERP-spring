package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.invoicing.application.DocumentFxRates;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Supplier claims (ADR-PROCUREMENT-001 wave 5): shortages, damage, quality rejects, price differences and late delivery
 * raised against a supplier, optionally tied to an order, receipt or bill. Lifecycle {@code open → submitted →
 * accepted / rejected → settled}; {@code open} claims can be cancelled.
 *
 * <p><b>Settlement posts a supplier credit</b> (R11, docs/spec/procurement-postings.md §7; key
 * {@code supplier_claim:<claimId>}, journal PURCH, source {@code supplier_claim}) under a <b>classification</b> chosen
 * when settling (owner decision: a claim without quantities never credits stock or GRNI). The amount is the agreed
 * amount, net of tax, in the claimed document's currency; the debit is always the supplier's payable (partner AP
 * account, else {@code accounts_payable}, tagged with the partner) unless noted:
 * <ul>
 *   <li>{@code price_variance} — Cr per claimed line, pro rata to its value (the bill's lines, else the receipt's,
 *       else the order's): a stock line credits {@code purchase_price_variance}, a non-stock line the expense it was
 *       bought to (line account → category expense → {@code default_expense}); no document: PPV. Plus Cr
 *       {@code tax_receivable} for the input VAT those lines recovered (bill line VAT share / receipt voucher rate).</li>
 *   <li>{@code expense_correction} — the same over the document's <em>non-stock</em> lines only (refused when it has
 *       only stock lines); no document: {@code default_expense}.</li>
 *   <li>{@code vat_adjustment} — over-charged VAT on a posted bill: Dr AP / Cr {@code tax_receivable} for the amount,
 *       which must not exceed the bill's input VAT less VAT already reversed by earlier claims on that bill.</li>
 *   <li>{@code stock_adjustment} — only with quantity lines on goods-receipt lines, each verified by a second user,
 *       valued at the receipt line's unit cost. A line whose goods leave stock is issued through the stock engine
 *       (move {@code return_to_supplier}, the debit-note return-to-vendor path), so quantity, cost layers and the GL
 *       move together: Cr the product's stock account for the value the engine issued, the difference to the receipt
 *       cost to PPV. A line already written off credits {@code stock_adjustment_expense} (reverses the loss). The
 *       supplier side is AP (plus the VAT recovered on it) for a vouchered / billed receipt, and {@code grni_accrued}
 *       for the open (unbilled) quantity of an ad-hoc receipt — which the claim consumes so a later bill cannot
 *       clear it again.</li>
 * </ul>
 * A claim against a posted bill lowers that bill's open amount by the gross credit under a row lock (never below
 * zero; the excess stays on AP as a supplier credit balance) and sets its payment status like a credit-note allocation
 * on a customer invoice (invoicing {@code CreditNoteService}: {@code paid} at a zero residual, else {@code partial}).
 */
@Service
public class SupplierClaimService {

    /** Raise body. */
    public record CreateInput(String partnerId, String type, BigDecimal amount, String description,
            String purchaseOrderId, String goodsReceiptId, String vendorBillId) {}

    /** A quantity line: received quantity of one goods-receipt line the claim covers. */
    public record QuantityLineInput(String goodsReceiptLineId, String productId, BigDecimal quantity,
            String stockAction, String notes) {}

    /** Settlement classifications (V519 check constraint). */
    public static final List<String> CLASSIFICATIONS = List.of("price_variance", "expense_correction",
            "vat_adjustment", "stock_adjustment");

    /** Claim types that may carry verified quantities and settle as a stock adjustment. */
    private static final List<String> QUANTITY_TYPES = List.of("shortage", "damage", "quality");

    /** Claim types an over-charged-VAT adjustment applies to. */
    private static final List<String> VAT_TYPES = List.of("price", "other");

    /** Claim statuses whose quantity lines may still change or be verified. */
    private static final List<String> LINE_STATUSES = List.of("open", "submitted", "accepted");

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final EventBus events;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final TransactionTemplate tx;
    private final com.inventoryerp.kernel.approvals.SegregationOfDutiesService sod;
    private final DocumentFxRates fx;

    public SupplierClaimService(JdbcClient jdbc, SequenceService seq, AuditService audit, EventBus events,
            PostingService posting, AccountDeterminationService determination, StockService stock,
            StockPostingService stockPosting, PlatformTransactionManager txm,
            com.inventoryerp.kernel.approvals.SegregationOfDutiesService sod, DocumentFxRates fx) {
        this.jdbc = jdbc;
        this.sod = sod;
        this.fx = fx;
        this.seq = seq;
        this.audit = audit;
        this.events = events;
        this.posting = posting;
        this.determination = determination;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.tx = new TransactionTemplate(txm);
    }

    /** Raise a claim. Linked documents must belong to the same supplier. */
    public Map<String, Object> create(CreateInput in) {
        String orgId = org();
        if (in.amount() == null || in.amount().signum() < 0) {
            throw ApiException.badRequest("Claim amount must not be negative");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.partnerId(), orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        assertLinked("PurchaseOrder", in.purchaseOrderId(), in.partnerId(), "Purchase order");
        if (in.goodsReceiptId() != null && !in.goodsReceiptId().isBlank()
                && jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" g LEFT JOIN \"PurchaseOrder\" o ON o.id ="
                        + " g.\"purchaseOrderId\" WHERE g.id = ? AND g.\"organizationId\" = ? AND COALESCE(g.\"partnerId\","
                        + " o.\"partnerId\") = ?").params(in.goodsReceiptId(), orgId, in.partnerId()).query(Long.class)
                        .single() == 0) {
            throw ApiException.notFound("Goods receipt not found for this supplier");
        }
        if (in.vendorBillId() != null && !in.vendorBillId().isBlank()
                && jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"documentType\""
                        + " = 'vendor_bill' AND \"partnerId\" = ?").params(in.vendorBillId(), orgId, in.partnerId())
                        .query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier bill not found for this supplier");
        }
        String id = UUID.randomUUID().toString();
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        tx.executeWithoutResult(st -> {
            String number = seq.next("supplier_claim:" + year, "CLM-" + year + "-", 5);
            jdbc.sql("INSERT INTO \"SupplierClaim\" (id, \"organizationId\", \"claimNumber\", \"partnerId\","
                            + " \"purchaseOrderId\", \"goodsReceiptId\", \"vendorBillId\", type, amount, description,"
                            + " \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW
                            + ", " + NOW + ")")
                    .params(id, orgId, number, in.partnerId(), blank(in.purchaseOrderId()), blank(in.goodsReceiptId()),
                            blank(in.vendorBillId()), in.type(), in.amount(), in.description().trim(), user())
                    .update();
            audit.recordInTx(AuditInput.of("SupplierClaim", id, "create", null, Obj.of("claimNumber", number, "type",
                    in.type(), "amount", JsNumbers.dec(in.amount()))));
        });
        events.publish("supplier_claim.raised", Obj.of("organizationId", orgId, "claimId", id, "partnerId",
                in.partnerId(), "amount", JsNumbers.dec(in.amount())));
        return get(id);
    }

    /** Send the claim to the supplier. */
    public Map<String, Object> submit(String id) {
        return transition(id, List.of("open"), "submitted", ", \"submittedAt\" = " + NOW, null, null);
    }

    /**
     * Supplier accepted (optionally for a different amount). Gap fix h03-F7: the accepted amount is capped by what the
     * claimed document leaves open to claims, and whoever raised the claim cannot record its acceptance once the
     * {@code supplier_claim_settlement} separation-of-duties pair is on.
     */
    public Map<String, Object> accept(String id, BigDecimal agreedAmount, String response) {
        if (agreedAmount != null && agreedAmount.signum() < 0) {
            throw ApiException.badRequest("Agreed amount must not be negative");
        }
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierClaim\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c != null) {
            sod.assertSeparated(com.inventoryerp.kernel.approvals.SegregationOfDutiesService.SUPPLIER_CLAIM_SETTLEMENT,
                    "SupplierClaim", id, java.util.Arrays.asList((String) c.get("createdBy")));
            assertWithinDocument(c, agreedAmount != null ? agreedAmount : Money.dec(c.get("amount")));
        }
        return transition(id, List.of("open", "submitted"), "accepted", ", \"decidedAt\" = " + NOW
                + ", amount = COALESCE(?, amount), \"supplierResponse\" = ?", agreedAmount, response);
    }

    /**
     * Gap fix h03-F7: a claim credits at most the claimed document's net value (bill lines; receipt quantities at the
     * order price, or the receipt cost without an order; order lines) less what other accepted or settled claims on
     * that document already took. A claim that names no document has nothing to cap against.
     */
    private void assertWithinDocument(Map<String, Object> c, BigDecimal amount) {
        String orgId = org();
        BigDecimal value;
        String column;
        Object documentId;
        if (c.get("vendorBillId") != null) {
            value = sum(billBasis((String) c.get("vendorBillId")));
            column = "vendorBillId";
            documentId = c.get("vendorBillId");
        } else if (c.get("goodsReceiptId") != null) {
            // The order price converted to the receipt line's unit (a line received in pieces of a box order).
            value = Money.dec(jdbc.sql("SELECT COALESCE(SUM(gl.quantity * CASE WHEN pl.id IS NULL THEN gl.\"unitCost\""
                    + " WHEN COALESCE(pl.\"uomFactor\", 0) > 0 AND COALESCE(gl.\"uomFactor\", 0) > 0 THEN"
                    + " pl.\"unitPrice\" * gl.\"uomFactor\" / pl.\"uomFactor\" ELSE pl.\"unitPrice\" END), 0) FROM"
                    + " \"GoodsReceiptLine\" gl LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = gl.\"purchaseOrderLineId\""
                    + " WHERE gl.\"goodsReceiptId\" = ? AND gl.\"organizationId\" = ?")
                    .params(c.get("goodsReceiptId"), orgId).query(BigDecimal.class).single());
            column = "goodsReceiptId";
            documentId = c.get("goodsReceiptId");
        } else if (c.get("purchaseOrderId") != null) {
            value = sum(orderBasis((String) c.get("purchaseOrderId")));
            column = "purchaseOrderId";
            documentId = c.get("purchaseOrderId");
        } else {
            return;
        }
        BigDecimal taken = Money.dec(jdbc.sql("SELECT COALESCE(SUM(CASE WHEN status = 'settled' THEN"
                        + " COALESCE(\"settledAmount\", amount) ELSE amount END), 0) FROM \"SupplierClaim\" WHERE \"organizationId\" = ? AND \"" + column
                        + "\" = ? AND id <> ? AND status IN ('accepted', 'settled')")
                .params(orgId, documentId, c.get("id")).query(BigDecimal.class).single());
        BigDecimal open = Money.round(value.subtract(taken).max(Money.ZERO));
        if (Money.round(amount).compareTo(open) > 0) {
            throw ApiException.badRequest("Claim " + c.get("claimNumber") + " for " + JsNumbers.dec(Money.round(amount))
                    + " exceeds what the claimed document leaves open to claims (" + JsNumbers.dec(open) + " of "
                    + JsNumbers.dec(Money.round(value)) + "); agree a lower amount");
        }
    }

    private static BigDecimal sum(List<Basis> basis) {
        BigDecimal total = Money.ZERO;
        for (Basis b : basis) {
            total = total.add(b.weight());
        }
        return total;
    }

    /** Supplier rejected the claim. */
    public Map<String, Object> reject(String id, String response) {
        if (response == null || response.isBlank()) {
            throw ApiException.badRequest("Record the supplier's reason for rejecting the claim");
        }
        return transition(id, List.of("open", "submitted"), "rejected", ", \"decidedAt\" = " + NOW
                + ", amount = COALESCE(?, amount), \"supplierResponse\" = ?", null, response.trim());
    }

    /** Withdraw an open claim. */
    public Map<String, Object> cancel(String id) {
        return transition(id, List.of("open"), "cancelled", "", null, null);
    }

    // ── quantity lines (stock adjustment) ──────────────────────────────────────────────────────────────────────────

    /**
     * Record a received quantity the claim covers: a posted, unreversed goods-receipt line of the claim's supplier (of
     * the claim's receipt / order when it names one; all lines of a claim come from one receipt, which the claim then
     * links), a stock product, at most the received quantity less what other live claims already cover. Valued at the
     * receipt line's unit cost. The line counts for a settlement only once a second user verified it.
     */
    public Map<String, Object> addQuantityLine(String claimId, QuantityLineInput in) {
        String orgId = org();
        tx.executeWithoutResult(st -> {
            Map<String, Object> c = lockForLines(claimId);
            if (!QUANTITY_TYPES.contains(String.valueOf(c.get("type")))) {
                throw ApiException.badRequest("Only shortage, damage or quality claims carry verified quantities (this"
                        + " is a " + c.get("type") + " claim)");
            }
            BigDecimal qty = in.quantity();
            if (qty == null || qty.signum() <= 0) {
                throw ApiException.badRequest("Quantity must be positive");
            }
            String action = in.stockAction() == null || in.stockAction().isBlank() ? "issue" : in.stockAction();
            Map<String, Object> gl = SqlRows.one(jdbc.sql("SELECT gl.id, gl.\"goodsReceiptId\", gl.\"productId\","
                    + " gl.\"variantId\", gl.quantity, gl.\"unitCost\", gl.description, g.\"receiptNumber\","
                    + " g.status::text AS status, g.\"reversedAt\", g.\"purchaseOrderId\", COALESCE(g.\"partnerId\","
                    + " o.\"partnerId\") AS \"partnerId\", COALESCE(p.\"trackInventory\", false) AND"
                    + " COALESCE(p.\"productType\"::text, '') <> 'service' AS stocked FROM \"GoodsReceiptLine\" gl JOIN"
                    + " \"GoodsReceiptNote\" g ON g.id = gl.\"goodsReceiptId\" LEFT JOIN \"PurchaseOrder\" o ON o.id ="
                    + " g.\"purchaseOrderId\" LEFT JOIN \"Product\" p ON p.id = gl.\"productId\" WHERE gl.id = ? AND"
                    + " gl.\"organizationId\" = ? FOR UPDATE OF gl").params(in.goodsReceiptLineId(), orgId));
            if (gl == null || !String.valueOf(c.get("partnerId")).equals(gl.get("partnerId"))) {
                throw ApiException.notFound("Goods receipt line not found for this supplier");
            }
            if (!"posted".equals(gl.get("status")) || gl.get("reversedAt") != null) {
                throw ApiException.badRequest("Goods receipt " + gl.get("receiptNumber") + " is not a posted receipt"
                        + " (status: " + gl.get("status") + ")");
            }
            if (c.get("goodsReceiptId") != null && !c.get("goodsReceiptId").equals(gl.get("goodsReceiptId"))) {
                throw ApiException.badRequest("The claim is on goods receipt " + c.get("receiptNumber") + "; its quantity"
                        + " lines must come from that receipt");
            }
            if (c.get("purchaseOrderId") != null && !c.get("purchaseOrderId").equals(gl.get("purchaseOrderId"))) {
                throw ApiException.badRequest("Goods receipt " + gl.get("receiptNumber") + " is not a receipt of the"
                        + " claim's purchase order");
            }
            if (in.productId() != null && !in.productId().isBlank() && !in.productId().equals(gl.get("productId"))) {
                throw ApiException.badRequest("The product does not match the goods receipt line");
            }
            if (gl.get("productId") == null || !Boolean.TRUE.equals(gl.get("stocked"))) {
                throw ApiException.badRequest("Only received stock lines can carry claim quantities");
            }
            BigDecimal unitCost = Money.dec(gl.get("unitCost"));
            if (unitCost.signum() <= 0) {
                throw ApiException.badRequest("Goods receipt line " + gl.get("description") + " has no unit cost — a"
                        + " stock adjustment cannot value it");
            }
            BigDecimal received = Money.dec(gl.get("quantity"));
            BigDecimal left = received.subtract(claimedQuantity((String) gl.get("id")));
            if (qty.compareTo(left) > 0) {
                throw ApiException.badRequest("Only " + JsNumbers.dec(left.max(BigDecimal.ZERO)) + " of "
                        + JsNumbers.dec(received) + " received on " + gl.get("receiptNumber") + " are still unclaimed for "
                        + gl.get("description") + "; requested " + JsNumbers.dec(qty));
            }
            String lineId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"SupplierClaimLine\" (id, \"organizationId\", \"claimId\", \"goodsReceiptId\","
                            + " \"goodsReceiptLineId\", \"productId\", \"variantId\", quantity, \"unitCost\", \"stockAction\","
                            + " notes, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + NOW + ", " + NOW + ")")
                    .params(lineId, orgId, claimId, gl.get("goodsReceiptId"), gl.get("id"), gl.get("productId"),
                            gl.get("variantId"), qty, unitCost, action, blank(in.notes()), user())
                    .update();
            if (c.get("goodsReceiptId") == null) {
                jdbc.sql("UPDATE \"SupplierClaim\" SET \"goodsReceiptId\" = ?, version = version + 1, \"updatedAt\" = "
                        + NOW + " WHERE id = ?").params(gl.get("goodsReceiptId"), claimId).update();
            }
            audit.recordInTx(AuditInput.of("SupplierClaimLine", lineId, "create", null, Obj.of("claimId", claimId,
                    "goodsReceiptLineId", gl.get("id"), "quantity", JsNumbers.dec(qty), "unitCost",
                    JsNumbers.dec(unitCost), "stockAction", action)));
        });
        return get(claimId);
    }

    /** Remove a quantity line of an unsettled claim (verified or not). */
    public Map<String, Object> removeQuantityLine(String claimId, String lineId) {
        tx.executeWithoutResult(st -> {
            lockForLines(claimId);
            int n = jdbc.sql("DELETE FROM \"SupplierClaimLine\" WHERE id = ? AND \"claimId\" = ? AND \"organizationId\" = ?")
                    .params(lineId, claimId, org()).update();
            if (n == 0) {
                throw ApiException.notFound("Quantity line not found");
            }
            audit.recordInTx(AuditInput.of("SupplierClaimLine", lineId, "delete", null, Obj.of("claimId", claimId)));
        });
        return get(claimId);
    }

    /**
     * Verify a quantity line: a second user — not the one who raised the claim nor the one who recorded the line —
     * confirms the quantity; {@code verifiedBy} / {@code verifiedAt} are recorded. Verifying twice is a no-op.
     */
    public Map<String, Object> verifyQuantityLine(String claimId, String lineId) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> c = lockForLines(claimId);
            Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierClaimLine\" WHERE id = ? AND"
                    + " \"claimId\" = ? AND \"organizationId\" = ? FOR UPDATE").params(lineId, claimId, org()));
            if (line == null) {
                throw ApiException.notFound("Quantity line not found");
            }
            if (line.get("verifiedAt") != null) {
                return;
            }
            String me = user();
            if (me == null || me.equals(c.get("createdBy")) || me.equals(line.get("createdBy"))) {
                throw ApiException.forbidden("Claim quantities must be verified by a second user — not the person who"
                        + " raised the claim or recorded the quantity");
            }
            jdbc.sql("UPDATE \"SupplierClaimLine\" SET \"verifiedBy\" = ?, \"verifiedAt\" = " + NOW + ", \"updatedAt\" = "
                    + NOW + " WHERE id = ?").params(me, lineId).update();
            audit.recordInTx(AuditInput.of("SupplierClaimLine", lineId, "update", Obj.of("verifiedBy", null),
                    Obj.of("verifiedBy", me)));
        });
        return get(claimId);
    }

    /**
     * Goods-receipt lines a quantity line may reference: stock lines of the claim's receipt, else of its order's
     * posted receipts, else of the supplier's latest posted receipts, with the quantity still unclaimed.
     */
    public List<Map<String, Object>> receiptLines(String claimId) {
        Map<String, Object> c = get(claimId);
        String scope;
        Object param;
        if (c.get("goodsReceiptId") != null) {
            scope = "g.id = ?";
            param = c.get("goodsReceiptId");
        } else if (c.get("purchaseOrderId") != null) {
            scope = "g.\"purchaseOrderId\" = ?";
            param = c.get("purchaseOrderId");
        } else {
            scope = "COALESCE(g.\"partnerId\", o.\"partnerId\") = ?";
            param = c.get("partnerId");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT gl.id AS \"goodsReceiptLineId\", g.id AS"
                + " \"goodsReceiptId\", g.\"receiptNumber\", g.\"receivedAt\", gl.\"productId\", p.name AS \"productName\","
                + " gl.description, gl.quantity, gl.\"unitCost\", gl.quantity - COALESCE((SELECT sum(l.quantity) FROM"
                + " \"SupplierClaimLine\" l JOIN \"SupplierClaim\" sc ON sc.id = l.\"claimId\" WHERE"
                + " l.\"goodsReceiptLineId\" = gl.id AND " + LIVE_CLAIM.replace("c.", "sc.") + "), 0) AS claimable"
                + " FROM \"GoodsReceiptLine\" gl JOIN \"GoodsReceiptNote\" g ON g.id = gl.\"goodsReceiptId\" LEFT JOIN"
                + " \"PurchaseOrder\" o ON o.id = g.\"purchaseOrderId\" JOIN \"Product\" p ON p.id = gl.\"productId\""
                + " WHERE gl.\"organizationId\" = ? AND g.status::text = 'posted' AND g.\"reversedAt\" IS NULL AND"
                + " COALESCE(p.\"trackInventory\", false) AND COALESCE(p.\"productType\"::text, '') <> 'service' AND "
                + scope + " ORDER BY g.\"receivedAt\" DESC NULLS LAST, g.\"receiptNumber\" DESC, gl.\"lineNumber\""
                + " LIMIT 200").params(org(), param))) {
            r.put("quantity", JsNumbers.dec(Money.dec(r.get("quantity"))));
            r.put("unitCost", JsNumbers.dec(Money.dec(r.get("unitCost"))));
            r.put("claimable", JsNumbers.dec(Money.dec(r.get("claimable")).max(BigDecimal.ZERO)));
            out.add(r);
        }
        return out;
    }

    private Map<String, Object> lockForLines(String claimId) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT c.*, g.\"receiptNumber\" FROM \"SupplierClaim\" c LEFT JOIN"
                + " \"GoodsReceiptNote\" g ON g.id = c.\"goodsReceiptId\" WHERE c.id = ? AND c.\"organizationId\" = ?"
                + " FOR UPDATE OF c").params(claimId, org()));
        if (c == null) {
            throw ApiException.notFound("Claim not found");
        }
        if (!LINE_STATUSES.contains(String.valueOf(c.get("status")))) {
            throw ApiException.badRequest("Quantity lines can only change on an open, sent or accepted claim (status: "
                    + c.get("status") + ")");
        }
        return c;
    }

    /** Quantity of a receipt line covered by quantity lines of live claims (see {@link #LIVE_CLAIM}). */
    private BigDecimal claimedQuantity(String goodsReceiptLineId) {
        return jdbc.sql("SELECT COALESCE(sum(l.quantity), 0) FROM \"SupplierClaimLine\" l JOIN \"SupplierClaim\" c ON"
                + " c.id = l.\"claimId\" WHERE l.\"goodsReceiptLineId\" = ? AND l.\"organizationId\" = ? AND " + LIVE_CLAIM)
                .params(goodsReceiptLineId, org()).query(BigDecimal.class).single();
    }

    /**
     * A claim whose quantity lines still cover receipt quantity: not rejected / cancelled, and — once settled — only
     * when it was settled as a stock adjustment (lines of a claim settled otherwise moved nothing).
     */
    private static final String LIVE_CLAIM = "c.status NOT IN ('rejected', 'cancelled') AND (c.status <> 'settled' OR"
            + " c.classification = 'stock_adjustment')";

    // ── settlement ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Settle with the classification derived from the claim (see {@link #settle(String, String)}). */
    public Map<String, Object> settle(String id) {
        return settle(id, null);
    }

    /**
     * Settle an accepted claim under {@code classification} (null: derived — quantity lines → stock adjustment; price
     * claim → price variance; a document without stock lines → expense correction; otherwise price variance), post the
     * supplier credit (see the class comment) and, when the claim names a posted bill, lower that bill's open amount.
     * One transaction with the claim row (and the bill row) locked. Idempotent: a settled claim returns as is, and the
     * posting key {@code supplier_claim:<id>} posts at most once.
     */
    public Map<String, Object> settle(String id, String classification) {
        String orgId = org();
        if (classification != null && !CLASSIFICATIONS.contains(classification)) {
            throw ApiException.badRequest("classification must be one of " + String.join(", ", CLASSIFICATIONS));
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierClaim\" WHERE id = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE").params(id, orgId));
            if (c == null) {
                throw ApiException.notFound("Claim not found");
            }
            if ("settled".equals(c.get("status"))) {
                return;
            }
            if (!"accepted".equals(c.get("status"))) {
                throw ApiException.badRequest("Only an accepted claim can be settled (status: " + c.get("status") + ")");
            }
            BigDecimal amount = Money.round(Money.dec(c.get("amount")));
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("Nothing to settle — the agreed amount is 0");
            }
            // Gap fix h03-F7: maker-checker (when the pair is on), and the cap again under the document's lock so two
            // claims on one document cannot both take its last open value.
            sod.assertSeparated(com.inventoryerp.kernel.approvals.SegregationOfDutiesService.SUPPLIER_CLAIM_SETTLEMENT,
                    "SupplierClaim", id, java.util.Arrays.asList((String) c.get("createdBy")));
            Map<String, Object> bill = lockBill(c);
            if (bill == null && c.get("goodsReceiptId") != null) {
                jdbc.sql("SELECT id FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(c.get("goodsReceiptId"), orgId).query().listOfRows();
            } else if (bill == null && c.get("purchaseOrderId") != null) {
                jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(c.get("purchaseOrderId"), orgId).query().listOfRows();
            }
            assertWithinDocument(c, amount);
            Doc doc = document(c, bill);
            boolean hasLines = jdbc.sql("SELECT count(*) FROM \"SupplierClaimLine\" WHERE \"claimId\" = ? AND"
                    + " \"organizationId\" = ?").params(id, orgId).query(Long.class).single() > 0;
            String type = String.valueOf(c.get("type"));
            String kind = classification != null ? classification : defaultClassification(type, hasLines, doc);
            assertApplies(type, kind);
            Credit credit = switch (kind) {
                case "stock_adjustment" -> stockAdjustment(c, amount, bill);
                case "vat_adjustment" -> vatAdjustment(c, amount, doc);
                default -> credit(c, amount, kind, doc);
            };
            if (!"stock_adjustment".equals(kind)) {
                // Owner decision: without verified quantities a claim never moves inventory or GRNI.
                assertNoStockOrGrni(credit.lines());
            }
            Map<String, Object> entry = posting.post(new PostingRequest("PURCH", Instant.now(), "Supplier claim "
                    + c.get("claimNumber") + " (" + type + ", " + kind + ")", credit.currencyId(),
                    credit.exchangeRate(), "supplier_claim", id, null, "supplier_claim:" + id, null, null, null,
                    credit.lines()));
            String entryId = (String) entry.get("id");
            BigDecimal applied = lowerBill(credit.bill(), credit.billAmount());
            jdbc.sql("UPDATE \"SupplierClaim\" SET status = 'settled', \"settledAmount\" = ?, \"settledTax\" = ?,"
                    + " classification = ?, \"journalEntryId\" = ?, \"settledAt\" = " + NOW + ", version = version + 1,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(credit.settled(), credit.tax(), kind, entryId, id).update();
            audit.recordInTx(AuditInput.of("SupplierClaim", id, "update", Obj.of("status", "accepted"),
                    Obj.of("status", "settled", "classification", kind, "journalEntryId", entryId, "credit",
                            JsNumbers.dec(credit.gross()), "billApplied", JsNumbers.dec(applied))));
            // debitNoteId stays in the payload (packages/shared events.ts contract); R11 settlements create no note.
            events.publish("supplier_claim.settled", Obj.of("organizationId", orgId, "claimId", id, "debitNoteId",
                    null, "journalEntryId", entryId, "amount", JsNumbers.dec(credit.gross())));
        });
        return get(id);
    }

    /** Quantity lines → stock adjustment; price → price variance; no stock line → expense correction; else PPV. */
    private static String defaultClassification(String type, boolean hasLines, Doc doc) {
        if (hasLines) {
            return "stock_adjustment";
        }
        if ("price".equals(type)) {
            return "price_variance";
        }
        return doc.basis().stream().anyMatch(Basis::stocked) ? "price_variance" : "expense_correction";
    }

    private static void assertApplies(String type, String kind) {
        if ("stock_adjustment".equals(kind) && !QUANTITY_TYPES.contains(type)) {
            throw ApiException.badRequest("A stock adjustment settles a shortage, damage or quality claim with verified"
                    + " quantities, not a " + type + " claim");
        }
        if ("vat_adjustment".equals(kind) && !VAT_TYPES.contains(type)) {
            throw ApiException.badRequest("A VAT adjustment settles a price or other claim for over-charged VAT, not a "
                    + type + " claim");
        }
    }

    /** Refuse a posting that would touch stock valuation or GRNI (claims without verified quantities). */
    private void assertNoStockOrGrni(List<Line> lines) {
        Set<String> guarded = new HashSet<>();
        for (String key : List.of("stock_valuation", "grni_accrued")) {
            try {
                guarded.add(determination.mapped(key));
            } catch (RuntimeException e) {
                // not mapped: nothing to guard
            }
        }
        for (Line l : lines) {
            if (guarded.contains(l.accountId())) {
                throw ApiException.badRequest("A claim settled without verified quantities cannot post to inventory or"
                        + " GRNI — check the expense account of the claimed lines, or settle as a stock adjustment with"
                        + " verified quantities");
            }
        }
    }

    /** The posting of one claim: lines, entry currency / rate, gross AP debit, VAT reversed, net settled, the bill. */
    private record Credit(List<Line> lines, String currencyId, BigDecimal exchangeRate, BigDecimal gross,
            BigDecimal tax, BigDecimal settled, Map<String, Object> bill, BigDecimal billAmount) {

        /** The bill is lowered by the gross credit (the entry is in the bill's currency). */
        Credit(List<Line> lines, String currencyId, BigDecimal exchangeRate, BigDecimal gross, BigDecimal tax,
                BigDecimal settled, Map<String, Object> bill) {
            this(lines, currencyId, exchangeRate, gross, tax, settled, bill, gross);
        }
    }

    /** One line of the claimed document: value weight, stock flag, expense account, recovered VAT per unit of value. */
    private record Basis(BigDecimal weight, boolean stocked, String lineAccountId, String categoryExpenseAccountId,
            BigDecimal taxRatio) {}

    /** The claimed document: its lines, entry currency / rate (bill) or functional rate / currency (receipt, order). */
    private record Doc(Map<String, Object> bill, List<Basis> basis, String currencyId, BigDecimal exchangeRate,
            BigDecimal functionalRate, String foreignCurrency) {}

    /** A foreign order's functional rate and currency; rate 1 and no currency for a functional (or no) order. */
    private record Fx(BigDecimal rate, String currency) {}

    /** The claim's (locked) supplier bill; it must be posted. {@code null} without a bill. */
    private Map<String, Object> lockBill(Map<String, Object> c) {
        if (c.get("vendorBillId") == null) {
            return null;
        }
        Map<String, Object> bill = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\", status::text AS status,"
                + " \"currencyId\", \"exchangeRate\", \"journalEntryId\", \"amountResidual\" FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"documentType\"::text = 'vendor_bill' FOR UPDATE")
                .params(c.get("vendorBillId"), org()));
        if (bill == null) {
            throw ApiException.notFound("Supplier bill not found for this supplier");
        }
        if (!"posted".equals(bill.get("status")) && !"paid".equals(bill.get("status"))) {
            throw ApiException.badRequest("Post supplier bill " + bill.get("documentNumber") + " before settling a"
                    + " claim against it (status: " + bill.get("status") + ")");
        }
        return bill;
    }

    private Doc document(Map<String, Object> c, Map<String, Object> bill) {
        String orgId = org();
        if (bill != null) {
            String currencyId = (String) bill.get("currencyId");
            BigDecimal rate = Money.dec(bill.get("exchangeRate"));
            if (currencyId != null && fx.isForeign(bill)) {
                // Finance readiness audit B5: the claim relieves the payable at the bill's carrying rate, the rate it
                // posted at. A bill storing the default 1 ("not stated", FX-1) posted at the rate table's rate, and
                // that stored 1, passed on, made the claim post at today's table rate.
                rate = fx.documentRate(bill);
            }
            return new Doc(bill, billBasis((String) bill.get("id")), currencyId,
                    currencyId == null || rate.signum() <= 0 ? null : rate, BigDecimal.ONE, null);
        }
        List<Basis> basis;
        Map<String, Object> po = null;
        if (c.get("goodsReceiptId") != null) {
            basis = receiptBasis((String) c.get("goodsReceiptId"));
            po = SqlRows.one(jdbc.sql("SELECT o.\"currencyCode\", o.\"exchangeRate\" FROM \"GoodsReceiptNote\" g JOIN"
                    + " \"PurchaseOrder\" o ON o.id = g.\"purchaseOrderId\" WHERE g.id = ? AND g.\"organizationId\" = ?")
                    .params(c.get("goodsReceiptId"), orgId));
        } else if (c.get("purchaseOrderId") != null) {
            basis = orderBasis((String) c.get("purchaseOrderId"));
            po = SqlRows.one(jdbc.sql("SELECT \"currencyCode\", \"exchangeRate\" FROM \"PurchaseOrder\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(c.get("purchaseOrderId"), orgId));
        } else {
            basis = List.of();
        }
        Fx fx = fx(po);
        return new Doc(null, basis, null, null, fx.rate(), fx.currency());
    }

    /**
     * As the receipt voucher (P0-5): a foreign order's claim posts functional amounts and keeps the order currency on
     * the AP line, so FX revaluation and settlement see the credit as a foreign item.
     */
    private Fx fx(Map<String, Object> po) {
        if (po == null || po.get("currencyCode") == null) {
            return new Fx(BigDecimal.ONE, null);
        }
        String functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        BigDecimal rate = Money.dec(po.get("exchangeRate"));
        if (functional != null && !functional.equalsIgnoreCase(String.valueOf(po.get("currencyCode")))
                && rate.signum() > 0 && rate.compareTo(BigDecimal.ONE) != 0) {
            return new Fx(rate, String.valueOf(po.get("currencyCode")));
        }
        return new Fx(BigDecimal.ONE, null);
    }

    /** Price variance / expense correction: the claimed lines' counter accounts pro rata, plus the VAT they recovered. */
    private Credit credit(Map<String, Object> c, BigDecimal amount, String kind, Doc doc) {
        boolean expense = "expense_correction".equals(kind);
        List<Basis> basis = expense ? doc.basis().stream().filter(b -> !b.stocked()).toList() : doc.basis();
        if (expense && basis.isEmpty() && !doc.basis().isEmpty()) {
            throw ApiException.badRequest("An expense correction credits the expense a non-stock line was bought to,"
                    + " and the claimed document has only stock lines — settle it as a price variance, or as a stock"
                    + " adjustment with verified quantities");
        }
        Map<String, BigDecimal> counter = new LinkedHashMap<>();
        BigDecimal tax = Money.ZERO;
        BigDecimal totalWeight = Money.ZERO;
        for (Basis b : basis) {
            totalWeight = totalWeight.add(b.weight());
        }
        if (totalWeight.signum() <= 0) {
            counter.put(expense ? determination.mapped("default_expense") : priceVarianceAccount(), amount);
        } else {
            BigDecimal allocated = Money.ZERO;
            for (int i = 0; i < basis.size(); i++) {
                Basis b = basis.get(i);
                BigDecimal share = i == basis.size() - 1 ? amount.subtract(allocated)
                        : Money.round(amount.multiply(b.weight()).divide(totalWeight, 10, RoundingMode.HALF_UP));
                allocated = allocated.add(share);
                if (share.signum() == 0) {
                    continue;
                }
                // Only a price variance reaches a stock line: the stock ledger keeps its quantity and cost.
                String account = b.stocked() ? priceVarianceAccount()
                        : determination.expenseAccount(b.lineAccountId(), b.categoryExpenseAccountId());
                counter.merge(account, share, BigDecimal::add);
                tax = tax.add(Money.round(share.multiply(b.taxRatio())));
            }
        }
        BigDecimal gross = amount.add(tax);
        List<Line> lines = supplierCredit(c, counter, tax, gross, doc.functionalRate(), doc.foreignCurrency());
        return new Credit(lines, doc.currencyId(), doc.exchangeRate(), gross, tax, amount, doc.bill());
    }

    /** Over-charged VAT on a posted bill: Dr AP / Cr input VAT, capped by the bill's VAT not yet reversed. */
    private Credit vatAdjustment(Map<String, Object> c, BigDecimal amount, Doc doc) {
        Map<String, Object> bill = doc.bill();
        if (bill == null) {
            throw ApiException.badRequest("A VAT adjustment reverses input VAT a supplier bill over-charged — link the"
                    + " claim to that posted bill");
        }
        BigDecimal billVat = Money.round(jdbc.sql("SELECT COALESCE(sum(" + LINE_VAT + "), 0) FROM \"DocumentLine\" l"
                + " LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"documentId\" = ? AND l.\"organizationId\" = ? AND"
                + " COALESCE(l.\"lineType\", 'product') = 'product'").params(bill.get("id"), org())
                .query(BigDecimal.class).single());
        if (billVat.signum() <= 0) {
            throw ApiException.badRequest("Supplier bill " + bill.get("documentNumber") + " carries no input VAT to"
                    + " adjust");
        }
        BigDecimal reversed = jdbc.sql("SELECT COALESCE(sum(\"settledTax\"), 0) FROM \"SupplierClaim\" WHERE"
                + " \"vendorBillId\" = ? AND \"organizationId\" = ? AND status = 'settled' AND id <> ?")
                .params(bill.get("id"), org(), c.get("id")).query(BigDecimal.class).single();
        BigDecimal left = billVat.subtract(reversed);
        if (amount.compareTo(left) > 0) {
            throw ApiException.badRequest("VAT adjustment " + JsNumbers.dec(amount) + " exceeds the input VAT left on"
                    + " supplier bill " + bill.get("documentNumber") + " (" + JsNumbers.dec(left.max(BigDecimal.ZERO))
                    + " of " + JsNumbers.dec(billVat) + ")");
        }
        List<Line> lines = supplierCredit(c, Map.of(), amount, amount, BigDecimal.ONE, null);
        return new Credit(lines, doc.currencyId(), doc.exchangeRate(), amount, amount, amount, bill);
    }

    /**
     * Dr the supplier's AP (tagged with the partner; in the order currency when foreign) / Cr each counter account and
     * the input VAT reversed. Amounts are converted to functional at {@code rate} (1 for a bill: the entry carries the
     * bill currency and PostingService converts).
     */
    private List<Line> supplierCredit(Map<String, Object> c, Map<String, BigDecimal> counter, BigDecimal tax,
            BigDecimal gross, BigDecimal rate, String foreignCurrency) {
        String label = "Supplier claim " + c.get("claimNumber");
        BigDecimal apFunctional = Money.ZERO;
        List<Line> credits = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : counter.entrySet()) {
            BigDecimal v = functional(e.getValue(), rate);
            if (v.signum() != 0) {
                credits.add(v.signum() > 0 ? Line.credit(e.getKey(), JsNumbers.dec(v), label)
                        : Line.debit(e.getKey(), JsNumbers.dec(v.negate()), label));
                apFunctional = apFunctional.add(v);
            }
        }
        if (tax.signum() > 0) {
            BigDecimal v = functional(tax, rate);
            credits.add(Line.credit(determination.mapped("tax_receivable"), JsNumbers.dec(v), "Input tax reversal"));
            apFunctional = apFunctional.add(v);
        }
        List<Line> lines = new ArrayList<>();
        lines.add(apLine(c, apFunctional, gross, foreignCurrency, label));
        lines.addAll(credits);
        return lines;
    }

    private Line apLine(Map<String, Object> c, BigDecimal functionalAmount, BigDecimal documentAmount,
            String foreignCurrency, String label) {
        String partnerId = (String) c.get("partnerId");
        String ap = determination.payableAccount(jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(partnerId, org()).query(String.class).optional().orElse(null));
        Line apLine = new Line(ap, JsNumbers.dec(functionalAmount), null, partnerId, label, null, null, null);
        return foreignCurrency == null ? apLine : apLine.inCurrency(foreignCurrency, JsNumbers.dec(documentAmount));
    }

    /**
     * Stock adjustment: every quantity line verified (else refused — this is the only path that may post to inventory
     * or GRNI), each still within its receipt line's unclaimed quantity, their receipt-cost value within the agreed
     * amount. Per line, in the stock engine's lock order: the supplier side (GRNI for the open quantity of an ad-hoc
     * receipt, else AP plus the VAT the voucher / bill recovered) and the stock side (a return-to-supplier issue whose
     * engine value credits the stock account, the difference to the receipt cost to PPV; or, for goods already written
     * off, the write-off account). Posted in functional currency; the AP line keeps a foreign order's currency.
     */
    private Credit stockAdjustment(Map<String, Object> c, BigDecimal agreed, Map<String, Object> bill) {
        String orgId = org();
        String claimId = (String) c.get("id");
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.*, gl.quantity AS \"receivedQuantity\","
                + " gl.\"billedQuantity\", gl.\"uomId\", gl.\"uomFactor\", gl.\"batchNumber\", gl.description,"
                + " g.\"receiptNumber\", g.status::text AS \"receiptStatus\", g.\"reversedAt\", g.\"purchaseOrderId\","
                + " g.\"warehouseId\", COALESCE(pl.\"taxRate\", 0) AS \"taxRate\", (SELECT m.\"documentLineId\" FROM"
                + " \"VendorBillReceiptMatch\" m WHERE m.\"goodsReceiptLineId\" = gl.id AND m.\"organizationId\" ="
                + " gl.\"organizationId\" ORDER BY m.\"createdAt\" DESC LIMIT 1) AS \"billLineId\" FROM"
                + " \"SupplierClaimLine\" l JOIN \"GoodsReceiptLine\" gl ON gl.id = l.\"goodsReceiptLineId\" JOIN"
                + " \"GoodsReceiptNote\" g ON g.id = gl.\"goodsReceiptId\" LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id ="
                + " gl.\"purchaseOrderLineId\" WHERE l.\"claimId\" = ? AND l.\"organizationId\" = ? ORDER BY"
                + " l.\"createdAt\", l.id FOR UPDATE OF l, gl").params(claimId, orgId));
        if (lines.isEmpty()) {
            throw ApiException.badRequest("A stock adjustment needs quantity lines: record the received quantities the"
                    + " claim covers and have a second user verify them");
        }
        BigDecimal value = Money.ZERO;
        for (Map<String, Object> l : lines) {
            if (l.get("verifiedAt") == null || l.get("verifiedBy") == null) {
                throw ApiException.badRequest("The quantity " + JsNumbers.dec(Money.dec(l.get("quantity"))) + " of "
                        + l.get("description") + " is not verified — a second user must verify every quantity line"
                        + " before the claim is settled as a stock adjustment");
            }
            if (!"posted".equals(l.get("receiptStatus")) || l.get("reversedAt") != null) {
                throw ApiException.badRequest("Goods receipt " + l.get("receiptNumber") + " is no longer posted (status: "
                        + l.get("receiptStatus") + ")");
            }
            BigDecimal received = Money.dec(l.get("receivedQuantity"));
            if (claimedQuantity((String) l.get("goodsReceiptLineId")).compareTo(received) > 0) {
                throw ApiException.badRequest("Claims cover more of " + l.get("description") + " on "
                        + l.get("receiptNumber") + " than the " + JsNumbers.dec(received) + " received");
            }
            value = value.add(Money.round(Money.dec(l.get("quantity")).multiply(Money.dec(l.get("unitCost")))));
        }
        if (value.compareTo(agreed) > 0) {
            throw ApiException.badRequest("The verified quantities are worth " + JsNumbers.dec(value) + " at receipt cost,"
                    + " more than the agreed " + JsNumbers.dec(agreed) + " — reduce the quantity lines");
        }
        Map<String, Fx> fxByReceipt = new HashMap<>();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        // Hardening R3: stock credits per (account, product, warehouse) so each line carries its product dimension.
        Map<List<String>, BigDecimal> stockNet = new LinkedHashMap<>();
        BigDecimal grni = Money.ZERO;
        BigDecimal apNet = Money.ZERO;
        BigDecimal apFunctional = Money.ZERO;
        BigDecimal apTax = Money.ZERO;
        String foreign = null;
        String reference = "Claim " + c.get("claimNumber");
        for (Map<String, Object> l : StockMove.inLockOrder(lines)) {
            String lineId = (String) l.get("id");
            String productId = (String) l.get("productId");
            BigDecimal qty = Money.dec(l.get("quantity"));
            BigDecimal unitCost = Money.dec(l.get("unitCost"));
            BigDecimal lineValue = Money.round(qty.multiply(unitCost));
            Fx fx = fxByReceipt.computeIfAbsent((String) l.get("goodsReceiptId"), k -> fx(l.get("purchaseOrderId") == null
                    ? null : SqlRows.one(jdbc.sql("SELECT \"currencyCode\", \"exchangeRate\" FROM \"PurchaseOrder\" WHERE"
                            + " id = ? AND \"organizationId\" = ?").params(l.get("purchaseOrderId"), orgId))));
            if (fx.currency() != null) {
                foreign = fx.currency();
            }
            // A PO receipt was vouchered when posted (AP raised). An ad-hoc receipt keeps GRNI open for its unbilled
            // quantity; the claim takes that quantity off GRNI and off the bill matcher's open quantity.
            BigDecimal grniQty = Money.ZERO;
            if (l.get("purchaseOrderId") == null) {
                BigDecimal open = Money.dec(l.get("receivedQuantity")).subtract(Money.dec(l.get("billedQuantity")));
                grniQty = open.signum() <= 0 ? Money.ZERO : qty.min(open);
            }
            BigDecimal grniValue = grniQty.compareTo(qty) == 0 ? lineValue : Money.round(grniQty.multiply(unitCost));
            BigDecimal apValue = lineValue.subtract(grniValue);
            if (grniQty.signum() > 0) {
                jdbc.sql("UPDATE \"GoodsReceiptLine\" SET \"billedQuantity\" = \"billedQuantity\" + ? WHERE id = ?")
                        .params(grniQty, l.get("goodsReceiptLineId")).update();
                grni = grni.add(grniValue);
            }
            BigDecimal apLineFunctional = functional(apValue, fx.rate());
            if (apValue.signum() > 0) {
                apNet = apNet.add(apValue);
                apFunctional = apFunctional.add(apLineFunctional);
                apTax = apTax.add(Money.round(apValue.multiply(vatRatio(l))));
            }
            BigDecimal lineFunctional = grniValue.add(apLineFunctional);
            BigDecimal issuedValue = null;
            String ledgerCode = null;
            if ("issue".equals(l.get("stockAction"))) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(productId, orgId));
                if (product == null || !PurchaseOrdersService.stocked(product)) {
                    throw ApiException.badRequest(l.get("description") + " is not a stock product — record its quantity"
                            + " as already written off");
                }
                if (l.get("warehouseId") == null) {
                    throw ApiException.badRequest("Goods receipt " + l.get("receiptNumber") + " has no warehouse to"
                            + " return the goods from");
                }
                BigDecimal factor = l.get("uomFactor") == null ? null : Money.dec(l.get("uomFactor"));
                StockMove move = StockMove.of(productId, (String) l.get("warehouseId"), qty)
                        .variant((String) l.get("variantId"))
                        .uom(PurchaseOrdersService.stockUnit(new LineUnit((String) l.get("uomId"), factor), product),
                                factor)
                        .moveType("return_to_supplier").skipGl().requireAvailable(true)
                        .source("supplier_claim", claimId).sourceLine(lineId)
                        .reference(reference + " · GRN " + l.get("receiptNumber"));
                if (l.get("batchNumber") != null) {
                    move.batch((String) l.get("batchNumber")).strategy("MANUAL");
                }
                // The stock engine values the issue (AVCO / layers / standard) and the GL takes that value, so the
                // stock ledger and the stock account move together; the receipt-cost difference is a price variance.
                Issued issued = stock.issue(move);
                BigDecimal cost = issued == null ? Money.ZERO : Money.dec(issued.totalValue());
                if (cost.signum() > 0) {
                    stockNet.merge(List.of(stockPosting.stockAccount(productId, cost), productId,
                            (String) l.get("warehouseId")), cost, BigDecimal::add);
                }
                BigDecimal diff = lineFunctional.subtract(cost);
                if (diff.signum() != 0) {
                    net.merge(priceVarianceAccount(), diff, BigDecimal::add);
                }
                issuedValue = cost;
                ledgerCode = issued == null ? null : issued.ledgerCode();
            } else {
                // Already written off (Dr stock_adjustment_expense / Cr stock earlier): the credit reverses that loss.
                net.merge(lossAccount(), lineFunctional, BigDecimal::add);
            }
            jdbc.sql("UPDATE \"SupplierClaimLine\" SET \"grniQuantity\" = ?, \"issuedValue\" = ?, \"stockLedgerCode\" = ?,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(grniQty, issuedValue, ledgerCode, lineId)
                    .update();
        }
        String label = "Supplier claim " + c.get("claimNumber");
        BigDecimal rate = foreign == null ? BigDecimal.ONE : fxRate(fxByReceipt);
        // The agreed amount above the verified goods' receipt cost is a price concession with no quantity behind it:
        // Dr AP / Cr purchase price variance (+ the VAT at the goods' own ratio), never stock or GRNI, so the whole
        // agreed amount is credited.
        BigDecimal remainder = agreed.subtract(value);
        if (remainder.signum() > 0) {
            BigDecimal remainderTax = apNet.signum() > 0 ? Money.round(remainder.multiply(Money.div(apTax, apNet)))
                    : Money.ZERO;
            BigDecimal remainderFunctional = functional(remainder, rate);
            net.merge(priceVarianceAccount(), remainderFunctional, BigDecimal::add);
            apNet = apNet.add(remainder);
            apFunctional = apFunctional.add(remainderFunctional);
            apTax = apTax.add(remainderTax);
        }
        BigDecimal taxFunctional = functional(apTax, rate);
        BigDecimal apGross = apNet.add(apTax);
        List<Line> out = new ArrayList<>();
        if (apFunctional.add(taxFunctional).signum() > 0) {
            out.add(apLine(c, apFunctional.add(taxFunctional), apGross, foreign, label));
        }
        if (grni.signum() > 0) {
            out.add(Line.debit(determination.mapped("grni_accrued"), JsNumbers.dec(grni), label + " · GRNI"));
        }
        for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
            BigDecimal v = e.getValue();
            if (v.signum() > 0) {
                out.add(Line.credit(e.getKey(), JsNumbers.dec(v), label));
            } else if (v.signum() < 0) {
                out.add(Line.debit(e.getKey(), JsNumbers.dec(v.negate()), label));
            }
        }
        for (Map.Entry<List<String>, BigDecimal> e : stockNet.entrySet()) {
            BigDecimal v = e.getValue();
            List<String> k = e.getKey();
            if (v.signum() != 0) {
                Line line = v.signum() > 0 ? Line.credit(k.get(0), JsNumbers.dec(v), label)
                        : Line.debit(k.get(0), JsNumbers.dec(v.negate()), label);
                out.add(StockPostingService.stampLine(line, k.get(1), k.get(2)));
            }
        }
        if (taxFunctional.signum() > 0) {
            out.add(Line.credit(determination.mapped("tax_receivable"), JsNumbers.dec(taxFunctional),
                    "Input tax reversal"));
        }
        return new Credit(out, null, null, apGross, apTax, agreed, bill,
                inBillCurrency(bill, apGross, apFunctional.add(taxFunctional), foreign));
    }

    /**
     * The AP credit of a stock adjustment expressed in the bill's currency (the entry itself is in functional): the
     * order-currency gross when the bill is in that currency, the functional gross when the bill is functional, else
     * the functional gross at the bill's rate.
     */
    private BigDecimal inBillCurrency(Map<String, Object> bill, BigDecimal documentGross, BigDecimal functionalGross,
            String foreign) {
        if (bill == null) {
            return Money.ZERO;
        }
        String billCurrency = bill.get("currencyId") == null ? null : jdbc.sql("SELECT code FROM \"Currency\" WHERE"
                + " id = ?").param(bill.get("currencyId")).query(String.class).optional().orElse(null);
        String functionalCode = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        if (billCurrency == null || billCurrency.equalsIgnoreCase(functionalCode)) {
            return functionalGross;
        }
        if (billCurrency.equalsIgnoreCase(foreign)) {
            return documentGross;
        }
        BigDecimal billRate = Money.dec(bill.get("exchangeRate"));
        if (billRate.signum() <= 0) {
            throw ApiException.badRequest("Supplier bill " + bill.get("documentNumber") + " has no exchange rate to"
                    + " apply the claim's " + functionalCode + " credit to its " + billCurrency + " balance");
        }
        return Money.round(Money.div(functionalGross, billRate));
    }

    /** All quantity lines come from one receipt, so one functional rate. */
    private static BigDecimal fxRate(Map<String, Fx> byReceipt) {
        return byReceipt.values().stream().map(Fx::rate).filter(r -> r.compareTo(BigDecimal.ONE) != 0).findFirst()
                .orElse(BigDecimal.ONE);
    }

    /**
     * Input VAT recovered per unit of value on a quantity line's AP part: the order line's rate (the receipt voucher)
     * for a PO receipt, else the matched bill line's own VAT share.
     */
    private BigDecimal vatRatio(Map<String, Object> l) {
        if (l.get("purchaseOrderId") != null) {
            return Money.div(Money.dec(l.get("taxRate")), BigDecimal.valueOf(100));
        }
        if (l.get("billLineId") == null) {
            return Money.ZERO;
        }
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT l.subtotal, " + LINE_VAT + " AS vat FROM \"DocumentLine\" l"
                + " LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.id = ? AND l.\"organizationId\" = ?")
                .params(l.get("billLineId"), org()));
        if (r == null || Money.dec(r.get("subtotal")).signum() <= 0) {
            return Money.ZERO;
        }
        return Money.div(Money.dec(r.get("vat")), Money.dec(r.get("subtotal")));
    }

    private static BigDecimal functional(BigDecimal amount, BigDecimal rate) {
        return rate.compareTo(BigDecimal.ONE) == 0 ? amount : Money.round(amount.multiply(rate));
    }

    /** Purchase price variance, the stock adjustment expense account when no PPV account is mapped (as the bill). */
    private String priceVarianceAccount() {
        try {
            return determination.mapped("purchase_price_variance");
        } catch (RuntimeException e) {
            return determination.mapped("stock_adjustment_expense");
        }
    }

    /** Stock write-off account (verified quantities already written off), PPV when it is not mapped. */
    private String lossAccount() {
        try {
            return determination.mapped("stock_adjustment_expense");
        } catch (RuntimeException e) {
            return determination.mapped("purchase_price_variance");
        }
    }

    /** Stock flag (tracked, not a service — as the receipt) and category expense account of the line's product. */
    private static final String PRODUCT_COLUMNS = " COALESCE(p.\"trackInventory\", false) AND"
            + " COALESCE(p.\"productType\"::text, '') <> 'service' AS stocked, c.\"expenseAccountId\" AS"
            + " \"categoryExpenseAccountId\"";

    /** VAT one bill line {@code l} recovered (breakdown kind {@code tax}, else a non-withholding tax {@code t}). */
    private static final String LINE_VAT = "CASE WHEN EXISTS (SELECT 1 FROM \"DocumentLineTax\" x WHERE"
            + " x.\"documentLineId\" = l.id) THEN (SELECT COALESCE(sum(x.amount), 0) FROM \"DocumentLineTax\" x"
            + " WHERE x.\"documentLineId\" = l.id AND x.kind = 'tax') WHEN t.id IS NOT NULL AND t.type::text <>"
            + " 'withholding' THEN l.\"taxAmount\" ELSE 0 END";

    /** The bill's product lines with the VAT each recovered. */
    private List<Basis> billBasis(String billId) {
        List<Basis> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.subtotal AS weight, l.\"accountId\" AS"
                + " \"lineAccountId\", " + LINE_VAT + " AS vat," + PRODUCT_COLUMNS + " FROM \"DocumentLine\" l"
                + " LEFT JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " p.\"categoryId\" LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"documentId\" = ? AND"
                + " l.\"organizationId\" = ? AND COALESCE(l.\"lineType\", 'product') = 'product' AND l.subtotal > 0"
                + " ORDER BY l.\"lineNumber\", l.id").params(billId, org()))) {
            BigDecimal weight = Money.dec(r.get("weight"));
            out.add(new Basis(weight, Boolean.TRUE.equals(r.get("stocked")), (String) r.get("lineAccountId"),
                    (String) r.get("categoryExpenseAccountId"), Money.div(Money.dec(r.get("vat")), weight)));
        }
        return out;
    }

    /** The receipt's lines at order price; the voucher recovered VAT at the order line's rate on stock lines only. */
    private List<Basis> receiptBasis(String goodsReceiptId) {
        List<Basis> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT gl.quantity * COALESCE(pl.\"unitPrice\","
                + " gl.\"unitCost\", 0) AS weight, COALESCE(pl.\"taxRate\", 0) AS \"taxRate\"," + PRODUCT_COLUMNS
                + " FROM \"GoodsReceiptLine\" gl LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id ="
                + " gl.\"purchaseOrderLineId\" LEFT JOIN \"Product\" p ON p.id = gl.\"productId\" LEFT JOIN"
                + " \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE gl.\"goodsReceiptId\" = ? AND"
                + " gl.\"organizationId\" = ? ORDER BY gl.\"lineNumber\", gl.id").params(goodsReceiptId, org()))) {
            boolean stocked = Boolean.TRUE.equals(r.get("stocked"));
            BigDecimal ratio = stocked ? Money.div(Money.dec(r.get("taxRate")), BigDecimal.valueOf(100)) : Money.ZERO;
            out.add(new Basis(Money.dec(r.get("weight")), stocked, null, (String) r.get("categoryExpenseAccountId"),
                    ratio));
        }
        return out;
    }

    /** The order's lines at order price; nothing recovered VAT yet, so none is reversed. */
    private List<Basis> orderBasis(String purchaseOrderId) {
        List<Basis> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT pl.quantity * pl.\"unitPrice\" AS weight,"
                + PRODUCT_COLUMNS + " FROM \"PurchaseOrderLine\" pl LEFT JOIN \"Product\" p ON p.id = pl.\"productId\""
                + " LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE pl.\"purchaseOrderId\" = ? AND"
                + " pl.\"organizationId\" = ? ORDER BY pl.\"lineNumber\", pl.id").params(purchaseOrderId, org()))) {
            out.add(new Basis(Money.dec(r.get("weight")), Boolean.TRUE.equals(r.get("stocked")), null,
                    (String) r.get("categoryExpenseAccountId"), Money.ZERO));
        }
        return out;
    }

    /**
     * Lower the (locked) bill's open amount by the gross credit, never below zero, and return what was applied; the
     * rest stays on AP as a supplier credit balance. Payment status follows the credit-note allocation convention of
     * invoicing {@code CreditNoteService.applyTo} (a credit allocation counts like a payment): {@code paid} (status
     * {@code paid} too) at a zero residual, else {@code partial}. {@code null} bill or nothing open: nothing applied.
     */
    private BigDecimal lowerBill(Map<String, Object> bill, BigDecimal gross) {
        if (bill == null) {
            return Money.ZERO;
        }
        BigDecimal residual = Money.dec(bill.get("amountResidual"));
        BigDecimal applied = gross.min(residual);
        if (applied.signum() <= 0) {
            return Money.ZERO;
        }
        BigDecimal left = residual.subtract(applied);
        boolean paid = left.signum() <= 0;
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\", status ="
                        + " CASE WHEN ? THEN 'paid'::\"DocumentStatus\" ELSE status END, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(left, paid ? "paid" : "partial", paid, bill.get("id"), org()).update();
        return applied;
    }

    // ── queries ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code {data, meta}} newest first; each claim carries its quantity lines. */
    public PageResult<Map<String, Object>> list(String status, String partnerId, String purchaseOrderId, int page,
            int limit) {
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND c.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (purchaseOrderId != null && !purchaseOrderId.isBlank()) {
            where.append(" AND c.\"purchaseOrderId\" = ?");
            params.add(purchaseOrderId);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"SupplierClaim\" c" + where).params(params).query(Long.class)
                .single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(limit);
        pp.add((long) (page - 1) * limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(SELECT + where + " ORDER BY c.\"createdAt\" DESC, c.id LIMIT ?"
                + " OFFSET ?").params(pp))) {
            rows.add(wire(r));
        }
        attachLines(rows);
        return PageResult.of(rows, page, limit, total);
    }

    /** One claim with supplier, order and debit-note numbers and its quantity lines. */
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql(SELECT + " WHERE c.id = ? AND c.\"organizationId\" = ?")
                .params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Claim not found");
        }
        Map<String, Object> out = wire(r);
        attachLines(List.of(out));
        return out;
    }

    /** {@code quantityLines} per claim (receipt, product, quantity, receipt cost, value, verification). */
    private void attachLines(List<Map<String, Object>> claims) {
        if (claims.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, Object>>> byClaim = new LinkedHashMap<>();
        for (Map<String, Object> c : claims) {
            byClaim.put((String) c.get("id"), new ArrayList<>());
        }
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.id, l.\"claimId\", l.\"goodsReceiptId\","
                + " g.\"receiptNumber\", l.\"goodsReceiptLineId\", l.\"productId\", p.name AS \"productName\","
                + " l.\"variantId\", l.quantity, l.\"unitCost\", l.\"stockAction\", l.notes, l.\"createdBy\","
                + " l.\"verifiedBy\", NULLIF(trim(concat_ws(' ', vu.\"firstName\", vu.\"lastName\")), '') AS"
                + " \"verifiedByName\", l.\"verifiedAt\", l.\"grniQuantity\", l.\"issuedValue\", l.\"stockLedgerCode\""
                + " FROM \"SupplierClaimLine\" l LEFT JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
                + " LEFT JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"User\" vu ON vu.id = l.\"verifiedBy\""
                + " WHERE l.\"claimId\" = ANY(?::text[]) AND l.\"organizationId\" = ? ORDER BY l.\"createdAt\", l.id")
                .params(byClaim.keySet().toArray(String[]::new), org()))) {
            BigDecimal qty = Money.dec(l.get("quantity"));
            BigDecimal unitCost = Money.dec(l.get("unitCost"));
            l.put("quantity", JsNumbers.dec(qty));
            l.put("unitCost", JsNumbers.dec(unitCost));
            l.put("value", JsNumbers.dec(Money.round(qty.multiply(unitCost))));
            l.put("grniQuantity", JsNumbers.dec(Money.dec(l.get("grniQuantity"))));
            l.put("issuedValue", l.get("issuedValue") == null ? null : JsNumbers.dec(Money.dec(l.get("issuedValue"))));
            l.put("verified", l.get("verifiedAt") != null);
            byClaim.get((String) l.get("claimId")).add(l);
        }
        for (Map<String, Object> c : claims) {
            c.put("quantityLines", byClaim.get((String) c.get("id")));
        }
    }

    /** Claims settled before R11 keep their (outbound) debit note link; R11 settlements link the journal entry. */
    private static final String SELECT = "SELECT c.*, p.name AS \"partnerName\", o.\"orderNumber\", g.\"receiptNumber\","
            + " d.\"documentNumber\" AS \"billNumber\", n.\"noteNumber\" AS \"debitNoteNumber\", n.status::text AS"
            + " \"debitNoteStatus\", je.\"entryNumber\" AS \"journalEntryNumber\" FROM \"SupplierClaim\" c"
            + " LEFT JOIN \"Partner\" p ON p.id = c.\"partnerId\" LEFT JOIN \"PurchaseOrder\" o ON"
            + " o.id = c.\"purchaseOrderId\" LEFT JOIN \"GoodsReceiptNote\" g ON g.id = c.\"goodsReceiptId\""
            + " LEFT JOIN \"Document\" d ON d.id = c.\"vendorBillId\" LEFT JOIN \"DebitNote\" n ON"
            + " n.id = c.\"debitNoteId\" LEFT JOIN \"JournalEntry\" je ON je.id = c.\"journalEntryId\"";

    private Map<String, Object> transition(String id, List<String> from, String to, String extraSet, BigDecimal amount,
            String response) {
        List<Object> params = new ArrayList<>();
        params.add(to);
        if (extraSet.contains("?")) {
            params.add(amount);
            params.add(response);
        }
        params.add(id);
        params.add(org());
        params.add(from.toArray(String[]::new));
        int n = jdbc.sql("UPDATE \"SupplierClaim\" SET status = ?" + extraSet + ", version = version + 1, \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = ANY(?::text[])").params(params).update();
        Map<String, Object> c = get(id);
        if (n == 0) {
            throw ApiException.badRequest("Cannot change a " + c.get("status") + " claim to " + to);
        }
        audit.record(AuditInput.of("SupplierClaim", id, "update", null, Obj.of("status", to)));
        return c;
    }

    private void assertLinked(String table, String id, String partnerId, String label) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(id, org(), partnerId).query(Long.class).single() == 0) {
            throw ApiException.notFound(label + " not found for this supplier");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> wire(Map<String, Object> r) {
        r.put("amount", JsNumbers.dec(Money.dec(r.get("amount"))));
        r.put("settledAmount", JsNumbers.dec(Money.dec(r.get("settledAmount"))));
        r.put("settledTax", JsNumbers.dec(Money.dec(r.get("settledTax"))));
        return r;
    }
}
