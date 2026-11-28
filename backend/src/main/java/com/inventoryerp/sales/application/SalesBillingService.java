package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.fleet.application.DeliveryInvoicing;
import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.kernel.common.BusinessZone;
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
    private final DeliveryInvoicing deliveryInvoicing;
    private final AuditService audit;
    private final CreditNoteService creditNotes;

    public SalesBillingService(SalesDb db, SalesOrderService orders, SalesSettingsService settings,
            InvoiceService invoices, CustomerDepositService deposits, DeliveryInvoicing deliveryInvoicing,
            AuditService audit, CreditNoteService creditNotes) {
        this.db = db;
        this.orders = orders;
        this.settings = settings;
        this.invoices = invoices;
        this.deposits = deposits;
        this.deliveryInvoicing = deliveryInvoicing;
        this.audit = audit;
        this.creditNotes = creditNotes;
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
        List<Map<String, Object>> legacy = legacyInvoices(orderId, deliveryInvoicing.goodsInvoices(orderId));
        return Obj.of("orderId", orderId, "legacyFleetInvoices", legacy, "blocked", !legacy.isEmpty(), "lines", lines,
                "amount", JsNumbers.js(total), "deposits", deposits.list(null,
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
            String policy = policy(override, l);
            BigDecimal base = base(policy, l);
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

    private static String policy(String override, Map<String, Object> l) {
        return override != null ? override : l.get("invoicePolicy") == null ? "delivered" : str(l.get("invoicePolicy"));
    }

    /**
     * What a line may be invoiced for in total: {@code ordered} → ordered − closed short; {@code delivered} → billable
     * (Phase A A4: the POD-accepted quantity, pending disputes never included).
     */
    private static BigDecimal base(String policy, Map<String, Object> l) {
        return "ordered".equals(policy) ? dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled")))
                : dec(l.get("qtyBillable"));
    }

    /**
     * Phase A A4 (plan §6): live goods invoices on the order's delivery orders that are not Sales' own (no
     * {@code origin = sales} link to the order) and have no reconciled review — historical Fleet goods invoices.
     */
    List<Map<String, Object>> legacyInvoices(String orderId, List<Map<String, Object>> goodsInvoices) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : goodsInvoices) {
            long own = db.count("SELECT count(*) FROM \"SalesInvoiceLink\" WHERE \"orderId\" = ? AND \"documentId\" = ? AND"
                    + " origin = 'sales'", List.of(orderId, g.get("documentId")));
            long reconciled = db.count("SELECT count(*) FROM \"SalesLegacyInvoiceReview\" WHERE \"orderId\" = ? AND"
                    + " \"documentId\" = ? AND status = 'reconciled'", List.of(orderId, g.get("documentId")));
            if (own == 0 && reconciled == 0) {
                out.add(g);
            }
        }
        return out;
    }

    /** Whether the order is blocked from goods invoicing by an unreconciled historical Fleet invoice. */
    public boolean legacyBlocked(String orderId) {
        return !legacyInvoices(orderId, deliveryInvoicing.goodsInvoices(orderId)).isEmpty();
    }

    /** SG-9: every taxable line carries a tax, and a large invoice needs the customer's tax number. */
    private void assertTaxRules(Map<String, Object> o) {
        Map<String, Object> s = settings.settings();
        if (Boolean.TRUE.equals(s.get("requireLineTax")) && db.count("SELECT count(*) FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND"
                + " \"taxId\" IS NULL AND \"productId\" IS NOT NULL", List.of(o.get("id"))) > 0) {
            throw ApiException.badRequest("Every product line needs a tax before invoicing");
        }
        BigDecimal above = dec(s.get("requireTaxNumberAbove"));
        if (above.signum() > 0 && dec(o.get("total")).compareTo(above) > 0) {
            Map<String, Object> p = db.row("SELECT \"taxNumber\" FROM \"Partner\" WHERE id = ?"
                    + " AND \"organizationId\" = ?", o.get("partnerId"), org());
            if (p == null || blank(str(p.get("taxNumber")))) {
                throw ApiException.badRequest("This customer needs a tax number for an invoice of this size");
            }
        }
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
        assertTaxRules(o);
        String mode = blank(str(in.get("mode"))) ? "regular" : str(in.get("mode"));
        String issue = blank(str(in.get("issueDate"))) ? LocalDate.now(BusinessZone.get()).toString() : str(in.get("issueDate"));
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
            // Sales readiness R2b (INV-7): together with the order's live down-payment invoices (the order is locked).
            BigDecimal already = dec(db.row("SELECT COALESCE(sum(k.amount), 0) AS s FROM \"SalesInvoiceLink\" k JOIN"
                    + " \"Document\" d ON d.id = k.\"documentId\" WHERE k.\"orderId\" = ? AND k.\"organizationId\" = ? AND"
                    + " k.kind = 'down_payment' AND d.status::text <> 'cancelled'", orderId, org()).get("s"));
            if (already.add(amount).compareTo(dec(o.get("total"))) > 0) {
                throw ApiException.badRequest("Down payments on sales order " + number + " would exceed the order total"
                        + " (already " + already.stripTrailingZeros().toPlainString() + " of "
                        + dec(o.get("total")).stripTrailingZeros().toPlainString() + ")");
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
            link(orderId, null, str(doc.get("id")), "down_payment", BigDecimal.ZERO, amount, null);
        } else if ("regular".equals(mode)) {
            // Phase A A4 lock order: sales order (above) → its delivery orders; Fleet must hold no goods on them.
            if (!legacyInvoices(orderId, deliveryInvoicing.assertSalesGoods(orderId)).isEmpty()) {
                throw ApiException.conflict("Sales order " + number + " has historical Fleet invoices awaiting"
                        + " reconciliation");
            }
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
                        str(l.get("uomId")), null, dec(it.get("quantity")).compareTo(dec(l.get("quantity"))) == 0
                                ? ProductSizingService.Sizing.stored(l) : null)
                        // Phase I2 U-5: the invoice keeps the order line's frozen unit factor.
                        .withUnitSnapshot(str(l.get("uomId")), l.get("uomFactor") == null ? null
                                : dec(l.get("uomFactor"))));
            }
            // ADR-S094 C1b (D-VAT-1): taxed down payments are deducted on this invoice, with their VAT.
            List<Deduction> deductions = deductions(o, items);
            deductions.forEach(d -> lines.add(d.line()));
            doc = create(o, issue, lines, "Sales order " + number);
            String docId = str(doc.get("id"));
            invoices.linkSource(docId, "sales_order", orderId, false);
            if (goods) {
                invoices.setStockIssuedByDelivery(docId, number);
            }
            List<Map<String, Object>> docLines = db.rows("SELECT id, total FROM \"DocumentLine\" WHERE \"documentId\" = ?"
                    + " ORDER BY \"lineNumber\", id", docId);
            boolean aligned = docLines.size() == items.size() + deductions.size();
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> it = items.get(i);
                link(orderId, str(it.get("lineId")), docId, "regular", dec(it.get("quantity")), dec(it.get("amount")),
                        aligned ? str(docLines.get(i).get("id")) : null);
            }
            if (!deductions.isEmpty()) {
                if (!aligned) {
                    throw new IllegalStateException("Invoice lines do not match the sales order lines and deductions");
                }
                for (int j = 0; j < deductions.size(); j++) {
                    Map<String, Object> dl = docLines.get(items.size() + j);
                    db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"documentId\", kind,"
                            + " quantity, amount, \"documentLineId\", \"downPaymentDocumentId\") VALUES (?, ?, ?, ?,"
                            + " 'deduction', 0, ?, ?, ?)", SalesDb.newId(), org(), orderId, docId,
                            dec(dl.get("total")).negate(), str(dl.get("id")), deductions.get(j).downPaymentId());
                }
                if (dec(invoices.findOne(docId).get("totalAmount")).signum() < 0) {
                    throw ApiException.badRequest("The down payments to deduct exceed what sales order " + number
                            + " invoices now");
                }
            }
            deliveryInvoicing.markGoodsInvoiced("sales_order", orderId, docId, str(doc.get("documentNumber")));
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
            // Phase A A4: an order blocked by a historical Fleet invoice waits in the review queue.
            if (!toInvoice(orders.require(id)).isEmpty() && !legacyBlocked(id)) {
                out.add(createInvoice(id, Obj.of("mode", "regular", "issueDate", in.get("issueDate"))));
            }
        }
        return out;
    }

    private Map<String, Object> create(Map<String, Object> o, String issue, List<LineInput> lines, String notes) {
        Map<String, Object> site = o.get("siteId") == null ? null : db.row("SELECT name, address, city, \"projectId\""
                + " FROM \"FleetCustomerSite\" WHERE id = ? AND \"organizationId\" = ?", o.get("siteId"), org());
        String address = site != null ? String.join(", ", java.util.stream.Stream.of(site.get("name"), site.get(
                "address"), site.get("city")).filter(x -> x != null).map(Object::toString).toList())
                : str(o.get("deliveryAddress"));
        // The order's prices are in its currency, so its invoice must be too.
        Map<String, Object> currency = db.row("SELECT c.id FROM \"Currency\" c JOIN \"Organization\" g ON g.id = ?"
                + " WHERE c.code = ? AND c.code <> g.\"currencyCode\"", org(), o.get("currencyCode"));
        String currencyId = currency == null ? null : str(currency.get("id"));
        BigDecimal rate = currencyId == null ? null : dec(o.get("exchangeRate"));
        Map<String, Object> inv = invoices.createCopied(new InvoiceInput(str(o.get("partnerId")), issue, null,
                str(o.get("paymentTermId")), null, null, null, str(o.get("ownerId")), null, address,
                str(o.get("incoterm")), null, str(o.get("number")), currencyId, rate, str(o.get("customerRef")), notes,
                lines));
        // ADR-S075: an invoice to a customer site carries the site and its project.
        invoices.setSiteReference(str(inv.get("id")), str(o.get("siteId")), site == null ? null
                : str(site.get("projectId")));
        return inv;
    }

    /** ADR-S094 C1b: one negative line deducting a taxed down payment, and the down-payment invoice it deducts. */
    private record Deduction(String downPaymentId, LineInput line) {}

    /** Live deductions and posted deposit-account credit notes of down-payment invoice {@code d.id} (net amounts). */
    private static final String DP_USED = """
            COALESCE((SELECT sum(-x.subtotal) FROM "SalesInvoiceLink" j JOIN "Document" e ON e.id = j."documentId"
                       JOIN "DocumentLine" x ON x.id = j."documentLineId"
                      WHERE j."downPaymentDocumentId" = d.id AND j.kind = 'deduction'
                        AND e.status::text NOT IN ('cancelled', 'rejected')), 0) AS deducted,
            COALESCE((SELECT sum(y.subtotal) FROM "Document" c JOIN "DocumentLine" y ON y."documentId" = c.id
                      WHERE c."reversedDocumentId" = d.id AND c."documentType" = 'credit_note'
                        AND c.status::text IN ('posted', 'paid') AND y."accountId" = ?), 0) AS credited""";

    /**
     * ADR-S094 C1b (D-VAT-1, AD-14 pro-rata, AD-17): the taxed down payments of the order this regular invoice deducts.
     * A down payment whose deposit line carries a tax ({@code depositTaxPoint = receipt}) already charged the VAT on the
     * advance, so the final invoice deducts it — on the deposit account, with the same tax — instead of applying the
     * deposit, which would charge that VAT a second time. Each invoice deducts, of every down payment, what is left of
     * it × this invoice's net / the order's net not yet invoiced (AD-14); the invoice that leaves nothing more to invoice
     * deducts all that is left. The whole down payment at once is the down payment's own line negated, so its net and VAT
     * come back exactly as charged; a part is a tax-exclusive line for that net, with the same tax.
     */
    private List<Deduction> deductions(Map<String, Object> o, List<Map<String, Object>> items) {
        String orderId = str(o.get("id"));
        String account = deposits.depositAccount();
        if (db.count("SELECT count(*) FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\" JOIN"
                + " \"DocumentLine\" dl ON dl.\"documentId\" = d.id AND dl.\"accountId\" = ? AND dl.\"taxId\" IS NOT NULL"
                + " WHERE k.\"orderId\" = ? AND k.\"organizationId\" = ? AND k.kind = 'down_payment' AND d.status::text ="
                + " 'draft'", List.of(account, orderId, org())) > 0) {
            throw ApiException.badRequest("A down-payment invoice of sales order " + o.get("number") + " is still a"
                    + " draft; post or cancel it before invoicing the order");
        }
        List<Map<String, Object>> dps = db.rows("SELECT d.id, d.\"documentNumber\", dl.\"taxId\", dl.\"taxInclusive\","
                + " dl.quantity, dl.\"unitPrice\", dl.subtotal, " + DP_USED + " FROM \"SalesInvoiceLink\" k JOIN \"Document\" d"
                + " ON d.id = k.\"documentId\" JOIN \"DocumentLine\" dl ON dl.\"documentId\" = d.id AND dl.\"accountId\" = ? AND"
                + " dl.\"taxId\" IS NOT NULL WHERE k.\"orderId\" = ? AND k.\"organizationId\" = ? AND k.kind = 'down_payment'"
                + " AND d.status::text IN ('posted', 'paid') ORDER BY d.\"postedAt\", d.id", account, account, orderId, org());
        if (dps.isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> now = new java.util.HashMap<>();
        for (Map<String, Object> it : items) {
            now.merge(str(it.get("lineId")), dec(it.get("quantity")), BigDecimal::add);
        }
        // AD-14: net of this invoice's goods, and net of the order not yet invoiced before it (live invoices).
        boolean last = true;
        BigDecimal invoiceNet = BigDecimal.ZERO;
        BigDecimal remainingNet = BigDecimal.ZERO;
        for (Map<String, Object> l : db.rows("""
                SELECT l.id, l.quantity, l."qtyCancelled", l."lineSubtotal",
                       COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k JOIN "Document" d ON d.id = k."documentId"
                                  WHERE k."orderLineId" = l.id AND k.kind = 'regular'
                                    AND d.status::text NOT IN ('cancelled', 'rejected')), 0) AS linked
                  FROM "SalesOrderLine" l WHERE l."orderId" = ?""", orderId)) {
            BigDecimal qty = dec(l.get("quantity"));
            BigDecimal open = qty.subtract(dec(l.get("qtyCancelled"))).max(BigDecimal.ZERO);
            BigDecimal linked = dec(l.get("linked"));
            BigDecimal here = now.getOrDefault(str(l.get("id")), BigDecimal.ZERO);
            if (linked.add(here).compareTo(open) < 0) {
                last = false;
            }
            if (qty.signum() > 0) {
                BigDecimal unitNet = dec(l.get("lineSubtotal")).divide(qty, 10, RoundingMode.HALF_UP);
                invoiceNet = invoiceNet.add(unitNet.multiply(here));
                remainingNet = remainingNet.add(unitNet.multiply(open.subtract(linked).max(here)));
            }
        }
        Map<String, Object> currency = db.row("SELECT \"decimalPlaces\" FROM \"Currency\" WHERE code = ?",
                o.get("currencyCode"));
        int scale = currency == null || currency.get("decimalPlaces") == null ? 2
                : ((Number) currency.get("decimalPlaces")).intValue();
        List<Deduction> out = new ArrayList<>();
        for (Map<String, Object> dp : dps) {
            BigDecimal net = dec(dp.get("subtotal"));
            BigDecimal used = dec(dp.get("deducted")).add(dec(dp.get("credited")));
            BigDecimal remaining = net.subtract(used);
            if (remaining.signum() <= 0) {
                continue;
            }
            BigDecimal share = last || remainingNet.signum() <= 0 || invoiceNet.compareTo(remainingNet) >= 0 ? remaining
                    : remaining.multiply(invoiceNet).divide(remainingNet, scale, RoundingMode.HALF_UP).min(remaining);
            if (share.signum() <= 0) {
                continue;
            }
            String description = "Deduction of down payment " + dp.get("documentNumber");
            String taxId = str(dp.get("taxId"));
            LineInput line = used.signum() == 0 && share.compareTo(net) == 0
                    ? new LineInput(null, null, null, null, account, description, dec(dp.get("quantity")).negate(),
                            dec(dp.get("unitPrice")), BigDecimal.ZERO, null, null, null, "manual", taxId, null,
                            Boolean.TRUE.equals(dp.get("taxInclusive")), null)
                    : new LineInput(null, null, null, null, account, description, BigDecimal.ONE.negate(), share,
                            BigDecimal.ZERO, null, null, null, "manual", taxId, null, false, null);
            out.add(new Deduction(str(dp.get("id")), line));
        }
        return out;
    }

    /**
     * ADR-S094 C1b, inside the posting transaction ({@link SalesInvoicePostGuard#afterPost}): a posted down-payment
     * invoice registers its deposit now (its event does it again, idempotently), and a posted regular invoice consumes
     * the deposits of the down payments it deducts.
     */
    @Transactional
    public void afterPost(String documentId) {
        if (db.count("SELECT count(*) FROM \"SalesInvoiceLink\" WHERE \"documentId\" = ? AND \"organizationId\" = ? AND"
                + " kind = 'down_payment'", List.of(documentId, org())) > 0) {
            deposits.registerDownPayment(documentId);
        }
        for (Map<String, Object> k : db.rows("SELECT k.\"downPaymentDocumentId\" AS dp, -dl.subtotal AS net FROM"
                + " \"SalesInvoiceLink\" k JOIN \"DocumentLine\" dl ON dl.id = k.\"documentLineId\" WHERE k.\"documentId\" = ?"
                + " AND k.\"organizationId\" = ? AND k.kind = 'deduction' ORDER BY k.\"downPaymentDocumentId\"", documentId,
                org())) {
            deposits.consume(str(k.get("dp")), documentId, dec(k.get("net")), "deduction");
        }
    }

    /**
     * ADR-S094 C1b post-time recheck: each deduction line is still the down payment's own (deposit account, its tax,
     * negative, the linked amount), its down-payment invoice is still posted, and with the other live deductions and the
     * credit notes on it no down payment is deducted beyond its net amount.
     */
    List<String> deductionProblems(String documentId) {
        List<String> out = new ArrayList<>();
        String account = deposits.depositAccount();
        for (Map<String, Object> r : db.rows("SELECT k.amount, d.\"documentNumber\" AS dp, d.status::text AS \"dpStatus\","
                + " dpl.\"taxId\" AS \"dpTax\", dpl.subtotal AS \"dpNet\", dl.id AS \"dlId\", dl.\"accountId\","
                + " dl.\"productId\", dl.quantity, dl.\"taxId\", dl.total, " + DP_USED + " FROM \"SalesInvoiceLink\" k JOIN"
                + " \"Document\" d ON d.id = k.\"downPaymentDocumentId\" LEFT JOIN \"DocumentLine\" dpl ON dpl.\"documentId\" ="
                + " d.id AND dpl.\"accountId\" = ? LEFT JOIN \"DocumentLine\" dl ON dl.id = k.\"documentLineId\" AND"
                + " dl.\"documentId\" = k.\"documentId\" WHERE k.\"documentId\" = ? AND k.\"organizationId\" = ? AND k.kind ="
                + " 'deduction'", account, account, documentId, org())) {
            if (!List.of("posted", "paid").contains(str(r.get("dpStatus")))) {
                out.add("Down-payment invoice " + r.get("dp") + " deducted on this invoice is " + r.get("dpStatus"));
            } else if (r.get("dlId") == null || r.get("productId") != null || !account.equals(r.get("accountId"))
                    || dec(r.get("quantity")).signum() >= 0 || !java.util.Objects.equals(r.get("taxId"), r.get("dpTax"))
                    || dec(r.get("total")).add(dec(r.get("amount"))).abs().compareTo(CENT) > 0) {
                out.add("The deduction of down-payment invoice " + r.get("dp") + " no longer matches its line");
            } else if (dec(r.get("deducted")).add(dec(r.get("credited"))).subtract(dec(r.get("dpNet"))).compareTo(CENT) > 0) {
                out.add("Down-payment invoice " + r.get("dp") + " would be deducted beyond its amount");
            }
        }
        return out;
    }

    private void link(String orderId, String lineId, String documentId, String kind, BigDecimal qty, BigDecimal amount,
            String documentLineId) {
        db.update("INSERT INTO \"SalesInvoiceLink\" (id, \"organizationId\", \"orderId\", \"orderLineId\", \"documentId\","
                + " kind, quantity, amount, \"documentLineId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", SalesDb.newId(), org(),
                orderId, lineId, documentId, kind, qty, amount, documentLineId);
    }

    /**
     * Phase A A4 post-time recheck (plan §5): for every order line a sales-order invoice carries, the live regular links
     * (drafts and posted, any origin) must not exceed what the line may be invoiced for. Returns the problems.
     */
    @Transactional(readOnly = true)
    public List<String> postProblems(String documentId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> l : db.rows("""
                SELECT l.*, p."invoicePolicy", o."billingPolicy", o.number AS "orderNumber",
                       COALESCE((SELECT sum(k2.quantity) FROM "SalesInvoiceLink" k2 JOIN "Document" d2 ON d2.id = k2."documentId"
                                  WHERE k2."orderLineId" = l.id AND k2.kind = 'regular'
                                    AND d2.status::text NOT IN ('cancelled', 'rejected')), 0) AS linked
                  FROM "SalesInvoiceLink" k
                  JOIN "SalesOrderLine" l ON l.id = k."orderLineId"
                  JOIN "SalesOrder" o ON o.id = l."orderId"
                  LEFT JOIN "Product" p ON p.id = l."productId"
                 WHERE k."documentId" = ? AND k."organizationId" = ? AND k.kind = 'regular' AND k.origin = 'sales'
                """, documentId, org())) {
            BigDecimal base = base(policy(str(l.get("billingPolicy")), l), l);
            BigDecimal linked = dec(l.get("linked"));
            if (linked.compareTo(base) > 0) {
                out.add("Sales order " + l.get("orderNumber") + " line \"" + l.get("description") + "\" is invoiced for "
                        + linked.stripTrailingZeros().toPlainString() + " but only "
                        + base.stripTrailingZeros().toPlainString() + " can be invoiced");
            }
        }
        out.addAll(lineProblems(documentId));
        out.addAll(downPaymentProblems(documentId));
        out.addAll(deductionProblems(documentId));
        return out;
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");

    /**
     * Sales readiness R1 (B1): every line of a sales-order invoice must be exactly the commercial line it was generated
     * from — same order line, product, variant, unit and factor, the linked quantity, the order line's price, discount
     * and tax, and the linked amount — and the invoice may carry no line its links do not account for. The links are the
     * allocation the caps were checked on; a document line that drifted from them would bill what nobody authorised.
     */
    List<String> lineProblems(String documentId) {
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> rows = db.rows("""
                SELECT k."documentLineId", k.quantity AS "linkQty", k.amount AS "linkAmount",
                       o.number AS "orderNumber", l.description, l."productId" AS "oProduct", l."variantId" AS "oVariant",
                       l."uomId" AS "oUom", l."uomFactor" AS "oFactor", l."unitPrice" AS "oPrice",
                       l."discountPercent" AS "oDiscount", l."taxId" AS "oTax", l."taxInclusive" AS "oInclusive",
                       dl.id AS "dlId", dl."productId" AS "dProduct", dl."variantId" AS "dVariant", dl."uomId" AS "dUom",
                       dl."uomFactor" AS "dFactor", dl.quantity AS "dQty", dl."unitPrice" AS "dPrice",
                       dl."discountPercent" AS "dDiscount", dl."taxId" AS "dTax", dl."taxInclusive" AS "dInclusive",
                       dl.total AS "dTotal"
                  FROM "SalesInvoiceLink" k
                  JOIN "SalesOrderLine" l ON l.id = k."orderLineId"
                  JOIN "SalesOrder" o ON o.id = k."orderId"
                  LEFT JOIN "DocumentLine" dl ON dl.id = k."documentLineId" AND dl."documentId" = k."documentId"
                 WHERE k."documentId" = ? AND k."organizationId" = ? AND k.kind = 'regular' AND k.origin = 'sales'
                """, documentId, org());
        if (rows.isEmpty()) {
            return out;
        }
        java.util.Set<Object> linkedLines = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            String where = "Sales order " + r.get("orderNumber") + " line \"" + r.get("description") + "\"";
            if (r.get("dlId") == null) {
                out.add(where + " has no matching invoice line");
                continue;
            }
            linkedLines.add(r.get("dlId"));
            List<String> diff = new ArrayList<>();
            if (!java.util.Objects.equals(r.get("oProduct"), r.get("dProduct"))) {
                diff.add("product");
            }
            if (!java.util.Objects.equals(r.get("oVariant"), r.get("dVariant"))) {
                diff.add("variant");
            }
            if (!java.util.Objects.equals(r.get("oUom"), r.get("dUom")) || !sameOrNull(r.get("oFactor"), r.get("dFactor"))) {
                diff.add("unit");
            }
            if (dec(r.get("linkQty")).compareTo(dec(r.get("dQty"))) != 0) {
                diff.add("quantity");
            }
            if (dec(r.get("oPrice")).compareTo(dec(r.get("dPrice"))) != 0) {
                diff.add("unit price");
            }
            if (dec(r.get("oDiscount")).compareTo(dec(r.get("dDiscount"))) != 0) {
                diff.add("discount");
            }
            if (!java.util.Objects.equals(r.get("oTax"), r.get("dTax"))
                    || !java.util.Objects.equals(r.get("oInclusive"), r.get("dInclusive"))) {
                diff.add("tax");
            }
            if (diff.isEmpty() && dec(r.get("linkAmount")).subtract(dec(r.get("dTotal"))).abs().compareTo(CENT) > 0) {
                diff.add("amount");
            }
            if (!diff.isEmpty()) {
                out.add(where + " differs from the invoice line (" + String.join(", ", diff) + ")");
            }
        }
        // ADR-S094 C1b: a deduction line is accounted for by its deduction link (checked in deductionProblems).
        long lines = db.count("SELECT count(*) FROM \"DocumentLine\" dl WHERE dl.\"documentId\" = ? AND dl.\"organizationId\" = ?"
                + " AND NOT EXISTS (SELECT 1 FROM \"SalesInvoiceLink\" j WHERE j.\"documentLineId\" = dl.id AND j.kind ="
                + " 'deduction')", List.of(documentId, org()));
        if (lines > linkedLines.size()) {
            out.add("The invoice carries " + (lines - linkedLines.size()) + " line(s) that no sales order line accounts for");
        }
        return out;
    }

    /** A down-payment invoice is one deposit line for exactly the amount the order's down-payment link records. */
    List<String> downPaymentProblems(String documentId) {
        Map<String, Object> k = db.row("SELECT sum(amount) AS amount, count(*) AS n FROM \"SalesInvoiceLink\" WHERE"
                + " \"documentId\" = ? AND \"organizationId\" = ? AND kind = 'down_payment'", documentId, org());
        if (k == null || ((Number) k.get("n")).longValue() == 0) {
            return List.of();
        }
        Map<String, Object> d = db.row("SELECT \"totalAmount\", (SELECT count(*) FROM \"DocumentLine\" x WHERE"
                + " x.\"documentId\" = d.id) AS lines, (SELECT count(*) FROM \"DocumentLine\" x WHERE x.\"documentId\" = d.id"
                + " AND (x.\"productId\" IS NOT NULL OR x.\"accountId\" IS DISTINCT FROM ?)) AS others FROM \"Document\" d"
                + " WHERE d.id = ? AND d.\"organizationId\" = ?", deposits.depositAccount(), documentId, org());
        if (d == null || ((Number) d.get("lines")).longValue() != 1 || ((Number) d.get("others")).longValue() != 0
                || dec(d.get("totalAmount")).subtract(dec(k.get("amount"))).abs().compareTo(CENT) > 0) {
            return List.of("The down-payment invoice no longer matches the down payment recorded on its sales order");
        }
        return List.of();
    }

    private static boolean sameOrNull(Object a, Object b) {
        return a == null || b == null || dec(a).compareTo(dec(b)) == 0;
    }

    /**
     * Phase A A4 (plan §13): a shortage or weight claim confirmed after the goods were invoiced. A draft credit note
     * for min(confirmed, gross invoiced − credited − invoiceable) of the order line, against the latest posted invoice
     * that carried it; the credit link makes a replay credit nothing more. Null when nothing is over-invoiced.
     */
    @Transactional
    public Map<String, Object> lateDisputeCredit(String orderId, String discrepancyId) {
        Map<String, Object> x = db.row("SELECT x.code, x.kind, x.status, x.\"differenceQty\", x.\"reconciledQty\","
                + " dl.\"sourceLineId\" AS \"orderLineId\" FROM \"FleetDeliveryDiscrepancy\" x JOIN \"FleetLoadLine\" ll ON"
                + " ll.id = x.\"loadLineId\" JOIN \"FleetDeliveryOrderLine\" dl ON dl.id = ll.\"deliveryOrderLineId\" WHERE"
                + " x.id = ? AND x.\"organizationId\" = ?", discrepancyId, org());
        if (x == null || !"approved".equals(x.get("status")) || x.get("orderLineId") == null
                || !List.of("shortage", "weight").contains(str(x.get("kind")))) {
            return null;
        }
        Map<String, Object> o = orders.lock(orderId);
        String lineId = str(x.get("orderLineId"));
        Map<String, Object> l = db.row("""
                SELECT l.*, c."incomeAccountId", p."invoicePolicy", o."billingPolicy",
                       COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k JOIN "Document" d ON d.id = k."documentId"
                                  WHERE k."orderLineId" = l.id AND k.kind = 'credit_note'
                                    AND d.status::text NOT IN ('cancelled', 'rejected')), 0) AS credited
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId" LEFT JOIN "Product" p ON p.id = l."productId"
                  LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
                 WHERE l.id = ? AND l."orderId" = ? FOR UPDATE OF l""", lineId, orderId);
        if (l == null) {
            return null;
        }
        BigDecimal confirmed = dec(x.get("differenceQty")).subtract(dec(x.get("reconciledQty")));
        // Over-invoiced against what the line may invoice: billable for accepted / delivered billing; an ordered-policy
        // line invoices its ordered quantity, so a delivery claim never auto-credits it (returns and credit notes stay
        // the route there, owner decision 2026-11-16).
        BigDecimal base = base(policy(str(l.get("billingPolicy")), l), l);
        BigDecimal over = dec(l.get("qtyInvoiced")).subtract(dec(l.get("credited"))).subtract(base);
        BigDecimal qty = confirmed.min(over);
        if (qty.signum() <= 0 || dec(l.get("unitPrice")).signum() <= 0) {
            return null;
        }
        Map<String, Object> inv = db.row("SELECT d.id, d.\"documentNumber\" FROM \"SalesInvoiceLink\" k JOIN \"Document\" d"
                + " ON d.id = k.\"documentId\" WHERE k.\"orderLineId\" = ? AND k.kind = 'regular' AND d.status::text IN"
                + " ('posted', 'paid') ORDER BY d.\"createdAt\" DESC LIMIT 1", lineId);
        if (inv == null) {
            return null;
        }
        String account = l.get("incomeAccountId") != null ? str(l.get("incomeAccountId"))
                : str(settings.settings().get("salesReturnsAccountId"));
        Map<String, Object> cn = creditNotes.create(new CreditNoteInput(str(o.get("partnerId")), LocalDate.now(BusinessZone.get())
                .toString(), null, str(x.get("code")), "Delivery " + ("weight".equals(x.get("kind")) ? "weight variance"
                        : "shortage") + " " + x.get("code") + " confirmed after invoice " + inv.get("documentNumber")
                        + " of sales order " + o.get("number") + ". The goods did not arrive: the line carries no"
                        + " product, so posting does not move stock.", str(inv.get("id")),
                List.of(new LineInput(null, null, null, null, account, "Delivery " + x.get("kind") + " " + x.get("code")
                        + ": " + l.get("description"), qty, dec(l.get("unitPrice")), dec(l.get("discountPercent")), null,
                        null, null, "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive")),
                        null))));
        link(orderId, lineId, str(cn.get("id")), "credit_note", qty, BigDecimal.ZERO, null);
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("lateDisputeCredit",
                cn.get("documentNumber"), "discrepancy", x.get("code"), "quantity", qty)));
        return cn;
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
        // IPR LK-3: the sales order before its lines, the order every other sales path uses.
        db.row("SELECT id FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", orderId, org());
        db.update("""
                UPDATE "SalesOrderLine" l SET "qtyInvoiced" = COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k
                       JOIN "Document" d ON d.id = k."documentId" WHERE k."orderLineId" = l.id AND k.kind = 'regular'
                        AND d.status::text IN ('posted', 'paid')), 0)
                 WHERE l."orderId" = ?""", orderId);
        orders.refreshStatus(orderId);
    }

    /**
     * Phase A A4 (plan §10) financial reconciliation of Sales-owned goods. Per order line: gross invoiced − credited ≤
     * billable (accepted, or manual decision); credit notes ≤ gross invoiced; one regular link per invoice and line;
     * plus the historical Fleet invoices awaiting review. Records a {@code SalesReconciliation} run (kind
     * {@code financial}); changes nothing else.
     */
    @Transactional
    public Map<String, Object> financialReconcile() {
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> lines = db.rows("""
                SELECT o.id AS "orderId", o.number, l.id AS "lineId", l.description, l."qtyInvoiced", l."qtyCredited",
                       l."qtyBillable", p."invoicePolicy", o."billingPolicy", l.quantity, l."qtyCancelled"
                  FROM "SalesOrderLine" l JOIN "SalesOrder" o ON o.id = l."orderId"
                  LEFT JOIN "Product" p ON p.id = l."productId"
                 WHERE o."organizationId" = ? AND o."deletedAt" IS NULL
                   AND o.status IN ('confirmed', 'in_fulfilment', 'done')""", org());
        java.util.Set<Object> orders = new java.util.HashSet<>();
        for (Map<String, Object> l : lines) {
            orders.add(l.get("orderId"));
            BigDecimal invoiced = dec(l.get("qtyInvoiced"));
            BigDecimal credited = dec(l.get("qtyCredited"));
            BigDecimal base = base(policy(str(l.get("billingPolicy")), l), l);
            if (invoiced.subtract(credited).compareTo(base) > 0) {
                findings.add(Obj.of("rule", "over_invoiced", "orderId", l.get("orderId"), "orderNumber", l.get("number"),
                        "orderLineId", l.get("lineId"), "expected", JsNumbers.js(base), "actual",
                        JsNumbers.js(invoiced.subtract(credited)), "message", "Over-invoiced "
                                + invoiced.subtract(credited).subtract(base).stripTrailingZeros().toPlainString()
                                + " on " + l.get("number") + " / " + l.get("description")));
            }
            if (credited.compareTo(invoiced) > 0) {
                findings.add(Obj.of("rule", "credit_exceeds_invoiced", "orderId", l.get("orderId"), "orderNumber",
                        l.get("number"), "orderLineId", l.get("lineId"), "expected", JsNumbers.js(invoiced), "actual",
                        JsNumbers.js(credited), "message", "Credited more than invoiced on " + l.get("number")));
            }
        }
        for (Map<String, Object> d : db.rows("SELECT \"documentId\", \"orderLineId\", count(*) AS n FROM \"SalesInvoiceLink\""
                + " WHERE \"organizationId\" = ? AND kind = 'regular' AND \"orderLineId\" IS NOT NULL GROUP BY 1, 2 HAVING"
                + " count(*) > 1", org())) {
            findings.add(Obj.of("rule", "duplicate_regular_link", "documentId", d.get("documentId"), "orderLineId",
                    d.get("orderLineId"), "actual", d.get("n"), "message", "Several regular links of one invoice and"
                            + " order line"));
        }
        for (Map<String, Object> g : db.rows("""
                SELECT f."sourceId" AS "orderId", f.code, f."goodsInvoiceId" AS "documentId"
                  FROM "FleetDeliveryOrder" f JOIN "Document" d ON d.id = f."goodsInvoiceId"
                 WHERE f."organizationId" = ? AND f."sourceType" = 'sales_order'
                   AND d.status::text NOT IN ('cancelled', 'rejected')
                   AND NOT EXISTS (SELECT 1 FROM "SalesInvoiceLink" k WHERE k."orderId" = f."sourceId"
                                     AND k."documentId" = d.id AND k.origin = 'sales')
                   AND NOT EXISTS (SELECT 1 FROM "SalesLegacyInvoiceReview" r WHERE r."orderId" = f."sourceId"
                                     AND r."documentId" = d.id AND r.status = 'reconciled')""", org())) {
            findings.add(Obj.of("rule", "legacy_fleet_invoice_unreconciled", "orderId", g.get("orderId"), "documentId",
                    g.get("documentId"), "message", "Historical Fleet goods invoice on delivery order " + g.get("code")
                            + " awaits reconciliation"));
        }
        String id = SalesDb.newId();
        db.update("INSERT INTO \"SalesReconciliation\" (id, \"organizationId\", \"ordersChecked\", \"linesDrifted\","
                + " details, kind) VALUES (?, ?, ?, ?, ?::jsonb, 'financial')", id, org(), orders.size(), findings.size(),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Obj.of("findings",
                        findings)));
        return Obj.of("id", id, "kind", "financial", "ordersChecked", orders.size(), "findings", findings);
    }

    /** Apply the order's open deposits, oldest first, to a posted invoice (stops when the invoice is settled). */
    @Transactional
    public List<Map<String, Object>> applyDeposits(String orderId, String documentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> inv = db.row("SELECT upper(coalesce(NULLIF(d.\"currencyId\", ''), o.\"currencyCode\")) AS ccy"
                + " FROM \"Document\" d JOIN \"Organization\" o ON o.id = d.\"organizationId\" WHERE d.id = ?", documentId);
        String invCurrency = inv == null ? null : str(inv.get("ccy"));
        for (Map<String, Object> d : deposits.list(null, orderId, "open")) {
            // ADR-S094 C1b: a taxed down payment is deducted on the invoice itself, never applied
            if (deposits.isTaxedDownPayment(d)) {
                continue;
            }
            // ADR-S080: only deposits in the invoice's currency settle it (the list fills in the functional code)
            if (invCurrency != null && !invCurrency.equalsIgnoreCase(str(d.get("currency")))) {
                continue;
            }
            Map<String, Object> doc = db.row("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?"
                    + " AND \"organizationId\" = ?", documentId, org());
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
