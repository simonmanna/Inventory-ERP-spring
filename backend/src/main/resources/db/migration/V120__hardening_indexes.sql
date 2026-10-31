-- V120: Phase 6 index review. One index per query path added in Phases 2-5 that filtered a growing table with no index
-- on that column: report date ranges, the Gantt board, cost absorption, the period-close checklist, bank matching and
-- the driver app. New objects only, safe to re-run by hand. None of these tables take POS writes.
--
-- Plain CREATE INDEX, inside the Flyway transaction, blocks writes to each table while it builds. The tables below are
-- small on a first install; on a large copy, build them beforehand with CREATE INDEX CONCURRENTLY under the same names.
-- "Order" and "Invoice" need two more indexes but take POS writes, so they are a manual step: docs/OPERATIONS.md 5a.

CREATE INDEX IF NOT EXISTS "ProductionOrder_org_completedAt_idx" ON "ProductionOrder" ("organizationId", "completedAt");
CREATE INDEX IF NOT EXISTS "WorkOrder_org_plannedStartAt_idx" ON "WorkOrder" ("organizationId", "plannedStartAt");
CREATE INDEX IF NOT EXISTS "ProductionOrderStage_org_workCenter_idx" ON "ProductionOrderStage" ("organizationId", "workCenterId");
CREATE INDEX IF NOT EXISTS "ProductionStagePosting_org_stage_idx" ON "ProductionStagePosting" ("organizationId", "stageId");

CREATE INDEX IF NOT EXISTS "ShopFloorClockEntry_org_workOrder_idx" ON "ShopFloorClockEntry" ("organizationId", "workOrderId");
CREATE INDEX IF NOT EXISTS "ShopFloorDowntime_org_workOrder_idx" ON "ShopFloorDowntime" ("organizationId", "workOrderId");
CREATE INDEX IF NOT EXISTS "ShopFloorMeterReading_shiftReport_idx" ON "ShopFloorMeterReading" ("shiftReportId");

CREATE INDEX IF NOT EXISTS "CrushingRun_org_productionOrder_idx" ON "CrushingRun" ("organizationId", "productionOrderId");
CREATE INDEX IF NOT EXISTS "CrushingRunOutput_org_product_idx" ON "CrushingRunOutput" ("organizationId", "productId");

CREATE INDEX IF NOT EXISTS "GoodsReceiptNote_org_receivedAt_idx" ON "GoodsReceiptNote" ("organizationId", "receivedAt");
CREATE INDEX IF NOT EXISTS "BankStatementLine_org_postedAt_idx" ON "BankStatementLine" ("organizationId", "postedAt");
CREATE INDEX IF NOT EXISTS "BankStatementLine_org_matchedPayment_idx" ON "BankStatementLine" ("organizationId", "matchedPaymentId");

CREATE INDEX IF NOT EXISTS "CommissionEntry_org_earnedOn_idx" ON "CommissionEntry" ("organizationId", "earnedOn");
CREATE INDEX IF NOT EXISTS "FleetFuelLog_org_employee_fuelledAt_idx" ON "FleetFuelLog" ("organizationId", "employeeId", "fuelledAt");
