package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.manufacturing.spi.StageCheckpoint;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults;
import com.inventoryerp.manufacturing.spi.StageCheckpointResults.CheckpointDecision;
import com.inventoryerp.manufacturing.spi.StageQcCheckpointHook;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityLotService;
import jakarta.annotation.PostConstruct;
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

    public QualityStageGateBridge(QualityLotService lots, StageCheckpointResults results, EventBus events) {
        this.lots = lots;
        this.results = results;
        this.events = events;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("quality.lot.decided", this::onDecided);
    }

    @Override
    public void checkpointOpened(StageCheckpoint cp) {
        lots.openLotIfPlanned(new OpenLot("in_process", cp.productId(), null, cp.batchNumber(), cp.quantity(), null,
                cp.locationId(), SOURCE_TYPE, cp.orderId(), cp.stageId(), cp.orderCode(), cp.checkpointCode(), null,
                null, null, "source", false, "Stage " + cp.stageSequence() + " · " + cp.stageName()));
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
                results.recordResult(stageId, new CheckpointDecision(passed, p.path("lotNumber").asString(null),
                        "Quality lot " + p.path("lotNumber").asString("") + " · " + decision));
            } catch (ApiException e) {
                // e.g. the order was cancelled or the stage has no gate any more: the manual route still applies
                log.info("Stage gate not updated from quality lot {}: {}", p.path("lotNumber").asString(""),
                        e.getMessage());
            }
        });
    }
}
