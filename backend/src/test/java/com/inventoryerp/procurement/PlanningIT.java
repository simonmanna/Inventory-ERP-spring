package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.procurement.web.PlanningController;
import com.inventoryerp.procurement.web.SupplierPortalController;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 7: replenishment suggestions and requests, supplier scorecards, price z-score, spend,
 * demand forecast, and the token-only supplier portal for RFQs and purchase orders.
 */
class PlanningIT extends ApiTestSupport {

    String warehouse;
    String supplier;

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PL-" + suffix(), "name", "Block yard "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = supplier("Lafarge");
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

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "PL-" + suffix(), "Cement " + suffix()).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String po(String partner, String productId, Object qty, Object price, String expected) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", partner, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitPrice", price))));
        if (expected != null) {
            body.put("expectedDeliveryDate", expected);
        }
        return ok(201, "POST", "/procurement/purchase-orders", body).get("id").asString();
    }

    void receive(String poId, String productId, Object qty) {
        Res r = call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    @Test
    @DisplayName("replenishment: min/max rule → suggestion rounded to the order multiple → draft request → covered")
    void replenishment() {
        String cement = product();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 5, now())").params(UUID.randomUUID().toString(), demoOrg, cement, warehouse).update();
        Res bad = as("PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId", warehouse,
                "minQuantity", 10, "maxQuantity", 5));
        assertThat(bad.body().get("message").asString()).isEqualTo("Maximum must not be below minimum");
        JsonNode rule = ok(200, "PUT", "/procurement/replenishment/rules", Map.of("productId", cement, "locationId",
                warehouse, "minQuantity", 10, "maxQuantity", 50, "orderMultiple", 10, "preferredSupplierId", supplier));
        String ruleId = rule.get("id").asString();

        JsonNode s = find(ok(200, "GET", "/procurement/replenishment/suggestions", null), ruleId);
        assertThat(s).isNotNull();
        assertThat(s.get("onHand").asString()).isEqualTo("5");
        assertThat(s.get("suggestedQuantity").asString()).isEqualTo("50");

        JsonNode pr = ok(200, "POST", "/procurement/replenishment/raise", Map.of("ruleIds", List.of(ruleId)));
        assertThat(pr.get("status").asString()).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT \"demandSource\" FROM \"PurchaseRequestLine\" WHERE \"purchaseRequestId\" = ?")
                .param(pr.get("id").asString()).query(String.class).single()).isEqualTo("reorder");
        // The open request now covers the gap: no further suggestion.
        assertThat(find(ok(200, "GET", "/procurement/replenishment/suggestions", null), ruleId)).isNull();
        JsonNode again = ok(200, "POST", "/procurement/replenishment/raise", Map.of("ruleIds", List.of(ruleId)));
        assertThat(again.get("raised").asBoolean()).isFalse();
    }

    static JsonNode find(JsonNode list, String ruleId) {
        for (JsonNode n : list) {
            if (ruleId.equals(n.get("ruleId").asString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("analytics: scorecard on-time / in-full, price z-score, spend groups, forecast shape")
    void analytics() {
        String vendor = supplier("Hima");
        String cement = product();
        String late = po(vendor, cement, 2, 100, LocalDate.now().minusDays(3).toString());
        receive(late, cement, 2);
        String onTime = po(vendor, cement, 2, 100, LocalDate.now().plusDays(3).toString());
        receive(onTime, cement, 2);
        JsonNode card = ok(200, "GET", "/procurement/analytics/scorecards/" + vendor, null);
        assertThat(card.get("orders").asInt()).isEqualTo(2);
        assertThat(card.get("onTimePct").asString()).isEqualTo("50");
        assertThat(card.get("inFullPct").asString()).isEqualTo("100");
        assertThat(card.get("grade").asString()).isIn("A", "B", "C", "D");

        for (int p : new int[] {98, 99, 100, 101, 102, 100}) {
            jdbc.sql("INSERT INTO \"SupplierPrice\" (id, \"organizationId\", \"partnerId\", \"productId\", \"currencyCode\","
                    + " \"unitPrice\", \"validFrom\", source) VALUES (?, ?, ?, ?, 'UGX', ?, current_date - 10, 'manual')")
                    .params(UUID.randomUUID().toString(), demoOrg, vendor, cement, p).update();
        }
        JsonNode high = ok(200, "GET", "/procurement/analytics/price-check?productId=" + cement + "&partnerId=" + vendor
                + "&unitPrice=150", null);
        assertThat(high.get("anomaly").asBoolean()).isTrue();
        assertThat(high.get("message").asString()).startsWith("Unusually high");
        JsonNode usual = ok(200, "GET", "/procurement/analytics/price-check?productId=" + cement + "&partnerId=" + vendor
                + "&unitPrice=100", null);
        assertThat(usual.get("anomaly").asBoolean()).isFalse();

        JsonNode spend = ok(200, "GET", "/procurement/analytics/spend?groupBy=supplier", null);
        assertThat(spend.get("rows").findValuesAsString("label")).anyMatch(l -> l.startsWith("Hima"));
        assertThat(as("GET", "/procurement/analytics/spend?groupBy=planet", null).status()).isEqualTo(400);
        JsonNode fc = ok(200, "GET", "/procurement/analytics/forecast?productId=" + cement, null);
        assertThat(fc.get("history")).hasSize(12);
        assertThat(fc.get("method").asString()).isEqualTo("exponential_smoothing");
        assertThat(ok(200, "GET", "/procurement/analytics/alerts", null).isArray()).isTrue();
    }

    @Test
    @DisplayName("portal: RFQ link → public view and quotation; PO link → confirm; revoke and bad tokens refused")
    void supplierPortal() {
        String rfqId = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Aggregates " + suffix(), "lines",
                List.of(Map.of("description", "Ballast 20mm", "quantity", 300)), "supplierIds", List.of(supplier)))
                .get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/send", null);
        Res stranger = as("POST", "/procurement/portal-links", Map.of("documentType", "rfq", "documentId", rfqId,
                "partnerId", supplier("Nobody")));
        assertThat(stranger.body().get("message").asString()).isEqualTo("This supplier was not invited to the RFQ");
        JsonNode link = ok(201, "POST", "/procurement/portal-links", Map.of("documentType", "rfq", "documentId", rfqId,
                "partnerId", supplier, "days", 7));
        String token = link.get("token").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"SupplierPortalToken\" WHERE \"tokenHash\" = ?").param(token)
                .query(Long.class).single()).isZero();

        Res view = call("GET", "/supplier-portal/" + token, null, Map.of());
        assertThat(view.status()).as(view.raw().body()).isEqualTo(200);
        assertThat(view.body().get("documentType").asString()).isEqualTo("rfq");
        assertThat(view.body().get("canQuote").asBoolean()).isTrue();
        String line = view.body().get("lines").get(0).get("id").asString();
        Res quote = call("POST", "/supplier-portal/" + token + "/quotation", Map.of("supplierReference", "Q-77",
                "currencyCode", "UGX", "lines", List.of(Map.of("rfqLineId", line, "unitPrice", 52000))), Map.of());
        assertThat(quote.status()).as(quote.raw().body()).isEqualTo(200);
        JsonNode cmp = ok(200, "GET", "/procurement/rfqs/" + rfqId + "/comparison", null);
        assertThat(cmp.get("suppliers")).hasSize(1);
        Res wrongAction = call("POST", "/supplier-portal/" + token + "/confirm", Map.of("supplierReference", "x"),
                Map.of());
        assertThat(wrongAction.status()).isEqualTo(400);

        String linkId = link.get("id").asString();
        assertThat(ok(200, "GET", "/procurement/portal-links?documentType=rfq&documentId=" + rfqId, null).get(0)
                .get("useCount").asInt()).isGreaterThanOrEqualTo(2);
        ok(200, "POST", "/procurement/portal-links/" + linkId + "/revoke", null);
        assertThat(call("GET", "/supplier-portal/" + token, null, Map.of()).status()).isEqualTo(404);
        assertThat(call("GET", "/supplier-portal/not-a-real-token-" + "x".repeat(30), null, Map.of()).status())
                .isEqualTo(404);

        String cement = product();
        String poId = po(supplier, cement, 10, 50000, null);
        String poToken = ok(201, "POST", "/procurement/portal-links", Map.of("documentType", "purchase_order",
                "documentId", poId)).get("token").asString();
        JsonNode poView = call("GET", "/supplier-portal/" + poToken, null, Map.of()).body();
        assertThat(poView.get("canConfirm").asBoolean()).isTrue();
        Res confirmed = call("POST", "/supplier-portal/" + poToken + "/confirm", Map.of("supplierReference", "SO-991",
                "promisedDate", LocalDate.now().plusDays(5).toString()), Map.of());
        assertThat(confirmed.status()).as(confirmed.raw().body()).isEqualTo(200);
        JsonNode life = ok(200, "GET", "/procurement/purchase-orders/" + poId + "/lifecycle", null);
        assertThat(life.get("supplierReference").asString()).isEqualTo("SO-991");
        assertThat(life.get("acknowledgedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("authorization matrix: planning routes need permissions; the portal is public")
    void authorization() {
        assertThat(assertAuthorizationMatrix(PlanningController.class, SupplierPortalController.class)).isEqualTo(18);
    }
}
