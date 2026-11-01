package com.inventoryerp.customization.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The data a print template (and an automation rule) sees for a record (ADR-S029 §3): one normalized shape for every
 * document kind — {@code company}, {@code document}, {@code partner}, {@code lines[]}, {@code custom}, {@code printedAt}.
 * Money is given formatted ({@code total}) and raw ({@code totalRaw}). Reads other modules' tables (queries only).
 */
@Component
public class PrintContextProviders {

    public static final Set<String> KINDS = Set.of("sales_invoice", "credit_note", "vendor_bill", "purchase_order",
            "sales_order", "sales_quotation");

    private static final Map<String, String> LABELS = Map.of("sales_invoice", "Invoice", "credit_note", "Credit note",
            "vendor_bill", "Vendor bill", "purchase_order", "Purchase order", "sales_order", "Sales order",
            "sales_quotation", "Quotation");

    private final JdbcClient jdbc;
    private final CustomFieldService customFields;

    public PrintContextProviders(JdbcClient jdbc, CustomFieldService customFields) {
        this.jdbc = jdbc;
        this.customFields = customFields;
    }

    public static void assertKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("documentKind must be one of the following values: credit_note,"
                    + " purchase_order, sales_invoice, sales_order, sales_quotation, vendor_bill");
        }
    }

    /** Context of record {@code id} of {@code kind} in {@code org}; 404 when it does not exist there. */
    public Map<String, Object> context(String org, String kind, String id) {
        assertKind(kind);
        Map<String, Object> doc;
        List<Map<String, Object>> lines;
        switch (kind) {
            case "sales_invoice", "credit_note", "vendor_bill" -> {
                Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT d.*, c.code AS \"currencyCode\" FROM \"Document\" d"
                        + " LEFT JOIN \"Currency\" c ON c.id = d.\"currencyId\" WHERE d.id = ? AND d.\"organizationId\" = ?"
                        + " AND d.\"documentType\"::text = ?").params(id, org, kind));
                if (d == null) {
                    throw ApiException.notFound("Record not found");
                }
                doc = document(kind, d.get("documentNumber"), d.get("status"), d.get("issueDate"), d.get("dueDate"),
                        d.get("reference"), d.get("currencyCode"), d.get("notes"), null, d.get("subtotal"),
                        d.get("discountTotal"), d.get("taxAmount"), d.get("totalAmount"), d.get("amountResidual"));
                doc.put("partnerId", d.get("partnerId"));
                lines = lines(SqlRows.list(jdbc.sql("SELECT l.description, l.quantity, NULL AS unit, l.\"unitPrice\","
                        + " l.\"discountPercent\", t.name AS tax, l.subtotal, l.total FROM \"DocumentLine\" l LEFT JOIN"
                        + " \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"documentId\" = ? AND l.\"organizationId\" = ?"
                        + " ORDER BY l.\"lineNumber\", l.id").params(id, org)));
            }
            case "purchase_order" -> {
                Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org));
                if (d == null) {
                    throw ApiException.notFound("Record not found");
                }
                doc = document(kind, d.get("orderNumber"), d.get("status"), d.get("orderDate"),
                        d.get("expectedDeliveryDate"), d.get("description"), d.get("currencyCode"), d.get("notes"),
                        d.get("terms"), d.get("subtotal"), null, d.get("taxAmount"), d.get("totalAmount"), null);
                doc.put("partnerId", d.get("partnerId"));
                lines = lines(SqlRows.list(jdbc.sql("SELECT l.description, l.quantity, u.name AS unit, l.\"unitPrice\","
                        + " 0 AS \"discountPercent\", t.name AS tax, l.subtotal, l.subtotal AS total FROM"
                        + " \"PurchaseOrderLine\" l LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" LEFT JOIN \"UnitOfMeasure\" u"
                        + " ON u.id = l.\"unitOfMeasureId\" WHERE l.\"purchaseOrderId\" = ? AND l.\"organizationId\" = ?"
                        + " ORDER BY l.\"lineNumber\", l.id").params(id, org)));
            }
            case "sales_order", "sales_quotation" -> {
                boolean order = "sales_order".equals(kind);
                Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"" + (order ? "SalesOrder" : "SalesQuotation")
                        + "\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org));
                if (d == null) {
                    throw ApiException.notFound("Record not found");
                }
                doc = document(kind, d.get("number"), d.get("status"), order ? d.get("createdAt") : d.get("issueDate"),
                        order ? d.get("commitmentDate") : d.get("validUntil"), order ? d.get("customerRef") : null,
                        d.get("currencyCode"), d.get("notes"), d.get("terms"), d.get("subtotal"), d.get("discountTotal"),
                        d.get("taxAmount"), d.get("total"), null);
                doc.put("partnerId", d.get("partnerId"));
                lines = lines(SqlRows.list(jdbc.sql("SELECT l.description, l.quantity, u.name AS unit, l.\"unitPrice\","
                        + " l.\"discountPercent\", t.name AS tax, l.\"lineSubtotal\" AS subtotal, l.\"lineTotal\" AS total"
                        + " FROM \"" + (order ? "SalesOrderLine" : "SalesQuotationLine") + "\" l LEFT JOIN \"Tax\" t ON"
                        + " t.id = l.\"taxId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\""
                        + (order ? "orderId" : "quotationId") + "\" = ? AND l.\"organizationId\" = ? ORDER BY"
                        + " l.\"sortOrder\", l.id").params(id, org)));
            }
            default -> throw new IllegalStateException(kind);
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("company", company(org));
        ctx.put("document", doc);
        ctx.put("partner", partner(org, (String) doc.remove("partnerId")));
        ctx.put("lines", lines);
        ctx.put("custom", customFields.visibleValues(org, kind, id));
        ctx.put("printedAt", LocalDate.now(ZoneOffset.UTC).toString());
        return ctx;
    }

    /** Built-in sample data for previews without a record. */
    public Map<String, Object> sample(String org, String kind) {
        assertKind(kind);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("company", company(org));
        ctx.put("document", document(kind, "SAMPLE-0001", "posted", LocalDate.now(ZoneOffset.UTC).toString(),
                LocalDate.now(ZoneOffset.UTC).plusDays(30).toString(), "PO-778", ctx.get("company") instanceof Map<?, ?> c
                        ? c.get("currency") : "USD", "Thank you for your business.", "Payment within 30 days.",
                new BigDecimal("1500.00"), new BigDecimal("50.00"), new BigDecimal("261.00"), new BigDecimal("1711.00"),
                new BigDecimal("1711.00")));
        ctx.put("partner", Obj.of("name", "Sample Customer Ltd", "code", "C-0001", "email", "accounts@example.com",
                "phone", "+256 700 000 000", "taxNumber", "1000000000", "address", "Plot 1, Kampala Road, Kampala"));
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(line(1, "Cement 50 kg", new BigDecimal("20"), "bag", new BigDecimal("50.00"), BigDecimal.ZERO, "VAT 18%",
                new BigDecimal("1000.00"), new BigDecimal("1180.00")));
        lines.add(line(2, "Delivery", BigDecimal.ONE, "trip", new BigDecimal("500.00"), BigDecimal.TEN, "VAT 18%",
                new BigDecimal("450.00"), new BigDecimal("531.00")));
        ctx.put("lines", lines);
        ctx.put("custom", Obj.of());
        ctx.put("printedAt", LocalDate.now(ZoneOffset.UTC).toString());
        return ctx;
    }

    /** Variables of {@code kind} for the designer's field palette: path, section flag and sample value. */
    public List<Map<String, Object>> fields(String org, String kind) {
        Map<String, Object> sample = sample(org, kind);
        Map<String, Object> custom = new LinkedHashMap<>();
        customFields.fields(org, kind).stream().filter(CustomFieldService.Field::active)
                .forEach(f -> custom.put(f.key(), f.label()));
        sample.put("custom", custom);
        List<Map<String, Object>> out = new ArrayList<>();
        flatten("", sample, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> m, List<Map<String, Object>> out) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> inner) {
                flatten(path, (Map<String, Object>) inner, out);
            } else if (v instanceof List<?> list) {
                out.add(Obj.of("path", path, "section", true, "sample", null));
                if (!list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
                    for (Map.Entry<?, ?> f : first.entrySet()) {
                        out.add(Obj.of("path", path + "[]." + f.getKey(), "section", false, "sample",
                                f.getValue() == null ? null : String.valueOf(f.getValue())));
                    }
                }
            } else {
                out.add(Obj.of("path", path, "section", false, "sample", v == null ? null : String.valueOf(v)));
            }
        }
    }

    private Map<String, Object> company(String org) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT name, code, \"currencyCode\", settings, \"receiptHeader\","
                + " \"receiptFooter\" FROM \"Organization\" WHERE id = ?").param(org));
        Map<String, Object> settings = o == null ? Map.of() : CustomFieldService.asMap(o.get("settings"));
        return Obj.of("name", o == null ? null : o.get("name"), "code", o == null ? null : o.get("code"), "currency",
                o == null ? null : o.get("currencyCode"), "address", str(settings.get("address")), "phone",
                str(settings.get("phone")), "email", str(settings.get("email")), "taxNumber",
                str(settings.getOrDefault("taxNumber", settings.get("tin"))), "header",
                o == null ? null : o.get("receiptHeader"), "footer", o == null ? null : o.get("receiptFooter"));
    }

    private Map<String, Object> partner(String org, String partnerId) {
        if (partnerId == null) {
            return Obj.of();
        }
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT name, code, email, phone, \"taxNumber\" FROM \"Partner\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(partnerId, org));
        if (p == null) {
            return Obj.of();
        }
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT line1, line2, city, state, \"postalCode\", country FROM"
                + " \"Address\" WHERE \"partnerId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"isPrimary\" DESC, \"createdAt\" LIMIT 1").params(partnerId, org));
        String address = null;
        if (a != null) {
            List<String> parts = new ArrayList<>();
            for (String k : List.of("line1", "line2", "city", "state", "postalCode", "country")) {
                if (a.get(k) != null && !String.valueOf(a.get(k)).isBlank()) {
                    parts.add(String.valueOf(a.get(k)));
                }
            }
            address = String.join(", ", parts);
        }
        return Obj.of("name", p.get("name"), "code", p.get("code"), "email", p.get("email"), "phone", p.get("phone"),
                "taxNumber", p.get("taxNumber"), "address", address);
    }

    private static Map<String, Object> document(String kind, Object number, Object status, Object issueDate,
            Object dueDate, Object reference, Object currency, Object notes, Object terms, Object subtotal,
            Object discount, Object tax, Object total, Object amountDue) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", kind);
        d.put("kindLabel", LABELS.get(kind));
        d.put("number", number);
        d.put("status", status == null ? null : String.valueOf(status));
        d.put("issueDate", day(issueDate));
        d.put("dueDate", day(dueDate));
        d.put("reference", reference);
        d.put("currency", currency);
        d.put("notes", notes);
        d.put("terms", terms);
        money(d, "subtotal", subtotal);
        money(d, "discount", discount);
        money(d, "tax", tax);
        money(d, "total", total);
        money(d, "amountDue", amountDue);
        return d;
    }

    private static List<Map<String, Object>> lines(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        int no = 1;
        for (Map<String, Object> r : rows) {
            out.add(line(no++, r.get("description"), r.get("quantity"), r.get("unit"), r.get("unitPrice"),
                    r.get("discountPercent"), r.get("tax"), r.get("subtotal"), r.get("total")));
        }
        return out;
    }

    private static Map<String, Object> line(int no, Object description, Object quantity, Object unit, Object unitPrice,
            Object discountPercent, Object tax, Object subtotal, Object total) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("no", no);
        l.put("description", description);
        l.put("quantity", quantity == null ? null : dec(quantity).stripTrailingZeros().toPlainString());
        l.put("unit", unit);
        money(l, "unitPrice", unitPrice);
        l.put("discountPercent", discountPercent == null ? "0" : dec(discountPercent).stripTrailingZeros()
                .toPlainString());
        l.put("tax", tax);
        money(l, "subtotal", subtotal);
        money(l, "total", total);
        return l;
    }

    private static void money(Map<String, Object> m, String key, Object v) {
        if (v == null) {
            m.put(key, null);
            m.put(key + "Raw", null);
            return;
        }
        BigDecimal d = dec(v).setScale(2, RoundingMode.HALF_UP);
        m.put(key, format(d));
        m.put(key + "Raw", d.toPlainString());
    }

    static String format(BigDecimal d) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(d);
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static String day(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (v instanceof LocalDateTime t) {
            return t.toLocalDate().toString();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate().toString();
        }
        String s = String.valueOf(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
