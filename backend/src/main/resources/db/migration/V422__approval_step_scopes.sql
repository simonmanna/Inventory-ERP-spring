-- ADR-S061: stages can be assigned to named users or roles. V50 limited approverScope to the CTRL-1 values.
ALTER TABLE "ApprovalStep" DROP CONSTRAINT "ApprovalStep_approverScope_check";
ALTER TABLE "ApprovalStep" ADD CONSTRAINT "ApprovalStep_approverScope_check"
    CHECK ("approverScope" IN ('permission', 'department_manager', 'user', 'role'));

-- Rule and request priorities are a closed set too.
ALTER TABLE "ApprovalWorkflow" ADD CONSTRAINT "ApprovalWorkflow_priority_check"
    CHECK (priority IN ('low', 'medium', 'high', 'critical'));
ALTER TABLE "ApprovalRequest" ADD CONSTRAINT "ApprovalRequest_priority_check"
    CHECK (priority IN ('low', 'medium', 'high', 'critical'));
