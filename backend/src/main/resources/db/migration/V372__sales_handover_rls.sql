-- V350 created "SalesHandover" without the tenant row-level-security policy every org-scoped table carries
-- (TenantRowSecurityIT). Same policy as the other tables.
ALTER TABLE "SalesHandover" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesHandover" USING (("organizationId" = current_setting('app.org_id', true)));
