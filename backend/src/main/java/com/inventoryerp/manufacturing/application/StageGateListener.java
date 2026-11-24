package com.inventoryerp.manufacturing.application;

/**
 * In-module listener (ADR-S014, Phase 2.4) told, inside the deciding transaction, when a stage QC gate was decided
 * ({@code passed} or {@code failed}, per lot) by any path: the manual route, the quality-lot bridge or the curing job.
 * Implementations must not throw for business reasons.
 */
public interface StageGateListener {

    /** {@code postingIds}: the lots (gated output postings) the decision covered. */
    void gateDecided(String stageId, String qcStatus, java.util.List<String> postingIds);
}
