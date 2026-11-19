package com.inventoryerp.biexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventoryerp.biexport.application.SupersetClient;
import com.inventoryerp.biexport.application.SupersetEmbedService;
import com.inventoryerp.biexport.application.SupersetProperties;
import com.inventoryerp.biexport.application.SupersetSettings;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guest tokens against a stub Superset: the clause pins the session's organization, and failures are a clean 503. */
class SupersetEmbedServiceTest {

    static final String DASHBOARD = "8f0f6b1c-1111-4c3e-9a51-0123456789ab";
    static final String HR_DASHBOARD = "8f0f6b1c-2222-4c3e-9a51-0123456789ab";

    HttpServer server;
    final List<String> guestBodies = new CopyOnWriteArrayList<>();
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger guestStatus = new AtomicInteger(200);
    final AtomicInteger loginStatus = new AtomicInteger(200);
    PermissionService permissions;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/security/login", ex -> {
            logins.incrementAndGet();
            reply(ex, loginStatus.get(), "{\"access_token\":\"access-" + logins.get() + "\"}");
        });
        server.createContext("/api/v1/security/csrf_token/", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/");
            reply(ex, 200, "{\"result\":\"csrf-1\"}");
        });
        server.createContext("/api/v1/security/guest_token/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = "csrf-1".equals(ex.getRequestHeaders().getFirst("X-CSRFToken"))
                    && ex.getRequestHeaders().getFirst("Authorization").startsWith("Bearer access-");
            guestBodies.add(body);
            int status = ok ? guestStatus.get() : 400;
            if (status == 401) {
                guestStatus.set(200);
            }
            reply(ex, status, "{\"token\":\"guest-token\"}");
        });
        server.start();
        permissions = mock(PermissionService.class);
        when(permissions.current()).thenReturn(List.of("analytics:view", "analytics:sales"));
        TenantContext.set(TenantContext.TenantStore.user("org-a", "user-1"));
    }

    @AfterEach
    void stop() {
        TenantContext.clear();
        server.stop(0);
    }

    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    SupersetEmbedService service(String url) {
        SupersetProperties props = new SupersetProperties(url, "https://bi.example.com/", "embedder", "secret",
                Map.of("sales", DASHBOARD, "inventory", "", "hr", HR_DASHBOARD), Duration.ofSeconds(2));
        SupersetSettings settings = new SupersetSettings(props);
        return new SupersetEmbedService(settings, new SupersetClient(settings), permissions);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("the guest token is for one dashboard and pinned to the session's organization")
    void pinsOrganization() {
        var token = service(url()).guestToken("sales");
        assertThat(token.token()).isEqualTo("guest-token");
        assertThat(token.dashboardId()).isEqualTo(DASHBOARD);
        assertThat(token.supersetDomain()).isEqualTo("https://bi.example.com");
        assertThat(guestBodies).hasSize(1);
        assertThat(guestBodies.getFirst()).contains("\"clause\":\"\\\"organizationId\\\" = 'org-a'\"")
                .contains("\"id\":\"" + DASHBOARD + "\"").contains("\"type\":\"dashboard\"");
    }

    @Test
    @DisplayName("the service-account login is reused, and a stale one is replaced once")
    void reusesAndRefreshesLogin() {
        SupersetEmbedService s = service(url());
        s.guestToken("sales");
        s.guestToken("sales");
        assertThat(logins.get()).isEqualTo(1);
        guestStatus.set(401);
        s.guestToken("sales");
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("only configured dashboards the caller holds the permission for are listed")
    void listsAllowed() {
        assertThat(service(url()).dashboards()).containsExactly(Map.of("module", "sales", "label", "Sales"));
    }

    @Test
    @DisplayName("a module without a built-in dashboard is gated by its own reports permission (HR: hr:report)")
    void moduleReportPermission() {
        SupersetEmbedService s = service(url());
        assertThatThrownBy(() -> s.guestToken("hr")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(403);
        when(permissions.current()).thenReturn(List.of("analytics:view", "hr:report"));
        assertThat(s.dashboards()).containsExactly(Map.of("module", "hr", "label", "HR"));
        assertThat(s.guestToken("hr").dashboardId()).isEqualTo(HR_DASHBOARD);
        assertThat(guestBodies.getFirst()).contains("\"organizationId\\\" = 'org-a'");
    }

    @Test
    @DisplayName("missing permission is 403, an unknown or unconfigured module or Superset off 404, Superset down 503")
    void errors() {
        SupersetEmbedService s = service(url());
        assertThatThrownBy(() -> s.guestToken("finance")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(403);
        when(permissions.current()).thenReturn(List.of("analytics:inventory"));
        assertThatThrownBy(() -> s.guestToken("inventory")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(404);
        assertThatThrownBy(() -> s.guestToken("pg_user")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(404);
        when(permissions.current()).thenReturn(List.of("analytics:sales"));
        loginStatus.set(401);
        assertThatThrownBy(() -> s.guestToken("sales")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(503);
        assertThatThrownBy(() -> service("http://127.0.0.1:1").guestToken("sales"))
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(503);
        SupersetEmbedService off = new SupersetEmbedService(
                new SupersetSettings(new SupersetProperties("", null, null, null, null, null)), null, permissions);
        assertThat(off.dashboards()).isEmpty();
        assertThatThrownBy(() -> off.guestToken("sales")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("B2 modules: the business overview needs analytics:finance, expenses expense:read, CRM crm:report:read")
    void b2Modules() {
        SupersetProperties props = new SupersetProperties(url(), null, "embedder", "secret",
                Map.of("overview", DASHBOARD, "expenses", DASHBOARD, "crm", DASHBOARD), Duration.ofSeconds(2));
        SupersetSettings settings = new SupersetSettings(props);
        SupersetEmbedService s = new SupersetEmbedService(settings, new SupersetClient(settings), permissions);
        assertThat(s.dashboards()).isEmpty();
        assertThatThrownBy(() -> s.guestToken("overview")).extracting(e -> ((ApiException) e).status().value())
                .isEqualTo(403);
        when(permissions.current()).thenReturn(List.of("analytics:view", "analytics:finance", "expense:read",
                "crm:report:read"));
        assertThat(s.dashboards()).extracting(m -> m.get("module")).containsExactly("overview", "expenses", "crm");
    }

    @Test
    @DisplayName("ids and the service account can come from files written at run time; values set directly win")
    void fileValues(@TempDir Path dir) throws IOException {
        Path dashboards = dir.resolve("dashboards.json");
        Path secrets = dir.resolve("secrets.env");
        SupersetProperties props = new SupersetProperties(url(), null, null, null, Map.of("hr", HR_DASHBOARD),
                Duration.ofSeconds(2), dashboards.toString(), secrets.toString());
        SupersetSettings settings = new SupersetSettings(props);
        SupersetEmbedService s = new SupersetEmbedService(settings, new SupersetClient(settings), permissions);
        assertThat(s.dashboards()).as("no service account yet: off").isEmpty();

        Files.writeString(secrets, "# generated\nSUPERSET_EMBED_USERNAME=erp_embedder\nSUPERSET_EMBED_PASSWORD=\"pw\"\n");
        Files.writeString(dashboards, "{\"dashboards\": {\"sales\": \"" + DASHBOARD + "\", \"hr\": \"other\","
                + " \"bad key\": \"x\", \"inventory\": \"'; DROP\"}}");
        assertThat(settings.current().username()).isEqualTo("erp_embedder");
        assertThat(settings.current().password()).isEqualTo("pw");
        assertThat(settings.current().dashboards()).containsEntry("sales", DASHBOARD)
                .containsEntry("hr", HR_DASHBOARD).doesNotContainKeys("bad key", "inventory");
        assertThat(s.guestToken("sales").dashboardId()).isEqualTo(DASHBOARD);
    }

    @Test
    @DisplayName("an organization id that could break out of the clause is refused before Superset is called")
    void refusesUnsafeOrganization() {
        TenantContext.set(TenantContext.TenantStore.user("x' OR '1'='1", "user-1"));
        assertThatThrownBy(() -> service(url()).guestToken("sales"))
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(403);
        assertThat(guestBodies).isEmpty();
    }
}
