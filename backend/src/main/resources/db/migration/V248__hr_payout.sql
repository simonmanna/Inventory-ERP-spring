-- V248: HP-7 — pay out: employee payment details for bank files (IBAN, routing / agent code, WPS person id), the
-- settlement of a run's net pay to a bank or cash account, and statutory remittance payments.

CREATE TABLE "HrEmployeePayInfo" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT         NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    iban             TEXT,
    "routingCode"    TEXT,
    "wpsPersonId"    TEXT,
    "bankBranch"     TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrEmployeePayInfo_emp_key" ON "HrEmployeePayInfo" ("employeeId") WHERE "deletedAt" IS NULL;

CREATE TABLE "HrPayrollPayment" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- NET_PAY (a run's net pay) or REMITTANCE (a statutory payable for a period).
    "paymentType"    TEXT           NOT NULL CHECK ("paymentType" IN ('NET_PAY', 'REMITTANCE')),
    "runId"          TEXT,
    "periodId"       TEXT,
    "statutoryCode"  TEXT,
    "accountId"      TEXT           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "paidOn"         DATE           NOT NULL,
    reference        TEXT,
    "journalEntryId" TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrPayrollPayment_netpay_key" ON "HrPayrollPayment" ("runId")
    WHERE "paymentType" = 'NET_PAY' AND "deletedAt" IS NULL;
CREATE UNIQUE INDEX "HrPayrollPayment_remit_key" ON "HrPayrollPayment" ("periodId", "statutoryCode")
    WHERE "paymentType" = 'REMITTANCE' AND "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrEmployeePayInfo', 'HrPayrollPayment'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
