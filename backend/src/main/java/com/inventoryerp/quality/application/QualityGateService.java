package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage-gate check for multi-stage manufacturing (2.1) and any other source module (ADR-S013 §4): "is the lot of
 * order / stage Y passed?". A gate is {@code required} when a live lot exists for the source and stage, or — for a
 * production order — an active in-process plan matches its output product and stage. It is {@code passed} when the
 * latest live lot has result {@code pass} (or was released under a concession) and at least one release decision.
 */
@Service
public class QualityGateService {

    /** Gate answer; {@code lotId} / {@code lotNumber} / {@code status} / {@code result} null when no lot exists. */
    public record GateStatus(boolean required, boolean passed, String lotId, String lotNumber, String status,
            String result, String reason) {}

    private final QualityDb db;
    private final QualityPlanService plans;

    public QualityGateService(QualityDb db, QualityPlanService plans) {
        this.db = db;
        this.plans = plans;
    }

    @Transactional(readOnly = true)
    public GateStatus gate(String sourceType, String sourceId, String stageKey) {
        if (!Q.has(sourceType) || !Q.has(sourceId)) {
            throw ApiException.badRequest("sourceType and sourceId are required");
        }
        Map<String, Object> lot = SqlRows.one(db.jdbc().sql("SELECT l.id, l.\"lotNumber\", l.status, l.result,"
                + " l.\"releasedQty\", EXISTS (SELECT 1 FROM \"QualityLotDecision\" d WHERE d.\"lotId\" = l.id AND"
                + " d.decision = 'release') AS released, EXISTS (SELECT 1 FROM \"QualityLotDecision\" d WHERE"
                + " d.\"lotId\" = l.id AND d.decision = 'release' AND d.concession) AS concession"
                + " FROM \"QualityInspectionLot\" l WHERE l.\"organizationId\" = ? AND l.\"sourceType\" = ? AND"
                + " l.\"sourceId\" = ? AND COALESCE(l.\"stageKey\", '') = ? AND l.status <> 'cancelled'"
                + " ORDER BY l.\"createdAt\" DESC LIMIT 1").params(QualityDb.org(), sourceType, sourceId,
                stageKey == null ? "" : stageKey));
        if (lot == null) {
            boolean required = "production_order".equals(sourceType) && planRequired(sourceId, stageKey);
            return new GateStatus(required, !required, null, null, null, null,
                    required ? "No inspection lot has been opened for this stage yet." : "No inspection required.");
        }
        boolean released = Boolean.TRUE.equals(lot.get("released"));
        boolean ok = released && ("pass".equals(lot.get("result")) || Boolean.TRUE.equals(lot.get("concession")));
        String reason = ok ? ("pass".equals(lot.get("result")) ? "Passed and released." : "Released under concession.")
                : "fail".equals(lot.get("result")) ? "The lot failed inspection."
                : !released && "pass".equals(lot.get("result")) ? "Passed; waiting for release."
                : "Inspection pending.";
        return new GateStatus(true, ok, Q.str(lot, "id"), Q.str(lot, "lotNumber"), Q.str(lot, "status"),
                Q.str(lot, "result"), reason);
    }

    private boolean planRequired(String orderId, String stageKey) {
        String product = db.jdbc().sql("SELECT \"outputProductId\" FROM \"ProductionOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(orderId, QualityDb.org()).query(String.class).optional().orElse(null);
        return product != null && plans.findPlan("in_process", product, null, stageKey) != null;
    }
}
