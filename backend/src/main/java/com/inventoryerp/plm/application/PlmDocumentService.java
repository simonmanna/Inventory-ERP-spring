package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.plm.application.PlmCommands.SaveDocument;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.EngineeringChange;
import com.inventoryerp.plm.domain.PlmDocument;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engineering documents (ADR-S063): drawings, specifications, CAD, work instructions… One {@code number} has many
 * versions; at most one is {@code released}, releasing a new version supersedes the previous one. A draft linked to
 * an open ECO is released when the ECO is applied. The file itself lives in {@code /files} (or behind a URL).
 */
@Service
public class PlmDocumentService {

    private final PlmDb db;
    private final PlmSupport plm;
    private final SequenceService seq;
    private final FilesService files;

    public PlmDocumentService(PlmDb db, PlmSupport plm, SequenceService seq, FilesService files) {
        this.db = db;
        this.plm = plm;
        this.seq = seq;
        this.files = files;
    }

    /** Documents, newest version of each number first; {@code status=current} = drafts and released only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String productId, String changeId, String status, String docType, String q) {
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (productId != null && !productId.isBlank()) {
            extra.append(" AND \"productId\" = ?");
            params.add(productId);
        }
        if (changeId != null && !changeId.isBlank()) {
            extra.append(" AND \"changeId\" = ?");
            params.add(changeId);
        }
        if ("current".equals(status)) {
            extra.append(" AND status IN ('draft', 'released')");
        } else if (status != null && !status.isBlank()) {
            extra.append(" AND status = ?");
            params.add(status);
        }
        if (docType != null && !docType.isBlank()) {
            extra.append(" AND \"docType\" = ?");
            params.add(docType);
        }
        if (q != null && !q.isBlank()) {
            extra.append(" AND (number ILIKE ? OR title ILIKE ?)");
            params.add("%" + q.trim() + "%");
            params.add("%" + q.trim() + "%");
        }
        extra.append(" ORDER BY number, version DESC LIMIT 500");
        return decorate(db.list(PlmDocument.class, extra.toString(), params.toArray()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> doc = require(id);
        Map<String, Object> out = decorate(List.of(doc)).getFirst();
        out.put("versions", db.list(PlmDocument.class, " AND number = ? ORDER BY version DESC", doc.get("number")));
        return out;
    }

    @Transactional
    public Map<String, Object> create(SaveDocument dto) {
        String title = PlmSupport.blank(dto.title());
        if (title == null) {
            throw ApiException.badRequest("title should not be empty");
        }
        Map<String, Object> data = refs(dto, new LinkedHashMap<>());
        data.put("number", seq.next("plm_document", "DOC-", 5));
        data.put("version", 1);
        data.put("revision", PlmSupport.blank(dto.revision()) == null ? "A" : dto.revision().trim());
        data.put("title", title);
        data.put("docType", dto.docType() == null ? "other" : dto.docType());
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> created = db.insert(PlmDocument.class, data);
        reopen(changeOf(data), created);
        return created;
    }

    @Transactional
    public Map<String, Object> update(String id, SaveDocument dto) {
        Map<String, Object> doc = requireDraft(id, "edited");
        // Integrity fix 2026-11-24 (ML-2): a draft attached to an approved change is frozen with it.
        Map<String, Object> owner = frozenGuard(doc);
        Map<String, Object> data = refs(dto, new LinkedHashMap<>());
        if (dto.title() != null) {
            String title = PlmSupport.blank(dto.title());
            if (title == null) {
                throw ApiException.badRequest("title should not be empty");
            }
            data.put("title", title);
        }
        if (dto.docType() != null) {
            data.put("docType", dto.docType());
        }
        if (dto.revision() != null && PlmSupport.blank(dto.revision()) != null) {
            data.put("revision", dto.revision().trim());
        }
        if (data.isEmpty()) {
            return doc;
        }
        Map<String, Object> updated = db.update(PlmDocument.class, id, data);
        reopen(owner, doc);
        Map<String, Object> target = changeOf(data);
        if (target != null && (owner == null || !target.get("id").equals(owner.get("id")))) {
            reopen(target, doc);
        }
        return updated;
    }

    /** Release a draft (not one owned by an open ECO); the previously released version is superseded. */
    @Transactional
    public Map<String, Object> release(String id) {
        Map<String, Object> doc = requireDraft(id, "released");
        String changeId = str(doc, "changeId");
        if (changeId != null) {
            Map<String, Object> eco = db.byId(EngineeringChange.class, changeId);
            if (eco != null && PlmSupport.OPEN_CHANGE.contains(str(eco, "status"))) {
                throw ApiException.badRequest("Document " + doc.get("number") + " is released by " + eco.get("number")
                        + " when that change is applied.");
            }
        }
        return doRelease(doc);
    }

