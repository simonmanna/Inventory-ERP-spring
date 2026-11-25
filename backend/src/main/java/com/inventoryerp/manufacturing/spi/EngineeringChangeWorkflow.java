package com.inventoryerp.manufacturing.spi;

import java.util.Map;

/**
 * The review and apply workflow of engineering change orders (ADR-S086), implemented by the PLM module (stages,
 * approvals, effectivity, dispositions). Manufacturing owns the ECO row and the BOM revision ({@code POST
 * /manufacturing/boms/:id/revise}); without an implementation the ADR-S006 routes keep their original behaviour:
 * approve marks the ECO approved, reject marks it rejected, apply activates the new BOM version.
 */
public interface EngineeringChangeWorkflow {

    /**
     * Whether the workflow serves the current organization (PLM can be switched off per organization); when not,
     * manufacturing uses the ADR-S006 behaviour.
     */
    default boolean active() {
        return true;
    }

    /**
     * A BOM revision just opened ECO {@code change} (its row as inserted) from {@code sourceBom}, whose new draft is
     * version {@code newVersion}. Runs in the revision's transaction.
     */
    void opened(Map<String, Object> change, Map<String, Object> sourceBom, int newVersion);

    /**
     * Integrity fix 2026-11-24 (ML-2): the draft BOM of draft ECO {@code change} (its row, locked by the caller) was
     * edited; the workflow voids the approvals the change already has and sends it back for re-approval. Called in
     * the edit's transaction whether or not the workflow is {@link #active()} for the organization, so no approval
     * outlives an edit made while PLM was switched off.
     */
    default void draftEdited(Map<String, Object> change, String what) {}

    /** {@code POST /manufacturing/engineering-changes/:id/approve}. */
    Map<String, Object> approve(String changeId);

    /** {@code POST /manufacturing/engineering-changes/:id/reject}. */
    Map<String, Object> reject(String changeId);

    /** {@code POST /manufacturing/engineering-changes/:id/apply}. */
    Map<String, Object> apply(String changeId);
}
