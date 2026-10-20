package com.inventoryerp.documents.application;

import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.documents.domain.DocumentLine;
import com.inventoryerp.documents.domain.DocumentPrintLog;
import com.inventoryerp.documents.domain.TemplateDefinition;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DMS document surface (port of the {@code documents.controller.ts} handlers, which hold their logic inline in
 * Nest): permission-prefiltered list, detail with registry policies, guarded optimistic-lock patch, preview,
 * template admin, relation / source / attachment entry points and the merged timeline. Permission checks are
 * runtime per document type ({@code document:<type>:<action>} → category → generic).
 */
@Service
public class DocumentsService {

    /** Fields a consumer may patch through the generic endpoint. */
    public static final List<String> PATCHABLE_FIELDS = List.of("reference", "notes", "dueDate", "deliveryDate",
            "deliveryAddress", "sourceDocument");

    private static final List<String> LIST_COLUMNS = List.of("id", "organizationId", "documentNumber", "documentType",
            "documentTypeId", "partnerId", "issueDate", "dueDate", "status", "paymentStatus", "totalAmount",
            "amountResidual", "branchId", "version", "createdAt", "updatedAt");

    private final JdbcClient jdbc;
    private final DmsStore store;
    private final DocumentRenderService render;

    public DocumentsService(JdbcClient jdbc, DmsStore store, DocumentRenderService render) {
        this.jdbc = jdbc;
        this.store = store;
        this.render = render;
    }

    /** Raw document + its type row. */
    public record Loaded(Map<String, Object> doc, Map<String, Object> def) {}

    /** {@code loadDoc}: 404 {@code Document not found}. */
    public Loaded load(String id, String orgId) {
        Map<String, Object> doc = store.requireDoc(id, orgId);
        return new Loaded(doc, store.rawDef(doc.get("documentTypeId")));
    }

    public void requireRead(List<String> keys, Map<String, Object> def) {
        if (!store.can(keys, def, "read")) {
            throw ApiException.forbidden("Missing document:read for '" + def.get("code") + "'");
        }
    }

    public void requireWrite(List<String> keys, Map<String, Object> def) {
        if (!store.can(keys, def, "update")) {
            throw ApiException.forbidden("Missing document:update for '" + def.get("code") + "'");
        }
    }

    public void requireManage(List<String> keys) {
        if (!store.can(keys, null, "manage")) {
            throw ApiException.forbidden("Missing document:manage");
        }
    }

    /** {@code {items, total, page, pageSize}} over the types the actor can read. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String orgId, List<String> keys, Map<String, String> q) {
        List<Map<String, Object>> defs = jdbc.sql("SELECT d.*, o.\"isActive\" AS \"orgActive\" FROM \"DocumentTypeDef\" d"
                + " LEFT JOIN \"OrganizationDocumentType\" o ON o.\"documentTypeId\" = d.id AND o.\"organizationId\" = ?"
                + " ORDER BY d.code ASC").param(orgId).query().listOfRows();
        List<String> readable = new ArrayList<>();
        Map<String, String> codeById = new HashMap<>();
        Map<String, String> idByCode = new HashMap<>();
        for (Map<String, Object> d : defs) {
            idByCode.put((String) d.get("code"), (String) d.get("id"));
            boolean active = d.get("orgActive") == null || Boolean.TRUE.equals(d.get("orgActive"));
            if (active && store.can(keys, d, "read")) {
                readable.add((String) d.get("id"));
                codeById.put((String) d.get("id"), (String) d.get("code"));
            }
        }
        if (readable.isEmpty()) {
            throw ApiException.forbidden("No document types are readable");
        }
        List<Object> params = new ArrayList<>(List.of(orgId));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        String type = q.get("type");
        if (type != null && !type.isEmpty()) {
            String typeId = idByCode.get(type);
            if (typeId == null) {
                throw ApiException.badRequest("Unknown document type '" + type + "'");
            }
            where.append(" AND \"documentTypeId\" = ?");
            params.add(typeId);
        } else {
            where.append(" AND \"documentTypeId\" = ANY(?::text[])");
            params.add(readable.toArray(String[]::new));
        }
        if (notBlank(q.get("status"))) {
            where.append(" AND status::text = ?");
            params.add(q.get("status"));
        }
        if (notBlank(q.get("branchId"))) {
            where.append(" AND \"branchId\" = ?");
            params.add(q.get("branchId"));
        }
        String text = q.get("q") == null ? "" : q.get("q").trim();
        if (!text.isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(text) + "%";
            where.append(" AND (\"documentNumber\" ILIKE ? OR reference ILIKE ? OR \"sourceDocument\" ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        int page = Math.max(1, orOne(q.get("page"), 1));
        int pageSize = Math.min(100, Math.max(1, orOne(q.get("pageSize"), 20)));
        long total = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.sql("SELECT * FROM \"Document\" WHERE" + where + " ORDER BY \"createdAt\" DESC"
                + " OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize).params(params).query().listOfRows()) {
            Map<String, Object> shaped = SqlRows.as(Document.class, raw);
            Map<String, Object> item = new LinkedHashMap<>();
            for (String c : LIST_COLUMNS) {
                item.put(c, shaped.get(c));
            }
            item.put("documentTypeCode", codeById.get((String) raw.get("documentTypeId")));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("pageSize", pageSize);
        return out;
    }

    /** {@code Number(x ?? d) || d}. */
    private static int orOne(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        double n = com.inventoryerp.kernel.common.JsNumbers.number(raw);
        return Double.isNaN(n) || n == 0 ? fallback : (int) n;
    }

