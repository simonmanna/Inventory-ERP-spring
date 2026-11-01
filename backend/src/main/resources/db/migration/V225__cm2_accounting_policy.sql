-- V225: Cost Management CM-2 (ADR-S032) — governed accounting policy elections and cost eligibility.
-- An election is a choice the standards permit, stored with an effective date, an IAS 8 justification and
-- maker-checker approval; it applies prospectively. Cost eligibility (AD-10) is the election 'cost_eligibility'
-- scoped to an account, cost component or cost pool.

CREATE TABLE "AccountingPolicy" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    election         TEXT NOT NULL,
    "scopeType"      TEXT NOT NULL DEFAULT 'organization',
    "scopeId"        TEXT NOT NULL DEFAULT '',
    value            TEXT NOT NULL,
    "effectiveFrom"  DATE NOT NULL,
    status           TEXT NOT NULL DEFAULT 'proposed',
    justification    TEXT NOT NULL,
    "proposedBy"     TEXT,
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "rejectedReason" TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AccountingPolicy_status" CHECK (status IN ('proposed', 'approved', 'rejected')),
    CONSTRAINT "AccountingPolicy_scope" CHECK ("scopeType" IN ('organization', 'category', 'process', 'quarry',
        'contract', 'account', 'cost_component', 'cost_pool'))
);
CREATE INDEX "AccountingPolicy_lookup_idx"
    ON "AccountingPolicy" ("organizationId", election, "scopeType", "scopeId", status, "effectiveFrom");

ALTER TABLE public."AccountingPolicy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public."AccountingPolicy"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
