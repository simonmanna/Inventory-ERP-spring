package com.inventoryerp.rental.application;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock + invoice mechanics of a rental (port of {@code rental-posting.service.ts}). Checkout moves stock RENT-STOCK →
 * RENT-OUT as a plain internal transfer (no COGS, no revenue); returns move RENT-OUT → RENT-CLEAN / RENT-REPAIR /
 * RENT-DAMAGED and inspection RENT-CLEAN → RENT-STOCK. Fees ride the POS invoice spine (one Order + one Invoice per
 * billing event), so the GL and COA mapping stay those of an ordinary sale.
 */
@Service
public class RentalPostingService {

    /** One fee line ({@code {productId, description, quantity, unitPrice, rentalFeeType}}). */
    public record FeeLine(String productId, String description, double quantity, double unitPrice,
            String rentalFeeType) {}

    private final PosDb db;
    private final SequenceService sequence;
    private final StockService stock;
    private final RentalLocationConfigService locations;
    private final PosInvoiceService posInvoice;

    public RentalPostingService(RentalRows rows, SequenceService sequence, StockService stock,
            RentalLocationConfigService locations, PosInvoiceService posInvoice) {
        this.db = rows.db();
        this.sequence = sequence;
        this.stock = stock;
        this.locations = locations;
        this.posInvoice = posInvoice;
    }

    /** Checkout transfer for one agreement line: RENT-STOCK → RENT-OUT. */
    @Transactional
    public void checkoutTransfer(String agreementId, String productId, Object lineQuantity) {
        String orgId = RentalRows.org();
        String stockLoc = locations.resolve(orgId, "stock");
        String outLoc = locations.resolve(orgId, "out");
        double q = RentalRows.num(lineQuantity);
        double quantity = q == 0 || Double.isNaN(q) ? 1 : q;
        if (quantity <= 0) {
            throw ApiException.badRequest("Line quantity must be positive");
        }
        move(productId, stockLoc, outLoc, quantity, "rental_checkout", agreementId, "Rental checkout " + agreementId);
    }

    /** Return transfer: RENT-OUT → destination by disposition (damaged / repair / otherwise cleaning). */
    @Transactional
    public void returnTransfer(String agreementId, String productId, double quantity, String disposition) {
        String orgId = RentalRows.org();
        String outLoc = locations.resolve(orgId, "out");
        String role = "damaged".equals(disposition) ? "damaged" : "repair".equals(disposition) ? "repair" : "cleaning";
        String dest = locations.resolve(orgId, role);
        move(productId, outLoc, dest, quantity, "rental_return", agreementId,
                "Rental return (" + disposition + ") " + agreementId);
    }

    /** Cleaning complete: RENT-CLEAN → RENT-STOCK (inspection passed). */
    @Transactional
    public void cleaningCompleteTransfer(String agreementId, String productId, double quantity) {
        String orgId = RentalRows.org();
        String stockLoc = locations.resolve(orgId, "stock");
        String cleanLoc = locations.resolve(orgId, "cleaning");
        move(productId, cleanLoc, stockLoc, quantity, "rental_inspect", agreementId,
                "Rental inspection passed " + agreementId);
    }

    private void move(String productId, String from, String to, double quantity, String sourceType, String sourceId,
            String notes) {
        StockMove m = StockMove.transfer(productId, from, to, RentalRows.dec(quantity));
        m.sourceType = sourceType;
        m.sourceId = sourceId;
        m.notes = notes;
        stock.transfer(m);
    }

    /** Checkout invoice: one order + one invoice covering every line of the agreement (fee product RENT-FEE). */
    @Transactional
    public Map<String, Object> checkoutInvoice(Map<String, Object> agreement, List<Map<String, Object>> lines) {
        Map<String, Object> feeProduct = feeProduct("RENT-FEE");
        List<FeeLine> fees = new java.util.ArrayList<>();
        for (Map<String, Object> l : lines) {
            double qty = RentalRows.num(l.get("quantity"));
            double divisor = qty == 0 || Double.isNaN(qty) ? 1 : qty;
            fees.add(new FeeLine(feeProduct != null ? (String) feeProduct.get("id") : (String) l.get("productId"),
                    "Rental " + l.get("ratePeriod") + " — " + agreement.get("agreementNumber"),
                    l.get("quantity") == null ? 1 : qty,
                    RentalRows.num(l.get("lineTotal")) / divisor, "rental"));
        }
        return createFeeInvoice((String) agreement.get("id"), (String) agreement.get("partnerId"),
                (String) agreement.get("branchId"), "rental", fees, null);
    }

    /** {@code product.findFirst({where: {organizationId, code, isActive: true}})}. */
    Map<String, Object> feeProduct(String code) {
        return db.first(com.inventoryerp.core.domain.Product.class, "\"organizationId\" = ? AND code = ? AND"
                + " \"isActive\" = true AND \"deletedAt\" IS NULL", RentalRows.org(), code);
    }

    /**
     * Fee order + invoice: an Order marked {@code transactionKind = rental} (source document = the agreement) with
     * one item per fee line, billed through {@link PosInvoiceService#generateInvoice} inside this transaction.
     * Returns {@code {orderId, invoiceId, order, invoice}}.
     */
    @Transactional
    public Map<String, Object> createFeeInvoice(String agreementId, String partnerId, String branchId, String feeType,
            List<FeeLine> lines, String paymentMode) {
        String orgId = RentalRows.org();
        String orderNumber = sequence.next("pos_order", "OR-", 6);
        Map<String, Object> order = db.insert(Order.class, Obj.of("organizationId", orgId, "orderNumber", orderNumber,
                "orderType", "takeaway", "status", "confirmed", "partnerId", partnerId, "branchId", branchId,
                "notes", "Rental " + feeType + " — agreement " + agreementId, "transactionKind", "rental",
                "sourceDocumentType", "rental_agreement", "sourceDocumentId", agreementId,
                "rentalAgreementId", agreementId));
        int idx = 0;
        for (FeeLine l : lines) {
            db.insert(OrderItem.class, Obj.of("organizationId", orgId, "orderId", order.get("id"),
                    "productId", l.productId(),
                    "description", l.description() != null ? l.description() : "Rental " + feeType,
                    "quantity", RentalRows.dec(l.quantity()), "unitPrice", RentalRows.dec(l.unitPrice()),
                    "discountPercent", BigDecimal.ZERO, "discountType", "percentage", "discountAmount", BigDecimal.ZERO,
                    "taxInclusive", false, "lineNumber", idx++,
                    "rentalFeeType", l.rentalFeeType() != null ? l.rentalFeeType() : feeType));
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("paymentMode", paymentMode != null ? paymentMode : "cash");
        Map<String, Object> invoice = posInvoice.generateInvoice((String) order.get("id"), dto, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.get("id"));
        out.put("invoiceId", invoice.get("id"));
        out.put("order", order);
        out.put("invoice", invoice);
        return out;
    }
}
