package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.seed.SeedRunnerAccess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for module HTTP integration suites on Testcontainers Postgres: seeds DEMO, creates a second
 * organization (for tenant isolation), logs in the DEMO admin, and offers JSON call helpers plus a
 * route-by-route authorization matrix for a module package.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ApiTestSupport {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper mapper;

    @Autowired
    protected PasswordService passwords;

    @Autowired
    protected PlatformTransactionManager txm;

    @Autowired
    protected List<com.inventoryerp.kernel.seed.SeedContributor> seedContributors;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    protected RequestMappingHandlerMapping mappings;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected String demoOrg;
    protected String otherOrg;
    protected String otherOrgCode;
    protected String adminToken;
    protected String otherToken;

    public record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    @BeforeAll
    void baseFixtures() {
        synchronized (ApiTestSupport.class) {
            if (jdbc.sql("SELECT count(*) FROM \"Organization\" WHERE code = 'DEMO'").query(Long.class).single() == 0) {
                SeedRunnerAccess.seed(jdbc, passwords, txm, seedContributors);
            }
            demoOrg = jdbc.sql("SELECT id FROM \"Organization\" WHERE code = 'DEMO'").query(String.class).single();
            // A suite that seeded DEMO without the module contributors (e.g. KernelIT) left them out.
            if (jdbc.sql("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?").param(demoOrg)
                    .query(Long.class).single() == 0) {
                new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(
                        st -> seedContributors.stream()
                                .sorted(java.util.Comparator.comparingInt(com.inventoryerp.kernel.seed.SeedContributor::order))
                                .forEach(c -> c.seed(demoOrg)));
            }
        }
        otherOrgCode = "OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        otherOrg = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"updatedAt\") VALUES (?, ?, 'Other Org', now())")
                .params(otherOrg, otherOrgCode).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, 'Administrator', true, ?, now())")
                .params(role, otherOrg, Permissions.ALL.toArray(String[]::new)).update();
        String otherEmail = "boss-" + UUID.randomUUID().toString().substring(0, 8) + "@other.test";
        createUser(otherOrg, otherEmail, "Other@12345", role);
        adminToken = login("DEMO", "admin@demo.test", "Admin@123");
        otherToken = login(otherOrgCode, otherEmail, "Other@12345");
    }

    protected String createUser(String org, String email, String password, String roleId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"User\" (id, \"organizationId\", email, \"passwordHash\", \"firstName\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'T', now())")
                .params(id, org, email, passwords.hash(password)).update();
        if (roleId != null) {
            jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(roleId, id).update();
        }
        return id;
    }

    protected Res call(String method, String path, Object body, Map<String, String> headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(
                        body instanceof String s ? s : mapper.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isEmpty() ? null : mapper.readTree(r.body());
            return new Res(r.statusCode(), json, r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res call(String method, String path, Object body, String token) {
        Map<String, String> h = new LinkedHashMap<>();
        if (token != null) {
            h.put("Authorization", "Bearer " + token);
        }
        return call(method, path, body, h);
    }

    /** Admin call. */
    protected Res as(String method, String path, Object body) {
        return call(method, path, body, adminToken);
    }

    /** Admin call that must answer {@code status}. */
    protected JsonNode ok(int status, String method, String path, Object body) {
        Res r = as(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    protected String login(String org, String email, String password) {
        Res r = call("POST", "/auth/login", Map.of("organizationCode", org, "email", email, "password", password),
                (String) null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("accessToken").asString();
    }

    /** A DEMO user with no permissions at all. */
    protected String weakToken() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Nobody-" + role, new String[0]).update();
        String email = "nobody-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Nobody@12345", role);
        return login("DEMO", email, "Nobody@12345");
    }

    /**
     * Every route of controllers in {@code packagePrefix}: 401 without a token (unless public) and 403 with
     * the Nest message without its permission. Returns the number of checked routes.
     */
    protected int assertAuthorizationMatrix(String packagePrefix) {
        String weak = weakToken();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!hm.getBeanType().getPackageName().startsWith(packagePrefix)) {
                continue;
            }
            boolean isPublic = AnnotatedElementUtils.hasAnnotation(hm.getMethod(), Public.class)
                    || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), Public.class);
            RequirePermissions perms = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequirePermissions.class);
            if (perms == null) {
                perms = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequirePermissions.class);
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replace("/api/v1", "").replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                for (var m : entry.getKey().getMethodsCondition().getMethods()) {
                    checked++;
                    Object body = Set.of("POST", "PUT", "PATCH").contains(m.name()) ? Map.of() : null;
                    if (!isPublic) {
                        Res anon = call(m.name(), path, body, (String) null);
                        if (anon.status() != 401) {
                            failures.add(m + " " + pattern + " anon → " + anon.status());
                        }
                    }
                    if (perms != null) {
                        Res denied = call(m.name(), path, body, weak);
                        String expected = "Missing required permission(s): " + String.join(", ", perms.value());
                        if (denied.status() != 403 || !expected.equals(denied.body().get("message").asString())) {
                            failures.add(m + " " + pattern + " weak → " + denied.status() + " " + denied.raw().body());
                        }
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
        return checked;
    }
}
