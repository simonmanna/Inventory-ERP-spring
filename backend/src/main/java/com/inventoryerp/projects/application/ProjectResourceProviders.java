package com.inventoryerp.projects.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Projects and project tasks as DMS record types (ADR-S062): their files land in the "projects" workspace. */
@Configuration(proxyBeanMethods = false)
public class ProjectResourceProviders {

    public static final String PROJECT = "project";
    public static final String PROJECT_TASK = "project_task";

    @Bean
    DmsResourceProvider projectResourceProvider(JdbcClient jdbc) {
        return new Provider(jdbc, PROJECT, "Project", "SELECT code || ' ' || name FROM \"Project\" WHERE id = ? AND"
                + " \"organizationId\" = ?", "/projects/");
    }

    @Bean
    DmsResourceProvider projectTaskResourceProvider(JdbcClient jdbc) {
        return new Provider(jdbc, PROJECT_TASK, "Project task", "SELECT number || ' ' || title FROM \"ProjectTask\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", null);
    }

    record Provider(JdbcClient jdbc, String resType, String label, String sql, String pathPrefix)
            implements DmsResourceProvider {

        @Override
        public String readPermission() {
            return Permissions.PROJECT_TASK__READ;
        }

        @Override
        public String writePermission() {
            return Permissions.PROJECT_TASK__WRITE;
        }

        @Override
        public String workspaceCode() {
            return "projects";
        }

        @Override
        public String displayName(String resId) {
            List<String> rows = jdbc.sql(sql).params(resId, TenantContext.organizationId()).query(String.class).list();
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public String webPath(String resId) {
            if (pathPrefix != null) {
                return pathPrefix + resId;
            }
            List<String> project = jdbc.sql("SELECT \"projectId\" FROM \"ProjectTask\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(resId, TenantContext.organizationId()).query(String.class).list();
            if (project.isEmpty()) {
                return null;
            }
            return project.getFirst() == null ? "/projects/my-tasks?task=" + resId
                    : "/projects/" + project.getFirst() + "?task=" + resId;
        }
    }
}
