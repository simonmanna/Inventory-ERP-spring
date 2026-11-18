-- V575: loss prevention LP3 (bar intelligence) and LP4 (restaurant and cafe). Briefs: docs/phases/LP3-bar-intelligence.md,
-- docs/phases/LP4-restaurant-cafe.md. Three more reconciliation engines, and the pour meters LP3 reads.

ALTER TABLE "LpWindow" DROP CONSTRAINT IF EXISTS "LpWindow_engine_check";
ALTER TABLE "LpWindow" ADD CONSTRAINT "LpWindow_engine_check"
    CHECK (engine IN ('cashier', 'bar', 'cashier_vision', 'bar_pour', 'kitchen_flow', 'service_vision'));

-- A pour spout, flow meter or dispenser that reports poured volume. Read by an edge agent's adapter (agentId), or
-- posted by an outside system with lp:ingest (agentId empty). Retired, never deleted: past readings point at it.
CREATE TABLE "LpMeasurementDevice" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT           NOT NULL,
    "agentId"        TEXT           REFERENCES "LpAgent" (id) ON DELETE SET NULL,
    name             TEXT           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('pour_spout', 'flow_meter', 'dispenser')),
    vendor           TEXT,
    -- The vendor's own id of the device; what an adapter reads.
    "externalId"     TEXT           NOT NULL,
    -- The product it pours when fixed to one. A reading may name its own.
    "productId"      TEXT,
    -- The bar station, as written on a bar_counter zone's link.
    station          TEXT,
    "standardPourMl" NUMERIC(12, 3) CHECK ("standardPourMl" IS NULL OR "standardPourMl" > 0),
    enabled          BOOLEAN        NOT NULL DEFAULT true,
    "retiredAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX "LpMeasurementDevice_org_branch_idx" ON "LpMeasurementDevice" ("organizationId", "branchId");
CREATE INDEX "LpMeasurementDevice_agent_idx" ON "LpMeasurementDevice" ("agentId");
CREATE UNIQUE INDEX "LpMeasurementDevice_external_key" ON "LpMeasurementDevice" ("organizationId", "branchId",
    "externalId") WHERE "retiredAt" IS NULL;

ALTER TABLE "LpMeasurementDevice" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LpMeasurementDevice"
    USING (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
