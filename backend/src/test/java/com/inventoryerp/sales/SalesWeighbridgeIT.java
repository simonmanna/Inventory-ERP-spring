package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetWeighbridgeRuleService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesDeliveryService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase A1: the authoritative Fleet weighbridge ticket is visible on Sales deliveries (detail, list, delivery note,
 * PDF view copy) without duplicating ticket data, without {@code weighbridge:*} permissions and without changing how
 * weighing, dispatch, stock or billing work.
 */
class SalesWeighbridgeIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    int seq;
    String ton;
    String aggregate;
    String yard;
    String customer;
    String station;
    String sim;
    String operator;
    String manualOperator;
    String salesReader;
    String noDeliveryRead;

    @BeforeAll
    void fixtures() {
        ton = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'TON'").param(demoOrg)
                .query(String.class).single();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'WB yard', 'warehouse', now())").params(yard, demoOrg, "WBY-" + sfx).update();
        aggregate = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"purchaseUomId\","
                + " \"billingBasis\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 45000, ?, ?, ?,"
                + " 'weighed', now())").params(aggregate, demoOrg, "WBA-" + sfx, "Aggregate 20mm " + sfx, ton, ton, ton)
                .update();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "WBC-" + sfx, "WB cust " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(aggregate, yard, new BigDecimal("1000"))
                        .unitCost(new BigDecimal("30000")))));
        station = ok(201, "POST", "/fleet/weighbridge/stations", Map.of("code", "wbs-" + sfx, "name", "Quarry gate "
                + sfx, "locationId", yard, "tolerancePct", 0.5, "toleranceKg", 20, "tareValidityDays", 30))
                .get("id").asString();
        sim = ok(201, "POST", "/fleet/weighbridge/devices", Map.of("stationId", station, "code", "WBSIM-" + sfx,
                "adapterType", "simulator", "config", Map.of("weightKg", 0, "motionFrames", 1), "readTimeoutMs", 1000))
                .get("id").asString();
        operator = tokenWith("weighbridge:read", "weighbridge:weigh");
        manualOperator = tokenWith("weighbridge:read", "weighbridge:weigh", "weighbridge:manual_entry");
        salesReader = tokenWith("sales:order:read", "sales:order:read_all", "sales:delivery:read");
        noDeliveryRead = tokenWith("sales:order:read", "sales:order:read_all");
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "WB sales " + role, permissions).update();
        String email = "wbs-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Weigh@12345", role);
        return login("DEMO", email, "Weigh@12345");
    }

    String plate() {
        return "UWB " + sfx.toUpperCase() + "-" + (++seq);
    }

    /** A confirmed sales order for {@code tonnes} t of aggregate from the yard. */
    JsonNode order(int tonnes) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", aggregate, "quantity", tonnes, "unitPrice", 45000))))
                .get("id").asString();
        return ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
    }

    /** One planned truck (manual carrier) of {@code tonnes} t on the order; returns the delivery. */
    JsonNode truck(JsonNode order, int tonnes) {
        String lineId = order.get("lines").get(0).get("id").asString();
        return ok(201, "POST", "/sales/orders/" + order.get("id").asString() + "/deliveries", Map.of("vehiclePlate",
                plate(), "driverName", "Driver " + seq, "lines", List.of(Map.of("lineId", lineId, "quantity", tonnes))));
    }

    JsonNode ticketFor(String deliveryId) {
        Res r = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType", "sale_delivery",
                "tripId", deliveryId), operator);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    void onPlatform(int kg) {
        ok(201, "POST", "/fleet/weighbridge/devices/" + sim + "/simulator-weight", Map.of("weightKg", kg));
    }

    JsonNode weigh(String ticketId, String slot, int kg) {
        onPlatform(kg);
        Res r = call("POST", "/fleet/weighbridge/tickets/" + ticketId + "/" + slot + "-weigh", Map.of("deviceId", sim),
                operator);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    /** Ticket for the delivery, tare 12 000 kg, gross 12 000 + net. */
    JsonNode weighed(String deliveryId, int netKg) {
        String id = ticketFor(deliveryId).get("id").asString();
        weigh(id, "first", 12000);
        return weigh(id, "second", 12000 + netKg);
    }

    JsonNode delivery(String id) {
        return ok(200, "GET", "/sales/deliveries/" + id, null);
    }

    Res issue(String id) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", "/sales/deliveries/" + id + "/issue", null, h);
    }

    HttpResponse<byte[]> pdf(String deliveryId, String ticketId, String token) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sales/deliveries/"
                    + deliveryId + "/weigh-tickets/" + ticketId + "/pdf")).header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    BigDecimal onHand() {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(aggregate, yard).query(BigDecimal.class).single();
    }

    static JsonNode only(JsonNode tickets) {
        assertThat(tickets).hasSize(1);
        return tickets.get(0);
    }

    static boolean absent(JsonNode n) {
        return n == null || n.isNull();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m == null ? "" : m.isArray() ? m.get(0).asString() : m.asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @DisplayName("captured ticket: shown on the delivery detail, list and delivery note; weighed billing = ticket net, one stock issue")
    void capturedTicket() {
        JsonNode o = order(100);
        String d = truck(o, 20).get("id").asString();
        assertThat(delivery(d).get("weighbridgeTickets")).isEmpty();
        assertThat(absent(delivery(d).get("weighVerification"))).isTrue();
        JsonNode done = weighed(d, 20040);
        assertThat(done.get("status").asString()).isEqualTo("completed");

        JsonNode t = delivery(d);
        JsonNode w = only(t.get("weighbridgeTickets"));
        assertThat(w.get("ticketNumber").asString()).isEqualTo(done.get("ticketNumber").asString());
        assertThat(w.get("registrationPlate").asString()).isEqualTo(t.get("vehiclePlate").asString());
        assertThat(dec(w.get("tareKg"))).isEqualByComparingTo("12000");
        assertThat(dec(w.get("grossKg"))).isEqualByComparingTo("32040");
        assertThat(dec(w.get("netKg"))).isEqualByComparingTo("20040");
        assertThat(w.get("firstSource").asString()).isEqualTo("device");
        assertThat(w.get("secondSource").asString()).isEqualTo("device");
        assertThat(w.get("firstWeighedAt").isNull()).isFalse();
        assertThat(w.get("secondWeighedAt").isNull()).isFalse();
        assertThat(w.get("firstOperator").asString()).isNotBlank();
        assertThat(w.get("isManual").asBoolean()).isFalse();
        assertThat(w.get("verification").asString()).isEqualTo("verified");
        assertThat(w.get("feedsTrip").asBoolean()).isTrue();
        assertThat(t.get("weighbridge").get("feedingTicketId").asString()).isEqualTo(w.get("id").asString());
        assertThat(t.get("weighTicketNumber").asString()).isEqualTo(w.get("ticketNumber").asString());
        assertThat(t.get("weighVerification").asString()).isEqualTo("verified");
        // The ticket fed the truck: loaded = ticket net in tonnes.
        assertThat(dec(t.get("lines").get(0).get("loadedQty"))).isEqualByComparingTo("20.04");

        JsonNode listed = null;
        for (JsonNode row : ok(200, "GET", "/sales/deliveries?orderId=" + o.get("id").asString(), null)) {
            if (d.equals(row.get("id").asString())) {
                listed = row;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("weighTicketNumber").asString()).isEqualTo(w.get("ticketNumber").asString());
        assertThat(dec(listed.get("weighNetKg"))).isEqualByComparingTo("20040");
        assertThat(listed.get("weighVerification").asString()).isEqualTo("verified");

        BigDecimal before = onHand();
        Res issued = issue(d);
        assertThat(issued.status()).as(issued.raw().body()).isEqualTo(200);
        assertThat(before.subtract(onHand())).isEqualByComparingTo("20.04");
        // A replayed or second dispatch never issues again.
        assertThat(issue(d).status()).isBetween(400, 409);
        assertThat(before.subtract(onHand())).isEqualByComparingTo("20.04");
        // Weighed basis bills the ticket net (current rule; D1 moves invoiceability to after POD in A4).
        assertThat(jdbc.sql("SELECT \"qtyBillable\" FROM \"SalesOrderLine\" WHERE id = ?").param(o.get("lines").get(0)
                .get("id").asString()).query(BigDecimal.class).single()).isEqualByComparingTo("20.04");

        HttpResponse<byte[]> note = downloadBytes("/sales/deliveries/" + d + "/note");
        assertThat(note.statusCode()).isEqualTo(200);
        assertThat(new String(note.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("authorised manual weighing is flagged on the Sales delivery")
    void manualWeighing() {
        String d = truck(order(30), 20).get("id").asString();
        String id = ticketFor(d).get("id").asString();
        Res manual = call("POST", "/fleet/weighbridge/tickets/" + id + "/first-weigh", Map.of("manualKg", 12000,
                "manualReason", "Indicator down"), manualOperator);
        assertThat(manual.status()).as(manual.raw().body()).isEqualTo(201);
        weigh(id, "second", 32000);
        JsonNode w = only(delivery(d).get("weighbridgeTickets"));
        assertThat(w.get("isManual").asBoolean()).isTrue();
        assertThat(w.get("firstSource").asString()).isEqualTo("manual");
        assertThat(w.get("secondSource").asString()).isEqualTo("device");
        assertThat(w.get("verification").asString()).isEqualTo("verified");
    }

    @Test
    @DisplayName("expired stored tare is refused; the truck is re-tared on the platform and the ticket feeds the delivery")
    void expiredTare() {
        String d = truck(order(30), 20).get("id").asString();
        String plate = delivery(d).get("vehiclePlate").asString();
        onPlatform(11800);
        String tare = ok(201, "POST", "/fleet/weighbridge/tares", Map.of("registrationPlate", plate, "stationId",
                station, "deviceId", sim)).get("id").asString();
        jdbc.sql("UPDATE \"FleetVehicleTare\" SET \"weighedAt\" = now() - interval '40 days', \"expiresAt\" = now() -"
                + " interval '10 days' WHERE id = ?").param(tare).update();
        String id = ticketFor(d).get("id").asString();
        weigh(id, "first", 31800);
        Res expired = call("POST", "/fleet/weighbridge/tickets/" + id + "/stored-tare", Map.of(), operator);
        assertThat(expired.status()).isEqualTo(400);
        assertThat(msg(expired)).contains("expired");
        weigh(id, "second", 11800);
        JsonNode w = only(delivery(d).get("weighbridgeTickets"));
        assertThat(w.get("usedStoredTare").asBoolean()).isFalse();
        assertThat(w.get("verification").asString()).isEqualTo("verified");
        assertThat(w.get("feedsTrip").asBoolean()).isTrue();
        assertThat(dec(delivery(d).get("lines").get(0).get("loadedQty"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("out-of-tolerance ticket: pending exception on the delivery, approved by another user, then feeds the truck")
    void outOfTolerance() {
        String d = truck(order(30), 20).get("id").asString();
        String id = ticketFor(d).get("id").asString();
        weigh(id, "first", 12000);
        JsonNode t = weigh(id, "second", 33000);
        assertThat(t.get("status").asString()).isEqualTo("exception");
        JsonNode pending = only(delivery(d).get("weighbridgeTickets"));
        assertThat(pending.get("verification").asString()).isEqualTo("pending_exception");
        assertThat(pending.get("exceptionStatus").asString()).isEqualTo("pending");
        assertThat(pending.get("feedsTrip").asBoolean()).isFalse();
        assertThat(absent(delivery(d).get("weighbridge").get("feedingTicketId"))).isTrue();
        // No view copy of a ticket that is not completed.
        assertThat(pdf(d, id, salesReader).statusCode()).isEqualTo(400);

        String exceptionId = t.get("exceptions").get(0).get("id").asString();
        ok(201, "POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve", Map.of("notes", "Customer took 21 t"));
        JsonNode approved = only(delivery(d).get("weighbridgeTickets"));
        assertThat(approved.get("verification").asString()).isEqualTo("approved_exception");
        assertThat(approved.get("exceptionStatus").asString()).isEqualTo("approved");
        assertThat(approved.get("withinTolerance").asBoolean()).isFalse();
        assertThat(approved.get("feedsTrip").asBoolean()).isTrue();
        assertThat(dec(delivery(d).get("lines").get(0).get("loadedQty"))).isEqualByComparingTo("21");
    }

    @Test
    @DisplayName("void + reissue before dispatch shows the chain; quantities follow the new ticket; void after dispatch is refused")
    void voidAndReissue() {
        String d = truck(order(30), 20).get("id").asString();
        String first = weighed(d, 20000).get("id").asString();
        JsonNode out = ok(201, "POST", "/fleet/weighbridge/tickets/" + first + "/void", Map.of("reason",
                "Weighed with the wrong trailer", "reissue", true, "copyWeights", false));
        String second = out.get("reissued").get("id").asString();
        JsonNode cleared = delivery(d).get("lines").get(0).get("loadedQty");
        assertThat(absent(cleared) || dec(cleared).signum() == 0).as("loaded cleared by the void").isTrue();
        weigh(second, "first", 12000);
        weigh(second, "second", 31950);

        JsonNode t = delivery(d);
        JsonNode tickets = t.get("weighbridgeTickets");
        assertThat(tickets).hasSize(2);
        JsonNode voided = tickets.get(0);
        JsonNode reissued = tickets.get(1);
        assertThat(voided.get("id").asString()).isEqualTo(first);
        assertThat(voided.get("verification").asString()).isEqualTo("voided");
        assertThat(voided.get("voidReason").asString()).isEqualTo("Weighed with the wrong trailer");
        assertThat(voided.get("replacedById").asString()).isEqualTo(second);
        assertThat(voided.get("feedsTrip").asBoolean()).isFalse();
        assertThat(reissued.get("reissuedFromId").asString()).isEqualTo(first);
        assertThat(reissued.get("feedsTrip").asBoolean()).isTrue();
        assertThat(t.get("weighTicketNumber").asString()).isEqualTo(reissued.get("ticketNumber").asString());
        assertThat(dec(t.get("lines").get(0).get("loadedQty"))).isEqualByComparingTo("19.95");

        BigDecimal before = onHand();
        assertThat(issue(d).status()).isEqualTo(200);
        assertThat(before.subtract(onHand())).isEqualByComparingTo("19.95");
        Res late = as("POST", "/fleet/weighbridge/tickets/" + second + "/void", Map.of("reason", "too late"));
        assertThat(late.status()).isEqualTo(409);
        assertThat(before.subtract(onHand())).isEqualByComparingTo("19.95");
        JsonNode still = delivery(d).get("weighbridgeTickets").get(1);
        assertThat(still.get("id").asString()).isEqualTo(second);
        assertThat(still.get("verification").asString()).isEqualTo("verified");
        assertThat(still.get("feedsTrip").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("three trucks on one order, each with its own ticket; partial delivery leaves the rest outstanding")
    void threeTrucksPartial() {
        JsonNode o = order(100);
        List<String> ids = new ArrayList<>();
        int[] nets = {20000, 25000, 15000};
        for (int net : nets) {
            String d = truck(o, net / 1000).get("id").asString();
            weighed(d, net);
            ids.add(d);
        }
        assertThat(issue(ids.get(0)).status()).isEqualTo(200);
        assertThat(issue(ids.get(1)).status()).isEqualTo(200);
        Set<String> numbers = new HashSet<>();
        for (JsonNode row : ok(200, "GET", "/sales/deliveries?orderId=" + o.get("id").asString(), null)) {
            assertThat(row.get("weighTicketNumber").isNull()).isFalse();
            numbers.add(row.get("weighTicketNumber").asString());
        }
        assertThat(numbers).hasSize(3);
        JsonNode line = ok(200, "GET", "/sales/orders/" + o.get("id").asString() + "/delivery-summary", null)
                .get("lines").get(0);
        assertThat(dec(line.get("dispatched"))).isEqualByComparingTo("45");
        assertThat(dec(line.get("scheduled"))).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("PDF view copy: Sales-only user, not counted as a print, audited; scoped to the delivery; tenant isolated")
    void pdfCopyAndAccess() {
        String d = truck(order(30), 20).get("id").asString();
        String ticket = weighed(d, 20000).get("id").asString();
        String other = truck(order(30), 20).get("id").asString();

        // Sales-only reader (no weighbridge:* permission) sees the ticket fields and opens the copy.
        Res seen = call("GET", "/sales/deliveries/" + d, null, salesReader);
        assertThat(seen.status()).isEqualTo(200);
        assertThat(only(seen.body().get("weighbridgeTickets")).get("ticketNumber").isNull()).isFalse();
        HttpResponse<byte[]> copy = pdf(d, ticket, salesReader);
        assertThat(copy.statusCode()).isEqualTo(200);
        assertThat(copy.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(new String(copy.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(jdbc.sql("SELECT \"printCount\" FROM \"WeighbridgeTicket\" WHERE id = ?").param(ticket)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'WeighbridgeTicket' AND \"entityId\" = ?"
                + " AND action = 'reprint' AND \"newValues\"->>'copy' = 'view'").param(ticket).query(Long.class).single()).isEqualTo(1);
        // The Fleet print route still needs weighbridge:weigh.
        assertThat(call("POST", "/fleet/weighbridge/tickets/" + ticket + "/print", null, salesReader).status())
                .isEqualTo(403);

        // Another delivery's ticket, an unknown ticket, no delivery permission, another tenant.
        assertThat(pdf(other, ticket, salesReader).statusCode()).isEqualTo(404);
        assertThat(pdf(d, UUID.randomUUID().toString(), salesReader).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/sales/deliveries/" + d, null, noDeliveryRead).status()).isEqualTo(403);
        assertThat(pdf(d, ticket, noDeliveryRead).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/sales/deliveries/" + d, null, otherToken).status()).isEqualTo(404);
        assertThat(pdf(d, ticket, otherToken).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Fleet optionality: the services Sales uses for tickets are not switched off with the fleet module")
    void noFleetModuleCondition() {
        for (Class<?> c : List.of(WeighbridgeTicketService.class, FleetWeighbridgeRuleService.class,
                SalesDeliveryService.class)) {
            assertThat(c.isAnnotationPresent(ConditionalOnProperty.class)).as(c.getSimpleName()).isFalse();
        }
    }
}
