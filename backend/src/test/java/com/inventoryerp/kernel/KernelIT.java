package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.TestcontainersConfiguration;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.DetachedWrites;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import com.inventoryerp.kernel.recurring.RecurringService;
import com.inventoryerp.kernel.security.JwtTokenService;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.seed.SeedRunnerAccess;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kernel integration suite on Testcontainers Postgres: auth (lockout, refresh rotation, MFA), POS session
 * revocation, idempotency (HTTP + business outcome), authorization matrix over every kernel route,
 * tenant isolation, audit atomicity, sequences, recurring tick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KernelIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PasswordService passwords;

    @Autowired
    JwtTokenService jwt;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    AuditService audit;

    @Autowired
    SequenceService sequences;

    @Autowired
    RecurringService recurring;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    DetachedWrites detached;

    @Autowired
    EventBus eventBus;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    final HttpClient http = HttpClient.newHttpClient();
    String demoOrg;
    String otherOrg;
    String otherOrgCode;
    String adminToken;

    record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    @BeforeAll
    void fixtures() {
        if (jdbc.sql("SELECT count(*) FROM \"Organization\" WHERE code = 'DEMO'").query(Long.class).single() == 0) {
            SeedRunnerAccess.seed(jdbc, passwords, txm);
        }
        demoOrg = jdbc.sql("SELECT id FROM \"Organization\" WHERE code = 'DEMO'").query(String.class).single();
        otherOrgCode = "OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        otherOrg = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"updatedAt\") VALUES (?, ?, 'Other Org', now())")
                .params(otherOrg, otherOrgCode).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES (?, ?, 'Administrator', true, ?, now())")
                .params(role, otherOrg, com.inventoryerp.kernel.security.Permissions.ALL.toArray(String[]::new)).update();
        String user = createUser(otherOrg, "boss@other.test", "Other@12345", role);
        assertThat(user).isNotNull();
        adminToken = login("DEMO", "admin@demo.test", "Admin@123");
    }

    // ---------------------------------------------------------------- helpers

    String createUser(String org, String email, String password, String roleId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"User\" (id, \"organizationId\", email, \"passwordHash\", \"firstName\", \"updatedAt\") VALUES (?, ?, ?, ?, 'T', now())")
                .params(id, org, email, passwords.hash(password)).update();
        if (roleId != null) {
            jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(roleId, id).update();
        }
        return id;
    }

    Res call(String method, String path, Object body, Map<String, String> headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : mapper.writeValueAsString(body)));
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

    Res call(String method, String path, Object body, String token) {
        Map<String, String> h = new LinkedHashMap<>();
        if (token != null) {
            h.put("Authorization", "Bearer " + token);
        }
        return call(method, path, body, h);
    }

    String login(String org, String email, String password) {
        Res r = call("POST", "/auth/login", Map.of("organizationCode", org, "email", email, "password", password), (String) null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("accessToken").asString();
    }

    // ---------------------------------------------------------------- auth

    @Test
    @DisplayName("login: Nest shape, 200 (@Idempotent), bad credentials and validation errors")
    void login() {
        Res ok = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", "admin@demo.test", "password", "Admin@123"), (String) null);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().has("accessToken")).isTrue();
        assertThat(ok.body().get("refreshToken").asString()).hasSize(64);
        assertThat(ok.body().at("/user/roles/0").asString()).isEqualTo("Administrator");
        assertThat(ok.body().at("/organization/code").asString()).isEqualTo("DEMO");
        // The full catalog; once DmsRegistrySeeder has run, system roles also hold the per-type DMS keys
        // (Nest wireDmsRoleKeys), so the exact count depends on boot / seed order.
        java.util.List<String> granted = new java.util.ArrayList<>();
        ok.body().get("permissions").forEach(n -> granted.add(n.asString()));
        assertThat(granted).containsAll(com.inventoryerp.kernel.security.Permissions.ALL);

        Res bad = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", "admin@demo.test", "password", "nope"), (String) null);
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().get("message").asString()).isEqualTo("Invalid credentials");
        assertThat(bad.body().get("error").asString()).isEqualTo("Unauthorized");

        Res noOrg = call("POST", "/auth/login", Map.of("organizationCode", "NOPE", "email", "a@b.c", "password", "x"), (String) null);
        assertThat(noOrg.body().get("message").asString()).isEqualTo("Invalid credentials");

        Res invalid = call("POST", "/auth/login", Map.of("email", "not-an-email"), (String) null);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().get("message").isArray()).isTrue();
        assertThat(invalid.body().get("message").toString()).contains("email must be an email");

        Res unknown = call("POST", "/auth/login", "{\"organizationCode\":\"DEMO\",\"email\":\"a@b.c\",\"password\":\"x\",\"evil\":1}", Map.of());
        assertThat(unknown.body().get("message").get(0).asString()).isEqualTo("property evil should not exist");
    }

    @Test
    @DisplayName("10 failed logins lock the account for 15 minutes; attempts are recorded")
    void lockout() {
        String email = "lock-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Right@12345", null);
        for (int i = 0; i < 10; i++) {
            assertThat(call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", email, "password", "wrong"), (String) null)
                    .body().get("message").asString()).isEqualTo("Invalid credentials");
        }
        Res locked = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", email, "password", "Right@12345"), (String) null);
        assertThat(locked.status()).isEqualTo(401);
        assertThat(locked.body().get("message").asString()).isEqualTo("Account temporarily locked; try again later");
        long attempts = jdbc.sql("SELECT count(*) FROM \"LoginAttempt\" WHERE email = ?").param(email).query(Long.class).single();
        assertThat(attempts).isGreaterThanOrEqualTo(12);
        // admin unlock
        String id = jdbc.sql("SELECT id FROM \"User\" WHERE email = ?").param(email).query(String.class).single();
        assertThat(call("POST", "/users/" + id + "/unlock", null, adminToken).status()).isEqualTo(200);
        assertThat(login("DEMO", email, "Right@12345")).isNotBlank();
    }

    @Test
    @DisplayName("refresh rotates the token; the replaced token is refused")
    void refreshRotation() {
        Res first = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", "admin@demo.test", "password", "Admin@123"), (String) null);
        String rt = first.body().get("refreshToken").asString();
        Res rotated = call("POST", "/auth/refresh", Map.of("refreshToken", rt), (String) null);
        assertThat(rotated.status()).isEqualTo(201);
        String rt2 = rotated.body().get("refreshToken").asString();
        assertThat(rt2).isNotEqualTo(rt);
        Res reuse = call("POST", "/auth/refresh", Map.of("refreshToken", rt), (String) null);
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.body().get("message").asString()).isEqualTo("Invalid or expired refresh token");
        assertThat(call("POST", "/auth/refresh", Map.of("refreshToken", rt2), (String) null).status()).isEqualTo(201);
        String replaced = jdbc.sql("SELECT \"replacedById\" FROM \"RefreshToken\" WHERE \"tokenHash\" = ?")
                .param(com.inventoryerp.kernel.auth.AuthenticationHashes.sha256(rt)).query(String.class).single();
        assertThat(replaced).isNotNull();
    }

    @Test
    @DisplayName("unauthenticated → 401 'Authentication required'; unknown route → 404 'Cannot GET'")
    void unauthenticated() {
        Res r = call("GET", "/auth/me", null, (String) null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().get("message").asString()).isEqualTo("Authentication required");
        Res nf = call("GET", "/does-not-exist", null, (String) null);
        assertThat(nf.status()).isEqualTo(404);
        assertThat(nf.body().get("message").asString()).isEqualTo("Cannot GET /api/v1/does-not-exist");
    }

    @Test
    @DisplayName("GET /metrics is public outside prod (no METRICS_TOKEN) and renders Prometheus text")
    void metrics() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(r.body()).contains("# TYPE erp_process_uptime_seconds gauge")
                .contains("erp_process_memory_bytes{type=\"heap_used\"}")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("MFA: enroll → verify → login requires TOTP → mfa-login issues tokens → disable")
    void mfa() throws Exception {
        String email = "mfa-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Mfa@123456", null);
        String token = login("DEMO", email, "Mfa@123456");
        Res enroll = call("POST", "/auth/mfa/enroll", null, token);
        assertThat(enroll.status()).isEqualTo(201);
        String secret = enroll.body().get("secret").asString();
        assertThat(enroll.body().get("qrDataUrl").asString()).startsWith("data:image/png;base64,");
        assertThat(call("POST", "/auth/mfa/verify", Map.of("code", "000000"), token).body().get("message").asString())
                .isIn("Invalid TOTP code; scan the QR and retry");
        Res verified = call("POST", "/auth/mfa/verify", Map.of("code", totp(secret)), token);
        assertThat(verified.status()).as(verified.raw().body()).isEqualTo(201);
        String stored = jdbc.sql("SELECT \"mfaSecret\" FROM \"User\" WHERE email = ?").param(email).query(String.class).single();
        assertThat(stored).isNotEqualTo(secret);

        Res step1 = call("POST", "/auth/login", Map.of("organizationCode", "DEMO", "email", email, "password", "Mfa@123456"), (String) null);
        assertThat(step1.body().get("requiresMfa").asBoolean()).isTrue();
        String mfaToken = step1.body().get("mfaToken").asString();
        Res step2 = call("POST", "/auth/mfa-login", Map.of("mfaToken", mfaToken, "code", totp(secret)), (String) null);
        assertThat(step2.status()).as(step2.raw().body()).isEqualTo(201);
        assertThat(step2.body().at("/user/mfaEnrolled").asBoolean()).isTrue();
        Res burned = call("POST", "/auth/mfa-login", Map.of("mfaToken", mfaToken, "code", totp(secret)), (String) null);
        assertThat(burned.body().get("message").asString()).isEqualTo("MFA token invalid or expired");
        assertThat(call("POST", "/auth/mfa/disable", Map.of("code", totp(secret)), step2.body().get("accessToken").asString())
                .status()).isEqualTo(201);
    }

    String totp(String secret) throws Exception {
        return new DefaultCodeGenerator().generate(secret, Instant.now().getEpochSecond() / 30);
    }

    @Test
    @DisplayName("change password revokes sessions; forgot password always answers ok")
    void passwords() {
        String email = "pw-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Old@1234567", null);
        String token = login("DEMO", email, "Old@1234567");
        Res wrong = call("POST", "/auth/change-password", Map.of("currentPassword", "x", "newPassword", "New@1234567"), token);
        assertThat(wrong.body().get("message").asString()).isEqualTo("Current password is incorrect");
        Res ok = call("POST", "/auth/change-password", Map.of("currentPassword", "Old@1234567", "newPassword", "New@1234567"), token);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(login("DEMO", email, "New@1234567")).isNotBlank();
        Res forgot = call("POST", "/auth/forgot-password", Map.of("email", "nobody@demo.test", "organizationCode", "DEMO"), (String) null);
        assertThat(forgot.status()).isEqualTo(201);
        assertThat(forgot.body().get("ok").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- POS session

    @Test
    @DisplayName("a revoked POS cashier gets 401 + X-Pos-Session: revoked, except on the PIN screens")
    void posRevocation() {
        String email = "cashier-" + UUID.randomUUID() + "@demo.test";
        String cashier = createUser(demoOrg, email, "Cash@123456", null);
        String posToken = jwt.signPos(cashier, demoOrg, email);
        Map<String, String> h = Map.of("Authorization", "Bearer " + adminToken, "X-Pos-User", posToken);
        Res asCashier = call("GET", "/auth/me", null, h);
        assertThat(asCashier.body().get("email").asString()).isEqualTo(email);
        jdbc.sql("UPDATE \"User\" SET \"isActive\" = false WHERE id = ?").param(cashier).update();
        String other = createUser(demoOrg, "c2-" + email, "Cash@123456", null);
        String posToken2 = jwt.signPos(other, demoOrg, "c2-" + email);
        jdbc.sql("UPDATE \"User\" SET \"deletedAt\" = now() WHERE id = ?").param(other).update();
        Map<String, String> h2 = Map.of("Authorization", "Bearer " + adminToken, "X-Pos-User", posToken2);
        Res revoked = call("GET", "/auth/me", null, h2);
        assertThat(revoked.status()).isEqualTo(401);
        assertThat(revoked.raw().headers().firstValue("X-Pos-Session")).contains("revoked");
        assertThat(revoked.body().get("message").asString()).isEqualTo("This cashier can no longer use the POS. Sign in with another PIN.");
        // PIN screen: falls back to the bearer identity (not 401).
        assertThat(call("GET", "/pos/auth/staff", null, h2).status()).isEqualTo(200);
        // POS token of another org is ignored.
        String foreign = jwt.signPos(cashier, otherOrg, email);
        Res ignored = call("GET", "/auth/me", null, Map.of("Authorization", "Bearer " + adminToken, "X-Pos-User", foreign));
        assertThat(ignored.body().get("email").asString()).isEqualTo("admin@demo.test");
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    @DisplayName("idempotency: required key, replay, body mismatch, cached definitive 4xx")
    void idempotencyHttp() {
        String path = "/test-kernel/pos/checkout";
        Res missing = call("POST", path, Map.of("n", 1, "total", 10), adminToken);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("message").asString())
                .isEqualTo("An Idempotency-Key header is required for this operation so a retry can never charge twice.");

        String key = UUID.randomUUID().toString();
        Map<String, String> h = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", key);
        int before = TestIdempotencyController.CALLS.get();
        Res first = call("POST", path, Map.of("n", 1, "total", 10), h);
        Res replay = call("POST", path, Map.of("n", 1, "total", 10), h);
        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(TestIdempotencyController.CALLS.get() - before).isEqualTo(1);
        Res mismatch = call("POST", path, Map.of("n", 2, "total", 10), h);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.body().get("message").asString()).isEqualTo("Idempotency-Key '" + key + "' was previously used with a different request body");

        Map<String, String> h2 = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString());
        Res fail = call("POST", path, Map.of("fail", true), h2);
        assertThat(fail.status()).isEqualTo(400);
        assertThat(fail.body().get("safeToRetry").asBoolean()).isTrue();
        Res failReplay = call("POST", path, Map.of("fail", true), h2);
        assertThat(failReplay.status()).isEqualTo(400);
        assertThat(failReplay.body().get("message").asString()).isEqualTo("Insufficient credit");
    }

    /** Port of {@code business-outcome.spec.ts} ("Financial operation recovery"). */
    @Test
    @DisplayName("returns committed money outcome when final response persistence fails; keeps uncertain refunds protected")
    void businessOutcome() {
        IdempotencyService flaky = new IdempotencyService(jdbc, mapper) {
            @Override
            public void complete(BusinessOutcome.Operation op, int statusCode, Object body) {
                throw new IllegalStateException("connection lost after business commit");
            }
        };
        TenantContext.run(TenantStore.user(demoOrg, "cashier"), () -> {
            String key = "sale-" + UUID.randomUUID();
            int[] runs = {0};
            var first = flaky.executeWithKey(key, "original-body", "POST", "/pos/checkout", null, () -> {
                runs[0]++;
                new TransactionTemplate(txm).executeWithoutResult(s -> BusinessOutcome.record(jdbc,
                        Map.of("invoiceId", "inv-1", "paymentIds", List.of("pay-1"), "total", 20), true));
                return new IdempotencyService.Result(false, 201, Map.of("invoiceId", "inv-1"));
            });
            assertThat(first.replayed()).isTrue();
            assertThat(((JsonNode) first.body()).get("paymentIds").get(0).asString()).isEqualTo("pay-1");
            assertThat(jdbc.sql("SELECT status FROM \"IdempotencyRecord\" WHERE key = ?").param(key).query(String.class).single())
                    .isEqualTo("business_completed");
            var retried = flaky.executeWithKey(key, "original-body", "POST", "/pos/checkout", null, () -> {
                runs[0]++;
                return null;
            });
            assertThat(retried.body()).isEqualTo(first.body());
            assertThat(runs[0]).isEqualTo(1);
            assertThatThrownBy(() -> flaky.executeWithKey(key, "edited-body", "POST", "/pos/checkout", null, () -> null))
                    .hasMessageContaining("different request body");

            String refund = "refund-" + UUID.randomUUID();
            assertThatThrownBy(() -> idempotency.executeWithKey(refund, "body", "POST", "/pos/invoices/inv-1/refund", null, () -> {
                throw new IllegalStateException("unknown commit result");
            })).hasMessageContaining("unknown commit");
            assertThat(jdbc.sql("SELECT status FROM \"IdempotencyRecord\" WHERE key = ?").param(refund).query(String.class).single())
                    .isEqualTo("pending");
            assertThatThrownBy(() -> idempotency.executeWithKey(refund, "body", "POST", "/pos/invoices/inv-1/refund", null, () -> null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                    .hasMessageContaining("needs recovery");

            String invalid = "invalid-" + UUID.randomUUID();
            assertThatThrownBy(() -> idempotency.executeWithKey(invalid, "body", "POST", "/pos/checkout", null, () -> {
                throw ApiException.badRequest("Insufficient credit");
            })).hasMessage("Insufficient credit");
            var cached = idempotency.executeWithKey(invalid, "body", "POST", "/pos/checkout", null, () -> null);
            assertThat(cached.statusCode()).isEqualTo(400);
            assertThat(((JsonNode) cached.body()).get("safeToRetry").asBoolean()).isTrue();
        });
    }

    // ---------------------------------------------------------------- authorization matrix

    @Test
    @DisplayName("authorization matrix: every kernel route → 401 without a token, 403 without its permission")
    void authorizationMatrix() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Nobody-" + role, new String[0]).update();
        String email = "nobody-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Nobody@12345", role);
        String weak = login("DEMO", email, "Nobody@12345");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!hm.getBeanType().getPackageName().startsWith("com.inventoryerp.kernel") || hm.getBeanType().getSimpleName().startsWith("Test")) {
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
        assertThat(checked).isGreaterThan(80);
        assertThat(failures).isEmpty();
    }

    // ---------------------------------------------------------------- tenancy

    @Test
    @DisplayName("tenant isolation: another organization never sees or touches DEMO rows")
    void tenantIsolation() {
        String other = login(otherOrgCode, "boss@other.test", "Other@12345");
        Res users = call("GET", "/users?pageSize=100", null, other);
        Set<String> emails = new HashSet<>();
        users.body().get("data").forEach(u -> emails.add(u.get("email").asString()));
        assertThat(emails).containsExactly("boss@other.test");
        String demoAdmin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        assertThat(call("GET", "/users/" + demoAdmin, null, other).status()).isEqualTo(404);
        assertThat(call("PATCH", "/users/" + demoAdmin, Map.of("firstName", "Hacked"), other).status()).isEqualTo(404);
        String demoRole = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Cashier'").param(demoOrg).query(String.class).single();
        assertThat(call("GET", "/roles/" + demoRole, null, other).status()).isEqualTo(404);
        assertThat(call("DELETE", "/roles/" + demoRole, null, other).status()).isEqualTo(404);
        Res roles = call("GET", "/roles", null, other);
        roles.body().forEach(r -> assertThat(r.get("organizationId").asString()).isEqualTo(otherOrg));
        Res audit = call("GET", "/audit-logs", null, other);
        audit.body().get("data").forEach(r -> assertThat(r.get("organizationId").asString()).isEqualTo(otherOrg));
        // role assignment across orgs is refused
        Res cross = call("POST", "/users", Map.of("email", "x-" + UUID.randomUUID() + "@o.test", "password", "Passw0rd!!x",
                "firstName", "X", "roleIds", List.of(demoRole)), other);
        assertThat(cross.status()).isEqualTo(400);
        assertThat(cross.body().get("message").asString()).startsWith("Unknown role id(s) for this organization:");
    }

    // ---------------------------------------------------------------- staff rules

    @Test
    @DisplayName("roles: duplicate name, unknown permission, delete of an assigned role; users: last administrator guard")
    void staffRules() {
        String name = "Role-" + UUID.randomUUID().toString().substring(0, 8);
        Res created = call("POST", "/roles", Map.of("name", name, "permissions", List.of("user:read")), adminToken);
        assertThat(created.status()).isEqualTo(201);
        assertThat(call("POST", "/roles", Map.of("name", name, "permissions", List.of()), adminToken).body().get("message").asString())
                .isEqualTo("Role \"" + name + "\" already exists");
        assertThat(call("POST", "/roles", Map.of("name", name + "x", "permissions", List.of("nope:x")), adminToken).body().get("message").asString())
                .isEqualTo("Unknown permission(s): nope:x. Use keys from the permission catalog.");
        String roleId = created.body().get("id").asString();
        Res user = call("POST", "/users", Map.of("email", "staff-" + UUID.randomUUID() + "@demo.test", "password", "Staff@123456",
                "firstName", "S", "roleIds", List.of(roleId)), adminToken);
        assertThat(user.status()).as(user.raw().body()).isEqualTo(201);
        assertThat(user.body().at("/roles/0/name").asString()).isEqualTo(name);
        Res del = call("DELETE", "/roles/" + roleId, null, adminToken);
        assertThat(del.status()).isEqualTo(409);
        assertThat(del.body().get("message").asString()).isEqualTo("Role \"" + name + "\" is assigned to 1 user(s); reassign them first");
        assertThat(call("GET", "/roles/" + roleId, null, adminToken).body().at("/_count/users").asInt()).isEqualTo(1);

        String other = login(otherOrgCode, "boss@other.test", "Other@12345");
        String boss = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'boss@other.test'").query(String.class).single();
        Res demote = call("PATCH", "/users/" + boss, Map.of("roleIds", List.of()), other);
        assertThat(demote.status()).isEqualTo(403);
        assertThat(demote.body().get("message").asString())
                .isEqualTo("Refusing to remove the last administrator. Promote another user to an admin role first.");
        assertThat(call("DELETE", "/users/" + boss, null, other).body().get("message").asString())
                .isEqualTo("You cannot delete your own account");
        Res pin = call("POST", "/users/" + boss + "/pin", Map.of("pin", "12"), other);
        assertThat(pin.body().get("message").toString()).contains("PIN must be 4–8 digits");
        Res pinOk = call("POST", "/users/" + boss + "/pin", Map.of("pin", "4321"), other);
        assertThat(pinOk.status()).isEqualTo(200);
        assertThat(pinOk.body().get("hasPin").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- audit / sequence / recurring

    /** Port of {@code audit.service.spec.ts}: a failing audit insert rolls back the business write. */
    @Test
    @DisplayName("recordInTx failure rolls back the business write; success commits both")
    void auditAtomicity() {
        String code = "AUD-" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.run(TenantStore.user(demoOrg, "test-user"), () -> {
            assertThatThrownBy(() -> new TransactionTemplate(txm).executeWithoutResult(s -> {
                jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, 'Roll Back', now())")
                        .params(UUID.randomUUID().toString(), demoOrg, code).update();
                audit.recordInTx(AuditService.AuditInput.of("Partner", "x", "not-an-action"));
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE code = ?").param(code).query(Long.class).single()).isZero();

            long before = jdbc.sql("SELECT count(*) FROM \"AuditLog\"").query(Long.class).single();
            new TransactionTemplate(txm).executeWithoutResult(s -> {
                jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, 'Persist', now())")
                        .params(UUID.randomUUID().toString(), demoOrg, code + "-OK").update();
                audit.recordInTx(AuditService.AuditInput.of("Partner", "x", "create"));
            });
            assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\"").query(Long.class).single()).isEqualTo(before + 1);
            assertThatThrownBy(() -> audit.recordInTx(AuditService.AuditInput.of("Partner", "x", "create")))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        });
    }

    /** Port of {@code sequence.service.spec.ts} (concurrent part). */
    @Test
    @DisplayName("returns 100 unique, monotonic codes under concurrent pressure")
    void sequences() throws Exception {
        String key = "stock_move_" + UUID.randomUUID().toString().substring(0, 6);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> TenantContext.run(TenantStore.organization(demoOrg), () -> sequences.next(key, "STK/", 6)));
        }
        List<String> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (var f : pool.invokeAll(tasks)) {
                codes.add(f.get());
            }
        }
        assertThat(new HashSet<>(codes)).hasSize(100);
        assertThat(codes).allMatch(c -> c.matches("^STK/\\d{6}$"));
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            int a = Integer.parseInt(sequences.next(key, "STK/", 6).substring(4));
            int b = Integer.parseInt(sequences.next(key, "STK/", 6).substring(4));
            assertThat(b).isGreaterThan(a);
            assertThat(sequences.next("invoice", "INV-", 6)).startsWith("INV-");
        });
    }

    @Test
    @DisplayName("a sequence first created in a rolled-back transaction is created again on the next call")
    void sequenceSurvivesRollbackOfItsCreator() {
        String key = "stock_move_" + UUID.randomUUID().toString().substring(0, 6);
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            assertThatThrownBy(() -> new TransactionTemplate(txm).executeWithoutResult(s -> {
                sequences.next(key, "STK/", 6);
                // Second call in the same transaction sees the uncommitted sequence; it must not be cached.
                sequences.next(key, "STK/", 6);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sequences.next(key, "STK/", 6)).isEqualTo("STK/000001");
            String next = new TransactionTemplate(txm).execute(s -> sequences.next(key, "STK/", 6));
            assertThat(next).isEqualTo("STK/000002");
        });
    }

    @Test
    @DisplayName("recurring tick generates a draft document and advances the schedule")
    void recurringTick() {
        String partner = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND code = 'CUST-001'").param(demoOrg)
                .query(String.class).single();
        Res created = call("POST", "/recurring", Map.of("name", "Rent", "documentType", "sales_invoice", "frequency", "monthly",
                "nextRunAt", "2026-01-31T00:00:00Z",
                "template", Map.of("partnerId", partner, "lines", List.of(Map.of("description", "Rent", "quantity", 2, "unitPrice", 50.5,
                        "discountPercent", 10)))), adminToken);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String id = created.body().get("id").asString();
        var result = recurring.tick(Instant.now());
        assertThat(result.get("errors")).as(jdbc.sql("SELECT coalesce(max(error),'') FROM \"RecurringDocumentRun\" WHERE \"recurringId\" = ?")
                .param(id).query(String.class).single()).isZero();
        assertThat(result.get("processed")).isGreaterThanOrEqualTo(1);
        var doc = jdbc.sql("""
                        SELECT d."totalAmount" FROM "RecurringDocumentRun" r JOIN "Document" d ON d.id = r."documentId"
                        WHERE r."recurringId" = ?""").param(id).query(java.math.BigDecimal.class).single();
        assertThat(doc).isEqualByComparingTo("90.9");
        assertThat(call("PATCH", "/recurring/" + id + "/pause", null, adminToken).body().get("status").asString()).isEqualTo("paused");
    }

    /** Fire-and-forget writes collected by {@link DetachedWrites#batch} land after it ends; one failure spares the rest. */
    @Test
    @DisplayName("DetachedWrites.batch defers outbox + audit writes and isolates a failing one")
    void detachedBatch() {
        String marker = "batch-" + UUID.randomUUID();
        String entityId = UUID.randomUUID().toString();
        String eventName = "test.batch." + marker;
        TenantContext.run(TenantStore.user(demoOrg, "test-user"), () -> {
            detached.batch(() -> {
                eventBus.publish(eventName, Map.of("n", 1));
                new TransactionTemplate(txm).executeWithoutResult(s -> eventBus.publish(eventName, Map.of("n", 2)));
                detached.submit(() -> {
                    try {
                        new TransactionTemplate(txm).executeWithoutResult(s -> jdbc.sql("SELECT no_such_column FROM"
                                + " \"Organization\"").query().listOfRows());
                    } catch (RuntimeException expected) {
                        // logged by real writers; the batch must still persist the others
                    }
                });
                audit.record(AuditService.AuditInput.of("Partner", entityId, "create"));
                assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = ?").param(eventName)
                        .query(Long.class).single()).isZero();
                return null;
            });
        });
        assertThat(detached.awaitIdle(java.time.Duration.ofSeconds(10))).isTrue();
        assertThat(jdbc.sql("SELECT payload->>'n' FROM \"EventOutbox\" WHERE \"eventName\" = ? ORDER BY payload->>'n'")
                .param(eventName).query(String.class).list()).containsExactly("1", "2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND \"organizationId\" = ?")
                .params(entityId, demoOrg).query(Long.class).single()).isEqualTo(1);
    }
}
