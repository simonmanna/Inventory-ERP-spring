package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.Application;
import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.sync.application.SyncBranch;
import com.inventoryerp.sync.application.SyncBranchRelay;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O7 end to end: this suite's server is HQ; a second application context on its own database runs as a
 * branch node ({@code erp.sync.mode=branch}) relaying to it. A till syncs to the branch only. Covers: master data
 * mirrored to the till, inline pass-through while HQ answers, store-and-forward while HQ is unreachable, a money op
 * HQ rejects blocking the shift close at the branch until resolved at HQ, a branch restored from an older backup
 * (HQ's relay watermark, no sequence reuse, no duplicate posting), a till revoked at HQ, and the boundaries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncBranchRelayIT extends ApiTestSupport {

    static final String BRANCH_DB = "erp_branch_it";

    @Autowired
    DataSource appDataSource;

    @Autowired
    Flyway flyway;

    ConfigurableApplicationContext branchCtx;
    SyncBranchRelay relay;
    SyncBranch branchCfg;
    JdbcClient branchDb;
    int branchPort;
    final HttpClient http = HttpClient.newHttpClient();

    String admin;
    String branchId;
    String branchDeviceId;
    String branchToken;
    String tillId;
    String tillToken;
    String register;
    String product;
    String session;
    String sale1Op;
    Map<String, Object> sale1;
    int seq;
    final List<String> devices = new ArrayList<>();

    @BeforeAll
    void startBranch() throws Exception {
        admin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        branchId = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? ORDER BY code LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(null);
        if (branchId == null) {
            branchId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, 'BR-IT',"
                    + " 'Relay branch', now())").params(branchId, demoOrg).update();
        }
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode b = ok(201, "POST", "/sync/devices/register", Map.of("name", "Branch node " + suffix, "platform",
                "branch", "branchId", branchId));
        branchDeviceId = b.get("id").asString();
        branchToken = b.get("deviceToken").asString();
        JsonNode t = ok(201, "POST", "/sync/devices/register", Map.of("name", "Branch till " + suffix, "platform",
                "android", "branchId", branchId));
        tillId = t.get("id").asString();
        tillToken = t.get("deviceToken").asString();
        devices.add(branchDeviceId);
        devices.add(tillId);
        register = ok(201, "POST", "/cash-registers", Map.of("code", "BRL-" + suffix, "name", "Branch till " + suffix))
                .get("id").asString();
        product = ok(201, "POST", "/products", Map.of("code", "BRL-" + suffix, "name", "Branch tea " + suffix,
                "productType", "service", "trackInventory", false, "salesPrice", 60, "costPrice", 10)).get("id")
                .asString();

        // The branch's own database on the same server, migrated by the branch app's Flyway.
        DataSource owner = flyway.getConfiguration().getDataSource();
        String ownerUrl;
        String ownerUser;
        try (Connection c = owner.getConnection(); Statement st = c.createStatement()) {
            ownerUrl = c.getMetaData().getURL();
            ownerUser = c.getMetaData().getUserName();
            st.execute("DROP DATABASE IF EXISTS " + BRANCH_DB + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + BRANCH_DB + " OWNER \"" + ownerUser + "\"");
        }
        String ownerPassword = firstNonNull(prop(owner, "getPassword"), System.getenv("FLYWAY_PASSWORD"),
                System.getenv("DB_PASSWORD"));
        String appUser = prop(appDataSource, "getUsername");
        String appPassword = prop(appDataSource, "getPassword");
        String branchUrl = withDatabase(ownerUrl, BRANCH_DB);
        branchCtx = new SpringApplicationBuilder(Application.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + branchUrl, "--spring.datasource.username=" + appUser,
                "--spring.datasource.password=" + appPassword,
                "--spring.flyway.url=" + branchUrl, "--spring.flyway.user=" + ownerUser,
                "--spring.flyway.password=" + ownerPassword,
                "--spring.flyway.placeholders.approle=" + appUser,
                "--spring.flyway.placeholders.apppassword=" + appPassword,
                "--erp.sync.mode=branch", "--erp.sync.server-id=branch-it",
                "--erp.sync.branch.hq-url=http://localhost:" + port + "/api/v1",
                "--erp.sync.branch.token=" + branchToken, "--erp.sync.branch.organization-id=" + demoOrg,
                "--erp.sync.branch.interval=PT1H", "--erp.sync.branch.initial-delay=PT1H",
                "--erp.sync.branch.pull-interval=PT0S", "--app.jobs.enabled=false");
        relay = branchCtx.getBean(SyncBranchRelay.class);
        branchCfg = branchCtx.getBean(SyncBranch.class);
        branchDb = branchCtx.getBean(JdbcClient.class);
        branchPort = Integer.parseInt(branchCtx.getEnvironment().getProperty("local.server.port"));

        jdbc.sql("UPDATE \"CashSession\" SET status = 'closed', \"closedAt\" = now() WHERE \"organizationId\" = ? AND"
                + " status = 'open'").param(demoOrg).update();
        releaseHeldOrders();
        relay.cycle();
    }

    @AfterAll
    void stopBranch() {
        for (String d : devices) {
            jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                    + " 'investigating')").param(d).update();
        }
        if (register != null) {
            forceCloseSessions(List.of(register));
        }
        if (branchCtx != null) {
            branchCtx.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    static String prop(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static String withDatabase(String jdbcUrl, String db) {
        int q = jdbcUrl.indexOf('?');
        String base = q < 0 ? jdbcUrl : jdbcUrl.substring(0, q);
        String params = q < 0 ? "" : jdbcUrl.substring(q);
        return base.substring(0, base.lastIndexOf('/') + 1) + db + params;
    }

    /** A request to the branch node as the till. */
    Res branch(String method, String path, Object body) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://localhost:" + branchPort + "/api/v1" + path))
                    .header("X-Device-Token", tillToken).header("X-Sync-Protocol", "2.0")
                    .header("Content-Type", "application/json");
            r.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank() ? null : mapper.readTree(res.body());
            return new Res(res.statusCode(), node, res);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> op(String opId, String type, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", admin);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode pushToBranch(List<Map<String, Object>> ops) {
        Res r = branch("POST", "/sync/push", Map.of("deviceId", tillId, "ops", ops));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results");
    }

    Map<String, Object> sale(String clientId, String partnerId, int qty) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("clientId", clientId);
        p.put("cashSessionId", session);
        if (partnerId != null) {
            p.put("partnerId", partnerId);
        }
        p.put("expectedTotal", 60 * qty);
        p.put("lines", List.of(Map.of("productId", product, "description", "Branch tea", "quantity", qty, "unitPrice",
                60)));
        p.put("tenders", List.of(Map.of("method", "cash", "amount", 60 * qty)));
        return p;
    }

    String outboxState(String opId) {
        return branchDb.sql("SELECT \"relayState\" FROM \"SyncRelayOutbox\" WHERE \"opId\" = ?").param(opId)
                .query(String.class).single();
    }

    JsonNode status() {
        Res r = branch("GET", "/sync/branch/status", null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @Order(1)
    @DisplayName("the till pulls HQ master data from the branch (no token rotation) and HQ answers its push inline")
    void mirrorAndPassThrough() {
        Res pull = branch("GET", "/sync/pull", null);
        assertThat(pull.status()).as(pull.raw().body()).isEqualTo(200);
        List<String> products = new ArrayList<>();
        pull.body().get("data").get("products").forEach(p -> products.add(p.get("id").asString()));
        assertThat(products).contains(product);
        assertThat(pull.body().has("deviceToken")).as("tokens are HQ's; the branch never rotates them").isFalse();
        assertThat(pull.body().get("serverId").asString()).isEqualTo("branch-it");
        assertThat(pull.body().get("relay").get("mode").asString()).isEqualTo("branch");
        assertThat(pull.body().get("currency").isObject()).as("HQ's currency, mirrored").isTrue();
        assertThat(pull.body().get("data").get("messages").size()).isZero();

        session = UUID.randomUUID().toString();
        sale1Op = UUID.randomUUID().toString();
        sale1 = sale(sale1Op, null, 1);
        JsonNode results = pushToBranch(List.of(
                op(session, "cash_session.open", Map.of("clientId", session, "cashRegisterId", register, "openingFloat", 0)),
                op(sale1Op, "sale.checkout", sale1)));
        assertThat(results.get(0).get("status").asString()).as(results.toString()).isEqualTo("applied");
        assertThat(results.get(1).get("status").asString()).as(results.toString()).isEqualTo("applied");
        String invoice = results.get(1).get("mapping").get("invoiceId").asString();
        assertThat(results.get(1).get("relayState").asString()).isEqualTo("hq_posted");
        assertThat(jdbc.sql("SELECT \"totalAmount\" FROM \"Invoice\" WHERE id = ?").param(invoice)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("60");
        assertThat(outboxState(sale1Op)).isEqualTo("hq_posted");
        assertThat(jdbc.sql("SELECT \"originChain\"::text FROM \"SyncAppliedOp\" WHERE \"opId\" = ?").param(sale1Op)
                .query(String.class).single()).contains("device:" + tillId, "server:branch-it",
                        "device:" + branchDeviceId);
    }

    @Test
    @Order(2)
    @DisplayName("HQ unreachable: the branch holds the ops (accepted_local), reports them pending, relays on reconnect")
    void storeAndForward() {
        branchCfg.retargetHq("http://127.0.0.1:9/api/v1");
        try {
            offline();
        } finally {
            branchCfg.retargetHq("http://localhost:" + port + "/api/v1");
        }
        relay.cycle();
        assertThat(status().get("pendingHqPosting").asLong()).isZero();
        assertThat(outboxState(sale2Op)).isEqualTo("hq_posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ?").param(custOp).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"cashSessionId\" = (SELECT id FROM \"CashSession\""
                + " WHERE \"cashRegisterId\" = ? AND status = 'open')").param(register).query(Long.class).single())
                .isEqualTo(2);
        assertThat(branch("GET", "/sync/branch/shift-report?cashSessionId=" + session, null).body().get("note")
                .asString()).isEqualTo("All operations posted at HQ");
    }

    String custOp;
    String sale2Op;

    void offline() {
        String cust = UUID.randomUUID().toString();
        String sale2 = UUID.randomUUID().toString();
        String move = UUID.randomUUID().toString();
        JsonNode results = pushToBranch(List.of(
                op(cust, "customer.upsert", Map.of("clientId", cust, "id", cust, "name", "Branch customer " + cust
                        .substring(0, 6), "phone", "+255700000002")),
                op(sale2, "sale.checkout", sale(sale2, cust, 2)),
                op(move, "cash_session.movement", Map.of("sessionId", session, "movementType", "pay_in", "amount", 10,
                        "reason", "Branch top-up", "counterpartAccountId", jdbc.sql("SELECT id FROM \"Account\" WHERE"
                                + " \"organizationId\" = ? AND code = '3100'").param(demoOrg).query(String.class).single()))));
        for (JsonNode r : results) {
            assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
            assertThat(r.get("relayState").asString()).isIn("accepted_local", "pending_hq");
            assertThat(r.has("mapping")).isFalse();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ?").param(cust).query(Long.class).single())
                .as("nothing reached HQ yet").isZero();
        JsonNode st = status();
        assertThat(st.get("pendingHqPosting").asLong()).isEqualTo(3);
        assertThat(st.get("lastError").isNull()).isFalse();
        JsonNode report = branch("GET", "/sync/branch/shift-report?cashSessionId=" + session, null).body();
        assertThat(report.get("expectedCash").asString()).isEqualTo("190");
        // The customer op belongs to no shift: the shift's own two are pending.
        assertThat(report.get("note").asString()).isEqualTo("Includes 2 operation(s) pending HQ posting");
        custOp = cust;
        sale2Op = sale2;
    }

    @Test
    @Order(3)
    @DisplayName("a money op HQ rejects blocks the shift close at the branch until it is resolved at HQ")
    void rejectedMoneyBlocksClose() {
        String bad = UUID.randomUUID().toString();
        JsonNode r = pushToBranch(List.of(op(bad, "cash_session.movement", Map.of("sessionId", session, "movementType",
                "pay_out", "amount", 5, "reason", "No account")))).get(0);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("failed");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(outboxState(bad)).isEqualTo("hq_rejected");

        Map<String, Object> closePayload = withUncountedTenders(Map.of("sessionId", session, "closingCounted", 190));
        JsonNode refused = pushToBranch(List.of(op(UUID.randomUUID().toString(), "cash_session.close", closePayload)))
                .get(0);
        assertThat(refused.get("code").asString()).as(refused.toString()).isEqualTo("SYNC_DEAD_LETTERS_OPEN");

        String dl = jdbc.sql("SELECT id FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(bad).query(String.class)
                .single();
        ok(201, "POST", "/sync/dead-letters/" + dl + "/resolve", Map.of("status", "discarded", "note",
                "Entered by mistake"));
        relay.feed();
        JsonNode closed = pushToBranch(List.of(op(UUID.randomUUID().toString(), "cash_session.close", closePayload)))
                .get(0);
        assertThat(closed.get("status").asString()).as(closed.toString()).isEqualTo("applied");
        Map<String, Object> shift = jdbc.sql("SELECT status::text AS status, \"closingExpected\" FROM \"CashSession\""
                + " WHERE id = ?").param(closed.get("mapping") != null && closed.get("mapping").get(session) != null
                        ? closed.get("mapping").get(session).asString()
                        : jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? ORDER BY \"openedAt\" DESC"
                                + " LIMIT 1").param(register).query(String.class).single()).query().singleRow();
        assertThat(shift.get("status")).isEqualTo("closed");
        assertThat((java.math.BigDecimal) shift.get("closingExpected")).isEqualByComparingTo("190");
    }

    @Test
    @Order(4)
    @DisplayName("a branch restored from an older backup: HQ's watermark is read first, no sequence reuse, no double posting")
    void restoredBranch() {
        long invoicesBefore = jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        // The backup predates every op: outbox, counters and cursors are back to empty.
        branchDb.sql("DELETE FROM \"SyncRelayOutbox\"").update();
        branchDb.sql("UPDATE \"SyncBranchState\" SET \"relaySeq\" = 0, \"hqSeqHighWater\" = 0, \"feedCursor\" = NULL")
                .update();
        relay.markRestored();

        String cust = UUID.randomUUID().toString();
        Map<String, Object> resent = new LinkedHashMap<>();
        resent.put("opId", sale1Op);
        resent.put("deviceSeq", 2);
        resent.put("type", "sale.checkout");
        resent.put("actorUserId", admin);
        resent.put("occurredAt", Instant.now().toString());
        resent.put("payload", sale1);
        JsonNode results = pushToBranch(List.of(resent, op(cust, "customer.upsert", Map.of("clientId", cust, "id", cust,
                "name", "After restore " + cust.substring(0, 6)))));
        assertThat(results.get(0).get("status").asString()).as(results.toString()).isIn("applied", "replayed");
        assertThat(results.get(0).get("mapping").get("invoiceId").isString()).as("HQ's original result").isTrue();
        assertThat(results.get(1).get("status").asString()).as(results.toString()).isEqualTo("applied");
        assertThat(results.get(1).get("relayState").asString()).isEqualTo("hq_posted");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).as("the re-relayed sale replayed at HQ").isEqualTo(invoicesBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE \"deviceId\" = ? AND \"failureCode\" ="
                + " 'SEQ_REUSED'").param(branchDeviceId).query(Long.class).single()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("a till revoked at HQ: the branch learns it from the feed, holds its ops, HQ dead-letters them")
    void revokedTill() {
        ok(201, "POST", "/sync/devices/" + tillId + "/revoke", Map.of());
        relay.feed();
        String opId = UUID.randomUUID().toString();
        JsonNode r = pushToBranch(List.of(op(opId, "customer.upsert", Map.of("clientId", opId, "id", opId, "name",
                "Revoked " + opId.substring(0, 6))))).get(0);
        assertThat(r.get("code").asString()).as(r.toString()).isEqualTo("DEVICE_REVOKED");
        assertThat(jdbc.sql("SELECT \"failureCode\" FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ? AND \"deviceId\" = ?")
                .params(opId, branchDeviceId).query(String.class).single()).isEqualTo("DEVICE_REVOKED");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ?").param(opId).query(Long.class).single())
                .isZero();
        assertThat(branch("GET", "/sync/pull", null).status()).isEqualTo(401);
    }

    @Test
    @Order(6)
    @DisplayName("boundaries: feed only for branch devices, branch bound to a branch, relayed ops name a till, 503 for back office")
    void boundaries() {
        JsonNode other = ok(201, "POST", "/sync/devices/register", Map.of("name", "Plain till " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        devices.add(other.get("id").asString());
        assertThat(call("GET", "/sync/relay/feed", null, Map.of("X-Device-Token", other.get("deviceToken").asString()))
                .status()).isEqualTo(403);
        assertThat(call("GET", "/sync/relay/feed", null, Map.of()).status()).isEqualTo(401);
        assertThat(as("POST", "/sync/devices/register", Map.of("name", "No branch", "platform", "branch")).status())
                .isEqualTo(400);
        assertThat(call("GET", "/sync/branch/status", null, Map.of("X-Device-Token", other.get("deviceToken")
                .asString())).status()).as("HQ is not a branch node").isEqualTo(404);

        String opId = UUID.randomUUID().toString();
        Map<String, Object> o = op(opId, "customer.upsert", Map.of("clientId", opId, "id", opId, "name", "Orphan"));
        o.put("deviceSeq", 900_000);
        Res r = call("POST", "/sync/push", Map.of("deviceId", branchDeviceId, "ops", List.of(o)), Map.of(
                "X-Device-Token", branchToken, "X-Sync-Protocol", "2.0"));
        assertThat(r.body().get("results").get(0).get("code").asString()).isEqualTo("ORIGIN_NOT_ALLOWED");

        Res backOffice = branch("GET", "/products", null);
        assertThat(backOffice.status()).isEqualTo(503);
    }
}
