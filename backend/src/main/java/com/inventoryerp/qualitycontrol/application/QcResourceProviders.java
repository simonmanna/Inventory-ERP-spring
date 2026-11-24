package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Quality-control records as DMS attachment targets (ADR-S087 §11). Read needs {@code quality_control:read}; write is
 * per resource: control-point documents need {@code configure}, check photos {@code check}, alert evidence
 * {@code alert} — an operator who may execute checks cannot change a control point's instructions.
 */
@Configuration
public class QcResourceProviders {

    @Bean
    DmsResourceProvider qcControlPointResource(JdbcClient jdbc) {
        return new Provider(jdbc, "qc_control_point", "Quality control point", Permissions.QUALITY_CONTROL__CONFIGURE,
                "SELECT reference || ' ' || title FROM \"QcControlPoint\" WHERE id = ? AND \"organizationId\" = ?",
                "/quality-control/control-points/");
    }

    @Bean
    DmsResourceProvider qcCheckResource(JdbcClient jdbc) {
        return new Provider(jdbc, QcCheckService.RES_TYPE, "Quality check", Permissions.QUALITY_CONTROL__CHECK,
                "SELECT \"checkNumber\" || ' ' || title FROM \"QcCheck\" WHERE id = ? AND \"organizationId\" = ?",
                "/quality-control/checks/");
    }

    /** One record type; display name from one org-scoped SQL expression. */
    record Provider(JdbcClient jdbc, String resType, String label, String writePermission, String nameSql,
            String pathPrefix) implements DmsResourceProvider {

        @Override
        public String readPermission() {
            return Permissions.QUALITY_CONTROL__READ;
        }

        @Override
        public String workspaceCode() {
            return "general";
        }

        @Override
        public String displayName(String resId) {
            List<String> rows = jdbc.sql(nameSql).params(resId, TenantContext.organizationId()).query(String.class).list();
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public String webPath(String resId) {
            return pathPrefix + resId;
        }
    }
}
