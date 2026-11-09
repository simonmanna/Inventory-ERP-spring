-- V430: Project management (ADR-S062; Spring-native, no Nest reference). Turns the job-costing project (V231) into a
-- full project workspace: managers and members, privacy, favourites, per-project task stages, tasks with several
-- assignees, tags, subtasks, dependencies, milestones, checklists, chatter and followers, recurrence, timesheets
-- (mirrored into HR timesheets, which feed the project cost capture of ADR-S038), timers and status updates.

ALTER TABLE "Project"
    ADD COLUMN description         TEXT,
    ADD COLUMN "managerId"         TEXT,
    ADD COLUMN color               INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN privacy             TEXT          NOT NULL DEFAULT 'internal',
    ADD COLUMN "allowTimesheets"   BOOLEAN       NOT NULL DEFAULT true,
    ADD COLUMN "allowMilestones"   BOOLEAN       NOT NULL DEFAULT true,
    ADD COLUMN "allowDependencies" BOOLEAN       NOT NULL DEFAULT true,
    ADD COLUMN "allowRecurring"    BOOLEAN       NOT NULL DEFAULT true,
    ADD COLUMN "isTemplate"        BOOLEAN       NOT NULL DEFAULT false,
    ADD COLUMN "salesOrderId"      TEXT,
    ADD COLUMN "dealId"            TEXT,
    ADD COLUMN "plannedHours"      NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK ("plannedHours" >= 0),
    ADD COLUMN "lastUpdateStatus"  TEXT,
    ADD COLUMN "taskSeq"           INTEGER       NOT NULL DEFAULT 0,
    ADD CONSTRAINT "Project_privacy" CHECK (privacy IN ('internal', 'members')),
    ADD CONSTRAINT "Project_last_update_status"
        CHECK ("lastUpdateStatus" IS NULL OR "lastUpdateStatus" IN ('on_track', 'at_risk', 'off_track', 'on_hold', 'done'));
CREATE INDEX "Project_manager_idx" ON "Project" ("organizationId", "managerId");
CREATE INDEX "Project_sales_order_idx" ON "Project" ("organizationId", "salesOrderId") WHERE "salesOrderId" IS NOT NULL;

CREATE TABLE "ProjectMember" (
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    "userId"         TEXT NOT NULL,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    role             TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('manager', 'member', 'viewer')),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("projectId", "userId")
);
CREATE INDEX "ProjectMember_user_idx" ON "ProjectMember" ("organizationId", "userId");

CREATE TABLE "ProjectFavorite" (
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    "userId"         TEXT NOT NULL,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    PRIMARY KEY ("projectId", "userId")
);

CREATE TABLE "ProjectTaskStage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    sequence         INTEGER NOT NULL DEFAULT 10,
    -- open: work in progress; done: closes the task (completed); cancelled: closes it without completing it
    kind             TEXT NOT NULL DEFAULT 'open' CHECK (kind IN ('open', 'done', 'cancelled')),
    folded           BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectTaskStage_project_idx" ON "ProjectTaskStage" ("projectId", sequence);

CREATE TABLE "ProjectTag" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    color            INTEGER NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProjectTag_name" ON "ProjectTag" ("organizationId", lower(name));

CREATE TABLE "ProjectMilestone" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    description      TEXT,
    deadline         DATE,
    "isReached"      BOOLEAN NOT NULL DEFAULT false,
    "reachedAt"      TIMESTAMP(3),
    "reachedById"    TEXT,
    sequence         INTEGER NOT NULL DEFAULT 10,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectMilestone_project_idx" ON "ProjectMilestone" ("projectId", deadline);

