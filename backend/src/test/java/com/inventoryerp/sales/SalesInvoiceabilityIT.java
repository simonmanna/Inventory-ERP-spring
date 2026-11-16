package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase A A4 (plan §5, §6, §10, §13): a Sales-module order invoices its own goods, for the POD-accepted quantity only
 * (also on the weighed basis); its Fleet delivery order invoices transport charges only; a draft that would bill more
 * than is invoiceable cannot post; a claim confirmed after invoicing gets a draft credit note for what was invoiced
 * beyond accepted; a historical Fleet goods invoice blocks the order until a two-person review reconciles it.
 */
class SalesInvoiceabilityIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    int seq;
    String sand;
    String gravel;
    String yard;
    String customer;
    String decider;
    String reviewer;
    String approver;

    @BeforeAll
    void fixtures() {
        String ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'")
                .param(demoOrg).query(String.class).single();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(yard, demoOrg, "SIY-" + sfx, "SI yard").update();
        sand = product("SIS-", "SI sand", ton, "accepted");
        gravel = product("SIG-", "SI gravel", ton, "weighed");
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "SIC-" + sfx, "SI cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    stock.receive(StockMove.of(sand, yard, new BigDecimal("2000")).unitCost(new BigDecimal("20000")));
                    stock.receive(StockMove.of(gravel, yard, new BigDecimal("2000")).unitCost(new BigDecimal("20000")));
                }));
        decider = tokenWith("fleet_dispatch:read", "fleet_dispatch:approve_discrepancy");
        reviewer = tokenWith("sales:order:read", "sales:order:read_all", "sales:billing:override");
        approver = tokenWith("sales:order:read", "sales:order:read_all", "sales:billing:override");
    }

    String product(String prefix, String name, String uom, String basis) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"billingBasis\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 45000, ?, ?, ?,"
                + " ?, now())").params(id, demoOrg, prefix + sfx, name + " " + sfx, uom, uom, uom, basis).update();
        return id;
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "SI " + role, permissions).update();
        String email = "si-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Invoice@12345", role);
        return login("DEMO", email, "Invoice@12345");
    }

    Map<String, String> headers(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String token, String path, Object body) {
        Res r = call("POST", path, body, headers(token));
        assertThat(r.status()).as(path + " " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    record Delivery(String orderId, String deliveryId, String lineId) {}

    /** Confirmed order of {@code qty} t of {@code product}, one delivery dispatched in full. */
    Delivery dispatched(String product, int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 45000)))).get("id").asString();
        JsonNode o = ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        String d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "USI " + sfx + "-"
                + (++seq), "driverName", "Driver", "lines", List.of(Map.of("lineId", o.get("lines").get(0).get("id")
                        .asString(), "quantity", qty)))).get("id").asString();
        money(adminToken, "/sales/deliveries/" + d + "/issue", null);
        String line = ok(200, "GET", "/sales/deliveries/" + d, null).get("lines").get(0).get("id").asString();
        return new Delivery(so, d, line);
    }

    void pod(Delivery v, Map<String, Object> line) {
        Map<String, Object> l = new LinkedHashMap<>(line);
        l.put("loadLineId", v.lineId());
        money(adminToken, "/sales/deliveries/" + v.deliveryId() + "/confirm", Map.of("receiverName", "Site agent",
                "lines", List.of(l)));
        settle(v);
    }

    /** Let the Fleet events reach Sales, then rebuild deterministically. */
    void settle(Delivery v) {
        drainOutboxFully();
        Res r = call("POST", "/sales/orders/" + v.orderId() + "/rebuild", null, headers(adminToken));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    BigDecimal invoiceable(String so) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : ok(200, "GET", "/sales/orders/" + so + "/billing", null).get("lines")) {
            sum = sum.add(new BigDecimal(l.get("quantity").asString()));
        }
        return sum;
    }

    String doOf(String so) {
        return jdbc.sql("SELECT id FROM \"FleetDeliveryOrder\" WHERE \"sourceType\" = 'sales_order' AND \"sourceId\" = ?")
                .param(so).query(String.class).single();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m == null ? "" : m.isArray() ? m.get(0).asString() : m.asString();
    }

    @Test
    @DisplayName("invoiceable = accepted; the pending dispute never invoices; the delivery order carries charges only")
    void acceptedOnlyAndFleetChargesOnly() {
        Delivery v = dispatched(sand, 30);
        pod(v, Map.of("deliveredQty", 28, "reason", "spillage"));
        assertThat(invoiceable(v.orderId())).as("2 t pending dispute held back").isEqualByComparingTo("28");
        JsonNode inv = money(adminToken, "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"));
        assertThat(new BigDecimal(inv.get("lines").get(0).get("quantity").asString())).isEqualByComparingTo("28");
        assertThat(jdbc.sql("SELECT \"documentLineId\" FROM \"SalesInvoiceLink\" WHERE \"documentId\" = ? AND kind ="
                + " 'regular'").param(inv.get("id").asString()).query(String.class).single()).isNotNull();

        // The Fleet delivery order of a Sales order never invoices goods (no charge rule here: nothing at all).
        String shortage = jdbc.sql("SELECT id FROM \"FleetDeliveryDiscrepancy\" WHERE \"loadId\" = ? AND kind = 'shortage'")
                .param(v.deliveryId()).query(String.class).single();
        money(decider, "/fleet/delivery-discrepancies/" + shortage + "/approve", Map.of());
        Res fleet = call("POST", "/fleet/delivery-orders/" + doOf(v.orderId()) + "/invoice", Map.of(),
                headers(adminToken));
        assertThat(fleet.status()).as(fleet.raw().body()).isEqualTo(400);
        assertThat(msg(fleet)).startsWith("Nothing to invoice on delivery order");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetDeliveryInvoiceLine\" WHERE \"deliveryOrderId\" = ?")
                .param(doOf(v.orderId())).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("weighed basis: nothing invoiceable before the POD, the accepted quantity after it")
    void weighedOnlyAfterPod() {
        Delivery v = dispatched(gravel, 20);
        settle(v);
        assertThat(invoiceable(v.orderId())).as("dispatched is not accepted").isEqualByComparingTo("0");
        pod(v, Map.of("deliveredQty", 19, "reason", "weighing_variance"));
        assertThat(invoiceable(v.orderId())).isEqualByComparingTo("19");
    }

    @Test
    @DisplayName("a draft that bills more than is invoiceable cannot post until the claim is dismissed")
    void postGuard() {
        Delivery v = dispatched(sand, 30);
        pod(v, Map.of("deliveredQty", 30));
        String draft = money(adminToken, "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"))
                .get("id").asString();
        // The customer weighs 1 t light: a pending weight claim lowers accepted to 29 under the draft.
        jdbc.sql("UPDATE \"FleetLoad\" SET \"manualGross\" = 42000, \"manualTare\" = 12000 WHERE id = ?")
                .param(v.deliveryId()).update();
        money(adminToken, "/sales/deliveries/" + v.deliveryId() + "/customer-weight", Map.of("netKg", 29000));
        settle(v);
        Res blocked = call("POST", "/invoices/" + draft + "/post", null, headers(adminToken));
        assertThat(blocked.status()).as(blocked.raw().body()).isEqualTo(409);
        assertThat(msg(blocked)).contains("can be invoiced").contains("cancel this draft");

        // Dismiss the claim: accepted is 30 again and the draft posts.
        String claim = jdbc.sql("SELECT id FROM \"FleetDeliveryDiscrepancy\" WHERE \"loadId\" = ? AND kind = 'weight'")
                .param(v.deliveryId()).query(String.class).single();
        money(decider, "/fleet/delivery-discrepancies/" + claim + "/reject", Map.of("notes", "Customer scale off"));
        settle(v);
        ok(200, "POST", "/invoices/" + draft + "/post", null);
    }

    @Test
    @DisplayName("claim raised after posting: over-invoiced pending is flagged; confirmed → one draft credit note")
    void lateCreditAfterPosting() {
        Delivery v = dispatched(sand, 30);
        pod(v, Map.of("deliveredQty", 30));
        String draft = money(adminToken, "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"))
                .get("id").asString();
        ok(200, "POST", "/invoices/" + draft + "/post", null);
        drainOutboxFully();
        jdbc.sql("UPDATE \"FleetLoad\" SET \"manualGross\" = 42000, \"manualTare\" = 12000 WHERE id = ?")
                .param(v.deliveryId()).update();
        money(adminToken, "/sales/deliveries/" + v.deliveryId() + "/customer-weight", Map.of("netKg", 29000));
        settle(v);
        JsonNode recon = money(approver, "/sales/billing/reconcile", null);
        assertThat(recon.get("findings").toString()).contains("over_invoiced");
        String second = jdbc.sql("SELECT id FROM \"FleetDeliveryDiscrepancy\" WHERE \"loadId\" = ? AND kind = 'weight'"
                + " AND status = 'pending'").param(v.deliveryId()).query(String.class).single();
        money(decider, "/fleet/delivery-discrepancies/" + second + "/approve", Map.of());
        drainOutboxFully();
        List<Map<String, Object>> credits = jdbc.sql("SELECT k.quantity, d.status::text AS status, d.\"reversedDocumentId\""
                + " FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\" WHERE k.\"orderId\" = ? AND"
                + " k.kind = 'credit_note'").param(v.orderId()).query().listOfRows();
        assertThat(credits).hasSize(1);
        assertThat(new BigDecimal(credits.get(0).get("quantity").toString())).isEqualByComparingTo("1");
        assertThat(credits.get(0).get("status")).isEqualTo("draft");
        assertThat(credits.get(0).get("reversedDocumentId")).isEqualTo(draft);
    }

    @Test
    @DisplayName("historical Fleet goods invoice: order blocked (409) until a two-person review reconciles it")
    void legacyFleetInvoiceBlocksUntilReconciled() {
        Delivery v = dispatched(sand, 10);
        pod(v, Map.of("deliveredQty", 10));
        // Before A4 the delivery order could invoice the order's goods itself: stand in for such an invoice.
        String fleetInv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate",
                java.time.LocalDate.now().toString(), "lines", List.of(Map.of("productId", sand, "quantity", 10,
                        "unitPrice", 45000)))).get("id").asString();
        jdbc.sql("UPDATE \"FleetDeliveryOrder\" SET \"goodsInvoiceId\" = ?, \"goodsInvoiceKind\" = 'document' WHERE id = ?")
                .params(fleetInv, doOf(v.orderId())).update();

        Res blocked = call("POST", "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"),
                headers(adminToken));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(msg(blocked)).endsWith("has historical Fleet invoices awaiting reconciliation");
        assertThat(ok(200, "GET", "/sales/orders/" + v.orderId() + "/billing", null).get("blocked").asBoolean())
                .isTrue();
        JsonNode queue = ok(200, "GET", "/sales/legacy-invoice-reviews", null);
        assertThat(queue.toString()).contains(fleetInv);

        JsonNode mine = ok(200, "GET", "/sales/orders/" + v.orderId() + "/legacy-invoices", null);
        JsonNode proposed = mine.get("invoices").get(0).get("proposedMatches").get(0);
        Map<String, Object> match = Map.of("documentLineId", proposed.get("documentLineId").asString(), "orderLineId",
                proposed.get("orderLineId").asString(), "quantity", 10);
        JsonNode review = money(reviewer, "/sales/orders/" + v.orderId() + "/legacy-invoices/" + fleetInv + "/review",
                Map.of("matches", List.of(match), "notes", "Matched against DN"));
        String reviewId = review.get("id").asString();
        Res self = call("POST", "/sales/legacy-invoice-reviews/" + reviewId + "/approve", null, headers(reviewer));
        assertThat(self.status()).as("separation of duties").isEqualTo(403);
        money(approver, "/sales/legacy-invoice-reviews/" + reviewId + "/approve", Map.of("notes", "OK"));

        assertThat(jdbc.sql("SELECT origin FROM \"SalesInvoiceLink\" WHERE \"documentId\" = ? AND \"legacyReviewId\" = ?")
                .params(fleetInv, reviewId).query(String.class).single()).isEqualTo("legacy_fleet");
        assertThat(ok(200, "GET", "/sales/orders/" + v.orderId() + "/billing", null).get("blocked").asBoolean())
                .isFalse();
        Res nothing = call("POST", "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"),
                headers(adminToken));
        assertThat(nothing.status()).as("the 10 t are already on the reconciled Fleet invoice").isEqualTo(400);
    }

    @Test
    @DisplayName("legacy review exception keeps the order blocked; needs sales:billing:override")
    void legacyExceptionStaysBlocked() {
        Delivery v = dispatched(sand, 5);
        pod(v, Map.of("deliveredQty", 5));
        String fleetInv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate",
                java.time.LocalDate.now().toString(), "lines", List.of(Map.of("productId", sand, "quantity", 7,
                        "unitPrice", 45000)))).get("id").asString();
        jdbc.sql("UPDATE \"FleetDeliveryOrder\" SET \"goodsInvoiceId\" = ?, \"goodsInvoiceKind\" = 'document' WHERE id = ?")
                .params(fleetInv, doOf(v.orderId())).update();
        assertThat(call("GET", "/sales/legacy-invoice-reviews", null, decider).status()).isEqualTo(403);
        JsonNode proposed = ok(200, "GET", "/sales/orders/" + v.orderId() + "/legacy-invoices", null).get("invoices")
                .get(0).get("proposedMatches").get(0);
        Res over = call("POST", "/sales/orders/" + v.orderId() + "/legacy-invoices/" + fleetInv + "/review",
                Map.of("matches", List.of(Map.of("documentLineId", proposed.get("documentLineId").asString(),
                        "orderLineId", proposed.get("orderLineId").asString(), "quantity", 7))), headers(reviewer));
        assertThat(over.status()).isEqualTo(400);
        assertThat(msg(over)).endsWith("record an exception instead");
        String reviewId = money(reviewer, "/sales/orders/" + v.orderId() + "/legacy-invoices/" + fleetInv + "/review",
                Map.of("matches", List.of(Map.of("documentLineId", proposed.get("documentLineId").asString(),
                        "orderLineId", proposed.get("orderLineId").asString(), "quantity", 5)))).get("id").asString();
        money(reviewer, "/sales/legacy-invoice-reviews/" + reviewId + "/exception", Map.of("notes",
                "Fleet invoice billed 7 t of a 5 t order"));
        Res blocked = call("POST", "/sales/orders/" + v.orderId() + "/invoices", Map.of("mode", "regular"),
                headers(adminToken));
        assertThat(blocked.status()).isEqualTo(409);
    }
}
