-- ADR-S083 O0: offline hybrid foundations. Additive only; Nest-shaped columns untouched.
-- New org-scoped tables carry the V214 tenant policy (restricted inside a tenant transaction, open without one).

-- C1: permanent applied-operation ledger. A row is written inside the op's first business commit
-- (state 'committed'), then completed with the original result (state 'applied'). Unique per org + opId.
CREATE TABLE "SyncAppliedOp" (
    id text NOT NULL,
    "organizationId" text NOT NULL,
    "opId" text NOT NULL,
    "payloadHash" text NOT NULL,
    "opType" text NOT NULL,
    "deviceId" text,
    "deviceSeq" integer,
    "serverId" text NOT NULL,
    "originChain" jsonb DEFAULT '[]'::jsonb NOT NULL,
    "supersedesOpId" text,
    state text NOT NULL,
    "httpStatus" integer,
    "resultJson" jsonb,
    "committedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "completedAt" timestamp(3) without time zone,
    CONSTRAINT "SyncAppliedOp_pkey" PRIMARY KEY (id),
    CONSTRAINT "SyncAppliedOp_state_check" CHECK (state IN ('committed', 'applied'))
);
CREATE UNIQUE INDEX "SyncAppliedOp_organizationId_opId_key" ON "SyncAppliedOp" ("organizationId", "opId");
CREATE INDEX "SyncAppliedOp_device_seq_idx" ON "SyncAppliedOp" ("organizationId", "deviceId", "deviceSeq");
CREATE INDEX "SyncAppliedOp_incomplete_idx" ON "SyncAppliedOp" ("organizationId") WHERE state = 'committed';
ALTER TABLE "SyncAppliedOp" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncAppliedOp" USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- S5: dead-letter workflow (open -> investigating -> resolved | retried | discarded).
ALTER TABLE "SyncOpDeadLetter"
    ADD COLUMN "failureCode" text,
    ADD COLUMN retryable boolean DEFAULT false NOT NULL,
    ADD COLUMN attempts integer DEFAULT 1 NOT NULL,
    ADD COLUMN "lastAttemptAt" timestamp(3) without time zone,
    ADD COLUMN "branchId" text,
    ADD COLUMN "resolvedByOpId" text,
    ADD COLUMN "investigatingById" text,
    ADD COLUMN "investigatingAt" timestamp(3) without time zone,
    ADD COLUMN "investigationNote" text;

-- S2/C4: device identity, token lifecycle and offline policy (revocation stays "revokedAt").
ALTER TABLE "PosDevice"
    ADD COLUMN "serverId" text,
    ADD COLUMN "tokenExpiresAt" timestamp(3) without time zone,
    ADD COLUMN "tokenRotatedAt" timestamp(3) without time zone,
    ADD COLUMN "previousTokenHash" text,
    ADD COLUMN "previousTokenValidUntil" timestamp(3) without time zone,
    ADD COLUMN "protocolVersion" text,
    ADD COLUMN "lastPullAt" timestamp(3) without time zone,
    ADD COLUMN "offlineWindowHours" integer;
CREATE INDEX IF NOT EXISTS "PosDevice_previousTokenHash_idx" ON "PosDevice" ("previousTokenHash");

-- C5: snapshot sync. Each served page is recorded so completion can prove the device received all of them.
CREATE TABLE "SyncSnapshot" (
    id text NOT NULL,
    "organizationId" text NOT NULL,
    "deviceId" text NOT NULL,
    "snapshotAt" timestamp(3) without time zone NOT NULL,
    "expiresAt" timestamp(3) without time zone NOT NULL,
    scopes jsonb NOT NULL,
    status text DEFAULT 'open'::text NOT NULL,
    "completedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "SyncSnapshot_pkey" PRIMARY KEY (id),
    CONSTRAINT "SyncSnapshot_status_check" CHECK (status IN ('open', 'completed'))
);
CREATE INDEX "SyncSnapshot_device_idx" ON "SyncSnapshot" ("organizationId", "deviceId");
ALTER TABLE "SyncSnapshot" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncSnapshot" USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

CREATE TABLE "SyncSnapshotPage" (
    id text NOT NULL,
    "organizationId" text NOT NULL,
    "snapshotId" text NOT NULL REFERENCES "SyncSnapshot" (id) ON DELETE CASCADE,
    scope text NOT NULL,
    "afterKey" text NOT NULL,
    "nextAfter" text,
    "rowCount" integer NOT NULL,
    checksum text NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "SyncSnapshotPage_pkey" PRIMARY KEY (id)
);
CREATE UNIQUE INDEX "SyncSnapshotPage_page_key" ON "SyncSnapshotPage" ("snapshotId", scope, "afterKey");
ALTER TABLE "SyncSnapshotPage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncSnapshotPage" USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- S7: a change to a menu item's children re-sends the item (pull ships menu items as aggregates).
CREATE OR REPLACE FUNCTION sync_touch_menu_item() RETURNS trigger AS $$
DECLARE
    item_id text;
BEGIN
    item_id := CASE WHEN TG_OP = 'DELETE' THEN OLD."menuItemId" ELSE NEW."menuItemId" END;
    UPDATE "MenuItem" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id = item_id;
    IF TG_OP = 'UPDATE' AND OLD."menuItemId" IS DISTINCT FROM NEW."menuItemId" THEN
        UPDATE "MenuItem" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id = OLD."menuItemId";
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_touch_menu_item_variant AFTER INSERT OR UPDATE OR DELETE ON "MenuItemVariant"
    FOR EACH ROW EXECUTE FUNCTION sync_touch_menu_item();
CREATE TRIGGER sync_touch_menu_item_modifier_group AFTER INSERT OR UPDATE OR DELETE ON "MenuItemModifierGroup"
    FOR EACH ROW EXECUTE FUNCTION sync_touch_menu_item();
CREATE TRIGGER sync_touch_menu_item_accompaniment_group AFTER INSERT OR UPDATE OR DELETE ON "MenuItemAccompanimentGroup"
    FOR EACH ROW EXECUTE FUNCTION sync_touch_menu_item();

-- A modifier / accompaniment option change re-sends its group, and the group change re-sends linked items.
CREATE OR REPLACE FUNCTION sync_touch_group() RETURNS trigger AS $$
DECLARE
    group_id text;
BEGIN
    group_id := CASE WHEN TG_OP = 'DELETE' THEN OLD."groupId" ELSE NEW."groupId" END;
    IF TG_TABLE_NAME = 'Modifier' THEN
        UPDATE "ModifierGroup" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id = group_id;
        UPDATE "MenuItem" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id IN
            (SELECT "menuItemId" FROM "MenuItemModifierGroup" WHERE "modifierGroupId" = group_id);
    ELSE
        UPDATE "AccompanimentGroup" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id = group_id;
        UPDATE "MenuItem" SET "updatedAt" = CURRENT_TIMESTAMP WHERE id IN
            (SELECT "menuItemId" FROM "MenuItemAccompanimentGroup" WHERE "accompanimentGroupId" = group_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_touch_modifier AFTER INSERT OR UPDATE OR DELETE ON "Modifier"
    FOR EACH ROW EXECUTE FUNCTION sync_touch_group();
CREATE TRIGGER sync_touch_accompaniment_option AFTER INSERT OR UPDATE OR DELETE ON "AccompanimentOption"
    FOR EACH ROW EXECUTE FUNCTION sync_touch_group();
