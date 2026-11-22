package com.inventoryerp.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 C1, O0 correction 1: durable duplicate protection for every op boundary. For single-transaction ops
 * (tax.upsert, customer.upsert, menuItem.upsert) and service ops that commit on their own (stock.in, sale.checkout):
 * first submission applies once, a repeat replays the original result, parallel submissions apply once, a failure
 * before commit leaves neither business rows nor a ledger row, and a crash after commit never runs the op again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncLedgerCoverageIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    String location;
    String managerPin;
    String serviceProduct;
    String registerId;
    String sessionId;
    int seq = 10_000;

    /** One op family under test: builds a fresh op and counts the business rows it wrote. */
    record Case(String name, Supplier<Map<String, Object>> payload, Function<Map<String, Object>, Long> rows) {}

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Coverage tablet", "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        location = pinPosWarehouse();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        serviceProduct = ok(201, "POST", "/products", Map.of("code", "COV-" + suffix, "name", "Coverage tea " + suffix,
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "COV-" + suffix, "name", "Coverage till " + suffix))
                .get("id").asString();
        JsonNode open = push(List.of(op("cash_session.open", Map.of("clientId", "s-" + suffix, "cashRegisterId",
                registerId, "openingFloat", 0)))).get(0);
        assertThat(open.get("status").asString()).as(open.toString()).isEqualTo("applied");
        sessionId = jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status = 'open'")
                .param(registerId).query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        SyncPushService.afterRunHook = null;
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
        forceCloseSessions(List.of(registerId));
    }

    Map<String, Object> op(String type, Map<String, Object> payload) {
        return op("cov-" + UUID.randomUUID(), type, payload);
    }

    Map<String, Object> op(String opId, String type, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", opId);
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(List<Map<String, Object>> ops) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", ops), Map.of("X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results");
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    String ledgerState(String opId) {
        return jdbc.sql("SELECT state FROM \"SyncAppliedOp\" WHERE \"opId\" = ?").param(opId).query(String.class)
                .optional().orElse(null);
    }

    List<Case> cases() {
        return List.of(
                new Case("tax.upsert", () -> Map.of("id", UUID.randomUUID().toString(), "name", "Cov VAT " + UUID
                        .randomUUID().toString().substring(0, 8), "rate", 3), p -> count("SELECT count(*) FROM \"Tax\""
                        + " WHERE id = ?", p.get("id"))),
                new Case("customer.upsert", () -> Map.of("id", UUID.randomUUID().toString(), "name", "Cov guest"),
                        p -> count("SELECT count(*) FROM \"Partner\" WHERE id = ?", p.get("id"))),
                new Case("menuItem.upsert", () -> {
                    String id = UUID.randomUUID().toString();
                    return Map.of("id", id, "name", "Cov dish", "code", "CD-" + id.substring(0, 8), "basePrice", 10,
                            "variants", List.of(Map.of("id", UUID.randomUUID().toString(), "name", "Large", "price", 12)));
                }, p -> count("SELECT count(*) FROM \"MenuItem\" WHERE id = ?", p.get("id"))
                        + count("SELECT count(*) FROM \"MenuItemVariant\" WHERE \"menuItemId\" = ?", p.get("id"))),
                new Case("stock.in", () -> {
                    String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
                    String product = ok(201, "POST", "/products", Map.of("code", "CW-" + suffix, "name", "Cov water "
                            + suffix, "productType", "stockable", "trackInventory", true, "costPrice", 40, "salesPrice",
                            100)).get("id").asString();
                    return Map.of("locationId", location, "approvedById", adminId, "approverPin", managerPin, "items",
                            List.of(Map.of("productId", product, "quantity", 5, "unitCost", 40)));
                }, p -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?",
                        ((Map<?, ?>) ((List<?>) p.get("items")).getFirst()).get("productId"))),
                new Case("sale.checkout", () -> Map.of("provisionalNumber", "COV-" + UUID.randomUUID(), "cashSessionId",
                        sessionId, "lines", List.of(Map.of("productId", serviceProduct, "description", "Tea", "quantity", 1,
                                "unitPrice", 100)), "tenders", List.of(Map.of("method", "cash", "amount", 100)),
                        "amountTendered", 100),
                        p -> count("SELECT count(*) FROM \"Invoice\" WHERE \"provisionalNumber\" = ?",
                                p.get("provisionalNumber"))));
    }

    @Test
    @DisplayName("first submission applies once; a repeat replays the original result (even without its idempotency"
            + " record)")
    void appliesOnceThenReplays() {
        for (Case c : cases()) {
            Map<String, Object> payload = c.payload().get();
            Map<String, Object> o = op(c.name(), payload);
            String opId = (String) o.get("opId");
            JsonNode first = push(List.of(o)).get(0);
            assertThat(first.get("status").asString()).as(c.name() + " " + first).isEqualTo("applied");
            long rows = c.rows().apply(payload);
            assertThat(rows).as(c.name()).isPositive();
            assertThat(ledgerState(opId)).as(c.name()).isEqualTo("applied");
            assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ? AND \"receivedAt\" IS NOT NULL",
                    opId)).isEqualTo(1);
            JsonNode again = push(List.of(o)).get(0);
            assertThat(again.get("status").asString()).as(c.name()).isEqualTo("replayed");
            jdbc.sql("DELETE FROM \"IdempotencyRecord\" WHERE key = ?").param(opId).update();
            JsonNode third = push(List.of(o)).get(0);
            assertThat(third.get("status").asString()).as(c.name()).isEqualTo("replayed");
            assertThat(third.get("mapping")).as(c.name()).isEqualTo(first.get("mapping")); // key order may differ
            assertThat(c.rows().apply(payload)).as(c.name() + " rows after replays").isEqualTo(rows);
        }
    }

    @Test
    @DisplayName("parallel submissions of one opId apply exactly once")
    void parallelAppliesOnce() throws Exception {
        for (Case c : cases()) {
            Map<String, Object> payload = c.payload().get();
            Map<String, Object> o = op(c.name(), payload);
            int n = 4;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return push(List.of(o)).get(0);
                }, pool));
            }
            go.countDown();
            int applied = 0;
            for (CompletableFuture<JsonNode> f : futures) {
                JsonNode r = f.get();
                String status = r.get("status").asString();
                if ("applied".equals(status)) {
                    applied++;
                } else if ("failed".equals(status)) {
                    assertThat(r.get("code").asString()).as(c.name() + " " + r).isEqualTo("OPERATION_PENDING");
                } else {
                    assertThat(status).isEqualTo("replayed");
                }
            }
            pool.shutdown();
            assertThat(applied).as(c.name()).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"opId\" = ?", o.get("opId"))).isEqualTo(1);
            long rows = c.rows().apply(payload);
            JsonNode after = push(List.of(o)).get(0);
            assertThat(after.get("status").asString()).as(c.name()).isEqualTo("replayed");
            assertThat(c.rows().apply(payload)).as(c.name()).isEqualTo(rows);
        }
    }

    @Test
    @DisplayName("a failure before commit leaves no business rows and no ledger row (single-transaction and service ops)")
    void failureBeforeCommitLeavesNothing() {
        // menuItem.upsert writes the item, then a variant whose price is not a number: before O0 correction 1 the
        // item stayed behind (auto-commit); now the whole op rolls back with its ledger row.
        String item = UUID.randomUUID().toString();
        Map<String, Object> bad = op("menuItem.upsert", Map.of("id", item, "name", "Half dish", "code",
                "HD-" + item.substring(0, 8), "basePrice", 10, "variants", List.of(Map.of("id",
                        UUID.randomUUID().toString(), "name", "Bad", "price", "not-a-number"))));
        JsonNode r = push(List.of(bad)).get(0);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("failed");
        assertThat(count("SELECT count(*) FROM \"MenuItem\" WHERE id = ?", item)).isZero();
        assertThat(ledgerState((String) bad.get("opId"))).isNull();
        assertThat(count("SELECT count(*) FROM \"IdempotencyRecord\" WHERE key = ?", bad.get("opId"))).isZero();

        // stock.in: a good line and an unknown product in one document — nothing is booked, no ledger row.
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String product = ok(201, "POST", "/products", Map.of("code", "CF-" + suffix, "name", "Cov fail " + suffix,
                "productType", "stockable", "trackInventory", true, "costPrice", 40, "salesPrice", 100)).get("id")
                .asString();
        Map<String, Object> badStock = op("stock.in", Map.of("locationId", location, "approvedById", adminId,
                "approverPin", managerPin, "items", List.of(Map.of("productId", product, "quantity", 5, "unitCost", 40),
                        Map.of("productId", UUID.randomUUID().toString(), "quantity", 1, "unitCost", 1))));
        JsonNode s = push(List.of(badStock)).get(0);
        assertThat(s.get("status").asString()).as(s.toString()).isEqualTo("failed");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?", product)).isZero();
        assertThat(ledgerState((String) badStock.get("opId"))).isNull();
    }

    @Test
    @DisplayName("a crash after commit never runs the op again: single-transaction ops replay the original result,"
            + " service ops are held as OPERATION_INCOMPLETE")
    void crashAfterCommit() {
        for (Case c : cases()) {
            Map<String, Object> payload = c.payload().get();
            Map<String, Object> o = op(c.name(), payload);
            String opId = (String) o.get("opId");
            // Expected outcome of the crashed attempt: single-transaction ops committed their result with the ledger
            // row; sale.checkout is recovered at once by the idempotency sale recovery (original result); other
            // service ops are left committed-but-incomplete for reconciliation.
            boolean single = SyncOpCatalog.of(c.name()).boundary() == SyncOpCatalog.Boundary.OP_TRANSACTION;
            boolean saleRecovery = "sale.checkout".equals(c.name());
            SyncPushService.afterRunHook = () -> {
                throw new IllegalStateException("simulated crash after commit, before the response");
            };
            JsonNode crashed;
            try {
                crashed = push(List.of(o)).get(0);
            } finally {
                SyncPushService.afterRunHook = null;
            }
            assertThat(crashed.get("status").asString()).as(c.name() + " " + crashed)
                    .isEqualTo(saleRecovery ? "replayed" : "failed");
            long rows = c.rows().apply(payload);
            assertThat(rows).as(c.name() + " committed").isPositive();
            assertThat(ledgerState(opId)).as(c.name()).isEqualTo(single || saleRecovery ? "applied" : "committed");

            JsonNode again = push(List.of(o)).get(0);
            if (single || saleRecovery) {
                assertThat(again.get("status").asString()).as(c.name() + " " + again).isEqualTo("replayed");
            } else {
                assertThat(again.get("code").asString()).as(c.name() + " " + again).isEqualTo("OPERATION_INCOMPLETE");
            }
            if (single) {
                assertThat(jdbc.sql("SELECT status FROM \"SyncOpDeadLetter\" WHERE \"opId\" = ?").param(opId)
                        .query(String.class).single()).isEqualTo("retried");
            }
            assertThat(c.rows().apply(payload)).as(c.name() + " never applied twice").isEqualTo(rows);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.inventoryerp.kernel.idempotency.IdempotencyService idempotency;

    @Test
    @DisplayName("key order does not matter; an idempotency record hashed the pre-O0 way still replays")
    void canonicalAndLegacyHash() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rate", 4);
        payload.put("name", "Order VAT " + UUID.randomUUID().toString().substring(0, 8));
        payload.put("id", UUID.randomUUID().toString());
        Map<String, Object> o = op("tax.upsert", payload);
        String opId = (String) o.get("opId");
        assertThat(push(List.of(o)).get(0).get("status").asString()).isEqualTo("applied");
        // Same op, keys in another order (as a jsonb round trip returns them): still the same op.
        Map<String, Object> reordered = new LinkedHashMap<>(o);
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("id", payload.get("id"));
        p2.put("name", payload.get("name"));
        p2.put("rate", 4);
        reordered.put("payload", p2);
        assertThat(push(List.of(reordered)).get(0).get("status").asString()).isEqualTo("replayed");
        // Pre-O0 state: no ledger row, an idempotency record carrying the as-sent hash.
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("type", "tax.upsert");
        hashed.put("payload", payload);
        String legacy = com.inventoryerp.kernel.tenancy.TenantContext.run(
                com.inventoryerp.kernel.tenancy.TenantContext.TenantStore.user(demoOrg, adminId),
                () -> idempotency.hashPayload(hashed));
        jdbc.sql("DELETE FROM \"SyncAppliedOp\" WHERE \"opId\" = ?").param(opId).update();
        jdbc.sql("UPDATE \"IdempotencyRecord\" SET \"requestHash\" = ? WHERE key = ?").params(legacy, opId).update();
        JsonNode again = push(List.of(o)).get(0);
        assertThat(again.get("status").asString()).as(again.toString()).isEqualTo("replayed");
        assertThat(count("SELECT count(*) FROM \"Tax\" WHERE id = ?", payload.get("id"))).isEqualTo(1);
    }

    @Test
    @DisplayName("every catalog type reaches its handler (none is refused as unsupported)")
    void catalogReachesHandlers() {
        for (String type : SyncOpCatalog.types()) {
            JsonNode r = push(List.of(op(type, new HashMap<>(Map.of("probe", true))))).get(0);
            assertThat(String.valueOf(r.get("error"))).as(type).doesNotContain("Unsupported sync op type");
        }
    }
}