CREATE TABLE "ProjectTask" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    -- null: a personal to-do outside any project
    "projectId"          TEXT REFERENCES "Project" (id) ON DELETE CASCADE,
    number               TEXT NOT NULL,
    title                TEXT NOT NULL,
    description          TEXT,
    "stageId"            TEXT REFERENCES "ProjectTaskStage" (id),
    "parentId"           TEXT REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "milestoneId"        TEXT REFERENCES "ProjectMilestone" (id) ON DELETE SET NULL,
    "wbsId"              TEXT REFERENCES "ProjectWbsElement" (id),
    "siteId"             TEXT REFERENCES "ProjectSite" (id),
    "partnerId"          TEXT,
    priority             TEXT NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    "kanbanState"        TEXT NOT NULL DEFAULT 'normal' CHECK ("kanbanState" IN ('normal', 'blocked', 'ready')),
    "blockedReason"      TEXT,
    "plannedStart"       DATE,
    deadline             DATE,
    "plannedHours"       NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK ("plannedHours" >= 0),
    "effectiveHours"     NUMERIC(10, 2) NOT NULL DEFAULT 0,
    sequence             INTEGER NOT NULL DEFAULT 1000,
    color                INTEGER NOT NULL DEFAULT 0,
    "isClosed"           BOOLEAN NOT NULL DEFAULT false,
    "closedAt"           TIMESTAMP(3),
    recurrence           TEXT CHECK (recurrence IN ('daily', 'weekly', 'monthly', 'yearly')),
    "recurrenceInterval" INTEGER NOT NULL DEFAULT 1 CHECK ("recurrenceInterval" BETWEEN 1 AND 366),
    "recurrenceUntil"    DATE,
    "recurrenceSourceId" TEXT,
    "sourceResType"      TEXT,
    "sourceResId"        TEXT,
    "checklistDone"      INTEGER NOT NULL DEFAULT 0,
    "checklistTotal"     INTEGER NOT NULL DEFAULT 0,
    "lastReminderOn"     DATE,
    version              INTEGER NOT NULL DEFAULT 0,
    "createdById"        TEXT,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3),
    CONSTRAINT "ProjectTask_number" UNIQUE ("organizationId", number),
    CONSTRAINT "ProjectTask_not_own_parent" CHECK ("parentId" IS NULL OR "parentId" <> id)
);
CREATE INDEX "ProjectTask_board_idx" ON "ProjectTask" ("organizationId", "projectId", "stageId", sequence)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "ProjectTask_deadline_idx" ON "ProjectTask" ("organizationId", deadline)
    WHERE "deletedAt" IS NULL AND NOT "isClosed";
CREATE INDEX "ProjectTask_parent_idx" ON "ProjectTask" ("parentId") WHERE "parentId" IS NOT NULL;
CREATE INDEX "ProjectTask_source_idx" ON "ProjectTask" ("organizationId", "sourceResType", "sourceResId")
    WHERE "sourceResType" IS NOT NULL;
CREATE INDEX "ProjectTask_milestone_idx" ON "ProjectTask" ("milestoneId") WHERE "milestoneId" IS NOT NULL;

CREATE TABLE "ProjectTaskAssignee" (
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "userId"         TEXT NOT NULL,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "assignedAt"     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("taskId", "userId")
);
CREATE INDEX "ProjectTaskAssignee_user_idx" ON "ProjectTaskAssignee" ("organizationId", "userId");

CREATE TABLE "ProjectTaskTag" (
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "tagId"          TEXT NOT NULL REFERENCES "ProjectTag" (id) ON DELETE CASCADE,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    PRIMARY KEY ("taskId", "tagId")
);

CREATE TABLE "ProjectTaskDependency" (
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "dependsOnId"    TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    PRIMARY KEY ("taskId", "dependsOnId"),
    CONSTRAINT "ProjectTaskDependency_self" CHECK ("taskId" <> "dependsOnId")
);
CREATE INDEX "ProjectTaskDependency_blocking_idx" ON "ProjectTaskDependency" ("dependsOnId");

CREATE TABLE "ProjectTaskChecklistItem" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    title            TEXT NOT NULL,
    "isDone"         BOOLEAN NOT NULL DEFAULT false,
    sequence         INTEGER NOT NULL DEFAULT 10,
    "doneById"       TEXT,
    "doneAt"         TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectTaskChecklistItem_task_idx" ON "ProjectTaskChecklistItem" ("taskId", sequence);

CREATE TABLE "ProjectTaskFollower" (
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "userId"         TEXT NOT NULL,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    PRIMARY KEY ("taskId", "userId")
);

