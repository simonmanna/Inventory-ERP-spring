package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.org;

import com.inventoryerp.fleet.application.DispatchGate;
import com.inventoryerp.qualitycontrol.application.QcGate;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sales readiness R4d (DEL-4): the controls of a sales delivery, whichever route dispatches it (the sales issue route
 * or the fleet trip routes): its approval when one is required, then its blocking quality checks (ADR-S087), before
 * any stock or ledger write.
 */
@Component
class SalesDispatchGate implements DispatchGate {

    private final SalesDb db;
    private QcGate qcGate;

    SalesDispatchGate(SalesDb db) {
        this.db = db;
    }

    /** Quality-control gate (ADR-S087); optional for plain constructions. */
    @Autowired(required = false)
    void setQcGate(QcGate qcGate) {
        this.qcGate = qcGate;
    }

    @Override
    public void beforeDispatch(String tripId, String sourceType) {
        if (!"sales_order".equals(sourceType)) {
            return;
        }
        Map<String, Object> t = db.row("SELECT \"approvalRequired\", \"approvedAt\" FROM \"FleetLoad\" WHERE id = ? AND"
                + " \"organizationId\" = ?", tripId, org());
        if (t != null && Boolean.TRUE.equals(t.get("approvalRequired")) && t.get("approvedAt") == null) {
            throw ApiException.badRequest("This delivery is waiting for approval");
        }
        if (qcGate != null) {
            qcGate.assertComplete("sales_delivery", tripId, List.of());
        }
    }
}
