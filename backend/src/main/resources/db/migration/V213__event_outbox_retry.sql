-- PR-1: retry limit and dead letter for the event outbox. A failing row now waits "nextAttemptAt" (exponential
-- backoff) instead of being picked again on every poll, and after OUTBOX_MAX_ATTEMPTS failures its status becomes
-- 'dead' so it no longer holds back newer rows. Ops list and retry dead rows through /admin/outbox.
ALTER TABLE "EventOutbox" ADD COLUMN "nextAttemptAt" TIMESTAMP(3);

CREATE INDEX "EventOutbox_pending_due_idx" ON "EventOutbox" ("createdAt") WHERE status = 'pending';
CREATE INDEX "EventOutbox_dead_idx" ON "EventOutbox" ("organizationId", "createdAt") WHERE status = 'dead';
