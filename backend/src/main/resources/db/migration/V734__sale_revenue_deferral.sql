-- ADR-S094 C2b (§3.6; AD-2 = A, AD-16 = dispatch): revenue of goods invoiced before they leave is held as a contract
-- liability (the unearned_revenue mapping, fallback DEFERRED-REVENUE) and released when they are dispatched.
--
-- One row per posted invoice line that carries deferred revenue of a sales order line (a "layer"). quantity / amount
-- are what is deferred now; unitAmount is the functional amount per order-line unit at the invoice's rate, so a
-- release is never revalued. Every change is a movement and a journal entry of the difference.

CREATE TABLE "SalesRevenueDeferral" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL,
    "orderId"            TEXT NOT NULL REFERENCES "SalesOrder"(id),
    "orderLineId"        TEXT NOT NULL REFERENCES "SalesOrderLine"(id),
    "documentId"         TEXT NOT NULL REFERENCES "Document"(id),
    "documentLineId"     TEXT NOT NULL REFERENCES "DocumentLine"(id),
    "revenueAccountId"   TEXT NOT NULL REFERENCES "Account"(id),
    "liabilityAccountId" TEXT NOT NULL REFERENCES "Account"(id),
    "unitAmount"         NUMERIC(28, 12) NOT NULL CHECK ("unitAmount" > 0),
    quantity             NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    amount               NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SalesRevenueDeferral_layer_key" UNIQUE ("orderLineId", "documentLineId")
);
CREATE INDEX "SalesRevenueDeferral_order_idx" ON "SalesRevenueDeferral" ("organizationId", "orderId");
CREATE INDEX "SalesRevenueDeferral_open_idx" ON "SalesRevenueDeferral" ("organizationId") WHERE quantity > 0;

CREATE TABLE "SalesRevenueMovement" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL,
    "deferralId"     TEXT NOT NULL REFERENCES "SalesRevenueDeferral"(id),
    kind             TEXT NOT NULL CHECK (kind IN ('defer', 'release')),
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity >= 0),
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount >= 0),
    reason           TEXT NOT NULL,
    "sourceType"     TEXT,
    "sourceId"       TEXT,
    "journalEntryId" TEXT REFERENCES "JournalEntry"(id),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SalesRevenueMovement_nonzero" CHECK (quantity > 0 OR amount > 0)
);
CREATE INDEX "SalesRevenueMovement_deferral_idx" ON "SalesRevenueMovement" ("deferralId");

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['SalesRevenueDeferral', 'SalesRevenueMovement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
