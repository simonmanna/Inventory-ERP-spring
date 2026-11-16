package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project tag of a fleet trip or work order (ADR-S075 §3): the customer's job / own project the trip or repair served,
 * optionally with a WBS element of that project. Job costing charges tagged trips and work orders to the project
 * ({@code POST /projects/:id/costs/fleet}); without a tag fleet behaviour is unchanged. A trip delivering to a customer
 * site linked to a project is tagged through its delivery order.
 */
@Service
public class FleetProjectTagService {

    public record TagInput(String projectId, String wbsId) {}

    private final FleetDb db;
    private final AuditService audit;

    public FleetProjectTagService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> tagTrip(String tripId, TagInput in) {
        return tag("FleetLoad", "Trip not found", tripId, in);
    }

    @Transactional
    public Map<String, Object> tagWorkOrder(String workOrderId, TagInput in) {
        return tag("FleetWorkOrder", "Work order not found", workOrderId, in);
    }

    private Map<String, Object> tag(String table, String notFound, String id, TagInput in) {
        Map<String, Object> row = SqlRows.one(db.jdbc().sql("SELECT id, code, \"projectId\", \"wbsId\" FROM \"" + table
                + "\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, FleetDb.org()));
        if (row == null) {
            throw ApiException.notFound(notFound);
        }
        String project = blank(in.projectId());
        String wbs = blank(in.wbsId());
        if (project == null && wbs != null) {
            throw ApiException.badRequest("A WBS element needs its project");
        }
        if (project != null && !db.exists("Project", project, false)) {
            throw ApiException.notFound("Project not found");
        }
        if (wbs != null && db.jdbc().sql("SELECT count(*) FROM \"ProjectWbsElement\" WHERE id = ? AND \"projectId\" = ?"
                + " AND \"organizationId\" = ?").params(wbs, project, FleetDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("WBS element not found on this project");
        }
        if (row.get("projectId") != null && !row.get("projectId").equals(project) && db.jdbc().sql("SELECT count(*)"
                + " FROM \"ProjectCostLine\" WHERE \"sourceType\" = 'project_fleet' AND status = 'posted' AND"
                + " \"sourceLineId\" = ?").param(id).query(Long.class).single() > 0) {
            throw ApiException.badRequest(row.get("code") + " is already charged to its project; reverse the charge"
                    + " first");
        }
        db.jdbc().sql("UPDATE \"" + table + "\" SET \"projectId\" = ?, \"wbsId\" = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(project, wbs, id).update();
        audit.recordInTx(AuditInput.of(table, id, "update", Obj.of("projectId", row.get("projectId"), "wbsId",
                row.get("wbsId")), Obj.of("projectId", project, "wbsId", wbs)));
        return Obj.of("id", id, "code", row.get("code"), "projectId", project, "wbsId", wbs);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
