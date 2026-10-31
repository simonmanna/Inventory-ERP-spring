package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.PurchaseAgreementController;
import com.inventoryerp.procurement.web.RfqController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 4: RFQ from approved request lines, supplier quotations with revisions, comparison on
 * delivered cost and weighted score, split awards (justification when not the cheapest), orders from awards, and
 * blanket / rate agreements with locked releases.
 */
class SourcingIT extends ApiTestSupport {

    String warehouse;
    String checkerToken;
    String a;
    String b;
    String c;

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "SR-" + suffix(), "name", "Sourcing wh "
                + suffix(), "type", "warehouse")).get("id").asString();
        String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\" u"
                + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "sourcing-checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        a = supplier("Alpha Cement");
        b = supplier("Bravo Cement");
        c = supplier("Charlie Cement");
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "SC-" + suffix(), name + " " + suffix()).update();
        return id;
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode approvedRequest(List<Map<String, Object>> lines) {
        String id = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Block plant", "lines",
                lines)).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/submit", null);
        ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/approve", null);
        return ok(200, "GET", "/procurement/purchase-requests/" + id, null);
    }

    Map<String, Object> quote(String partner, List<Map<String, Object>> lines, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", partner, "currencyCode", "UGX", "lines", lines));
        body.putAll(extra);
        return body;
    }

    @Test
    @DisplayName("RFQ: request lines → 3 quotations (one revised) → comparison → justified split award → 2 orders")
    void rfqToOrders() {
        String cement = product("Cement");
        String steel = product("Rebar");
        JsonNode pr = approvedRequest(List.of(
                Map.of("productId", cement, "description", "Cement 42.5", "quantity", 100),
                Map.of("productId", steel, "description", "Rebar 12mm", "quantity", 20)));
        List<String> reqLines = List.of(pr.get("lines").get(0).get("id").asString(),
                pr.get("lines").get(1).get("id").asString());

        Res badWeights = as("POST", "/procurement/rfqs", Map.of("title", "x", "requestLineIds", reqLines, "weights",
                Map.of("price", 90)));
        assertThat(badWeights.body().get("message").asString()).isEqualTo("Evaluation weights must add up to 100");
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Cement and rebar Q4", "requestLineIds",
                reqLines, "supplierIds", List.of(a, b, c), "deadline", LocalDate.now().plusDays(3).toString()));
        String rfqId = rfq.get("id").asString();
        assertThat(rfq.get("rfqNumber").asString()).startsWith("RFQ-");
        assertThat(rfq.get("lines")).hasSize(2);
        assertThat(rfq.get("suppliers")).hasSize(3);
        // While on the RFQ the lines leave the workbench, and cannot be ordered directly.
        assertThat(ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + pr.get("id").asString(), null))
                .isEmpty();
        Res direct = call("POST", "/procurement/sourcing/orders", Map.of("partnerId", a, "warehouseId", warehouse,
                "lines", List.of(Map.of("requestLineId", reqLines.getFirst()))), key(adminToken));
        assertThat(direct.body().get("message").asString()).endsWith("is on an open RFQ — award the RFQ instead");

        Res early = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, List.of(), Map.of()));
        assertThat(early.status()).isEqualTo(400);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/send", null);
        String cementLine = rfq.get("lines").get(0).get("id").asString();
        String steelLine = rfq.get("lines").get(1).get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, List.of(
                Map.of("rfqLineId", cementLine, "unitPrice", 45000), Map.of("rfqLineId", steelLine, "unitPrice", 3000)),
                Map.of("leadTimeDays", 2, "paymentTermsDays", 0)));
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(b, List.of(
                Map.of("rfqLineId", cementLine, "unitPrice", 42000), Map.of("rfqLineId", steelLine, "unitPrice", 2900)),
                Map.of("leadTimeDays", 5, "paymentTermsDays", 30, "freightAmount", 200000)));
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(b, List.of(
                Map.of("rfqLineId", cementLine, "unitPrice", 41000), Map.of("rfqLineId", steelLine, "unitPrice", 2900)),
                Map.of("leadTimeDays", 5, "paymentTermsDays", 30, "freightAmount", 200000, "supplierReference", "Q-B2")));
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(c, List.of(
                Map.of("rfqLineId", cementLine, "unitPrice", 47000)), Map.of("leadTimeDays", 1)));
        Res stranger = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(supplier("Delta"), List.of(
                Map.of("rfqLineId", cementLine, "unitPrice", 1)), Map.of()));
        assertThat(stranger.body().get("message").asString()).isEqualTo("This supplier was not invited to the RFQ");

        JsonNode cmp = ok(200, "GET", "/procurement/rfqs/" + rfqId + "/comparison", null);
        assertThat(cmp.get("suppliers")).hasSize(3);
        JsonNode bravo = null;
        for (JsonNode s : cmp.get("suppliers")) {
            if (b.equals(s.get("partnerId").asString())) {
                bravo = s;
            }
        }
        assertThat(bravo.get("revision").asInt()).isEqualTo(2);
        // 100 × 41,000 + 20 × 2,900 + 200,000 freight = 4,358,000 delivered.
        assertThat(new BigDecimal(bravo.get("deliveredTotal").asString())).isEqualByComparingTo("4358000");
        assertThat(cmp.get("lines").get(0).get("bestPartnerId").asString()).isEqualTo(b);
        // Rebar: B's 2,900 carries ~139.5 of freight per unit (spread by value) → A's 3,000 is cheaper delivered.
        assertThat(cmp.get("lines").get(1).get("bestPartnerId").asString()).isEqualTo(a);
        assertThat(cmp.get("suppliers").findValuesAsString("partnerId")).contains(a, b, c);
        assertThat(cmp.get("recommendedPartnerId").isNull()).isFalse();

        Res noReason = as("POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(
                Map.of("rfqLineId", cementLine, "partnerId", a))));
        assertThat(noReason.body().get("message").asString())
                .isEqualTo("A justification is required when the lowest offer is not chosen (\"Cement 42.5\")");
        Res tooMuch = as("POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(
                Map.of("rfqLineId", cementLine, "partnerId", b, "quantity", 60),
                Map.of("rfqLineId", cementLine, "partnerId", a, "quantity", 60, "justification", "Faster"))));
        assertThat(tooMuch.body().get("message").asString()).isEqualTo("Awards for \"Cement 42.5\" exceed the RFQ quantity");
        JsonNode awarded = ok(200, "POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(
                Map.of("rfqLineId", cementLine, "partnerId", b, "quantity", 70),
                Map.of("rfqLineId", cementLine, "partnerId", a, "quantity", 30, "justification",
                        "2-day delivery for the first block run"),
                Map.of("rfqLineId", steelLine, "partnerId", a))));
        assertThat(awarded.get("status").asString()).isEqualTo("awarded");
        assertThat(awarded.get("awards")).hasSize(3);

        JsonNode created = call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                key(adminToken)).body();
        assertThat(created.get("orders")).hasSize(2);
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + pr.get("id").asString(), null).get("status")
                .asString()).isEqualTo("converted");
        JsonNode fromB = ok(200, "GET", "/procurement/purchase-orders/" + created.get("orders").findValuesAsString("id")
                .get(created.get("orders").findValuesAsString("partnerId").indexOf(b)) + "/lifecycle", null);
        assertThat(new BigDecimal(fromB.get("lines").get(0).get("quantity").asString())).isEqualByComparingTo("70");
        Res again = call("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse),
                key(adminToken));
        assertThat(again.body().get("message").asString()).isEqualTo("Every award already has a purchase order");
        // Quoted prices fed the price history.
        assertThat(ok(200, "GET", "/procurement/suppliers/price-history?productId=" + cement, null).get("history").size())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("agreements: activation by a second person, locked releases, exhaustion and return on short-close")
    void blanketAgreementReleases() throws Exception {
        String aggregate = product("Aggregate 20mm");
        JsonNode pa = ok(201, "POST", "/procurement/agreements", Map.of("partnerId", a, "type", "blanket", "title",
                "Aggregate H2", "validFrom", LocalDate.now().minusDays(1).toString(), "validTo",
                LocalDate.now().plusMonths(6).toString(), "currencyCode", "UGX", "lines", List.of(Map.of("productId",
                        aggregate, "description", "Aggregate 20mm", "unitPrice", 30000, "committedQuantity", 10000))));
        String id = pa.get("id").asString();
        String line = pa.get("lines").get(0).get("id").asString();
        Res self = as("POST", "/procurement/agreements/" + id + "/activate", null);
        assertThat(self.status()).isEqualTo(403);
        Res early = call("POST", "/procurement/agreements/" + id + "/releases", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("agreementLineId", line, "quantity", 1))), key(adminToken));
        assertThat(early.body().get("message").asString()).isEqualTo("Releases need an active agreement (status: draft)");
        assertThat(call("POST", "/procurement/agreements/" + id + "/activate", null, checkerToken).body().get("status")
                .asString()).isEqualTo("active");

        Res r1 = call("POST", "/procurement/agreements/" + id + "/releases", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("agreementLineId", line, "quantity", 4000))), key(adminToken));
        assertThat(r1.status()).as(r1.raw().body()).isEqualTo(200);
        assertThat(new BigDecimal(r1.body().get("lines").get(0).get("unitPrice").asString())).isEqualByComparingTo("30000");
        Res over = call("POST", "/procurement/agreements/" + id + "/releases", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("agreementLineId", line, "quantity", 7000))), key(adminToken));
        assertThat(over.body().get("message").asString()).startsWith("Only 6000 of \"Aggregate 20mm\" is left on PA-");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Res>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> call("POST", "/procurement/agreements/" + id + "/releases", Map.of(
                        "warehouseId", warehouse, "lines", List.of(Map.of("agreementLineId", line, "quantity", 6000))),
                        key(adminToken))));
            }
            int ok = 0;
            String winner = null;
            for (Future<Res> f : futures) {
                if (f.get().status() == 200) {
                    ok++;
                    winner = f.get().body().get("id").asString();
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(ok(200, "GET", "/procurement/agreements/" + id, null).get("status").asString()).isEqualTo("exhausted");
            // Short-closing the last release hands its undelivered 6000 back to the agreement.
            call("POST", "/procurement/purchase-orders/" + winner + "/short-close", Map.of("reason", "Quarry flooded"),
                    key(adminToken));
            JsonNode back = ok(200, "GET", "/procurement/agreements/" + id, null);
            assertThat(back.get("status").asString()).isEqualTo("active");
            assertThat(new BigDecimal(back.get("lines").get(0).get("remainingQuantity").asString()))
                    .isEqualByComparingTo("6000");
            assertThat(back.get("releases")).hasSize(2);
        } finally {
            pool.shutdown();
        }

        // Rate contract with a value cap.
        JsonNode rate = ok(201, "POST", "/procurement/agreements", Map.of("partnerId", b, "type", "rate", "validFrom",
                LocalDate.now().toString(), "validTo", LocalDate.now().plusMonths(1).toString(), "committedValue", 100000,
                "lines", List.of(Map.of("description", "Haulage per trip", "unitPrice", 40000))));
        call("POST", "/procurement/agreements/" + rate.get("id").asString() + "/activate", null, checkerToken);
        Res cap = call("POST", "/procurement/agreements/" + rate.get("id").asString() + "/releases", Map.of(
                "warehouseId", warehouse, "lines", List.of(Map.of("agreementLineId", rate.get("lines").get(0).get("id")
                        .asString(), "quantity", 3))), key(adminToken));
        assertThat(cap.body().get("message").asString()).startsWith("This release (120000) exceeds the 100000 left");
        Res noReason = as("POST", "/procurement/agreements/" + rate.get("id").asString() + "/terminate", Map.of());
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to terminate an agreement");
    }

    @Test
    @DisplayName("tenant isolation + authorization matrix for RFQs and agreements")
    void isolationAndAuthorization() {
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Isolated", "lines", List.of(Map.of(
                "description", "x", "quantity", 1))));
        assertThat(call("GET", "/procurement/rfqs/" + rfq.get("id").asString(), null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/rfqs", null, otherToken).body().findValuesAsString("id"))
                .doesNotContain(rfq.get("id").asString());
        assertThat(call("GET", "/procurement/agreements", null, otherToken).body()).isEmpty();
        assertThat(assertAuthorizationMatrix(RfqController.class, PurchaseAgreementController.class)).isEqualTo(21);
    }
}
