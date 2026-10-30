package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase S1 of ADR-S011: sales orders (direct and from a quotation), confirm / hold / release / cancel / close short,
 * amendments through change requests with immutable revisions and quantity floors, R1 reports, settings, the
 * authorization matrix of every {@code /sales} route and tenant isolation.
 */
class SalesOrderIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String aggregate;
    String blocks;
    String repToken;
    String repUser;

    @BeforeAll
    void fixtures() {
        customer = partner("Contractor " + sfx, false);
        aggregate = product("Aggregate 20mm " + sfx, 50000);
        blocks = product("Block 6in " + sfx, 2500);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "SO rep " + sfx, new String[] {
                    "sales:order:read", "sales:order:write", "sales:order:confirm", "sales:order:amend",
                    "sales:quotation:read", "sales:quotation:write", "sales:quotation:convert"}).update();
        String email = "so-rep-" + sfx.toLowerCase() + "@demo.test";
        repUser = createUser(demoOrg, email, "Sales@12345", role);
        repToken = login("DEMO", email, "Sales@12345");
    }

    String partner(String name, boolean hold) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"creditHold\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, ?, now())")
                .params(id, demoOrg, "SO-" + UUID.randomUUID().toString().substring(0, 8), name, hold).update();
        return id;
    }

    String product(String name, int price) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", ?,"
                + " ?, false, now())").params(id, demoOrg, "SP-" + UUID.randomUUID().toString().substring(0, 8), name,
                price, price / 2).update();
        return id;
    }

    JsonNode order(String partnerId, List<Map<String, Object>> lines) {
        return ok(201, "POST", "/sales/orders", Map.of("partnerId", partnerId, "customerRef", "PO-" + sfx,
                "requestedDate", "2026-11-15", "lines", lines));
    }

    JsonNode confirm(String id) {
        return ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
    }

    @Test
    @DisplayName("direct order: draft edit, priced from list, confirm, numbering, quantities")
    void directOrder() {
        JsonNode o = order(customer, List.of(Map.of("productId", aggregate, "quantity", 100), Map.of("productId", blocks,
                "quantity", 2000, "discountPercent", 5)));
        String id = o.get("id").asString();
        assertThat(o.get("number").asString()).startsWith("SO-");
        assertThat(o.get("status").asString()).isEqualTo("draft");
        assertThat(o.get("subtotal").asDouble()).isEqualTo(100 * 50000 + 2000 * 2500 * 0.95);
        assertThat(o.get("lines").get(0).get("priceSource").asString()).isIn("list", "rule", "customer");
        assertThat(o.get("lines").get(0).get("costSnapshot").asDouble()).isEqualTo(25000);
        JsonNode edited = ok(200, "PUT", "/sales/orders/" + id, Map.of("notes", "Site gate 3", "lines", List.of(Map.of(
                "productId", aggregate, "quantity", 120))));
        assertThat(edited.get("lines").size()).isEqualTo(1);
        assertThat(edited.get("total").asDouble()).isEqualTo(120 * 50000.0);
        JsonNode confirmed = confirm(id);
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        assertThat(confirmed.get("onHold").asBoolean()).isFalse();
        JsonNode q = confirmed.get("lines").get(0).get("qty");
        assertThat(q.get("ordered").asDouble()).isEqualTo(120);
        assertThat(q.get("remaining").asDouble()).isEqualTo(120);
        assertThat(q.get("netInvoiced").asDouble()).isZero();
        assertThat(as("PUT", "/sales/orders/" + id, Map.of("notes", "x")).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/orders/" + id + "/confirm", null).status()).isEqualTo(400);
        assertThat(as("DELETE", "/sales/orders/" + id, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("customer on credit hold: confirmed order is held; release refused until customer hold lifted")
    void creditHold() {
        String held = partner("Held " + sfx, true);
        String id = order(held, List.of(Map.of("productId", aggregate, "quantity", 10))).get("id").asString();
        JsonNode c = confirm(id);
        assertThat(c.get("onHold").asBoolean()).isTrue();
        assertThat(c.get("holdReason").asString()).contains("credit hold");
        assertThat(as("POST", "/sales/orders/" + id + "/release", null).status()).isEqualTo(400);
        jdbc.sql("UPDATE \"Partner\" SET \"creditHold\" = false WHERE id = ?").param(held).update();
        assertThat(ok(200, "POST", "/sales/orders/" + id + "/release", null).get("onHold").asBoolean()).isFalse();
        assertThat(ok(200, "POST", "/sales/orders/" + id + "/hold", Map.of("reason", "Site not ready")).get("holdReason")
                .asString()).isEqualTo("Site not ready");
        assertThat(as("POST", "/sales/orders/" + id + "/hold", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("quotation converts into a sales order with the agreed prices; quote marked converted")
    void fromQuotation() {
        JsonNode quote = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", aggregate, "quantity", 500, "unitPrice", 47000))));
        String qid = quote.get("id").asString();
        ok(200, "POST", "/sales/quotations/" + qid + "/submit", null);
        JsonNode converted = ok(200, "POST", "/sales/quotations/" + qid + "/convert", Map.of());
        assertThat(converted.get("status").asString()).isEqualTo("converted");
        assertThat(converted.get("convertedType").asString()).isEqualTo("sales_order");
        String soId = converted.get("converted").get("id").asString();
        JsonNode so = ok(200, "GET", "/sales/orders/" + soId, null);
        assertThat(so.get("quotationId").asString()).isEqualTo(qid);
        assertThat(so.get("relations").get("quotation").get("number").asString()).isEqualTo(quote.get("number")
                .asString());
        assertThat(so.get("lines").get(0).get("unitPrice").asDouble()).isEqualTo(47000);
        assertThat(so.get("lines").get(0).get("priceSource").asString()).isEqualTo("quotation");
        assertThat(ok(200, "GET", "/sales/quotations/" + qid, null).get("convertedNumber").asString())
                .isEqualTo(so.get("number").asString());
    }

    @Test
    @DisplayName("change request: impact, own approval refused, apply → revision snapshot + diff; stale request refused")
    void amendments() {
        String id = call("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                aggregate, "quantity", 100))), repToken).body().get("id").asString();
        assertThat(call("POST", "/sales/orders/" + id + "/confirm", null, repToken).status()).isEqualTo(200);
        String lineId = ok(200, "GET", "/sales/orders/" + id, null).get("lines").get(0).get("id").asString();
        assertThat(as("PUT", "/sales/orders/" + id, Map.of("lines", List.of())).status()).isEqualTo(400);
        JsonNode cr = call("POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "Customer needs more",
                "changes", Map.of("header", Map.of("requestedDate", "2026-12-01"), "lines", List.of(Map.of("lineId",
                        lineId, "quantity", 150)), "addLines", List.of(Map.of("productId", blocks, "quantity", 400)))),
                repToken).body();
        assertThat(cr.get("status").asString()).isEqualTo("pending_approval");
        assertThat(cr.get("impact").get("valueDelta").asDouble()).isEqualTo(50 * 50000 + 400 * 2500);
        assertThat(call("POST", "/sales/change-requests/" + cr.get("id").asString() + "/approve", Map.of(), repToken)
                .status()).isEqualTo(403);
        assertThat(as("POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "second", "changes",
                Map.of("header", Map.of("notes", "x")))).status()).isEqualTo(409);
        JsonNode applied = ok(200, "POST", "/sales/change-requests/" + cr.get("id").asString() + "/approve", Map.of(
                "comment", "ok"));
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(applied.get("appliedRevision").asInt()).isEqualTo(1);
        JsonNode so = ok(200, "GET", "/sales/orders/" + id, null);
        assertThat(so.get("revision").asInt()).isEqualTo(1);
        assertThat(so.get("requestedDate").asString()).startsWith("2026-12-01");
        assertThat(so.get("lines").size()).isEqualTo(2);
        assertThat(so.get("lines").get(0).get("quantity").asDouble()).isEqualTo(150);
        assertThat(so.get("total").asDouble()).isEqualTo(150 * 50000 + 400 * 2500.0);
        JsonNode revs = ok(200, "GET", "/sales/orders/" + id + "/revisions", null);
        assertThat(revs.size()).isEqualTo(1);
        assertThat(revs.get(0).get("revision").asInt()).isZero();
        assertThat(revs.get(0).get("snapshot").get("total").asDouble()).isEqualTo(100 * 50000.0);
        assertThat(revs.get(0).get("diff").get("lines").size()).isEqualTo(2);
        // empty request and unknown header fields are refused
        assertThat(as("POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "x", "changes", Map.of()))
                .status()).isEqualTo(400);
        assertThat(as("POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "x", "changes", Map.of(
                "header", Map.of("partnerId", customer)))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("quantity floor: cannot amend below delivered / invoiced; price change on a moved line splits it")
    void quantityFloorAndSplit() {
        String id = order(customer, List.of(Map.of("productId", aggregate, "quantity", 100))).get("id").asString();
        confirm(id);
        String lineId = ok(200, "GET", "/sales/orders/" + id, null).get("lines").get(0).get("id").asString();
        // simulate the S5/S6 projections: 30 t delivered and billable, 30 t invoiced
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 30, \"qtyBillable\" = 30, \"qtyInvoiced\" = 30 WHERE"
                + " id = ?").param(lineId).update();
        assertThat(as("POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "less", "changes", Map.of(
                "lines", List.of(Map.of("lineId", lineId, "quantity", 20))))).body().get("message").asString())
                .contains("cannot go below 30");
        assertThat(as("POST", "/sales/orders/" + id + "/cancel", Map.of()).status()).isEqualTo(400);
        JsonNode cr = ok(201, "POST", "/sales/orders/" + id + "/change-requests", Map.of("reason", "renegotiated",
                "changes", Map.of("lines", List.of(Map.of("lineId", lineId, "unitPrice", 45000)))));
        assertThat(cr.get("impact").get("splitLines").size()).isEqualTo(1);
        ok(200, "POST", "/sales/change-requests/" + cr.get("id").asString() + "/approve", Map.of());
        JsonNode so = ok(200, "GET", "/sales/orders/" + id, null);
        JsonNode old = so.get("lines").get(0);
        JsonNode rest = so.get("lines").get(1);
        assertThat(old.get("unitPrice").asDouble()).isEqualTo(50000);
        assertThat(old.get("qty").get("cancelled").asDouble()).isEqualTo(70);
        assertThat(old.get("qty").get("grossInvoiced").asDouble()).isEqualTo(30);
        assertThat(rest.get("unitPrice").asDouble()).isEqualTo(45000);
        assertThat(rest.get("quantity").asDouble()).isEqualTo(70);
        assertThat(so.get("status").asString()).isEqualTo("in_fulfilment");
        assertThat(so.get("deliveryStatus").asString()).isEqualTo("partial");
    }

    @Test
    @DisplayName("close short keeps history; cancel; duplicate; delete draft")
    void closeShortCancelDuplicate() {
        String id = order(customer, List.of(Map.of("productId", aggregate, "quantity", 50))).get("id").asString();
        confirm(id);
        String lineId = ok(200, "GET", "/sales/orders/" + id, null).get("lines").get(0).get("id").asString();
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 45, \"qtyBillable\" = 45 WHERE id = ?").param(lineId)
                .update();
        assertThat(as("POST", "/sales/orders/" + id + "/lines/" + lineId + "/close-short", Map.of()).status())
                .isEqualTo(400);
        JsonNode closed = ok(200, "POST", "/sales/orders/" + id + "/lines/" + lineId + "/close-short", Map.of("reason",
                "Customer site full"));
        assertThat(closed.get("lines").get(0).get("qty").get("cancelled").asDouble()).isEqualTo(5);
        assertThat(closed.get("lines").get(0).get("qty").get("delivered").asDouble()).isEqualTo(45);
        assertThat(closed.get("deliveryStatus").asString()).isEqualTo("full");
        assertThat(closed.get("invoiceStatus").asString()).isEqualTo("to_invoice");

        String other = order(customer, List.of(Map.of("productId", blocks, "quantity", 10))).get("id").asString();
        confirm(other);
        JsonNode cancelled = ok(200, "POST", "/sales/orders/" + other + "/cancel", Map.of("reason", "Duplicate PO"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        JsonNode copy = ok(201, "POST", "/sales/orders/" + other + "/duplicate", null);
        assertThat(copy.get("status").asString()).isEqualTo("draft");
        assertThat(copy.get("lines").get(0).get("quantity").asDouble()).isEqualTo(10);
        ok(200, "DELETE", "/sales/orders/" + copy.get("id").asString(), null);
        assertThat(as("GET", "/sales/orders/" + copy.get("id").asString(), null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("visibility: rep sees own + unassigned orders only; summary report; settings")
    void visibilityReportsSettings() {
        JsonNode mine = call("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                aggregate, "quantity", 1))), repToken).body();
        JsonNode admins = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "ownerId", userId(), "lines",
                List.of(Map.of("productId", aggregate, "quantity", 1))));
        JsonNode list = call("GET", "/sales/orders?pageSize=200&q=" + "Contractor%20" + sfx, null, repToken).body();
        List<String> seen = new java.util.ArrayList<>();
        list.get("data").forEach(x -> seen.add(x.get("id").asString()));
        assertThat(seen).contains(mine.get("id").asString()).doesNotContain(admins.get("id").asString());
        assertThat(call("GET", "/sales/orders/" + admins.get("id").asString(), null, repToken).status()).isEqualTo(404);

        JsonNode s = ok(200, "GET", "/sales/reports/summary", null);
        assertThat(s.get("openOrders").get("count").asInt()).isPositive();
        assertThat(s.has("conversion")).isTrue();
        assertThat(call("GET", "/sales/reports/summary", null, repToken).status()).isEqualTo(403);

        assertThat(as("PUT", "/sales/settings", Map.of("ownershipTransfer", "at_acceptance")).status()).isEqualTo(400);
        JsonNode set = ok(200, "PUT", "/sales/settings", Map.of("billingPolicy", "ordered"));
        assertThat(set.get("billingPolicy").asString()).isEqualTo("ordered");
        ok(200, "PUT", "/sales/settings", Map.of("billingPolicy", "delivered"));
    }

    String userId() {
        return jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix of /sales routes")
    void isolationAndMatrix() {
        String id = order(customer, List.of(Map.of("productId", aggregate, "quantity", 1))).get("id").asString();
        assertThat(call("GET", "/sales/orders/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/sales/orders?q=" + sfx, null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
        assertThat(assertAuthorizationMatrix("com.inventoryerp.sales")).isGreaterThan(30);
        assertThat(new BigDecimal("1")).isPositive();
    }
}
