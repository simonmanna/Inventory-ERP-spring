package com.inventoryerp.rental;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.rental.application.RentalCronWorker;
import com.inventoryerp.rental.application.RentalDepositService;
import com.inventoryerp.rental.application.RentalReservationService;
import com.inventoryerp.rental.application.RentalRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Rental (port of the Nest {@code rental} module) on Testcontainers Postgres (ENABLE_RENTAL on in the test profile):
 * catalog rates and packages, serialised units, availability, reservation holds, the agreement lifecycle (create →
 * confirm → checkout with stock transfer, deposit and invoice), extensions, swaps, returns with late fees,
 * inspection with damages, settlement against the deposit, deposit refund / forfeit, customer score holds, service
 * orders, reports, the housekeeping cron, tenant isolation and the 401/403 matrix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RentalLifecycleIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    RentalRows rentalRows;

    @Autowired
    RentalReservationService reservationService;

    @Autowired
    RentalDepositService depositService;

    /** The cron bean is off in tests (app.jobs.enabled=false); drive it directly. */
    RentalCronWorker cron;

    String sfx;
    String adminId;
    String productId;
    String partnerId;
    String stockLoc;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        cron = new RentalCronWorker(rentalRows, reservationService, depositService);
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        productId = product("CAM", false);
        partnerId = ok(201, "POST", "/partners", Map.of("code", "RNT-C-" + sfx, "name", "Rental Cust " + sfx,
                "isCustomer", true)).get("id").asString();
        JsonNode boot = ok(201, "POST", "/rental/locations/bootstrap", null);
        stockLoc = boot.get("stock").asString();
        assertThat(boot.get("out").asString()).isNotBlank();
        receive(productId, 10);
        generateUnits(productId, 4);
        post("/rental/rates", Map.of("productId", productId, "period", "day", "minUnits", 1, "price", 40));
    }

    String product(String prefix, boolean pooled) {
        String id = ok(201, "POST", "/products", Map.of("code", "RNT-" + prefix + "-" + sfx + "-" + UUID.randomUUID()
                .toString().substring(0, 4), "name", prefix + " rental " + sfx, "productType", "stockable",
                "salesPrice", 100, "costPrice", 400)).get("id").asString();
        jdbc.sql("UPDATE \"Product\" SET \"rentalIsPooled\" = ?, \"rentalDepositAmount\" = 50,"
                + " \"rentalReplacementCost\" = 800 WHERE id = ?").params(pooled, id).update();
        return id;
    }

    void receive(String product, int qty) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> stock.receive(StockMove.of(product, stockLoc,
                new BigDecimal(qty)).unitCost(new BigDecimal("400")).source("rental_it", sfx)));
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode post(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as("POST " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String iso(Instant i) {
        return i.toString();
    }

    List<String> generateUnits(String product, int count) {
        String tag = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        JsonNode gen = post("/rental/units/generate", Map.of("productId", product, "count", count, "codeTemplate",
                "U" + sfx + tag + "-{n}", "barcodePrefix", "BC" + sfx + tag, "acquisitionCost", 400));
        List<String> ids = new java.util.ArrayList<>();
        gen.get("units").forEach(u -> ids.add(u.get("id").asString()));
        return ids;
    }

    JsonNode agreement(String product, String unitId, Instant start, Instant due) {
        Map<String, Object> line = new HashMap<>(Map.of("productId", product, "quantity", 1, "ratePeriod", "day",
                "rateUnits", 3));
        if (unitId != null) {
            line.put("unitId", unitId);
        }
        return post("/rental/agreements", Map.of("partnerId", partnerId, "startAt", iso(start), "dueAt", iso(due),
                "lines", List.of(line)));
    }

    @Test
    @DisplayName("catalog: rates upsert / update / delete, packages with items, validation")
    void catalog() {
        JsonNode rate = post("/rental/rates", Map.of("productId", productId, "period", "day", "minUnits", 1, "price", 25));
        String rateId = rate.get("id").asString();
        Res bad = as("POST", "/rental/rates", Map.of("productId", productId, "period", "day", "minUnits", 1, "price", 0));
        assertThat(bad.body().get("message").asString()).isEqualTo("Rate price must be positive");
        Res noProduct = as("POST", "/rental/rates", Map.of("productId", UUID.randomUUID().toString(), "price", 5));
        assertThat(noProduct.body().get("message").asString()).isEqualTo("Product not found");
        ok(200, "PATCH", "/rental/rates/" + rateId, Map.of("price", 30));
        post("/rental/rates", Map.of("productId", productId, "period", "week", "price", 120, "minUnits", 1));
        JsonNode cat = ok(200, "GET", "/rental/catalog?productId=" + productId, null);
        assertThat(cat.get("rates").size()).isGreaterThanOrEqualTo(2);
        assertThat(as("PATCH", "/rental/rates/" + UUID.randomUUID(), Map.of("price", 1)).body().get("message")
                .asString()).isEqualTo("Rate not found");

        JsonNode pkg = post("/rental/packages", Map.of("code", "PKG-" + sfx, "name", "Camera kit", "productId",
                productId, "items", List.of(Map.of("productId", productId, "quantity", 2))));
        String pkgId = pkg.get("id").asString();
        assertThat(pkg.get("items")).hasSize(1);
        ok(200, "PATCH", "/rental/packages/" + pkgId, Map.of("name", "Camera kit v2", "items",
                List.of(Map.of("productId", productId, "quantity", 1))));
        assertThat(ok(200, "GET", "/rental/catalog?includeInactive=true", null).get("packages").size())
                .isGreaterThanOrEqualTo(1);
        ok(200, "DELETE", "/rental/packages/" + pkgId, null);
        assertThat(as("DELETE", "/rental/packages/" + UUID.randomUUID(), null).status()).isEqualTo(404);
        ok(200, "DELETE", "/rental/rates/" + rateId, null);
        assertThat(as("DELETE", "/rental/rates/" + rateId, null).status()).isIn(200, 404);
    }

    @Test
    @DisplayName("units: generate (template + guards), list, update, retire; pooled products have none")
    void units() {
        String p = product("LENS", false);
        receive(p, 3);
        Res badTemplate = as("POST", "/rental/units/generate", Map.of("productId", p, "count", 2, "codeTemplate", "X"));
        assertThat(badTemplate.body().get("message").asString()).isEqualTo(
                "codeTemplate must contain '{n}' (e.g. CAM-{n})");
        Res badCount = as("POST", "/rental/units/generate", Map.of("productId", p, "count", 0, "codeTemplate", "X{n}"));
        assertThat(badCount.body().get("message").asString()).isEqualTo("Count must be between 1 and 500");
        String pooled = product("POOL", true);
        Res pooledRes = as("POST", "/rental/units/generate", Map.of("productId", pooled, "count", 1, "codeTemplate",
                "P{n}"));
        assertThat(pooledRes.body().get("message").asString()).isEqualTo("Pooled products have no individual units");

        List<String> ids = generateUnits(p, 3);
        assertThat(ids).hasSize(3);
        JsonNode list = ok(200, "GET", "/rental/units?productId=" + p, null);
        assertThat(list.toString()).contains("U" + sfx);
        ok(200, "PATCH", "/rental/units/" + ids.get(0), Map.of("conditionGrade", "fair", "notes", "scratched"));
        JsonNode retired = post("/rental/units/" + ids.get(2) + "/retire", Map.of("notes", "end of life"));
        assertThat(retired.toString()).contains("retired");
        assertThat(as("PATCH", "/rental/units/" + UUID.randomUUID(), Map.of("notes", "x")).status()).isEqualTo(404);

        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.DAYS);
        Res count = as("GET", "/rental/availability?productId=" + p + "&startAt=" + iso(start) + "&endAt=" + iso(end),
                null);
        assertThat(count.status()).isEqualTo(200);
        assertThat(count.body().asLong()).isEqualTo(2); // 3 units, one retired
        JsonNode candidates = ok(200, "GET", "/rental/availability/units?productId=" + p + "&startAt=" + iso(start)
                + "&endAt=" + iso(end), null);
        assertThat(candidates).hasSize(2);
        assertThat(as("GET", "/rental/availability?productId=" + p, null).body().get("message").asString())
                .isEqualTo("availability requires productId, startAt, endAt");
        assertThat(as("GET", "/rental/availability/units", null).status()).isEqualTo(400);

        JsonNode svc = post("/rental/service-orders", Map.of("unitId", ids.get(1), "type", "cleaning", "vendor",
                "In-house", "cost", 15));
        String svcId = svc.get("id").asString();
        assertThat(ok(200, "GET", "/rental/service-orders?unitId=" + ids.get(1), null).toString()).contains(svcId);
        JsonNode done = post("/rental/service-orders/" + svcId + "/complete", Map.of("cost", 20));
        assertThat(done.toString()).contains("completed");
        assertThat(as("POST", "/rental/service-orders/" + svcId + "/complete", Map.of()).body().get("message")
                .asString()).isEqualTo("Service order already completed");
        assertThat(as("POST", "/rental/service-orders/" + svcId + "/cancel", Map.of()).body().get("message").asString())
                .isEqualTo("Completed orders cannot be cancelled");
        String svc2 = post("/rental/service-orders", Map.of("unitId", ids.get(1), "type", "repair")).get("id")
                .asString();
        post("/rental/service-orders/" + svc2 + "/cancel", Map.of());
        assertThat(as("POST", "/rental/service-orders", Map.of("unitId", UUID.randomUUID().toString())).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("reservations: hold blocks availability, validation, cancel releases; cron expires stale holds")
    void reservations() {
        String p = product("TRIPOD", false);
        receive(p, 2);
        generateUnits(p, 1);
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant end = start.plus(1, ChronoUnit.DAYS);
        Res backwards = as("POST", "/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(end),
                "endAt", iso(start), "lines", List.of(Map.of("productId", p, "quantity", 1))));
        assertThat(backwards.body().get("message").asString()).isEqualTo("endAt must be after startAt");
        Res noLines = as("POST", "/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(start),
                "endAt", iso(end), "lines", List.of()));
        assertThat(noLines.body().get("message").asString()).isEqualTo("At least one line is required");

        JsonNode hold = post("/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(start), "endAt",
                iso(end), "holdMinutes", 30, "lines", List.of(Map.of("productId", p, "quantity", 1))));
        String id = hold.get("id").asString();
        assertThat(hold.get("status").asString()).isEqualTo("held");
        Res over = as("POST", "/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(start), "endAt",
                iso(end), "lines", List.of(Map.of("productId", p, "quantity", 1))));
        assertThat(over.body().get("message").asString()).startsWith("Insufficient availability for");
        assertThat(post("/rental/reservations/" + id + "/confirm", null).get("id").asString()).isEqualTo(id);
        assertThat(ok(200, "GET", "/rental/reservations?status=held", null).toString()).contains(id);
        assertThat(post("/rental/reservations/" + id + "/cancel", null).get("status").asString()).isEqualTo("cancelled");
        assertThat(as("POST", "/rental/reservations/" + id + "/cancel", null).body().get("message").asString())
                .startsWith("Cannot cancel a reservation in status");
        assertThat(as("POST", "/rental/reservations/" + UUID.randomUUID() + "/confirm", null).status()).isEqualTo(404);

        // A hold that already expired is released by the housekeeping cron.
        String stale = post("/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(start), "endAt",
                iso(end), "lines", List.of(Map.of("productId", p, "quantity", 1)))).get("id").asString();
        jdbc.sql("UPDATE \"RentalReservation\" SET \"expiresAt\" = now() - interval '1 hour' WHERE id = ?").param(stale)
                .update();
        jdbc.sql("UPDATE \"RentalBooking\" SET \"expiresAt\" = now() - interval '1 hour' WHERE \"sourceId\" = ?")
                .param(stale).update();
        cron.housekeeping();
        assertThat(jdbc.sql("SELECT status::text FROM \"RentalReservation\" WHERE id = ?").param(stale)
                .query(String.class).single()).isNotEqualTo("held");

        // A reservation converts into an agreement (create checks availability with the hold still counted).
        generateUnits(p, 1);
        String conv = post("/rental/reservations", Map.of("partnerId", partnerId, "startAt", iso(start), "endAt",
                iso(end), "lines", List.of(Map.of("productId", p, "quantity", 1)))).get("id").asString();
        post("/rental/agreements", Map.of("partnerId", partnerId, "startAt", iso(start), "dueAt", iso(end),
                "reservationId", conv, "lines", List.of(Map.of("productId", p, "quantity", 1))));
        assertThat(jdbc.sql("SELECT status::text FROM \"RentalReservation\" WHERE id = ?").param(conv)
                .query(String.class).single()).isEqualTo("converted");
    }

    @Test
    @DisplayName("agreement lifecycle: create → confirm → checkout (stock + deposit + invoice) → extend → swap"
            + " → return late → inspect with damage → settle against deposit → refund the rest")
    void lifecycle() {
        List<String> units = generateUnits(productId, 2);
        post("/rental/rates", Map.of("productId", productId, "period", "day", "minUnits", 1, "price", 40));
        Instant start = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant due = Instant.now().minus(2, ChronoUnit.DAYS);

        Res backwards = as("POST", "/rental/agreements", Map.of("partnerId", partnerId, "startAt", iso(due), "dueAt",
                iso(start), "lines", List.of(Map.of("productId", productId))));
        assertThat(backwards.body().get("message").asString()).isEqualTo("dueAt must be after startAt");
        Res noPartner = as("POST", "/rental/agreements", Map.of("partnerId", UUID.randomUUID().toString(), "startAt",
                iso(start), "dueAt", iso(due), "lines", List.of(Map.of("productId", productId))));
        assertThat(noPartner.body().get("message").asString()).isEqualTo("Partner not found");

        JsonNode draft = agreement(productId, units.get(0), start, due);
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("agreementNumber").asString()).startsWith("RAG-");
        String lineId = draft.get("lines").get(0).get("id").asString();
        assertThat(new BigDecimal(draft.get("lines").get(0).get("lineTotal").asString())).isEqualByComparingTo("120");

        Res early = as("POST", "/rental/agreements/" + id + "/checkout", Map.of());
        assertThat(early.body().get("message").asString()).startsWith("Checkout requires a confirmed agreement");
        assertThat(post("/rental/agreements/" + id + "/confirm", null).get("status").asString()).isEqualTo("confirmed");
        assertThat(as("POST", "/rental/agreements/" + id + "/confirm", null).body().get("message").asString())
                .startsWith("Cannot confirm an agreement in status");

        JsonNode out = post("/rental/agreements/" + id + "/checkout", Map.of("depositAmount", 200, "depositMethod",
                "cash"));
        assertThat(out.get("status").asString()).isEqualTo("checked_out");
        assertThat(out.get("invoiceId").isNull()).isFalse();
        JsonNode dep = ok(200, "GET", "/rental/agreements/" + id + "/deposit", null);
        assertThat(dep.toString()).contains("200");
        assertThat(jdbc.sql("SELECT status::text FROM \"RentalUnit\" WHERE id = ?").param(units.get(0))
                .query(String.class).single()).isEqualTo("checked_out");

        // Extension: past the current due date, then a backwards one is refused.
        Instant newDue = Instant.now().minus(1, ChronoUnit.DAYS);
        JsonNode ext = post("/rental/extensions", Map.of("agreementId", id, "lineId", lineId, "newDueAt", iso(newDue)));
        assertThat(ext.toString()).isNotEmpty();
        Res backExt = as("POST", "/rental/extensions", Map.of("agreementId", id, "lineId", lineId, "newDueAt",
                iso(start)));
        assertThat(backExt.body().get("message").asString()).isEqualTo("newDueAt must be after the current due date");
        assertThat(as("POST", "/rental/extensions", Map.of("agreementId", id, "lineId", UUID.randomUUID().toString(),
                "newDueAt", iso(newDue))).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/rental/extensions?agreementId=" + id, null).toString()).contains(lineId);

        // Swap to the second unit.
        Res sameUnit = as("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineId, "toUnitId",
                units.get(0)));
        assertThat(sameUnit.status()).isEqualTo(400);
        JsonNode swap = post("/rental/swaps", Map.of("agreementId", id, "lineId", lineId, "toUnitId", units.get(1),
                "reason", "lens fault", "priceDelta", 0));
        assertThat(swap.toString()).isNotEmpty();
        assertThat(ok(200, "GET", "/rental/swaps?agreementId=" + id, null).toString()).contains(units.get(1));
        Res noUnit = as("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineId, "toUnitId",
                UUID.randomUUID().toString()));
        assertThat(noUnit.body().get("message").asString()).isEqualTo("Target unit not found for this product");

        // Return a day late, inspect with damage, settle against the deposit.
        Res noItems = as("POST", "/rental/returns", Map.of("agreementId", id, "items", List.of()));
        assertThat(noItems.body().get("message").asString()).isEqualTo("At least one returned item is required");
        JsonNode ret = post("/rental/returns", Map.of("agreementId", id, "items", List.of(Map.of("agreementLineId",
                lineId, "quantity", 1, "conditionGrade", "fair"))));
        String retId = ret.get("id").asString();
        JsonNode fullRet = ok(200, "GET", "/rental/returns/" + retId, null);
        String retLineId = fullRet.get("lines").get(0).get("id").asString();
        assertThat(ok(200, "GET", "/rental/returns?agreementId=" + id, null).toString()).contains(retId);
        assertThat(as("POST", "/rental/returns/" + retId + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                UUID.randomUUID().toString())))).status()).isEqualTo(400);
        post("/rental/returns/" + retId + "/inspect", Map.of("items", List.of(Map.of("returnLineId", retLineId,
                "conditionGrade", "damaged", "damages", List.of(Map.of("damageType", "scratch", "description",
                        "Body scratch", "chargeAmount", 30), Map.of("damageType", "dent", "chargeAmount", 10))))));
        String damageId = jdbc.sql("SELECT id FROM \"RentalDamage\" WHERE \"returnLineId\" = ? AND \"damageType\" = 'dent'")
                .param(retLineId).query(String.class).single();
        JsonNode waived = post("/rental/damages/" + damageId + "/waive", Map.of("reason", "goodwill"));
        assertThat(waived.toString()).isNotEmpty();
        assertThat(as("POST", "/rental/damages/" + UUID.randomUUID() + "/waive", Map.of()).status()).isEqualTo(404);
        JsonNode settled = post("/rental/returns/" + retId + "/settle", Map.of("paymentMode", "cash"));
        assertThat(settled.toString()).isNotEmpty();
        JsonNode after = ok(200, "GET", "/rental/agreements/" + id, null);
        assertThat(after.get("status").asString()).isIn("settled", "closed", "returned");

        // Whatever deposit is left can be refunded; asking for more is refused.
        JsonNode depAfter = ok(200, "GET", "/rental/agreements/" + id + "/deposit", null);
        Res tooMuch = as("POST", "/rental/agreements/" + id + "/deposit/refund", Map.of("amount", 100000));
        assertThat(tooMuch.body().get("message").asString()).startsWith("Cannot refund");
        assertThat(as("POST", "/rental/agreements/" + id + "/deposit/refund", Map.of("amount", 0)).body().get("message")
                .asString()).isEqualTo("Amount must be positive");
        assertThat(depAfter.toString()).isNotEmpty();

        JsonNode list = ok(200, "GET", "/rental/agreements?partnerId=" + partnerId, null);
        assertThat(list.toString()).contains(id);
        assertThat(as("GET", "/rental/agreements/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("deposits: collect / refund / forfeit guards; cancel releases a draft; score hold blocks checkout")
    void depositsCancelAndScore() {
        List<String> units = generateUnits(productId, 1);
        Instant start = Instant.now().plus(20, ChronoUnit.DAYS);
        Instant due = start.plus(2, ChronoUnit.DAYS);

        String cancelId = agreement(productId, null, start, due).get("id").asString();
        assertThat(post("/rental/agreements/" + cancelId + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(as("POST", "/rental/agreements/" + cancelId + "/cancel", null).body().get("message").asString())
                .startsWith("Cannot cancel an agreement in status");

        String id = agreement(productId, units.get(0), start, due).get("id").asString();
        post("/rental/agreements/" + id + "/confirm", null);
        assertThat(as("GET", "/rental/agreements/" + id + "/deposit", null).status()).isEqualTo(404);

        // Manual hold on the customer blocks checkout without an override.
        String risky = ok(201, "POST", "/partners", Map.of("code", "RNT-R-" + sfx, "name", "Risky " + sfx,
                "isCustomer", true)).get("id").asString();
        JsonNode score = ok(200, "GET", "/rental/scores/" + risky, null);
        assertThat(score.toString()).isNotEmpty();
        ok(200, "PATCH", "/rental/scores/" + risky + "/hold", Map.of("manualHold", true));
        String held = post("/rental/agreements", Map.of("partnerId", risky, "startAt", iso(start), "dueAt", iso(due),
                "lines", List.of(Map.of("productId", productId, "quantity", 1)))).get("id").asString();
        post("/rental/agreements/" + held + "/confirm", null);
        Res blocked = as("POST", "/rental/agreements/" + held + "/checkout", Map.of());
        assertThat(blocked.body().get("message").asString()).startsWith("Partner blocked from checkout");
        JsonNode overridden = post("/rental/agreements/" + held + "/checkout", Map.of("overrideById", adminId,
                "depositAmount", 0));
        assertThat(overridden.get("status").asString()).isEqualTo("checked_out");
        ok(200, "PATCH", "/rental/scores/" + risky + "/hold", Map.of("manualHold", false));

        // Deposit policy default (no explicit amount) and the deposit moves.
        JsonNode out = post("/rental/agreements/" + id + "/checkout", Map.of());
        assertThat(out.get("status").asString()).isEqualTo("checked_out");
        assertThat(as("POST", "/rental/agreements/" + id + "/deposit/collect", Map.of("amount", 0)).body()
                .get("message").asString()).isEqualTo("Deposit amount must be positive");
        post("/rental/agreements/" + id + "/deposit/collect", Map.of("amount", 100, "method", "cash"));
        post("/rental/agreements/" + id + "/deposit/forfeit", Map.of("amount", 20, "note", "late pickup"));
        assertThat(as("POST", "/rental/agreements/" + id + "/deposit/forfeit", Map.of("amount", 0)).body()
                .get("message").asString()).isEqualTo("Amount must be positive");
        assertThat(as("POST", "/rental/agreements/" + id + "/deposit/forfeit", Map.of("amount", 1_000_000)).body()
                .get("message").asString()).startsWith("Cannot forfeit");
        post("/rental/agreements/" + id + "/deposit/refund", Map.of("amount", 30, "method", "cash"));
        assertThat(ok(200, "GET", "/rental/agreements/" + id + "/deposit", null).toString()).contains("refunded");
    }

    @Test
    @DisplayName("reports: utilization, revenue, fleet")
    void reports() {
        String from = Instant.now().minus(60, ChronoUnit.DAYS).toString();
        String to = Instant.now().plus(60, ChronoUnit.DAYS).toString();
        assertThat(ok(200, "GET", "/rental/reports/utilization?from=" + from + "&to=" + to, null)).isNotNull();
        assertThat(ok(200, "GET", "/rental/reports/revenue?from=" + from + "&to=" + to, null)).isNotNull();
        assertThat(ok(200, "GET", "/rental/reports/fleet", null)).isNotNull();
        assertThat(ok(200, "GET", "/rental/reports/utilization", null)).isNotNull();
        assertThat(ok(200, "GET", "/rental/reports/revenue", null)).isNotNull();
        cron.housekeeping();
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of this org's rental records")
    void tenantIsolation() {
        Instant start = Instant.now().plus(40, ChronoUnit.DAYS);
        String id = agreement(productId, null, start, start.plus(1, ChronoUnit.DAYS)).get("id").asString();
        assertThat(call("GET", "/rental/agreements/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/rental/agreements/" + id + "/confirm", null, otherToken).status()).isEqualTo(404);
        JsonNode units = call("GET", "/rental/units", null, otherToken).body();
        assertThat(units.toString()).doesNotContain("U" + sfx);
    }

    @Test
    @DisplayName("authorization: every rental route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.rental")).isEqualTo(47);
    }
}