    /** Detail incl. lines, partner, registry policies, last 20 print logs and 5 snapshots. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String orgId, List<String> keys, String id) {
        Map<String, Object> raw = store.rawDoc(id, orgId);
        if (raw == null) {
            throw ApiException.notFound("Document not found");
        }
        Map<String, Object> def = store.rawDef(raw.get("documentTypeId"));
        requireRead(keys, def);
        Map<String, Object> doc = SqlRows.as(Document.class, raw);
        doc.put("lines", SqlRows.list(DocumentLine.class, jdbc.sql("SELECT * FROM \"DocumentLine\" WHERE"
                + " \"documentId\" = ? ORDER BY \"lineNumber\" ASC").param(id)));
        doc.put("partner", SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ?").param(raw.get("partnerId"))));
        for (String k : List.of("printProfile", "editPolicy", "postingPolicy", "cancellationPolicy", "snapshotPolicy")) {
            doc.put(k, SqlRows.value(def.get(k)));
        }
        doc.put("printLogs", SqlRows.list(DocumentPrintLog.class, jdbc.sql("SELECT * FROM \"DocumentPrintLog\" WHERE"
                + " \"documentId\" = ? ORDER BY \"createdAt\" DESC LIMIT 20").param(id)));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Map<String, Object> s : jdbc.sql("SELECT id, reason, state, seal, \"generatedAt\", \"templateVersionId\" FROM"
                + " \"DocumentSnapshot\" WHERE \"documentId\" = ? ORDER BY \"generatedAt\" DESC LIMIT 5").param(id).query()
                .listOfRows()) {
            snapshots.add(SqlRows.normalize(new LinkedHashMap<>(s)));
        }
        doc.put("snapshots", snapshots);
        return doc;
    }

    /** Guarded field update with optimistic lock. */
    @Transactional
    public Map<String, Object> patch(String orgId, String userId, List<String> keys, String id, Object version,
            Map<String, Object> fields) {
        if (version == null) {
            throw ApiException.badRequest("version is required for optimistic locking");
        }
        if (fields == null || fields.isEmpty()) {
            throw ApiException.badRequest("fields must contain at least one patchable field");
        }
        Loaded l = load(id, orgId);
        requireWrite(keys, l.def());
        Object editPolicy = l.def().get("editPolicy") == null ? "EDITABLE" : l.def().get("editPolicy");
        String status = String.valueOf(l.doc().get("status"));
        if ("VERSIONED".equals(editPolicy)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This document type uses versioned amendments (Phase 5) — use the amend action instead");
        }
        if ("IMMUTABLE_AFTER_ISSUE".equals(editPolicy) && !"draft".equals(status)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This document type is immutable after issue (current status '" + status + "')");
        }
        List<String> sets = new ArrayList<>(List.of("\"updatedBy\" = ?"));
        List<Object> params = new ArrayList<>(List.of(userId));
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!PATCHABLE_FIELDS.contains(e.getKey())) {
                throw ApiException.badRequest("Field '" + e.getKey() + "' is not patchable");
            }
            sets.add("\"" + e.getKey() + "\" = ?");
            Object v = e.getValue();
            if (v != null && (e.getKey().equals("dueDate") || e.getKey().equals("deliveryDate"))) {
                v = SqlRows.ts(com.inventoryerp.kernel.crud.EntityPatch.toInstant(v.toString()));
            }
            params.add(v);
        }
        sets.add("version = version + 1");
        sets.add("\"updatedAt\" = " + DmsStore.NOW);
        params.addAll(List.of(id, orgId, (int) com.inventoryerp.kernel.common.JsNumbers.number(version.toString())));
        int n = jdbc.sql("UPDATE \"Document\" SET " + String.join(", ", sets) + " WHERE id = ? AND \"organizationId\" = ?"
                + " AND version = ?").params(params).update();
        if (n == 0) {
            throw ApiException.conflict("Document was modified concurrently (expected version " + jsString(version)
                    + "); reload and retry");
        }
        Map<String, Object> fresh = store.rawDoc(id, orgId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("documentNumber", fresh == null ? null : fresh.get("documentNumber"));
        out.put("version", fresh == null ? null : fresh.get("version"));
        out.put("updated", true);
        return out;
    }

    private static String jsString(Object v) {
        if (v instanceof Double d) {
            return com.inventoryerp.kernel.common.JsNumbers.toJsString(d);
        }
        return String.valueOf(v);
    }

    /** Live render without a print log. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String orgId, List<String> keys, String id, String format) {
        String fmt = format != null ? format : "html";
        render.assertFormat(fmt);
        Loaded l = load(id, orgId);
        requireRead(keys, l.def());
        return render.render(orgId, l.doc(), store.rawLines(id), (String) l.def().get("code"),
                (String) l.def().get("name"), l.def().get("id"), fmt);
    }

    /** Template versions (admin), optionally filtered by type code / format. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates(List<String> keys, String type, String format) {
        requireManage(keys);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" true");
        if (notBlank(type)) {
            where.append(" AND t.\"documentTypeId\" IN (SELECT id FROM \"DocumentTypeDef\" WHERE code = ?)");
            params.add(type);
        }
        if (notBlank(format)) {
            where.append(" AND t.format = ?");
            params.add(format);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.sql("SELECT t.* FROM \"TemplateDefinition\" t WHERE" + where
                + " ORDER BY t.\"templateKey\" ASC, t.version DESC").params(params).query().listOfRows()) {
            Map<String, Object> t = SqlRows.as(TemplateDefinition.class, raw);
            t.put("documentType", SqlRows.one(jdbc.sql("SELECT code, name FROM \"DocumentTypeDef\" WHERE id = ?")
                    .param(raw.get("documentTypeId"))));
            out.add(t);
        }
        return out;
    }

    /** New inactive template version for a type + format. */
    @Transactional
    public Map<String, Object> createTemplate(List<String> keys, String userId, String typeCode, String format,
            String name, String content, String engine) {
        requireManage(keys);
        if (!notBlank(typeCode) || !notBlank(content) || !notBlank(format)) {
            throw ApiException.badRequest("typeCode, format and content are required");
        }
        render.assertFormat(format);
        String eng = engine != null ? engine : "plain";
        render.assertEngine(eng);
        Map<String, Object> def = store.rawDefByCode(typeCode);
        if (def == null) {
            throw ApiException.badRequest("Unknown document type '" + typeCode + "'");
        }
        String templateKey = typeCode + ":" + format;
        int latest = jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM \"TemplateDefinition\" WHERE \"templateKey\" = ?")
                .param(templateKey).query(Integer.class).single();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"TemplateDefinition\" (id, \"templateKey\", \"documentTypeId\", format, engine, version, name,"
                        + " content, \"isActive\", \"createdById\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, ?, "
                        + DmsStore.NOW + ")")
                .params(id, templateKey, def.get("id"), format, eng, latest + 1,
                        name != null ? name : templateKey + " v" + (latest + 1), content, userId)
                .update();
        return SqlRows.one(TemplateDefinition.class, jdbc.sql("SELECT * FROM \"TemplateDefinition\" WHERE id = ?")
                .param(id));
    }

    /** Activate one version; siblings on the same key + format are deactivated. */
    @Transactional
    public Map<String, Object> activateTemplate(List<String> keys, String templateId) {
        requireManage(keys);
        Map<String, Object> tpl = jdbc.sql("SELECT \"templateKey\", format FROM \"TemplateDefinition\" WHERE id = ?")
                .param(templateId).query().listOfRows().stream().findFirst().orElse(null);
        if (tpl == null) {
            throw ApiException.notFound("Template not found");
        }
        jdbc.sql("UPDATE \"TemplateDefinition\" SET \"isActive\" = false, \"updatedAt\" = " + DmsStore.NOW
                + " WHERE \"templateKey\" = ? AND format = ?").params(tpl.get("templateKey"), tpl.get("format")).update();
        jdbc.sql("UPDATE \"TemplateDefinition\" SET \"isActive\" = true, \"updatedAt\" = " + DmsStore.NOW
                + " WHERE id = ?").param(templateId).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", templateId);
        out.put("templateKey", tpl.get("templateKey"));
        out.put("format", tpl.get("format"));
        out.put("isActive", true);
        return out;
    }

    /** Resolve the relation target by id or document number (400 when neither exists). */
    @Transactional(readOnly = true)
    public String relationTarget(String orgId, String toDocumentId, String documentNumber) {
        String target = null;
        if (notBlank(toDocumentId)) {
            target = jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? LIMIT 1")
                    .params(toDocumentId, orgId).query(String.class).optional().orElse(null);
        } else if (notBlank(documentNumber)) {
            target = jdbc.sql("SELECT id FROM \"Document\" WHERE \"documentNumber\" = ? AND \"organizationId\" = ? LIMIT 1")
                    .params(documentNumber, orgId).query(String.class).optional().orElse(null);
        }
        if (target == null) {
            throw ApiException.badRequest("toDocumentId or documentNumber must reference an existing document");
        }
        return target;
    }

    /** Merged actions + prints + snapshots + sources + relations + versions + attachments, newest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> timeline(String orgId, List<String> keys, String id) {
        Map<String, Object> doc = store.rawDoc(id, orgId);
        if (doc == null) {
            throw ApiException.notFound("Document not found");
        }
        Map<String, Object> def = store.rawDef(doc.get("documentTypeId"));
        requireRead(keys, def);
        List<Event> events = new ArrayList<>();
        for (Map<String, Object> a : rows("SELECT * FROM \"DocumentAction\" WHERE \"documentId\" = ? ORDER BY"
                + " \"createdAt\" ASC", id)) {
            events.add(event(a.get("createdAt"), "action", "action", a.get("action"), "status", a.get("status"),
                    "requestedById", a.get("requestedById"), "idempotencyKey", a.get("idempotencyKey"), "result",
                    a.get("result"), "error", a.get("error")));
        }
        for (Map<String, Object> p : rows("SELECT * FROM \"DocumentPrintLog\" WHERE \"documentId\" = ? ORDER BY"
                + " \"createdAt\" ASC", id)) {
            events.add(event(p.get("createdAt"), "print", "action", p.get("action"), "printType", p.get("type"),
                    "copies", p.get("copies"), "printedById", p.get("printedById"), "printer", p.get("printer"),
                    "reason", p.get("reason"), "documentLineId", p.get("documentLineId")));
        }
        for (Map<String, Object> s : rows("SELECT * FROM \"DocumentSnapshot\" WHERE \"documentId\" = ? ORDER BY"
                + " \"generatedAt\" ASC", id)) {
            events.add(event(s.get("generatedAt"), "snapshot", "reason", s.get("reason"), "state", s.get("state"),
                    "seal", s.get("seal"), "templateVersionId", s.get("templateVersionId"), "createdById",
                    s.get("createdById")));
        }
        for (Map<String, Object> s : rows("SELECT * FROM \"DocumentSource\" WHERE \"documentId\" = ? ORDER BY"
                + " \"createdAt\" ASC", id)) {
            events.add(event(s.get("createdAt"), "source", "sourceType", s.get("sourceType"), "sourceId",
                    s.get("sourceId"), "relationship", s.get("relationship"), "createdById", s.get("createdById")));
        }
        for (Map<String, Object> r : rows("SELECT r.*, t.code, d.\"documentNumber\" AS other FROM \"DocumentRelation\" r"
                + " JOIN \"DocumentRelationType\" t ON t.id = r.\"relationTypeId\" JOIN \"Document\" d ON d.id ="
                + " r.\"toDocumentId\" WHERE r.\"fromDocumentId\" = ? ORDER BY r.\"createdAt\" ASC", id)) {
            events.add(event(r.get("createdAt"), "relation", "relationType", r.get("code"), "direction", "out",
                    "fromDocumentNumber", doc.get("documentNumber"), "toDocumentNumber", r.get("other"), "payload",
                    r.get("payload"), "createdById", r.get("createdById")));
        }
        for (Map<String, Object> r : rows("SELECT r.*, t.code, d.\"documentNumber\" AS other FROM \"DocumentRelation\" r"
                + " JOIN \"DocumentRelationType\" t ON t.id = r.\"relationTypeId\" JOIN \"Document\" d ON d.id ="
                + " r.\"fromDocumentId\" WHERE r.\"toDocumentId\" = ? ORDER BY r.\"createdAt\" ASC", id)) {
            events.add(event(r.get("createdAt"), "relation", "relationType", r.get("code"), "direction", "in",
                    "fromDocumentNumber", r.get("other"), "toDocumentNumber", doc.get("documentNumber"), "payload",
                    r.get("payload"), "createdById", r.get("createdById")));
        }
        for (Map<String, Object> v : rows("SELECT * FROM \"DocumentVersion\" WHERE \"documentId\" = ? ORDER BY"
                + " \"createdAt\" ASC", id)) {
            events.add(event(v.get("createdAt"), "version", "versionNo", v.get("versionNo"), "changeNote",
                    v.get("changeNote"), "changedById", v.get("changedById")));
        }
        for (Map<String, Object> a : rows("SELECT * FROM \"DocumentAttachment\" WHERE \"documentId\" = ? ORDER BY"
                + " \"createdAt\" ASC", id)) {
            events.add(event(a.get("createdAt"), "attachment", "role", a.get("role"), "attachmentKind", a.get("kind"),
                    "attachmentVersion", a.get("version"), "fileId", a.get("fileId"), "createdById", a.get("createdById")));
        }
        // Stable sort, newest first (Array#sort is stable in V8).
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::at).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", doc.get("id"));
        out.put("documentNumber", doc.get("documentNumber"));
        out.put("documentTypeCode", def.get("code"));
        out.put("status", String.valueOf(doc.get("status")));
        out.put("version", doc.get("version"));
        out.put("events", sorted.stream().map(Event::body).toList());
        return out;
    }

    private List<Map<String, Object>> rows(String sql, Object id) {
        return jdbc.sql(sql).param(id).query().listOfRows();
    }

    private record Event(Instant at, Map<String, Object> body) {}

    private static Event event(Object at, String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("at", SqlRows.value(at));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], SqlRows.value(kv[i + 1]));
        }
        return new Event((Instant) SqlRows.value(at), m);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
