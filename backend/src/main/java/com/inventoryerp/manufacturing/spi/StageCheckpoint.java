package com.inventoryerp.manufacturing.spi;

import java.math.BigDecimal;

/**
 * A QC checkpoint opened by a stage-gated output posting (ADR-S011 §5): {@code quantity} of {@code productId} sits in
 * {@code locationId} (the QC quarantine) until the gate passes. {@code checkpointCode} is the stage's configured
 * checkpoint (e.g. an inspection plan code), {@code postingId} the stage posting that produced the lot.
 */
public record StageCheckpoint(String organizationId, String orderId, String orderCode, String stageId,
        int stageSequence, String stageName, String checkpointCode, String productId, BigDecimal quantity,
        String locationId, String postingId, String batchNumber) {}
