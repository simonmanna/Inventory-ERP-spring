-- PC-0 (ADR-S054): shared currency handling and realised FX.
--
-- 1. Every foreign-currency journal entry records the rate it was posted at: currency pair, rate, the date it applies
--    to and its source (document = stated on the document, table = configured rate table). Base-currency entries
--    need none.
-- 2. Payments may be made in a foreign currency. Each allocation keeps the document's carrying rate, the settlement
--    rate and the realised FX difference posted to the fx_gain / fx_loss accounts.

CREATE TABLE "JournalEntryFxRate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "journalEntryId" TEXT           NOT NULL REFERENCES "JournalEntry" (id) ON DELETE CASCADE,
    "fromCode"       TEXT           NOT NULL,
    "toCode"         TEXT           NOT NULL,
    rate             NUMERIC(20, 10) NOT NULL CHECK (rate > 0),
    "rateDate"       TIMESTAMP(3)   NOT NULL,
    source           TEXT           NOT NULL CHECK (source IN ('document', 'table')),
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "JournalEntryFxRate_entry_key" ON "JournalEntryFxRate" ("journalEntryId");
CREATE INDEX "JournalEntryFxRate_org_idx" ON "JournalEntryFxRate" ("organizationId");

-- "Payment" already carries "currencyId" / "exchangeRate" (baseline); add where the rate came from and the FX.
ALTER TABLE "Payment"
    ADD COLUMN "rateSource" TEXT,
    ADD COLUMN "fxAmount"   NUMERIC(20, 6) NOT NULL DEFAULT 0;

ALTER TABLE "PaymentAllocation"
    ADD COLUMN "carryingRate"   NUMERIC(20, 10),
    ADD COLUMN "settlementRate" NUMERIC(20, 10),
    ADD COLUMN "fxAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['JournalEntryFxRate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
