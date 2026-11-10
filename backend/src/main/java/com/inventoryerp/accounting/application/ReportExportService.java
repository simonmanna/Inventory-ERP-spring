package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV and PDF report exports (port of {@code export.controller.ts}): trial balance CSV, AR aging CSV and the invoice
 * PDF. CSV follows {@code csv-stringify} defaults (header row, {@code \n} records, trailing newline, quoting only when
 * needed); numbers use JavaScript {@code Number(x).toFixed(2)}.
 */
@Service
public class ReportExportService {

    /** A rendered export: download file name and content. */
    public record Export(String filename, byte[] content) {}

    private final JdbcClient jdbc;

    public ReportExportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** JavaScript {@code Number(value).toFixed(2)} (rounds the exact binary value half-up on the magnitude). */
    static String toFixed2(Object value) {
        double d = value == null ? 0 : value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
        if (Double.isNaN(d)) {
            return "NaN";
        }
        String abs = new BigDecimal(Math.abs(d)).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return d < 0 ? "-" + abs : abs;
    }

    private static String csvField(Object v) {
        String s = v == null ? "" : v.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String csv(List<String> columns, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder(String.join(",", columns)).append('\n');
        for (List<Object> row : rows) {
            out.append(String.join(",", row.stream().map(ReportExportService::csvField).toList())).append('\n');
        }
        return out.toString();
    }

    private static String day(Instant i) {
        return JsonConfig.toJsIso(i).substring(0, 10);
    }

    @Transactional(readOnly = true)
    public Export trialBalanceCsv(String asOf) {
        String org = TenantContext.organizationId();
        Instant date = asOf != null && !asOf.isEmpty() ? LedgerQueries.date(asOf, AccountingDay.zone(jdbc)) : Instant.now();
        // Verbatim Nest SQL: the entry filter sits in the LEFT JOIN condition.
        List<List<Object>> rows = SqlRows.list(jdbc.sql("SELECT a.code, a.name, ac.key AS \"categoryKey\", ac.name AS \"categoryName\","
                        + " ac.\"classification\"::text AS classification, COALESCE(SUM(jl.\"baseDebit\"), 0)::text AS debit,"
                        + " COALESCE(SUM(jl.\"baseCredit\"), 0)::text AS credit,"
                        + " COALESCE(SUM(jl.\"baseDebit\" - jl.\"baseCredit\"), 0)::text AS balance"
                        + " FROM \"Account\" a LEFT JOIN \"AccountCategory\" ac ON ac.id = a.\"categoryId\""
                        + " LEFT JOIN \"JournalLine\" jl ON jl.\"accountId\" = a.id"
                        + " LEFT JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" AND je.status IN ('posted', 'reversed')"
                        + " AND je.\"postingDate\" <= ? WHERE a.\"organizationId\" = ?"
                        + " GROUP BY a.id, a.code, a.name, a.\"sortOrder\", ac.key, ac.name, ac.\"classification\""
                        + " ORDER BY a.\"sortOrder\", a.code").params(FiscalPeriodControl.ts(date), org))
                .stream().map(r -> List.<Object>of(r.get("code"), r.get("name"),
                        r.get("categoryName") != null ? r.get("categoryName") : r.get("categoryKey") != null ? r.get("categoryKey") : "",
                        r.get("classification") != null ? r.get("classification") : "",
                        toFixed2(r.get("debit")), toFixed2(r.get("credit")), toFixed2(r.get("balance"))))
                .toList();
        String body = csv(List.of("code", "name", "category", "classification", "debit", "credit", "balance"), rows);
        return new Export("trial-balance-" + day(date) + ".csv", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public Export arAgingCsv() {
        String org = TenantContext.organizationId();
        Instant now = Instant.now();
        List<List<Object>> rows = SqlRows.list(jdbc.sql("SELECT d.\"documentNumber\", p.name AS partner, d.\"issueDate\", d.\"dueDate\","
                        + " d.\"totalAmount\", d.\"amountResidual\" FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\""
                        + " WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice' AND d.status::text = 'posted'"
                        + " AND d.\"amountResidual\" > 0 ORDER BY d.\"dueDate\" ASC").param(org))
                .stream().map(d -> {
                    Instant due = (Instant) d.get("dueDate");
                    return List.<Object>of(d.get("documentNumber"), d.get("partner") != null ? d.get("partner") : "",
                            day((Instant) d.get("issueDate")), due != null ? day(due) : "", bucket(due, now),
                            toFixed2(SqlRows.dec(d, "totalAmount").doubleValue()),
                            toFixed2(SqlRows.dec(d, "amountResidual").doubleValue()));
                })
                .toList();
        String body = csv(List.of("documentNumber", "partner", "issueDate", "dueDate", "bucket", "amount", "residual"), rows);
        return new Export("ar-aging-" + day(now) + ".csv", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String bucket(Instant due, Instant now) {
        if (due == null || !due.isBefore(now)) {
            return "Current";
        }
        long diffDays = Duration.between(due, now).toMillis() / 86_400_000L;
        if (diffDays <= 30) {
            return "1-30";
        }
        if (diffDays <= 60) {
            return "31-60";
        }
        if (diffDays <= 90) {
            return "61-90";
        }
        return "90+";
    }

    /**
     * Invoice / document PDF; {@code null} when not found. Like Nest, the id comes from the {@code ?id=} query
     * parameter (the path segment is ignored); without it the organization's first document is rendered.
     */
    @Transactional(readOnly = true)
    public Export invoicePdf(String id) {
        String org = TenantContext.organizationId();
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT d.*, d.\"documentType\"::text AS doc_type, p.name AS partner_name"
                + " FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = :org"
                + (id != null ? " AND d.id = :id" : "") + " LIMIT 1").param("org", org).params(id != null ? Map.of("id", id) : Map.of()));
        if (doc == null) {
            return null;
        }
        Map<String, Object> organization = SqlRows.one(jdbc.sql("SELECT name, settings->>'address' AS address FROM \"Organization\""
                + " WHERE id = ?").param(org));
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT description, quantity, \"unitPrice\", total"
                + " FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(doc.get("id")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 50, 50, 50, 50);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        Font gray10 = FontFactory.getFont(FontFactory.HELVETICA, 10, java.awt.Color.GRAY);
        Font f10 = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font f11u = FontFactory.getFont(FontFactory.HELVETICA, 11, Font.UNDERLINE);
        Font f12 = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Font f14 = FontFactory.getFont(FontFactory.HELVETICA, 14);

        pdf.add(new Paragraph(organization != null && organization.get("name") != null ? (String) organization.get("name") : "ERP",
                FontFactory.getFont(FontFactory.HELVETICA, 20)));
        pdf.add(new Paragraph(organization != null && organization.get("address") != null ? (String) organization.get("address") : "",
                gray10));
        pdf.add(new Paragraph(" "));
        String type = (String) doc.get("doc_type");
        pdf.add(aligned("sales_invoice".equals(type) ? "INVOICE" : type.toUpperCase(), FontFactory.getFont(FontFactory.HELVETICA, 18),
                Element.ALIGN_RIGHT));
        pdf.add(aligned("# " + doc.get("documentNumber"), gray10, Element.ALIGN_RIGHT));
        pdf.add(new Paragraph(" "));
        pdf.add(new Paragraph("Issue date: " + day((Instant) doc.get("issueDate")), f10));
        if (doc.get("dueDate") != null) {
            pdf.add(new Paragraph("Due date: " + day((Instant) doc.get("dueDate")), f10));
        }
        if (doc.get("reference") != null && !((String) doc.get("reference")).isEmpty()) {
            pdf.add(new Paragraph("Reference: " + doc.get("reference"), f10));
        }
        pdf.add(new Paragraph(" "));
        pdf.add(new Paragraph("Bill to:", f11u));
        pdf.add(new Paragraph(doc.get("partner_name") != null ? (String) doc.get("partner_name") : "", f10));
        pdf.add(new Paragraph(" "));
        pdf.add(new Paragraph("Items:", f11u));
        for (Map<String, Object> ln : lines) {
            pdf.add(new Paragraph(ln.get("description") + "    qty " + PostingService.plain(SqlRows.dec(ln, "quantity")) + " × "
                    + toFixed2(SqlRows.dec(ln, "unitPrice").doubleValue()) + "  =  " + toFixed2(SqlRows.dec(ln, "total").doubleValue()), f10));
        }
        pdf.add(new Paragraph(" "));
        pdf.add(aligned("Subtotal: " + toFixed2(SqlRows.dec(doc, "subtotal").doubleValue()), f12, Element.ALIGN_RIGHT));
        pdf.add(aligned("Tax: " + toFixed2(SqlRows.dec(doc, "taxAmount").doubleValue()), f12, Element.ALIGN_RIGHT));
        pdf.add(aligned("Total: " + toFixed2(SqlRows.dec(doc, "totalAmount").doubleValue()), f14, Element.ALIGN_RIGHT));
        pdf.add(aligned("Amount due: " + toFixed2(SqlRows.dec(doc, "amountResidual").doubleValue()), f14, Element.ALIGN_RIGHT));
        if (doc.get("notes") != null && !((String) doc.get("notes")).isEmpty()) {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Notes: " + doc.get("notes"), gray10));
        }
        pdf.close();
        return new Export(doc.get("documentNumber") + ".pdf", out.toByteArray());
    }

    private static Paragraph aligned(String text, Font font, int alignment) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(alignment);
        return p;
    }
}
