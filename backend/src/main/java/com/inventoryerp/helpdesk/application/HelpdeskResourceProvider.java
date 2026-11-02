package com.inventoryerp.helpdesk.application;

import com.inventoryerp.dms.spi.DmsResourceProvider;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Helpdesk tickets (and letters) as a DMS record type: attachments land in the "helpdesk" workspace. */
@Component
public class HelpdeskResourceProvider implements DmsResourceProvider {

    public static final String RES_TYPE = "helpdesk_ticket";

    private final JdbcClient jdbc;

    public HelpdeskResourceProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resType() {
        return RES_TYPE;
    }

    @Override
    public String label() {
        return "Helpdesk ticket";
    }

    @Override
    public String readPermission() {
        return Permissions.HELPDESK__TICKET_READ;
    }

    @Override
    public String writePermission() {
        return Permissions.HELPDESK__TICKET_WRITE;
    }

    @Override
    public String workspaceCode() {
        return "helpdesk";
    }

    @Override
    public String displayName(String resId) {
        List<String> rows = jdbc.sql("SELECT coalesce(\"letterNumber\", number) || ' ' || subject FROM \"HdTicket\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(resId, TenantContext.organizationId()).query(String.class).list();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public String webPath(String resId) {
        return "/helpdesk/tickets/" + resId;
    }
}
