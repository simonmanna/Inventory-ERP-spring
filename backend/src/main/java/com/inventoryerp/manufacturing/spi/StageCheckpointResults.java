package com.inventoryerp.manufacturing.spi;

import java.util.Map;

/**
 * Records the outcome of a stage QC checkpoint (ADR-S011 §5). {@code passed = true} releases the stage's quarantined
 * output to its destination (next stage location or finished goods) and opens the gate for later stages;
 * {@code false} keeps the gate closed and the output in quarantine. Runs in the caller's transaction (or its own) and
 * is safe to call again with the same decision.
 */
public interface StageCheckpointResults {

    /** {@code reference}: e.g. the inspection lot number; {@code notes}: free text. Returns the updated stage. */
    Map<String, Object> recordResult(String stageId, CheckpointDecision decision);

    /** A checkpoint decision. */
    record CheckpointDecision(boolean passed, String reference, String notes) {}
}
