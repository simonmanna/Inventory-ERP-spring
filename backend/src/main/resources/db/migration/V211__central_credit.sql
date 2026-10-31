-- ADR-S022 §3, §5: one customer credit profile (Partner.creditLimit / creditHold / creditRiskCategory) for every channel.
-- Additive: no table is dropped; org-wide CreditLimit rows stay (mirrors of the profile) and the old values are logged.

CREATE TABLE "CreditMigrationLog" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL,
    "partnerId"         TEXT           NOT NULL,
    "creditLimitId"     TEXT,
    -- Partner.creditLimit before the migration (SALES-1 limit) and the org-wide CreditLimit amount (sales limit).
    "partnerLimit"      NUMERIC(20, 6) NOT NULL,
    "salesLimit"        NUMERIC(20, 6) NOT NULL,
    "resultLimit"       NUMERIC(20, 6) NOT NULL,
    "riskCategory"      TEXT,
    note                TEXT           NOT NULL,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CreditMigrationLog_org_partner_idx" ON "CreditMigrationLog" ("organizationId", "partnerId");
ALTER TABLE "CreditMigrationLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CreditMigrationLog" USING (("organizationId" = current_setting('app.org_id', true)));

-- 1. Org-wide sales limits (no branch, no currency) become the customer profile; the higher value wins when both exist.
WITH org_wide AS (
    SELECT DISTINCT ON (l."organizationId", l."partnerId") l.id, l."organizationId", l."partnerId", l.amount,
           l."riskCategory", p."creditLimit" AS partner_limit
      FROM "CreditLimit" l JOIN "Partner" p ON p.id = l."partnerId" AND p."organizationId" = l."organizationId"
     WHERE l."branchId" IS NULL AND l."currencyCode" IS NULL
     ORDER BY l."organizationId", l."partnerId", l."updatedAt" DESC
)
INSERT INTO "CreditMigrationLog" (id, "organizationId", "partnerId", "creditLimitId", "partnerLimit", "salesLimit",
                                  "resultLimit", "riskCategory", note)
SELECT md5(random()::text || clock_timestamp()::text || o.id), o."organizationId", o."partnerId", o.id,
       COALESCE(o.partner_limit, 0), o.amount, GREATEST(COALESCE(o.partner_limit, 0), o.amount), o."riskCategory",
       CASE WHEN COALESCE(o.partner_limit, 0) = o.amount THEN 'same'
            WHEN COALESCE(o.partner_limit, 0) = 0 THEN 'sales limit copied to profile'
            WHEN o.amount = 0 THEN 'profile kept (sales limit 0 = unlimited row ignored)'
            ELSE 'differed: higher value kept' END
  FROM org_wide o;

UPDATE "Partner" p
   SET "creditLimit" = m."resultLimit",
       "creditRiskCategory" = COALESCE(p."creditRiskCategory", m."riskCategory"),
       "updatedAt" = now()
  FROM "CreditMigrationLog" m
 WHERE m."partnerId" = p.id AND m."organizationId" = p."organizationId"
   AND (COALESCE(p."creditLimit", 0) <> m."resultLimit" OR (p."creditRiskCategory" IS NULL AND m."riskCategory" IS NOT NULL));

UPDATE "CreditLimit" l SET amount = p."creditLimit", "updatedAt" = now()
  FROM "Partner" p
 WHERE p.id = l."partnerId" AND l."branchId" IS NULL AND l."currencyCode" IS NULL AND l.amount <> p."creditLimit";

-- 2. Every channel's check point is a policy switch; the overdue rule applies to its own list of check points.
ALTER TABLE "CreditPolicy" ADD COLUMN "overdueCheckpoints" TEXT[] NOT NULL
    DEFAULT ARRAY['quote_submit', 'order_confirm', 'amendment', 'trip_dispatch']::TEXT[];
ALTER TABLE "CreditPolicy" ALTER COLUMN checkpoints SET DEFAULT ARRAY['quote_submit', 'order_confirm', 'amendment',
    'trip_dispatch', 'pos_credit_sale', 'delivery_order', 'invoice_post']::TEXT[];
UPDATE "CreditPolicy"
   SET checkpoints = checkpoints || ARRAY(SELECT c FROM unnest(ARRAY['pos_credit_sale', 'delivery_order',
       'invoice_post']) c WHERE NOT c = ANY(checkpoints));

-- 3. Customer override grants are read by the credit module for every channel.
CREATE INDEX IF NOT EXISTS "PartnerCreditOverride_org_partner_active_idx"
    ON "PartnerCreditOverride" ("organizationId", "partnerId") WHERE "revokedAt" IS NULL;
