-- FX-2 D (owner decision 3, 2026-11-20): a customer deposit is an advance (non-monetary contract liability, IFRIC 22)
-- or a refundable deposit (monetary financial liability, revalued). No blanket classification: existing rows stay
-- NULL (unclassified) until reviewed one by one (POST /customer-deposits/{id}/classify). New deposits always get a kind.
ALTER TABLE "CustomerDeposit"
    ADD COLUMN kind                   TEXT,
    ADD COLUMN "classifiedAt"         TIMESTAMP(3),
    ADD COLUMN "classifiedById"       TEXT,
    ADD COLUMN "classificationReason" TEXT;
ALTER TABLE "CustomerDeposit"
    ADD CONSTRAINT "CustomerDeposit_kind_check" CHECK (kind IS NULL OR kind IN ('advance', 'refundable'));

-- The refundable-deposit account (2176, mapping customer_refundable_deposit) is created on first use (ensureByCode).

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
VALUES (gen_random_uuid()::text, 'sales:deposit:classify', 'sales', 'deposit', CURRENT_TIMESTAMP)
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:deposit:classify'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
