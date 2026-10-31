-- V95: CRM-2 — customer segments are saved filters over /crm/customers (owner decision logged in DECISIONS_FOR_OWNER):
-- partner category, credit hold and search, saved per user or shared. No segment entity. Adds the scope.

ALTER TABLE "CrmSavedFilter" DROP CONSTRAINT "CrmSavedFilter_scope_check";
ALTER TABLE "CrmSavedFilter" ADD CONSTRAINT "CrmSavedFilter_scope_check"
    CHECK (scope IN ('pipeline', 'leads', 'quotations', 'activities', 'report', 'customers'));
