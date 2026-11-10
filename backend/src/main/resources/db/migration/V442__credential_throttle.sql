-- V442: race-free credential lockout counters (POS hardening, owner-approved 2026-11-10).
--
-- Every PIN check (POS PIN login, change-PIN, own-PIN verify, manager override / approval PINs, cash-session approval
-- PINs) counts attempts here with one atomic upsert BEFORE the hash compare, so parallel guesses cannot overwrite each
-- other's increments, and a rolled-back business transaction cannot erase a failure (the counter is written in its own
-- short transaction). The PIN counters are separate from the back-office password counters on "User"
-- (failedLoginCount / lockedUntil): wrong PINs no longer lock a manager's web login.
--
--   scope        what is being guessed: pos_pin (a user's own PIN) or manager_pin (an approver's PIN)
--   subject      the user id being guessed ('pin-only' for the PIN-only override lookup)
--   failures     attempts counted in the current window (reset on a correct credential)
--   windowStart  start of the counting window
--   lockedUntil  set when failures reaches the policy maximum
CREATE TABLE "CredentialThrottle" (
    "organizationId" TEXT         NOT NULL,
    scope            TEXT         NOT NULL,
    subject          TEXT         NOT NULL,
    failures         INTEGER      NOT NULL DEFAULT 0,
    "windowStart"    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lockedUntil"    TIMESTAMP(3),
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CredentialThrottle_pkey" PRIMARY KEY ("organizationId", scope, subject)
);

ALTER TABLE "CredentialThrottle" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CredentialThrottle"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
