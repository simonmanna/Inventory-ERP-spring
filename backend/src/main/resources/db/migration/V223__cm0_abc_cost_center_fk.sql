-- V223: CM-0 (ADR-S030 amendment) — ABC activity cost centre is a real reference (rolled up in ABC pools).
UPDATE "AbcActivity" a SET "costCenterId" = NULL
WHERE "costCenterId" IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "CostCenter" c WHERE c.id = a."costCenterId");

ALTER TABLE "AbcActivity"
    ADD CONSTRAINT "AbcActivity_costCenter_fk" FOREIGN KEY ("costCenterId") REFERENCES "CostCenter" (id)
        ON DELETE SET NULL;
