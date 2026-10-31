package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AnalyticDimensionService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analytic dimensions on the lines of invoices, credit notes and vendor bills (roadmap 2.4, ADR-S025). Set while the
 * document is a draft; at posting the revenue / expense GL line of each document line carries them
 * ({@link DocumentBuilderService#groupForPosting}).
 */
@Service
public class DocumentLineDimensionService {

    private static final Set<String> TYPES = Set.of("sales_invoice", "credit_note", "vendor_bill");

    /** One line's dimensions; {@code dimensions} null or empty clears them. */
    public record LineDimensions(String lineId, Map<String, Object> dimensions) {}

    private final JdbcClient jdbc;
    private final AnalyticDimensionService dimensions;
    private final AuditService audit;

    public DocumentLineDimensionService(JdbcClient jdbc, AnalyticDimensionService dimensions, AuditService audit) {
        this.jdbc = jdbc;
        this.dimensions = dimensions;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Object> document(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT id, \"documentType\"::text AS type, status::text AS status,"
                + " \"documentNumber\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
        if (d == null || !TYPES.contains(String.valueOf(d.get("type")))) {
            throw ApiException.notFound("Document not found");
        }
        return d;
    }

    /** The document's lines with their dimensions. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String documentId) {
        Map<String, Object> d = document(documentId);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT id, \"lineNumber\", description, \"accountId\","
                + " \"productId\", \"lineType\", subtotal, dimensions::text AS dimensions FROM \"DocumentLine\" WHERE"
                + " \"documentId\" = ? AND \"organizationId\" = ? ORDER BY \"lineNumber\"").params(documentId, org()));
        for (Map<String, Object> l : lines) {
            Object dims = l.get("dimensions");
            l.put("dimensions", dims == null ? null : EntityJson.parseJson(String.valueOf(dims)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", documentId);
        out.put("documentType", d.get("type"));
        out.put("documentNumber", d.get("documentNumber"));
        out.put("status", d.get("status"));
        out.put("lines", lines);
        return out;
    }

    /** Set the dimensions of some lines of a draft document; lines not listed keep theirs. */
    @Transactional
    public Map<String, Object> set(String documentId, List<LineDimensions> lines) {
        Map<String, Object> d = document(documentId);
        if (!"draft".equals(d.get("status"))) {
            throw ApiException.badRequest("Dimensions can only be changed on a draft document");
        }
        for (LineDimensions l : lines) {
            if (jdbc.sql("SELECT count(*) FROM \"DocumentLine\" WHERE id = ? AND \"documentId\" = ? AND"
                    + " \"organizationId\" = ?").params(l.lineId(), documentId, org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Line " + l.lineId() + " is not a line of this document");
            }
            Map<String, Object> dims = l.dimensions() == null || l.dimensions().isEmpty() ? null
                    : new TreeMap<>(l.dimensions());
            dimensions.assertAssignable(dims);
            jdbc.sql("UPDATE \"DocumentLine\" SET dimensions = ?::jsonb WHERE id = ? AND \"organizationId\" = ?")
                    .params(dims == null ? null : EntityJson.toJson(dims), l.lineId(), org()).update();
        }
        audit.recordInTx(AuditInput.of("Document", documentId, "update", null, Obj.of("lineDimensions",
                lines.size())));
        return get(documentId);
    }
}
