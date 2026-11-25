package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production orders and BOM versions (ADR-S086 §3, Odoo mrp_plm): whether an order still runs on the current version
 * of its BOM, and moving a not-started order onto the current version with the ADR-S063 order replacement.
 */
@Service
public class PlmOrderService {

    private static final Set<String> NOT_STARTED = Set.of("draft", "confirmed");

    private final PlmDb db;
    private final PlmDispositionService dispositions;

    public PlmOrderService(PlmDb db, PlmDispositionService dispositions) {
        this.db = db;
        this.dispositions = dispositions;
    }

    /**
     * The order's BOM, the current active version of the same output, whether the order's BOM was superseded,
     * whether it can still be moved ({@code canUpdate}, or {@code blocker}) and the open ECOs on its BOM.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> bomStatus(String orderId) {
        Map<String, Object> order = order(orderId);
        Map<String, Object> bom = order.get("bomId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code,"
                + " version, status::text AS status, \"outputProductId\", \"outputVariantKey\" FROM \"Bom\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(order.get("bomId"), PlmDb.org()));
        Map<String, Object> current = bom == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code, version FROM"
                + " \"Bom\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ? AND \"outputVariantKey\" = ? AND"
                + " status::text = 'active' AND \"deletedAt\" IS NULL ORDER BY version DESC LIMIT 1")
                .params(PlmDb.org(), bom.get("outputProductId"), bom.get("outputVariantKey")));
        boolean superseded = current != null && !current.get("id").equals(bom.get("id"));
        String blocker = null;
        if (superseded) {
            if (!NOT_STARTED.contains(str(order, "status"))) {
                blocker = "Production order " + order.get("orderCode") + " has started; it finishes on BOM "
                        + bom.get("code") + " v" + bom.get("version") + ".";
            } else {
                String why = dispositions.replaceBlocker(order);
                if (why != null) {
                    blocker = "Production order " + order.get("orderCode") + " is a " + why + ".";
                }
            }
        }
        List<Map<String, Object>> openChanges = bom == null ? List.of() : SqlRows.list(db.jdbc().sql("SELECT id, number,"
                + " title, status FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND \"bomId\" = ? AND status IN"
                + " ('draft', 'approved') ORDER BY \"createdAt\"").params(PlmDb.org(), bom.get("id")));
        return Obj.of("orderId", order.get("id"), "orderCode", order.get("orderCode"), "status", order.get("status"),
                "bom", bom == null ? null : Obj.of("id", bom.get("id"), "code", bom.get("code"), "version",
                        bom.get("version"), "status", bom.get("status")), "current", current, "superseded", superseded,
                "canUpdate", superseded && blocker == null, "blocker", blocker, "openChanges", openChanges);
    }

    /** Move a not-started order onto the current version of its BOM (a replacement order; the old one is cancelled). */
    @Transactional
    public Map<String, Object> updateBom(String orderId) {
        db.jdbc().sql("SELECT id FROM \"ProductionOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(orderId, PlmDb.org()).query().listOfRows();
        Map<String, Object> status = bomStatus(orderId);
        if (!Boolean.TRUE.equals(status.get("superseded"))) {
            throw ApiException.badRequest("Production order " + status.get("orderCode")
                    + " already uses the current BOM version.");
        }
        if (status.get("blocker") != null) {
            throw ApiException.badRequest((String) status.get("blocker"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) status.get("current");
        Map<String, Object> replaced = dispositions.replaceOrder(order(orderId), str(current, "id"),
                "BOM " + current.get("code") + " v" + current.get("version"));
        return Obj.merge(replaced, Obj.of("bom", current));
    }

    private Map<String, Object> order(String orderId) {
        Map<String, Object> order = dispositions.orderRow(orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return order;
    }
}
