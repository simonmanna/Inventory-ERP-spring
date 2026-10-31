package com.inventoryerp.manufacturing.application;

/**
 * In-module listener (ADR-S014, Phase 2.4) told, inside the deciding transaction, when a stage QC gate was decided
 * ({@code passed} or {@code failed}) by any path: the manual route, the quality-lot bridge or the curing job.
 * Implementations must not throw for business reasons.
 */
public interface StageGateListener {

    void gateDecided(String stageId, String qcStatus);
}