-- Chatter of a project (taskId null) or of a task: comment = message to followers, note = internal log,
-- system = tracked change (stage, assignees, deadline, ...).
CREATE TABLE "ProjectMessage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT REFERENCES "Project" (id) ON DELETE CASCADE,
    "taskId"         TEXT REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    kind             TEXT NOT NULL CHECK (kind IN ('comment', 'note', 'system')),
    body             TEXT NOT NULL,
    "authorId"       TEXT,
    "authorName"     TEXT,
    mentions         TEXT[] NOT NULL DEFAULT '{}',
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "editedAt"       TIMESTAMP(3)
);
CREATE INDEX "ProjectMessage_task_idx" ON "ProjectMessage" ("taskId", "createdAt") WHERE "taskId" IS NOT NULL;
CREATE INDEX "ProjectMessage_project_idx" ON "ProjectMessage" ("projectId", "createdAt");

-- Time spent on a project / task. hrEntryId: the HrTimesheetEntry mirror (linkedEntityType 'project'), which HR
-- approves and project cost capture (POST /projects/:id/costs/timesheets) posts to WIP.
CREATE TABLE "ProjectTimesheetLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    "taskId"         TEXT REFERENCES "ProjectTask" (id) ON DELETE SET NULL,
    "userId"         TEXT NOT NULL,
    "employeeId"     TEXT,
    date             DATE NOT NULL,
    hours            NUMERIC(8, 2) NOT NULL CHECK (hours > 0 AND hours <= 24),
    description      TEXT,
    "isBillable"     BOOLEAN NOT NULL DEFAULT true,
    "hrEntryId"      TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectTimesheetLine_user_idx" ON "ProjectTimesheetLine" ("organizationId", "userId", date);
CREATE INDEX "ProjectTimesheetLine_project_idx" ON "ProjectTimesheetLine" ("projectId", date);
CREATE INDEX "ProjectTimesheetLine_task_idx" ON "ProjectTimesheetLine" ("taskId") WHERE "taskId" IS NOT NULL;

-- One running timer per user.
CREATE TABLE "ProjectTimer" (
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "userId"         TEXT NOT NULL,
    "taskId"         TEXT NOT NULL REFERENCES "ProjectTask" (id) ON DELETE CASCADE,
    "startedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("organizationId", "userId")
);

CREATE TABLE "ProjectUpdate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    status           TEXT NOT NULL CHECK (status IN ('on_track', 'at_risk', 'off_track', 'on_hold', 'done')),
    progress         INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    summary          TEXT,
    date             DATE NOT NULL DEFAULT CURRENT_DATE,
    stats            JSONB NOT NULL DEFAULT '{}',
    "authorId"       TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectUpdate_project_idx" ON "ProjectUpdate" ("projectId", "createdAt" DESC);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ProjectMember', 'ProjectFavorite', 'ProjectTaskStage', 'ProjectTag', 'ProjectMilestone',
                             'ProjectTask', 'ProjectTaskAssignee', 'ProjectTaskTag', 'ProjectTaskDependency',
                             'ProjectTaskChecklistItem', 'ProjectTaskFollower', 'ProjectMessage',
                             'ProjectTimesheetLine', 'ProjectTimer', 'ProjectUpdate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

-- Existing projects get the default stages and their creator as manager.
INSERT INTO "ProjectTaskStage" (id, "organizationId", "projectId", name, sequence, kind, folded)
SELECT gen_random_uuid()::text, p."organizationId", p.id, s.name, s.seq, s.kind, s.folded
FROM "Project" p
CROSS JOIN (VALUES ('To Do', 10, 'open', false), ('In Progress', 20, 'open', false), ('Review', 30, 'open', false),
                   ('Done', 40, 'done', false), ('Cancelled', 50, 'cancelled', true)) AS s(name, seq, kind, folded);
UPDATE "Project" SET "managerId" = "createdBy" WHERE "managerId" IS NULL AND "createdBy" IS NOT NULL;

-- Per-organisation counters (project codes PRJ-0001, personal to-do numbers). Plain rows rather than database
-- sequences, so the application role never owns schema objects.
CREATE TABLE "ProjectCounter" (
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    key              TEXT NOT NULL,
    value            BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY ("organizationId", key)
);
ALTER TABLE "ProjectCounter" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProjectCounter"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
