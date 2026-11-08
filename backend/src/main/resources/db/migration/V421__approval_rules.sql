-- Configurable approval rules and the approval hub (ADR-S061). Additive: every new column is nullable or defaulted,
-- so existing workflows keep their exact behaviour.

-- Rule-level: a condition tree on the document snapshot, a precedence among rules of the same document type,
-- a default priority stamped on requests, and a free-text description.
ALTER TABLE "ApprovalWorkflow"
    ADD COLUMN "description" text,
    ADD COLUMN "conditions" jsonb,
    ADD COLUMN "precedence" integer NOT NULL DEFAULT 0,
    ADD COLUMN "priority" text NOT NULL DEFAULT 'medium';

-- Stage-level: a condition that must hold for the stage to apply, and named assignees for the new
-- approverScope values 'user' and 'role'.
ALTER TABLE "ApprovalStep"
    ADD COLUMN "conditions" jsonb,
    ADD COLUMN "approverUserIds" text[],
    ADD COLUMN "approverRoleIds" text[];

-- Request-level: the priority the hub sorts and badges by.
ALTER TABLE "ApprovalRequest"
    ADD COLUMN "priority" text NOT NULL DEFAULT 'medium';

CREATE INDEX "ApprovalRequest_org_status_priority_idx"
    ON "ApprovalRequest" ("organizationId", status, priority);
