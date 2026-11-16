package com.inventoryerp.projects.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bonds and compliance documents as DMS record types (ADR-S074): the scanned guarantee or certificate is attached to
 * its record and lands in the "projects" workspace.
 */
@Configuration(proxyBeanMethods = false)
public class ConstructionResourceProviders {

    public static final String BOND = "project_bond";
    public static final String COMPLIANCE_DOCUMENT = "compliance_document";

    @Bean
    DmsResourceProvider projectBondResourceProvider(JdbcClient jdbc) {
        return new Provider(jdbc, BOND, "Bond / guarantee", Permissions.BOND__READ, Permissions.BOND__MANAGE,
                "SELECT number || ' ' || issuer FROM \"ProjectBond\" WHERE id = ? AND \"organizationId\" = ?",
                "/projects/bonds?bond=");
    }

    @Bean
    DmsResourceProvider complianceDocumentResourceProvider(JdbcClient jdbc) {
        return new Provider(jdbc, COMPLIANCE_DOCUMENT, "Compliance document", Permissions.COMPLIANCE__READ,
                Permissions.COMPLIANCE__MANAGE, "SELECT replace(d.\"documentType\", '_', ' ') || coalesce(' ' ||"
                        + " d.\"documentNumber\", '') || ' · ' || p.name FROM \"ComplianceDocument\" d JOIN \"Partner\" p"
                        + " ON p.id = d.\"partnerId\" WHERE d.id = ? AND d.\"organizationId\" = ?", null);
    }

    record Provider(JdbcClient jdbc, String resType, String label, String readPermission, String writePermission,
            String sql, String pathPrefix) implements DmsResourceProvider {

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
            List<String> partner = jdbc.sql("SELECT \"partnerId\" FROM \"ComplianceDocument\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(resId, TenantContext.organizationId()).query(String.class)
                    .list();
            return partner.isEmpty() ? null : "/suppliers/" + partner.getFirst() + "?tab=compliance";
        }
    }
}
