-- V244: HP-3 — statutory packs as effective-dated data (ADR-S048). Rates are global reference data, not per
-- organisation: a new rate is a new row with a later effectiveFrom, never an UPDATE of a row already used by a run.
-- Sources: Uganda Income Tax Act (Third Schedule, resident individuals, monthly), NSSF Act (5 % + 10 %), Local
-- Governments Act (Local Service Tax, collected July–October); UAE Federal Decree-Law 33/2021 (end-of-service
-- gratuity, art. 51) and Federal Law 57/2023 (GPSSA pension for UAE nationals). Verify before each tax year.

CREATE TABLE "HrStatutoryRate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "packCode"       TEXT         NOT NULL,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    "effectiveFrom"  DATE         NOT NULL,
    params           JSONB        NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("packCode", code, "effectiveFrom")
);

INSERT INTO "HrStatutoryRate" (id, "packCode", code, name, "effectiveFrom", params) VALUES
('ug-paye-2013', 'UG', 'PAYE', 'Pay As You Earn (resident)', '2013-07-01',
 '{"bands": [{"upTo": 235000, "rate": 0}, {"upTo": 335000, "rate": 0.10}, {"upTo": 410000, "rate": 0.20},
             {"upTo": null, "rate": 0.30}],
   "surchargeThreshold": 10000000, "surchargeRate": 0.10, "payableKey": "paye_payable"}'),
('ug-nssf-1985', 'UG', 'NSSF', 'National Social Security Fund', '1985-01-01',
 '{"employeeRate": 0.05, "employerRate": 0.10, "base": "GROSS", "payableKey": "social_security_payable",
   "expenseKey": "employer_contribution_expense"}'),
('ug-lst-2008', 'UG', 'LST', 'Local Service Tax', '2008-07-01',
 '{"bands": [{"upTo": 100000, "annual": 0}, {"upTo": 200000, "annual": 5000}, {"upTo": 300000, "annual": 10000},
             {"upTo": 400000, "annual": 20000}, {"upTo": 500000, "annual": 30000}, {"upTo": 600000, "annual": 40000},
             {"upTo": 700000, "annual": 60000}, {"upTo": 800000, "annual": 70000}, {"upTo": 900000, "annual": 80000},
             {"upTo": 1000000, "annual": 90000}, {"upTo": null, "annual": 100000}],
   "months": [7, 8, 9, 10], "payableKey": "local_service_tax_payable", "fallbackPayableKey": "paye_payable"}'),
('ae-gpssa-2023', 'AE', 'GPSSA', 'GPSSA pension (UAE nationals)', '2023-10-31',
 '{"nationalities": ["AE", "ARE", "UAE", "EMIRATI"], "employeeRate": 0.11, "employerRate": 0.125,
   "governmentRate": 0.025, "base": "PENSIONABLE", "minBase": 1000, "maxBase": 100000,
   "payableKey": "pension_payable", "expenseKey": "employer_contribution_expense"}'),
('ae-eosb-2022', 'AE', 'EOSB', 'End-of-service gratuity', '2022-02-02',
 '{"daysFirst5Years": 21, "daysAfter5Years": 30, "minServiceYears": 1, "capYearsOfWage": 2, "dailyDivisor": 30,
   "base": "BASIC", "provisionKey": "gratuity_provision", "expenseKey": "gratuity_expense"}');

-- HP-5 / HP-6 / HP-7 settings that the packs and the engine read.
ALTER TABLE "HrPayrollSettings" ADD COLUMN "absenceDeduction" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "HrPayrollSettings" ADD COLUMN "lateDeduction" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "HrPayrollSettings" ADD COLUMN "requireOvertimeApproval" BOOLEAN NOT NULL DEFAULT false;
-- Contractual gratuity for packs without a statutory one (0 = none): days of basic pay per year of service.
ALTER TABLE "HrPayrollSettings" ADD COLUMN "gratuityDaysPerYear" NUMERIC(6, 2) NOT NULL DEFAULT 0;
ALTER TABLE "HrPayrollSettings" ADD COLUMN "wpsEmployerId" TEXT;
ALTER TABLE "HrPayrollSettings" ADD COLUMN "wpsBankCode" TEXT;
ALTER TABLE "HrPayrollSettings" ADD COLUMN "varianceThresholdPct" NUMERIC(6, 2) NOT NULL DEFAULT 10;
