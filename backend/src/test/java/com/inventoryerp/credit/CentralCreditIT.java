package com.inventoryerp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S022: one customer credit management for sales, orders, invoices and delivery orders. */
class CentralCreditIT extends ApiTestSupport {

    String service;
    final List<String> orders = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        String s = sfx();
        service = ok(201, "POST", "/products", Map.of("code", "CCR-" + s, "name", "Central credit service " + s,
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
    }

    @AfterAll
    void cancelLeftovers() {
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelledAt\" = now() WHERE id = ANY(?) AND \"invoiceId\""
                + " IS NULL AND status::text <> 'cancelled'").param(orders.toArray(String[]::new)).update();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String customer(int limit, boolean hold) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"creditLimit\","
                + " \"creditHold\", \"updatedAt\") VALUES (?, ?, ?, ?, true, ?, ?, now())")
                .params(id, demoOrg, "CCR-" + id.substring(0, 8), "Central customer " + id.substring(0, 8), limit, hold)
                .update();
        return id;
    }

    Map<String, Object> order(String customer, int qty) {
        return new HashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of("productId", service, "quantity",
                qty, "unitPrice", 100))));
    }

    JsonNode exposure(String customer) {
        return ok(200, "GET", "/credit/partners/" + customer + "/exposure", null);
    }

    @Test
    @DisplayName("one limit: set through the sales screen or the credit-control screen, every channel sees the same")
    void oneLimit() {
        String c = customer(0, false);
        ok(201, "POST", "/credit/limits", Map.of("partnerId", c, "amount", 800));
        assertThat(ok(200, "GET", "/credit-control/customers/" + c, null).get("creditLimit").decimalValue())
                .isEqualByComparingTo("800");
        assertThat(exposure(c).get("limitSource").asString()).isEqualTo("profile");

        ok(200, "PUT", "/credit-control/customers/" + c + "/policy", Map.of("creditLimit", 1200, "reason", "Review"));
        assertThat(exposure(c).get("limit").decimalValue()).isEqualByComparingTo("1200");
        // The org-wide sales limit row follows the profile; it is never a second source.
        assertThat(jdbc.sql("SELECT amount FROM \"CreditLimit\" WHERE \"partnerId\" = ? AND \"branchId\" IS NULL AND"
                + " \"currencyCode\" IS NULL").param(c).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("a branch sub-limit only lowers the profile limit")
    void subLimitOnlyLowers() {
        String c = customer(1000, false);
        String branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, 'Yard',"
                + " now())").params(branch, demoOrg, "CB-" + sfx()).update();
        String id = ok(201, "POST", "/credit/limits", Map.of("partnerId", c, "branchId", branch, "amount", 5000))
                .get("id").asString();
        JsonNode high = ok(200, "GET", "/credit/partners/" + c + "/exposure?branchId=" + branch, null);
        assertThat(high.get("limit").decimalValue()).isEqualByComparingTo("1000");
        ok(200, "PUT", "/credit/limits/" + id, Map.of("amount", 300));
        JsonNode low = ok(200, "GET", "/credit/partners/" + c + "/exposure?branchId=" + branch, null);
        assertThat(low.get("limit").decimalValue()).isEqualByComparingTo("300");
        assertThat(low.get("limitSource").asString()).isEqualTo("sub_limit");
        assertThat(exposure(c).get("limit").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("an order and an invoice of the same customer add up across channels")
    void crossChannelExposure() {
        String c = customer(1000, false);
        orders.add(ok(200, "POST", "/orders", order(c, 6)).get("id").asString());
        assertThat(exposure(c).get("components").get("undelivered").decimalValue()).isEqualByComparingTo("600");
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", service, "quantity", 6, "unitPrice", 100))));
        Res post = as("POST", "/invoices/" + inv.get("id").asString() + "/post", null);
        assertThat(post.status()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).isEqualTo("Credit limit exceeded: limit 1000, would become 1200.");
    }

    @Test
    @DisplayName("no double counting: the draft invoice being posted is not counted on top of itself")
    void noDoubleCounting() {
        String c = customer(1000, false);
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", service, "quantity", 7, "unitPrice", 100))));
        // The draft already counts (other = 700); posting adds 700 once, not twice.
        assertThat(exposure(c).get("components").get("other").decimalValue()).isEqualByComparingTo("700");
        ok(200, "POST", "/invoices/" + inv.get("id").asString() + "/post", null);
        JsonNode after = exposure(c);
        assertThat(after.get("components").get("other").decimalValue()).isEqualByComparingTo("0");
        assertThat(after.get("components").get("receivables").decimalValue()).isEqualByComparingTo("700");
        assertThat(after.get("exposure").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("reservation: two simultaneous orders cannot both use the last headroom")
    void reservation() throws Exception {
        String c = customer(1000, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Res>> tasks = List.of(() -> as("POST", "/orders", order(c, 6)), () -> as("POST", "/orders",
                    order(c, 6)));
            List<Integer> codes = new ArrayList<>();
            for (Future<Res> f : pool.invokeAll(tasks)) {
                Res r = f.get();
                codes.add(r.status());
                if (r.status() == 200) {
                    orders.add(r.body().get("id").asString());
                }
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("check points are policy switches; a disabled one passes and is recorded")
    void checkpointSwitch() {
        String c = customer(0, true);
        assertThat(as("POST", "/orders", order(c, 1)).status()).isEqualTo(400);
        JsonNode before = ok(200, "GET", "/credit/policy", null);
        List<String> original = new ArrayList<>();
        before.get("checkpoints").forEach(n -> original.add(n.asString()));
        List<String> without = new ArrayList<>(original);
        without.remove("pos_credit_sale");
        try {
            ok(200, "PUT", "/credit/policy", Map.of("checkpoints", without));
            JsonNode o = ok(200, "POST", "/orders", order(c, 1));
            orders.add(o.get("id").asString());
            assertThat(jdbc.sql("SELECT count(*) FROM \"CreditDecision\" WHERE \"partnerId\" = ? AND checkpoint ="
                    + " 'pos_credit_sale'").param(c).query(Long.class).single()).isPositive();
        } finally {
            ok(200, "PUT", "/credit/policy", Map.of("checkpoints", original));
        }
    }

    @Test
    @DisplayName("customer override grants are audited and work in every channel")
    void grantAudited() {
        String c = customer(0, true);
        JsonNode granted = ok(200, "POST", "/credit-control/customers/" + c + "/overrides", Map.of("coversHold", true,
                "reason", "Cheque cleared", "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()));
        String grant = granted.get("overrides").get(0).get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'PartnerCreditOverride' AND"
                + " \"entityId\" = ?").param(grant).query(Long.class).single()).isPositive();
        // The grantor cannot use their own grant; the central exposure shows it to everyone else.
        assertThat(exposure(c).get("grants").get("coversHold").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("reconciliation lists the central exposure next to the retired formula")
    void reconciliation() {
        String c = customer(1000, false);
        orders.add(ok(200, "POST", "/orders", order(c, 2)).get("id").asString());
        JsonNode rows = ok(200, "GET", "/credit/reconciliation", null);
        JsonNode mine = null;
        for (JsonNode r : rows) {
            if (c.equals(r.get("partnerId").asString())) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("exposure").decimalValue()).isEqualByComparingTo("200");
        assertThat(mine.get("legacyExposure").decimalValue()).isEqualByComparingTo("200");
        assertThat(mine.get("difference").decimalValue()).isEqualByComparingTo("0");
    }
}
