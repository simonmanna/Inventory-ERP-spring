package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 O5: field sales recorded offline — orders and quotes priced by the server (price audit against the
 * tolerance), credit holds and the rep's offline order cap, payments recorded as received with allocations applied
 * up to what is still open, the review queue, the payment reconciliation report and the field-sales pull scopes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncSalesIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    String pcs;
    String product;
    int seq;
    String capSetting;

    @BeforeAll
    void fixtures() {
        JsonNode d = ok(201, "POST", "/sync/devices/register", Map.of("name", "Field tablet " + sfx(), "platform", "web"));
        token = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        pcs = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'PCS', 'unit', 1, 1, 1, now())")
                .params(pcs, demoOrg, "FPCS-" + sfx()).update();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service',"
                + " false, 4, 10, ?, ?, now())").params(product, demoOrg, "FS-" + sfx(), "Field service " + sfx(), pcs, pcs)
                .update();
    }

    @AfterEach
    void dropCap() {
        if (capSetting != null) {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(capSetting).update();
            capSetting = null;
        }
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncReviewItem\" SET status = 'resolved' WHERE \"deviceId\" = ? AND status = 'open'")
                .param(deviceId).update();
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                + " 'investigating')").param(deviceId).update();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "FC-" + sfx(), "Field customer " + sfx())
                .update();
        return id;
    }

    Map<String, Object> op(String type, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", payload.containsKey("clientId") ? payload.get("clientId") : UUID.randomUUID().toString());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().minusSeconds(600).toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(String type, Map<String, Object> payload) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op(type, payload))), Map.of(
                "X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        JsonNode res = r.body().get("results").get(0);
        assertThat(res.get("status").asString()).as(res.toString()).isEqualTo("applied");
        return res;
    }

    Map<String, Object> orderPayload(String client, String partner, int qty, Object expectedTotal) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("clientId", client);
        p.put("partnerId", partner);
        // the device's unitPrice is ignored: the server prices the line
        p.put("lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 1, "expectedUnitPrice", 10)));
        if (expectedTotal != null) {
            p.put("expectedTotal", expectedTotal);
        }
        return p;
    }

    Map<String, Object> order(String id) {
        return jdbc.sql("SELECT status::text AS status, \"onHold\", \"holdReason\", total, \"recordedOffline\","
                + " \"occurredAt\", \"ownerId\" FROM \"SalesOrder\" WHERE id = ?").param(id).query().singleRow();
    }

    List<Map<String, Object>> reviews(String kind, String documentId) {
        return jdbc.sql("SELECT kind, status, reason, \"expectedAmount\", \"actualAmount\" FROM \"SyncReviewItem\" WHERE"
                + " kind = ? AND \"documentId\" = ?").params(kind, documentId).query().listOfRows();
    }

    @Test
    @DisplayName("salesOrder.create: priced by the server, within tolerance → confirmed, stamped as recorded offline")
    void orderConfirmed() {
        String c = customer();
        String client = UUID.randomUUID().toString();
        JsonNode r = push("salesOrder.create", orderPayload(client, c, 3, 30));
        String id = r.get("mapping").get(client).asString();
        assertThat(r.get("mapping").get("orderNumber").asString()).isNotBlank();
        assertThat(r.get("outcome").get("state").asString()).as(r.toString()).isEqualTo("confirmed");
        assertThat(r.get("outcome").get("total").asDouble()).isEqualTo(30.0);
        Map<String, Object> o = order(id);
        assertThat(o.get("status")).isEqualTo("confirmed");
        assertThat((BigDecimal) o.get("total")).isEqualByComparingTo("30");
        assertThat(o.get("recordedOffline")).isEqualTo(true);
        assertThat(o.get("occurredAt")).isNotNull();
        assertThat(o.get("ownerId")).isEqualTo(adminId);
    }

    @Test
    @DisplayName("price audit: a server total beyond the tolerance keeps the order as a draft with a price_variance item")
    void priceVarianceReviewed() {
        String c = customer();
        String client = UUID.randomUUID().toString();
        JsonNode r = push("salesOrder.create", orderPayload(client, c, 3, 24)); // rep saw 24, server says 30 (25 %)
        String id = r.get("mapping").get(client).asString();
        assertThat(r.get("outcome").get("state").asString()).isEqualTo("price_review");
        assertThat(order(id).get("status")).isEqualTo("draft");
        assertThat(reviews("price_variance", id)).singleElement().satisfies(v -> {
            assertThat((BigDecimal) v.get("expectedAmount")).isEqualByComparingTo("24");
            assertThat((BigDecimal) v.get("actualAmount")).isEqualByComparingTo("30");
        });

        JsonNode queue = ok(200, "GET", "/sync/sales-reviews?kind=price_variance&deviceId=" + deviceId, null);
        JsonNode item = null;
        for (JsonNode q : queue) {
            if (id.equals(q.get("documentId").asString())) {
                item = q;
            }
        }
        assertThat(item).as(queue.toString()).isNotNull();
        assertThat(item.get("partnerName").asString()).startsWith("Field customer");
        JsonNode resolved = ok(201, "POST", "/sync/sales-reviews/" + item.get("id").asString() + "/resolve", Map.of(
                "note", "Price list updated; confirmed with the customer"));
        assertThat(resolved.get("status").asString()).isEqualTo("resolved");
        assertThat(call("POST", "/sync/sales-reviews/" + item.get("id").asString() + "/resolve", Map.of("note", "again"),
                adminToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("credit: a customer on credit hold → the order is confirmed on hold, as online")
    void creditHoldPutsOrderOnHold() {
        String c = customer();
        jdbc.sql("UPDATE \"Partner\" SET \"creditHold\" = true WHERE id = ?").param(c).update();
        String client = UUID.randomUUID().toString();
        JsonNode r = push("salesOrder.create", orderPayload(client, c, 1, 10));
        String id = r.get("mapping").get(client).asString();
        assertThat(r.get("outcome").get("state").asString()).as(r.toString()).isIn("on_hold", "draft");
        Map<String, Object> o = order(id);
        if ("confirmed".equals(o.get("status"))) {
            assertThat(o.get("onHold")).isEqualTo(true);
        } else {
            assertThat(reviews("confirm_refused", id)).hasSize(1); // an org whose policy blocks: kept for review
        }
    }

    @Test
    @DisplayName("offline order cap: beyond the rep's cap the order is held with an offline_cap item")
    void offlineCapHolds() {
        capSetting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'sync.offlineOrderCap', '15'::jsonb, 'organization', now())").params(capSetting, demoOrg).update();
        // only this test's orders count: earlier offline orders of the admin move out of the window
        jdbc.sql("UPDATE \"SalesOrder\" SET \"occurredAt\" = now() - interval '30 days' WHERE \"recordedOffline\" AND"
                + " \"ownerId\" = ?").param(adminId).update();
        String c = customer();
        String first = UUID.randomUUID().toString();
        assertThat(push("salesOrder.create", orderPayload(first, c, 1, 10)).get("outcome").get("state").asString())
                .isEqualTo("confirmed");
        String second = UUID.randomUUID().toString();
        JsonNode r = push("salesOrder.create", orderPayload(second, c, 1, 10));
        String id = r.get("mapping").get(second).asString();
        assertThat(r.get("outcome").get("state").asString()).isEqualTo("on_hold");
        assertThat(r.get("outcome").get("reason").asString()).startsWith("Offline order cap exceeded");
        assertThat(order(id).get("onHold")).isEqualTo(true);
        assertThat(reviews("offline_cap", id)).hasSize(1);
        JsonNode pull = call("GET", "/sync/pull?scopes=taxes", null, Map.of("X-Device-Token", token)).body();
        assertThat(pull.get("offlinePolicy").get("offlineOrderCap").asDouble()).isEqualTo(15.0);
    }

    @Test
    @DisplayName("quote.create: priced by the server and submitted; beyond the tolerance it stays a draft")
    void quotes() {
        String c = customer();
        String client = UUID.randomUUID().toString();
        JsonNode r = push("quote.create", orderPayload(client, c, 2, 20));
        String id = r.get("mapping").get(client).asString();
        assertThat(r.get("outcome").get("state").asString()).as(r.toString()).isIn("approved", "pending_approval");
        assertThat(jdbc.sql("SELECT \"recordedOffline\" FROM \"SalesQuotation\" WHERE id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        String off = UUID.randomUUID().toString();
        JsonNode v = push("quote.create", orderPayload(off, c, 2, 100));
        assertThat(v.get("outcome").get("state").asString()).isEqualTo("price_review");
        assertThat(jdbc.sql("SELECT status::text FROM \"SalesQuotation\" WHERE id = ?").param(v.get("mapping").get(off)
                .asString()).query(String.class).single()).isEqualTo("draft");
    }

    String postedInvoice(String partner, int amount) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", amount)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    BigDecimal residual(String doc) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(doc).query(BigDecimal.class)
                .single();
    }

    @Test
    @DisplayName("payment.receive: the amount received is recorded; allocations apply up to what is open, the rest"
            + " stays on account for review; the reconciliation report shows it")
    void paymentAllocations() {
        String c = customer();
        String inv1 = postedInvoice(c, 100);
        String inv2 = postedInvoice(c, 50);
        // inv2 was settled online while the rep was offline
        Map<String, String> h = new LinkedHashMap<>(Map.of("Idempotency-Key", UUID.randomUUID().toString(),
                "Authorization", "Bearer " + adminToken));
        assertThat(call("POST", "/payments", Map.of("partnerId", c, "paymentDate", LocalDate.now().toString(), "amount", 50,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv2, "amount", 50))), h).status())
                .isEqualTo(200);

        String client = UUID.randomUUID().toString();
        JsonNode r = push("payment.receive", Map.of("clientId", client, "partnerId", c, "amount", 150, "paymentMethod",
                "cash", "reference", "Field receipt 17", "allocations", List.of(Map.of("documentId", inv1, "amount", 100),
                        Map.of("documentId", inv2, "amount", 50))));
        String paymentId = r.get("mapping").get(client).asString();
        assertThat(r.get("outcome").get("state").asString()).as(r.toString()).isEqualTo("partly_unallocated");
        assertThat(r.get("outcome").get("allocated").asDouble()).isEqualTo(100.0);
        Map<String, Object> pay = jdbc.sql("SELECT amount, \"allocatedAmount\", \"unallocatedAmount\", \"recordedOffline\""
                + " FROM \"Payment\" WHERE id = ?").param(paymentId).query().singleRow();
        assertThat((BigDecimal) pay.get("amount")).isEqualByComparingTo("150");
        assertThat((BigDecimal) pay.get("allocatedAmount")).isEqualByComparingTo("100");
        assertThat((BigDecimal) pay.get("unallocatedAmount")).isEqualByComparingTo("50");
        assertThat(pay.get("recordedOffline")).isEqualTo(true);
        assertThat(residual(inv1)).isEqualByComparingTo("0");
        assertThat(reviews("payment_allocation", paymentId)).singleElement().satisfies(v -> {
            assertThat((BigDecimal) v.get("expectedAmount")).isEqualByComparingTo("150");
            assertThat((BigDecimal) v.get("actualAmount")).isEqualByComparingTo("100");
        });

        JsonNode report = ok(200, "GET", "/sync/payment-reconciliation?userId=" + adminId + "&deviceId=" + deviceId, null);
        assertThat(report.size()).as(report.toString()).isGreaterThanOrEqualTo(1);
        JsonNode day = report.get(0);
        assertThat(day.get("received").asDouble()).isEqualTo(150.0);
        assertThat(day.get("allocated").asDouble()).isEqualTo(100.0);
        assertThat(day.get("requestedAllocation").asDouble()).isEqualTo(150.0);
        assertThat(day.get("onAccount").asDouble()).isEqualTo(50.0);
        assertThat(ok(400, "GET", "/sync/payment-reconciliation?from=2026-02-01&to=2026-01-01", null).toString())
                .contains("to must not be before from");
    }

    @Test
    @DisplayName("a payment in full against open invoices is simply posted (no review)")
    void paymentPosted() {
        String c = customer();
        String inv = postedInvoice(c, 40);
        String client = UUID.randomUUID().toString();
        JsonNode r = push("payment.receive", Map.of("clientId", client, "partnerId", c, "amount", 40, "paymentMethod",
                "cash", "allocations", List.of(Map.of("documentId", inv, "amount", 40))));
        assertThat(r.get("outcome").get("state").asString()).isEqualTo("posted");
        assertThat(residual(inv)).isEqualByComparingTo("0");
        String paymentId = r.get("mapping").get(client).asString();
        assertThat(reviews("payment_allocation", paymentId)).isEmpty();
        // the offline stamp is written once; payments stay evidence (guard_payment)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE \"Payment\" SET \"recordedOffline\" ="
                + " false WHERE id = ?").param(paymentId).update()).hasMessageContaining("offline stamp is immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE \"Payment\" SET amount = 1 WHERE id = ?")
                .param(paymentId).update()).hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("field-sales scopes come only when named; a default pull is unchanged")
    void salesScopes() {
        String c = customer();
        jdbc.sql("UPDATE \"Partner\" SET \"creditLimit\" = 1000 WHERE id = ?").param(c).update();
        postedInvoice(c, 70);
        Res named = call("GET", "/sync/pull?scopes=priceLists,paymentTerms,customerCredit,openSalesOrders,openInvoices",
                null, Map.of("X-Device-Token", token));
        assertThat(named.status()).as(named.raw().body()).isEqualTo(200);
        JsonNode data = named.body().get("data");
        for (String s : List.of("priceLists", "paymentTerms", "customerCredit", "openSalesOrders", "openInvoices")) {
            assertThat(data.has(s)).as(s).isTrue();
        }
        JsonNode credit = null;
        for (JsonNode r : data.get("customerCredit")) {
            if (c.equals(r.get("id").asString())) {
                credit = r;
            }
        }
        assertThat(credit).isNotNull();
        assertThat(credit.get("limit").asDouble()).isEqualTo(1000.0);
        assertThat(credit.get("exposure").asDouble()).isGreaterThanOrEqualTo(70.0);
        assertThat(data.get("openInvoices").toString()).contains(c);
        Res all = call("GET", "/sync/pull", null, Map.of("X-Device-Token", token));
        assertThat(all.body().get("data").has("customerCredit")).isFalse();
    }

    @Test
    @DisplayName("an offline order for an unknown customer is refused (dead letter), never half-created")
    void unknownCustomerRefused() {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op("salesOrder.create",
                orderPayload(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 10)))), Map.of(
                        "X-Device-Token", token));
        assertThat(r.body().get("results").get(0).get("status").asString()).isEqualTo("failed");
        assertThat(r.body().get("results").get(0).get("retryable").asBoolean()).isFalse();
    }
}
