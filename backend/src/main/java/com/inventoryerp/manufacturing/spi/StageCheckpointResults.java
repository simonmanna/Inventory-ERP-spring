package com.inventoryerp.manufacturing.spi;

import java.util.Map;

/**
 * Records the outcome of a stage QC checkpoint (ADR-S011 §5). Each gated output posting is one lot (integrity fix
 * 2026-11-24): {@code passed = true} releases that lot's quarantined output to its destination (next stage location or
 * finished goods); {@code false} keeps it in quarantine. The gate opens for later stages once no lot is held. Runs in
 * the caller's transaction (or its own) and is safe to call again with the same decision.
 */
public interface StageCheckpointResults {

    /** {@code reference}: e.g. the inspection lot number; {@code notes}: free text. Returns the updated stage. */
    Map<String, Object> recordResult(String stageId, CheckpointDecision decision);

    /**
     * A checkpoint decision. {@code postingId}: the lot (gated output posting) it decides; null decides every lot of
     * the stage that is not yet decided (refused when undecided and failed lots are both held, so a pass never
     * releases a failed lot by accident).
     */
    record CheckpointDecision(boolean passed, String reference, String notes, String postingId) {

        /** A decision for the stage's undecided lots. */
        public CheckpointDecision(boolean passed, String reference, String notes) {
            this(passed, reference, notes, null);
        }
    }
}
