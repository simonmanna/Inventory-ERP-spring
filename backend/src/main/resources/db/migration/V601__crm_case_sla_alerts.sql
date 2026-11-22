-- CRM enhancements T1 (post-parity): case response-time alerts.
-- A case is warned once when 75 % of its response time has passed and flagged once when it is overdue; changing the
-- due time clears both so the new deadline is watched again. The breach is also written to the case timeline.
ALTER TABLE "CrmCase"
    ADD COLUMN "slaWarnedAt"   TIMESTAMP(3),
    ADD COLUMN "slaBreachedAt" TIMESTAMP(3);

CREATE INDEX "CrmCase_sla_watch_idx" ON "CrmCase" ("organizationId", "dueAt")
    WHERE status IN ('open', 'in_progress') AND "slaBreachedAt" IS NULL;

ALTER TABLE "CrmCaseEvent" DROP CONSTRAINT "CrmCaseEvent_kind_check";
ALTER TABLE "CrmCaseEvent"
    ADD CONSTRAINT "CrmCaseEvent_kind_check" CHECK (kind IN ('note', 'status', 'assignment', 'discrepancy', 'sla'));
