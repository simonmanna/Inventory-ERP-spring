-- Readiness gaps h-01 F7 / F2 (stream gB): warehouse lookups that ran without an index.
--
-- Open pick / move tasks of a bin (bin free and reserved quantity, putaway room, reconcile, bin delete) were found by
-- "fromBinId" / "toBinId" with no index, so every lookup read every task ever written. The status column follows the
-- bin so the open-task lookups stay inside the bin's open tasks.
CREATE INDEX "WarehouseTask_from_bin" ON "WarehouseTask" ("fromBinId", status);
CREATE INDEX "WarehouseTask_to_bin" ON "WarehouseTask" ("toBinId", status);
CREATE INDEX "WarehouseTask_wave" ON "WarehouseTask" ("waveId") WHERE "waveId" IS NOT NULL;

-- Pick orders by source document: a sourced pick order is matched with the stock issue of its document (sales order,
-- delivery order, stock-out, transfer) and with the other pick orders of the same document.
CREATE INDEX "WarehousePickOrder_source" ON "WarehousePickOrder" ("organizationId", "sourceType", "sourceRef")
    WHERE "sourceType" <> 'manual';
