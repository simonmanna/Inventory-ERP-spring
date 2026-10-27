package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.ReportExportService;
import com.inventoryerp.accounting.application.ReportExportService.Export;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.Attachment;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice PDF and e-mail delivery (ADR-S005). The PDF is the existing A4 invoice export; e-mail goes to the customer
 * (or an explicit address) over the configured SMTP transport and stamps {@code Document.data.lastEmailedAt}.
 */
@Service
public class InvoiceDeliveryService {

    private final JdbcClient jdbc;
    private final ReportExportService exports;
    private final NotificationsService notifications;

    public InvoiceDeliveryService(JdbcClient jdbc, ReportExportService exports, NotificationsService notifications) {
        this.jdbc = jdbc;
        this.exports = exports;
        this.notifications = notifications;
    }

    private Map<String, Object> invoice(String id) {
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT d.id, d.\"documentNumber\", d.status::text AS status,"
                + " d.\"totalAmount\", d.\"amountResidual\", d.\"dueDate\", p.name AS \"partnerName\", p.email AS"
                + " \"partnerEmail\" FROM \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.id = ? AND"
                + " d.\"organizationId\" = ? AND d.\"documentType\"::text IN ('sales_invoice', 'credit_note')")
                .params(id, TenantContext.organizationId()));
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        return doc;
    }

    @Transactional(readOnly = true)
    public Export pdf(String id) {
        invoice(id);
        return exports.invoicePdf(id);
    }

    @Transactional
    public Map<String, Object> email(String id, String to, String message) {
        Map<String, Object> doc = invoice(id);
        if ("draft".equals(doc.get("status")) || "cancelled".equals(doc.get("status"))) {
            throw ApiException.badRequest("Only posted invoices can be emailed");
        }
        String address = to != null && !to.isBlank() ? to.trim() : (String) doc.get("partnerEmail");
        if (address == null || address.isBlank()) {
            throw ApiException.badRequest("Customer has no email on file. Add one in Partners or pass \"to\".");
        }
        String company = jdbc.sql("SELECT COALESCE(NULLIF(\"receiptHeader\"->>'businessName', ''), name) FROM"
                + " \"Organization\" WHERE id = ?").param(TenantContext.organizationId()).query(String.class).single();
        Export pdf = exports.invoicePdf(id);
        String subject = "Invoice " + doc.get("documentNumber") + " from " + company;
        String body = (message != null && !message.isBlank() ? message.trim() + "\n\n" : "")
                + "Dear " + doc.get("partnerName") + ",\n\nPlease find attached invoice " + doc.get("documentNumber")
                + ". Amount due: " + StatementService.plain(SqlRows.dec(doc, "amountResidual"))
                + (doc.get("dueDate") != null ? " (due " + String.valueOf(doc.get("dueDate")).substring(0, 10) + ")" : "")
                + ".\n\n" + company;
        boolean sent = notifications.sendExternalEmail(address, subject, body,
                new Attachment(pdf.filename(), "application/pdf", pdf.content()));
        jdbc.sql("UPDATE \"Document\" SET data = COALESCE(data, '{}'::jsonb) || jsonb_build_object('lastEmailedAt',"
                        + " to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), 'lastEmailedTo', ?::text)"
                        + " WHERE id = ?")
                .params(address, id).update();
        return Obj.of("ok", true, "sentTo", address, "delivered", sent, "message",
                sent ? "Invoice emailed to " + address : "Email transport not configured; message logged");
    }
}
