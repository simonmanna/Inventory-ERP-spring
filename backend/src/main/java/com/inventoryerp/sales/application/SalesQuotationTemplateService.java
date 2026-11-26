package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quotation templates: reusable lines (as the editor sends them, so prices follow the rules when used), notes and
 * terms. Saved from an existing quotation; a new quotation starts from one in the editor. Org-scoped, soft-deleted.
 */
@Service
public class SalesQuotationTemplateService {

    private static final String COLUMNS = "id, name, description, notes, terms, \"createdById\", \"createdAt\","
            + " \"updatedAt\", jsonb_array_length(lines) AS \"lineCount\"";

    private final SalesDb db;
    private final SalesQuotationService quotations;
    private final AuditService audit;

    public SalesQuotationTemplateService(SalesDb db, SalesQuotationService quotations, AuditService audit) {
        this.db = db;
        this.quotations = quotations;
        this.audit = audit;
    }

    /** Templates of the organization, by name (without their lines). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows("SELECT " + COLUMNS + " FROM \"SalesQuotationTemplate\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL ORDER BY lower(name)", org());
    }

    /** One template with its lines. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = db.row("SELECT " + COLUMNS + ", lines FROM \"SalesQuotationTemplate\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", id, org());
        if (t == null) {
            throw ApiException.notFound("Quotation template not found");
        }
        return t;
    }

    /**
     * Save quotation {@code quotationId} (one the caller can see) as template {@code name}: its lines, notes and
     * terms. Names are unique per organization (case-insensitive).
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String name = str(in.get("name"));
        if (blank(name) || name.trim().length() > 120) {
            throw ApiException.badRequest("name must be 1 to 120 characters");
        }
        String quotationId = str(in.get("quotationId"));
        if (blank(quotationId)) {
            throw ApiException.badRequest("quotationId should not be empty");
        }
        Map<String, Object> q = quotations.get(quotationId);
        List<Map<String, Object>> lines = quotations.linesAsInput(quotationId);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line first");
        }
        if (db.count("SELECT count(*) FROM \"SalesQuotationTemplate\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL AND lower(name) = lower(?)", List.of(org(), name.trim())) > 0) {
            throw ApiException.conflict("A quotation template with this name already exists");
        }
        String description = str(in.get("description"));
        String id = SalesDb.newId();
        db.update("INSERT INTO \"SalesQuotationTemplate\" (id, \"organizationId\", name, description, lines, notes, terms,"
                + " \"createdById\") VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)", id, org(), name.trim(),
                blank(description) ? null : description, SalesDb.toJson(lines), str(q.get("notes")), str(q.get("terms")),
                user());
        audit.record(AuditInput.of("SalesQuotationTemplate", id, "create", null, Obj.of("name", name.trim(), "from",
                q.get("number"))));
        return get(id);
    }

    /** Remove a template (soft delete); quotations made from it are not affected. */
    @Transactional
    public Map<String, Object> delete(String id) {
        get(id);
        db.update("UPDATE \"SalesQuotationTemplate\" SET \"deletedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\""
                + " = ?", id, org());
        audit.record(AuditInput.of("SalesQuotationTemplate", id, "delete"));
        return Obj.of("ok", true);
    }
}
