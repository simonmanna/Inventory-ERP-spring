package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.approvals.ApprovalOrgDirectory;
import com.inventoryerp.kernel.tenancy.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Answers the approval engine's department questions from the employee records (CTRL-1). */
@Component
public class HrApprovalDirectory implements ApprovalOrgDirectory {

    private final JdbcClient jdbc;

    public HrApprovalDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String departmentOf(String userId) {
        return jdbc.sql("SELECT \"departmentId\" FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND"
                        + " \"deletedAt\" IS NULL AND \"departmentId\" IS NOT NULL LIMIT 1")
                .params(TenantContext.organizationId(), userId).query(String.class).optional().orElse(null);
    }

    @Override
    public String departmentManagerOf(String departmentId) {
        return jdbc.sql("SELECT e.\"userId\" FROM \"HrDepartment\" d JOIN \"HrEmployee\" e ON e.id = d.\"managerId\""
                        + " WHERE d.id = ? AND d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL"
                        + " AND e.\"deletedAt\" IS NULL AND e.\"userId\" IS NOT NULL LIMIT 1")
                .params(departmentId, TenantContext.organizationId()).query(String.class).optional().orElse(null);
    }
}
