-- CRM sales teams linked to HR (owner request 2026-11-23, docs/spec/crm.md deviation "Sales teams ↔ HR").
-- A team may name the HR department it belongs to and the branch it sells for. Members and the leader stay user ids
-- in "leaderId" / "memberIds" (every reader keeps working); the service only admits users who are active HR
-- employees. Existing members who are not are kept and reported, never removed.
ALTER TABLE "CrmTeam"
    ADD COLUMN "departmentId" TEXT REFERENCES "HrDepartment" (id) ON DELETE SET NULL,
    ADD COLUMN "branchId"     TEXT REFERENCES "Branch" (id) ON DELETE SET NULL;

CREATE INDEX "CrmTeam_departmentId_idx" ON "CrmTeam" ("departmentId");
CREATE INDEX "CrmTeam_branchId_idx" ON "CrmTeam" ("branchId");

-- The employee profile lists the teams a user belongs to.
CREATE INDEX "CrmTeam_memberIds_gin" ON "CrmTeam" USING gin ("memberIds");
