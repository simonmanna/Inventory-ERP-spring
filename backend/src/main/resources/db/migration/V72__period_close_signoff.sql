-- V72 (Phase 4, FIN-3): manual sign-offs of the period-close checklist.
--
-- The automatic checks are computed live; only a person's confirmation is stored. Closing a period does not read this
-- table: the checklist guides the close, the gates in PeriodCloseService stay what they were.

CREATE TABLE "PeriodCloseSignOff" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "periodId"       TEXT         NOT NULL REFERENCES "FiscalPeriod" (id) ON DELETE CASCADE,
    key              TEXT         NOT NULL,
    note             TEXT,
    "signedBy"       TEXT,
    "signedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PeriodCloseSignOff_org_period_key" ON "PeriodCloseSignOff" ("organizationId", "periodId", key);

ALTER TABLE "PeriodCloseSignOff" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PeriodCloseSignOff"
    USING (("organizationId" = current_setting('app.org_id', true)));
