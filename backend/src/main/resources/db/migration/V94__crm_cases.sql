-- V94: CRM-1 — customer complaints and service requests (Track C Phase 4). A case belongs to a customer, follows
-- open → in_progress → resolved → closed, and can be tied to a fleet delivery order or delivery discrepancy. Fleet ids
-- are plain text with a code snapshot, because crm reads fleet through its application API only.

CREATE TABLE "CrmCase" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT         NOT NULL,
    "caseType"          TEXT         NOT NULL DEFAULT 'complaint' CHECK ("caseType" IN ('complaint', 'service_request')),
    category            TEXT         NOT NULL DEFAULT 'other'
        CHECK (category IN ('delivery', 'quality', 'billing', 'service', 'other')),
    subject             TEXT         NOT NULL,
    description         TEXT,
    "partnerId"         TEXT         NOT NULL REFERENCES "Partner" (id),
    "contactName"       TEXT,
    "contactPhone"      TEXT,
    priority            TEXT         NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    status              TEXT         NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'in_progress', 'resolved', 'closed', 'cancelled')),
    "assigneeId"        TEXT,
    "dueAt"             TIMESTAMP(3),
    "deliveryOrderId"   TEXT,
    "deliveryOrderCode" TEXT,
    "discrepancyId"     TEXT,
    "discrepancyCode"   TEXT,
    resolution          TEXT,
    "resolvedAt"        TIMESTAMP(3),
    "closedAt"          TIMESTAMP(3),
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmCase_org_code_key" ON "CrmCase" ("organizationId", code);
CREATE INDEX "CrmCase_org_status_idx" ON "CrmCase" ("organizationId", status, "dueAt");
CREATE INDEX "CrmCase_org_partner_idx" ON "CrmCase" ("organizationId", "partnerId");
-- One live case per delivery discrepancy, so opening it twice returns the same case.
CREATE UNIQUE INDEX "CrmCase_discrepancy_key" ON "CrmCase" ("organizationId", "discrepancyId")
    WHERE "discrepancyId" IS NOT NULL AND status <> 'cancelled';

-- The case's timeline: notes, status changes, assignments and fleet decisions. Append-only.
CREATE TABLE "CrmCaseEvent" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "caseId"            TEXT         NOT NULL REFERENCES "CrmCase" (id) ON DELETE CASCADE,
    kind                TEXT         NOT NULL CHECK (kind IN ('note', 'status', 'assignment', 'discrepancy')),
    body                TEXT,
    "fromStatus"        TEXT,
    "toStatus"          TEXT,
    "actorId"           TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmCaseEvent_case_idx" ON "CrmCaseEvent" ("caseId", "createdAt");

ALTER TABLE "CrmCase" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmCase" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmCaseEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmCaseEvent" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['crm:case:read', 'crm:case:write']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['crm:case:read', 'crm:case:write'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
