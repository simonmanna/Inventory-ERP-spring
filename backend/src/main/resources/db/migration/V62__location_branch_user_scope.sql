-- V62 (BR-1): link locations to branches and restrict users to branches or locations.
--
-- Additive. "branchId" stays NULL on every existing location. A user with no row in "UserDataScope" is unrestricted and
-- sees exactly what they saw before. "User"."defaultBranchId" is deliberately NOT the restriction: it is the active
-- branch a user picks for themselves and HR fills in, and it is set for existing users, so treating it as a limit
-- would silently cut their data.

ALTER TABLE "InventoryLocation" ADD COLUMN "branchId" TEXT REFERENCES "Branch" (id);
CREATE INDEX "InventoryLocation_branch_idx" ON "InventoryLocation" ("organizationId", "branchId");

CREATE TABLE "UserDataScope" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL REFERENCES "User" (id) ON DELETE CASCADE,
    "branchId"       TEXT REFERENCES "Branch" (id) ON DELETE CASCADE,
    "locationId"     TEXT REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "UserDataScope_one_target" CHECK (("branchId" IS NULL) <> ("locationId" IS NULL))
);

CREATE UNIQUE INDEX "UserDataScope_branch_key" ON "UserDataScope" ("organizationId", "userId", "branchId")
    WHERE "branchId" IS NOT NULL;
CREATE UNIQUE INDEX "UserDataScope_location_key" ON "UserDataScope" ("organizationId", "userId", "locationId")
    WHERE "locationId" IS NOT NULL;
CREATE INDEX "UserDataScope_user_idx" ON "UserDataScope" ("organizationId", "userId");

ALTER TABLE "UserDataScope" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "UserDataScope" USING (("organizationId" = current_setting('app.org_id', true)));
