package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Phase A A3 (plan §2, §3, §9, §10): every delivered quantity sits in one bucket — accepted, returned, damaged,
 * confirmed shortage, pending dispute — and dispatched = Σ buckets; a dismissed POD shortage stays disputed until an
 * authorised, evidenced POD correction reconciles it; every change leaves a line history row; the operational
 * reconciliation repairs a drifted billable quantity and flags it.
 */
class SalesQuantityBucketsIT extends ApiTestSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA"
            + "60e6kgAAAABJRU5ErkJggg==";

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    int seq;
    String sand;
    String yard;
    String customer;
    String decider;
    String corrector;
    String readOnly;

    @BeforeAll
    void fixtures() {
        String ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'")
                .param(demoOrg).query(String.class).single();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(yard, demoOrg, "QBY-" + sfx, "QB yard").update();
        sand = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"billingBasis\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 45000, ?, ?, ?,"
                + " 'accepted', now())").params(sand, demoOrg, "QBS-" + sfx, "QB sand " + sfx, ton, ton, ton).update();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "QBC-" + sfx, "QB cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(sand, yard, new BigDecimal("2000"))
                        .unitCost(new BigDecimal("20000")))));
        decider = tokenWith("fleet_dispatch:read", "fleet_dispatch:approve_discrepancy");
        corrector = tokenWith("sales:order:read", "sales:order:read_all", "sales:delivery:read",
                "sales:delivery:pod_correct");
        readOnly = tokenWith("sales:order:read", "sales:order:read_all", "sales:delivery:read");
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "QB " + role, permissions).update();
        String email = "qb-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Bucket@12345", role);
        return login("DEMO", email, "Bucket@12345");
    }

    Map<String, String> headers(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String token, String path, Object body) {
        Res r = call("POST", path, body, headers(token));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    /** A dispatched delivery of {@code qty} t sand. */
    String dispatched(int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", sand, "quantity", qty, "unitPrice", 45000)))).get("id").asString();
        JsonNode o = ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        String d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UQB " + sfx + "-"
                + (++seq), "driverName", "Driver", "lines", List.of(Map.of("lineId", o.get("lines").get(0).get("id")
                        .asString(), "quantity", qty)))).get("id").asString();
        money(adminToken, "/sales/deliveries/" + d + "/issue", null);
        return d;
    }

    JsonNode delivery(String id) {
        return ok(200, "GET", "/sales/deliveries/" + id, null);
    }

    String lineId(String d) {
        return delivery(d).get("lines").get(0).get("id").asString();
    }

    /** POD of the single line by the admin. */
    JsonNode pod(String d, Map<String, Object> line) {
        Map<String, Object> l = new LinkedHashMap<>(line);
        l.put("loadLineId", lineId(d));
        return money(adminToken, "/sales/deliveries/" + d + "/confirm", Map.of("receiverName", "Site agent", "lines",
                List.of(l)));
    }

    Map<String, Object> claim(String d, String kind) {
        return jdbc.sql("SELECT id, status, \"differenceQty\", \"reconciledQty\", \"resolutionModel\" FROM"
                + " \"FleetDeliveryDiscrepancy\" WHERE \"loadId\" = ? AND kind = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(d, kind).query().singleRow();
    }

    BigDecimal billable(String d) {
        return jdbc.sql("SELECT \"billableQty\" FROM \"FleetLoadLine\" WHERE \"loadId\" = ?").param(d)
                .query(BigDecimal.class).single();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static BigDecimal dec(Object o) {
        return new BigDecimal(o.toString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m == null ? "" : m.isArray() ? m.get(0).asString() : m.asString();
    }

    @Test
    @DisplayName("30 = 25 accepted + 2 returned + 1 damaged + 1 confirmed shortage + 1 pending weight dispute")
    void reviewersExample() {
        String d = dispatched(30);
        pod(d, Map.of("deliveredQty", 26, "rejectedQty", 2, "damagedQty", 1, "reason", "spillage"));
        Map<String, Object> shortage = claim(d, "shortage");
        assertThat(dec(shortage.get("differenceQty"))).as("damaged is not folded into the shortage")
                .isEqualByComparingTo("1");
        assertThat(((Number) shortage.get("resolutionModel")).intValue()).isEqualTo(2);
        assertThat(dec(claim(d, "damaged").get("differenceQty"))).isEqualByComparingTo("1");
        money(decider, "/fleet/delivery-discrepancies/" + shortage.get("id") + "/approve", Map.of());
        // The customer's weighing is 1 t lighter (manual quarry weight 30 000 kg).
        jdbc.sql("UPDATE \"FleetLoad\" SET \"manualGross\" = 42000, \"manualTare\" = 12000 WHERE id = ?").param(d)
                .update();
        money(adminToken, "/sales/deliveries/" + d + "/customer-weight", Map.of("netKg", 29000));

        JsonNode l = delivery(d).get("lines").get(0);
        assertThat(dec(l.get("acceptedQty"))).isEqualByComparingTo("25");
        assertThat(dec(l.get("returnedQty"))).isEqualByComparingTo("2");
        assertThat(dec(l.get("damagedQty"))).isEqualByComparingTo("1");
        assertThat(dec(l.get("confirmedShortageQty"))).isEqualByComparingTo("1");
        assertThat(dec(l.get("pendingDisputedQty"))).isEqualByComparingTo("1");
        assertThat(billable(d)).isEqualByComparingTo("25");

        // Reduce the pending weight claim to 0.5 t (a reason is required): accepted rises to 25.5.
        String weight = (String) claim(d, "weight").get("id");
        Res noReason = call("POST", "/fleet/delivery-discrepancies/" + weight + "/reduce", Map.of("quantity", 0.5,
                "reason", ""), headers(decider));
        assertThat(noReason.status()).isEqualTo(400);
        money(decider, "/fleet/delivery-discrepancies/" + weight + "/reduce", Map.of("quantity", 0.5, "reason",
                "Customer re-weighed"));
        assertThat(billable(d)).isEqualByComparingTo("25.5");

        JsonNode summary = ok(200, "GET", "/sales/orders/" + delivery(d).get("orderId").asString()
                + "/delivery-summary", null);
        JsonNode sl = summary.get("lines").get(0);
        assertThat(dec(sl.get("accepted"))).isEqualByComparingTo("25.5");
        assertThat(dec(sl.get("confirmedShortage"))).isEqualByComparingTo("1");
        assertThat(dec(sl.get("pendingDisputed"))).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("dismissed POD shortage stays disputed; an evidenced correction reconciles part, the rest is confirmed")
    void dismissedThenCorrected() {
        String d = dispatched(30);
        pod(d, Map.of("deliveredQty", 28, "reason", "weighing_variance"));
        String c = (String) claim(d, "shortage").get("id");
        money(decider, "/fleet/delivery-discrepancies/" + c + "/reject", Map.of("notes", "Driver says all delivered"));
        JsonNode l = delivery(d).get("lines").get(0);
        assertThat(dec(l.get("acceptedQty"))).isEqualByComparingTo("28");
        assertThat(dec(l.get("pendingDisputedQty"))).as("dismissed: POD correction required").isEqualByComparingTo("2");
        assertThat(delivery(d).get("claims").get(0).get("claimDecision").asString()).isEqualTo("dismissed");

        String line = lineId(d);
        String path = "/sales/deliveries/" + d + "/pod-correction";
        Map<String, Object> body = Map.of("lines", List.of(Map.of("loadLineId", line, "deliveredQty", 29)), "reason",
                "Site book shows 29 t", "evidence", List.of(PNG));
        assertThat(call("POST", path, body, headers(readOnly)).status()).isEqualTo(403);
        Res self = call("POST", path, body, headers(adminToken));
        assertThat(self.status()).as("the POD recorder cannot correct it").isEqualTo(403);
        Res noEvidence = call("POST", path, Map.of("lines", List.of(Map.of("loadLineId", line, "deliveredQty", 29)),
                "reason", "x"), headers(corrector));
        assertThat(noEvidence.status()).isEqualTo(400);
        assertThat(msg(noEvidence)).isEqualTo("Evidence is required to correct a proof of delivery");
        Res lower = call("POST", path, Map.of("lines", List.of(Map.of("loadLineId", line, "deliveredQty", 27)),
                "reason", "x", "evidence", List.of(PNG)), headers(corrector));
        assertThat(lower.status()).isEqualTo(400);
        Res over = call("POST", path, Map.of("lines", List.of(Map.of("loadLineId", line, "deliveredQty", 31)),
                "reason", "x", "evidence", List.of(PNG)), headers(corrector));
        assertThat(over.status()).isEqualTo(400);

        money(corrector, path, body);
        Map<String, Object> after = claim(d, "shortage");
        assertThat(dec(after.get("reconciledQty"))).isEqualByComparingTo("1");
        l = delivery(d).get("lines").get(0);
        assertThat(dec(l.get("deliveredQty"))).isEqualByComparingTo("29");
        assertThat(dec(l.get("acceptedQty"))).isEqualByComparingTo("29");
        assertThat(dec(l.get("pendingDisputedQty"))).isEqualByComparingTo("1");

        // A full correction of the rest closes the claim as reconciled.
        money(corrector, path, Map.of("lines", List.of(Map.of("loadLineId", line, "deliveredQty", 30)), "reason",
                "Second page of the site book", "evidence", List.of(PNG)));
        assertThat(claim(d, "shortage").get("status")).isEqualTo("reconciled");
        assertThat(billable(d)).isEqualByComparingTo("30");

        JsonNode h = ok(200, "GET", "/sales/deliveries/" + d + "/lines/" + line + "/history", null);
        List<String> kinds = new ArrayList<>();
        h.get("events").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("dispatched", "pod_recorded", "claim_opened", "claim_dismissed", "pod_corrected",
                "claim_reconciled");
        assertThat(dec(h.get("line").get("buckets").get("acceptedQty"))).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("pending shortage confirmed after a partial correction: accepted 29, confirmed 1")
    void partialCorrectionThenConfirm() {
        String d = dispatched(30);
        pod(d, Map.of("deliveredQty", 28, "reason", "spillage"));
        money(corrector, "/sales/deliveries/" + d + "/pod-correction", Map.of("lines", List.of(Map.of("loadLineId",
                lineId(d), "deliveredQty", 29)), "reason", "Site book", "evidence", List.of(PNG)));
        Map<String, Object> c = claim(d, "shortage");
        assertThat(c.get("status")).isEqualTo("pending");
        money(decider, "/fleet/delivery-discrepancies/" + c.get("id") + "/approve", Map.of());
        JsonNode l = delivery(d).get("lines").get(0);
        assertThat(dec(l.get("acceptedQty"))).isEqualByComparingTo("29");
        assertThat(dec(l.get("confirmedShortageQty"))).isEqualByComparingTo("1");
        assertThat(dec(l.get("pendingDisputedQty"))).isEqualByComparingTo("0");
        assertThat(billable(d)).isEqualByComparingTo("29");
    }

    @Test
    @DisplayName("reconciliation run repairs a drifted billable quantity and flags it; the next run resolves it")
    void reconciliationRepairsDrift() {
        String d = dispatched(10);
        pod(d, Map.of("deliveredQty", 10));
        jdbc.sql("UPDATE \"FleetLoadLine\" SET \"billableQty\" = 7 WHERE \"loadId\" = ?").param(d).update();
        Res denied = call("POST", "/fleet/reconciliation/run", null, headers(readOnly));
        assertThat(denied.status()).isEqualTo(403);
        JsonNode run = money(adminToken, "/fleet/reconciliation/run", null);
        assertThat(run.get("repaired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(billable(d)).isEqualByComparingTo("10");
        String line = lineId(d);
        assertThat(jdbc.sql("SELECT status FROM \"FleetReconciliationFinding\" WHERE \"loadLineId\" = ? AND rule ="
                + " 'billable_drift_repaired'").param(line).query(String.class).list()).containsExactly("open");
        money(adminToken, "/fleet/reconciliation/run", null);
        assertThat(jdbc.sql("SELECT status FROM \"FleetReconciliationFinding\" WHERE \"loadLineId\" = ? AND rule ="
                + " 'billable_drift_repaired'").param(line).query(String.class).single()).isEqualTo("resolved");
        JsonNode findings = ok(200, "GET", "/fleet/reconciliation/findings?scope=operational&status=resolved", null);
        assertThat(findings.get("data")).isNotNull();
    }
}
