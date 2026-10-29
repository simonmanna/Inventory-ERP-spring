package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetDeliveryInvoiceService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.3 — trips, delivery planning &amp; dispatch over HTTP: sales order → delivery order → loads split by payload /
 * volume → assignment with eligibility and capacity blocks → loaded quantity → dispatch issuing stock exactly once
 * (Idempotency-Key replay + concurrent dispatches) → delivery note PDF → POD with signature → shortage discrepancy →
 * approval (SoD) → billable quantity → customer invoice with the transport charge (posting does not issue twice);
 * posted-invoice source (stock mode invoiced) → approved shortage raises a draft credit note; returns put stock back;
 * transporter freight; weighbridge API; settings; tenant isolation; authorization matrix. Every posting is checked for
 * debits = credits.
 */
class FleetDispatchIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    FleetDispatchService dispatch;

    @Autowired
    FleetDeliveryInvoiceService deliveryInvoices;

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String today;
    String adminId;
    String tipper;
    String trailerClass;
    String costCenter;
    String yard;
    String aggregate;
    String service;
    String customer;
    String transporter;
    String site;
    String approverToken;
    String clerkToken;
    int seq;

    @BeforeAll
    void dispatchFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        tipper = category("TIPPER");
        trailerClass = category("TRAILER");
        costCenter = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, category, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'vehicle', now())").params(costCenter, demoOrg, "CC-D" + sfx, "Deliveries " + sfx)
                .update();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Quarry yard', 'storage_room', now())").params(yard, demoOrg, "YRD-" + sfx).update();
        aggregate = product("Aggregate 20mm", true);
        service = product("Cement bag (non-stock)", false);
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> {
                    stock.receive(StockMove.of(aggregate, yard, new BigDecimal("400")).unitCost(new BigDecimal("30000")));
                    deliveryInvoices.transportRevenueAccount();
                }));
        ok(200, "PUT", "/fleet/product-load-factors/" + aggregate, Map.of("tonnesPerUnit", 1, "notes", "sold by t"));
        customer = partner("Builder", false);
        transporter = partner("Haulier", true);
        site = ok(201, "POST", "/fleet/customer-sites", com.inventoryerp.kernel.common.Obj.of("partnerId", customer, "name", "Tower site " + sfx,
                "address", "Plot 4 Jinja Rd", "city", "Kampala", "latitude", 0.3136, "longitude", 32.5811,
                "contactName", "Site agent", "contactPhone", "+256700000001", "accessNotes", "Gate B, no trucks 7-9",
                "zone", "Kampala " + sfx, "distanceKm", 42)).get("id").asString();
        approverToken = user("Dispatch manager", "fleet_dispatch:read", "fleet_dispatch:approve_discrepancy",
                "fleet_trip:read");
        clerkToken = user("Dispatch clerk", "fleet_dispatch:read", "fleet_trip:read");
    }

    // ------------------------------------------------------------------ helpers

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    String product(String name, boolean tracked) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"ProductType\","
                        + " ?, 'AVCO'::\"CostingMethod\", 0, 45000, now())")
                .params(id, demoOrg, "DSP-" + sfx + "-" + (++seq), name + " " + sfx, tracked ? "stockable" : "service",
                        tracked).update();
        return id;
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name + " " + sfx, supplier, !supplier).update();
        return id;
    }

    String user(String roleName, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, roleName + " " + sfx + role.substring(0, 4),
                        permissions).update();
        String email = "dsp-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Disp@12345", role);
        return login("DEMO", email, "Disp@12345");
    }

    String vehicle(String categoryId, Object payloadT, Object volumeM3) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("registrationPlate", "UDS " + sfx + "-" + (++seq));
        body.put("costCenterId", costCenter);
        if (payloadT != null) {
            body.put("payloadCapacityT", payloadT);
        }
        if (volumeM3 != null) {
            body.put("bodyVolumeM3", volumeM3);
        }
        if (categoryId.equals(tipper)) {
            body.put("openingOdometer", 1000);
        }
        return ok(201, "POST", "/fleet/vehicles", body).get("id").asString();
    }

    String driver(LocalDate licenceExpiresOn) {
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Driver", "lastName", sfx + "-" + (++seq),
                "baseSalary", 500000)).get("id").asString();
        return ok(201, "POST", "/fleet/drivers", Map.of("employeeId", emp, "licenceNumber", "DL-" + sfx + "-" + seq,
                "licenceExpiresOn", licenceExpiresOn.toString(), "qualifiedCategoryIds", List.of(tipper)))
                .get("id").asString();
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, productId, yard).query(BigDecimal.class)
                .single();
    }

    void assertBalanced(String entryId) {
        Map<String, Object> sums = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        assertThat((BigDecimal) sums.get("d")).isPositive();
        assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo((BigDecimal) sums.get("c"));
    }

    List<String> entries(String sourceType, String sourceId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND"
                + " \"sourceId\" = ?").params(demoOrg, sourceType, sourceId).query(String.class).list();
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(String.class).single();
    }

    /** Manual delivery order of {@code qty} t of aggregate to the tower site. */
    JsonNode manualOrder(Object qty) {
        return ok(201, "POST", "/fleet/delivery-orders", Map.of("partnerId", customer, "siteId", site, "locationId",
                yard, "requestedDate", today, "windowFrom", "07:00", "windowTo", "16:00", "lines",
                List.of(Map.of("productId", aggregate, "quantity", qty, "unitPrice", 45000))));
    }

    /** One planned trip on a 20 t tipper with a valid driver; returns the trip id. */
    String plannedTrip(JsonNode order, String vehicleId, String driverId) {
        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + order.get("id").asString() + "/plan-loads",
                Map.of("vehicleId", vehicleId, "driverId", driverId));
        return planned.get("createdTripIds").get(0).asString();
    }

    JsonNode line(JsonNode trip, int i) {
        return trip.get("lines").get(i);
    }

    Map<String, Object> pod(JsonNode trip, Object delivered, Object returned, String reason) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("loadLineId", line(trip, 0).get("id").asString());
        l.put("deliveredQty", delivered);
        if (returned != null) {
            l.put("returnedQty", returned);
        }
        if (reason != null) {
            l.put("reason", reason);
        }
        return new LinkedHashMap<>(Map.of("receiverName", "Site agent", "receiverPhone", "+256700000001", "latitude",
                0.3137, "longitude", 32.5812, "signature", PNG, "lines", List.of(l)));
    }

    // ------------------------------------------------------------------ end to end

    @Test
    @DisplayName("sales order → DO → 3 loads by payload → eligibility / capacity blocks → weighed → dispatch once →"
            + " delivery note → POD shortage → SoD approval → close → short-close → invoice + transport charge")
    void salesOrderToInvoice() {
        String order = ok(200, "POST", "/orders", Map.of("partnerId", customer, "orderType", "delivery", "lines",
                List.of(Map.of("productId", aggregate, "quantity", 50, "unitPrice", 45000)))).get("id").asString();
        JsonNode doc = ok(201, "POST", "/fleet/delivery-orders/from-sales-order", Map.of("sourceId", order, "siteId",
                site, "locationId", yard, "requestedDate", today));
        String doId = doc.get("id").asString();
        assertThat(doc.get("code").asString()).startsWith("DO-");
        assertThat(doc.get("sourceType").asString()).isEqualTo("sales_order");
        assertThat(doc.get("stockMode").asString()).isEqualTo("dispatch");
        assertThat(doc.get("status").asString()).isEqualTo("open");
        assertThat(doc.get("contactName").asString()).isEqualTo("Site agent");
        assertThat(dec(doc.get("lines").get(0).get("orderedQty"))).isEqualByComparingTo("50");
        assertThat(dec(doc.get("lines").get(0).get("tonnesPerUnit"))).isEqualByComparingTo("1");
        Res dup = as("POST", "/fleet/delivery-orders/from-sales-order", Map.of("sourceId", order));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(msg(dup)).startsWith("Sales order ").contains("already has delivery order " + doc.get("code")
                .asString());
        assertThat(ok(200, "GET", "/fleet/dispatch/sources?type=sales_order", null).toString()).doesNotContain(order);
        // The DO holds its own copy of the lines: close the confirmed order so POS shift-close suites (shared DEMO
        // organization) do not see an open order.
        ok(200, "POST", "/orders/" + order + "/cancel", Map.of("reason", "Delivered through fleet dispatch"));

        String truck = vehicle(tipper, 20, 14);
        String small = vehicle(tipper, 10, 8);
        String good = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        String expired = driver(LocalDate.now(BusinessZone.get()).minusDays(1));

        JsonNode preview = ok(200, "GET", "/fleet/delivery-orders/" + doId + "/load-plan?vehicleId=" + truck, null);
        assertThat(preview.get("loads")).hasSize(3);
        assertThat(dec(preview.get("loads").get(2).get("tonnes"))).isEqualByComparingTo("10");

        Res blocked = as("POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("vehicleId", truck,
                "driverId", expired));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).contains("driving licence expired on");
        assertThat(ok(200, "GET", "/fleet/delivery-orders/" + doId, null).get("trips")).isEmpty();

        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("vehicleId", truck,
                "driverId", good));
        assertThat(planned.get("status").asString()).isEqualTo("planned");
        assertThat(planned.get("createdTripIds")).hasSize(3);
        String t1 = planned.get("createdTripIds").get(0).asString();
        String t2 = planned.get("createdTripIds").get(1).asString();
        String t3 = planned.get("createdTripIds").get(2).asString();
        JsonNode trip1 = ok(200, "GET", "/fleet/trips/" + t1, null);
        assertThat(dec(trip1.get("plannedTonnes"))).isEqualByComparingTo("20");
        assertThat(trip1.get("vehicleId").asString()).isEqualTo(truck);
        assertThat(trip1.get("costCenterId").asString()).isEqualTo(costCenter);

        // Capacity and eligibility blocks on (re-)assignment.
        Res tooSmall = as("POST", "/fleet/trips/" + t1 + "/assign", Map.of("carrier", "own", "vehicleId", small,
                "driverId", good));
        assertThat(tooSmall.status()).isEqualTo(400);
        assertThat(msg(tooSmall)).contains("carries 20 t but").endsWith("split the load");
        String broken = vehicle(tipper, 30, 20);
        ok(201, "POST", "/fleet/vehicles/" + broken + "/status", Map.of("status", "breakdown", "reason", "Gearbox"));
        Res down = as("POST", "/fleet/trips/" + t1 + "/assign", Map.of("carrier", "own", "vehicleId", broken,
                "driverId", good));
        assertThat(down.status()).isEqualTo(400);
        assertThat(msg(down)).contains("cannot be dispatched");
        JsonNode check = ok(200, "GET", "/fleet/trips/" + t1 + "/assignment-check?vehicleId=" + broken + "&driverId="
                + expired, null);
        assertThat(check.get("eligible").asBoolean()).isFalse();
        assertThat(check.get("reasons").size()).isGreaterThanOrEqualTo(2);
        Res trailerAsTruck = as("POST", "/fleet/trips/" + t1 + "/assign", Map.of("carrier", "own", "vehicleId",
                vehicle(trailerClass, 30, null), "driverId", good));
        assertThat(msg(trailerAsTruck)).endsWith("is a trailer; choose the truck that pulls it");

        // Loading → weighed (manual loaded quantity) → dispatch (stock exactly once).
        JsonNode loading = ok(201, "POST", "/fleet/trips/" + t1 + "/start-loading", Map.of("odometer", 1010));
        assertThat(loading.get("status").asString()).isEqualTo("loading");
        assertThat(dec(loading.get("startOdometer"))).isEqualByComparingTo("1010");
        JsonNode weighed = ok(201, "POST", "/fleet/trips/" + t1 + "/loaded-quantity", Map.of("lines", List.of(
                Map.of("loadLineId", line(loading, 0).get("id").asString(), "quantity", 19.5))));
        assertThat(weighed.get("status").asString()).isEqualTo("weighed");
        assertThat(dec(weighed.get("loadedTonnes"))).isEqualByComparingTo("19.5");
        BigDecimal before = onHand(aggregate);
        Res noKey = as("POST", "/fleet/trips/" + t1 + "/dispatch", null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(msg(noKey)).startsWith("An Idempotency-Key header is required");
        Map<String, String> k = key(adminToken);
        Res first = call("POST", "/fleet/trips/" + t1 + "/dispatch", null, k);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(first.body().get("status").asString()).isEqualTo("dispatched");
        assertThat(first.body().get("deliveryNoteNumber").asString()).startsWith("DN-");
        assertThat(dec(line(first.body(), 0).get("dispatchedQty"))).isEqualByComparingTo("19.5");
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("19.5")));
        Res replay = call("POST", "/fleet/trips/" + t1 + "/dispatch", null, k);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("deliveryNoteNumber").asString())
                .isEqualTo(first.body().get("deliveryNoteNumber").asString());
        Res again = call("POST", "/fleet/trips/" + t1 + "/dispatch", null, key(adminToken));
        assertThat(again.status()).isEqualTo(400);
        assertThat(msg(again)).isEqualTo("Trip " + first.body().get("code").asString()
                + " is dispatched and cannot be dispatched");
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("19.5")));
        List<String> issueEntries = entries("fleet_trip", t1);
        assertThat(issueEntries).hasSize(1);
        assertBalanced(issueEntries.getFirst());
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'fleet_trip' AND"
                + " \"referenceId\" = ?").param(t1).query(Long.class).single()).isEqualTo(1);
        // Loaded 0.5 t less than planned: that quantity is plannable again.
        JsonNode afterDispatch = ok(200, "GET", "/fleet/delivery-orders/" + doId, null);
        assertThat(afterDispatch.get("status").asString()).isEqualTo("in_progress");
        assertThat(dec(afterDispatch.get("lines").get(0).get("remainingQty"))).isEqualByComparingTo("0.5");
        assertThat(dec(afterDispatch.get("lines").get(0).get("dispatchedQty"))).isEqualByComparingTo("19.5");

        // Delivery note PDF.
        HttpResponse<byte[]> pdf = pdf("/fleet/trips/" + t1 + "/delivery-note");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(new String(pdf.body(), 0, 4)).isEqualTo("%PDF");
        assertThat(ok(200, "GET", "/fleet/trips/" + t1, null).get("deliveryNotePrints").asInt()).isEqualTo(1);

        // In transit → POD with a 0.5 t shortage → pending discrepancy.
        assertThat(ok(201, "POST", "/fleet/trips/" + t1 + "/depart", null).get("status").asString())
                .isEqualTo("in_transit");
        Res noReason = call("POST", "/fleet/trips/" + t1 + "/proof-of-delivery", pod(first.body(), 19, null, null),
                key(adminToken));
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(msg(noReason)).startsWith("A reason is required for the difference on Aggregate 20mm");
        JsonNode delivered = money("/fleet/trips/" + t1 + "/proof-of-delivery", pod(first.body(), 19, null,
                "spillage"));
        assertThat(delivered.get("status").asString()).isEqualTo("delivered");
        assertThat(delivered.get("pod").get("receiverName").asString()).isEqualTo("Site agent");
        assertThat(delivered.get("pod").get("locationSource").asString()).isEqualTo("device");
        assertThat(delivered.get("pod").get("signatureUrl").asString()).isNotBlank();
        assertThat(dec(delivered.get("deliveredTonnes"))).isEqualByComparingTo("19");
        JsonNode disc = delivered.get("discrepancies").get(0);
        assertThat(disc.get("kind").asString()).isEqualTo("shortage");
        assertThat(disc.get("status").asString()).isEqualTo("pending");
        assertThat(dec(disc.get("differenceQty"))).isEqualByComparingTo("0.5");
        String discId = disc.get("id").asString();

        // The recorder cannot approve; the dispatch manager can.
        Res self = call("POST", "/fleet/delivery-discrepancies/" + discId + "/approve", Map.of(), key(adminToken));
        assertThat(self.status()).isEqualTo(403);
        assertThat(msg(self)).isEqualTo("The person who recorded a discrepancy cannot approve or reject it");
        assertThat(call("POST", "/fleet/delivery-discrepancies/" + discId + "/reject", Map.of(), approverToken)
                .status()).isEqualTo(400);
        Res approved = call("POST", "/fleet/delivery-discrepancies/" + discId + "/approve",
                Map.of("notes", "Spillage on the Jinja road"), key(approverToken));
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("approved");
        assertThat(approved.body().get("creditNoteId").isNull()).isTrue();
        assertThat(dec(line(ok(200, "GET", "/fleet/trips/" + t1, null), 0).get("billableQty")))
                .isEqualByComparingTo("19");

        // Close: distance from the odometer, meter readings via FleetMeterService (source trip).
        JsonNode closed = ok(201, "POST", "/fleet/trips/" + t1 + "/close", Map.of("endOdometer", 1052));
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(dec(closed.get("distanceKm"))).isEqualByComparingTo("42");
        assertThat(closed.get("distanceSource").asString()).isEqualTo("odometer");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source = 'trip'")
                .param(truck).query(Long.class).single()).isEqualTo(2);

        // Trips 2 and 3 straight from planned to dispatched, delivered in full, closed.
        for (String t : List.of(t2, t3)) {
            JsonNode d = money("/fleet/trips/" + t + "/dispatch", null);
            money("/fleet/trips/" + t + "/proof-of-delivery", pod(d, dec(line(d, 0).get("dispatchedQty")), null,
                    null));
            ok(201, "POST", "/fleet/trips/" + t + "/close", Map.of("distanceKm", 41));
        }
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("49.5")));
        JsonNode open = ok(200, "GET", "/fleet/delivery-orders/" + doId, null);
        assertThat(open.get("status").asString()).isEqualTo("in_progress");
        Res earlyInvoice = call("POST", "/fleet/delivery-orders/" + doId + "/invoice", null, key(adminToken));
        assertThat(msg(earlyInvoice)).endsWith("invoice it once its trips are delivered");

        // Board shows today's trips.
        JsonNode board = ok(200, "GET", "/fleet/dispatch-board?from=" + today + "&to=" + today + "&partnerId="
                + customer, null);
        assertThat(board.get("days").get(0).get("trips").toString()).contains(t1, t2, t3);

        // Short-close the 0.5 t the customer no longer needs; invoice delivered goods + transport charge.
        ok(201, "POST", "/fleet/delivery-charge-rules", Map.of("name", "Per tonne " + sfx, "partnerId", customer,
                "basis", "per_tonne", "rate", 2000));
        JsonNode closedOrder = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/close", Map.of("reason",
                "Customer accepts 49 t"));
        assertThat(closedOrder.get("status").asString()).isEqualTo("closed");
        JsonNode charge = ok(200, "GET", "/fleet/delivery-orders/" + doId + "/delivery-charge", null);
        assertThat(dec(charge.get("charge").get("amount"))).isEqualByComparingTo("98000");
        Res invNoKey = as("POST", "/fleet/delivery-orders/" + doId + "/invoice", null);
        assertThat(invNoKey.status()).isEqualTo(400);
        JsonNode invoiced = money("/fleet/delivery-orders/" + doId + "/invoice", null);
        String invoiceId = invoiced.get("invoice").get("id").asString();
        assertThat(invoiced.get("goodsInvoiceId").asString()).isEqualTo(invoiceId);
        assertThat(invoiced.get("chargesInvoiceId").asString()).isEqualTo(invoiceId);
        assertThat(invoiced.get("charges")).hasSize(1);
        JsonNode inv = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(inv.get("status").asString()).isEqualTo("draft");
        assertThat(inv.get("lines")).hasSize(2);
        for (JsonNode l : inv.get("lines")) {
            if (l.path("productId").isNull() || l.path("productId").isMissingNode()) {
                assertThat(dec(l.get("subtotal"))).isEqualByComparingTo("98000");
                assertThat(l.get("accountId").asString()).isEqualTo(mapped("fleet_transport_revenue"));
            } else {
                assertThat(l.get("productId").asString()).isEqualTo(aggregate);
                assertThat(dec(l.get("quantity"))).isEqualByComparingTo("49");
            }
        }
        assertThat(msg(call("POST", "/fleet/delivery-orders/" + doId + "/invoice", null, key(adminToken))))
                .isEqualTo("Nothing to invoice on delivery order " + doc.get("code").asString());

        // Posting the invoice: AR / revenue + transport revenue; the goods are NOT issued a second time.
        BigDecimal beforePost = onHand(aggregate);
        JsonNode posted = ok(200, "POST", "/invoices/" + invoiceId + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertBalanced(posted.get("journalEntryId").asString());
        assertThat(jdbc.sql("SELECT COALESCE(SUM(credit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                + " \"accountId\" = ?").params(posted.get("journalEntryId").asString(), mapped("fleet_transport_revenue"))
                .query(BigDecimal.class).single()).isEqualByComparingTo("98000");
        assertThat(onHand(aggregate)).isEqualByComparingTo(beforePost);
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'sales_invoice' AND"
                + " \"referenceId\" = ?").param(invoiceId).query(Long.class).single()).isZero();
    }

    HttpResponse<byte[]> pdf(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + adminToken).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("posted invoice source: no stock at dispatch; approved shortage → draft credit note (no auto-post);"
            + " transporter freight per trip; draft invoice source hands its stock issue to the delivery")
    void invoicedSourceAndCreditNote() {
        String invoice = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", service, "quantity", 10, "unitPrice", 30000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + invoice + "/post", null);
        JsonNode doc = ok(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", invoice, "siteId",
                site, "locationId", yard));
        String doId = doc.get("id").asString();
        assertThat(doc.get("stockMode").asString()).isEqualTo("invoiced");
        assertThat(doc.get("goodsInvoiceId").asString()).isEqualTo(invoice);
        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("payloadT", 20));
        assertThat(planned.get("warnings").toString()).contains("No weight or volume data");
        String trip = planned.get("createdTripIds").get(0).asString();
        Res supplierOnly = as("POST", "/fleet/transporter-rates", Map.of("partnerId", customer, "name", "x",
                "basis", "per_trip", "rate", 1));
        assertThat(msg(supplierOnly)).isEqualTo("The transporter must be a supplier partner");
        String rate = ok(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name",
                "Kampala run " + sfx, "basis", "per_trip", "rate", 150000)).get("id").asString();
        Res notAssigned = call("POST", "/fleet/trips/" + trip + "/dispatch", null, key(adminToken));
        assertThat(msg(notAssigned)).startsWith("Assign a truck and a driver to trip");
        JsonNode assigned = ok(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter",
                "transporterPartnerId", transporter, "transporterRateId", rate, "transporterPlate", "uax 123b",
                "transporterDriverName", "Okello"));
        assertThat(assigned.get("carrier").asString()).isEqualTo("transporter");
        assertThat(assigned.get("transporterPlate").asString()).isEqualTo("UAX 123B");
        JsonNode d = money("/fleet/trips/" + trip + "/dispatch", null);
        assertThat(line(d, 0).get("stockLedgerCode").isNull()).isTrue();
        assertThat(entries("fleet_trip", trip)).isEmpty();
        JsonNode delivered = money("/fleet/trips/" + trip + "/proof-of-delivery", pod(d, 8, null, "damaged"));
        String discId = delivered.get("discrepancies").get(0).get("id").asString();
        assertThat(ok(200, "GET", "/fleet/delivery-discrepancies?status=pending&deliveryOrderId=" + doId, null)
                .get("meta").get("total").asLong()).isEqualTo(1);
        JsonNode approved = call("POST", "/fleet/delivery-discrepancies/" + discId + "/approve", null,
                key(approverToken)).body();
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        String cn = approved.get("creditNoteId").asString();
        assertThat(approved.get("creditNoteNumber").asString()).startsWith("CN-");
        assertThat(dec(approved.get("creditAmount"))).isEqualByComparingTo("60000");
        Map<String, Object> cnRow = jdbc.sql("SELECT status::text AS status, \"reversedDocumentId\", \"partnerId\" FROM"
                + " \"Document\" WHERE id = ?").param(cn).query().singleRow();
        assertThat(cnRow.get("status")).isEqualTo("draft");
        assertThat(cnRow.get("reversedDocumentId")).isEqualTo(invoice);
        assertThat(cnRow.get("partnerId")).isEqualTo(customer);
        JsonNode closed = ok(201, "POST", "/fleet/trips/" + trip + "/close", null);
        assertThat(dec(closed.get("freightAmount"))).isEqualByComparingTo("150000");
        assertThat(closed.get("distanceSource").asString()).isEqualTo("site");
        // Goods already invoiced: the delivery-order invoice carries only the transport charge (none configured here).
        JsonNode charge = ok(200, "GET", "/fleet/delivery-orders/" + doId + "/delivery-charge", null);
        if (charge.get("charge").isNull()) {
            assertThat(msg(call("POST", "/fleet/delivery-orders/" + doId + "/invoice", null, key(adminToken))))
                    .startsWith("Nothing to invoice");
        }

        // Draft invoice of tracked goods: the delivery takes over the stock issue; cancelling frees the invoice.
        String draft = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", aggregate, "quantity", 5, "unitPrice", 45000)))).get("id").asString();
        JsonNode fromDraft = ok(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", draft,
                "locationId", yard));
        assertThat(fromDraft.get("stockMode").asString()).isEqualTo("dispatch");
        assertThat(jdbc.sql("SELECT data->>'stockIssuedBy' FROM \"Document\" WHERE id = ?").param(draft)
                .query(String.class).single()).isEqualTo(fromDraft.get("code").asString());
        ok(201, "POST", "/fleet/delivery-orders/" + fromDraft.get("id").asString() + "/cancel", Map.of("reason",
                "Customer collects"));
        assertThat(jdbc.sql("SELECT data->>'stockIssuedBy' FROM \"Document\" WHERE id = ?").param(draft)
                .query(String.class).optional().orElse(null)).isNull();
        JsonNode again = ok(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", draft,
                "locationId", yard));
        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        String t = plannedTrip(again, truck, driver);
        BigDecimal before = onHand(aggregate);
        money("/fleet/trips/" + t + "/dispatch", null);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("5")));
        ok(200, "POST", "/invoices/" + draft + "/post", null);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("5")));
    }

    @Test
    @DisplayName("returns: a returned load puts the stock back at its issue cost (balanced COGS reversal), the"
            + " quantity is plannable again and a returned discrepancy is raised; partial return at POD; cancel")
    void returnsPutStockBack() {
        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        JsonNode order = manualOrder(30);
        String doId = order.get("id").asString();
        JsonNode planned = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of("vehicleId", truck,
                "driverId", driver));
        String t1 = planned.get("createdTripIds").get(0).asString();
        String t2 = planned.get("createdTripIds").get(1).asString();
        BigDecimal before = onHand(aggregate);
        money("/fleet/trips/" + t1 + "/dispatch", null);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("20")));
        Res cancelDispatched = as("POST", "/fleet/trips/" + t1 + "/cancel", Map.of("reason", "x"));
        assertThat(msg(cancelDispatched)).endsWith("is dispatched and cannot be cancelled");
        JsonNode returned = money("/fleet/trips/" + t1 + "/return", Map.of("reason", "Site closed by police",
                "reasonCode", "site_closed"));
        assertThat(returned.get("status").asString()).isEqualTo("returned");
        assertThat(onHand(aggregate)).isEqualByComparingTo(before);
        List<String> back = entries("fleet_trip_return", t1);
        assertThat(back).hasSize(1);
        assertBalanced(back.getFirst());
        assertThat(returned.get("discrepancies").get(0).get("kind").asString()).isEqualTo("returned");
        assertThat(returned.get("discrepancies").get(0).get("reason").asString()).isEqualTo("site_closed");
        JsonNode o = ok(200, "GET", "/fleet/delivery-orders/" + doId, null);
        assertThat(dec(o.get("lines").get(0).get("returnedQty"))).isEqualByComparingTo("20");
        assertThat(dec(o.get("lines").get(0).get("remainingQty"))).isEqualByComparingTo("20");

        // Partial return at POD: 2 t back to the yard at once.
        JsonNode d2 = money("/fleet/trips/" + t2 + "/dispatch", null);
        BigDecimal mid = onHand(aggregate);
        JsonNode pod2 = money("/fleet/trips/" + t2 + "/proof-of-delivery", pod(d2, 8, 2, "customer_rejected"));
        assertThat(onHand(aggregate)).isEqualByComparingTo(mid.add(new BigDecimal("2")));
        assertThat(pod2.get("discrepancies")).hasSize(1);
        assertThat(pod2.get("discrepancies").get(0).get("kind").asString()).isEqualTo("returned");

        // A planned trip cancelled: its quantity is plannable again.
        JsonNode extra = ok(201, "POST", "/fleet/delivery-orders/" + doId + "/trips", Map.of("lines", List.of(Map.of(
                "deliveryOrderLineId", o.get("lines").get(0).get("id").asString(), "quantity", 5))));
        Res tooMuch = as("POST", "/fleet/delivery-orders/" + doId + "/trips", Map.of("lines", List.of(Map.of(
                "deliveryOrderLineId", o.get("lines").get(0).get("id").asString(), "quantity", 100))));
        assertThat(msg(tooMuch)).startsWith("Only ").endsWith("remain to be planned");
        ok(201, "POST", "/fleet/trips/" + extra.get("id").asString() + "/cancel", Map.of("reason", "Truck needed"));
        assertThat(dec(ok(200, "GET", "/fleet/delivery-orders/" + doId, null).get("lines").get(0).get("remainingQty")))
                .isEqualByComparingTo("22");
        Res cancelOrder = as("POST", "/fleet/delivery-orders/" + doId + "/cancel", Map.of("reason", "x"));
        assertThat(msg(cancelOrder)).contains("has dispatched trips");
    }

    @Test
    @DisplayName("concurrency: five simultaneous dispatches of one trip (different keys) issue the stock once")
    void concurrentDispatch() throws Exception {
        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        String trip = plannedTrip(manualOrder(15), truck, driver);
        BigDecimal before = onHand(aggregate);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return call("POST", "/fleet/trips/" + trip + "/dispatch", null, key(adminToken));
            }));
        }
        start.countDown();
        int okCount = 0;
        for (Future<Res> f : futures) {
            Res r = f.get();
            if (r.status() == 200) {
                okCount++;
            } else {
                assertThat(r.status()).as(r.raw().body()).isIn(400, 409);
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(1);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("15")));
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'fleet_trip' AND"
                + " \"referenceId\" = ?").param(trip).query(Long.class).single()).isEqualTo(1);
        assertThat(entries("fleet_trip", trip)).hasSize(1);
    }

    @Test
    @DisplayName("weighbridge API: recordWeighedQuantity (kg → t, idempotent per ticket, re-weigh) drives dispatch")
    void weighedQuantityApi() {
        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        JsonNode order = manualOrder(20);
        String trip = plannedTrip(order, truck, driver);
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> dispatch.recordWeighedQuantity(trip, new BigDecimal("18400"), "kg", "weighbridge_ticket", "WBT-1")));
        JsonNode w = ok(200, "GET", "/fleet/trips/" + trip, null);
        assertThat(w.get("status").asString()).isEqualTo("weighed");
        assertThat(dec(line(w, 0).get("loadedQty"))).isEqualByComparingTo("18.4");
        assertThat(w.get("weighSourceId").asString()).isEqualTo("WBT-1");
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> {
                    dispatch.recordWeighedQuantity(trip, new BigDecimal("1"), "kg", "weighbridge_ticket", "WBT-1");
                    dispatch.recordWeighedQuantity(trip, new BigDecimal("18.6"), "t", "weighbridge_ticket", "WBT-2");
                }));
        JsonNode re = ok(200, "GET", "/fleet/trips/" + trip, null);
        assertThat(dec(line(re, 0).get("loadedQty"))).isEqualByComparingTo("18.6");
        assertThat(re.get("weighSourceId").asString()).isEqualTo("WBT-2");
        BigDecimal before = onHand(aggregate);
        money("/fleet/trips/" + trip + "/dispatch", null);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("18.6")));
        assertThat(dec(ok(200, "GET", "/fleet/delivery-orders/" + order.get("id").asString(), null).get("lines").get(0)
                .get("remainingQty"))).isEqualByComparingTo("1.4");
    }

    @Test
    @DisplayName("customer sites, settings, validation; driver trips (mine); claim / check-out trip FK")
    void sitesSettingsAndValidation() {
        JsonNode s = ok(201, "POST", "/fleet/customer-sites", Map.of("partnerId", customer, "name", "Depot " + sfx));
        assertThat(s.get("code").asString()).startsWith("SITE-");
        Res half = as("POST", "/fleet/customer-sites", Map.of("partnerId", customer, "name", "x", "latitude", 1));
        assertThat(msg(half)).isEqualTo("Give both latitude and longitude, or neither");
        JsonNode patched = ok(200, "PATCH", "/fleet/customer-sites/" + s.get("id").asString(), Map.of("zone", "North",
                "distanceKm", 120));
        assertThat(patched.get("zone").asString()).isEqualTo("North");
        assertThat(ok(200, "GET", "/fleet/customer-sites?partnerId=" + customer, null).get("meta").get("total")
                .asLong()).isGreaterThanOrEqualTo(2);
        assertThat(ok(200, "DELETE", "/fleet/customer-sites/" + s.get("id").asString(), null).get("deleted")
                .asBoolean()).isTrue();
        manualOrder(1);
        assertThat(ok(200, "DELETE", "/fleet/customer-sites/" + site, null).get("deactivated").asBoolean()).isTrue();
        ok(200, "PATCH", "/fleet/customer-sites/" + site, Map.of("isActive", true));
        Res otherCustomerSite = as("POST", "/fleet/delivery-orders", Map.of("partnerId", partner("Other", false),
                "siteId", site, "locationId", yard, "requestedDate", today, "lines", List.of(Map.of("productId",
                        aggregate, "quantity", 1))));
        assertThat(msg(otherCustomerSite)).endsWith("belongs to another customer");
        Res window = as("POST", "/fleet/delivery-orders", Map.of("partnerId", customer, "locationId", yard,
                "requestedDate", today, "windowFrom", "16:00", "windowTo", "08:00", "lines", List.of(Map.of(
                        "productId", aggregate, "quantity", 1))));
        assertThat(msg(window)).isEqualTo("The delivery window must end after it starts");
        assertThat(validationMessages(as("POST", "/fleet/delivery-orders", Map.of("partnerId", customer,
                "requestedDate", today, "windowFrom", "25:00", "lines", List.of())))).isNotEmpty();

        JsonNode factor = ok(200, "GET", "/fleet/product-load-factors/" + aggregate, null);
        assertThat(factor.get("source").asString()).isEqualTo("fleet_factor");
        assertThat(ok(200, "GET", "/fleet/product-load-factors", null).toString()).contains(aggregate);
    }

    @Test
    @DisplayName("charge rule band validation, rate / rule CRUD, trip FK on claims, driver phone list")
    void settingsCrudAndTripFk() {
        Res band = as("POST", "/fleet/delivery-charge-rules", Map.of("name", "Bad band", "basis", "per_km", "rate",
                3000, "minDistanceKm", 100, "maxDistanceKm", 50));
        assertThat(msg(band)).isEqualTo("maxDistanceKm must be at least minDistanceKm");
        String rule = ok(201, "POST", "/fleet/delivery-charge-rules", Map.of("name", "Zone N " + sfx, "zone", "North",
                "basis", "per_km", "rate", 3000)).get("id").asString();
        assertThat(ok(200, "PATCH", "/fleet/delivery-charge-rules/" + rule, Map.of("rate", 3500)).get("rate")
                .asString()).startsWith("3500");
        assertThat(ok(200, "DELETE", "/fleet/delivery-charge-rules/" + rule, null).get("deleted").asBoolean()).isTrue();
        String rate = ok(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name", "Per t "
                + sfx, "basis", "per_tonne", "rate", 9000)).get("id").asString();
        assertThat(ok(200, "GET", "/fleet/transporter-rates?partnerId=" + transporter, null).toString()).contains(rate);
        assertThat(ok(200, "DELETE", "/fleet/transporter-rates/" + rate, null).get("deleted").asBoolean()).isTrue();
        ok(200, "PUT", "/fleet/product-load-factors/" + service, Map.of("cubicMetresPerUnit", 0.02));
        assertThat(ok(200, "DELETE", "/fleet/product-load-factors/" + service, null).get("deleted").asBoolean())
                .isTrue();
        assertThat(as("DELETE", "/fleet/product-load-factors/" + service, null).status()).isEqualTo(404);

        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        String trip = plannedTrip(manualOrder(5), truck, driver);
        Res badTrip = as("POST", "/fleet/claims", Map.of("driverId", driver, "claimDate", today, "tripId", "nope"));
        assertThat(badTrip.status()).isEqualTo(400);
        assertThat(msg(badTrip)).isEqualTo("Trip not found");
        JsonNode claim = ok(201, "POST", "/fleet/claims", Map.of("driverId", driver, "vehicleId", truck, "claimDate",
                today, "tripId", trip));
        assertThat(claim.get("tripId").asString()).isEqualTo(trip);
        assertThat(ok(200, "GET", "/fleet/trips?mine=true", null).get("meta").get("total").asLong()).isZero();
        assertThat(ok(200, "GET", "/fleet/trips?status=open&vehicleId=" + truck, null).get("meta").get("total")
                .asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees and touches none of DEMO's dispatch records")
    void tenantIsolation() {
        String truck = vehicle(tipper, 20, 14);
        String driver = driver(LocalDate.now(BusinessZone.get()).plusYears(1));
        JsonNode order = manualOrder(5);
        String doId = order.get("id").asString();
        String trip = plannedTrip(order, truck, driver);
        assertThat(call("GET", "/fleet/delivery-orders/" + doId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/trips/" + trip, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/customer-sites/" + site, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/trips/" + trip + "/dispatch", null, key(otherToken)).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/delivery-orders/" + doId + "/plan-loads", Map.of(), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/delivery-orders?search=" + order.get("code").asString(), null, otherToken)
                .body().get("meta").get("total").asLong()).isZero();
        assertThat(call("GET", "/fleet/trips", null, otherToken).body().toString()).doesNotContain(trip);
        assertThat(call("GET", "/fleet/dispatch/lookups", null, otherToken).body().toString()).doesNotContain(truck);
        Res foreign = call("POST", "/fleet/delivery-orders", Map.of("partnerId", customer, "requestedDate", today,
                "lines", List.of(Map.of("productId", aggregate, "quantity", 1))), otherToken);
        assertThat(foreign.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("authorization matrix: 401 without a token and 403 without the permission on every dispatch route")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fleet.web.dispatch")).isEqualTo(55);
        assertThat(call("GET", "/fleet/delivery-orders", null, clerkToken).status()).isEqualTo(200);
        Res plan = call("POST", "/fleet/delivery-orders/" + UUID.randomUUID() + "/plan-loads", Map.of(), clerkToken);
        assertThat(plan.status()).isEqualTo(403);
        assertThat(msg(plan)).isEqualTo("Missing required permission(s): fleet_dispatch:plan");
        assertThat(call("POST", "/fleet/delivery-charge-rules", Map.of(), clerkToken).status()).isEqualTo(403);
        assertThat(call("POST", "/fleet/delivery-discrepancies/x/approve", null, key(clerkToken)).status())
                .isEqualTo(403);
    }
}
