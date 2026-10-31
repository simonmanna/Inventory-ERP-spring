-- V81: mobile money collections (INT-1). A row per request to a customer's phone; a successful one links to the customer
-- receipt it became (exactly one). The number is stored masked.

CREATE TABLE "MobileMoneyCollection" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    reference           TEXT           NOT NULL,
    provider            TEXT           NOT NULL,
    "providerReference" TEXT,
    "partnerId"         TEXT           NOT NULL REFERENCES "Partner" (id),
    "msisdnMasked"      TEXT           NOT NULL,
    amount              NUMERIC(16, 2) NOT NULL CHECK (amount > 0),
    currency            TEXT,
    -- Mobile money account to receive into; null = the mobile_money account mapping.
    "accountId"         TEXT,
    "invoiceId"         TEXT,
    "documentId"        TEXT,
    description         TEXT,
    status              TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'successful', 'failed')),
    "failureReason"     TEXT,
    "paymentId"         TEXT,
    "createdBy"         TEXT,
    "completedAt"       TIMESTAMP(3),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "MobileMoneyCollection_reference_key" ON "MobileMoneyCollection" ("organizationId", reference);
CREATE UNIQUE INDEX "MobileMoneyCollection_provider_ref_key" ON "MobileMoneyCollection" ("organizationId", provider, "providerReference")
    WHERE "providerReference" IS NOT NULL;
CREATE UNIQUE INDEX "MobileMoneyCollection_payment_key" ON "MobileMoneyCollection" ("paymentId") WHERE "paymentId" IS NOT NULL;
CREATE INDEX "MobileMoneyCollection_status_idx" ON "MobileMoneyCollection" ("organizationId", status, "createdAt");

ALTER TABLE "MobileMoneyCollection" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MobileMoneyCollection" USING (("organizationId" = current_setting('app.org_id', true)));
