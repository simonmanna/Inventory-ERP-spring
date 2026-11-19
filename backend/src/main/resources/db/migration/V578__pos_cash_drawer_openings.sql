-- V578: the POS records each cash-drawer opening (owner decision 2026-11-19; ADR-S077, docs/spec/pos.md
-- "Cash-drawer openings"). Loss prevention projects every row as a `cash_drawer.opened` ledger event: C11 counts the
-- `no_sale` openings per cashier, and V-C1 takes an opening as the explanation of a drawer seen open on camera.
-- Append-only: an opening is a fact about the drawer; nothing edits or deletes one.
CREATE TABLE "CashDrawerOpening" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT,
    -- The shift open on the till at the time. Always set for a `no_sale` opening (the caller's own open shift).
    "cashSessionId"  TEXT         REFERENCES "CashSession" (id),
    -- The till: the CashRegister the drawer belongs to. Sent to loss prevention as payload.tillId.
    "cashRegisterId" TEXT         NOT NULL REFERENCES "CashRegister" (id),
    "userId"         TEXT         NOT NULL,
    reason           TEXT         NOT NULL
        CHECK (reason IN ('sale', 'refund', 'cash_movement', 'no_sale', 'float', 'other')),
    note             TEXT         CHECK (note IS NULL OR length(note) <= 500),
    -- The sale, refund or cash movement the opening belongs to, when there is one.
    "referenceType"  TEXT         CHECK ("referenceType" IS NULL OR "referenceType" IN ('invoice', 'refund',
        'cash_movement')),
    "referenceId"    TEXT,
    "occurredAt"     TIMESTAMP(3) NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CashDrawerOpening_reference_check" CHECK (("referenceType" IS NULL) = ("referenceId" IS NULL)),
    CONSTRAINT "CashDrawerOpening_no_sale_session_check" CHECK (reason <> 'no_sale' OR "cashSessionId" IS NOT NULL)
);
-- The loss-prevention projection reads by creation time; the shift screen by session.
CREATE INDEX "CashDrawerOpening_org_created_idx" ON "CashDrawerOpening" ("organizationId", "createdAt", id);
CREATE INDEX "CashDrawerOpening_session_idx" ON "CashDrawerOpening" ("cashSessionId", "occurredAt")
    WHERE "cashSessionId" IS NOT NULL;
CREATE INDEX "CashDrawerOpening_register_idx" ON "CashDrawerOpening" ("cashRegisterId", "occurredAt");

CREATE OR REPLACE FUNCTION cash_drawer_opening_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'CashDrawerOpening rows are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "CashDrawerOpening_append_only" BEFORE UPDATE ON "CashDrawerOpening"
    FOR EACH ROW EXECUTE FUNCTION cash_drawer_opening_append_only();

-- Tenant isolation (V214 pattern): no tenant set (migrations, schedulers) sees every row; a tenant sees its own.
ALTER TABLE "CashDrawerOpening" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CashDrawerOpening"
    USING (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
