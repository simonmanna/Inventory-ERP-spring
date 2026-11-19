package com.inventoryerp.biexport.application;

import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Embedded Superset dashboards, one per ERP module (ADR-S078). The API hands the browser a short-lived Superset guest
 * token for one dashboard, and the token carries a row-level security clause that pins every query to the caller's
 * organization. The organization comes from the signed-in session, never from the request, which is what keeps one
 * tenant out of another's figures on a shared database.
 */
@Service
public class SupersetEmbedService {

    /** Lifetime of a guest token; matches GUEST_TOKEN_JWT_EXP_SECONDS in infra/docker/superset/superset_config.py. */
    static final long GUEST_TOKEN_SECONDS = 300;

    /** Organization ids are UUIDs or seed codes; anything else never reaches a SQL clause. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** An ERP module that can have a dashboard; the key is the analytics screen path and the config key. */
    public record BiModule(String key, String label, String permission) {}

    /**
     * B0 modules use the keys and permissions of the built-in analytics dashboards (ADR-S020); B1 and B2 modules, which
     * have no built-in dashboard, use the permission of the module's own reports.
     */
    public static final List<BiModule> MODULES = List.of(
            new BiModule("sales", "Sales", Permissions.ANALYTICS__SALES),
            new BiModule("procurement", "Procurement", Permissions.ANALYTICS__PROCUREMENT),
            new BiModule("inventory", "Inventory", Permissions.ANALYTICS__INVENTORY),
            new BiModule("finance", "Finance", Permissions.ANALYTICS__FINANCE),
            new BiModule("pos", "Point of sale", Permissions.POS__REPORTS),
            new BiModule("hr", "HR", Permissions.HR__REPORT),
            new BiModule("manufacturing", "Manufacturing", Permissions.PRODUCTION_ORDER__READ),
            // B2: the business overview shows profit, so it needs the finance analytics permission.
            new BiModule("overview", "Business overview", Permissions.ANALYTICS__FINANCE),
            new BiModule("expenses", "Expenses", Permissions.EXPENSE__READ),
            new BiModule("crm", "CRM pipeline", Permissions.CRM__REPORT_READ));

    /** What the browser needs to embed one dashboard with the Superset embedded SDK. */
    public record GuestToken(String module, String dashboardId, String supersetDomain, String token,
            Instant expiresAt) {}

    private final SupersetSettings settings;
    private final SupersetClient client;
    private final PermissionService permissions;

    public SupersetEmbedService(SupersetSettings settings, SupersetClient client, PermissionService permissions) {
        this.settings = settings;
        this.client = client;
        this.permissions = permissions;
    }

    /** The Row-level security clause for one organization. */
    static String organizationClause(String organizationId) {
        if (organizationId == null || !SAFE_ID.matcher(organizationId).matches()) {
            throw ApiException.forbidden("Organization context required");
        }
        return "\"organizationId\" = '" + organizationId + "'";
    }

    /** The dashboards the caller may open: configured, and the caller holds the module's analytics permission. */
    public List<Map<String, Object>> dashboards() {
        SupersetProperties properties = settings.current();
        if (!properties.enabled()) {
            return List.of();
        }
        List<String> granted = permissions.current();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiModule m : MODULES) {
            if (properties.dashboards().containsKey(m.key()) && granted.contains(m.permission())) {
                out.add(Map.of("module", m.key(), "label", m.label()));
            }
        }
        return out;
    }

    /** A guest token for the module's dashboard, limited to the caller's organization; 404 while Superset is off. */
    public GuestToken guestToken(String module) {
        // Superset off means no module has a dashboard: the same 404 as an unconfigured module. 503 is kept for a
        // configured Superset that cannot be reached.
        SupersetProperties properties = settings.current();
        if (!properties.enabled()) {
            throw ApiException.notFound("Dashboard not found");
        }
        BiModule m = MODULES.stream().filter(x -> x.key().equals(module)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Dashboard not found"));
        if (!permissions.current().contains(m.permission())) {
            throw ApiException.forbidden("Missing required permission(s): " + m.permission());
        }
        String dashboardId = properties.dashboards().get(m.key());
        if (dashboardId == null) {
            throw ApiException.notFound("Dashboard not found");
        }
        String clause = organizationClause(TenantContext.organizationId());
        String user = "erp:" + TenantContext.userId();
        try {
            String token = client.guestToken(dashboardId, user, List.of(clause));
            return new GuestToken(m.key(), dashboardId, properties.publicUrl(), token,
                    Instant.now().plusSeconds(GUEST_TOKEN_SECONDS));
        } catch (SupersetClient.Unavailable e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The dashboard service is unavailable");
        }
    }
}
