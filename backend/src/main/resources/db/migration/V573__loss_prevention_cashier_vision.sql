-- V573 (written as V524): loss prevention LP2, cashier vision (docs/phases/LP2-cashier-vision.md). One more reconciliation engine,
-- cashier_vision: drawer and customer-presence observations against the POS events of the same till. Its rules are in
-- the catalog (V-C1 to V-C3); its windows and runs use the existing tables.

ALTER TABLE "LpWindow" DROP CONSTRAINT IF EXISTS "LpWindow_engine_check";
ALTER TABLE "LpWindow" ADD CONSTRAINT "LpWindow_engine_check"
    CHECK (engine IN ('cashier', 'bar', 'cashier_vision'));
