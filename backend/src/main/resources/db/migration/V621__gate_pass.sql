-- Gate pass (ADR-S085, owner request 2026-11-23): a gate-out document for the driver, vehicle or person who takes
-- goods out through the gate. It can be issued beside every delivery point: sales delivery / trip, sales order and
-- invoice handover, branch cash sale, stock transfer, warehouse pick order, rental checkout, repair return and supplier
-- return. Owner decisions: a pass may be issued at any time, and the print warns when goods are not yet issued from
-- stock or not paid. The guard's gate-out marks the source "departed" where that step exists and posts nothing.

CREATE TABLE "GatePass" (
    id                 TEXT          NOT NULL PRIMARY KEY,
    "organizationId"   TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"         TEXT REFERENCES "Branch" (id) ON DELETE SET NULL,
    number             TEXT          NOT NULL,
    "sourceType"       TEXT          NOT NULL CHECK ("sourceType" IN ('delivery', 'sales_order', 'invoice', 'cash_sale',
                                         'stock_transfer', 'pick_order', 'rental', 'repair', 'supplier_return')),
    "sourceId"         TEXT          NOT NULL,
    "sourceNumber"     TEXT,
    -- The document the goods belong to (an invoice's cash sale / sales order, a delivery's order); passes on related
    -- documents share one cap per product. Equal to the source for a stand-alone document.
    "rootType"         TEXT          NOT NULL,
    "rootId"           TEXT          NOT NULL,
    "partnerId"        TEXT,
    "partnerName"      TEXT,
    status             TEXT          NOT NULL DEFAULT 'issued' CHECK (status IN ('issued', 'used', 'void')),
    "bearerType"       TEXT          NOT NULL CHECK ("bearerType" IN ('driver', 'person', 'courier')),
    "bearerName"       TEXT          NOT NULL,
    "bearerIdNumber"   TEXT,
    "bearerPhone"      TEXT,
    "vehiclePlate"     TEXT,
    "vehicleType"      TEXT,
    "transportCompany" TEXT,
    destination        TEXT,
    purpose            TEXT,
    remarks            TEXT,
    -- Snapshot of the source when the pass was issued; the gate check re-reads the live state.
    "goodsIssued"      BOOLEAN       NOT NULL,
    "paymentState"     TEXT          NOT NULL CHECK ("paymentState" IN ('paid', 'partial', 'unpaid', 'not_applicable')),
    warnings           TEXT[]        NOT NULL DEFAULT ARRAY[]::TEXT[],
    "validUntil"       TIMESTAMP(3)  NOT NULL,
    "verifyCode"       TEXT          NOT NULL,
    "printCount"       INTEGER       NOT NULL DEFAULT 0,
    "issuedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "issuedById"       TEXT,
    "gateOutAt"        TIMESTAMP(3),
    "gateOutById"      TEXT,
    "gateOutPlate"     TEXT,
    "gateOutNote"      TEXT,
    "voidedAt"         TIMESTAMP(3),
    "voidedById"       TEXT,
    "voidReason"       TEXT,
    version            INTEGER       NOT NULL DEFAULT 0,
    "createdAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "GatePass_used_chk" CHECK (status <> 'used' OR "gateOutAt" IS NOT NULL),
    CONSTRAINT "GatePass_void_chk" CHECK (status <> 'void' OR ("voidedAt" IS NOT NULL AND "voidReason" IS NOT NULL)),
    CONSTRAINT "GatePass_bearer_chk" CHECK ("bearerType" <> 'driver' OR "vehiclePlate" IS NOT NULL)
);
CREATE UNIQUE INDEX "GatePass_org_number_key" ON "GatePass" ("organizationId", number);
CREATE INDEX "GatePass_source_idx" ON "GatePass" ("organizationId", "sourceType", "sourceId");
CREATE INDEX "GatePass_root_idx" ON "GatePass" ("organizationId", "rootType", "rootId");
CREATE INDEX "GatePass_status_idx" ON "GatePass" ("organizationId", status, "issuedAt" DESC);

CREATE TABLE "GatePassLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "gatePassId"     TEXT           NOT NULL REFERENCES "GatePass" (id) ON DELETE CASCADE,
    "lineNo"         INTEGER        NOT NULL,
    "sourceLineId"   TEXT,
    "productId"      TEXT,
    "variantId"      TEXT,
    description      TEXT           NOT NULL,
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    unit             TEXT,
    "sourceQuantity" NUMERIC(20, 6)
);
CREATE INDEX "GatePassLine_pass_idx" ON "GatePassLine" ("gatePassId");
CREATE INDEX "GatePassLine_sourceLine_idx" ON "GatePassLine" ("organizationId", "sourceLineId");

-- Every issue, print, gate-out, refusal at the gate and void, for the security audit trail.
CREATE TABLE "GatePassEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "gatePassId"     TEXT         NOT NULL REFERENCES "GatePass" (id) ON DELETE CASCADE,
    type             TEXT         NOT NULL CHECK (type IN ('issued', 'printed', 'gate_out', 'refused', 'voided')),
    at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "byId"           TEXT,
    note             TEXT,
    data             JSONB
);
CREATE INDEX "GatePassEvent_pass_idx" ON "GatePassEvent" ("gatePassId", at);

-- Standard tenant policy (V214 / V605 expression).
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['GatePass', 'GatePassLine', 'GatePassEvent'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['gate_pass:read', 'gate_pass:issue', 'gate_pass:void', 'gate_pass:check']) AS k
ON CONFLICT (key) DO NOTHING;

-- Whoever can run a delivery point can read and issue passes; delivery approvers / managers can also void them.
-- The gate check ('gate_pass:check') is granted to Administrators only; give it to the security role explicitly.
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['gate_pass:read', 'gate_pass:issue'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND permissions && ARRAY['sales:delivery:write', 'sales:delivery:dispatch', 'fleet_trip:dispatch',
        'sales:cash_sale:submit', 'invoice:create', 'inventory_doc:update', 'warehouse:operate', 'rental:checkout',
        'repair:manage', 'debit_note:post'];
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['gate_pass:void'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND permissions && ARRAY['sales:delivery:approve', 'sales:delivery:cancel'];
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['gate_pass:read', 'gate_pass:issue',
        'gate_pass:void', 'gate_pass:check'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name = 'Administrator';
