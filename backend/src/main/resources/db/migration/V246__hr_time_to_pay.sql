-- V246: HP-5 — time to pay: overtime approval on attendance rows. Not mapped on the Nest entity, so the Nest JSON of
-- attendance is unchanged. NULL = not reviewed; with requireOvertimeApproval only TRUE rows are paid.

ALTER TABLE "HrAttendance" ADD COLUMN "overtimeApproved" BOOLEAN;
ALTER TABLE "HrAttendance" ADD COLUMN "overtimeReviewedBy" TEXT;
ALTER TABLE "HrAttendance" ADD COLUMN "overtimeReviewedAt" TIMESTAMP(3);
CREATE INDEX "HrAttendance_ot_pending_idx" ON "HrAttendance" ("organizationId", date)
    WHERE "overtimeMinutes" > 0 AND "overtimeApproved" IS NULL AND "deletedAt" IS NULL;
