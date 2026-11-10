package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.JsFormat.padEnd;
import static com.inventoryerp.pos.application.JsFormat.padStart;
import static com.inventoryerp.pos.application.JsFormat.slice;
import static com.inventoryerp.pos.application.JsFormat.ugx;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.invoicing.domain.InvoiceItem;
import com.inventoryerp.invoicing.domain.InvoiceItemModifier;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.pos.domain.PosTable;
import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receipts, bills, KOTs and their printing (port of {@code pos-receipts.service.ts}). Text layouts are reproduced
 * column for column; ESC/POS output is the CP437-safe ASCII text + {@code ESC d n} feed + {@code GS V 0} cut, sent
 * over TCP 9100 or the Windows spooler (RAW). The PDF receipt is rendered with OpenPDF (Nest: pdfkit).
 */
@Service
public class PosReceiptsService {

    private static final Logger log = LoggerFactory.getLogger(PosReceiptsService.class);
    private static final int TICKET_W = 48;
    static final List<String> RECEIPT_PAYMENT_MODES = List.of("cash", "card", "mobile_money", "mixed", "credit");
    static final List<String> RECEIPT_SETTLEMENT_STATUSES = List.of("unsettled", "partially_settled", "settled",
            "written_off");
    private static final Map<String, String> ORDER_TYPE_LABELS = Map.of("dine_in", "Dine In", "takeaway", "Take Away",
            "delivery", "Delivery");

    /** Printer target ({@code kind}: tcp | windows | none). */
    public record Target(String kind, String host, int port, String printerName, String backend) {}

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationsService notifications;
    private final SettingsService settings;
    private final PosPrintLifecycleService printLifecycle;
    private final TransactionTemplate tx;

    public PosReceiptsService(PosDb db, AuditService audit, NotificationsService notifications, SettingsService settings,
            PosPrintLifecycleService printLifecycle, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.notifications = notifications;
        this.settings = settings;
        this.printLifecycle = printLifecycle;
        this.tx = new TransactionTemplate(txm);
    }

    public PosPrintLifecycleService lifecycle() {
        return printLifecycle;
    }

    /** ESC/POS printers speak CP437: map common Unicode to ASCII. */
    public static String toPrinterAscii(String s) {
        return s.replaceAll("[—–]", "-").replaceAll("[’‘]", "'").replaceAll("[“”]", "\"").replace("…", "...")
                .replace("×", "x").replaceAll("[^\\x00-\\x7F]", "?");
    }

    /** Invoice.subtotal is after discount: reconstruct the pre-discount subtotal. */
    private static double receiptSubtotal(Map<String, Object> inv) {
        return num(inv.get("subtotal")) + num(inv.get("discountTotal"));
    }

    // ─────────────────────────── resolution ───────────────────────────

