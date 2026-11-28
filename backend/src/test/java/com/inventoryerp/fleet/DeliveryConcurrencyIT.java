package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 1a, R2 concurrency evidence (lock order L1 → L7, no 40P01, no lost or doubled stock): the paths that
 * return or issue an invoice's goods racing on one invoice, and two sales-order reservations racing on one stock cell
 * (M-27). Each scenario runs in its own organisation and ends with the integrity report green (report-only checks
 * included, {@code feature_risk} aside) and the GL tie-out.
 */
class DeliveryConcurrencyIT extends InventoryAuditSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    void fresh() {
        AuditOrg o = newOrg();
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, org, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, supplier, !supplier).update();
        return id;
    }

    Res moneyRes(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", path, body, h);
    }

    JsonNode money(String path, Object body) {
        Res r = moneyRes(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String today() {
        return LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString();
    }

    record Delivered(String invoice, String deliveryOrder, String product, String customer) {}

    Delivered invoiceWithDeliveryOrder(int qty) {
        String customer = partner("Customer", false);
        String p = product("DLV");
        receive(p, 50, 100);
        String invoice = okA(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", p, "quantity", qty, "unitPrice", 200)))).get("id").asString();
        JsonNode dO = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", invoice,
                "locationId", main));
        return new Delivered(invoice, dO.get("id").asString(), p, customer);
    }

    String trip(String deliveryOrder) {
        String trip = okA(201, "POST", "/fleet/delivery-orders/" + deliveryOrder + "/plan-loads", Map.of("payloadT", 20))
                .get("createdTripIds").get(0).asString();
        String transporter = partner("Haulier", true);
        String rate = okA(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name",
                "Run " + UUID.randomUUID().toString().substring(0, 6), "basis", "per_trip", "rate", 1000)).get("id")
                .asString();
        okA(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "transporterRateId", rate, "transporterPlate", "UAX 123B", "transporterDriverName", "Okello"));
        return trip;
    }

    Map<String, Object> pod(JsonNode dispatched, int delivered, int returned) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("loadLineId", dispatched.get("lines").get(0).get("id").asString());
        line.put("deliveredQty", delivered);
        line.put("returnedQty", returned);
        if (returned > 0) {
            line.put("reason", "other");
        }
        return Map.of("receiverName", "Site agent", "latitude", 0.3137, "longitude", 32.5812, "signature", PNG,
                "lines", List.of(line));
    }

    void assertClean() {
        assertIntegrityGreen(true, "feature_risk");
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("R2: credit-note restock, invoice cancel and a proof-of-delivery put-back race on one invoice: no"
            + " deadlock, never more back than the trip issued")
    void creditNoteCancelAndPutBack() throws Exception {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String t = trip(d.deliveryOrder());
        JsonNode dispatched = money("/fleet/trips/" + t + "/dispatch", null);
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        String cn = okA(200, "POST", "/credit-notes", Map.of("partnerId", d.customer(), "issueDate", today(),
                "reversedDocumentId", d.invoice(), "lines", List.of(Map.of("productId", d.product(), "quantity", 10,
                        "unitPrice", 200)))).get("id").asString();

        List<Callable<Res>> racers = List.of(
                () -> moneyRes("/credit-notes/" + cn + "/post", null),
                () -> moneyRes("/invoices/" + d.invoice() + "/cancel", Map.of("reason", "Race")),
                () -> moneyRes("/fleet/trips/" + t + "/proof-of-delivery", pod(dispatched, 8, 2)));
        List<Raced<Res>> out = race(Duration.ofSeconds(60), racers);
        for (Raced<Res> r : out) {
            assertThat(r.ok()).as(r.message()).isTrue();
            assertThat(r.value().status()).as(r.value().raw().body()).isLessThan(500);
        }
        BigDecimal back = onHand(d.product(), main).subtract(new BigDecimal("40"));
        assertThat(back).as("units back for 10 issued; outcomes " + out.stream().map(r -> r.value().status()).toList())
                .isBetween(BigDecimal.ZERO, BigDecimal.TEN);
        assertClean();
    }

    @Test
    @DisplayName("R2: a collection (issue-undelivered) and a new delivery order race on an invoice awaiting delivery:"
            + " one wins, the goods leave stock exactly once")
    void collectionVersusNewDelivery() throws Exception {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/cancel", Map.of("reason", "Truck broke"));

        List<Raced<Res>> out = race(Duration.ofSeconds(60), List.of(
                () -> moneyRes("/invoices/" + d.invoice() + "/issue-undelivered", Map.of("locationId", main)),
                () -> api("POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", d.invoice(), "locationId",
                        main))));
        Res collect = out.get(0).value();
        Res redeliver = out.get(1).value();
        assertThat(collect).isNotNull();
        assertThat(redeliver).isNotNull();
        if (redeliver.status() == 201 && "dispatch".equals(redeliver.body().get("stockMode").asString())) {
            assertThat(collect.status()).as(collect.raw().body()).isEqualTo(400);
            String t = trip(redeliver.body().get("id").asString());
            // The collection races the new order's dispatch too: it is refused (the order holds the goods).
            List<Raced<Res>> second = race(Duration.ofSeconds(60), List.of(
                    () -> moneyRes("/fleet/trips/" + t + "/dispatch", null),
                    () -> moneyRes("/invoices/" + d.invoice() + "/issue-undelivered", Map.of("locationId", main))));
            assertThat(second.get(0).value().status()).as(second.get(0).value().raw().body()).isIn(200, 201);
            assertThat(second.get(1).value().status()).isEqualTo(400);
        } else {
            assertThat(collect.status()).as(collect.raw().body()).isEqualTo(200);
        }
        assertThat(onHand(d.product(), main)).as("issued exactly once").isEqualByComparingTo("40");
        assertClean();
    }

    @Test
    @DisplayName("M-27: two sales orders reserving the same cell at once never reserve more than is on hand")
    void reservationsDoNotOverReserve() throws Exception {
        fresh();
        String customer = partner("Customer", false);
        // Reservations only by hand, so the three orders race for the same free stock at /reserve.
        okA(200, "PUT", "/sales/settings", Map.of("reservationTrigger", "none"));
        for (int round = 0; round < 3; round++) {
            String p = product("RSV" + round);
            receive(p, 10, 100);
            List<String> orders = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String so = okA(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", main,
                        "lines", List.of(Map.of("productId", p, "quantity", 8, "unitPrice", 200)))).get("id")
                        .asString();
                okA(200, "POST", "/sales/orders/" + so + "/confirm", null);
                orders.add(so);
            }
            List<Callable<Res>> racers = orders.stream().<Callable<Res>>map(so ->
                    () -> api("POST", "/sales/orders/" + so + "/reserve", null)).toList();
            for (Raced<Res> r : race(Duration.ofSeconds(60), racers)) {
                assertThat(r.ok()).as(r.message()).isTrue();
                assertThat(r.value().status()).as(r.value().raw().body()).isLessThan(300);
            }
            BigDecimal reserved = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\""
                    + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND status = 'active'").params(org, p)
                    .query(BigDecimal.class).single());
            assertThat(reserved).as("round " + round).isLessThanOrEqualTo(BigDecimal.TEN);
        }
        assertClean();
    }
}
