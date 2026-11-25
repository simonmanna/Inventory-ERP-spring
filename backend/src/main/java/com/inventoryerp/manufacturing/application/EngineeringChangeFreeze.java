package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.spi.EngineeringChangeWorkflow;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Approval freezes an engineering change's content (integrity fixes 2026-11-24, ML-2 and MR-2). Editing the draft BOM
 * or the new routing of an approved ECO is refused; editing them on a draft ECO voids the approvals it already has
 * (through the change workflow, PLM, when it is part of the build). The ECO rows are locked, so the edit and a
 * concurrent approval serialise.
 */
@Component
public class EngineeringChangeFreeze {

    private final MfgDb db;
    private final ObjectProvider<EngineeringChangeWorkflow> workflow;

    public EngineeringChangeFreeze(MfgDb db, ObjectProvider<EngineeringChangeWorkflow> workflow) {
        this.db = db;
        this.workflow = workflow;
    }

    /** ML-2: the draft BOM version of an ECO. */
    public void requireBomEditable(Map<String, Object> bom) {
        require("newBomId", bom.get("id"), "BOM " + bom.get("code"));
    }

    /** MR-2: the new routing of a routing ECO (its stage set-up). */
    public void requireRoutingEditable(Map<String, Object> routing) {
        require("newRoutingId", routing.get("id"), "Routing " + routing.get("name"));
    }

    private void require(String column, Object id, String label) {
        List<Map<String, Object>> ecos = SqlRows.list(db.jdbc().sql("SELECT * FROM \"EngineeringChange\" WHERE"
                + " \"organizationId\" = ? AND \"" + column + "\" = ? AND status IN ('draft', 'approved') ORDER BY id"
                + " FOR UPDATE").params(MfgDb.org(), id));
        for (Map<String, Object> eco : ecos) {
            if ("approved".equals(eco.get("status"))) {
                throw ApiException.badRequest(label + " is part of approved change " + eco.get("number")
                        + "; it can no longer be edited.");
            }
        }
        EngineeringChangeWorkflow w = workflow.getIfAvailable();
        if (w != null) {
            String what = label.startsWith("Routing ") ? "routing " + label.substring("Routing ".length()) : label;
            for (Map<String, Object> eco : ecos) {
                w.draftEdited(eco, what);
            }
        }
    }
}