    /** Invoice (with items, modifiers, order type, partner, cashier, table) or the open-tab Order fallback. */
    public Map<String, Object> resolveInvoice(String invoiceId) {
        String orgId = org();
        Map<String, Object> inv = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
        if (inv != null) {
            List<Map<String, Object>> items = db.list(InvoiceItem.class, "\"invoiceId\" = ? ORDER BY \"lineNumber\" ASC",
                    invoiceId);
            PosModifiers.attach(jdbc, items, InvoiceItemModifier.class, "InvoiceItemModifier", "invoiceItemId");
            inv.put("items", items);
            String orderType = jdbc.sql("SELECT \"orderType\"::text FROM \"Order\" WHERE \"invoiceId\" = ? LIMIT 1")
                    .param(invoiceId).query(String.class).optional().orElse(null);
            boolean hasOrder = jdbc.sql("SELECT count(*) FROM \"Order\" WHERE \"invoiceId\" = ?").param(invoiceId)
                    .query(Long.class).single() > 0;
            inv.put("order", hasOrder ? Obj.of("orderType", orderType) : null);
            Map<String, Object> partner = db.first(Partner.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                    inv.get("partnerId"), orgId);
            Map<String, Map<String, Object>> products = productsFor(items);
            Object createdBy = inv.get("createdBy") != null ? inv.get("createdBy") : inv.get("postedBy");
            String cashierName = resolveUserName((String) createdBy);
            Map<String, Object> table = inv.get("tableId") == null ? null
                    : db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", inv.get("tableId"), orgId);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> ln : items) {
                Map<String, Object> l = new LinkedHashMap<>(ln);
                l.put("product", ln.get("productId") == null ? null : products.get((String) ln.get("productId")));
                lines.add(l);
            }
            inv.put("documentNumber", inv.get("invoiceNumber"));
            inv.put("partner", partner);
            inv.put("cashierName", cashierName);
            inv.put("lines", lines);
            inv.put("orderType", orderType);
            inv.put("tableName", table == null ? null : table.get("name"));
            return inv;
        }
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
        if (order == null) {
            throw ApiException.notFound("Invoice not found");
        }
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", invoiceId);
        PosModifiers.attach(jdbc, items, OrderItemModifier.class, "OrderItemModifier", "orderItemId");
        order.put("items", items);
        // Prisma drops an undefined id filter: with no partner the first partner of the org answers.
        Map<String, Object> partner = order.get("partnerId") == null
                ? db.first(Partner.class, "\"organizationId\" = ? AND \"deletedAt\" IS NULL", orgId)
                : db.first(Partner.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                        order.get("partnerId"), orgId);
        Map<String, Map<String, Object>> products = productsFor(items);
        Map<String, Object> table = order.get("tableId") == null ? null
                : db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", order.get("tableId"), orgId);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> ln : items) {
            Map<String, Object> l = new LinkedHashMap<>(ln);
            double qty = num(ln.get("quantity"));
            double unit = num(ln.get("unitPrice"));
            double disc = num(ln.get("discountPercent"));
            l.put("total", PosSql.js(qty * unit * (1 - disc / 100)));
            l.put("product", ln.get("productId") == null ? null : products.get((String) ln.get("productId")));
            lines.add(l);
        }
        Object createdBy = order.get("createdBy") != null ? order.get("createdBy") : order.get("waiterId");
        String cashierName = resolveUserName((String) createdBy);
        Map<String, Object> out = new LinkedHashMap<>(order);
        out.put("documentNumber", order.get("orderNumber"));
        out.put("issueDate", order.get("openedAt") != null ? order.get("openedAt") : order.get("createdAt"));
        out.put("amountPaid", 0);
        out.put("amountResidual", PosSql.js(order.get("totalAmount")));
        out.put("partner", partner);
        out.put("cashierName", cashierName);
        out.put("lines", lines);
        out.put("orderType", order.get("orderType"));
        out.put("tableName", table == null ? null : table.get("name"));
        return out;
    }

    private String resolveUserName(String userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> u = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\", email FROM \"User\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(userId, org()));
        if (u == null) {
            return null;
        }
        String name = ((u.get("firstName") == null ? "" : u.get("firstName")) + " "
                + (u.get("lastName") == null ? "" : u.get("lastName"))).trim();
        return !name.isEmpty() ? name : (String) u.get("email");
    }

    private Map<String, Map<String, Object>> productsFor(List<Map<String, Object>> items) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            if (it.get("productId") != null) {
                ids.add((String) it.get("productId"));
            }
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String id : ids) {
            Map<String, Object> p = db.first(Product.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                    id, org());
            if (p != null) {
                out.put(id, p);
            }
        }
        return out;
    }

    /** Org-level setting value; memoized for the request ({@link ReadMemo}) so one receipt render reads it once. */
    private Object settingValue(String key) {
        return ReadMemo.get(() -> Optional.ofNullable(settings.getValue(key)), "setting", org(), key).orElse(null);
    }

    /** {@code receipt.header} / {@code receipt.footer} setting values. */
    private Map<String, Object> header() {
        return PosSql.map(settingValue("receipt.header"));
    }

    private Map<String, Object> footer() {
        return PosSql.map(settingValue("receipt.footer"));
    }

    private static String h(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        return v == null ? fallback : v.toString();
    }

    /** Save the per-organization receipt customization. */
    public Map<String, Object> saveReceiptSettings(Map<String, Object> dto) {
        Map<String, Object> headerValue = Obj.of(
                "businessName", h(dto, "businessName", ""), "addressLine1", h(dto, "addressLine1", ""),
                "addressLine2", h(dto, "addressLine2", ""), "phone", h(dto, "phone", ""), "taxId", h(dto, "taxId", ""));
        Map<String, Object> footerValue = Obj.of("message", h(dto, "footerMessage", ""));
        tx.executeWithoutResult(st -> {
            settings.set("receipt.header", headerValue, null, null);
            settings.set("receipt.footer", footerValue, null, null);
        });
        return Obj.of("ok", true);
    }

    // ─────────────────────────── text builders ───────────────────────────

    private static Instant instant(Object v) {
        return v instanceof Instant i ? i : PosSql.date(String.valueOf(v));
    }

    private static String mode(Map<String, Object> inv) {
        Object m = inv.get("paymentMode");
        return (PosSql.truthy(m) ? m.toString() : "CASH").toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> inv) {
        return (List<Map<String, Object>>) inv.get("lines");
    }

    private static String customerName(Map<String, Object> inv) {
        Map<String, Object> p = PosSql.map(inv.get("partner"));
        return p == null ? null : (String) p.get("name");
    }

    /** Plain-text 48-column receipt. */
    public String buildTextReceipt(String invoiceId, boolean isReprint, String copyLabel) {
        return buildTextReceipt(resolveInvoice(invoiceId), isReprint, copyLabel);
    }

    /** Plain-text receipt from an already {@link #resolveInvoice resolved} invoice (read only). */
    public String buildTextReceipt(Map<String, Object> inv, boolean isReprint, String copyLabel) {
        Map<String, Object> header = header();
        Map<String, Object> footer = footer();
        List<String> lines = new ArrayList<>();
        boolean isMerchant = copyLabel != null && !copyLabel.isEmpty()
                && copyLabel.toUpperCase().matches(".*(CASHIER|MERCHANT).*");
        if (isReprint) {
            lines.add("*** REPRINT COPY ***");
        }
        if (copyLabel != null && !copyLabel.isEmpty()) {
            lines.add("*** " + copyLabel + " ***");
        }
        int r = 48;
        String rule = "=".repeat(r);
        lines.add(rule);
        lines.add(h(header, "businessName", "Abiliz Cafe and Patisserie").toUpperCase());
        lines.add("");
        lines.add(h(header, "addressLine1", "AFEE COMPLEX, KASANGA"));
        lines.add(h(header, "addressLine2", "Kampala, Uganda"));
        lines.add("Telephone: " + h(header, "phone", "+256757920771"));
        if (header != null && PosSql.truthy(header.get("taxId"))) {
            lines.add("TIN/VAT: " + header.get("taxId"));
        }
        lines.add("");
        lines.add("Receipt: #" + inv.get("documentNumber"));
        Instant d = instant(inv.get("issueDate"));
        lines.add("Date " + JsFormat.gbDate(d) + "   " + JsFormat.hhmm(d));
        lines.add("Mode: " + mode(inv));
        lines.add("Cashier: " + (inv.get("cashierName") == null ? "-" : inv.get("cashierName")));
        String cust = customerName(inv);
        if (cust != null && !cust.isEmpty() && !"Walk-in Customer".equals(cust)) {
            lines.add("Customer: " + cust);
        }
        lines.add("");
        lines.add(" " + padStart("Qty", 3) + " " + padEnd("Item", 22) + " " + padStart("Price", 8) + " "
                + padStart("Total", 10));
        lines.add("-".repeat(r));
        for (Map<String, Object> ln : lines(inv)) {
            double qty = num(ln.get("quantity"));
            double price = num(ln.get("unitPrice"));
            double disc = num(ln.get("discountPercent"));
            double lineTotal = qty * price * (1 - disc / 100);
            String incl = PosSql.truthy(ln.get("taxInclusive")) ? " (incl)" : "";
            String variant = PosSql.truthy(ln.get("variantName")) ? ln.get("variantName") + " " : "";
            String desc = padEnd(slice(variant + ln.get("description") + incl, 22), 22);
            lines.add(" " + padStart(JsFormat.str(qty), 3) + " " + desc + " " + padStart(JsFormat.locale(price), 8) + " "
                    + padStart(JsFormat.locale(lineTotal), 10));
            for (Object o : PosSql.list(ln.get("modifiers"))) {
                Map<String, Object> m = PosSql.map(o);
                lines.add("      + " + slice(m.get("name") == null ? "" : m.get("name").toString(), 22));
            }
            if (disc > 0) {
                lines.add("      (discount " + JsFormat.str(disc) + "%)");
            }
            if (PosSql.truthy(ln.get("note"))) {
                lines.add("      " + slice(ln.get("note").toString(), r - 6));
            }
        }
        lines.add("");
        lines.add("");
        lines.add("-".repeat(r));
        lines.add(padEnd("Subtotal:", r - 10) + padStart(ugx(receiptSubtotal(inv)), 10));
        if (num(inv.get("discountTotal")) > 0) {
            lines.add(padEnd("Discount:", r - 10) + padStart("-" + ugx(inv.get("discountTotal")), 10));
        }
        if (num(inv.get("taxAmount")) > 0) {
            lines.add(padEnd("Tax:", r - 10) + padStart(ugx(inv.get("taxAmount")), 10));
        }
        lines.add(padEnd("TOTAL:", r - 10) + padStart(ugx(inv.get("totalAmount")), 10));
        lines.add(padEnd("Paid:", r - 10) + padStart(ugx(inv.get("amountPaid")), 10));
        double tendered = num(inv.get("amountTendered") != null ? inv.get("amountTendered") : inv.get("amountPaid"));
        double change = Math.max(0, tendered - num(inv.get("totalAmount")));
        if (tendered > num(inv.get("amountPaid")) + 0.001) {
            lines.add(padEnd("Cash tendered:", r - 10) + padStart(ugx(tendered), 10));
        }
        if (change > 0) {
            lines.add(padEnd("Change:", r - 10) + padStart(ugx(change), 10));
        }
        if (PosSql.truthy(inv.get("discountReason"))) {
            lines.add("Why: " + inv.get("discountReason"));
        }
        if (PosSql.truthy(inv.get("discountApprovedBy"))) {
            lines.add("Approved: " + inv.get("discountApprovedBy"));
        }
        lines.add("-".repeat(r));
        lines.add("");
        lines.add("");
        if (isMerchant) {
            lines.add(padStart("CASHIER COPY — keep for records", r));
            lines.add("");
            lines.add(padStart("Signature: ____________________", r));
        } else {
            lines.add(padStart(h(footer, "message", "Thank you!"), r));
        }
        return String.join("\n", lines);
    }

    public String buildTextReceipt(String invoiceId) {
        return buildTextReceipt(invoiceId, false, null);
    }

    /** HTML receipt (46 columns, opens the browser print dialog). */
    public String buildHtmlReceipt(String invoiceId, boolean isReprint, String copyLabel) {
        return buildHtmlReceipt(resolveInvoice(invoiceId), isReprint, copyLabel);
    }

    /** HTML receipt from an already {@link #resolveInvoice resolved} invoice (read only). */
    public String buildHtmlReceipt(Map<String, Object> inv, boolean isReprint, String copyLabel) {
        Map<String, Object> header = header();
        Map<String, Object> footer = footer();
        List<String> lines = new ArrayList<>();
        boolean isMerchant = copyLabel != null && !copyLabel.isEmpty()
                && copyLabel.toUpperCase().matches(".*(CASHIER|MERCHANT).*");
        if (isReprint) {
            lines.add("*** REPRINT COPY ***");
        }
        if (copyLabel != null && !copyLabel.isEmpty()) {
            lines.add("*** " + copyLabel + " ***");
        }
        int w = 46;
        String addr1 = h(header, "addressLine1", "AFEE COMPLEX, KASANGA");
        String addr2 = h(header, "addressLine2", "Kampala, Uganda");
        String phone = h(header, "phone", "+256757920771");
        String bizName = h(header, "businessName", "Abiliz Cafe and Patisserie").toUpperCase();
        lines.add("=".repeat(w));
        lines.add(center(bizName, w));
        lines.add(center(addr1, w));
        lines.add(center(addr2, w));
        lines.add(center("Tel: " + phone, w));
        if (header != null && PosSql.truthy(header.get("taxId"))) {
            lines.add(center("TIN/VAT: " + header.get("taxId"), w));
        }
        lines.add("-".repeat(w));
        lines.add("Receipt #" + inv.get("documentNumber"));
        Instant d = instant(inv.get("issueDate"));
        lines.add(JsFormat.gbDate(d) + "  " + JsFormat.hhmm(d));
        lines.add("Mode: " + mode(inv));
        lines.add("Cashier: " + (inv.get("cashierName") == null ? "-" : inv.get("cashierName")));
        String cust = customerName(inv);
        if (cust != null && !cust.isEmpty() && !"Walk-in Customer".equals(cust)) {
            lines.add("Customer: " + cust);
        }
        lines.add("-".repeat(w));
        int qtyW = 3;
        int descW = 26;
        int priceW = 8;
        int totalW = w - qtyW - 3 - descW - priceW;
        lines.add(padStart("Qty", qtyW) + " " + padEnd("Item", descW) + " " + padStart("Price", priceW) + " "
                + padStart("Total", totalW));
        lines.add("-".repeat(w));
        for (Map<String, Object> ln : lines(inv)) {
            double qty = num(ln.get("quantity"));
            double price = num(ln.get("unitPrice"));
            double disc = num(ln.get("discountPercent"));
            double lineTotal = qty * price * (1 - disc / 100);
            String incl = PosSql.truthy(ln.get("taxInclusive")) ? " (incl)" : "";
            String variant = PosSql.truthy(ln.get("variantName")) ? ln.get("variantName") + " " : "";
            lines.add(padStart(JsFormat.str(qty), qtyW) + " " + padEnd(slice(variant + ln.get("description") + incl, descW),
                    descW) + " " + padStart(JsFormat.locale(price), priceW) + " "
                    + padStart(JsFormat.locale(lineTotal), totalW));
            for (Object o : PosSql.list(ln.get("modifiers"))) {
                Map<String, Object> m = PosSql.map(o);
                lines.add("     + " + slice(m.get("name") == null ? "" : m.get("name").toString(), w - 6));
            }
            if (PosSql.truthy(ln.get("note"))) {
                lines.add("     " + slice(ln.get("note").toString(), w - 6));
            }
        }
        lines.add("-".repeat(w));
        lines.add(two("Subtotal:", ugx(receiptSubtotal(inv)), w));
        if (num(inv.get("discountTotal")) > 0) {
            String typeTag = "fixed_amount".equals(inv.get("discountType")) ? " (fixed)"
                    : PosSql.truthy(inv.get("discountValue")) && num(inv.get("discountValue")) > 0
                            ? " (" + JsFormat.toFixed(num(inv.get("discountValue")), 1) + "%)" : "";
            lines.add(two("Discount" + typeTag + ":", "-" + ugx(inv.get("discountTotal")), w));
            if (PosSql.truthy(inv.get("discountReason"))) {
                lines.add("  Why: " + inv.get("discountReason"));
            }
            if (PosSql.truthy(inv.get("discountApprovedBy"))) {
                lines.add("  Approved: " + inv.get("discountApprovedBy"));
            }
        }
        if (num(inv.get("taxAmount")) > 0) {
            lines.add(two("Tax:", ugx(inv.get("taxAmount")), w));
        }
        lines.add(two("TOTAL:", ugx(inv.get("totalAmount")), w));
        lines.add(two("Paid:", ugx(inv.get("amountPaid")), w));
        double tendered = num(inv.get("amountTendered") != null ? inv.get("amountTendered") : inv.get("amountPaid"));
        double change = Math.max(0, tendered - num(inv.get("totalAmount")));
        if (tendered > num(inv.get("amountPaid")) + 0.001) {
            lines.add(two("Cash tendered:", ugx(tendered), w));
        }
        if (change > 0) {
            lines.add(two("Change:", ugx(change), w));
        }
        lines.add("-".repeat(w));
        if (isMerchant) {
            lines.add(center("CASHIER COPY — keep for records", w));
            lines.add("");
            lines.add(center("Signature: ____________________", w));
        } else {
            lines.add(center(h(footer, "message", "Thank you!"), w));
        }
        String text = String.join("\n", lines);
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        return "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>Receipt</title>\n<style>\n"
                + "  * { margin:0; padding:0; box-sizing:border-box; }\n"
                + "  html,body { margin:0; padding:0; width:72mm; height:auto; }\n"
                + "  body { font-family:'Courier New',Courier,monospace; font-size:9.5px; line-height:1.25; white-space:pre;"
                + " padding:1mm; }\n"
                + "  @media print {\n"
                + "    @page { margin:0; size:72mm 297mm; }\n"
                + "    html,body { width:72mm; height:auto; }\n"
                + "    /* 14mm bottom padding feeds the last lines past the tear bar/cutter —\n"
                + "       a print concern only; on screen it rendered as a blank band. */\n"
                + "    body { padding:1mm 1mm 14mm; }\n"
                + "  }\n"
                + "</style></head>\n"
                + "<body>" + escaped.replace("\n", "<br>") + "<script>\n"
                + "  // \"size:80mm auto\" is invalid CSS (dropped by browsers), which left the page\n"
                + "  // size to the driver's custom thermal paper — that broke print preview and\n"
                + "  // paginated long receipts so the cutter fired mid-receipt. Size the page to\n"
                + "  // the exact content height instead: one continuous page, one cut at the end.\n"
                + "  // The 14mm bottom padding feeds the last lines past the tear bar/cutter.\n"
                + "  window.onload=function(){\n"
                + "      var mm=Math.max(40,Math.ceil(document.body.scrollHeight*25.4/96)+4);\n"
                + "      var s=document.createElement('style');\n"
                + "      s.textContent='@page { size:72mm '+mm+'mm; margin:0; }';\n"
                + "      document.head.appendChild(s);\n"
                + "    };\n"
                + "</script></body></html>";
    }

    public String buildHtmlReceipt(String invoiceId) {
        return buildHtmlReceipt(invoiceId, false, null);
    }

    private static String center(String s, int w) {
        int pad = Math.max(0, (int) Math.floor((w - s.length()) / 2.0));
        return " ".repeat(pad) + s;
    }

    private static String two(String l, String r, int w) {
        int gap = Math.max(1, w - l.length() - r.length());
        return l + " ".repeat(gap) + r;
    }

    /** PDF receipt (customer copy + cashier copy unless {@code pos.printCashierCopy} is "false"). */
    public byte[] buildPdfReceipt(String invoiceId, boolean isReprint) {
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> header = header();
        Map<String, Object> footer = footer();
        boolean includeCashier = !"false".equals(settingValue("pos.printCashierCopy"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(new Rectangle(226, 600), 8, 8, 8, 8);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font bold10 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font bold20 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
        Font helv9 = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font courier8 = FontFactory.getFont(FontFactory.COURIER, 8);
        Font courierBold8 = FontFactory.getFont(FontFactory.COURIER_BOLD, 8);
        Font courierOblique7 = FontFactory.getFont(FontFactory.COURIER_OBLIQUE, 7);
        List<String> labels = includeCashier ? List.of("CUSTOMER COPY", "CASHIER COPY") : List.of("CUSTOMER COPY");
        boolean first = true;
        for (String copyLabel : labels) {
            if (!first) {
                doc.newPage();
            }
            first = false;
            if (isReprint) {
                doc.add(new Paragraph("*** REPRINT COPY ***", bold10));
            }
            doc.add(new Paragraph("*** " + copyLabel + " ***", bold10));
            doc.add(new Paragraph(h(header, "businessName", "Abiliz Cafe and Patisserie").toUpperCase(), bold20));
            doc.add(new Paragraph("=".repeat(42), courierBold8));
            doc.add(new Paragraph(h(header, "addressLine1", "AFEE COMPLEX, KASANGA"), helv9));
            doc.add(new Paragraph(h(header, "addressLine2", "Kampala, Uganda"), helv9));
            doc.add(new Paragraph("Telephone: " + h(header, "phone", "+256757920771"), helv9));
            if (header != null && PosSql.truthy(header.get("taxId"))) {
                doc.add(new Paragraph("TIN/VAT: " + header.get("taxId"), helv9));
            }
            doc.add(new Paragraph("Receipt #" + inv.get("documentNumber"), helv9));
            Instant d = instant(inv.get("issueDate"));
            doc.add(new Paragraph(JsFormat.gbDate(d) + "     " + JsFormat.hhmm(d), helv9));
            doc.add(new Paragraph("Cashier: " + (inv.get("cashierName") == null ? "-" : inv.get("cashierName")), helv9));
            doc.add(new Paragraph("Qty...Item............... Price....... Total", courierBold8));
            doc.add(new Paragraph("-".repeat(42), courier8));
            for (Map<String, Object> ln : lines(inv)) {
                double qty = num(ln.get("quantity"));
                double price = num(ln.get("unitPrice"));
                String variant = PosSql.truthy(ln.get("variantName")) ? ln.get("variantName") + " " : "";
                doc.add(new Paragraph(JsFormat.str(qty) + " " + padEnd(slice(variant + ln.get("description"), 18), 18) + " "
                        + padStart(ugx(price), 8) + " " + padStart(ugx(qty * price), 9), courier8));
                for (Object o : PosSql.list(ln.get("modifiers"))) {
                    Map<String, Object> m = PosSql.map(o);
                    doc.add(new Paragraph("      + " + slice(m.get("name") == null ? "" : m.get("name").toString(), 35),
                            courierOblique7));
                }
                if (PosSql.truthy(ln.get("note"))) {
                    doc.add(new Paragraph("      " + padEnd(slice(ln.get("note").toString(), 32), 32), courierOblique7));
                }
            }
            doc.add(new Paragraph("-".repeat(42), courier8));
            doc.add(new Paragraph(padEnd("Subtotal:", 33) + padStart(ugx(receiptSubtotal(inv)), 9), courier8));
            if (num(inv.get("taxAmount")) > 0) {
                doc.add(new Paragraph(padEnd("Tax:", 33) + padStart(ugx(inv.get("taxAmount")), 9), courier8));
            }
            doc.add(new Paragraph(padEnd("Total:", 33) + padStart(ugx(inv.get("totalAmount")), 9), courier8));
            doc.add(new Paragraph(padEnd("Paid:", 33) + padStart(ugx(inv.get("amountPaid")), 9), courier8));
            double tendered = num(inv.get("amountTendered") != null ? inv.get("amountTendered") : inv.get("amountPaid"));
            double change = Math.max(0, tendered - num(inv.get("totalAmount")));
            doc.add(new Paragraph(padEnd("Change:", 33) + padStart(ugx(change), 9), courier8));
            doc.add(new Paragraph(padEnd("Mode:", 33) + padStart(mode(inv), 9), courier8));
            doc.add(new Paragraph("-".repeat(42), courier8));
            doc.add(new Paragraph(h(footer, "message", "Thank you!"), helv9));
        }
        doc.close();
        return out.toByteArray();
    }

    /** Email the PDF receipt to the customer. */
    public Map<String, Object> emailReceipt(String invoiceId) {
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> partner = PosSql.map(inv.get("partner"));
        Object customerEmail = partner == null ? null : partner.get("email");
        if (!PosSql.truthy(customerEmail)) {
            return Obj.of("ok", false, "message", "Customer has no email on file. Add one in Partners.");
        }
        String orgId = org();
        byte[] pdf = buildPdfReceipt(invoiceId, false);
        String text = buildTextReceipt(invoiceId);
        notifications.send(new NotificationsService.SendInput(orgId, null, "email", "transactional",
                "Receipt " + inv.get("documentNumber"), text, Obj.of("kind", "pos_receipt", "invoiceId", invoiceId,
                        "invoiceNumber", inv.get("documentNumber"), "attachment", Obj.of(
                                "filename", "receipt-" + inv.get("documentNumber") + ".pdf",
                                "contentType", "application/pdf", "data", Base64.getEncoder().encodeToString(pdf)))));
        printLifecycle.markReceiptPrinted(invoiceId, user());
        printLifecycle.recordPrintLog(orgId, invoiceId, "RECEIPT", user(), "PRINT", null, null, null, null, null);
        audit.record(AuditInput.of("Document", invoiceId, "update", null, Obj.of("receiptEmailedTo", customerEmail)));
        return Obj.of("ok", true, "sentTo", customerEmail);
    }

    // ─────────────────────────── 48-column ticket frame ───────────────────────────

    private static List<String> ticketHeader(Map<String, Object> header) {
        List<String> out = new ArrayList<>(List.of("=".repeat(TICKET_W),
                h(header, "businessName", "Abiliz Cafe and Patisserie").toUpperCase(), "",
                h(header, "addressLine1", "AFEE COMPLEX, KASANGA"), h(header, "addressLine2", "Kampala, Uganda"),
                "Telephone: " + h(header, "phone", "+256757920771")));
        if (header != null && PosSql.truthy(header.get("taxId"))) {
            out.add("TIN/VAT: " + header.get("taxId"));
        }
        out.add("");
        return out;
    }

    private static String ticketDate(Instant d) {
        return "Date " + JsFormat.gbDate(d) + "   " + JsFormat.hhmm(d);
    }

    private static void ticketItemRows(List<Map<String, Object>> items, List<String> out) {
        for (Map<String, Object> ln : items) {
            double qty = num(ln.get("quantity"));
            double price = num(ln.get("unitPrice"));
            double disc = num(ln.get("discountPercent"));
            double lineTotal = qty * price * (1 - disc / 100);
            String incl = PosSql.truthy(ln.get("taxInclusive")) ? " (incl)" : "";
            String variant = PosSql.truthy(ln.get("variantName")) ? ln.get("variantName") + " " : "";
            String desc = padEnd(slice(variant + ln.get("description") + incl, 22), 22);
            out.add(" " + padStart(JsFormat.str(qty), 3) + " " + desc + " " + padStart(JsFormat.locale(price), 8) + " "
                    + padStart(JsFormat.locale(lineTotal), 10));
            for (Object o : PosSql.list(ln.get("modifiers"))) {
                Map<String, Object> m = PosSql.map(o);
                out.add("      + " + slice(m.get("name") == null ? "" : m.get("name").toString(), 38));
            }
            for (Object a : PosSql.list(ln.get("accompanimentNames"))) {
                out.add("      + " + slice(String.valueOf(a), 38));
            }
            if (disc > 0) {
                out.add("      (discount " + JsFormat.str(disc) + "%)");
            }
            if (PosSql.truthy(ln.get("note"))) {
                out.add("      " + slice(String.valueOf(ln.get("note")), 40));
            }
        }
    }

    private static String ticketTotal(String label, String value) {
        int gap = Math.max(1, TICKET_W - label.length() - value.length());
        return label + " ".repeat(gap) + value;
    }

    /** Pre-payment bill. */
    public String buildTextBill(String invoiceId, boolean isReprint) {
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> header = header();
        List<String> lines = new ArrayList<>();
        if (isReprint) {
            lines.add("*** REPRINT COPY ***");
        }
        lines.add("*** BILL ***");
        lines.addAll(ticketHeader(header));
        lines.add("Bill: #" + inv.get("documentNumber"));
        lines.add(ticketDate(instant(inv.get("issueDate"))));
        if (PosSql.truthy(inv.get("orderType"))) {
            lines.add("Order: " + ORDER_TYPE_LABELS.getOrDefault(inv.get("orderType").toString(), inv.get("orderType").toString()));
        }
        if (PosSql.truthy(inv.get("tableName"))) {
            lines.add("Table: " + inv.get("tableName"));
        }
        lines.add("");
        lines.add(" " + padStart("Qty", 3) + " " + padEnd("Item", 22) + " " + padStart("Price", 8) + " "
                + padStart("Total", 10));
        lines.add("-".repeat(TICKET_W));
        ticketItemRows(lines(inv), lines);
        lines.add("");
        lines.add("");
        lines.add("-".repeat(TICKET_W));
        lines.add(ticketTotal("Subtotal:", ugx(receiptSubtotal(inv))));
        if (num(inv.get("discountTotal")) > 0) {
            lines.add(ticketTotal("Discount:", "-" + ugx(inv.get("discountTotal"))));
        }
        if (PosSql.truthy(inv.get("discountReason"))) {
            lines.add(ticketTotal("Why:", inv.get("discountReason").toString()));
        }
        if (num(inv.get("taxAmount")) > 0) {
            lines.add(ticketTotal("Tax:", ugx(inv.get("taxAmount"))));
        }
        lines.add(ticketTotal("TOTAL DUE:", ugx(inv.get("totalAmount"))));
        lines.add("-".repeat(TICKET_W));
        lines.add("");
        lines.add("");
        lines.add(padStart("Thank you for your patience!", TICKET_W));
        return String.join("\n", lines);
    }

    /** Only Admin/Manager role names may reprint. */
    public void assertCanReprint(String userId) {
        if (userId == null) {
            throw ApiException.forbidden("Authentication required for reprint");
        }
        Long users = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(userId, org()).query(Long.class).single();
        if (users == 0) {
            throw ApiException.forbidden("User not found");
        }
        List<String> roles = jdbc.sql("SELECT r.name FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\" WHERE ur.\"B\" = ?")
                .param(userId).query(String.class).list();
        boolean ok = roles.stream().map(n -> n == null ? "" : n.toLowerCase())
                .anyMatch(n -> n.contains("admin") || n.contains("manager"));
        if (!ok) {
            throw ApiException.forbidden("Only Admin/Manager can reprint bills and receipts. Record a reason in the audit log.");
        }
    }

    // ─────────────────────────── transport ───────────────────────────

    private String settingString(String key) {
        Object v = settingValue(key);
        return v == null || "".equals(v) ? null : String.valueOf(v instanceof Number n ? JsFormat.str(n.doubleValue()) : v);
    }

    /** TCP printer ({@code pos.printerHost}/{@code pos.printerPort}), Windows spooler, or none. */
    public Target resolvePrintTarget() {
        String host = settingString("pos.printerHost");
        if (host != null) {
            Object port = settingValue("pos.printerPort");
            return new Target("tcp", host, PosSql.truthy(port) ? (int) num(port) : 9100, null, "escpos");
        }
        String win = settingString("pos.printerWindowsName");
        if (win != null) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                log.warn("[POS] pos.printerWindowsName is set but the API host is not Windows; falling back to console.");
                return new Target("none", null, 0, null, "console");
            }
            return new Target("windows", null, 0, win, "windows-raw");
        }
        return new Target("none", null, 0, null, "console");
    }

    /** Send raw ESC/POS bytes. Throws on failure. */
    public void sendRaw(Target target, byte[] payload) throws IOException {
        if ("none".equals(target.kind())) {
            throw new IOException("No printer configured");
        }
        if ("tcp".equals(target.kind())) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(target.host(), target.port()), 5000);
                s.setSoTimeout(5000);
                OutputStream os = s.getOutputStream();
                os.write(payload);
                os.flush();
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("printer timeout");
            }
            return;
        }
        sendWindowsRaw(target.printerName(), payload);
    }

    private static final String WINDOWS_RAW_SCRIPT = """
            $ErrorActionPreference='Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            public class RawPrint {
              [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)]
              public class DOCINFOA { [MarshalAs(UnmanagedType.LPStr)] public string pDocName; [MarshalAs(UnmanagedType.LPStr)] public string pOutputFile; [MarshalAs(UnmanagedType.LPStr)] public string pDataType; }
              [DllImport("winspool.Drv", EntryPoint="OpenPrinterA", SetLastError=true, CharSet=CharSet.Ansi)] public static extern bool OpenPrinter(string szPrinter, out IntPtr hPrinter, IntPtr pd);
              [DllImport("winspool.Drv", SetLastError=true)] public static extern bool ClosePrinter(IntPtr hPrinter);
              [DllImport("winspool.Drv", EntryPoint="StartDocPrinterA", SetLastError=true, CharSet=CharSet.Ansi)] public static extern bool StartDocPrinter(IntPtr hPrinter, int level, [In] DOCINFOA di);
              [DllImport("winspool.Drv", SetLastError=true)] public static extern bool EndDocPrinter(IntPtr hPrinter);
              [DllImport("winspool.Drv", SetLastError=true)] public static extern bool StartPagePrinter(IntPtr hPrinter);
              [DllImport("winspool.Drv", SetLastError=true)] public static extern bool EndPagePrinter(IntPtr hPrinter);
              [DllImport("winspool.Drv", SetLastError=true)] public static extern bool WritePrinter(IntPtr hPrinter, byte[] pBytes, int dwCount, out int dwWritten);
              public static string Send(string printer, byte[] bytes) {
                IntPtr h; if (!OpenPrinter(printer, out h, IntPtr.Zero)) return "OpenPrinter failed: " + Marshal.GetLastWin32Error();
                var di = new DOCINFOA(); di.pDocName = "POS ticket"; di.pDataType = "RAW";
                if (!StartDocPrinter(h, 1, di)) { ClosePrinter(h); return "StartDocPrinter failed: " + Marshal.GetLastWin32Error(); }
                StartPagePrinter(h); int written;
                bool ok = WritePrinter(h, bytes, bytes.Length, out written);
                EndPagePrinter(h); EndDocPrinter(h); ClosePrinter(h);
                return ok ? ("OK " + written) : ("WritePrinter failed: " + Marshal.GetLastWin32Error());
              }
            }
            '@
            $bytes = [System.IO.File]::ReadAllBytes($env:ESCPOS_FILE)
            $r = [RawPrint]::Send($env:ESCPOS_PRINTER, $bytes)
            if ($r -like 'OK*') { Write-Output $r; exit 0 } else { [Console]::Error.WriteLine($r); exit 1 }
            """;

    /** Spool raw bytes to a local Windows printer (datatype RAW) through an inline PowerShell winspool helper. */
    private void sendWindowsRaw(String printerName, byte[] payload) throws IOException {
        Path tmp = Files.createTempFile("escpos-", ".bin");
        Files.write(tmp, payload);
        try {
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            Path ps = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            String exe = Files.exists(ps) ? ps.toString() : "powershell.exe";
            String encoded = Base64.getEncoder().encodeToString(WINDOWS_RAW_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder(exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded);
            pb.environment().put("ESCPOS_FILE", tmp.toString());
            pb.environment().put("ESCPOS_PRINTER", printerName);
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("printer timeout");
            }
            if (p.exitValue() != 0) {
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new IOException(err.isEmpty() ? "spooler error" : err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /** {@code ESC d n}: feed {@code pos.cutFeedLines} (default 6, clamped 0–16) before the cut. */
    byte[] feedBuffer() {
        Object v = settingValue("pos.cutFeedLines");
        double parsed = v == null ? Double.NaN : num(v);
        int lines = Double.isFinite(parsed) ? (int) Math.min(16, Math.max(0, (long) parsed)) : 6;
        return new byte[] {0x1b, 0x64, (byte) lines};
    }

    private static final byte[] CUT = {0x1d, 0x56, 0x00};

    /** Text ticket bytes: ASCII text + newline + feed + cut. */
    byte[] ticketBytes(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((toPrinterAscii(text) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(feedBuffer());
        out.writeBytes(CUT);
        return out.toByteArray();
    }

    // ─────────────────────────── KOT ───────────────────────────

    /** Paper KOT for everything not yet on paper (or a cancel slip). Never throws on printer errors. */
    public Map<String, Object> printKotDelta(String recordId, String userId) {
        String orgId = org();
        String orderId = jdbc.sql("SELECT \"orderId\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                .params(recordId, orgId).query(String.class).optional().orElse(null);
        if (orderId == null) {
            orderId = jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?").params(recordId, orgId)
                    .query(String.class).optional().orElse(null);
        }
        if (orderId == null) {
            return Obj.of("ok", false, "backend", "none", "message", "Invoice has no linked order");
        }
        String oid = orderId;
        List<PosPrintLifecycleService.Delta>[] claimed = tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(oid, orgId)
                    .query().listOfRows();
            PosPrintLifecycleService.KitchenDelta delta = printLifecycle.getKitchenDeltas(oid);
            List<PosPrintLifecycleService.Delta> adds = delta.addLines().stream().filter(d -> kotEligible(d.line())).toList();
            List<PosPrintLifecycleService.Delta> removes = delta.removeLines().stream().filter(d -> kotEligible(d.line()))
                    .toList();
            List<PosPrintLifecycleService.Delta> changed = new ArrayList<>(adds);
            changed.addAll(removes);
            if (!changed.isEmpty()) {
                Map<String, Double> qty = new LinkedHashMap<>();
                for (PosPrintLifecycleService.Delta d : changed) {
                    qty.put((String) d.line().get("id"), num(d.line().get("quantity")));
                }
                printLifecycle.markKitchenPrinted(changed.stream().map(d -> (String) d.line().get("id")).toList(), qty,
                        userId);
            }
            @SuppressWarnings("unchecked")
            List<PosPrintLifecycleService.Delta>[] res = new List[] {adds, removes};
            return res;
        });
        List<PosPrintLifecycleService.Delta> addLines = claimed[0];
        List<PosPrintLifecycleService.Delta> removeLines = claimed[1];
        if (addLines.isEmpty() && removeLines.isEmpty()) {
            return Obj.of("ok", true, "backend", "none", "message", "No new kitchen items to print", "printedCount", 0);
        }
        int kotNumber = printLifecycle.getKotCopyNumber(orderId);
        String text = !addLines.isEmpty() ? buildTextKot(orderId, kotNumber, addLines, removeLines)
                : buildTextCancelKot(orderId, kotNumber, removeLines);
        printLifecycle.markKotPrinted(orderId, userId);
        String logType = !removeLines.isEmpty() && addLines.isEmpty() ? "CANCEL" : "KOT";
        int printedCount = addLines.size() + removeLines.size();
        Runnable logIt = () -> printLifecycle.recordPrintLog(orgId, oid, logType, userId);
        try {
            Target target = resolvePrintTarget();
            if ("none".equals(target.kind())) {
                log.warn("[POS] No printer configured; KOT #{}:\n{}", kotNumber, text);
                logIt.run();
                return Obj.of("ok", true, "backend", "console", "message", "No printer; KOT logged to server console.",
                        "kotNumber", kotNumber, "text", text, "printedCount", printedCount);
            }
            sendRaw(target, ticketBytes(text));
            logIt.run();
            return Obj.of("ok", true, "backend", target.backend(), "kotNumber", kotNumber, "text", text,
                    "printedCount", printedCount);
        } catch (IOException | RuntimeException e) {
            log.warn("[POS] Printer unreachable; KOT #{} fallback: {}", kotNumber, e.getMessage());
            return Obj.of("ok", false, "backend", "escpos", "message", e.getMessage() == null ? "printer error"
                    : e.getMessage(), "kotNumber", kotNumber, "text", text, "printedCount", printedCount);
        }
    }

    private static boolean kotEligible(Map<String, Object> l) {
        return PosSql.truthy(l.get("productId")) || PosSql.truthy(l.get("menuItemId")) || PosSql.truthy(l.get("comboId"));
    }

    public String buildTextKot(String invoiceId, int kotNumber, List<PosPrintLifecycleService.Delta> addLines,
            List<PosPrintLifecycleService.Delta> removeLines) {
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> header = header();
        int r = TICKET_W;
        List<String> lines = new ArrayList<>();
        boolean isInitial = kotNumber <= 1 && removeLines.isEmpty();
        lines.add("=".repeat(r));
        String biz = header == null || !PosSql.truthy(header.get("businessName")) ? "ABILIZ CAKE & PATISSERIE"
                : header.get("businessName").toString();
        lines.add(biz.toUpperCase());
        lines.add("");
        lines.add(isInitial ? "*** KITCHEN ORDER TICKET ***" : "*** ADDITIONAL KOT ***");
        lines.add("KOT: #" + padStart(String.valueOf(kotNumber), 3, '0'));
        lines.add("Order: #" + inv.get("documentNumber"));
        lines.add(ticketDate(Instant.now()));
        if (PosSql.truthy(inv.get("orderType"))) {
            lines.add("Type: " + ORDER_TYPE_LABELS.getOrDefault(inv.get("orderType").toString(), inv.get("orderType").toString()));
        }
        if (PosSql.truthy(inv.get("tableName"))) {
            lines.add("Table: " + inv.get("tableName"));
        }
        lines.add("");
        lines.add(" " + padStart("Qty", 3) + "  Item");
        lines.add("-".repeat(r));
        for (PosPrintLifecycleService.Delta d : addLines) {
            Map<String, Object> line = d.line();
            double qty = num(line.get("quantity"));
            String mark = d.delta() >= qty ? "x" + JsFormat.str(qty) : "+" + JsFormat.str(d.delta());
            lines.add(" " + padStart(mark, 3) + "  " + slice(str(line.get("description")), r - 7));
            if (PosSql.truthy(line.get("variantName"))) {
                lines.add("        " + slice(line.get("variantName").toString(), r - 9));
            }
            for (Object a : PosSql.list(line.get("accompanimentNames"))) {
                lines.add("        + " + slice(String.valueOf(a), r - 11));
            }
            for (Object o : PosSql.list(line.get("modifiers"))) {
                Map<String, Object> m = PosSql.map(o);
                Object name = m.get("kitchenPrintName") != null ? m.get("kitchenPrintName") : m.get("name");
                lines.add("        " + slice(str(name), r - 9));
            }
            if (PosSql.truthy(line.get("note"))) {
                lines.add("        ! " + slice(line.get("note").toString(), r - 11));
            }
        }
        if (!removeLines.isEmpty()) {
            lines.add("-".repeat(r));
            lines.add("CANCELLED:");
            for (PosPrintLifecycleService.Delta d : removeLines) {
                lines.add(" " + padStart("-" + JsFormat.str(d.delta()), 3) + "  " + slice(str(d.line().get("description")), r - 7));
            }
        }
        if (addLines.isEmpty() && removeLines.isEmpty()) {
            lines.add("  (no changes)");
        }
        lines.add("-".repeat(r));
        lines.add("");
        lines.add(padStart("Prepare and serve with care", r));
        return String.join("\n", lines);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    public String buildTextCancelKot(String invoiceId, int kotNumber, List<PosPrintLifecycleService.Delta> removeLines) {
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> header = header();
        int r = TICKET_W;
        List<String> lines = new ArrayList<>();
        lines.add("=".repeat(r));
        String biz = header == null || !PosSql.truthy(header.get("businessName")) ? "ABILIZ CAKE & PATISSERIE"
                : header.get("businessName").toString();
        lines.add(biz.toUpperCase());
        lines.add("");
        lines.add("*** CANCEL KOT ***");
        lines.add("KOT: #" + padStart(String.valueOf(kotNumber), 3, '0'));
        lines.add("Order: #" + inv.get("documentNumber"));
        lines.add(ticketDate(Instant.now()));
        if (PosSql.truthy(inv.get("tableName"))) {
            lines.add("Table: " + inv.get("tableName"));
        }
        lines.add("");
        lines.add(" " + padStart("Qty", 3) + "  Item");
        lines.add("-".repeat(r));
        for (PosPrintLifecycleService.Delta d : removeLines) {
            lines.add(" " + padStart("-" + JsFormat.str(d.delta()), 3) + "  " + slice(str(d.line().get("description")), r - 7));
            if (PosSql.truthy(d.line().get("variantName"))) {
                lines.add("        " + slice(d.line().get("variantName").toString(), r - 9));
            }
        }
        lines.add("-".repeat(r));
        lines.add("");
        lines.add(padStart("VOID / REDUCE", r));
        return String.join("\n", lines);
    }

    // ─────────────────────────── bills ───────────────────────────

    private int billPrintCount(String id) {
        Integer inv = jdbc.sql("SELECT \"billPrintCount\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Integer.class).optional().orElse(null);
        if (inv != null) {
            return inv;
        }
        return jdbc.sql("SELECT \"billPrintCount\" FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Integer.class).optional().orElse(0);
    }

    /** Closing statement on demand (session must be closed). */
    public Map<String, Object> printClosingStatement(String sessionId, String text) {
        String status = jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                .params(sessionId, org()).query(String.class).optional().orElse(null);
        if (status == null) {
            throw ApiException.notFound("Cash session not found");
        }
        if ("open".equals(status)) {
            throw ApiException.badRequest("The shift is still open; close it before printing the closing statement.");
        }
        Target target = resolvePrintTarget();
        if ("none".equals(target.kind())) {
            log.warn("[POS] No printer configured; closing statement:\n{}", text);
            return Obj.of("ok", true, "backend", "console", "message",
                    "No printer configured; closing statement logged to server console.");
        }
        try {
            sendRaw(target, ticketBytes(text));
            return Obj.of("ok", true, "backend", target.backend());
        } catch (IOException | RuntimeException e) {
            log.warn("[POS] Printer unreachable; closing statement not printed: {}", e.getMessage());
            return Obj.of("ok", false, "backend", target.backend(), "message",
                    e.getMessage() == null ? "printer error" : e.getMessage());
        }
    }

    public Map<String, Object> printBill(String invoiceId, String userId, boolean isReprint) {
        if (!isReprint && billPrintCount(invoiceId) > 0) {
            throw ApiException.forbidden("Bill already printed. Use the reprint endpoint (Admin/Manager only, with a reason).");
        }
        String text = buildTextBill(invoiceId, isReprint);
        String orgId = org();
        printLifecycle.markBillPrinted(invoiceId, userId);
        printLifecycle.markLinesBilled(invoiceId, userId);
        Target target = resolvePrintTarget();
        if ("none".equals(target.kind())) {
            log.warn("[POS] No printer configured; bill:\n{}", text);
            printLifecycle.recordPrintLog(orgId, invoiceId, "BILL", userId);
            return Obj.of("ok", true, "backend", "console", "message", "No printer configured; bill logged to server console.");
        }
        try {
            sendRaw(target, ticketBytes(text));
            printLifecycle.recordPrintLog(orgId, invoiceId, "BILL", userId);
            return Obj.of("ok", true, "backend", target.backend());
        } catch (IOException | RuntimeException e) {
            log.warn("[POS] Printer unreachable; bill fallback: {}", e.getMessage());
            return Obj.of("ok", false, "backend", "escpos", "message",
                    e.getMessage() == null ? "printer error; bill fallback" : e.getMessage());
        }
    }

    /** Quantities that have not appeared on a previous bill. */
    private List<Map<String, Object>> getUnbilledLines(String recordId) {
        String orgId = org();
        if (jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(recordId, orgId)
                .query(Long.class).single() > 0) {
            return SqlRows.list(com.inventoryerp.documents.domain.DocumentLine.class, jdbc.sql("SELECT * FROM \"DocumentLine\""
                    + " WHERE \"documentId\" = ? AND \"organizationId\" = ? AND \"billPrintedAt\" IS NULL"
                    + " ORDER BY \"lineNumber\" ASC").params(recordId, orgId));
        }
        if (jdbc.sql("SELECT count(*) FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?").params(recordId, orgId)
                .query(Long.class).single() == 0) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> line : db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", recordId)) {
            double q = Math.max(0, num(line.get("quantity")) - num(line.get("billPrintedQty")));
            if (q > 0.000001) {
                Map<String, Object> l = new LinkedHashMap<>(line);
                l.put("quantity", q);
                out.add(l);
            }
        }
        return out;
    }

    public Map<String, Object> printAdditionalBill(String invoiceId, String userId) {
        String orgId = org();
        int copyNumber = billPrintCount(invoiceId) + 1;
        List<Map<String, Object>> unbilled = tx.execute(st -> {
            if (jdbc.sql("SELECT count(*) FROM \"Order\" WHERE id = ? AND \"organizationId\" = ?").params(invoiceId, orgId)
                    .query(Long.class).single() > 0) {
                jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(invoiceId, orgId)
                        .query().listOfRows();
            }
            List<Map<String, Object>> delta = getUnbilledLines(invoiceId);
            if (delta.isEmpty()) {
                throw ApiException.badRequest(
                        "No additional items to bill. All items have already appeared on a previous bill.");
            }
            printLifecycle.markBillPrinted(invoiceId, userId);
            printLifecycle.markLinesBilled(invoiceId, userId);
            return delta;
        });
        Map<String, Object> inv = resolveInvoice(invoiceId);
        Map<String, Object> header = header();
        List<String> lines = new ArrayList<>();
        lines.add("*** ADDITIONAL BILL ***");
        lines.addAll(ticketHeader(header));
        lines.add("Bill: #" + inv.get("documentNumber"));
        if (copyNumber > 1) {
            lines.add("Copy: #" + copyNumber);
        }
        lines.add(ticketDate(instant(inv.get("issueDate"))));
        if (PosSql.truthy(inv.get("orderType"))) {
            lines.add("Order: " + ORDER_TYPE_LABELS.getOrDefault(inv.get("orderType").toString(), inv.get("orderType").toString()));
        }
        if (PosSql.truthy(inv.get("tableName"))) {
            lines.add("Table: " + inv.get("tableName"));
        }
        lines.add("");
        lines.add(" " + padStart("Qty", 3) + " " + padEnd("Item", 22) + " " + padStart("Price", 8) + " "
                + padStart("Total", 10));
        lines.add("-".repeat(TICKET_W));
        double additionalSubtotal = 0;
        for (Map<String, Object> ln : unbilled) {
            additionalSubtotal += num(ln.get("quantity")) * num(ln.get("unitPrice")) * (1 - num(ln.get("discountPercent")) / 100);
        }
        ticketItemRows(unbilled, lines);
        if (unbilled.isEmpty()) {
            lines.add("  (no additional items)");
        }
        double previousSubtotal = Math.max(0, num(inv.get("subtotal")) - additionalSubtotal);
        double grandTotal = num(inv.get("totalAmount"));
        lines.add("");
        lines.add("");
        lines.add("-".repeat(TICKET_W));
        lines.add(ticketTotal("Additional Total:", ugx(additionalSubtotal)));
        lines.add(ticketTotal("Previous Total:", ugx(previousSubtotal)));
        lines.add("=".repeat(TICKET_W));
        lines.add(ticketTotal("GRAND TOTAL DUE:", ugx(grandTotal)));
        lines.add("-".repeat(TICKET_W));
        lines.add("");
        lines.add("");
        lines.add(padStart("Thank you for your patience!", TICKET_W));
        String text = String.join("\n", lines);
        Map<String, Object> totals = Obj.of("copyNumber", copyNumber, "grandTotal", PosSql.js(grandTotal),
                "additionalSubtotal", PosSql.js(additionalSubtotal), "previousSubtotal", PosSql.js(previousSubtotal));
        Target target = resolvePrintTarget();
        if ("none".equals(target.kind())) {
            log.warn("[POS] No printer configured; additional bill:\n{}", text);
            printLifecycle.recordPrintLog(orgId, invoiceId, "BILL", userId, "PRINT", 1, null, null, null, null);
            return Obj.merge(Obj.of("ok", true, "backend", "console", "message",
                    "No printer configured; additional bill logged to server console."), totals);
        }
        try {
            sendRaw(target, ticketBytes(text));
            printLifecycle.recordPrintLog(orgId, invoiceId, "BILL", userId, "PRINT", 1, null, null, null, null);
            return Obj.merge(Obj.of("ok", true, "backend", target.backend()), totals);
        } catch (IOException | RuntimeException e) {
            log.warn("[POS] Printer unreachable; additional bill fallback: {}", e.getMessage());
            return Obj.merge(Obj.of("ok", false, "backend", "escpos", "message",
                    e.getMessage() == null ? "printer error" : e.getMessage()), totals);
        }
    }

    // ─────────────────────────── receipts ───────────────────────────

    public Map<String, Object> printReceipt(String invoiceId, String userId, boolean isReprint) {
        String orgId = org();
        if (!isReprint) {
            Integer count = jdbc.sql("SELECT \"receiptPrintCount\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(invoiceId, orgId).query(Integer.class).optional().orElse(0);
            if (count > 0) {
                throw ApiException.forbidden(
                        "Receipt already printed. Use the reprint endpoint (Admin/Manager only, with a reason).");
            }
        }
        // Both copies render the same resolution: marking the receipt printed only bumps its print counters.
        Map<String, Object> inv = resolveInvoice(invoiceId);
        String customerText = buildTextReceipt(inv, isReprint, "CUSTOMER COPY");
        if (isReprint) {
            printLifecycle.markReceiptPrinted(invoiceId, userId);
        } else if (!printLifecycle.claimOriginalReceipt(invoiceId, userId)) {
            throw ApiException.forbidden(
                    "Receipt already printed. Use the reprint endpoint (Admin/Manager only, with a reason).");
        }
        boolean printCashierCopy = !"false".equals(settingValue("pos.printCashierCopy"));
        String merchantText = printCashierCopy ? buildTextReceipt(inv, isReprint, "CASHIER COPY") : null;
        int copies = merchantText != null ? 2 : 1;
        Target target = resolvePrintTarget();
        if ("none".equals(target.kind())) {
            log.warn("[POS] No printer configured; receipt (customer):\n{}", customerText);
            printLifecycle.recordPrintLog(orgId, invoiceId, "RECEIPT", userId, null, copies, null, null, null, null);
            return Obj.of("ok", true, "backend", "console", "message", "No printer configured; " + copies + " receipt cop"
                    + (copies > 1 ? "ies" : "y") + " logged to server console.");
        }
        try {
            boolean shouldKick = !"false".equals(settingValue("pos.kickDrawerOnPrint"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] feed = feedBuffer();
            out.writeBytes(toPrinterAscii(customerText).getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(feed);
            out.writeBytes(CUT);
            if (merchantText != null) {
                out.writeBytes(toPrinterAscii(merchantText).getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(feed);
                out.writeBytes(CUT);
            }
            if (shouldKick) {
                out.writeBytes(new byte[] {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA});
            }
            sendRaw(target, out.toByteArray());
            printLifecycle.recordPrintLog(orgId, invoiceId, "RECEIPT", userId, null, copies, null, null, null, null);
            Map<String, Object> res = Obj.of("ok", true, "backend", target.backend());
            if (merchantText != null) {
                res.put("message", "Customer + cashier copies printed");
            }
            return res;
        } catch (IOException | RuntimeException e) {
            log.warn("[POS] Printer unreachable; receipt fallback: {}", e.getMessage());
            return Obj.of("ok", false, "backend", "escpos", "message", e.getMessage() == null ? "printer error"
                    : e.getMessage());
        }
    }

    /**
     * {@code POST :id/reprint} / {@code :id/reprint-bill}: Admin/Manager only, then a REPRINT print log with the
     * reason and an audit row ({@code kind} = {@code RECEIPT} or {@code BILL}).
     */
    public Map<String, Object> reprint(String invoiceId, String kind, String reason) {
        String userId = user();
        assertCanReprint(userId);
        Map<String, Object> r = "BILL".equals(kind) ? printBill(invoiceId, userId, true)
                : printReceipt(invoiceId, userId, true);
        printLifecycle.recordPrintLog(org(), invoiceId, kind, userId, "REPRINT", null, reason, null, null, null);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("kind", kind);
        newValues.put("reason", reason);
        newValues.put("printedById", userId);
        audit.record(AuditInput.of("Document", invoiceId, "reprint", null, newValues));
        return r;
    }

    public Map<String, Object> listReceipts(int page, int pageSize, String search, String paymentMode,
            String settlementStatus, String dateFrom, String dateTo) {
        String orgId = org();
        StringBuilder where = new StringBuilder("i.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (search != null && !search.trim().isEmpty()) {
            String q = search.trim();
            List<String> partnerIds = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND name ILIKE ? LIMIT 500").params(orgId, PosSql.contains(q)).query(String.class).list();
            where.append(" AND (i.\"invoiceNumber\" ILIKE ?");
            params.add(PosSql.contains(q));
            if (!partnerIds.isEmpty()) {
                where.append(" OR i.\"partnerId\" IN (").append(PosSql.marks(partnerIds.size())).append(")");
                params.addAll(partnerIds);
            }
            where.append(")");
        }
        if (paymentMode != null && RECEIPT_PAYMENT_MODES.contains(paymentMode)) {
            where.append(" AND i.\"paymentMode\" = ?::\"InvoicePaymentMode\"");
            params.add(paymentMode);
        }
        if (settlementStatus != null && RECEIPT_SETTLEMENT_STATUSES.contains(settlementStatus)) {
            where.append(" AND i.\"settlementStatus\" = ?::\"InvoiceSettlementStatus\"");
            params.add(settlementStatus);
        }
        Instant from = validDate(dateFrom);
        Instant to = validDate(dateTo);
        if (from != null) {
            where.append(" AND i.\"issueDate\" >= ?");
            params.add(SqlRows.ts(from));
        }
        if (to != null) {
            Instant end = java.time.LocalDateTime.of(to.atZone(BusinessZone.get()).toLocalDate(),
                    java.time.LocalTime.of(23, 59, 59, 999_000_000)).atZone(BusinessZone.get()).toInstant();
            where.append(" AND i.\"issueDate\" <= ?");
            params.add(SqlRows.ts(end));
        }
        List<Object> pp = new ArrayList<>(params);
        pp.add(pageSize);
        pp.add((page - 1) * pageSize);
        List<Map<String, Object>> items = SqlRows.list(jdbc.sql("SELECT i.id, i.\"invoiceNumber\", i.\"issueDate\","
                + " i.\"totalAmount\", i.\"amountPaid\", i.\"amountResidual\", i.subtotal, i.\"discountTotal\","
                + " i.\"paymentStatus\"::text AS \"paymentStatus\", i.\"paymentMode\"::text AS \"paymentMode\","
                + " i.\"settlementStatus\"::text AS \"settlementStatus\", i.status::text AS status, i.\"partnerId\","
                + " (SELECT o.\"orderType\"::text FROM \"Order\" o WHERE o.\"invoiceId\" = i.id LIMIT 1) AS \"orderType\","
                + " (SELECT count(*) FROM \"InvoiceItem\" ii WHERE ii.\"invoiceId\" = i.id) AS \"itemCount\""
                + " FROM \"Invoice\" i WHERE " + where + " ORDER BY i.\"createdAt\" DESC LIMIT ? OFFSET ?").params(pp));
        long total = jdbc.sql("SELECT count(*) FROM \"Invoice\" i WHERE " + where).params(params).query(Long.class).single();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> i : items) {
            Map<String, Object> partner = i.get("partnerId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM"
                    + " \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(i.get("partnerId"), orgId));
            data.add(Obj.of("id", i.get("id"), "documentNumber", i.get("invoiceNumber"), "issueDate", i.get("issueDate"),
                    "totalAmount", i.get("totalAmount"), "amountPaid", i.get("amountPaid"),
                    "amountResidual", i.get("amountResidual"), "subtotal", i.get("subtotal"),
                    "discountTotal", i.get("discountTotal"), "paymentStatus", i.get("paymentStatus"),
                    "paymentMode", i.get("paymentMode"), "settlementStatus", i.get("settlementStatus"),
                    "status", i.get("status"), "partnerId", i.get("partnerId"), "partner", partner,
                    "orderType", i.get("orderType"), "itemCount", i.get("itemCount")));
        }
        return Obj.of("data", data, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize))));
    }

    private static Instant validDate(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return PosSql.date(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, Object> getReceiptDetail(String invoiceId) {
        return resolveInvoice(invoiceId);
    }
}
