package com.inventoryerp.manufacturing.spi;

/**
 * Hook for a quality module (Phase 2.6): every bean implementing it is told, inside the posting transaction, when a
 * QC-gated stage has produced a lot that waits for inspection. A typical implementation opens an in-process
 * inspection lot and later reports its decision through {@link StageCheckpointResults#recordResult}. Without any
 * implementation the gate is decided manually ({@code POST /manufacturing/production-stages/{id}/qc-decision}).
 *
 * <p>Implementations must not throw for business reasons (a failure rolls the stage posting back) and must be
 * idempotent per {@link StageCheckpoint#postingId()}.
 */
public interface StageQcCheckpointHook {

    void checkpointOpened(StageCheckpoint checkpoint);
}
