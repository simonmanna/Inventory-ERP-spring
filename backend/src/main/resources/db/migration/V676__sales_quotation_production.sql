-- Quotations production readiness: email template, expiry reminders, action on online acceptance, templates.

ALTER TABLE "SalesSettings"
    -- Email compose defaults; placeholders {number}, {customer}, {validUntil}, {total}, {company}, {link}.
    ADD COLUMN "quoteEmailSubject"       TEXT,
    ADD COLUMN "quoteEmailBody"          TEXT,
    -- Tell the owner N days before an open offer expires (0 = off).
    ADD COLUMN "quoteExpiryReminderDays" INTEGER NOT NULL DEFAULT 3
        CHECK ("quoteExpiryReminderDays" BETWEEN 0 AND 90),
    -- What happens when the customer accepts through the online link.
    ADD COLUMN "quoteOnlineAcceptAction" TEXT    NOT NULL DEFAULT 'none'
        CHECK ("quoteOnlineAcceptAction" IN ('none', 'sales_order', 'proforma'));

-- The expiry reminder went out for the current validity date (cleared when the date changes or on a new revision).
ALTER TABLE "SalesQuotation" ADD COLUMN "expiryRemindedAt" TIMESTAMP(3);
CREATE INDEX "SalesQuotation_org_open_valid_idx" ON "SalesQuotation" ("organizationId", "validUntil")
    WHERE status IN ('approved', 'sent') AND "deletedAt" IS NULL;

-- Reusable starting points for repeat offers: lines (as the editor sends them), notes and terms.
CREATE TABLE "SalesQuotationTemplate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    description      TEXT,
    lines            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    notes            TEXT,
    terms            TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "SalesQuotationTemplate_org_name_key" ON "SalesQuotationTemplate" ("organizationId", lower(name))
    WHERE "deletedAt" IS NULL;

-- The standard tenant policy (V214 / V605) on the new table.
DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    EXECUTE 'ALTER TABLE public."SalesQuotationTemplate" ENABLE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS tenant_isolation ON public."SalesQuotationTemplate"';
    EXECUTE format('CREATE POLICY tenant_isolation ON public."SalesQuotationTemplate" USING (%s) WITH CHECK (%s)',
                   expr, expr);
END $$;
