-- CRM production-readiness fixes, PR B (P1, 2026-11-22; docs/spec/crm.md "Production-readiness fixes").

-- Optimistic concurrency on leads / opportunities: "version" goes up on every real change of the row, whatever writes
-- it (suite, legacy /crm/deals, merges, jobs). Re-ordering cards in a stage (sequenceInStage) and a bare updatedAt
-- touch (an activity logged on the deal) are not changes of the record, so they do not bump it and never cause a
-- false conflict. A PATCH that sends a stale "version" is refused with 409.
ALTER TABLE "Deal" ADD COLUMN "version" INTEGER NOT NULL DEFAULT 0;

CREATE FUNCTION crm_deal_bump_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'sequenceInStage' - 'updatedAt' - 'version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'sequenceInStage' - 'updatedAt' - 'version') THEN
        NEW."version" := OLD."version" + 1;
    ELSE
        NEW."version" := OLD."version";
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER "Deal_bump_version" BEFORE UPDATE ON "Deal" FOR EACH ROW EXECUTE FUNCTION crm_deal_bump_version();

-- Overdue-activity digest: one per person per business day, whatever reruns or clock skew between nodes do.
CREATE TABLE "CrmDigestLog" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    day              DATE         NOT NULL,
    "sentAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("organizationId", "userId", day)
);
ALTER TABLE "CrmDigestLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmDigestLog"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- Missing indexes on the T3a / T3b tables (organization scans, foreign-key lookups and cascades).
CREATE INDEX "SalesQuoteLink_org_quotation_idx" ON "SalesQuoteLink" ("organizationId", "quotationId");
CREATE INDEX "CrmDealLine_org_idx" ON "CrmDealLine" ("organizationId");
CREATE INDEX "CrmDealLine_product_idx" ON "CrmDealLine" ("productId") WHERE "productId" IS NOT NULL;
CREATE INDEX "CrmDealContact_org_idx" ON "CrmDealContact" ("organizationId");
CREATE INDEX "CrmDealContact_contact_idx" ON "CrmDealContact" ("contactId");
CREATE INDEX "Deal_competitor_idx" ON "Deal" ("competitorId") WHERE "competitorId" IS NOT NULL;
CREATE INDEX "CrmLeadForm_source_idx" ON "CrmLeadForm" ("sourceId") WHERE "sourceId" IS NOT NULL;
CREATE INDEX "CrmLeadForm_team_idx" ON "CrmLeadForm" ("teamId") WHERE "teamId" IS NOT NULL;
-- The form's salesperson must be a user (new and changed rows; existing rows are left as they are).
ALTER TABLE "CrmLeadForm" ADD CONSTRAINT "CrmLeadForm_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User" (id)
    ON DELETE SET NULL NOT VALID;
