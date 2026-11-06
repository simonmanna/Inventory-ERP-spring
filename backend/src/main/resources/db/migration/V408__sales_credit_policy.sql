-- SG-8: what happens to an order over the credit limit, cash orders, a credit request flow, a credit controller per
-- customer, and optional enforcement of the credit period and review date. Defaults keep today's behaviour.
ALTER TABLE "SalesSettings"
    ADD COLUMN "overLimitPolicy" TEXT NOT NULL DEFAULT 'flag' CHECK ("overLimitPolicy" IN ('flag', 'approve', 'block')),
    ADD COLUMN "cashBypassesCredit" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "SalesOrder" ADD COLUMN "paymentMode" TEXT NOT NULL DEFAULT 'credit' CHECK ("paymentMode" IN ('credit', 'cash'));
ALTER TABLE "Partner" ADD COLUMN "creditControllerId" TEXT;
ALTER TABLE "CreditPolicy" ADD COLUMN "enforcePeriodAndReview" BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE "SalesCreditRequest" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"            TEXT           NOT NULL REFERENCES "SalesOrder" (id),
    "partnerId"          TEXT           NOT NULL,
    amount               NUMERIC(20, 6) NOT NULL,
    "exposureAtRequest"  NUMERIC(20, 6),
    "limitAtRequest"     NUMERIC(20, 6),
    "exposureAtDecision" NUMERIC(20, 6),
    reason               TEXT           NOT NULL,
    status               TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    "requestedById"      TEXT,
    "decidedById"        TEXT,
    "decidedAt"          TIMESTAMP(3),
    "decisionComment"    TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SalesCreditRequest_pending_key" ON "SalesCreditRequest" ("orderId") WHERE status = 'pending';
CREATE INDEX "SalesCreditRequest_status_idx" ON "SalesCreditRequest" ("organizationId", status, "createdAt" DESC);
ALTER TABLE "SalesCreditRequest" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesCreditRequest" USING (("organizationId" = current_setting('app.org_id', true)));