    /** New draft version of a released (or superseded / obsolete) document; one draft per number at a time. */
    @Transactional
    public Map<String, Object> revise(String id) {
        Map<String, Object> doc = require(id);
        if ("draft".equals(doc.get("status"))) {
            throw ApiException.badRequest("Document " + doc.get("number") + " v" + doc.get("version")
                    + " is still a draft; edit it instead.");
        }
        if (!db.list(PlmDocument.class, " AND number = ? AND status = 'draft'", doc.get("number")).isEmpty()) {
            throw ApiException.badRequest("Document " + doc.get("number") + " already has a draft version.");
        }
        Integer max = db.jdbc().sql("SELECT max(version) FROM \"PlmDocument\" WHERE \"organizationId\" = ?"
                + " AND number = ?").params(PlmDb.org(), doc.get("number")).query(Integer.class).single();
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("number", "title", "docType", "productId", "bomId", "description")) {
            data.put(f, doc.get(f));
        }
        data.put("version", max + 1);
        data.put("revision", PlmSupport.nextRevision(str(doc, "revision")));
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        return db.insert(PlmDocument.class, data);
    }

    @Transactional
    public Map<String, Object> obsolete(String id) {
        Map<String, Object> doc = require(id);
        if (!"released".equals(doc.get("status"))) {
            throw ApiException.badRequest("Only a released document can be made obsolete (status " + doc.get("status")
                    + ").");
        }
        return db.update(PlmDocument.class, id, Obj.of("status", "obsolete"));
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> doc = requireDraft(id, "deleted");
        Map<String, Object> owner = frozenGuard(doc); // Integrity fix 2026-11-24 (ML-2)
        db.update(PlmDocument.class, id, Obj.of("deletedAt", Instant.now()));
        reopen(owner, doc);
    }

    // ---- used by the change service --------------------------------------------------------------------------------

    /** Attach draft documents to an ECO (their product defaults to the ECO's). */
    void linkToChange(String changeId, String productId, List<String> documentIds) {
        for (String docId : documentIds) {
            Map<String, Object> doc = requireDraft(docId, "attached to a change");
            String owner = str(doc, "changeId");
            if (owner != null && !owner.equals(changeId)) {
                Map<String, Object> eco = db.byId(EngineeringChange.class, owner);
                if (eco != null && PlmSupport.OPEN_CHANGE.contains(str(eco, "status"))) {
                    throw ApiException.badRequest("Document " + doc.get("number") + " already belongs to "
                            + eco.get("number") + ".");
                }
            }
            Map<String, Object> data = Obj.of("changeId", changeId);
            if (doc.get("productId") == null && productId != null) {
                data.put("productId", productId);
            }
            db.update(PlmDocument.class, docId, data);
        }
    }

    List<Map<String, Object>> forChange(String changeId) {
        return decorate(db.list(PlmDocument.class, " AND \"changeId\" = ? ORDER BY number, version", changeId));
    }

    /** Release every draft attached to the ECO; returns their numbers. */
    List<String> releaseForChange(String changeId) {
        List<String> released = new ArrayList<>();
        for (Map<String, Object> doc : db.list(PlmDocument.class, " AND \"changeId\" = ? AND status = 'draft'"
                + " ORDER BY number", changeId)) {
            doRelease(doc);
            released.add(str(doc, "number") + " v" + doc.get("version"));
        }
        return released;
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    private Map<String, Object> doRelease(Map<String, Object> doc) {
        db.updateWhere(PlmDocument.class, Obj.of("status", "superseded"), "\"organizationId\" = ? AND number = ?"
                + " AND status = 'released' AND id <> ? AND \"deletedAt\" IS NULL", PlmDb.org(), doc.get("number"),
                doc.get("id"));
        return db.update(PlmDocument.class, str(doc, "id"), Obj.of("status", "released", "releasedAt", Instant.now(),
                "releasedById", PlmSupport.actor()));
    }

    private Map<String, Object> refs(SaveDocument dto, Map<String, Object> data) {
        if (dto.productId() != null) {
            String p = PlmSupport.blank(dto.productId());
            if (p != null) {
                plm.product(p);
            }
            data.put("productId", p);
        }
        if (dto.bomId() != null) {
            String b = PlmSupport.blank(dto.bomId());
            if (b != null && db.byId(Bom.class, b) == null) {
                throw ApiException.notFound("BOM not found");
            }
            data.put("bomId", b);
        }
        if (dto.changeId() != null) {
            String c = PlmSupport.blank(dto.changeId());
            if (c != null) {
                Map<String, Object> eco = db.byId(EngineeringChange.class, c);
                if (eco == null) {
                    throw ApiException.notFound("Engineering change not found");
                }
                if (!PlmSupport.OPEN_CHANGE.contains(str(eco, "status"))) {
                    throw ApiException.badRequest("Change " + eco.get("number") + " is " + eco.get("status") + ".");
                }
                // Integrity fix 2026-11-24 (ML-2): no document joins an approved change (as linkDocuments refuses).
                Map<String, Object> locked = plm.lockChange(c);
                if (!"draft".equals(locked.get("status"))) {
                    throw ApiException.badRequest("Only a draft change can be edited (status " + locked.get("status")
                            + ").");
                }
            }
            data.put("changeId", c);
        }
        if (dto.fileId() != null) {
            String f = PlmSupport.blank(dto.fileId());
            if (f != null && files.meta(f) == null) {
                throw ApiException.notFound("File not found");
            }
            data.put("fileId", f);
            if (f != null && dto.fileName() == null) {
                data.put("fileName", files.meta(f).filename());
            }
        }
        if (dto.url() != null) {
            data.put("url", PlmSupport.blank(dto.url()));
        }
        if (dto.fileName() != null) {
            data.put("fileName", PlmSupport.blank(dto.fileName()));
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        return data;
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> docs) {
        Set<String> products = new HashSet<>();
        Set<String> changes = new HashSet<>();
        docs.forEach(d -> {
            products.add(str(d, "productId"));
            changes.add(str(d, "changeId"));
        });
        Map<String, Map<String, Object>> p = plm.products(products);
        Map<String, String> numbers = new LinkedHashMap<>();
        changes.remove(null);
        if (!changes.isEmpty()) {
            for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id, number FROM \"EngineeringChange\""
                    + " WHERE \"organizationId\" = ? AND id = ANY(?::text[])").params(PlmDb.org(),
                    changes.toArray(String[]::new)))) {
                numbers.put(str(r, "id"), str(r, "number"));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            Map<String, Object> row = new LinkedHashMap<>(d);
            row.put("product", p.get(str(d, "productId")));
            row.put("changeNumber", numbers.get(str(d, "changeId")));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> doc = db.byId(PlmDocument.class, id);
        if (doc == null) {
            throw ApiException.notFound("Document not found");
        }
        return doc;
    }

    /**
     * Integrity fix 2026-11-24 (ML-2): the change a draft document belongs to, locked: refused when it is approved (its
     * content is frozen), returned when it is a draft (an edit then voids its approvals), else null.
     */
    private Map<String, Object> frozenGuard(Map<String, Object> doc) {
        String changeId = str(doc, "changeId");
        if (changeId == null) {
            return null;
        }
        Map<String, Object> eco = plm.lockChange(changeId);
        if (eco == null) {
            return null;
        }
        if ("approved".equals(eco.get("status"))) {
            throw ApiException.badRequest("Document " + doc.get("number") + " is part of approved change "
                    + eco.get("number") + "; it can no longer be edited.");
        }
        return "draft".equals(eco.get("status")) ? eco : null;
    }

    /** The (draft, locked) change a document is being attached to by {@code data}, or null. */
    private Map<String, Object> changeOf(Map<String, Object> data) {
        Object c = data.get("changeId");
        return c == null ? null : plm.lockChange((String) c);
    }

    private void reopen(Map<String, Object> eco, Map<String, Object> doc) {
        if (eco != null && "draft".equals(eco.get("status"))) {
            plm.reopenAfterEdit(eco, "document " + doc.get("number"));
        }
    }

    private Map<String, Object> requireDraft(String id, String verb) {
        Map<String, Object> doc = require(id);
        if (!"draft".equals(doc.get("status"))) {
            throw ApiException.badRequest("Only a draft document can be " + verb + " (" + doc.get("number") + " v"
                    + doc.get("version") + " is " + doc.get("status") + ").");
        }
        return doc;
    }
}
