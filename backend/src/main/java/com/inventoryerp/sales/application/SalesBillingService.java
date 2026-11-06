package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.fleet.application.FleetDeliveryInvoiceService;
import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Billing of sales orders (ADR-S011 phase S6, sales-postings.md P3, P8, P9).
 *
 * <p>Regular invoices bill, per line, what the invoicing policy allows and is not yet on an invoice (draft or posted):
 * {@code ordered} → ordered − closed short; {@code delivered} → billable (S5 decision). The policy is the order's
 * override, else the product's. Goods leave through fleet, so the invoice never issues stock again
 * ({@code stockIssuedBy}); fleet delivery orders of the order are marked goods-invoiced so a delivery-order invoice
 * only carries transport charges.
 *
 * <p>Down-payment invoices post their line to the customer-deposit account (with VAT only when the deposit tax point is
 * "receipt"); the deposit is registered when the invoice posts. Deposits are applied to final invoices explicitly, or
 * oldest-first automatically when the settings say so ({@link #onInvoicePosted}).
 */
@Service
public class SalesBillingService {

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesSettingsService settings;
    private final InvoiceService invoices;
    private final CustomerDepositService deposits;
    private final FleetDeliveryInvoiceService fleetInvoices;
    private final AuditService audit;

    public SalesBillingService(SalesDb db, SalesOrderService orders, SalesSettingsService settings,
            InvoiceService invoices, CustomerDepositService deposits, FleetDeliveryInvoiceService fleetInvoices,
            AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.settings = settings;
        this.invoices = invoices;
        this.deposits = deposits;
        this.fleetInvoices = fleetInvoices;
        this.audit = audit;
    }

    /** What can be invoiced now, per line, plus deposits of the order and its invoices. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String orderId) {
        Map<String, Object> o = orders.require(orderId);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : toInvoice(o)) {
            total = total.add(dec(l.get("amount")));
            lines.add(l);
        }
        return Obj.of("orderId", orderId, "lines", lines, "amount", JsNumbers.js(total), "deposits", deposits.list(null,
                orderId, null), "invoices", db.rows("SELECT d.id, d.\"documentNumber\", d.status::text AS status,"
                        + " d.\"totalAmount\", d.\"amountResidual\", d.data->>'downPaymentFor' IS NOT NULL AS \"downPayment\""
                        + " FROM \"Document\" d WHERE d.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order' AND"
                        + " d.\"sourceId\" = ? ORDER BY d.\"createdAt\"", org(), orderId));
    }

    private List<Map<String, Object>> toInvoice(Map<String, Object> o) {
        String override = str(o.get("billingPolicy"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.rows("""
                SELECT l.*, p."invoicePolicy", p."trackInventory",
                       (SELECT v.name FROM "ProductVariant" v WHERE v.id = l."variantId") AS "variantName",
                       COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k JOIN "Document" d ON d.id = k."documentId"
                                  WHERE k."orderLineId" = l.id AND k.kind = 'regular'
                                    AND d.status::text NOT IN ('cancelled', 'rejected')), 0) AS linked
                  FROM "SalesOrderLine" l LEFT JOIN "Product" p ON p.id = l."productId"
                 WHERE l."orderId" = ? ORDER BY l."sortOrder\"""", o.get("id"))) {
            String policy = override != null ? override : l.get("invoicePolicy") == null ? "delivered"
                    : str(l.get("invoicePolicy"));
            BigDecimal base = "ordered".equals(policy) ? dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled")))
                    : dec(l.get("qtyBillable"));
            BigDecimal qty = base.subtract(dec(l.get("linked"))).max(BigDecimal.ZERO);
            if (qty.signum() == 0) {
                continue;
            }
            BigDecimal unitTotal = dec(l.get("lineTotal")).divide(dec(l.get("quantity")), 6, RoundingMode.HALF_UP);
            Map<String, Object> m = Obj.of("lineId", l.get("id"), "description", l.get("description"), "policy", policy,
                    "quantity", JsNumbers.js(qty), "amount", JsNumbers.js(qty.multiply(unitTotal).setScale(2,
                            RoundingMode.HALF_UP)));
            m.put("_row", l);
            out.add(m);
        }
        return out;
    }

    /**
     * Create a draft invoice for an order: {@code mode = regular} (what is invoiceable) or {@code down_payment}
     * ({@code percent} of the order total or a fixed {@code amount}).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> createInvoice(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.lock(orderId);
        if (!SalesOrderService.OPEN.contains(o.get("status")) && !"done".equals(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be invoiced");
        }
        String mode = blank(str(in.get("mode"))) ? "regular" : str(in.get("mode"));
        String issue = blank(str(in.get("issueDate"))) ? LocalDate.now().toString() : str(in.get("issueDate"));
        String number = str(o.get("number"));
        Map<String, Object> doc;
        if ("down_payment".equals(mode)) {
            BigDecimal amount = in.get("amount") != null ? dec(in.get("amount"))
                    : dec(o.get("total")).multiply(dec(in.get("percent"))).divide(BigDecimal.valueOf(100), 2,
                            RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("Give a down-payment percent or amount");
            }
            if (amount.compareTo(dec(o.get("total"))) > 0) {
                throw ApiException.badRequest("A down payment cannot exceed the order total");
            }
            boolean taxOnReceipt = "receipt".equals(settings.settings().get("depositTaxPoint"));
            Map<String, Object> taxed = !taxOnReceipt ? null : db.row("SELECT \"taxId\" FROM \"SalesOrderLine\" WHERE"
                    + " \"orderId\" = ? AND \"taxId\" IS NOT NULL ORDER BY \"sortOrder\" LIMIT 1", orderId);
            String taxId = taxed == null ? null : str(taxed.get("taxId"));
            LineInput line = new LineInput(null, null, null, null, deposits.depositAccount(), "Down payment for sales order "
                    + number, BigDecimal.ONE, amount, BigDecimal.ZERO, null, null, null, "manual", taxId, null,
                    taxId != null, null);
            doc = create(o, issue, List.of(line), "Down payment — " + number);
            invoices.linkSource(str(doc.get("id")), "sales_order", orderId, true);
            link(orderId, null, str(doc.get("id")), "down_payment", BigDecimal.ZERO, amount);
        } else if ("regular".equals(mode)) {
            List<Map<String, Object>> items = toInvoice(o);
            if (items.isEmpty()) {
                throw ApiException.badRequest("Nothing to invoice on sales order " + number);
            }
            List<LineInput> lines = new ArrayList<>();
            boolean goods = false;
            for (Map<String, Object> it : items) {
                Map<String, Object> l = (Map<String, Object>) it.get("_row");
                goods |= Boolean.TRUE.equals(l.get("trackInventory"));
                lines.add(new LineInput(str(l.get("productId")), null, str(l.get("variantId")), str(l.get("variantName")),
                        null, str(l.get("description")),
                        dec(it.get("quantity")), dec(l.get("unitPrice")), dec(l.get("discountPercent")), null, null, null,
                        "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")),
                        str(l.get("uomId"))));
            }
            doc = create(o, issue, lines, "Sales order " + number);
            String docId = str(doc.get("id"));
            invoices.linkSource(docId, "sales_order", orderId, false);
            if (goods) {
                invoices.setStockIssuedByDelivery(docId, number);
            }
            for (Map<String, Object> it : items) {
                link(orderId, str(it.get("lineId")), docId, "regular", dec(it.get("quantity")), dec(it.get("amount")));
            }
            fleetInvoices.markGoodsInvoiced("sales_order", orderId, docId, str(doc.get("documentNumber")));
        } else {
            throw ApiException.badRequest("mode must be one of: down_payment, regular");
        }
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("invoice", doc.get("documentNumber"),
                "mode", mode)));
        return invoices.findOne(str(doc.get("id")));
    }

    /** One regular invoice per order of a customer (or of the given orders) with something to invoice. */
    @Transactional
    public List<Map<String, Object>> batch(Map<String, Object> in) {
        List<String> ids = new ArrayList<>(SalesDb.strings(in.get("orderIds")));
        if (ids.isEmpty() && !blank(str(in.get("partnerId")))) {
            for (Map<String, Object> r : db.rows("SELECT id FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND"
                    + " \"partnerId\" = ? AND status IN ('confirmed', 'in_fulfilment', 'done') AND \"deletedAt\" IS NULL ORDER"
                    + " BY \"confirmedAt\"", org(), str(in.get("partnerId")))) {
                ids.add(str(r.get("id")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            if (!toInvoice(orders.require(id)).isEmpty()) {
                out.add(createInvoice(id, Obj.of("mode", "regular", "issueDate", in.get("issueDate"))));
            }
        }
        return out;
    }

    private Map<String, Object> create(Map<String, Object> o, String issue, List<LineInput> lines, String notes) {
        Map<String, Object> site = o.get("siteId") == null ? null : db.row("SELECT name, address, city FROM"
                + " \"FleetCustomerSite\" WHERE id = ?", o.get("siteId"));
        String address = site != null ? String.join(", ", java.util.stream.Stream.of(site.get("name"), site.get(
                "address"), site.get("city")).filter(x -> x != null).map(Object::toString).toList())
                : str(o.get("deliveryAddress"));
        // The order's prices are in its currency, so its invoice must be too.
        Map<String, Object> currency = db.row("SELECT c.id FROM \"Currency\" c JOIN \"Organization\" g ON g.id = ?"
                + " WHERE c.code = ? AND c.code <> g.\"currencyCode\"", org(), o.get("currencyCode"));
        String currencyId = currency == null ? null : str(currency.get("id"));
        BigDecimal rate = currencyId == null ? null : dec(o.get("exchangeRate"));
        return invoices.create(new InvoiceInput(str(o.get("partnerId")), issue, null, str(o.get("paymentTermId")), null,
                null, null, str(o.get("ownerId")), null, address, str(o.get("incoterm")), null, str(o.get("number")),
                currencyId, rate, str(o.get("customerRef")), notes, lines));
    }

    private void link(String orderId, String lineId, String documentId, String kind, BigDecimal qty, BigDecimal amount) {
        db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"orderLineId\", \"documentId\","
                + " kind, quantity, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(), org(), orderId, lineId,
                documentId, kind, qty, amount);
    }

    /**
     * {@code invoice.posted} / {@code invoice.cancelled}: recompute gross invoiced per line from the posted invoices
     * linked to the order, refresh the order status and (setting {@code oldest_first}) apply the order's open
     * deposits to a posted regular invoice.
     */
    @Transactional
    public void onInvoiceChanged(String documentId, boolean posted) {
        List<Map<String, Object>> links = db.rows("SELECT DISTINCT \"orderId\", kind FROM \"SalesInvoiceLink\" WHERE"
                + " \"documentId\" = ? AND \"organizationId\" = ?", documentId, org());
        for (Map<String, Object> k : links) {
            String orderId = str(k.get("orderId"));
            recomputeInvoiced(orderId);
            if (posted && "regular".equals(k.get("kind"))
                    && "oldest_first".equals(settings.settings().get("depositApplication"))) {
                applyDeposits(orderId, documentId);
            }
        }
    }

    /** Gross invoiced per line = Σ regular links on posted / paid invoices. */
    @Transactional
    public void recomputeInvoiced(String orderId) {
        db.update("""
                UPDATE "SalesOrderLine" l SET "qtyInvoiced" = COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k
                       JOIN "Document" d ON d.id = k."documentId" WHERE k."orderLineId" = l.id AND k.kind = 'regular'
                        AND d.status::text IN ('posted', 'paid')), 0)
                 WHERE l."orderId" = ?""", orderId);
        orders.refreshStatus(orderId);
    }

    /** Apply the order's open deposits, oldest first, to a posted invoice (stops when the invoice is settled). */
    @Transactional
    public List<Map<String, Object>> applyDeposits(String orderId, String documentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : deposits.list(null, orderId, "open")) {
            Map<String, Object> doc = db.row("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?", documentId);
            BigDecimal residual = doc == null ? BigDecimal.ZERO : dec(doc.get("amountResidual"));
            if (residual.signum() <= 0) {
                break;
            }
            BigDecimal amount = dec(d.get("available")).min(residual);
            if (amount.signum() > 0) {
                out.add(deposits.apply(str(d.get("id")), documentId, amount));
            }
        }
        return out;
    }
}
