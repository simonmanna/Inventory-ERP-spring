-- Review 2026-11-17 (R3): recurring journal templates and deferral schedules post through PostingService in system
-- context, which skipped the manual-posting (control account) rule and had no maker-checker. A template or schedule
-- now posts only once a user holding journal_entry:post, other than its author (and, for a template, other than the
-- user who last changed what it posts), approves it.
--
-- RecurringJournalTemplate: "lastEditedBy" is the user who last changed what the template posts (lines, accounts,
-- amounts, journal, currency, dimensions, schedule, mode); such a change clears "approvedBy" / "approvedAt". An
-- unapproved mode 'post' template stages each occurrence as a draft for a second user instead of posting it.
--
-- DeferralSchedule: an unapproved schedule posts nothing; the reclassification of a deferred P&L line posts on
-- approval, manual releases are refused and the daily job skips it.
--
-- Default for existing rows: unapproved (columns NULL). Nothing created before this change was reviewed by a second
-- user, so existing 'post' templates generate drafts and existing schedules pause their releases until approved.
ALTER TABLE "RecurringJournalTemplate"
    ADD COLUMN "lastEditedBy" TEXT,
    ADD COLUMN "approvedBy"   TEXT,
    ADD COLUMN "approvedAt"   TIMESTAMP(3);

ALTER TABLE "DeferralSchedule"
    ADD COLUMN "approvedBy" TEXT,
    ADD COLUMN "approvedAt" TIMESTAMP(3);
