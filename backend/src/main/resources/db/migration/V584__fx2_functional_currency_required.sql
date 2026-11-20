-- FX-2 A (owner decision 2026-11-20): every company has a functional currency before it posts.
-- Guarded: refuse to migrate (rather than guess a currency) if any organisation has none.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(code, ', ') INTO missing FROM "Organization" WHERE "currencyCode" IS NULL OR btrim("currencyCode") = '';
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V584: organisations without a functional currency: %. Set Organization.currencyCode first.', missing;
    END IF;
END $$;

ALTER TABLE "Organization" ALTER COLUMN "currencyCode" SET NOT NULL;
ALTER TABLE "Organization" ADD CONSTRAINT "Organization_currencyCode_not_blank" CHECK (btrim("currencyCode") <> '');
