package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.invoicing.web.InvoiceController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 1a (F1 / F2 / F3, owner decision D4): the delivery life cycle of an invoice's goods, end to end through
 * the API, in an isolated organisation per scenario. A delivery order cancelled or closed short hands what it did not
 * deliver back to the invoice (awaiting delivery); a new delivery order (stock mode dispatch) or a collection
 * ({@code POST /invoices/:id/issue-undelivered}) issues the rest exactly once; proof-of-delivery put-backs return
 * against the trip's issue, so a later credit note cannot restock the same units again; a sales return of goods that
 * never left stock restocks nothing and is capped by what the delivery still has out. After every scenario the
 * integrity report (value checks and the report-only checks, {@code feature_risk} aside) is green and the GL ties out.
 */
class DeliveryLifecycleIT extends InventoryAuditSupport {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    // ------------------------------------------------------------------ fixtures

    void use(AuditOrg o) {
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    void fresh() {
        use(newOrg());
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, org, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, supplier, !supplier).update();
        return id;
    }

    Map<String, String> keyed() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res moneyRes(String path, Object body) {
        return call("POST", path, body, keyed());
    }

    JsonNode money(String path, Object body) {
        Res r = moneyRes(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String msg(Res r) {
        return r.body() == null || r.body().get("message") == null ? r.raw().body() : r.body().get("message").asString();
    }

    String today() {
        return LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString();
    }

    record Delivered(String invoice, String deliveryOrder, String product, String customer) {}

    /** 50 in MAIN at 100; a draft invoice of {@code qty} handed to a delivery order (stock mode dispatch) from MAIN. */
    Delivered invoiceWithDeliveryOrder(int qty) {
        String customer = partner("Customer", false);
        String p = product("DLV");
        receive(p, 50, 100);
        String invoice = okA(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", p, "quantity", qty, "unitPrice", 200)))).get("id").asString();
        JsonNode dO = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", invoice,
                "locationId", main));
        assertThat(dO.get("stockMode").asString()).isEqualTo("dispatch");
        return new Delivered(invoice, dO.get("id").asString(), p, customer);
    }

    /** A trip of the delivery order assigned to a transporter: everything left ({@code qty} null) or {@code qty}. */
    String trip(String deliveryOrder, Integer qty) {
        String trip;
        if (qty == null) {
            trip = okA(201, "POST", "/fleet/delivery-orders/" + deliveryOrder + "/plan-loads", Map.of("payloadT", 20))
                    .get("createdTripIds").get(0).asString();
        } else {
            String line = okA(200, "GET", "/fleet/delivery-orders/" + deliveryOrder, null).get("lines").get(0)
                    .get("id").asString();
            trip = okA(201, "POST", "/fleet/delivery-orders/" + deliveryOrder + "/trips", Map.of("lines",
                    List.of(Map.of("deliveryOrderLineId", line, "quantity", qty)))).get("id").asString();
        }
        String transporter = partner("Haulier", true);
        String rate = okA(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name",
                "Run " + UUID.randomUUID().toString().substring(0, 6), "basis", "per_trip", "rate", 1000)).get("id")
                .asString();
        okA(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "transporterRateId", rate, "transporterPlate", "UAX 123B", "transporterDriverName", "Okello"));
        return trip;
    }

    JsonNode dispatch(String trip) {
        return money("/fleet/trips/" + trip + "/dispatch", null);
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

    String data(String invoice, String key) {
        return jdbc.sql("SELECT data->>'" + key + "' FROM \"Document\" WHERE id = ?").param(invoice)
                .query(String.class).optional().orElse(null);
    }

    String doCode(String deliveryOrder) {
        return okA(200, "GET", "/fleet/delivery-orders/" + deliveryOrder, null).get("code").asString();
    }

    Res creditNote(Delivered d, int qty) {
        String cn = okA(200, "POST", "/credit-notes", Map.of("partnerId", d.customer(), "issueDate", today(),
                "reversedDocumentId", d.invoice(), "lines", List.of(Map.of("productId", d.product(), "quantity", qty,
                        "unitPrice", 200)))).get("id").asString();
        return moneyRes("/credit-notes/" + cn + "/post", null);
    }

    /** Value checks and report-only checks green (feature_risk aside); GL ties out. */
    void assertClean() {
        assertIntegrityGreen(true, "feature_risk");
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ F1

    @Test
    @DisplayName("F1: delivery order of a draft invoice → invoice posted → delivery order cancelled → invoice awaiting"
            + " delivery → a new delivery order issues the goods at dispatch, once")
    void cancelAfterPostThenRedeliver() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String firstCode = doCode(d.deliveryOrder());
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        assertThat(onHand(d.product(), main)).as("the delivery issues, not the invoice").isEqualByComparingTo("50");
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/cancel", Map.of("reason", "Truck broke"));

        assertThat(data(d.invoice(), "stockIssuedBy")).as("the cancelled order's mark is released").isNull();
        assertThat(data(d.invoice(), "stockAwaitingDelivery")).isEqualTo(firstCode);
        // Awaiting delivery is listed by the report-only F1 check until the goods leave.
        assertThat(integrityCheck(integrityReport(), "invoice_undelivered").get("ok").asBoolean()).isFalse();

        JsonNode again = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", d.invoice(),
                "locationId", main));
        assertThat(again.get("stockMode").asString()).as("the new delivery order issues the goods").isEqualTo("dispatch");
        assertThat(dec(again.get("lines").get(0).get("orderedQty").asString())).isEqualByComparingTo("10");
        assertThat(data(d.invoice(), "stockIssuedBy")).isEqualTo(again.get("code").asString());
        assertThat(data(d.invoice(), "stockAwaitingDelivery")).isNull();
        Res twice = api("POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", d.invoice(), "locationId",
                main));
        assertThat(twice.status()).as(twice.raw().body()).isIn(400, 409);

        String t = trip(again.get("id").asString(), null);
        JsonNode dispatched = dispatch(t);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        money("/fleet/trips/" + t + "/proof-of-delivery", pod(dispatched, 10, 0));
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        assertClean();
    }

    @Test
    @DisplayName("F1: short close after 6 of 10 → awaiting delivery → issue-undelivered collects the 4 (key required,"
            + " once); a second collection is refused")
    void shortCloseThenCollect() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String t = trip(d.deliveryOrder(), 6);
        JsonNode dispatched = dispatch(t);
        money("/fleet/trips/" + t + "/proof-of-delivery", pod(dispatched, 6, 0));
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("44");
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/close", Map.of("reason", "Customer collects"));
        assertThat(data(d.invoice(), "stockAwaitingDelivery")).isNotNull();
        JsonNode undelivered = integrityCheck(integrityReport(), "invoice_undelivered");
        assertThat(undelivered.get("ok").asBoolean()).isFalse();
        assertThat(undelivered.get("rows").get(0).get("undeliveredQuantity").asString()).isEqualTo("4");

        Map<String, String> noKey = new LinkedHashMap<>();
        noKey.put("Authorization", "Bearer " + auditToken);
        noKey.put(NO_IDEMPOTENCY_KEY, "1");
        Res withoutKey = call("POST", "/invoices/" + d.invoice() + "/issue-undelivered", Map.of(), noKey);
        assertThat(withoutKey.status()).as(withoutKey.raw().body()).isEqualTo(400);

        Map<String, String> h = keyed();
        Res collected = call("POST", "/invoices/" + d.invoice() + "/issue-undelivered", Map.of("locationId", main), h);
        assertThat(collected.status()).as(collected.raw().body()).isEqualTo(200);
        assertThat(collected.body().get("id").asString()).isEqualTo(d.invoice());
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        assertThat(data(d.invoice(), "stockAwaitingDelivery")).isNull();
        // Same key: replayed, nothing issued twice.
        Res replay = call("POST", "/invoices/" + d.invoice() + "/issue-undelivered", Map.of("locationId", main), h);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        Res again = moneyRes("/invoices/" + d.invoice() + "/issue-undelivered", Map.of());
        assertThat(again.status()).isEqualTo(400);
        assertThat(msg(again)).contains("not awaiting delivery");
        assertThat(jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\""
                + " = ? AND \"referenceType\" = 'sales_invoice' AND \"referenceId\" = ?").params(org, d.invoice())
                .query(BigDecimal.class).single()).isEqualByComparingTo("-4");
        assertClean();
    }

    @Test
    @DisplayName("F1: a draft invoice whose delivery order is cancelled before anything left stock issues everything"
            + " at post, as before; a posted invoice that was never marked keeps the invoiced mode")
    void draftCancelUnchanged() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(5);
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/cancel", Map.of("reason", "x"));
        assertThat(data(d.invoice(), "stockIssuedBy")).isNull();
        assertThat(data(d.invoice(), "stockAwaitingDelivery")).isNull();
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("45");
        JsonNode invoiced = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", d.invoice(),
                "locationId", main));
        assertThat(invoiced.get("stockMode").asString()).isEqualTo("invoiced");
        assertClean();
    }

    @Test
    @DisplayName("F1: collection then invoice cancel returns the collected units; fleet-delivered units stay delivered")
    void invoiceCancelAfterCollection() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String t = trip(d.deliveryOrder(), 6);
        JsonNode dispatched = dispatch(t);
        money("/fleet/trips/" + t + "/proof-of-delivery", pod(dispatched, 6, 0));
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);
        okA(201, "POST", "/fleet/delivery-orders/" + d.deliveryOrder() + "/close", Map.of("reason", "Collect"));
        money("/invoices/" + d.invoice() + "/issue-undelivered", Map.of());
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("40");
        money("/invoices/" + d.invoice() + "/cancel", Map.of("reason", "Wrong customer"));
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("44");
        assertClean();
    }

    @Test
    @DisplayName("F1: issue-undelivered needs invoice:post (401 / 403)")
    void issueUndeliveredAuthorization() {
        assertThat(assertAuthorizationMatrix(InvoiceController.class)).isPositive();
        Res anon = call("POST", "/invoices/" + UUID.randomUUID() + "/issue-undelivered", Map.of(), (String) null);
        assertThat(anon.status()).isEqualTo(401);
        Res weak = call("POST", "/invoices/" + UUID.randomUUID() + "/issue-undelivered", Map.of(), weakToken());
        assertThat(weak.status()).isEqualTo(403);
        assertThat(msg(weak)).isEqualTo("Missing required permission(s): invoice:post");
    }

    // ------------------------------------------------------------------ F3

    @Test
    @DisplayName("F3: a proof-of-delivery put-back returns against the trip's issue; a credit note of the full 10 then"
            + " cannot restock the 2 again, the 8 delivered can")
    void podPutBackThenCreditNote() {
        fresh();
        Delivered d = invoiceWithDeliveryOrder(10);
        String t = trip(d.deliveryOrder(), null);
        JsonNode dispatched = dispatch(t);
        money("/fleet/trips/" + t + "/proof-of-delivery", pod(dispatched, 8, 2));
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("42");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" ="
                + " 'fleet_trip_return' AND \"referenceId\" = ? AND \"reversesLedgerId\" IS NOT NULL").params(org, t)
                .query(Long.class).single()).as("the put-back names the trip's issue row").isPositive();
        okA(200, "POST", "/invoices/" + d.invoice() + "/post", null);

        Res full = creditNote(d, 10);
        assertThat(full.status()).as(full.raw().body()).isEqualTo(400);
        assertThat(msg(full)).contains("Cannot return more than was issued");
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("42");
        Res delivered = creditNote(d, 8);
        assertThat(delivered.status()).as(delivered.raw().body()).isEqualTo(200);
        assertThat(onHand(d.product(), main)).isEqualByComparingTo("50");
        assertClean();
    }

    // ------------------------------------------------------------------ F2

    String salesOrder(String customer, String product, int qty) {
        String so = okA(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", main, "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 200)))).get("id").asString();
        okA(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    String orderLine(String so) {
        return okA(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
    }

    String invoiceOrder(String so) {
        String inv = money("/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        okA(200, "POST", "/invoices/" + inv + "/post", null);
        drainOutboxFully();
        return inv;
    }

    Res returnRequest(String so, int qty) {
        return api("POST", "/sales/returns", Map.of("orderId", so, "reason", "damaged", "physicalReturn", true, "lines",
                List.of(Map.of("orderLineId", orderLine(so), "quantity", qty))));
    }

    @Test
    @DisplayName("F2: a return of goods invoiced but never delivered restocks nothing (financial credit only)")
    void returnWithoutDelivery() {
        fresh();
        String customer = partner("Customer", false);
        String p = product("ORD", Map.of("invoicePolicy", "ordered"));
        receive(p, 50, 100);
        String so = salesOrder(customer, p, 5);
        invoiceOrder(so);
        assertThat(onHand(p, main)).as("an order invoice never issues stock").isEqualByComparingTo("50");
        Res created = returnRequest(so, 5);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String r = created.body().get("id").asString();
        okA(200, "POST", "/sales/returns/" + r + "/approve", null);
        money("/sales/returns/" + r + "/receive", Map.of("locationId", main));
        assertThat(onHand(p, main)).as("nothing left stock, so nothing comes back").isEqualByComparingTo("50");
        String line = okA(200, "GET", "/sales/returns/" + r, null).get("lines").get(0).get("id").asString();
        money("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line, "disposition",
                "restock"))));
        assertThat(onHand(p, main)).isEqualByComparingTo("50");
        JsonNode closed = money("/sales/returns/" + r + "/close", Map.of("outcome", "credit_note"));
        assertThat(closed.get("creditAmount").asDouble()).isEqualTo(1000);
        assertThat(onHand(p, main)).isEqualByComparingTo("50");
        assertClean();
    }

    @Test
    @DisplayName("F2: after a credit note restocked 4 of a 10 delivery, a return can claim at most the 6 still out")
    void returnCappedByIssued() {
        fresh();
        String customer = partner("Customer", false);
        String p = product("DEL");
        receive(p, 50, 100);
        String so = salesOrder(customer, p, 10);
        JsonNode dl = money("/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 7"));
        String delivery = dl.get("id").asString();
        money("/sales/deliveries/" + delivery + "/issue", null);
        money("/sales/deliveries/" + delivery + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", dl.get("lines").get(0).get("id").asString(), "deliveredQty", 10))));
        drainOutboxFully();
        assertThat(onHand(p, main)).isEqualByComparingTo("40");
        String inv = invoiceOrder(so);
        Res cn = creditNote(new Delivered(inv, null, p, customer), 4);
        assertThat(cn.status()).as(cn.raw().body()).isEqualTo(200);
        assertThat(onHand(p, main)).isEqualByComparingTo("44");
        drainOutboxFully();

        Res tooMuch = returnRequest(so, 7);
        assertThat(tooMuch.status()).as(tooMuch.raw().body()).isEqualTo(400);
        Res fine = returnRequest(so, 6);
        assertThat(fine.status()).as(fine.raw().body()).isEqualTo(201);
        String r = fine.body().get("id").asString();
        okA(200, "POST", "/sales/returns/" + r + "/approve", null);
        money("/sales/returns/" + r + "/receive", Map.of("locationId", main));
        assertThat(onHand(p, main)).isEqualByComparingTo("50");
        assertClean();
    }
}
