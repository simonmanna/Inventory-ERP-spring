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
    protected com.inventoryerp.kernel.common.DetachedWrites detachedWrites;

    @Autowired
    protected List<com.inventoryerp.kernel.seed.SeedContributor> seedContributors;

    @Autowired
    protected com.inventoryerp.kernel.events.OutboxWorker outboxWorker;

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

    /**
     * Ships every outbox row written so far, however many earlier suites left behind (a demo seed leaves hundreds).
     * The scheduled poller may hold some rows; wait for it instead of returning while they are claimed. Rows that
     * already failed once are left to the poller so a broken handler cannot hang the suite.
     */
    protected void drainOutboxFully() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            if (outboxWorker.tick() > 0) {
                continue;
            }
            long open = jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE status IN ('pending', 'claimed')"
                    + " AND attempts = 0").query(Long.class).single();
            if (open == 0) {
                detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

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
            // Fire-and-forget event/audit writes made inside a transaction run after it completes (Nest: un-awaited
            // publish/record); wait for them so "call, then read the outbox / audit log" stays deterministic.
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
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

    /** The {@code message} array of a 400, in order. */
    protected static List<String> validationMessages(Res r) {
        List<String> out = new ArrayList<>();
        r.body().get("message").forEach(m -> out.add(m.asString()));
        return out;
    }

    protected String login(String org, String email, String password) {
        Res r = call("POST", "/auth/login", Map.of("organizationCode", org, "email", email, "password", password),
                (String) null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("accessToken").asString();
    }

    /**
     * {@code uncountedAccounts} for every tracked tender method of DEMO, so a shift close does not depend on which
     * tracked methods other suites created in the shared org.
     */
    protected Map<String, Object> uncountedTenders() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : jdbc.sql("SELECT DISTINCT \"accountId\" FROM \"PosPaymentMethod\" WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL AND \"trackInShift\" = true AND \"accountId\" IS NOT NULL")
                .param(demoOrg).query(String.class).list()) {
            out.put(id, "Not counted in this test");
        }
        return out;
    }

    private String closingManagerId;

    /** Unique per suite: other suites give their managers short PINs, and a shared PIN is ambiguous. */
    private final String closingManagerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));

    /**
     * A shift-close body with {@link #uncountedTenders()} added. Marking a tender "not counted" needs a manager who
     * is not the cashier, so when there is any tracked tender a second DEMO administrator with a PIN approves it.
     */
    protected Map<String, Object> withUncountedTenders(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        Map<String, Object> uncounted = uncountedTenders();
        out.put("uncountedAccounts", uncounted);
        if (!uncounted.isEmpty()) {
            if (closingManagerId == null) {
                String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                        + " JOIN \"User\" u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1")
                        .query(String.class).single();
                String email = "closer-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
                closingManagerId = createUser(demoOrg, email, "Closer@12345", role);
                Res pin = call("POST", "/pos/override/pin", Map.of("pin", closingManagerPin),
                        login("DEMO", email, "Closer@12345"));
                assertThat(pin.status()).as(pin.raw().body()).isEqualTo(201);
            }
            out.put("approvedById", closingManagerId);
            out.put("managerPin", closingManagerPin);
        }
        return out;
    }

    /**
     * Pin {@code pos.stockLocationId} to DEMO's first active warehouse: with several warehouses (other suites add
     * some) and no setting, POS has no stock location at all.
     */
    protected String pinPosWarehouse() {
        String id = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY code ASC LIMIT 1").param(demoOrg)
                .query(String.class).single();
        Res r = as("PUT", "/settings/pos.stockLocationId", Map.of("value", id));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return id;
    }

    /** Test cleanup: force-close the POS shifts a suite left open on its registers (shared DEMO org). */
    protected void forceCloseSessions(java.util.Collection<String> registerIds) {
        for (String r : registerIds) {
            jdbc.sql("UPDATE \"CashSession\" SET status = 'closed', \"closedAt\" = now() WHERE \"cashRegisterId\" = ?"
                    + " AND status = 'open'").param(r).update();
        }
    }

    /**
     * Test isolation for the shared DEMO org: unbilled held orders left by earlier suites block every shift close
     * (org-wide floor rule), so a suite that closes a shift cancels them first.
     */
    protected void releaseHeldOrders() {
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelledAt\" = now() WHERE \"organizationId\" = ?"
                + " AND \"invoiceId\" IS NULL AND status::text IN ('draft', 'confirmed', 'in_progress')").param(demoOrg)
                .update();
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
    /**
     * Every GET route of controllers under {@code packagePrefix}, called as the DEMO admin with path variables set to
     * an unknown id; returns the routes that answered 5xx (or did not answer). Server-sent-event streams are skipped.
     */
    protected List<String> sweepGetRoutes(String packagePrefix) {
        List<String> failures = new ArrayList<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            boolean stream = org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class
                    .isAssignableFrom(hm.getMethod().getReturnType())
                    || entry.getKey().getProducesCondition().getProducibleMediaTypes().stream()
                            .anyMatch(t -> t.isCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM));
            if (!hm.getBeanType().getPackageName().startsWith(packagePrefix) || stream
                    || !entry.getKey().getMethodsCondition().getMethods()
                            .contains(org.springframework.web.bind.annotation.RequestMethod.GET)) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replace("/api/v1", "").replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v1" + path)).timeout(java.time.Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + adminToken).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() >= 500) {
                        failures.add("GET " + pattern + " → " + r.statusCode() + " " + r.body());
                    }
                } catch (Exception e) {
                    failures.add("GET " + pattern + " → " + e);
                }
            }
        }
        detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
        return failures;
    }

    protected int assertAuthorizationMatrix(String packagePrefix) {
        return assertAuthorizationMatrix(type -> type.getPackageName().startsWith(packagePrefix));
    }

    /** Same as {@link #assertAuthorizationMatrix(String)} for exactly these controller classes. */
    protected int assertAuthorizationMatrix(Class<?>... controllers) {
        Set<Class<?>> only = Set.of(controllers);
        return assertAuthorizationMatrix(only::contains);
    }

    private int assertAuthorizationMatrix(java.util.function.Predicate<Class<?>> controllerFilter) {
        String weak = weakToken();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!controllerFilter.test(hm.getBeanType())) {
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
