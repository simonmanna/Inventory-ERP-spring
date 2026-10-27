package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.LedgerQueries;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.Attachment;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer / supplier statements (ADR-S005), built from the GL: posted lines on the partner's AR (or AP) control
 * accounts that carry the partner, with the balance brought forward, a running balance and the closing balance,
 * plus aging of the partner's open documents. Rendered as JSON, A4 PDF, or e-mailed as a PDF attachment.
 */
@Service
public class StatementService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final NotificationsService notifications;

    public StatementService(JdbcClient jdbc, NotificationsService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    static String plain(BigDecimal v) {
        return PostingService.plain(v.setScale(2, RoundingMode.HALF_UP));
    }

    /** {@code side}: {@code ar} (customer, debit-normal) or {@code ap} (supplier, credit-normal). */
    @Transactional(readOnly = true)
    public Map<String, Object> statement(String side, String partnerId, String fromRaw, String toRaw) {
        boolean ar = "ar".equals(side);
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT id, code, name, email, phone, \"taxNumber\","
                + " \"receivableAccountId\", \"payableAccountId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org()));
        if (partner == null) {
            throw ApiException.notFound("Partner not found");
        }
        Instant from = LedgerQueries.date(fromRaw);
        Instant to = LedgerQueries.date(toRaw);
        Object own = partner.get(ar ? "receivableAccountId" : "payableAccountId");
        List<String> accounts = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND"
                        + " (\"controlAccountType\"::text = ? OR id = ?)")
                .params(org(), side, own == null ? "" : own).query(String.class).list();
        if (accounts.isEmpty()) {
            accounts = List.of("");
        }
        String base = " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE"
                + " l.\"organizationId\" = :org AND l.\"partnerId\" = :partner AND l.\"accountId\" IN (:accounts)"
                + " AND e.status::text IN ('posted', 'reversed')";
        BigDecimal opening = BigDecimal.ZERO;
        if (from != null) {
            Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT COALESCE(sum(l.\"baseDebit\"), 0) AS d,"
                            + " COALESCE(sum(l.\"baseCredit\"), 0) AS c" + base + " AND e.\"postingDate\" < :from")
                    .param("org", org()).param("partner", partnerId).param("accounts", accounts)
                    .param("from", SqlRows.ts(from)));
            opening = signed(ar, SqlRows.dec(o, "d"), SqlRows.dec(o, "c"));
        }
        StringBuilder sql = new StringBuilder("SELECT e.id AS \"entryId\", e.\"entryNumber\", e.\"postingDate\","
                + " e.description AS \"entryDescription\", e.\"sourceType\", e.\"sourceId\", l.description,"
                + " l.\"baseDebit\" AS debit, l.\"baseCredit\" AS credit, d.\"documentNumber\", d.\"dueDate\"" + base
                .replace("WHERE", "LEFT JOIN \"Document\" d ON d.id = e.\"sourceId\" WHERE"));
        if (from != null) {
            sql.append(" AND e.\"postingDate\" >= :from");
        }
        if (to != null) {
            sql.append(" AND e.\"postingDate\" <= :to");
        }
        sql.append(" ORDER BY e.\"postingDate\", e.\"createdAt\", l.\"lineNumber\"");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("org", org()).param("partner", partnerId)
                .param("accounts", accounts);
        if (from != null) {
            spec = spec.param("from", SqlRows.ts(from));
        }
        if (to != null) {
            spec = spec.param("to", SqlRows.ts(to));
        }
        BigDecimal running = opening;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(spec)) {
            BigDecimal d = SqlRows.dec(r, "debit");
            BigDecimal c = SqlRows.dec(r, "credit");
            running = running.add(signed(ar, d, c));
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
            Object ref = r.get("documentNumber") != null ? r.get("documentNumber") : r.get("entryNumber");
            Object desc = r.get("description") != null ? r.get("description") : r.get("entryDescription");
            lines.add(Obj.of("date", r.get("postingDate"), "entryId", r.get("entryId"), "entryNumber",
                    r.get("entryNumber"), "reference", ref, "type", typeOf((String) r.get("sourceType")),
                    "description", desc, "dueDate", r.get("dueDate"), "debit", plain(d), "credit", plain(c),
                    "balance", plain(running)));
        }
        Map<String, Object> aging = aging(ar, partnerId, to != null ? to : Instant.now());
        Map<String, Object> org = SqlRows.one(jdbc.sql("SELECT name, \"currencyCode\", \"receiptHeader\" FROM"
                + " \"Organization\" WHERE id = ?").param(org()));
        return Obj.of("side", side, "partner", Obj.of("id", partner.get("id"), "code", partner.get("code"), "name",
                        partner.get("name"), "email", partner.get("email"), "phone", partner.get("phone"), "taxNumber",
                        partner.get("taxNumber")),
                "organization", org, "from", fromRaw, "to", toRaw, "openingBalance", plain(opening),
                "totalDebit", plain(totalDebit), "totalCredit", plain(totalCredit), "closingBalance", plain(running),
                "lines", lines, "aging", aging);
    }

    private static BigDecimal signed(boolean ar, BigDecimal debit, BigDecimal credit) {
        return ar ? debit.subtract(credit) : credit.subtract(debit);
    }

    private static String typeOf(String sourceType) {
        if (sourceType == null) {
            return "journal";
        }
        String s = sourceType.toLowerCase();
        if (s.contains("credit")) {
            return "credit_note";
        }
        if (s.contains("debit_note")) {
            return "debit_note";
        }
        if (s.contains("payment") || s.contains("receipt")) {
            return "payment";
        }
        if (s.contains("bill")) {
            return "bill";
        }
        if (s.contains("invoice") || s.contains("pos")) {
            return "invoice";
        }
        return s;
    }

    /** Open documents bucketed by days past due ({@code current}, 1-30, 31-60, 61-90, 90+). */
    private Map<String, Object> aging(boolean ar, String partnerId, Instant asOf) {
        List<Map<String, Object>> open = new ArrayList<>(SqlRows.list(jdbc.sql("SELECT \"documentNumber\","
                + " \"issueDate\", \"dueDate\", \"totalAmount\", \"amountResidual\" FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text = ? AND status::text IN"
                + " ('posted', 'paid') AND \"amountResidual\" > 0 ORDER BY \"dueDate\" NULLS LAST")
                .params(org(), partnerId, ar ? "sales_invoice" : "vendor_bill")));
        if (ar) {
            open.addAll(SqlRows.list(jdbc.sql("SELECT \"invoiceNumber\" AS \"documentNumber\", \"issueDate\","
                    + " \"dueDate\", \"totalAmount\", \"amountResidual\" FROM \"Invoice\" WHERE \"organizationId\" = ? AND"
                    + " \"partnerId\" = ? AND status::text IN ('posted', 'paid') AND \"amountResidual\" > 0")
                    .params(org(), partnerId)));
        }
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        for (String b : List.of("current", "d1_30", "d31_60", "d61_90", "d90_plus")) {
            buckets.put(b, BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : open) {
            Instant due = (Instant) (d.get("dueDate") != null ? d.get("dueDate") : d.get("issueDate"));
            long days = (long) Math.floor((asOf.toEpochMilli() - due.toEpochMilli()) / 86_400_000.0);
            String bucket = days <= 0 ? "current" : days <= 30 ? "d1_30" : days <= 60 ? "d31_60" : days <= 90
                    ? "d61_90" : "d90_plus";
            BigDecimal residual = SqlRows.dec(d, "amountResidual");
            buckets.merge(bucket, residual, BigDecimal::add);
            total = total.add(residual);
            docs.add(Obj.of("documentNumber", d.get("documentNumber"), "issueDate", d.get("issueDate"), "dueDate",
                    d.get("dueDate"), "total", plain(SqlRows.dec(d, "totalAmount")), "residual", plain(residual),
                    "daysOverdue", Math.max(0, days), "bucket", bucket));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        buckets.forEach((k, v) -> b.put(k, plain(v)));
        return Obj.of("asOf", asOf, "buckets", b, "totalOpen", plain(total), "documents", docs);
    }

    // ------------------------------------------------------------------ PDF + e-mail

    /** A4 statement PDF. */
    @Transactional(readOnly = true)
    public byte[] pdf(String side, String partnerId, String from, String to) {
        return render(statement(side, partnerId, from, to));
    }

    /** E-mail the statement PDF to {@code to} (default: the partner's e-mail). */
    @Transactional(readOnly = true)
    public Map<String, Object> email(String side, String partnerId, String from, String to, String recipient,
            String message) {
        Map<String, Object> st = statement(side, partnerId, from, to);
        @SuppressWarnings("unchecked")
        Map<String, Object> partner = (Map<String, Object>) st.get("partner");
        String address = recipient != null && !recipient.isBlank() ? recipient.trim() : (String) partner.get("email");
        if (address == null || address.isBlank()) {
            throw ApiException.badRequest("Partner has no email on file. Add one in Partners or pass \"to\".");
        }
        String company = companyName(st);
        String subject = "Statement of account — " + company;
        String body = (message != null && !message.isBlank() ? message.trim() + "\n\n" : "")
                + "Dear " + partner.get("name") + ",\n\nPlease find attached your statement of account. Closing balance: "
                + st.get("closingBalance") + ".\n\n" + company;
        boolean sent = notifications.sendExternalEmail(address, subject, body,
                new Attachment("statement-" + partner.get("name") + ".pdf", "application/pdf", render(st)));
        return Obj.of("ok", true, "sentTo", address, "delivered", sent, "message",
                sent ? "Statement emailed to " + address : "Email transport not configured; message logged");
    }

    static String companyName(Map<String, Object> st) {
        @SuppressWarnings("unchecked")
        Map<String, Object> org = (Map<String, Object>) st.get("organization");
        if (org == null) {
            return "ERP";
        }
        Object header = org.get("receiptHeader");
        if (header instanceof tools.jackson.databind.JsonNode n && n.hasNonNull("businessName")
                && !n.get("businessName").asString().isBlank()) {
            return n.get("businessName").asString();
        }
        return String.valueOf(org.get("name"));
    }

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> st) {
        boolean ar = "ar".equals(st.get("side"));
        Map<String, Object> partner = (Map<String, Object>) st.get("partner");
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(companyName(st), title));
        Paragraph heading = new Paragraph((ar ? "CUSTOMER" : "SUPPLIER") + " STATEMENT", bold);
        heading.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(heading);
        pdf.add(new Paragraph(String.valueOf(partner.get("name")), bold));
        if (partner.get("email") != null) {
            pdf.add(new Paragraph(String.valueOf(partner.get("email")), small));
        }
        pdf.add(new Paragraph("Period: " + (st.get("from") == null ? "beginning" : st.get("from")) + " to "
                + (st.get("to") == null ? "today" : st.get("to")), small));
        pdf.add(new Paragraph(" "));
        PdfPTable t = new PdfPTable(new float[] {1.3f, 1.4f, 3.2f, 1.3f, 1.3f, 1.4f});
        t.setWidthPercentage(100);
        for (String h : List.of("Date", "Reference", "Description", "Debit", "Credit", "Balance")) {
            t.addCell(cell(h, bold, Element.ALIGN_LEFT));
        }
        t.addCell(cell("", small, Element.ALIGN_LEFT));
        t.addCell(cell("", small, Element.ALIGN_LEFT));
        t.addCell(cell("Balance brought forward", bold, Element.ALIGN_LEFT));
        t.addCell(cell("", small, Element.ALIGN_RIGHT));
        t.addCell(cell("", small, Element.ALIGN_RIGHT));
        t.addCell(cell(String.valueOf(st.get("openingBalance")), bold, Element.ALIGN_RIGHT));
        for (Map<String, Object> l : (List<Map<String, Object>>) st.get("lines")) {
            t.addCell(cell(l.get("date") instanceof Instant i ? DAY.format(i) : "", small, Element.ALIGN_LEFT));
            t.addCell(cell(String.valueOf(l.get("reference")), small, Element.ALIGN_LEFT));
            t.addCell(cell(l.get("description") == null ? "" : String.valueOf(l.get("description")), small,
                    Element.ALIGN_LEFT));
            t.addCell(cell(zeroBlank(l.get("debit")), small, Element.ALIGN_RIGHT));
            t.addCell(cell(zeroBlank(l.get("credit")), small, Element.ALIGN_RIGHT));
            t.addCell(cell(String.valueOf(l.get("balance")), small, Element.ALIGN_RIGHT));
        }
        pdf.add(t);
        Paragraph closing = new Paragraph("Closing balance: " + st.get("closingBalance"),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12));
        closing.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(closing);
        Map<String, Object> aging = (Map<String, Object>) st.get("aging");
        Map<String, Object> buckets = (Map<String, Object>) aging.get("buckets");
        pdf.add(new Paragraph(" "));
        pdf.add(new Paragraph("Aging of open items", bold));
        PdfPTable a = new PdfPTable(6);
        a.setWidthPercentage(100);
        for (String h : List.of("Current", "1-30", "31-60", "61-90", "90+", "Total open")) {
            a.addCell(cell(h, bold, Element.ALIGN_RIGHT));
        }
        for (String k : List.of("current", "d1_30", "d31_60", "d61_90", "d90_plus")) {
            a.addCell(cell(String.valueOf(buckets.get(k)), small, Element.ALIGN_RIGHT));
        }
        a.addCell(cell(String.valueOf(aging.get("totalOpen")), bold, Element.ALIGN_RIGHT));
        pdf.add(a);
        pdf.close();
        return out.toByteArray();
    }

    private static String zeroBlank(Object v) {
        String s = String.valueOf(v);
        return "0".equals(s) || "0.00".equals(s) ? "" : s;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }
}
