package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService.CompletedTicket;
import com.inventoryerp.fleet.web.weighbridge.WeighbridgeSettingsController;
import com.inventoryerp.fleet.web.weighbridge.WeighbridgeTicketController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.4a weighbridge: outbound ticket with the simulator (first / second weighing, net, unit conversion, within
 * tolerance → completed + event + public API), out-of-tolerance exception with separation of duties (operator refused,
 * second user approves; rejection → reweigh; approvals inbox → subscriber), manual entry (permission + flag),
 * immutability (edit / weigh after completion refused, print counted, void + reissue audited), stored tare (single
 * weighing, expiry, supersede, revoke), inbound ticket → exactly one goods-receipt line (replay, void guard), the ASCII
 * TCP adapter against an in-test indicator, concurrent captures, discrepancy report, tenant isolation and the route
 * matrix.
 */
class FleetWeighbridgeIT extends FleetSafetyTestSupport {

    @Autowired
    WeighbridgeTicketService service;

    String station;
    String sim;
    String location;
    String ton;
    String m3;
    String aggregate;
    String sand;
    String customer;
    String supplier;
    String operator;
    String manualOperator;
    String operatorApprover;
    String supervisor;

    @BeforeAll
    void weighbridgeFixtures() {
        location = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Quarry yard', 'warehouse', now())").params(location, demoOrg, "YRD-" + sfx).update();
        station = ok(201, "POST", "/fleet/weighbridge/stations", Map.of("code", "wb-" + sfx, "name", "Quarry gate",
                "locationId", location, "tolerancePct", 0.5, "toleranceKg", 20, "tareValidityDays", 30))
                .get("id").asString();
        sim = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "SIM-" + sfx,
                "adapterType", "simulator", "config", Map.of("weightKg", 0, "motionFrames", 2), "readTimeoutMs", 1000))
                .get("id").asString();
        ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'").param(demoOrg)
                .query(String.class).single();
        String volume = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UomCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(volume, demoOrg, "Bulk volume " + sfx).update();
        m3 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, \"categoryId\", category, factor,"
                + " ratio, \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'Cubic metre', ?, 'bulk', 1, 1, 0.001,"
                + " now())").params(m3, demoOrg, "M3-" + sfx, volume).update();
        aggregate = product("Aggregate 20mm", ton);
        sand = product("Pit sand", m3);
        customer = partner("Builder", false);
        supplier = partner("Sand supplier", true);
        operator = tokenWith("weighbridge:read", "weighbridge:weigh");
        manualOperator = tokenWith("weighbridge:read", "weighbridge:weigh", "weighbridge:manual_entry");
        operatorApprover = tokenWith("weighbridge:read", "weighbridge:weigh", "weighbridge:approve_exception");
        supervisor = tokenWith("weighbridge:read", "weighbridge:approve_exception");
    }

    String product(String name, String uomId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, ?, ?, ?, now())")
                .params(id, demoOrg, "WBP-" + sfx + "-" + (++plates), name + " " + sfx, uomId, uomId, uomId).update();
        return id;
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "WBP-" + sfx + "-"
                        + (++plates), name + " " + sfx, isSupplier, !isSupplier).update();
        return id;
    }

    void onPlatform(Object kg) {
        ok(201, "POST", "/fleet/weighbridge/devices/" + sim + "/simulator-weight", Map.of("weightKg", kg));
    }

    Res by(String token, String method, String path, Object body) {
        return call(method, path, body, token);
    }

    JsonNode okAs(String token, int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** An outbound sale-delivery ticket of {@code tonnes} t of aggregate for a fresh plate. */
    JsonNode outbound(String token, Object tonnes, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stationId", station);
        body.put("subjectType", "sale_delivery");
        body.put("registrationPlate", plate());
        body.put("driverName", "John Okello");
        body.put("partnerId", customer);
        body.put("productId", aggregate);
        if (tonnes != null) {
            body.put("orderedQty", tonnes);
        }
        body.putAll(extra);
        return okAs(token, 201, "POST", "/fleet/weighbridge/tickets", body);
    }

    JsonNode weigh(String token, String ticketId, String slot, Object kg) {
        onPlatform(kg);
        return okAs(token, 201, "POST", "/fleet/weighbridge/tickets/" + ticketId + "/" + slot + "-weigh",
                Map.of("deviceId", sim));
    }

    /** A completed outbound ticket: tare 12 000 kg, gross 12 000 + net. */
    JsonNode completedOutbound(Object tonnes, int netKg) {
        String id = outbound(operator, tonnes, Map.of()).get("id").asString();
        weigh(operator, id, "first", 12000);
        JsonNode t = weigh(operator, id, "second", 12000 + netKg);
        assertThat(t.get("status").asString()).isEqualTo("completed");
        return t;
    }

    long outboxCount(String event, String ticketId) {
        return jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = ? AND payload->>'ticketId' = ?")
                .params(event, ticketId).query(Long.class).single();
    }

    // ------------------------------------------------------------------ outbound

    @Test
    @DisplayName("outbound: simulator first / second weighing → net 20 050 kg = 20.05 t, within tolerance → completed")
    void outboundWithinTolerance() {
        String deliveryOrder = "DO-" + sfx;
        String trip = "TRIP-" + sfx;
        JsonNode created = outbound(operator, 20, Map.of("deliveryOrderId", deliveryOrder, "tripId", trip,
                "trailerPlate", "ut 12a"));
        String id = created.get("id").asString();
        assertThat(created.get("ticketNumber").asString()).startsWith("WBT-");
        assertThat(created.get("status").asString()).isEqualTo("open");
        assertThat(created.get("direction").asString()).isEqualTo("outbound");
        assertThat(created.get("orderedUomId").asString()).isEqualTo(ton);
        assertThat(dec(created.get("expectedKg"))).isEqualByComparingTo("20000");
        assertThat(created.get("trailerPlate").asString()).isEqualTo("UT 12A");

        JsonNode first = weigh(operator, id, "first", 12000);
        assertThat(first.get("status").asString()).isEqualTo("first_weighed");
        assertThat(dec(first.get("firstWeightKg"))).isEqualByComparingTo("12000");
        assertThat(first.get("firstSource").asString()).isEqualTo("device");
        assertThat(first.get("firstDeviceId").asString()).isEqualTo(sim);
        assertThat(first.get("firstRawFrame").asString()).isEqualTo("ST,GS,+0012000 kg");
        // A double click on "capture first weight" is refused.
        Res again = by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh", Map.of("deviceId", sim));
        assertThat(again.status()).isEqualTo(409);
        assertThat(msg(again)).contains("is already captured");

        JsonNode done = weigh(operator, id, "second", 32050);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("grossKg"))).isEqualByComparingTo("32050");
        assertThat(dec(done.get("tareKg"))).isEqualByComparingTo("12000");
        assertThat(dec(done.get("netKg"))).isEqualByComparingTo("20050");
        assertThat(dec(done.get("netQty"))).isEqualByComparingTo("20.05");
        assertThat(done.get("netUomCode").asString()).isEqualTo("TON");
        assertThat(dec(done.get("varianceKg"))).isEqualByComparingTo("50");
        assertThat(dec(done.get("allowedVarianceKg"))).isEqualByComparingTo("100");
        assertThat(done.get("withinTolerance").asBoolean()).isTrue();
        assertThat(done.get("isManual").asBoolean()).isFalse();
        assertThat(done.get("exceptions")).isEmpty();

        CompletedTicket api = TenantContext.run(TenantStore.user(demoOrg, adminId), () -> service.completedTicket(id));
        assertThat(api.netQty()).isEqualByComparingTo("20.05");
        assertThat(api.uomId()).isEqualTo(ton);
        assertThat(api.deliveryOrderId()).isEqualTo(deliveryOrder);
        assertThat(api.withinTolerance()).isTrue();
        assertThat(TenantContext.run(TenantStore.user(demoOrg, adminId), () -> service.completedTicketsForTrip(trip)))
                .extracting(CompletedTicket::id).containsExactly(id);
        assertThat(outboxCount("weighbridge.ticket.completed", id)).isEqualTo(1);

        JsonNode queue = ok(200, "GET", "/fleet/weighbridge/tickets?queue=completed&stationId=" + station, null);
        assertThat(queue.get("data").toString()).contains(id);
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets?queue=open&stationId=" + station, null).get("data")
                .toString()).doesNotContain(id);
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets/summary?stationId=" + station, null)
                .get("completedToday").asLong()).isPositive();
    }

    @Test
    @DisplayName("registration rules: one live ticket per truck, unit without weight conversion, kg per unit setting")
    void registrationRules() {
        String plate = plate();
        okAs(operator, 201, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "internal_haulage", "registrationPlate", plate));
        Res dup = by(operator, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "other", "registrationPlate", plate.toLowerCase()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(msg(dup)).startsWith("Truck " + plate + " is already on live weighbridge ticket WBT-");
        // Sand in m³ has no weight conversion until its density is set.
        Res noConv = by(operator, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "registrationPlate", plate(), "productId", sand, "orderedQty", 10));
        assertThat(noConv.status()).isEqualTo(400);
        assertThat(msg(noConv)).startsWith("No weight conversion for product Pit sand");
        ok(200, "PUT", "/fleet/weighbridge/product-settings/" + sand, Map.of("kgPerUnit", 1600, "tolerancePct", 2));
        JsonNode t = outbound(operator, 10, Map.of("productId", sand));
        assertThat(dec(t.get("expectedKg"))).isEqualByComparingTo("16000");
        weigh(operator, t.get("id").asString(), "first", 11000);
        JsonNode done = weigh(operator, t.get("id").asString(), "second", 11000 + 16250);
        // 2 % product tolerance (320 kg) instead of the station's 0.5 %.
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("netQty"))).isEqualByComparingTo("10.1563");
        assertThat(dec(done.get("tolerancePct"))).isEqualByComparingTo("2");
        assertThat(msg(by(operator, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "outbound", "registrationPlate", plate())))).startsWith("Subject type must be one of");
        assertThat(msg(by(operator, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "other", "registrationPlate", plate(), "salesOrderId", "x"))))
                .isEqualTo("Only sale deliveries reference a sales order or delivery order");
        // Below the station minimum (empty platform).
        String low = outbound(operator, null, Map.of()).get("id").asString();
        onPlatform(50);
        Res tooLow = by(operator, "POST", "/fleet/weighbridge/tickets/" + low + "/first-weigh", Map.of("deviceId", sim));
        assertThat(tooLow.status()).isEqualTo(400);
        assertThat(msg(tooLow)).startsWith("Weight 50 kg is below the station minimum of 200 kg");
        Res secondFirst = by(operator, "POST", "/fleet/weighbridge/tickets/" + low + "/second-weigh",
                Map.of("deviceId", sim));
        assertThat(msg(secondFirst)).contains("first");
    }

    // ------------------------------------------------------------------ exceptions

    @Test
    @DisplayName("exception: out of tolerance → approval; the operator's self-approval refused; a second user approves")
    void exceptionSeparationOfDuties() {
        String id = outbound(operatorApprover, 20, Map.of()).get("id").asString();
        weigh(operatorApprover, id, "first", 12000);
        JsonNode t = weigh(operatorApprover, id, "second", 33000);
        assertThat(t.get("status").asString()).isEqualTo("exception");
        assertThat(t.get("withinTolerance").asBoolean()).isFalse();
        assertThat(dec(t.get("varianceKg"))).isEqualByComparingTo("1000");
        JsonNode e = t.get("exceptions").get(0);
        assertThat(e.get("status").asString()).isEqualTo("pending");
        assertThat(e.get("approvalRequestId").isNull()).isFalse();
        String exceptionId = e.get("id").asString();
        assertThat(outboxCount("weighbridge.exception.raised", id)).isEqualTo(1);
        assertThat(outboxCount("weighbridge.ticket.completed", id)).isZero();
        // Printing / completing API refused while the exception is open.
        assertThat(by(operatorApprover, "POST", "/fleet/weighbridge/tickets/" + id + "/print", null).status())
                .isEqualTo(400);
        JsonNode list = okAs(operatorApprover, 200, "GET", "/fleet/weighbridge/exceptions?status=pending", null);
        JsonNode mine = null;
        for (JsonNode row : list.get("data")) {
            if (exceptionId.equals(row.get("id").asString())) {
                mine = row;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("ownTicket").asBoolean()).isTrue();

        Res self = by(operatorApprover, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve",
                Map.of("notes", "my own load"));
        assertThat(self.status()).isEqualTo(403);
        assertThat(msg(self)).isEqualTo("A weighbridge operator cannot approve or reject the exception of their own"
                + " ticket");
        Res noPerm = by(operator, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve", Map.of());
        assertThat(noPerm.status()).isEqualTo(403);

        JsonNode approved = okAs(supervisor, 201, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve",
                Map.of("notes", "Customer accepted the extra tonne"));
        assertThat(approved.get("status").asString()).isEqualTo("completed");
        assertThat(approved.get("exceptions").get(0).get("status").asString()).isEqualTo("approved");
        assertThat(jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ?").param(
                e.get("approvalRequestId").asString()).query(String.class).single()).isEqualTo("approved");
        assertThat(outboxCount("weighbridge.ticket.completed", id)).isEqualTo(1);
        // The subscriber sees the engine's decision later and leaves the decided exception alone.
        drainOutbox();
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets/" + id, null).get("status").asString())
                .isEqualTo("completed");
        Res twice = by(supervisor, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve", Map.of());
        assertThat(msg(twice)).isEqualTo("The exception is already approved");
    }

    @Test
    @DisplayName("exception: rejection needs a reason and sends the truck back for a new second weighing")
    void exceptionRejectAndReweigh() {
        String id = outbound(operator, 20, Map.of()).get("id").asString();
        weigh(operator, id, "first", 12000);
        String exceptionId = weigh(operator, id, "second", 30000).get("exceptions").get(0).get("id").asString();
        assertThat(msg(by(supervisor, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/reject", Map.of())))
                .isEqualTo("A reason is required to reject a weighbridge exception");
        JsonNode back = okAs(supervisor, 201, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/reject",
                Map.of("notes", "Two tonnes short: top up the load"));
        assertThat(back.get("status").asString()).isEqualTo("first_weighed");
        assertThat(back.get("secondWeightKg").isNull()).isTrue();
        assertThat(back.get("netKg").isNull()).isTrue();
        JsonNode done = weigh(operator, id, "second", 32010);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("exceptions")).hasSize(1);
        assertThat(done.get("exceptions").get(0).get("status").asString()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("exception: decided in the approvals inbox → applied by the approval.decided subscriber")
    void exceptionThroughInbox() {
        String id = outbound(operator, 20, Map.of()).get("id").asString();
        weigh(operator, id, "first", 12000);
        JsonNode e = weigh(operator, id, "second", 31000).get("exceptions").get(0);
        String requestId = e.get("approvalRequestId").asString();
        okAs(supervisor, 201, "POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved", "comment",
                "ok"));
        drainOutbox();
        JsonNode t = ok(200, "GET", "/fleet/weighbridge/tickets/" + id, null);
        assertThat(t.get("status").asString()).isEqualTo("completed");
        assertThat(t.get("exceptions").get(0).get("status").asString()).isEqualTo("approved");
        assertThat(ok(201, "POST", "/fleet/weighbridge/exceptions/" + e.get("id").asString() + "/sync", null)
                .get("status").asString()).isEqualTo("completed");
    }

    // ------------------------------------------------------------------ manual entry

    @Test
    @DisplayName("manual entry: needs weighbridge:manual_entry and a reason; flagged on the ticket and in reports")
    void manualEntry() {
        String id = outbound(operator, 20, Map.of()).get("id").asString();
        Res denied = by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh",
                Map.of("manualKg", 12000, "manualReason", "Indicator down"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Entering a weight manually requires the weighbridge:manual_entry"
                + " permission");
        assertThat(msg(by(manualOperator, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh",
                Map.of("manualKg", 12000)))).isEqualTo("A reason is required for a manually entered weight");
        assertThat(msg(by(manualOperator, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh",
                Map.of("manualKg", 12000, "manualReason", "x", "deviceId", sim)))).endsWith("not both");
        JsonNode first = okAs(manualOperator, 201, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh",
                Map.of("manualKg", 12000, "manualReason", "Indicator down"));
        assertThat(first.get("firstSource").asString()).isEqualTo("manual");
        assertThat(first.get("firstManualReason").asString()).isEqualTo("Indicator down");
        assertThat(first.get("firstRawFrame").isNull()).isTrue();
        assertThat(first.get("isManual").asBoolean()).isTrue();
        JsonNode done = weigh(operator, id, "second", 32000);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("isManual").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets?manual=true&stationId=" + station, null).get("data")
                .toString()).contains(id);
        JsonNode report = ok(200, "GET", "/fleet/weighbridge/reports/discrepancies?groupBy=station&stationId="
                + station, null);
        assertThat(report.get("totals").get("manual").asLong()).isPositive();
        assertThat(report.get("groups").get(0).get("label").asString()).contains("Quarry gate");
    }

    // ------------------------------------------------------------------ immutability, print, void + reissue

    @Test
    @DisplayName("immutability: edits / weighings after completion refused; print counted; void + reissue audited")
    void immutabilityVoidReissue() throws Exception {
        JsonNode t = completedOutbound(20, 20000);
        String id = t.get("id").asString();
        Res edit = by(operator, "PATCH", "/fleet/weighbridge/tickets/" + id, Map.of("referenceNumber", "DN-1"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(msg(edit)).endsWith("is completed and cannot be changed: void and reissue it to correct it");
        assertThat(by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/second-weigh", Map.of("deviceId", sim))
                .status()).isEqualTo(409);
        assertThat(by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/cancel", Map.of("reason", "x"))
                .status()).isEqualTo(400);

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/fleet/weighbridge/tickets/" + id + "/print")).header("Authorization", "Bearer " + operator)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(new String(pdf.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/weighbridge/tickets/"
                + id + "/print")).header("Authorization", "Bearer " + operator).POST(HttpRequest.BodyPublishers
                        .noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets/" + id, null).get("printCount").asInt()).isEqualTo(2);

        Res noVoid = by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of("reason", "wrong"));
        assertThat(noVoid.status()).isEqualTo(403);
        assertThat(as("POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of()).status()).isEqualTo(400);
        JsonNode out = ok(201, "POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of("reason",
                "Wrong delivery note number", "reissue", true, "corrections", Map.of("referenceNumber", "DN-FIX")));
        JsonNode voided = out.get("voided");
        JsonNode reissued = out.get("reissued");
        assertThat(voided.get("status").asString()).isEqualTo("void");
        assertThat(voided.get("voidReason").asString()).isEqualTo("Wrong delivery note number");
        assertThat(voided.get("replacedBy").get("id").asString()).isEqualTo(reissued.get("id").asString());
        assertThat(reissued.get("status").asString()).isEqualTo("completed");
        assertThat(reissued.get("ticketNumber").asString()).isNotEqualTo(voided.get("ticketNumber").asString());
        assertThat(reissued.get("referenceNumber").asString()).isEqualTo("DN-FIX");
        assertThat(dec(reissued.get("netKg"))).isEqualByComparingTo("20000");
        assertThat(reissued.get("reissuedFrom").get("id").asString()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'WeighbridgeTicket' AND \"entityId\" = ?"
                + " AND action = 'cancel' AND \"newValues\"::text LIKE '%void%'").param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(outboxCount("weighbridge.ticket.voided", id)).isEqualTo(1);
        assertThat(outboxCount("weighbridge.ticket.completed", reissued.get("id").asString())).isEqualTo(1);
        assertThat(msg(ok(400, "POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of("reason", "again"))))
                .startsWith("Only completed tickets can be voided");
        // A voided ticket still prints, marked VOID.
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/fleet/weighbridge/tickets/" + id + "/print")).header("Authorization", "Bearer "
                        + adminToken).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        // Reissue without weights → an open ticket to weigh again.
        String other = completedOutbound(10, 10000).get("id").asString();
        JsonNode fresh = ok(201, "POST", "/fleet/weighbridge/tickets/" + other + "/void", Map.of("reason",
                "Weighed on the wrong product", "reissue", true, "copyWeights", false)).get("reissued");
        assertThat(fresh.get("status").asString()).isEqualTo("open");
        assertThat(fresh.get("firstWeightKg").isNull()).isTrue();
    }

    static String msg(JsonNode body) {
        JsonNode m = body.get("message");
        return m == null ? "" : m.isArray() ? m.get(0).asString() : m.asString();
    }

    // ------------------------------------------------------------------ stored tare

    @Test
    @DisplayName("stored tare: one weighing with a valid tare; expired refused; supersede; revoke; save from a ticket")
    void storedTare() {
        String plate = plate();
        onPlatform(11800);
        JsonNode tare = okAs(operator, 201, "POST", "/fleet/weighbridge/tares", Map.of("registrationPlate", plate,
                "stationId", station, "deviceId", sim));
        assertThat(tare.get("state").asString()).isEqualTo("valid");
        assertThat(dec(tare.get("tareKg"))).isEqualByComparingTo("11800");
        assertThat(Instant.parse(tare.get("expiresAt").asString())).isAfter(Instant.now().plusSeconds(29L * 86400));
        String id = outbound(operator, 20, Map.of("registrationPlate", plate)).get("id").asString();
        weigh(operator, id, "first", 31800);
        JsonNode done = okAs(operator, 201, "POST", "/fleet/weighbridge/tickets/" + id + "/stored-tare", Map.of());
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("secondSource").asString()).isEqualTo("stored_tare");
        assertThat(done.get("usedStoredTare").asBoolean()).isTrue();
        assertThat(dec(done.get("netKg"))).isEqualByComparingTo("20000");
        assertThat(done.get("secondTareId").asString()).isEqualTo(tare.get("id").asString());

        // Expired tare refused.
        jdbc.sql("UPDATE \"FleetVehicleTare\" SET \"weighedAt\" = now() - interval '40 days', \"expiresAt\" = now() -"
                + " interval '10 days' WHERE id = ?").param(tare.get("id").asString()).update();
        String id2 = outbound(operator, 20, Map.of("registrationPlate", plate)).get("id").asString();
        Res expired = by(operator, "POST", "/fleet/weighbridge/tickets/" + id2 + "/stored-tare", Map.of());
        assertThat(expired.status()).isEqualTo(400);
        assertThat(msg(expired)).contains("expired").endsWith("weigh the empty truck again (re-tare)");
        assertThat(ok(200, "GET", "/fleet/weighbridge/tares?state=expired&plate=" + plate.replace(" ", "%20"), null)
                .get("data").toString()).contains(tare.get("id").asString());

        // Weighing the tare on the ticket and saving it supersedes the old one.
        weigh(operator, id2, "first", 31900);
        onPlatform(11850);
        JsonNode saved = okAs(operator, 201, "POST", "/fleet/weighbridge/tickets/" + id2 + "/second-weigh",
                Map.of("deviceId", sim, "saveTare", true));
        assertThat(saved.get("status").asString()).isEqualTo("completed");
        assertThat(jdbc.sql("SELECT status FROM \"FleetVehicleTare\" WHERE id = ?").param(tare.get("id").asString())
                .query(String.class).single()).isEqualTo("superseded");
        String current = jdbc.sql("SELECT id FROM \"FleetVehicleTare\" WHERE \"registrationPlate\" = ? AND status ="
                + " 'active'").param(plate).query(String.class).single();
        assertThat(jdbc.sql("SELECT \"ticketId\" FROM \"FleetVehicleTare\" WHERE id = ?").param(current)
                .query(String.class).single()).isEqualTo(id2);
        assertThat(by(operator, "POST", "/fleet/weighbridge/tares/" + current + "/revoke", Map.of("reason", "x"))
                .status()).isEqualTo(403);
        assertThat(ok(201, "POST", "/fleet/weighbridge/tares/" + current + "/revoke", Map.of("reason",
                "New body fitted")).get("status").asString()).isEqualTo("revoked");
        String id3 = outbound(operator, null, Map.of("registrationPlate", plate)).get("id").asString();
        assertThat(by(operator, "POST", "/fleet/weighbridge/tickets/" + id3 + "/stored-tare", Map.of()).status())
                .isEqualTo(404);
        // Manual tare needs the permission.
        assertThat(by(operator, "POST", "/fleet/weighbridge/tares", Map.of("registrationPlate", plate, "stationId",
                station, "manualKg", 11000, "manualReason", "x")).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ inbound → GRN

    @Test
    @DisplayName("inbound: ticket against a PO → exactly one draft GRN line (replay), post, void guard; draft cancelled")
    void inboundGoodsReceipt() {
        String po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                location, "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId",
                        aggregate, "description", "Aggregate", "quantity", 100, "unitPrice", 45000,
                        "unitOfMeasureId", ton)))).get("id").asString();
        assertThat(msg(by(operator, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "purchase_receipt", "registrationPlate", plate()))))
                .isEqualTo("Inbound purchase tickets need a purchase order");
        JsonNode t = okAs(operator, 201, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station,
                "subjectType", "purchase_receipt", "registrationPlate", plate(), "purchaseOrderId", po,
                "orderedQty", 15, "referenceNumber", "SUP-DN-77"));
        String id = t.get("id").asString();
        assertThat(t.get("direction").asString()).isEqualTo("inbound");
        assertThat(t.get("partnerId").asString()).isEqualTo(supplier);
        assertThat(t.get("productId").asString()).isEqualTo(aggregate);
        assertThat(t.get("locationId").asString()).isEqualTo(location);
        weigh(operator, id, "first", 27000);
        JsonNode done = weigh(operator, id, "second", 12000);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("netQty"))).isEqualByComparingTo("15");
        String grnId = done.get("goodsReceiptId").asString();
        assertThat(done.get("goodsReceipt").get("status").asString()).isEqualTo("draft");
        Map<String, Object> line = jdbc.sql("SELECT \"purchaseOrderLineId\", quantity, \"unitCost\" FROM"
                + " \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ?").param(grnId).query().singleRow();
        assertThat((BigDecimal) line.get("quantity")).isEqualByComparingTo("15");
        assertThat((BigDecimal) line.get("unitCost")).isEqualByComparingTo("45000");
        assertThat(line.get("purchaseOrderLineId")).isEqualTo(done.get("purchaseOrderLineId").asString());
        // Replay: the same receipt, still one line.
        JsonNode replay = okAs(operator, 201, "POST", "/fleet/weighbridge/tickets/" + id + "/goods-receipt", Map.of());
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("ticket").get("goodsReceiptId").asString()).isEqualTo(grnId);
        assertThat(jdbc.sql("SELECT count(*) FROM \"GoodsReceiptLine\" l JOIN \"WeighbridgeTicket\" t ON"
                + " t.\"goodsReceiptLineId\" = l.id WHERE t.id = ?").param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ?").param(po)
                .query(Long.class).single()).isEqualTo(1);
        // The store posts it through procurement; the ticket can then no longer be voided.
        Res posted = money("PATCH", "/procurement/goods-receipts/" + grnId + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT \"receivedQuantity\" FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?")
                .param(po).query(BigDecimal.class).single()).isEqualByComparingTo("15");
        Res guard = as("POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of("reason", "x"));
        assertThat(guard.status()).isEqualTo(409);
        assertThat(msg(guard)).endsWith("which is posted: reverse that receipt in procurement first");

        // A second load: voiding while its receipt is a draft cancels the draft; the reissue feeds a new one.
        String id2 = okAs(operator, 201, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station,
                "subjectType", "purchase_receipt", "registrationPlate", plate(), "purchaseOrderId", po)).get("id")
                .asString();
        weigh(operator, id2, "first", 26000);
        String grn2 = weigh(operator, id2, "second", 12000).get("goodsReceiptId").asString();
        JsonNode re = ok(201, "POST", "/fleet/weighbridge/tickets/" + id2 + "/void", Map.of("reason", "Wrong PO line",
                "reissue", true)).get("reissued");
        assertThat(jdbc.sql("SELECT status::text FROM \"GoodsReceiptNote\" WHERE id = ?").param(grn2)
                .query(String.class).single()).isEqualTo("cancelled");
        assertThat(re.get("goodsReceiptId").asString()).isNotEqualTo(grn2);
        assertThat(dec(re.get("netQty"))).isEqualByComparingTo("14");
    }

    // ------------------------------------------------------------------ devices

    @Test
    @DisplayName("ascii-tcp device: live read from an in-test indicator server; motion-only times out; unreachable")
    void tcpDevice() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread t = Thread.ofVirtual().start(() -> {
                for (int round = 0; round < 2; round++) {
                    try (Socket s = server.accept()) {
                        OutputStream out = s.getOutputStream();
                        if (round == 0) {
                            out.write(("US,GS,+0024100kg\r\nST,GS,+0024150kg\r\nST,GS,+0024150kg\r\n"
                                    + "ST,GS,+0024150kg\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            Thread.sleep(300);
                        } else {
                            long end = System.currentTimeMillis() + 1500;
                            while (System.currentTimeMillis() < end) {
                                out.write("US,GS,+0024100kg\r\n".getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                Thread.sleep(50);
                            }
                        }
                    } catch (Exception ignored) {
                        // client closed
                    }
                }
            });
            String device = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code",
                    "TCP-" + sfx, "adapterType", "ascii-tcp", "config", Map.of("host", "127.0.0.1", "port",
                            server.getLocalPort(), "stableFrames", 3), "readTimeoutMs", 1000)).get("id").asString();
            JsonNode read = okAs(operator, 201, "POST", "/fleet/weighbridge/devices/" + device + "/read", null);
            assertThat(dec(read.get("weightKg"))).isEqualByComparingTo("24150");
            assertThat(read.get("rawFrame").asString()).isEqualTo("ST,GS,+0024150kg");
            Res moving = by(operator, "POST", "/fleet/weighbridge/devices/" + device + "/read", null);
            assertThat(moving.status()).isEqualTo(409);
            assertThat(msg(moving)).isEqualTo("The weight on indicator TCP-" + sfx + " did not settle within 1 s");
            t.join(3000);
        }
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        String dead = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "DEAD-" + sfx,
                "adapterType", "ascii-tcp", "config", Map.of("host", "127.0.0.1", "port", closed))).get("id")
                .asString();
        assertThat(by(operator, "POST", "/fleet/weighbridge/devices/" + dead + "/read", null).status()).isEqualTo(503);
        JsonNode test = ok(201, "POST", "/fleet/weighbridge/devices/" + dead + "/test-read", null);
        assertThat(test.get("ok").asBoolean()).isFalse();
        assertThat(test.get("reachable").asBoolean()).isFalse();
        JsonNode stored = ok(200, "GET", "/fleet/weighbridge/devices?stationId=" + station, null);
        assertThat(stored.toString()).contains("\"lastStatus\":\"error\"");
        assertThat(msg(as("POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "BAD-" + sfx,
                "adapterType", "ascii-tcp", "config", Map.of("host", "x")))))
                .isEqualTo("The indicator port must be between 1 and 65535");
        assertThat(msg(as("POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "BAD2-" + sfx,
                "adapterType", "laser")))).startsWith("Weighbridge adapter laser is not available");
        JsonNode simTest = ok(201, "POST", "/fleet/weighbridge/devices/" + sim + "/test-read", null);
        assertThat(simTest.get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("concurrency: two simultaneous first-weigh captures — exactly one lands")
    void concurrentCapture() throws Exception {
        String id = outbound(operator, 20, Map.of()).get("id").asString();
        onPlatform(12000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(() -> by(operator, "POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh",
                        Map.of("deviceId", sim)).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'WeighbridgeTicket' AND \"entityId\" = ?"
                + " AND action = 'measure'").param(id).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ report, tenancy, authorization

    @Test
    @DisplayName("discrepancy report: by product with expected vs net and out-of-tolerance count")
    void discrepancyReport() {
        String p = product("Report aggregate", ton);
        String a = outbound(operator, 10, Map.of("productId", p)).get("id").asString();
        weigh(operator, a, "first", 12000);
        weigh(operator, a, "second", 22030);
        String b = outbound(operator, 10, Map.of("productId", p)).get("id").asString();
        weigh(operator, b, "first", 12000);
        String ex = weigh(operator, b, "second", 22500).get("exceptions").get(0).get("id").asString();
        okAs(supervisor, 201, "POST", "/fleet/weighbridge/exceptions/" + ex + "/approve", Map.of());
        JsonNode r = ok(200, "GET", "/fleet/weighbridge/reports/discrepancies?groupBy=product&productId=" + p, null);
        JsonNode g = r.get("groups").get(0);
        assertThat(g.get("key").asString()).isEqualTo(p);
        assertThat(g.get("tickets").asLong()).isEqualTo(2);
        assertThat(dec(g.get("expectedKg"))).isEqualByComparingTo("20000");
        assertThat(dec(g.get("netKg"))).isEqualByComparingTo("20530");
        assertThat(dec(g.get("varianceKg"))).isEqualByComparingTo("530");
        assertThat(g.get("outOfTolerance").asLong()).isEqualTo(1);
        assertThat(dec(g.get("variancePct"))).isEqualByComparingTo("2.65");
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets?outOfTolerance=true&productId=" + p, null).get("meta")
                .get("total").asLong()).isEqualTo(1);
        assertThat(as("GET", "/fleet/weighbridge/reports/discrepancies?groupBy=colour", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's stations, devices, tickets or tares")
    void tenantIsolation() {
        JsonNode t = completedOutbound(20, 20000);
        String id = t.get("id").asString();
        assertThat(call("GET", "/fleet/weighbridge/tickets/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/weighbridge/tickets", null, otherToken).body().toString()).doesNotContain(id);
        assertThat(call("GET", "/fleet/weighbridge/stations", null, otherToken).body().toString())
                .doesNotContain(station);
        assertThat(call("GET", "/fleet/weighbridge/devices", null, otherToken).body().toString()).doesNotContain(sim);
        assertThat(call("PATCH", "/fleet/weighbridge/devices/" + sim, Map.of("name", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/fleet/weighbridge/devices/" + sim + "/read", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType", "other",
                "registrationPlate", "X 1"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/weighbridge/tickets/" + id + "/void", Map.of("reason", "x"), otherToken)
                .status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/weighbridge/reports/discrepancies", null, otherToken).body().get("totals")
                .get("tickets").asLong()).isZero();
    }

    @Test
    @DisplayName("authorization: 401 / 403 on every weighbridge route; configure / void / approve are separate rights")
    void authorization() {
        assertThat(assertRouteMatrix(Set.of(WeighbridgeSettingsController.class, WeighbridgeTicketController.class)))
                .isEqualTo(36);
        Res station403 = by(operator, "POST", "/fleet/weighbridge/stations", Map.of("code", "X", "name", "X"));
        assertThat(station403.status()).isEqualTo(403);
        assertThat(msg(station403)).isEqualTo("Missing required permission(s): weighbridge:configure");
        assertThat(by(supervisor, "POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "other", "registrationPlate", plate())).status()).isEqualTo(403);
        assertThat(by(operator, "GET", "/fleet/weighbridge/tickets", null).status()).isEqualTo(200);
    }
}
