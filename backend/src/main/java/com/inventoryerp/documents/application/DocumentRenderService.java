package com.inventoryerp.documents.application;

import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * DMS render service (port of {@code document-render.service.ts}): active TemplateDefinition for (type, format),
 * render context from the latest snapshot (issued / posted / active documents) or live data, {@code {{key}}}
 * substitution. {@code a4} PDFs are produced with OpenPDF from the rendered HTML's text (Nest drives headless
 * Chrome — see the documents spec "Deviations").
 */
@Service
public class DocumentRenderService {

    private final JdbcClient jdbc;
    private final DmsStore store;

    public DocumentRenderService(JdbcClient jdbc, DmsStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** 400 {@code Unsupported format 'x' (a4|thermal|html|plain|email)}. */
    public void assertFormat(String format) {
        if (!DmsRules.TEMPLATE_FORMATS.contains(format)) {
            throw ApiException.badRequest("Unsupported format '" + format + "' (a4|thermal|html|plain|email)");
        }
    }

    /** 400 {@code Unsupported template engine 'x'}. */
    public void assertEngine(String engine) {
        if (!DmsRules.TEMPLATE_ENGINES.contains(engine)) {
            throw ApiException.badRequest("Unsupported template engine '" + engine + "'");
        }
    }

    record Template(String templateKey, String versionId, String content) {}

    Template resolveTemplate(Object typeId, String typeCode, String format) {
        Map<String, Object> tpl = jdbc.sql("SELECT id, \"templateKey\", content FROM \"TemplateDefinition\" WHERE"
                        + " \"documentTypeId\" = ? AND format = ? AND \"isActive\" = true ORDER BY version DESC LIMIT 1")
                .params(typeId, format).query().listOfRows().stream().findFirst().orElse(null);
        if (tpl != null) {
            return new Template((String) tpl.get("templateKey"), (String) tpl.get("id"), (String) tpl.get("content"));
        }
        if ("thermal".equals(format)) {
            return new Template(typeCode + ":thermal:fallback", null, null);
        }
        return new Template(typeCode + ":a4:fallback", null, null);
    }

    /** {@code RenderOutcome} for a raw document row + lines of type (code, name, id). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> render(String orgId, Map<String, Object> doc, List<Map<String, Object>> lines,
            String typeCode, String typeName, Object typeId, String format) {
        Template tpl = resolveTemplate(typeId, typeCode, format);
        Map<String, Object> latest = jdbc.sql("SELECT id, data FROM \"DocumentSnapshot\" WHERE \"documentId\" = ? AND"
                        + " \"organizationId\" = ? ORDER BY \"generatedAt\" DESC LIMIT 1")
                .params(doc.get("id"), orgId).query().listOfRows().stream().findFirst().orElse(null);
        String status = String.valueOf(doc.get("status"));
        boolean issued = "posted".equals(status) || "issued".equals(status) || "active".equals(status);
        boolean fromSnapshot = latest != null && issued;
        Map<String, Object> data = fromSnapshot ? (Map<String, Object>) store.json(latest.get("data"))
                : DmsSnapshot.buildSnapshotData(doc, lines, typeCode, typeName);
        Map<String, Object> ctx = DmsSnapshot.toRenderContext(data);
        ctx.put("companyName", "POS-CAFE");
        String content = tpl.content() != null ? tpl.content() : fallbackTemplate(format);
        String rendered = DmsSnapshot.renderTemplate(content, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", format);
        if ("html".equals(format)) {
            out.put("contentType", "text/html");
            out.put("content", rendered);
        } else if ("a4".equals(format)) {
            byte[] pdf = htmlToPdf(rendered);
            out.put("contentType", "application/pdf");
            out.put("content", "PDF " + pdf.length + " bytes (seal-reproducible)");
            out.put("base64", Base64.getEncoder().encodeToString(pdf));
            out.put("pdf", true);
        } else {
            out.put("contentType", "thermal".equals(format) ? "text/plain; charset=escpos" : "text/plain");
            out.put("content", rendered);
        }
        out.put("templateKey", tpl.templateKey());
        out.put("templateVersionId", tpl.versionId());
        out.put("renderedFrom", fromSnapshot ? "snapshot" : "live");
        out.put("snapshotId", fromSnapshot ? latest.get("id") : null);
        return out;
    }

    private static String fallbackTemplate(String format) {
        if ("thermal".equals(format)) {
            return "{{companyName}}\n{{documentTypeName}}\n{{documentNumber}}\n--------------------------------\n"
                    + "TOTAL {{totalAmount}}\n";
        }
        return "<html><body><h1>{{documentTypeName}}</h1><pre>{{documentNumber}}</pre></body></html>";
    }

    /** Rendered HTML → A4 PDF: style/head dropped, block tags become line breaks, text set in Helvetica. */
    static byte[] htmlToPdf(String html) {
        String text = html.replaceAll("(?is)<(style|head|script)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|tr|table|li)>", "\n")
                .replaceAll("(?i)</t[dh]>", "  ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(pdf, bytes);
        pdf.open();
        Font font = FontFactory.getFont(FontFactory.HELVETICA, 10);
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                pdf.add(new Paragraph(trimmed, font));
            }
        }
        if (text.isBlank()) {
            pdf.add(new Paragraph(" ", font));
        }
        pdf.close();
        return bytes.toByteArray();
    }
}
