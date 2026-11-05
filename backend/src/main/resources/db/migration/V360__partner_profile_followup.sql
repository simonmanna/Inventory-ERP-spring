-- Customer / supplier profile (customer detail page): contact deactivation, sales configuration,
-- accounting preferences and payment follow-up state on the partner.

ALTER TABLE "Contact" ADD COLUMN "isActive" BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE "Partner"
    -- Sales configuration (salespersonId already exists since V200).
    ADD COLUMN "paymentModeId"          TEXT,
    ADD COLUMN "incoterm"               TEXT CHECK ("incoterm" IS NULL OR "incoterm" IN
        ('EXW', 'FCA', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP', 'FAS', 'FOB', 'CFR', 'CIF')),
    ADD COLUMN "incotermLocation"       TEXT,
    -- Accounting preferences.
    ADD COLUMN "autoPostBills"          TEXT NOT NULL DEFAULT 'ask'
        CHECK ("autoPostBills" IN ('ask', 'always', 'never')),
    ADD COLUMN "bankAccounts"           JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- Payment follow-up.
    ADD COLUMN "followupLevelId"        TEXT REFERENCES "DunningLevel" (id) ON DELETE SET NULL,
    ADD COLUMN "followupNextReminder"   DATE,
    ADD COLUMN "followupResponsibleId"  TEXT,
    ADD COLUMN "followupExcluded"       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "followupNote"           TEXT;
