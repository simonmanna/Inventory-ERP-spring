package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.domain.EngineeringChange;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityLotService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First-article inspection (ADR-S086 §7, Odoo PLM ↔ quality): a BOM or routing change can ask for the first article
 * made on the new version to be inspected. On apply it waits ({@code pending}); when the first production order on the
 * new version completes, a final-stage inspection lot is opened through the quality application (source
 * {@code plm_fai}, the change) and its evaluation or decision closes the inspection ({@code passed} / {@code failed} /
 * {@code cancelled}). Without an active final inspection plan for the product the result is recorded on the change by
 * hand ({@code no_plan}).
 */
@Service
public class PlmFaiService {

    private static final Logger log = LoggerFactory.getLogger(PlmFaiService.class);

    /** Source type of the inspection lots PLM opens. */
    public static final String SOURCE = "plm_fai";

    private final PlmDb db;
    private final PlmSupport plm;
    private final QualityLotService quality;
    private final TransactionTemplate tx;

    public PlmFaiService(PlmDb db, PlmSupport plm, QualityLotService quality, PlatformTransactionManager txm) {
        this.db = db;
        this.plm = plm;
        this.quality = quality;
        this.tx = new TransactionTemplate(txm);
    }

    /** Apply step: the columns that start the inspection (empty when the change asks for none). */
    Map<String, Object> onApplied(Map<String, Object> eco) {
        if (!Boolean.TRUE.equals(eco.get("firstArticle")) || !Set.of("bom", "routing").contains(str(eco, "type"))) {
            return Map.of();
        }
        return Obj.of("faiStatus", "pending", "faiLotId", null, "faiOrderId", null, "faiNote", null);
    }

    /**
     * Event handler (no surrounding transaction): when the first order on the new version of a pending change
     * completes, open its inspection lot. The quality call runs in its own transaction, so a refusal there only
     * leaves the result to be recorded by hand.
     */
    public void onOrderCompleted(String orderId) {
        Map<String, Object> target = tx.execute(s -> candidate(orderId));
        if (target == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> eco = (Map<String, Object>) target.get("eco");
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) target.get("order");
        Map<String, Object> lot = null;
        String error = null;
        try {
            BigDecimal qty = SqlRows.dec(order, "producedQty");
            lot = quality.openLotIfPlanned(new OpenLot("final", str(order, "outputProductId"),
                    str(order, "outputVariantId"), str(order, "batchNumber"), qty.signum() > 0 ? qty : BigDecimal.ONE,
                    str(order, "uomId"), str(order, "outputLocationId"), SOURCE, str(eco, "id"), null,
                    eco.get("number") + " · " + order.get("orderCode"), null, null, null, null, null, null,
                    "First-article inspection of " + eco.get("number") + "."));
        } catch (RuntimeException e) {
            error = e.getMessage();
            log.warn("First-article lot for {} / {} not opened: {}", eco.get("number"), order.get("orderCode"), error);
        }
        Map<String, Object> opened = lot;
        String why = error;
        tx.executeWithoutResult(s -> record(str(eco, "id"), order, opened, why));
    }

    /** Event handler: the evaluation (pass / fail) or decision (cancelled) of a first-article lot closes it. */
    @Transactional
    public void onLotDecided(String lotId, String changeId, String result, String status) {
        Map<String, Object> eco = SqlRows.one(EngineeringChange.class, db.jdbc().sql("SELECT * FROM \"EngineeringChange\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"faiLotId\" = ? FOR UPDATE")
                .params(changeId, PlmDb.org(), lotId));
        if (eco == null || !"open".equals(eco.get("faiStatus"))) {
            return;
        }
        String outcome = "pass".equals(result) ? "passed" : "fail".equals(result) ? "failed"
                : "cancelled".equals(status) ? "cancelled" : null;
        if (outcome == null) {
            return;
        }
        String lotNumber = db.jdbc().sql("SELECT \"lotNumber\" FROM \"QualityInspectionLot\" WHERE id = ?").param(lotId)
                .query(String.class).optional().orElse(lotId);
        db.update(EngineeringChange.class, changeId, Obj.of("faiStatus", outcome));
        plm.activity(changeId, "fai", "First article " + outcome + " (inspection lot " + lotNumber + ").");
    }

