package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateRequest;
import com.inventoryerp.manufacturing.application.MfgCommands.RejectRequest;
import com.inventoryerp.manufacturing.application.MfgCommands.RequestLineInput;
import com.inventoryerp.manufacturing.domain.ProductionRequest;
import com.inventoryerp.manufacturing.domain.ProductionRequestLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production requests — demands to make something (port of {@code production-request.service.ts}). A planner
 * consolidates approved requests into plans; requests never move stock or post GL themselves.
 */
@Service
public class ProductionRequestService {

    /** A demand line raised programmatically (wholesale document, min-stock scan). */
    public record RaisedLine(String productId, String variantId, BigDecimal quantity, String uomId, String bomId,
            Instant neededBy, String notes) {

        static RaisedLine of(String productId, BigDecimal quantity, String bomId, Instant neededBy) {
            return new RaisedLine(productId, null, quantity, null, bomId, neededBy, null);
        }
    }

    private final MfgDb db;
    private final SequenceService seq;

    public ProductionRequestService(MfgDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    /** A manual DRAFT request. */
    @Transactional
    public Map<String, Object> create(CreateRequest dto) {
        String requestNumber = seq.next("production_request", "PRQ-", 5);
        List<RaisedLine> lines = new ArrayList<>();
        for (RequestLineInput l : dto.lines()) {
            lines.add(new RaisedLine(l.productId(), l.variantId(), l.quantity(), l.uomId(), l.bomId(),
                    date(l.neededBy()), l.notes()));
        }
        return insert(requestNumber, "manual", null, date(dto.neededBy()), "draft", dto.notes(), lines);
    }

    /** Programmatic entry point for demand sources: a {@code submitted} request tagged with its origin. */
    @Transactional
    public Map<String, Object> raiseRequest(String sourceType, String sourceId, Instant neededBy, String notes,
            List<RaisedLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        String requestNumber = seq.next("production_request", "PRQ-", 5);
        return insert(requestNumber, sourceType, sourceId, neededBy, "submitted", notes, lines);
    }

    private Map<String, Object> insert(String requestNumber, String sourceType, String sourceId, Instant neededBy,
            String status, String notes, List<RaisedLine> lines) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNumber", requestNumber);
        data.put("requestedById", TenantContext.userId());
        data.put("sourceType", sourceType);
        data.put("sourceId", sourceId);
        data.put("neededBy", neededBy);
        data.put("status", status);
        data.put("notes", notes);
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> req = db.insert(ProductionRequest.class, data);
        for (int i = 0; i < lines.size(); i++) {
            RaisedLine l = lines.get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("requestId", req.get("id"));
            line.put("productId", l.productId());
            line.put("variantId", l.variantId());
            line.put("quantity", l.quantity());
            line.put("uomId", l.uomId());
            line.put("bomId", l.bomId());
            line.put("neededBy", l.neededBy());
            line.put("notes", l.notes());
            line.put("lineNumber", i);
            db.insert(ProductionRequestLine.class, line);
        }
        return withLines(req);
    }

    private Map<String, Object> transition(String id, List<String> from, String to, Map<String, Object> extra) {
        Map<String, Object> req = db.byId(ProductionRequest.class, id);
        if (req == null) {
            throw ApiException.notFound("Request not found");
        }
        if (!from.contains(str(req, "status"))) {
            throw ApiException.badRequest("Cannot move a " + req.get("status") + " request to " + to + ".");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", to);
        data.put("updatedBy", TenantContext.userId());
        data.putAll(extra);
        return withLines(db.update(ProductionRequest.class, id, data));
    }

    @Transactional
    public Map<String, Object> submit(String id) {
        return transition(id, List.of("draft"), "submitted", Map.of());
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        return transition(id, List.of("submitted"), "approved", Obj.of("approvedById", TenantContext.userId(),
                "approvedAt", Instant.now()));
    }

    @Transactional
    public Map<String, Object> reject(String id, RejectRequest dto) {
        return transition(id, List.of("draft", "submitted"), "rejected",
                Obj.of("rejectedReason", dto != null ? dto.reason() : null));
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        return transition(id, List.of("draft", "submitted", "approved"), "cancelled", Map.of());
    }

    /** Newest first, each with its lines. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, Integer limit, Integer offset) {
        String extra = status != null && !status.isEmpty() ? " AND status = ?::\"ProductionRequestStatus\"" : "";
        Object[] params = extra.isEmpty() ? new Object[0] : new Object[] {status};
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list(ProductionRequest.class, extra + " ORDER BY \"createdAt\" DESC"
                + MfgDb.page(limit, offset), params)) {
            out.add(withLines(r));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> req = db.byId(ProductionRequest.class, id);
        if (req == null) {
            throw ApiException.notFound("Request not found");
        }
        return withLines(req);
    }

    private Map<String, Object> withLines(Map<String, Object> req) {
        req.put("lines", db.children(ProductionRequestLine.class, "requestId", req.get("id"), null));
        return req;
    }

    private static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }
}
