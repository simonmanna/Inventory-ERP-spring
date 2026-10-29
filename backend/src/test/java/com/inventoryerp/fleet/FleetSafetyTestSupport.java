package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;

/** Shared fixtures of the fleet 1.7 / 1.8 integration tests (vehicles, money calls, GL checks, route matrix). */
abstract class FleetSafetyTestSupport extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String adminId;
    String tipperClass;
    String trailerClass;
    int plates;

    @BeforeAll
    void safetyFixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        tipperClass = category("TIPPER");
        trailerClass = category("TRAILER");
    }

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    String plate() {
        return "UAS " + sfx + "-" + (++plates);
    }

    /** A 6x4 tipper (odometer) with optional extra fields. */
    JsonNode vehicle(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", tipperClass);
        body.put("registrationPlate", plate());
        body.put("make", "Sinotruk");
        body.put("axleConfig", "6x4");
        body.putAll(extra);
        return ok(201, "POST", "/fleet/vehicles", body);
    }

    String costCenter() {
        return ok(201, "POST", "/cost-centers", Map.of("code", "CC-" + sfx + "-" + (++plates), "name",
                "Fleet unit " + sfx + " " + plates)).get("id").asString();
    }

    String employee(String first, String last) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(id, demoOrg, "DRV-" + sfx + "-" + (++plates),
                first, last).update();
        return id;
    }

    Map<String, String> keyed(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** Admin call of a money route with a fresh Idempotency-Key. */
    Res money(String method, String path, Object body) {
        return call(method, path, body, keyed(adminToken));
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    /** Debits = credits on a journal entry; returns {debit, credit}. */
    BigDecimal[] assertBalanced(String entryId) {
        Map<String, Object> t = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        BigDecimal d = (BigDecimal) t.get("d");
        BigDecimal c = (BigDecimal) t.get("c");
        assertThat(d).as("entry " + entryId + " balanced").isEqualByComparingTo(c);
        assertThat(d.signum()).isPositive();
        return new BigDecimal[] {d, c};
    }

    /** Drains the outbox (subscribers run) a few times. */
    void drainOutbox() {
        for (int i = 0; i < 5; i++) {
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            outbox.tick();
        }
    }

    /**
     * 401 without a token and 403 (exact message) without the permission on every route of the given controllers;
     * returns the number of checked routes.
     */
    int assertRouteMatrix(Set<Class<?>> controllers) {
        String weak = weakToken();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!controllers.contains(hm.getBeanType())) {
                continue;
            }
            RequirePermissions perms = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(),
                    RequirePermissions.class);
            assertThat(perms).as(hm.getMethod().toString()).isNotNull();
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replace("/api/v1", "").replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                for (var m : entry.getKey().getMethodsCondition().getMethods()) {
                    checked++;
                    Object body = Set.of("POST", "PUT", "PATCH").contains(m.name()) ? Map.of() : null;
                    Res anon = call(m.name(), path, body, (String) null);
                    if (anon.status() != 401) {
                        failures.add(m + " " + pattern + " anon → " + anon.status());
                    }
                    Res denied = call(m.name(), path, body, weak);
                    String expected = "Missing required permission(s): " + String.join(", ", perms.value());
                    if (denied.status() != 403 || !expected.equals(denied.body().get("message").asString())) {
                        failures.add(m + " " + pattern + " weak → " + denied.status() + " " + denied.raw().body());
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
        return checked;
    }

    /** A user of DEMO holding exactly {@code permissions}. */
    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Fleet safety " + role, permissions)
                .update();
        String email = "fleet-safety-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Safety@12345", role);
        return login("DEMO", email, "Safety@12345");
    }
}
