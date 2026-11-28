package com.inventoryerp.sales.application;

import com.inventoryerp.invoicing.application.SourceRef;
import com.inventoryerp.kernel.common.BusinessZone;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proforma invoices (SG-4). A proforma is a {@code proforma_invoice} Document, made from a quotation or a sales order. It
 * never posts: no journal, no receivable, no stock. Its advance terms and payment details are kept in {@code Document.data}.
 */
@Service
public class SalesProformaService {

    private static final String TYPE = "proforma_invoice";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SalesDb db;
    private final DocumentBuilderService builder;
    private final SalesSettingsService settings;
    private final SalesAccess access;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final ObjectProvider<SalesOrderService> orders;
    private final SalesLetterhead letterhead;

    public SalesProformaService(SalesDb db, DocumentBuilderService builder, SalesSettingsService settings,
            SalesAccess access, NotificationsService notifications, AuditService audit,
            ObjectProvider<SalesOrderService> orders, SalesLetterhead letterhead) {
        this.letterhead = letterhead;
        this.db = db;
        this.builder = builder;
        this.settings = settings;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> p) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = '" + TYPE
                + "'");
        where.append(access.branchClause("d.\"branchId\"", params));
        if (!blank(p.get("partnerId"))) {
            where.append(" AND d.\"partnerId\" = ?");
            params.add(p.get("partnerId"));
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (d.\"documentNumber\" ILIKE ? OR pa.name ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int size = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows("SELECT d.id, d.\"documentNumber\", d.status::text AS status, d.\"issueDate\","
                + " d.\"dueDate\", d.\"totalAmount\", d.\"partnerId\", pa.name AS \"partnerName\", d.\"sourceDocument\", d.\"branchId\","
                + " d.data->>'advanceAmount' AS \"advanceAmount\" FROM \"Document\" d LEFT JOIN \"Partner\" pa ON pa.id ="
                + " d.\"partnerId\"" + where + " ORDER BY d.\"createdAt\" DESC OFFSET " + (long) (page - 1) * size + " LIMIT " + size,
                params);
        long total = db.count("SELECT count(*) FROM \"Document\" d LEFT JOIN \"Partner\" pa ON pa.id = d.\"partnerId\"" + where,
                params);
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", size, "total", total));
    }

    /**
     * One proforma: the document row, its advance terms and payment details, the customer ({@code partnerName},
     * {@code partner}), {@code currencyCode} and its {@code lines} (with {@code unit} and {@code tax} names). Payment
     * details missing on the proforma fall back to the current Sales settings.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = builder.document(id, TYPE);
        if (d == null) {
            throw ApiException.notFound("Proforma not found");
        }
        access.assertBranchIfScoped(str(d.get("branchId")));
        Map<String, Object> out = new java.util.LinkedHashMap<>(d);
        Map<String, Object> extra = extra(id);
        out.putAll(extra);
        if (blank(str(out.get("bankDetails"))) || blank(str(out.get("mobileDetails")))) {
            // A plain read: settings() creates the row when missing, which a read-only transaction cannot do.
            Map<String, Object> s = db.row("SELECT \"proformaBankDetails\", \"proformaMobileDetails\" FROM"
                    + " \"SalesSettings\" WHERE \"organizationId\" = ?", org());
            if (s == null) {
                s = Map.of();
            }
            if (blank(str(out.get("bankDetails")))) {
                out.put("bankDetails", blank(str(s.get("proformaBankDetails"))) ? null : str(s.get("proformaBankDetails")));
            }
            if (blank(str(out.get("mobileDetails")))) {
                out.put("mobileDetails", blank(str(s.get("proformaMobileDetails"))) ? null
                        : str(s.get("proformaMobileDetails")));
            }
        }
        Map<String, Object> partner = d.get("partnerId") == null ? null : db.row("SELECT id, name, email, phone,"
                + " \"taxNumber\", district FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?", d.get("partnerId"),
                org());
        out.put("partner", partner);
        out.put("partnerName", partner == null ? null : partner.get("name"));
        Map<String, Object> cur = d.get("currencyId") == null ? null : db.row("SELECT code FROM \"Currency\" WHERE id = ?",
                d.get("currencyId"));
        out.put("currencyCode", cur == null ? null : cur.get("code"));
        out.put("lines", lines(id));
        return out;
    }

    /** Printable lines in order, with unit symbol, tax name and the product's sizing unit. */
    private List<Map<String, Object>> lines(String id) {
        return db.rows("SELECT l.id, l.\"lineNumber\", l.\"productId\", l.\"variantId\", l.\"variantName\", l.description,"
                + " l.quantity, l.\"unitPrice\", l.\"discountPercent\", l.subtotal, l.\"taxAmount\", l.total, l.pieces,"
                + " l.length, l.width, l.height, p.\"sizingUnit\", COALESCE(NULLIF(u.symbol, ''), u.code) AS unit,"
                + " t.name AS tax FROM \"DocumentLine\" l LEFT JOIN \"Product\" p ON p.id = l.\"productId\""
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\""
                + " WHERE l.\"documentId\" = ? AND l.\"organizationId\" = ? ORDER BY l.\"lineNumber\", l.id", id, org());
    }

    private Map<String, Object> extra(String id) {
        return db.row("SELECT (data->>'advancePercent')::numeric AS \"advancePercent\", (data->>'advanceAmount')::numeric AS"
                + " \"advanceAmount\", data->>'bankDetails' AS \"bankDetails\", data->>'mobileDetails' AS \"mobileDetails\","
                + " data->>'sourceOrderId' AS \"sourceOrderId\" FROM \"Document\" WHERE id = ?"
                        + " AND \"organizationId\" = ?", id, org());
    }

    /** Proforma from a sales order: its lines, prices, currency, rate and branch. */
    @Transactional
    public Map<String, Object> fromOrder(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.getObject().require(orderId);
        if ("cancelled".equals(o.get("status"))) {
            throw ApiException.badRequest("This order is cancelled");
        }
        List<LineInput> inputs = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY \"sortOrder\"", orderId, org())) {
            BigDecimal open = dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled")));
            if (open.signum() > 0) {
                inputs.add(new LineInput(str(l.get("productId")), null, null, null, null, str(l.get("description")), open,
                        dec(l.get("unitPrice")), dec(l.get("discountPercent")), null, null, null, "manual",
                        str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive"))));
            }
        }
        if (inputs.isEmpty()) {
            throw ApiException.badRequest("Add at least one line first");
        }
        Map<String, Object> cur = db.row("SELECT id FROM \"Currency\" WHERE code = ?", o.get("currencyCode"));
        HeaderInput h = new HeaderInput(str(o.get("partnerId")), LocalDate.now(BusinessZone.get()).toString(), str(in.get("dueDate")),
                str(o.get("paymentTermId")), null, null, null, null, null, null, str(o.get("ownerId")), null, null,
                str(o.get("deliveryAddress")), null, null, str(o.get("number")), cur == null ? null : str(cur.get("id")),
                dec(o.get("exchangeRate")).signum() > 0 ? dec(o.get("exchangeRate")) : BigDecimal.ONE,
                str(o.get("customerRef")), str(o.get("notes")), null, "sales_order_proforma", str(o.get("branchId")));
        Map<String, Object> doc = builder.createDocument(TYPE, new SourceRef.Sale(orderId), h, inputs);
        String id = str(doc.get("id"));
        applyTerms(id, in, orderId);
        audit.record(AuditInput.of("Document", id, "create", null, Obj.of("proformaOf", o.get("number"))));
        return get(id);
    }

    /** Advance and payment details; {@code advanceAmount} or {@code advancePercent} override the organization default. */
    @Transactional
    public void applyTerms(String id, Map<String, Object> in, String orderId) {
        Map<String, Object> s = settings.settings();
        BigDecimal total = dec(db.row("SELECT \"totalAmount\" AS t FROM \"Document\" WHERE id = ?"
                + " AND \"organizationId\" = ?",
                id, org()).get("t"));
        BigDecimal percent = in.get("advancePercent") != null ? dec(in.get("advancePercent"))
                : dec(s.get("proformaAdvancePercent"));
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw ApiException.badRequest("advancePercent must be between 0 and 100");
        }
        BigDecimal amount = in.get("advanceAmount") != null ? dec(in.get("advanceAmount"))
                : total.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        if (amount.signum() < 0 || amount.compareTo(total) > 0) {
            throw ApiException.badRequest("advanceAmount must be between 0 and the proforma total");
        }
        db.update("UPDATE \"Document\" SET data = COALESCE(data, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object("
                + "'advancePercent', ?::numeric, 'advanceAmount', ?::numeric, 'bankDetails', ?::text, 'mobileDetails', ?::text,"
                + " 'sourceOrderId', ?::text)) WHERE id = ? AND \"organizationId\" = ?", percent, amount,
                str(s.get("proformaBankDetails")), str(s.get("proformaMobileDetails")), orderId, id, org());
    }

    @Transactional(readOnly = true)
    public byte[] pdf(String id) {
        Map<String, Object> d = get(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> partner = (Map<String, Object>) d.get("partner");
        List<String> billTo = new ArrayList<>();
        if (partner != null) {
            billTo.add(str(partner.get("name")));
            for (String[] k : new String[][] {{"district", ""}, {"phone", "Tel: "}, {"email", ""}, {"taxNumber", "TIN: "}}) {
                if (!blank(str(partner.get(k[0])))) {
                    billTo.add(k[1] + str(partner.get(k[0])).trim());
                }
            }
        }
        if (billTo.isEmpty()) {
            billTo.add("-");
        }
        QuoteLayout layout = settings.quoteLayout();
        SalesLetterhead.Head head = letterhead.company(layout.companyName());
        List<SalesQuotationPdf.Fact> facts = new ArrayList<>();
        facts.add(new SalesQuotationPdf.Fact("Date", day(d.get("issueDate"))));
        facts.add(new SalesQuotationPdf.Fact("Valid until", d.get("dueDate") == null ? null : day(d.get("dueDate"))));
        facts.add(new SalesQuotationPdf.Fact("Reference", str(d.get("sourceDocument"))));
        facts.add(new SalesQuotationPdf.Fact("Customer ref", str(d.get("reference"))));
        if (layout.showSalesperson()) {
            facts.add(new SalesQuotationPdf.Fact("Salesperson", str(d.get("salespersonName"))));
        }
        BigDecimal total = dec(d.get("totalAmount"));
        BigDecimal tax = dec(d.get("taxAmount"));
        BigDecimal discount = dec(d.get("discountTotal"));
        BigDecimal subtotal = d.get("subtotal") == null ? total.subtract(tax) : dec(d.get("subtotal"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.get("lines");
        return SalesProformaPdf.render(new SalesProformaPdf.Proforma(new SalesQuotationPdf.Company(head.name(),
                head.lines(), head.logo()), str(d.get("documentNumber")), facts, billTo, str(d.get("deliveryAddress")),
                lines, str(d.get("currencyCode")), subtotal, discount, tax, total, d.get("advancePercent") == null ? null
                        : dec(d.get("advancePercent")), dec(d.get("advanceAmount")), str(d.get("bankDetails")),
                str(d.get("mobileDetails")), str(d.get("notes"))), layout);
    }

    @Transactional
    public Map<String, Object> email(String id, String to) {
        Map<String, Object> d = get(id);
        Map<String, Object> p = db.row("SELECT name, email FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?", d.get("partnerId"), org());
        String address = !blank(to) ? to.trim() : p == null ? null : str(p.get("email"));
        if (blank(address)) {
            throw ApiException.badRequest("Customer has no email on file. Add one in Partners or pass \"to\".");
        }
        boolean sent = notifications.sendExternalEmail(address, "Proforma invoice " + d.get("documentNumber") + " from "
                + company(settings.quoteLayout()), "Please find attached proforma invoice " + d.get("documentNumber") + ".", new NotificationsService.Attachment(
                        d.get("documentNumber") + ".pdf", "application/pdf", pdf(id)));
        return Obj.of("ok", true, "sentTo", address, "delivered", sent, "advanceAmount", JsNumbers.js(dec(d.get(
                "advanceAmount"))));
    }

    /** The printed company name: the quotation layout's override, else the company settings name. */
    private String company(QuoteLayout layout) {
        return letterhead.company(layout.companyName()).name();
    }

    private static String day(Object v) {
        String s = String.valueOf(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }
}
