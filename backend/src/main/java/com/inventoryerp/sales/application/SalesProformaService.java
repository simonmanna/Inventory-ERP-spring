package com.inventoryerp.sales.application;

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

    public SalesProformaService(SalesDb db, DocumentBuilderService builder, SalesSettingsService settings,
            SalesAccess access, NotificationsService notifications, AuditService audit,
            ObjectProvider<SalesOrderService> orders) {
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

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> d = builder.document(id, TYPE);
        access.assertBranchIfScoped(str(d.get("branchId")));
        Map<String, Object> out = new java.util.LinkedHashMap<>(d);
        Map<String, Object> extra = extra(id);
        out.putAll(extra);
        return out;
    }

    private Map<String, Object> extra(String id) {
        return db.row("SELECT (data->>'advancePercent')::numeric AS \"advancePercent\", (data->>'advanceAmount')::numeric AS"
                + " \"advanceAmount\", data->>'bankDetails' AS \"bankDetails\", data->>'mobileDetails' AS \"mobileDetails\","
                + " data->>'sourceOrderId' AS \"sourceOrderId\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?", id, org());
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
        HeaderInput h = new HeaderInput(str(o.get("partnerId")), LocalDate.now().toString(), str(in.get("dueDate")),
                str(o.get("paymentTermId")), null, null, null, null, null, null, str(o.get("ownerId")), null, null,
                str(o.get("deliveryAddress")), null, null, str(o.get("number")), cur == null ? null : str(cur.get("id")),
                dec(o.get("exchangeRate")).signum() > 0 ? dec(o.get("exchangeRate")) : BigDecimal.ONE,
                str(o.get("customerRef")), str(o.get("notes")), null, "sales_order_proforma", str(o.get("branchId")));
        Map<String, Object> doc = builder.createDocument(TYPE, h, inputs);
        String id = str(doc.get("id"));
        applyTerms(id, in, orderId);
        audit.record(AuditInput.of("Document", id, "create", null, Obj.of("proformaOf", o.get("number"))));
        return get(id);
    }

    /** Advance and payment details; {@code advanceAmount} or {@code advancePercent} override the organization default. */
    @Transactional
    public void applyTerms(String id, Map<String, Object> in, String orderId) {
        Map<String, Object> s = settings.settings();
        BigDecimal total = dec(db.row("SELECT \"totalAmount\" AS t FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?",
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
        Map<String, Object> p = db.row("SELECT name FROM \"Partner\" WHERE id = ?", d.get("partnerId"));
        List<Map<String, Object>> lines = db.rows("SELECT description, quantity, \"unitPrice\", \"discountPercent\", total FROM"
                + " \"DocumentLine\" WHERE \"documentId\" = ?", id);
        Map<String, Object> cur = d.get("currencyId") == null ? null : db.row("SELECT code FROM \"Currency\" WHERE id = ?",
                d.get("currencyId"));
        List<SalesDocumentPdf.Total> totals = new ArrayList<>();
        totals.add(new SalesDocumentPdf.Total("Total", dec(d.get("totalAmount"))));
        if (dec(d.get("advanceAmount")).signum() > 0) {
            totals.add(new SalesDocumentPdf.Total("Advance required", dec(d.get("advanceAmount"))));
        }
        return SalesDocumentPdf.render(company(), "PROFORMA INVOICE", str(d.get("documentNumber")), List.of(
                "Customer: " + (p == null ? "" : p.get("name")), "Date: " + day(d.get("issueDate")),
                "Not a tax invoice. No amount is due until an invoice is issued."), lines, cur == null ? null
                        : str(cur.get("code")), totals, List.of(new SalesDocumentPdf.Block("Bank details",
                                str(d.get("bankDetails"))), new SalesDocumentPdf.Block("Mobile money",
                                        str(d.get("mobileDetails"))), new SalesDocumentPdf.Block("Notes",
                                                str(d.get("notes")))));
    }

    @Transactional
    public Map<String, Object> email(String id, String to) {
        Map<String, Object> d = get(id);
        Map<String, Object> p = db.row("SELECT name, email FROM \"Partner\" WHERE id = ?", d.get("partnerId"));
        String address = !blank(to) ? to.trim() : p == null ? null : str(p.get("email"));
        if (blank(address)) {
            throw ApiException.badRequest("Customer has no email on file. Add one in Partners or pass \"to\".");
        }
        boolean sent = notifications.sendExternalEmail(address, "Proforma invoice " + d.get("documentNumber") + " from "
                + company(), "Please find attached proforma invoice " + d.get("documentNumber") + ".", new NotificationsService.Attachment(
                        d.get("documentNumber") + ".pdf", "application/pdf", pdf(id)));
        return Obj.of("ok", true, "sentTo", address, "delivered", sent, "advanceAmount", JsNumbers.js(dec(d.get(
                "advanceAmount"))));
    }

    private String company() {
        return str(db.row("SELECT COALESCE(NULLIF(\"receiptHeader\"->>'businessName', ''), name) AS n FROM \"Organization\""
                + " WHERE id = ?", org()).get("n"));
    }

    private static String day(Object v) {
        String s = String.valueOf(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }
}
