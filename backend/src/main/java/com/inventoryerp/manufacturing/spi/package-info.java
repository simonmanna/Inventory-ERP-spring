/**
 * Extension points of the manufacturing stage engine (ADR-S011), for other modules to implement or call:
 * {@link com.inventoryerp.manufacturing.spi.StageQcCheckpointHook} (notified when a QC-gated stage has output
 * awaiting inspection) and {@link com.inventoryerp.manufacturing.spi.StageCheckpointResults} (record the pass / fail
 * that opens or keeps closed the stage gate).
 */
@org.springframework.modulith.NamedInterface("spi")
package com.inventoryerp.manufacturing.spi;