    /**
     * Record the first-article result by hand: the first order on the new version completed and no inspection lot was
     * opened for it ({@code no_plan}).
     */
    @Transactional
    public Map<String, Object> record(String changeId, String result, String note) {
        Map<String, Object> eco = SqlRows.one(EngineeringChange.class, db.jdbc().sql("SELECT * FROM \"EngineeringChange\""
                + " WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(changeId, PlmDb.org()));
        if (eco == null) {
            throw ApiException.notFound("Engineering change not found");
        }
        String number = str(eco, "number");
        if (!Boolean.TRUE.equals(eco.get("firstArticle")) || eco.get("faiStatus") == null) {
            throw ApiException.badRequest(number + " has no first-article inspection to record.");
        }
        String status = str(eco, "faiStatus");
        if ("open".equals(status)) {
            String lot = db.jdbc().sql("SELECT \"lotNumber\" FROM \"QualityInspectionLot\" WHERE id = ?")
                    .param(eco.get("faiLotId")).query(String.class).optional().orElse(str(eco, "faiLotId"));
            throw ApiException.badRequest("The first article of " + number + " is inspected on lot " + lot
                    + "; record the result there.");
        }
        if (!Set.of("pending", "no_plan").contains(status)) {
            throw ApiException.badRequest("The first article of " + number + " was already recorded (" + status + ").");
        }
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The first article of " + number + " is not made yet: no production order on"
                    + " the new version has completed.");
        }
        String why = PlmSupport.blank(note);
        db.update(EngineeringChange.class, changeId, Obj.of("faiStatus", result, "faiNote", why));
        plm.activity(changeId, "fai", "First article " + result + " (recorded by hand)" + (why == null ? "." : ": "
                + why));
        return Obj.of("changeId", changeId, "number", number, "faiStatus", result, "faiNote", why);
    }

    /** The pending change whose new version this completed order made, with the order; null when none. */
    private Map<String, Object> candidate(String orderId) {
        Map<String, Object> order = SqlRows.one(db.jdbc().sql("SELECT id, \"orderCode\", status::text AS status,"
                + " \"outputProductId\", \"outputVariantId\", \"bomId\", \"batchNumber\", \"producedQty\", \"uomId\","
                + " \"outputLocationId\", \"createdAt\" FROM \"ProductionOrder\" WHERE id = ? AND \"organizationId\" = ?")
                .params(orderId, PlmDb.org()));
        if (order == null || !Set.of("completed", "qc_hold").contains(str(order, "status"))) {
            return null;
        }
        Map<String, Object> eco = SqlRows.one(EngineeringChange.class, db.jdbc().sql("SELECT * FROM"
                + " \"EngineeringChange\" WHERE \"organizationId\" = ? AND \"firstArticle\" AND \"faiStatus\" = 'pending'"
                + " AND status = 'applied' AND ((type = 'bom' AND \"newBomId\" = ?) OR (type = 'routing' AND"
                + " \"productId\" = ? AND \"appliedAt\" <= ?)) ORDER BY \"appliedAt\" LIMIT 1")
                .params(PlmDb.org(), order.get("bomId"), order.get("outputProductId"),
                        SqlRows.ts((java.time.Instant) order.get("createdAt"))));
        return eco == null ? null : Obj.of("eco", eco, "order", order);
    }

    private void record(String changeId, Map<String, Object> order, Map<String, Object> lot, String error) {
        Map<String, Object> eco = SqlRows.one(EngineeringChange.class, db.jdbc().sql("SELECT * FROM \"EngineeringChange\""
                + " WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(changeId, PlmDb.org()));
        if (eco == null || !"pending".equals(eco.get("faiStatus"))) {
            return; // another order of the same version got there first
        }
        String number = str(eco, "number");
        if (lot != null) {
            db.update(EngineeringChange.class, changeId, Obj.of("faiStatus", "open", "faiLotId", lot.get("id"),
                    "faiOrderId", order.get("id")));
            plm.activity(changeId, "fai", "First article " + order.get("orderCode") + ": inspection lot "
                    + lot.get("lotNumber") + " opened.");
            plm.event(DomainEvents.PLM_FAI_OPENED, Obj.of("changeId", changeId, "number", number, "productId",
                    order.get("outputProductId"), "orderId", order.get("id"), "lotId", lot.get("id")));
            return;
        }
        String note = error == null
                ? "No active final inspection plan for this product: record the result of first article "
                        + order.get("orderCode") + " on the change."
                : "The inspection lot could not be opened (" + error + "): record the result of first article "
                        + order.get("orderCode") + " on the change.";
        db.update(EngineeringChange.class, changeId, Obj.of("faiStatus", "no_plan", "faiOrderId", order.get("id"),
                "faiNote", note));
        plm.activity(changeId, "fai", note);
    }
}
