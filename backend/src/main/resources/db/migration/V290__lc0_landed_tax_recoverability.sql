-- Landed cost LC-0 (ADR-S053): tax recoverability on landed-cost charges.
--
-- A charge's tax is either recoverable (input tax) or not (it becomes part of the charge: capitalised with it or
-- expensed with it). The default comes from the charge's tax, else its cost component's tax, else the organisation
-- setting costing.landedCost.taxRecoverableDefault. Nothing is assumed: existing charges that carry tax are marked
-- unreviewed (NULL) and must be resolved before they enter a new posting path.

ALTER TABLE "Tax" ADD COLUMN recoverability TEXT;
ALTER TABLE "Tax" ADD CONSTRAINT "Tax_recoverability_chk" CHECK (recoverability IN ('full', 'none'));

ALTER TABLE "CostComponent" ADD COLUMN "taxId" TEXT;

ALTER TABLE "LandedCostCharge"
    ADD COLUMN "taxId"          TEXT,
    ADD COLUMN "taxRecoverable" BOOLEAN;

-- No tax: recoverability is irrelevant (true). Tax present: unreviewed (NULL).
UPDATE "LandedCostCharge" SET "taxRecoverable" = TRUE WHERE "taxAmount" = 0;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['landed_cost:override_tax']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['landed_cost:override_tax'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
