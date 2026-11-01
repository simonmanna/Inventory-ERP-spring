package com.inventoryerp.customization.application;

import com.inventoryerp.customization.application.TemplateEngine.TemplateException;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.html.simpleparser.HTMLWorker;
import org.openpdf.text.html.simpleparser.StyleSheet;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Print templates (ADR-S029 §3): per-organization HTML templates per document kind with a version history, one
 * default per kind, live preview of unsaved content, and rendering of a record to HTML or PDF.
 */
@Service
public class PrintTemplateService {

    public static final Set<String> PAPER = Set.of("A4", "A5", "Letter");
    public static final int MAX_CONTENT = 200_000;

    /** Template input (update: nulls keep the stored value). */
    public record TemplateInput(String documentKind, String name, String content, String css, String paperSize,
            String orientation, Boolean isDefault, Boolean isActive) {}

    /** A rendered document. */
    public record Rendered(String html, byte[] pdf, String templateId, Integer version) {}

    private final JdbcClient jdbc;
    private final PrintContextProviders contexts;
    private final AuditService audit;

    public PrintTemplateService(JdbcClient jdbc, PrintContextProviders contexts, AuditService audit) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        if (kind != null) {
            PrintContextProviders.assertKind(kind);
        }
        return SqlRows.list(jdbc.sql("SELECT id, \"documentKind\", name, \"paperSize\", orientation, \"isDefault\","
                + " \"isActive\", version, \"updatedAt\" FROM \"PrintTemplate\" WHERE \"organizationId\" = ?"
                + (kind == null ? "" : " AND \"documentKind\" = ?") + " ORDER BY \"documentKind\", \"isDefault\" DESC,"
                + " name").params(kind == null ? List.of(org()) : List.of(org(), kind)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"PrintTemplate\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Print template not found");
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> versions(String id) {
        get(id);
        return SqlRows.list(jdbc.sql("SELECT id, version, \"createdBy\", \"createdAt\" FROM \"PrintTemplateVersion\""
                + " WHERE \"templateId\" = ? AND \"organizationId\" = ? ORDER BY version DESC").params(id, org()));
    }

    @Transactional
    public Map<String, Object> create(TemplateInput in) {
        PrintContextProviders.assertKind(in.documentKind());
        String content = content(in.content());
        String css = in.css() == null ? "" : in.css();
        String id = UUID.randomUUID().toString();
        boolean makeDefault = Boolean.TRUE.equals(in.isDefault()) || jdbc.sql("SELECT count(*) FROM \"PrintTemplate\""
                + " WHERE \"organizationId\" = ? AND \"documentKind\" = ? AND \"isDefault\"").params(org(),
                        in.documentKind()).query(Long.class).single() == 0;
        if (makeDefault) {
            clearDefault(in.documentKind());
        }
        jdbc.sql("INSERT INTO \"PrintTemplate\" (id, \"organizationId\", \"documentKind\", name, content, css,"
                + " \"paperSize\", orientation, \"isDefault\", \"isActive\", \"createdBy\", \"updatedBy\") VALUES (?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?)").params(id, org(), in.documentKind(), name(in.name()), content, css,
                        paper(in.paperSize()), orientation(in.orientation()), makeDefault,
                        in.isActive() == null || in.isActive(), TenantContext.userId(), TenantContext.userId()).update();
        snapshot(id, 1, content, css);
        audit.recordInTx(AuditInput.of("PrintTemplate", id, "create", null, Obj.of("documentKind", in.documentKind(),
                "name", in.name())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, TemplateInput in) {
        Map<String, Object> before = get(id);
        String content = in.content() == null ? (String) before.get("content") : content(in.content());
        String css = in.css() == null ? (String) before.get("css") : in.css();
        boolean changed = !content.equals(before.get("content")) || !css.equals(before.get("css"));
        int version = ((Number) before.get("version")).intValue() + (changed ? 1 : 0);
        if (Boolean.TRUE.equals(in.isDefault()) && !Boolean.TRUE.equals(before.get("isDefault"))) {
            clearDefault((String) before.get("documentKind"));
        }
        jdbc.sql("UPDATE \"PrintTemplate\" SET name = ?, content = ?, css = ?, \"paperSize\" = ?, orientation = ?,"
                + " \"isDefault\" = ?, \"isActive\" = ?, version = ?, \"updatedBy\" = ?, \"updatedAt\" = now() WHERE id = ?"
                + " AND \"organizationId\" = ?").params(in.name() == null ? before.get("name") : name(in.name()), content,
                        css, in.paperSize() == null ? before.get("paperSize") : paper(in.paperSize()),
                        in.orientation() == null ? before.get("orientation") : orientation(in.orientation()),
                        in.isDefault() == null ? before.get("isDefault") : in.isDefault(),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), version,
                        TenantContext.userId(), id, org()).update();
        if (changed) {
            snapshot(id, version, content, css);
        }
        audit.recordInTx(AuditInput.of("PrintTemplate", id, "update", Obj.of("version", before.get("version")),
                Obj.of("version", version)));
        return get(id);
    }

    /** Restore an earlier version as a new version. */
    @Transactional
    public Map<String, Object> restore(String id, int version) {
        get(id);
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT content, css FROM \"PrintTemplateVersion\" WHERE"
                + " \"templateId\" = ? AND \"organizationId\" = ? AND version = ?").params(id, org(), version));
        if (v == null) {
            throw ApiException.notFound("Template version not found");
        }
        return update(id, new TemplateInput(null, null, (String) v.get("content"), (String) v.get("css"), null, null,
                null, null));
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> t = get(id);
        jdbc.sql("DELETE FROM \"PrintTemplate\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("PrintTemplate", id, "delete", Obj.of("name", t.get("name")), null));
        return Obj.of("id", id, "deleted", true);
    }

    private void clearDefault(String kind) {
        jdbc.sql("UPDATE \"PrintTemplate\" SET \"isDefault\" = false WHERE \"organizationId\" = ? AND \"documentKind\" ="
                + " ? AND \"isDefault\"").params(org(), kind).update();
    }

    private void snapshot(String id, int version, String content, String css) {
        jdbc.sql("INSERT INTO \"PrintTemplateVersion\" (id, \"organizationId\", \"templateId\", version, content, css,"
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id,
                        version, content, css, TenantContext.userId()).update();
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fields(String kind) {
        return contexts.fields(org(), kind);
    }

    /** {@code POST /print-templates/preview}: unsaved content against a record or sample data, as HTML. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String kind, String content, String css, String recordId, String paperSize,
            String orientation) {
        Map<String, Object> ctx = recordId == null || recordId.isBlank() ? contexts.sample(org(), kind)
                : contexts.context(org(), kind, recordId);
        String html = page(render(content(content), ctx), css == null ? "" : css, paper(paperSize),
                orientation(orientation));
        return Obj.of("html", html, "sample", recordId == null || recordId.isBlank());
    }

    /** Render record {@code recordId} of {@code kind} with {@code templateId} (or the kind's default template). */
    @Transactional(readOnly = true)
    public Rendered render(String kind, String recordId, String templateId, boolean pdf) {
        PrintContextProviders.assertKind(kind);
        Map<String, Object> t = templateId == null || templateId.isBlank()
                ? SqlRows.one(jdbc.sql("SELECT * FROM \"PrintTemplate\" WHERE \"organizationId\" = ? AND \"documentKind\""
                        + " = ? AND \"isDefault\" AND \"isActive\"").params(org(), kind))
                : get(templateId);
        if (t == null) {
            throw ApiException.notFound("No default print template for " + kind);
        }
        if (!kind.equals(t.get("documentKind"))) {
            throw ApiException.badRequest("The template is for " + t.get("documentKind") + ", not " + kind);
        }
        Map<String, Object> ctx = contexts.context(org(), kind, recordId);
        String html = page(render((String) t.get("content"), ctx), (String) t.get("css"), (String) t.get("paperSize"),
                (String) t.get("orientation"));
        return new Rendered(html, pdf ? toPdf(html, (String) t.get("paperSize"), (String) t.get("orientation")) : null,
                (String) t.get("id"), ((Number) t.get("version")).intValue());
    }

    static String render(String content, Map<String, Object> ctx) {
        try {
            return TemplateEngine.render(content, ctx);
        } catch (TemplateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String page(String body, String css, String paper, String orientation) {
        String size = ("Letter".equals(paper) ? "letter" : paper) + " " + orientation;
        return "<!doctype html><html><head><meta charset=\"utf-8\"><style>@page { size: " + size + "; margin: 14mm; }"
                + " body { font-family: Helvetica, Arial, sans-serif; font-size: 11pt; }\n"
                + css.replace("</style", "<\\/style") + "</style></head><body>" + body + "</body></html>";
    }

    /** HTML → PDF with OpenPDF's HTML worker (tables, headings, emphasis); plain text when it cannot parse. */
    static byte[] toPdf(String html, String paper, String orientation) {
        Rectangle size = switch (paper == null ? "A4" : paper) {
            case "A5" -> PageSize.A5;
            case "Letter" -> PageSize.LETTER;
            default -> PageSize.A4;
        };
        if ("landscape".equals(orientation)) {
            size = size.rotate();
        }
        String body = html.replaceAll("(?is)<head>.*?</head>", "").replaceAll("(?is)<!doctype[^>]*>", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document pdf = new Document(size, 40, 40, 40, 40);
        PdfWriter.getInstance(pdf, bytes);
        pdf.open();
        try {
            List<Element> elements = HTMLWorker.parseToList(new StringReader(body), new StyleSheet());
            for (Element e : elements) {
                pdf.add(e);
            }
            if (elements.isEmpty()) {
                pdf.add(new Paragraph(" "));
            }
        } catch (Exception e) {
            Font font = FontFactory.getFont(FontFactory.HELVETICA, 10);
            String text = body.replaceAll("<[^>]+>", " ").replaceAll("[ \\t]+", " ");
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    pdf.add(new Paragraph(line.strip(), font));
                }
            }
        }
        pdf.close();
        return bytes.toByteArray();
    }

    private static String content(String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("content should not be empty");
        }
        if (content.length() > MAX_CONTENT) {
            throw ApiException.badRequest("content must be at most " + MAX_CONTENT + " characters");
        }
        try {
            TemplateEngine.validate(content);
        } catch (TemplateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return content;
    }

    private static String name(String n) {
        if (n == null || n.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return n.trim();
    }

    private static String paper(String p) {
        if (p == null) {
            return "A4";
        }
        if (!PAPER.contains(p)) {
            throw ApiException.badRequest("paperSize must be one of the following values: A4, A5, Letter");
        }
        return p;
    }

    private static String orientation(String o) {
        if (o == null) {
            return "portrait";
        }
        if (!"portrait".equals(o) && !"landscape".equals(o)) {
            throw ApiException.badRequest("orientation must be one of the following values: portrait, landscape");
        }
        return o;
    }
}
