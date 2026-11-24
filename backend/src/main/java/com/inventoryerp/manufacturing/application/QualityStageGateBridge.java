package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.manufacturing.domain.ProductionStagePosting;
import com.inventoryerp.manufacturing.spi.StageCheckpoint;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults.CheckpointDecision;
import com.inventoryerp.manufacturing.spi.StageQcCheckpointHook;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityLotService;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Connects the stage QC gates (ADR-S011 §5) to quality management (ADR-S013). A gated stage output opens an
 * in-process inspection lot ({@code sourceType = production_order}, source line = the stage, stage key = the stage's
 * checkpoint code, {@code stockControl = source}: the output already sits in QC quarantine, quality moves no stock).
 * Without a matching active plan nothing is opened and the gate stays manual. The lot's {@code quality.lot.decided}
 * event decides the gate: a full release passes it, a reject or downgrade fails it.
 */
@Component
public class QualityStageGateBridge implements StageQcCheckpointHook {

    static final String SOURCE_TYPE = "production_order";
    private static final Logger log = LoggerFactory.getLogger(QualityStageGateBridge.class);

    private final QualityLotService lots;
    private final StageCheckpointResults results;
    private final EventBus events;
    private final MfgDb db;

    public QualityStageGateBridge(QualityLotService lots, StageCheckpointResults results, EventBus events, MfgDb db) {
        this.lots = lots;
        this.results = results;
        this.events = events;
        this.db = db;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("quality.lot.decided", this::onDecided);
    }

    @Override
    public void checkpointOpened(StageCheckpoint cp) {
        Map<String, Object> lot = lots.openLotIfPlanned(new OpenLot("in_process", cp.productId(), null, cp.batchNumber(), cp.quantity(), null,
                cp.locationId(), SOURCE_TYPE, cp.orderId(), cp.stageId(), cp.orderCode(), cp.checkpointCode(), null,
                null, null, "source", false, "Stage " + cp.stageSequence() + " · " + cp.stageName()));
        // Integrity fix 2026-11-24: the inspection lot decides this posting's QC lot only.
        if (lot != null && lot.get("id") != null && cp.postingId() != null) {
            db.update(ProductionStagePosting.class, cp.postingId(), Obj.of("qcLotId", lot.get("id")));
        }
    }

    void onDecided(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        String stageId = p.path("sourceLineId").asString("");
        if (orgId.isEmpty() || stageId.isEmpty() || !SOURCE_TYPE.equals(p.path("sourceType").asString(""))
                || !"source".equals(p.path("stockControl").asString(""))) {
            return;
        }
        String decision = p.path("decision").asString("");
        Boolean passed = switch (decision) {
            case "release" -> "closed".equals(p.path("status").asString("")) ? Boolean.TRUE : null;
            case "reject", "downgrade" -> Boolean.FALSE;
            default -> null;
        };
        if (passed == null) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            try {
                String lotId = p.path("lotId").asString("");
                String posting = postingOfLot(stageId, lotId);
                // Integrity fix 2026-11-24 (MR-7): the inspection lot belonged to a posting that was reversed since —
                // its decision decides nothing (it used to fall back to every undecided lot of the stage, releasing
                // later output that was never inspected).
                if (posting == null && carriedByReversed(stageId, lotId)) {
                    log.info("Quality lot {} decided after its stage output was reversed; gate unchanged",
                            p.path("lotNumber").asString(""));
                    return;
                }
                results.recordResult(stageId, new CheckpointDecision(passed, p.path("lotNumber").asString(null),
                        "Quality lot " + p.path("lotNumber").asString("") + " · " + decision, posting));
            } catch (ApiException e) {
                // e.g. the order was cancelled or the stage has no gate any more: the manual route still applies
                log.info("Stage gate not updated from quality lot {}: {}", p.path("lotNumber").asString(""),
                        e.getMessage());
            }
        });
    }

    /** Whether a reversed posting of the stage carried the inspection lot (MR-7). */
    private boolean carriedByReversed(String stageId, String lotId) {
        if (lotId.isEmpty()) {
            return false;
        }
        return db.jdbc().sql("SELECT count(*) FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND"
                + " \"stageId\" = ? AND \"qcLotId\" = ? AND \"reversedAt\" IS NOT NULL").params(MfgDb.org(), stageId,
                lotId).query(Long.class).single() > 0;
    }

    /** The QC lot (gated output posting) an inspection lot was opened for; null for lots opened before V625. */
    private String postingOfLot(String stageId, String lotId) {
        if (lotId.isEmpty()) {
            return null;
        }
        return db.jdbc().sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"organizationId\" = ? AND \"stageId\" = ?"
                + " AND \"qcLotId\" = ? AND \"reversedAt\" IS NULL LIMIT 1").params(MfgDb.org(), stageId, lotId)
                .query(String.class).optional().orElse(null);
    }
}
