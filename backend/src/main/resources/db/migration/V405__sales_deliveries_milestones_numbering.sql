-- SG-9: delivery without the Fleet module (manual carrier), damaged and rejected quantities, several POD photos, posting
-- approval, milestone billing, recurring orders, tax validation and per-branch numbering.
ALTER TABLE "FleetLoad" DROP CONSTRAINT "FleetLoad_carrier_check";
ALTER TABLE "FleetLoad" ADD CONSTRAINT "FleetLoad_carrier_check" CHECK (carrier IN ('own', 'transporter', 'manual'));
ALTER TABLE "FleetLoad" DROP CONSTRAINT "FleetLoad_check";
ALTER TABLE "FleetLoad" ADD CONSTRAINT "FleetLoad_check" CHECK (carrier IN ('own', 'manual')
    OR "transporterPartnerId" IS NOT NULL OR status IN ('planned', 'cancelled'));
ALTER TABLE "FleetLoad"
    ADD COLUMN "manualGross" NUMERIC(14, 3) CHECK ("manualGross" IS NULL OR "manualGross" >= 0),
    ADD COLUMN "manualTare" NUMERIC(14, 3) CHECK ("manualTare" IS NULL OR "manualTare" >= 0),
    ADD COLUMN "approvalRequired" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "approvedAt" TIMESTAMP(3),
    ADD COLUMN "approvedById" TEXT;
ALTER TABLE "FleetLoadLine"
    ADD COLUMN "damagedQty" NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("damagedQty" >= 0),
    ADD COLUMN "rejectedQty" NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("rejectedQty" >= 0);

CREATE TABLE "SalesDeliveryPhoto" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "loadId"         TEXT         NOT NULL REFERENCES "FleetLoad" (id) ON DELETE CASCADE,
    "fileId"         TEXT         NOT NULL,
    caption          TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesDeliveryPhoto_load_idx" ON "SalesDeliveryPhoto" ("loadId");
ALTER TABLE "SalesDeliveryPhoto" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesDeliveryPhoto" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "SalesOrderMilestone" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    percent          NUMERIC(7, 4)  CHECK (percent IS NULL OR (percent > 0 AND percent <= 100)),
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "dueDate"        DATE,
    "sortOrder"      INTEGER        NOT NULL DEFAULT 0,
    status           TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'invoiced')),
    "documentId"     TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesOrderMilestone_order_idx" ON "SalesOrderMilestone" ("orderId");
ALTER TABLE "SalesOrderMilestone" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesOrderMilestone" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "SalesRecurringOrder" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "sourceOrderId"  TEXT         NOT NULL REFERENCES "SalesOrder" (id),
    frequency        TEXT         NOT NULL CHECK (frequency IN ('weekly', 'monthly', 'quarterly', 'yearly')),
    "nextRunDate"    DATE         NOT NULL,
    "endDate"        DATE,
    active           BOOLEAN      NOT NULL DEFAULT true,
    "lastOrderId"    TEXT,
    "runCount"       INTEGER      NOT NULL DEFAULT 0,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesRecurringOrder_due_idx" ON "SalesRecurringOrder" ("nextRunDate") WHERE active;
ALTER TABLE "SalesRecurringOrder" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesRecurringOrder" USING (("organizationId" = current_setting('app.org_id', true)));

ALTER TABLE "SalesSettings"
    ADD COLUMN "deliveryPostingApproval" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "requireLineTax" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "requireTaxNumberAbove" NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("requireTaxNumberAbove" >= 0),
    ADD COLUMN "numberingScope" TEXT NOT NULL DEFAULT 'organization' CHECK ("numberingScope" IN ('organization', 'branch'));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:delivery:read', 'sales:delivery:write', 'sales:delivery:approve']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:delivery:read', 'sales:delivery:write',
        'sales:delivery:approve'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name = 'Administrator';
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:delivery:read', 'sales:delivery:write'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name <> 'Administrator' AND permissions && ARRAY['sales:order:confirm'];
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:delivery:read'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name <> 'Administrator' AND permissions && ARRAY['sales:order:read'];
