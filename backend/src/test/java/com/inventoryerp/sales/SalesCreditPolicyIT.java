package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-8: a 20M order on 10M available: flag, block, request, approve or reject, who and why recorded, cash bypass. */
class SalesCreditPolicyIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String item;
    String clerkToken;

    @BeforeAll
    void fixtures() {
        item = ok(201, "POST", "/products", Map.of("code", "G8P-" + sfx, "name", "SG8 item " + sfx, "productType", "service", "salesPrice", 1000000))
                .get("id").asString();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "SG8 " + role, new String[] {"sales:order:read", "sales:order:read_all", "sales:order:write",
                    "sales:order:confirm", "credit:read"}).update();
        String email = "sg8-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Clerk@12345", role);
        clerkToken = login("DEMO", email, "Clerk@12345");
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", Map.of("overLimitPolicy", "flag", "cashBypassesCredit", true));
        ok(200, "PUT", "/credit/policy", Map.of("enforcePeriodAndReview", false));
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"creditLimit\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, 10000000, now())").params(id, demoOrg, "G8C-" + UUID.randomUUID(), "SG8 cust " + UUID.randomUUID()).update();
        return id;
    }

    JsonNode order(String partner, Map<String, Object> extra) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("partnerId", partner, "lines", List.of(Map.of("productId", item, "quantity", 20,
                "unitPrice", 1000000))));
        b.putAll(extra);
        return call("POST", "/sales/orders", b, clerkToken).body();
    }

    Res confirm(String id) {
        return call("POST", "/sales/orders/" + id + "/confirm", null, Map.of("Authorization", "Bearer " + clerkToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("flag: 20M on a 10M limit confirms on hold, as before")
    void flag() {
        JsonNode c = confirm(order(customer(), Map.of()).get("id").asString()).body();
        assertThat(c.get("status").asString()).isEqualTo("confirmed");
        assertThat(c.get("onHold").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("block: refused until a request is approved, then the order confirms; who and why are recorded")
    void blockThenApprove() {
        ok(200, "PUT", "/sales/settings", Map.of("overLimitPolicy", "block"));
        String id = order(customer(), Map.of()).get("id").asString();
        Res r = confirm(id);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("exceeds the customer's credit");
        assertThat(call("POST", "/sales/orders/" + id + "/credit-request", Map.of(), clerkToken).status()).as("reason needed").isEqualTo(400);
        Res req = call("POST", "/sales/orders/" + id + "/credit-request", Map.of("reason", "Project advance coming"), clerkToken);
        assertThat(req.status()).isEqualTo(201);
        String rid = req.body().get("id").asString();
        assertThat(req.body().get("limitAtRequest").asDouble()).isEqualTo(10000000);
        assertThat(call("POST", "/sales/credit-requests/" + rid + "/approve", Map.of("comment", "self"), clerkToken).status()).as("needs override")
                .isEqualTo(403);
        JsonNode done = ok(200, "POST", "/sales/credit-requests/" + rid + "/approve", Map.of("comment", "Guaranteed by director"));
        assertThat(done.get("status").asString()).isEqualTo("approved");
        assertThat(done.get("decisionComment").asString()).isEqualTo("Guaranteed by director");
        assertThat(done.get("decidedById").isNull()).isFalse();
        JsonNode o = ok(200, "GET", "/sales/orders/" + id, null);
        assertThat(o.get("status").asString()).isEqualTo("confirmed");
        assertThat(o.get("onHold").asBoolean()).isFalse();
        assertThat(done.get("exposureAtDecision").asDouble()).as("recalculated: the confirmed order now counts").isEqualTo(20000000);
    }

    @Test
    @DisplayName("approve policy: confirming opens a request and waits; rejecting leaves the order a draft with the reason")
    void approvePolicyRejected() {
        ok(200, "PUT", "/sales/settings", Map.of("overLimitPolicy", "approve"));
        String id = order(customer(), Map.of()).get("id").asString();
        JsonNode waiting = confirm(id).body();
        assertThat(waiting.get("status").asString()).isEqualTo("draft");
        String rid = waiting.get("creditRequest").get("id").asString();
        assertThat(as("POST", "/sales/credit-requests/" + rid + "/reject", Map.of()).status()).as("why needed").isEqualTo(400);
        JsonNode rejected = ok(200, "POST", "/sales/credit-requests/" + rid + "/reject", Map.of("comment", "Too exposed"));
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(ok(200, "GET", "/sales/orders/" + id, null).get("status").asString()).isEqualTo("draft");
        assertThat(as("POST", "/sales/credit-requests/" + rid + "/approve", Map.of("comment", "again")).status()).as("once").isEqualTo(400);
    }

    @Test
    @DisplayName("a held order is released when its request is approved")
    void heldThenReleased() {
        String id = order(customer(), Map.of()).get("id").asString();
        assertThat(confirm(id).body().get("onHold").asBoolean()).isTrue();
        String rid = call("POST", "/sales/orders/" + id + "/credit-request", Map.of("reason", "Release please"), clerkToken).body().get("id").asString();
        ok(200, "POST", "/sales/credit-requests/" + rid + "/approve", Map.of("comment", "ok"));
        assertThat(ok(200, "GET", "/sales/orders/" + id, null).get("onHold").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("cash orders skip the check and do not add exposure; the setting can turn that off")
    void cashBypass() {
        ok(200, "PUT", "/sales/settings", Map.of("overLimitPolicy", "block"));
        String c = customer();
        String id = order(c, Map.of("paymentMode", "cash")).get("id").asString();
        JsonNode done = confirm(id).body();
        assertThat(done.get("status").asString()).isEqualTo("confirmed");
        assertThat(done.get("onHold").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/credit/partners/" + c + "/exposure", null).get("exposure").asDouble()).isZero();
        ok(200, "PUT", "/sales/settings", Map.of("cashBypassesCredit", false));
        assertThat(confirm(order(c, Map.of("paymentMode", "cash")).get("id").asString()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a passed review date holds the order only when the policy enforces it")
    void reviewDate() {
        String c = customer();
        jdbc.sql("UPDATE \"Partner\" SET \"creditReviewDate\" = ? WHERE id = ?").params(java.sql.Date.valueOf(LocalDate.now().minusDays(1)), c).update();
        Map<String, Object> small = Map.of("lines", List.of(Map.of("productId", item, "quantity", 1, "unitPrice", 1000000)));
        assertThat(confirm(order(c, small).get("id").asString()).body().get("onHold").asBoolean()).isFalse();
        ok(200, "PUT", "/credit/policy", Map.of("enforcePeriodAndReview", true));
        JsonNode held = confirm(order(c, small).get("id").asString()).body();
        assertThat(held.get("onHold").asBoolean()).as(held.toString() + " policy " + ok(200, "GET", "/credit/policy", null)).isTrue();
        assertThat(held.get("holdReason").asString()).contains("review date");
    }

    @Test
    @DisplayName("credit routes need their permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.sales.web.SalesCreditRequestController.class)).isEqualTo(5);
    }
}
