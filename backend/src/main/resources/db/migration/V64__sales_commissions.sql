-- V64 (SALES-4): salesperson commissions, earned on posted invoices that name a salesperson and paid through payroll
-- inputs. No GL posting of its own: the approved payroll run expenses the commission.

CREATE TABLE "CommissionRule" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                TEXT          NOT NULL,
    -- Who: one salesperson (HrEmployee), or everyone.
    "employeeId"        TEXT,
    -- What: one product, a product category, or everything.
    "productId"         TEXT,
    "productCategoryId" TEXT,
    "ratePercent"       NUMERIC(7, 3) NOT NULL CHECK ("ratePercent" >= 0 AND "ratePercent" <= 100),
    "validFrom"         DATE,
    "validTo"           DATE,
    priority            INTEGER       NOT NULL DEFAULT 0,
    "isActive"          BOOLEAN       NOT NULL DEFAULT true,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);

CREATE INDEX "CommissionRule_org_idx" ON "CommissionRule" ("organizationId", "isActive");

CREATE TABLE "CommissionEntry" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT           NOT NULL,
    "documentId"     TEXT           NOT NULL,
    "documentNumber" TEXT,
    "lineId"         TEXT           NOT NULL,
    "productId"      TEXT,
    "ruleId"         TEXT           NOT NULL REFERENCES "CommissionRule" (id),
    "baseAmount"     NUMERIC(20, 6) NOT NULL,
    "ratePercent"    NUMERIC(7, 3)  NOT NULL,
    amount           NUMERIC(20, 2) NOT NULL,
    "earnedOn"       DATE           NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'accrued'
        CHECK (status IN ('accrued', 'approved', 'exported', 'cancelled')),
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "payrollInputId" TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX "CommissionEntry_line_key" ON "CommissionEntry" ("organizationId", "lineId")
    WHERE status <> 'cancelled';
CREATE INDEX "CommissionEntry_status_idx" ON "CommissionEntry" ("organizationId", status, "earnedOn");
CREATE INDEX "CommissionEntry_employee_idx" ON "CommissionEntry" ("organizationId", "employeeId");

ALTER TABLE "CommissionRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CommissionEntry" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionEntry" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['commission:read', 'commission:manage', 'commission:approve']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['commission:read', 'commission:manage',
        'commission:approve'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
