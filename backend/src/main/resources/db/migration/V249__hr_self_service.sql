-- V249: HP-8 — employee self-service requests: profile changes (applied by HR on approval) and pay claims (paid
-- through payroll as an input on approval).

CREATE TABLE "HrProfileChangeRequest" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT         NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    changes          JSONB        NOT NULL,
    previous         JSONB,
    status           TEXT         NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED',
                         'CANCELLED')),
    "reviewedBy"     TEXT,
    "reviewedAt"     TIMESTAMP(3),
    "reviewNote"     TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrProfileChangeRequest_status_idx" ON "HrProfileChangeRequest" ("organizationId", status);

CREATE TABLE "HrPayClaim" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimNumber"    TEXT           NOT NULL,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    "claimType"      TEXT           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "claimDate"      DATE           NOT NULL,
    description      TEXT,
    "isTaxable"      BOOLEAN        NOT NULL DEFAULT false,
    status           TEXT           NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED',
                         'CANCELLED', 'PAID')),
    "payrollInputId" TEXT,
    "reviewedBy"     TEXT,
    "reviewedAt"     TIMESTAMP(3),
    "reviewNote"     TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrPayClaim_number_key" ON "HrPayClaim" ("organizationId", "claimNumber");
CREATE INDEX "HrPayClaim_status_idx" ON "HrPayClaim" ("organizationId", status);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrProfileChangeRequest', 'HrPayClaim'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
