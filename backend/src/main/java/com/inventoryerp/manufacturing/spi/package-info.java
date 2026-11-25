/**
 * Extension points of the manufacturing stage engine (ADR-S011), for other modules to implement or call:
 * {@link com.inventoryerp.manufacturing.spi.StageQcCheckpointHook} (notified when a QC-gated stage has output
 * awaiting inspection), {@link com.inventoryerp.manufacturing.spi.StageCheckpointResults} (record the pass / fail
 * that opens or keeps closed the stage gate) and {@link com.inventoryerp.manufacturing.spi.MachineDataAdapter}
 * (production data capture from PLCs / counters / belt scales, ADR-S015). The PLM module (ADR-S086) implements
 * {@link com.inventoryerp.manufacturing.spi.ProductionLifecycleGate} (lifecycle gates on production orders and BOM
 * activation) and {@link com.inventoryerp.manufacturing.spi.EngineeringChangeWorkflow} (ECO review and apply).
 */
@org.springframework.modulith.NamedInterface("spi")
package com.inventoryerp.manufacturing.spi;
