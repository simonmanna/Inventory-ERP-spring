package com.inventoryerp.rental;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.rental.application.RentalCronWorker;
import com.inventoryerp.rental.application.RentalDepositService;
import com.inventoryerp.rental.application.RentalReservationService;
import com.inventoryerp.rental.application.RentalRows;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Rental on Postgres (ENABLE_RENTAL on in the test profile): catalog / rates / packages, units, availability with
 * per-unit overlap, reservations and their expiry, the agreement lifecycle (confirm, score gate, checkout with stock
 * transfer + deposit + fee invoice, deposits collect / refund / forfeit, extension, swap, return, inspection,
 * settlement) with balanced journals, service orders, scores, reports, location bootstrap, idempotency on the money
 * routes, tenant isolation and the 401/403 matrix (47 routes).
 */
class RentalIT extends ApiTestSupport {

    private static final String KEY_REQUIRED =
            "An Idempotency-Key header is required for this operation so a retry can never charge twice.";

    @Autowired
    RentalRows rentalRows;

    @Autowired
    RentalReservationService reservationService;

    @Autowired
    RentalDepositService depositService;

    String adminId;
    String customer;
    Map<String, String> locs = new HashMap<>();

    @BeforeAll
    void rentalFixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        for (String code : List.of("RENT-FEE", "RENT-EXTEND", "RENT-LATE", "RENT-DAMAGE")) {
            feeProduct(code);
        }
        assertThat(as("PUT", "/settings/rental.lateFeePerDay", Map.of("value", 10)).status()).isEqualTo(200);
        assertThat(as("PUT", "/settings/rental.depositPolicy", Map.of("value", Map.of("mode", "percent", "percent", 20)))
                .status()).isEqualTo(200);
        JsonNode boot = ok(201, "POST", "/rental/locations/bootstrap", null);
        for (String role : List.of("stock", "out", "cleaning", "repair", "damaged")) {
            locs.put(role, boot.get(role).asString());
        }
        customer = partner();
    }

    // ------------------------------------------------------------------ fixtures

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    void feeProduct(String code) {
        long n = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE \"organizationId\" = ? AND code = ? AND"
                + " \"deletedAt\" IS NULL AND \"isActive\" = true").params(demoOrg, code).query(Long.class).single();
        if (n == 0) {
            jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                            + " \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 'AVCO', 0, 0,"
                            + " now())")
                    .params(UUID.randomUUID().toString(), demoOrg, code, "Rental fee " + code).update();
        }
    }

    /** A rentable stockable product (AVCO, cost 40, price 100). */
    String product(String name, boolean pooled) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"salesPrice\", \"rentalIsPooled\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 40, 100, ?, now())")
                .params(id, demoOrg, "RNT-" + suffix(), name + " " + suffix(), pooled).update();
        return id;
    }

    String partner() {
        String sfx = suffix();
        return ok(201, "POST", "/partners", Map.of("code", "RNT-C-" + sfx, "name", "Renter " + sfx, "isCustomer", true))
                .get("id").asString();
    }

    List<String> units(String productId, int count) {
        String template = "U" + suffix() + "-{n}";
        JsonNode r = ok(201, "POST", "/rental/units/generate", Map.of("productId", productId, "count", count,
                "codeTemplate", template));
        assertThat(r.get("createdCount").asInt()).isEqualTo(count);
        List<String> ids = new ArrayList<>();
        r.get("units").forEach(u -> ids.add(u.get("id").asString()));
        return ids;
    }

    void stockIn(String productId, int qty) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", locs.get("stock"), "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", productId, "quantity", qty,
                        "unitCost", 40))));
    }

    BigDecimal onHand(String productId, String role) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, locs.get(role)).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    static String iso(Instant i) {
        return i.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** A money route call with an {@code Idempotency-Key} (null = no header). */
    Res money(String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call(method, path, body == null ? Map.of() : body, h);
    }

    /** A money route that must answer 200 (idempotent routes answer 200, not 201). */
    JsonNode paid(String path, Object body) {
        Res r = money("POST", path, body, UUID.randomUUID().toString());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static String msg(Res r) {
        return r.body().get("message").asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    void assertError(Res r, int status, String message) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        assertThat(msg(r)).isEqualTo(message);
    }

    /** The invoice's journal entry: posted and debit = credit. */
    BigDecimal assertBalanced(String invoiceId) {
        Map<String, Object> row = jdbc.sql("SELECT coalesce(sum(l.debit), 0) AS d, coalesce(sum(l.credit), 0) AS c,"
                        + " count(*) AS n FROM \"JournalLine\" l JOIN \"Invoice\" i ON i.\"journalEntryId\" ="
                        + " l.\"journalEntryId\" WHERE i.id = ? AND i.\"organizationId\" = ?")
                .params(invoiceId, demoOrg).query().singleRow();
        assertThat(((Number) row.get("n")).longValue()).as("journal lines of " + invoiceId).isGreaterThan(0);
        assertThat((BigDecimal) row.get("d")).isEqualByComparingTo((BigDecimal) row.get("c"));
        assertThat((BigDecimal) row.get("d")).isPositive();
        return (BigDecimal) row.get("d");
    }

    List<Map<String, Object>> feeOrders(String agreementId) {
        return jdbc.sql("SELECT id, notes, \"invoiceId\", \"transactionKind\"::text AS kind FROM \"Order\" WHERE"
                + " \"organizationId\" = ? AND \"rentalAgreementId\" = ? ORDER BY \"createdAt\", id")
                .params(demoOrg, agreementId).query().listOfRows();
    }

    String unitStatus(String unitId) {
        return jdbc.sql("SELECT status::text FROM \"RentalUnit\" WHERE id = ?").param(unitId).query(String.class)
                .single();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    @DisplayName("catalog: rate bands upsert by (product, period, minUnits), update, soft delete; packages CRUD")
    void catalog() {
        String cam = product("Camera", false);
        String lens = product("Lens", false);
        JsonNode r1 = ok(201, "POST", "/rental/rates", Map.of("productId", cam, "period", "day", "minUnits", 1,
                "maxUnits", 2, "price", 50));
        assertThat(dec(r1.get("price"))).isEqualByComparingTo("50");
        assertThat(r1.get("priority").asInt()).isEqualTo(100);
        assertThat(r1.get("isActive").asBoolean()).isTrue();
        assertThat(r1.get("maxUnits").asInt()).isEqualTo(2);
        JsonNode r3 = ok(201, "POST", "/rental/rates", Map.of("productId", cam, "period", "day", "minUnits", 3,
                "price", 40));
        assertThat(r3.get("maxUnits").isNull()).isTrue();
        JsonNode again = ok(201, "POST", "/rental/rates", Map.of("productId", cam, "period", "day", "minUnits", 1,
                "maxUnits", 2, "price", 45, "priority", 5));
        assertThat(again.get("id").asString()).isEqualTo(r1.get("id").asString());
        assertThat(dec(again.get("price"))).isEqualByComparingTo("45");
        assertThat(again.get("priority").asInt()).isEqualTo(5);
        assertError(as("POST", "/rental/rates", Map.of("productId", cam, "period", "day", "minUnits", 1, "price", 0)),
                400, "Rate price must be positive");
        assertError(as("POST", "/rental/rates", Map.of("productId", UUID.randomUUID().toString(), "period", "day",
                "minUnits", 1, "price", 5)), 404, "Product not found");

        String r1Id = r1.get("id").asString();
        JsonNode upd = ok(200, "PATCH", "/rental/rates/" + r1Id, Map.of("price", 55));
        assertThat(dec(upd.get("price"))).isEqualByComparingTo("55");
        assertThat(upd.get("priority").asInt()).isEqualTo(5);
        assertError(as("PATCH", "/rental/rates/" + r1Id, Map.of("price", -1)), 400, "Rate price must be positive");
        assertError(as("PATCH", "/rental/rates/" + UUID.randomUUID(), Map.of()), 404, "Rate not found");

        JsonNode cat = ok(200, "GET", "/rental/catalog?productId=" + cam, null);
        assertThat(cat.get("rates")).hasSize(2);
        assertThat(cat.get("rates").get(0).get("minUnits").asInt()).isEqualTo(1);
        assertThat(cat.get("rates").get(1).get("minUnits").asInt()).isEqualTo(3);
        assertThat(cat.has("packages")).isTrue();

        JsonNode del = ok(200, "DELETE", "/rental/rates/" + r3.get("id").asString(), null);
        assertThat(del.get("deletedAt").isNull()).isFalse();
        assertError(as("DELETE", "/rental/rates/" + r3.get("id").asString(), null), 404, "Rate not found");
        assertThat(ok(200, "GET", "/rental/catalog?productId=" + cam, null).get("rates")).hasSize(1);

        String code = "PKG-" + suffix();
        JsonNode pkg = ok(201, "POST", "/rental/packages", Map.of("code", code, "name", "Shoot kit", "productId", cam,
                "items", List.of(Map.of("productId", lens, "quantity", 2), Map.of("productId", cam, "role", "primary",
                        "priceMode", "add_on", "isRequired", false))));
        String pkgId = pkg.get("id").asString();
        assertThat(pkg.get("items")).hasSize(2);
        JsonNode lensItem = pkg.get("items").get(0).get("productId").asString().equals(lens) ? pkg.get("items").get(0)
                : pkg.get("items").get(1);
        assertThat(dec(lensItem.get("quantity"))).isEqualByComparingTo("2");
        assertThat(lensItem.get("role").asString()).isEqualTo("accessory");
        assertThat(lensItem.get("priceMode").asString()).isEqualTo("included");
        assertThat(lensItem.get("isRequired").asBoolean()).isTrue();
        assertError(as("POST", "/rental/packages", Map.of("code", "X-" + suffix(), "name", "x",
                "productId", UUID.randomUUID().toString(), "items", List.of())), 404, "Package product not found");

        JsonNode listed = null;
        for (JsonNode p : ok(200, "GET", "/rental/catalog", null).get("packages")) {
            if (p.get("id").asString().equals(pkgId)) {
                listed = p;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("items").get(0).get("product").has("code")).isTrue();

        JsonNode renamed = ok(200, "PATCH", "/rental/packages/" + pkgId, Map.of("name", "Shoot kit 2", "isActive",
                false, "items", List.of(Map.of("productId", lens))));
        assertThat(renamed.get("name").asString()).isEqualTo("Shoot kit 2");
        assertThat(renamed.get("isActive").asBoolean()).isFalse();
        assertThat(renamed.get("items")).hasSize(1);
        assertThat(dec(renamed.get("items").get(0).get("quantity"))).isEqualByComparingTo("1");
        boolean hidden = true;
        for (JsonNode p : ok(200, "GET", "/rental/catalog", null).get("packages")) {
            hidden &= !p.get("id").asString().equals(pkgId);
        }
        assertThat(hidden).as("inactive package hidden by default").isTrue();
        boolean shown = false;
        for (JsonNode p : ok(200, "GET", "/rental/catalog?includeInactive=true", null).get("packages")) {
            shown |= p.get("id").asString().equals(pkgId);
        }
        assertThat(shown).isTrue();
        JsonNode gone = ok(200, "DELETE", "/rental/packages/" + pkgId, null);
        assertThat(gone.get("deletedAt").isNull()).isFalse();
        assertError(as("PATCH", "/rental/packages/" + pkgId, Map.of("name", "x")), 404, "Package not found");
        assertError(as("DELETE", "/rental/packages/" + pkgId, null), 404, "Package not found");
    }

    // ------------------------------------------------------------------ units

    @Test
    @DisplayName("units: bulk generate continues numbering, list / search / paging, update, retire is terminal")
    void unitsLifecycle() {
        String cam = product("Body", false);
        String prefix = "CAM" + suffix().toUpperCase() + "-";
        JsonNode gen = ok(201, "POST", "/rental/units/generate", Map.of("productId", cam, "count", 3,
                "codeTemplate", prefix + "{n}", "barcodePrefix", "BC" + prefix, "conditionGrade", "excellent"));
        assertThat(gen.get("createdCount").asInt()).isEqualTo(3);
        assertThat(gen.get("units").get(0).get("unitCode").asString()).isEqualTo(prefix + "001");
        JsonNode more = ok(201, "POST", "/rental/units/generate", Map.of("productId", cam, "count", 2,
                "codeTemplate", prefix + "{n}"));
        assertThat(more.get("units").get(1).get("unitCode").asString()).isEqualTo(prefix + "005");
        assertError(as("POST", "/rental/units/generate", Map.of("productId", cam, "count", 0, "codeTemplate", "A{n}")),
                400, "Count must be between 1 and 500");
        assertError(as("POST", "/rental/units/generate", Map.of("productId", cam, "count", 501, "codeTemplate",
                "A{n}")), 400, "Count must be between 1 and 500");
        assertError(as("POST", "/rental/units/generate", Map.of("productId", cam, "count", 1, "codeTemplate", "A")),
                400, "codeTemplate must contain '{n}' (e.g. CAM-{n})");
        assertError(as("POST", "/rental/units/generate", Map.of("productId", product("Chairs", true), "count", 1,
                "codeTemplate", "A{n}")), 400, "Pooled products have no individual units");
        assertError(as("POST", "/rental/units/generate", Map.of("productId", UUID.randomUUID().toString(), "count", 1,
                "codeTemplate", "A{n}")), 404, "Product not found");

        JsonNode list = ok(200, "GET", "/rental/units?productId=" + cam, null);
        assertThat(list.get("total").asLong()).isEqualTo(5);
        assertThat(list.get("page").asInt()).isEqualTo(1);
        assertThat(list.get("pageSize").asInt()).isEqualTo(50);
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("unitCode").asString()).isEqualTo(prefix + "001");
        assertThat(first.get("barcode").asString()).isEqualTo("BC" + prefix + "0001");
        assertThat(first.get("conditionGrade").asString()).isEqualTo("excellent");
        assertThat(first.get("status").asString()).isEqualTo("available");
        assertThat(first.get("product").get("id").asString()).isEqualTo(cam);
        assertThat(ok(200, "GET", "/rental/units?search=" + (prefix + "002").toLowerCase(), null).get("total").asLong())
                .isEqualTo(1);
        JsonNode page2 = ok(200, "GET", "/rental/units?productId=" + cam + "&page=2&pageSize=2", null);
        assertThat(page2.get("items")).hasSize(2);
        assertThat(page2.get("items").get(0).get("unitCode").asString()).isEqualTo(prefix + "003");
        assertThat(page2.get("page").asString()).isEqualTo("2");
        assertThat(as("GET", "/rental/units?status=broken", null).status()).isEqualTo(500);

        String u1 = first.get("id").asString();
        JsonNode upd = ok(200, "PATCH", "/rental/units/" + u1, Map.of("notes", "spare battery", "conditionGrade",
                "fair", "acquisitionCost", 1200, "attributes", Map.of("color", "black")));
        assertThat(upd.get("notes").asString()).isEqualTo("spare battery");
        assertThat(upd.get("conditionGrade").asString()).isEqualTo("fair");
        assertThat(dec(upd.get("acquisitionCost"))).isEqualByComparingTo("1200");
        assertThat(upd.get("attributes").get("color").asString()).isEqualTo("black");
        assertError(as("PATCH", "/rental/units/" + UUID.randomUUID(), Map.of()), 404, "Rental unit not found");

        String u5 = more.get("units").get(1).get("id").asString();
        JsonNode retired = ok(201, "POST", "/rental/units/" + u5 + "/retire", Map.of("notes", "worn out"));
        assertThat(retired.get("status").asString()).isEqualTo("retired");
        assertThat(retired.get("retiredAt").isNull()).isFalse();
        assertThat(retired.get("notes").asString()).isEqualTo("worn out");
        JsonNode twice = ok(201, "POST", "/rental/units/" + u5 + "/retire", null);
        assertThat(twice.get("retiredAt").asString()).isEqualTo(retired.get("retiredAt").asString());
        assertError(as("POST", "/rental/units/" + UUID.randomUUID() + "/retire", null), 404, "Rental unit not found");
        assertThat(ok(200, "GET", "/rental/units?productId=" + cam + "&status=retired", null).get("total").asLong())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"LifecycleEvent\" WHERE \"entityId\" = ? AND action = 'retire'")
                .param(u5).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ availability + reservations

    @Test
    @DisplayName("availability: candidates − overlapping bookings, half-open windows, buffer days, cleaning units;"
            + " reservations hold, list, confirm, cancel")
    void availabilityAndReservations() {
        String p = product("Tent", false);
        List<String> u = units(p, 2);
        String s = "2031-03-01T00:00:00.000Z";
        String e = "2031-03-05T00:00:00.000Z";
        String q = "/rental/availability?productId=" + p + "&startAt=" + s + "&endAt=" + e;
        assertError(as("GET", "/rental/availability?productId=" + p, null), 400,
                "availability requires productId, startAt, endAt");
        assertError(as("GET", "/rental/availability/units?startAt=" + s + "&endAt=" + e, null), 400,
                "availability/units requires productId, startAt, endAt");
        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(2);
        assertThat(ok(200, "GET", "/rental/availability?productId=" + UUID.randomUUID() + "&startAt=" + s + "&endAt="
                + e, null).asLong()).isZero();
        assertThat(ok(200, "GET", "/rental/availability/units?productId=" + p + "&startAt=" + s + "&endAt=" + e, null))
                .hasSize(2);

        JsonNode res = ok(201, "POST", "/rental/reservations", Map.of("partnerId", customer, "startAt", s, "endAt", e,
                "notes", "hold", "lines", List.of(Map.of("productId", p))));
        String resId = res.get("id").asString();
        assertThat(res.get("reservationNumber").asString()).startsWith("RSV-");
        assertThat(res.get("status").asString()).isEqualTo("held");
        assertThat(res.get("holdMinutes").asInt()).isEqualTo(60);
        assertThat(Instant.parse(res.get("expiresAt").asString())).isAfter(Instant.now().plus(Duration.ofMinutes(55)));
        assertThat(res.get("lines")).hasSize(1);
        assertThat(dec(res.get("lines").get(0).get("quantity"))).isEqualByComparingTo("1");

        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(1);
        // [start, end) windows: a booking ending at 03-05 does not block one starting at 03-05
        assertThat(ok(200, "GET", "/rental/availability?productId=" + p + "&startAt=" + e
                + "&endAt=2031-03-08T00:00:00.000Z", null).asLong()).isEqualTo(2);
        assertThat(ok(200, "GET", "/rental/availability?productId=" + p + "&startAt=2031-03-04T00:00:00.000Z"
                + "&endAt=2031-03-08T00:00:00.000Z", null).asLong()).isEqualTo(1);
        // the edited agreement's own bookings can be excluded (reservation bookings are not)
        assertThat(ok(200, "GET", q + "&excludeSourceId=" + resId, null).asLong()).isEqualTo(1);
        // buffer days extend the requested end
        jdbc.sql("UPDATE \"Product\" SET \"rentalBufferDays\" = 1 WHERE id = ?").param(p).update();
        assertThat(ok(200, "GET", "/rental/availability?productId=" + p + "&startAt=2031-02-27T00:00:00.000Z"
                + "&endAt=2031-02-28T00:00:00.000Z", null).asLong()).isEqualTo(2);
        assertThat(ok(200, "GET", "/rental/availability?productId=" + p + "&startAt=2031-02-27T00:00:00.000Z"
                + "&endAt=2031-02-28T12:00:00.000Z", null).asLong()).isEqualTo(1);
        jdbc.sql("UPDATE \"Product\" SET \"rentalBufferDays\" = 0 WHERE id = ?").param(p).update();
        // count-level holds carry no unit, so every unit stays a candidate
        assertThat(ok(200, "GET", "/rental/availability/units?productId=" + p + "&startAt=" + s + "&endAt=" + e, null))
                .hasSize(2);

        Res over = as("POST", "/rental/reservations", Map.of("partnerId", customer, "startAt", s, "endAt", e,
                "lines", List.of(Map.of("productId", p, "quantity", 2))));
        String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(p).query(String.class).single();
        assertError(over, 400, "Insufficient availability for '" + name + "': requested 2, available 1");
        assertError(as("POST", "/rental/reservations", Map.of("partnerId", customer, "startAt", e, "endAt", s,
                "lines", List.of(Map.of("productId", p)))), 400, "endAt must be after startAt");
        assertError(as("POST", "/rental/reservations", Map.of("partnerId", customer, "startAt", s, "endAt", e,
                "lines", List.of())), 400, "At least one line is required");

        JsonNode list = ok(200, "GET", "/rental/reservations?partnerId=" + customer + "&status=held", null);
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode mine = null;
        for (JsonNode it : list.get("items")) {
            if (it.get("id").asString().equals(resId)) {
                mine = it;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("lines").get(0).get("product").get("id").asString()).isEqualTo(p);
        assertThat(ok(201, "POST", "/rental/reservations/" + resId + "/confirm", null).get("status").asString())
                .isEqualTo("held");
        assertError(as("POST", "/rental/reservations/" + UUID.randomUUID() + "/confirm", null), 404,
                "Reservation not found");

        JsonNode cancelled = ok(201, "POST", "/rental/reservations/" + resId + "/cancel", null);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT DISTINCT status::text FROM \"RentalBooking\" WHERE \"sourceId\" = ?").param(resId)
                .query(String.class).list()).containsExactly("cancelled");
        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(2);
        assertError(as("POST", "/rental/reservations/" + resId + "/cancel", null), 400,
                "Cannot cancel a reservation in status 'cancelled'");
        assertError(as("POST", "/rental/reservations/" + UUID.randomUUID() + "/cancel", null), 404,
                "Reservation not found");

        // cleaning units stay candidates unless the product requires cleaning
        JsonNode so = ok(201, "POST", "/rental/service-orders", Map.of("unitId", u.get(0), "type", "cleaning"));
        assertThat(unitStatus(u.get(0))).isEqualTo("cleaning");
        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(2);
        jdbc.sql("UPDATE \"Product\" SET \"rentalRequiresCleaning\" = true WHERE id = ?").param(p).update();
        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(1);
        assertThat(ok(200, "GET", "/rental/availability/units?productId=" + p + "&startAt=" + s + "&endAt=" + e, null))
                .hasSize(1);
        ok(201, "POST", "/rental/service-orders/" + so.get("id").asString() + "/complete", null);
        assertThat(ok(200, "GET", q, null).asLong()).isEqualTo(2);

        // pooled products have no units, so nothing is ever available (Nest sizes pools by booking qty only)
        String pooled = product("Chairs", true);
        assertThat(ok(200, "GET", "/rental/availability?productId=" + pooled + "&startAt=" + s + "&endAt=" + e, null)
                .asLong()).isZero();
        assertThat(ok(200, "GET", "/rental/availability/units?productId=" + pooled + "&startAt=" + s + "&endAt=" + e,
                null)).isEmpty();
    }

    @Test
    @DisplayName("extension: blocked by another agreement's booking on the only unit; validation messages")
    void extensionOverlap() {
        String p = product("Kayak", false);
        String unit = units(p, 1).getFirst();
        Instant d1 = Instant.parse("2032-05-01T00:00:00Z");
        Instant d2 = Instant.parse("2032-05-03T00:00:00Z");
        Instant d3 = Instant.parse("2032-05-06T00:00:00Z");
        JsonNode a1 = ok(201, "POST", "/rental/agreements", Map.of("partnerId", customer, "startAt", iso(d1),
                "dueAt", iso(d2), "lines", List.of(Map.of("productId", p, "unitId", unit, "unitRate", 30))));
        String line1 = a1.get("lines").get(0).get("id").asString();
        JsonNode a2 = ok(201, "POST", "/rental/agreements", Map.of("partnerId", customer, "startAt", iso(d2),
                "dueAt", iso(d3), "lines", List.of(Map.of("productId", p, "unitRate", 30))));
        String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(p).query(String.class).single();
        assertError(as("POST", "/rental/agreements", Map.of("partnerId", customer, "startAt", iso(d1),
                "dueAt", iso(d3), "lines", List.of(Map.of("productId", p)))), 400,
                "Insufficient availability for '" + name + "': requested 1, available 0");

        String a1Id = a1.get("id").asString();
        assertError(money("POST", "/rental/extensions", Map.of("agreementId", a1Id, "lineId", line1,
                "newDueAt", iso(d2.plus(Duration.ofDays(1)))), UUID.randomUUID().toString()), 400,
                "Requested extension overlaps an existing booking");
        assertError(money("POST", "/rental/extensions", Map.of("agreementId", a1Id, "lineId", line1,
                "newDueAt", iso(d1)), UUID.randomUUID().toString()), 400,
                "newDueAt must be after the current due date");
        assertError(money("POST", "/rental/extensions", Map.of("agreementId", a2.get("id").asString(),
                "lineId", line1, "newDueAt", iso(d3)), UUID.randomUUID().toString()), 400,
                "Line does not belong to the agreement");
        assertError(money("POST", "/rental/extensions", Map.of("agreementId", a1Id, "lineId",
                UUID.randomUUID().toString(), "newDueAt", iso(d3)), UUID.randomUUID().toString()), 404,
                "Agreement line not found");
        assertError(money("POST", "/rental/extensions", Map.of("agreementId", a1Id, "lineId", line1,
                "newDueAt", iso(d3)), null), 400, KEY_REQUIRED);

        // cancel both drafts: bookings released, a second cancel refused
        JsonNode c = ok(201, "POST", "/rental/agreements/" + a2.get("id").asString() + "/cancel", null);
        assertThat(c.get("status").asString()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT DISTINCT status::text FROM \"RentalBooking\" WHERE \"sourceId\" = ?")
                .param(a2.get("id").asString()).query(String.class).list()).containsExactly("cancelled");
        assertError(as("POST", "/rental/agreements/" + a2.get("id").asString() + "/cancel", null), 400,
                "Cannot cancel an agreement in status 'cancelled'");
        assertError(as("POST", "/rental/agreements/" + a2.get("id").asString() + "/confirm", null), 400,
                "Cannot confirm an agreement in status 'cancelled'");
        assertError(as("POST", "/rental/agreements/" + UUID.randomUUID() + "/cancel", null), 404,
                "Agreement not found");
        ok(201, "POST", "/rental/agreements/" + a1Id + "/cancel", null);
    }

    // ------------------------------------------------------------------ the full lifecycle

    @Test
    @DisplayName("lifecycle: agreement → score gate + override → checkout (stock, deposit, invoice) → deposits →"
            + " extension → swap → return → inspect → settle, journals balanced, idempotent money routes")
    void lifecycle() {
        String renter = partner();
        String p = product("Projector", false);
        List<String> u = units(p, 3);
        stockIn(p, 10);
        ok(201, "POST", "/rental/rates", Map.of("productId", p, "period", "day", "minUnits", 1, "price", 50));

        Instant now = Instant.now();
        Instant startAt = now.minus(Duration.ofDays(5));
        Instant dueAt = now.minus(Duration.ofHours(47));
        assertError(as("POST", "/rental/agreements", Map.of("partnerId", renter, "startAt", iso(dueAt), "dueAt",
                iso(startAt), "lines", List.of(Map.of("productId", p)))), 400, "dueAt must be after startAt");
        assertError(as("POST", "/rental/agreements", Map.of("partnerId", renter, "startAt", iso(startAt), "dueAt",
                iso(dueAt), "lines", List.of())), 400, "At least one line is required");
        assertError(as("POST", "/rental/agreements", Map.of("partnerId", UUID.randomUUID().toString(), "startAt",
                iso(startAt), "dueAt", iso(dueAt), "lines", List.of(Map.of("productId", p)))), 404,
                "Partner not found");

        JsonNode ag = ok(201, "POST", "/rental/agreements", Map.of("partnerId", renter, "startAt", iso(startAt),
                "dueAt", iso(dueAt), "notes", "Conference", "lines", List.of(
                        Map.of("productId", p, "unitId", u.get(0), "ratePeriod", "day", "rateUnits", 3),
                        Map.of("productId", p, "unitId", u.get(2), "rateUnits", 3, "unitRate", 40))));
        String id = ag.get("id").asString();
        String number = ag.get("agreementNumber").asString();
        assertThat(number).startsWith("RAG-");
        assertThat(ag.get("status").asString()).isEqualTo("draft");
        assertThat(ag.get("partner").get("id").asString()).isEqualTo(renter);
        assertThat(ag.get("termsSnapshot").get("lateFeePerDay").asInt()).isEqualTo(10);
        assertThat(ag.get("termsSnapshot").get("depositPolicy").get("percent").asInt()).isEqualTo(20);
        String lineA = null;
        String lineB = null;
        for (JsonNode l : ag.get("lines")) {
            assertThat(l.get("product").get("id").asString()).isEqualTo(p);
            if (l.get("unitId").asString().equals(u.get(0))) {
                lineA = l.get("id").asString();
                assertThat(dec(l.get("unitRate"))).as("rate band resolved").isEqualByComparingTo("50");
                assertThat(dec(l.get("lineTotal"))).isEqualByComparingTo("150");
                assertThat(l.get("ratePeriod").asString()).isEqualTo("day");
            } else {
                lineB = l.get("id").asString();
                assertThat(dec(l.get("lineTotal"))).isEqualByComparingTo("120");
            }
        }
        assertThat(lineA).isNotNull();
        assertThat(lineB).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"RentalBooking\" WHERE \"sourceType\" = 'agreement' AND"
                + " \"sourceId\" = ? AND status = 'held'").param(id).query(Long.class).single()).isEqualTo(2);

        JsonNode list = ok(200, "GET", "/rental/agreements?search=" + number.toLowerCase(), null);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("partner").has("code")).isTrue();
        assertThat(list.get("items").get(0).get("lines").get(0).get("unit").has("unitCode")).isTrue();
        assertThat(ok(200, "GET", "/rental/agreements?partnerId=" + renter + "&status=draft", null).get("total")
                .asLong()).isEqualTo(1);
        JsonNode detail = ok(200, "GET", "/rental/agreements/" + id, null);
        assertThat(detail.get("partner").has("phone")).isTrue();
        assertThat(detail.get("lines").get(0).get("unit").has("conditionGrade")).isTrue();
        assertError(as("GET", "/rental/agreements/" + UUID.randomUUID(), null), 404, "Agreement not found");

        String ck = "/rental/agreements/" + id + "/checkout";
        assertError(money("POST", ck, Map.of(), UUID.randomUUID().toString()), 400,
                "Checkout requires a confirmed agreement (status: 'draft')");
        assertError(as("POST", "/rental/returns", Map.of("agreementId", id, "items", List.of(Map.of("agreementLineId",
                lineA)))), 400, "Agreement status 'draft' cannot be returned");

        JsonNode confirmed = ok(201, "POST", "/rental/agreements/" + id + "/confirm", null);
        assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
        assertThat(confirmed.get("confirmedAt").isNull()).isFalse();
        assertThat(confirmed.get("confirmedBy").asString()).isEqualTo(adminId);
        assertThat(jdbc.sql("SELECT DISTINCT status::text FROM \"RentalBooking\" WHERE \"sourceId\" = ?").param(id)
                .query(String.class).list()).containsExactly("confirmed");
        assertError(as("POST", "/rental/agreements/" + id + "/confirm", null), 400,
                "Cannot confirm an agreement in status 'confirmed'");
        assertError(as("POST", "/rental/agreements/" + id + "/cancel", null), 400,
                "Cannot cancel an agreement in status 'confirmed'");

        // score gate: a manual hold blocks checkout unless a manager overrides it
        JsonNode hold = ok(200, "PATCH", "/rental/scores/" + renter + "/hold", Map.of("manualHold", true));
        assertThat(hold.get("manualHold").asBoolean()).isTrue();
        assertThat(hold.get("score").asInt()).isEqualTo(50);
        assertThat(hold.get("band").asString()).isEqualTo("caution");
        assertError(money("POST", ck, Map.of(), UUID.randomUUID().toString()), 400,
                "Partner blocked from checkout (manual_hold, score 50) — requires a manager override");
        assertError(money("POST", ck, Map.of("depositMethod", "card"), null), 400, KEY_REQUIRED);

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("depositMethod", "card", "overrideById", adminId);
        Res out = money("POST", ck, body, key);
        assertThat(out.status()).as(out.raw().body()).isEqualTo(200);
        JsonNode co = out.body();
        assertThat(co.get("status").asString()).isEqualTo("checked_out");
        assertThat(co.get("checkedOutAt").isNull()).isFalse();
        String checkoutInvoice = co.get("invoiceId").asString();
        String depositId = co.get("depositId").asString();
        assertThat(co.get("orderId").asString()).isNotEmpty();
        // replay: same answer, nothing re-run
        Res replay = money("POST", ck, body, key);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("invoiceId").asString()).isEqualTo(checkoutInvoice);
        assertThat(feeOrders(id)).hasSize(1);
        assertThat(money("POST", ck, Map.of("depositMethod", "cash"), key).status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"entityType\" = 'rental_score_hold' AND"
                + " \"entityId\" = ? AND status = 'approved'").param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(ok(200, "PATCH", "/rental/scores/" + renter + "/hold", Map.of("manualHold", false))
                .get("manualHold").asBoolean()).isFalse();

        // checkout effects: units, bookings, stock RENT-STOCK → RENT-OUT, fee invoice, deposit 20 % of 270
        assertThat(unitStatus(u.get(0))).isEqualTo("checked_out");
        assertThat(unitStatus(u.get(2))).isEqualTo("checked_out");
        assertThat(unitStatus(u.get(1))).as("unrelated units untouched").isEqualTo("available");
        assertThat(jdbc.sql("SELECT \"rentalCount\" FROM \"RentalUnit\" WHERE id = ?").param(u.get(0))
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT DISTINCT status::text FROM \"RentalBooking\" WHERE \"sourceId\" = ?").param(id)
                .query(String.class).list()).containsExactly("active");
        assertThat(onHand(p, "stock")).isEqualByComparingTo("8");
        assertThat(onHand(p, "out")).isEqualByComparingTo("2");
        Map<String, Object> order = feeOrders(id).getFirst();
        assertThat(order.get("kind")).isEqualTo("rental");
        assertThat(order.get("invoiceId")).isEqualTo(checkoutInvoice);
        assertThat(jdbc.sql("SELECT subtotal FROM \"Invoice\" WHERE id = ?").param(checkoutInvoice)
                .query(BigDecimal.class).single()).isEqualByComparingTo("270");
        assertBalanced(checkoutInvoice);
        assertThat(jdbc.sql("SELECT count(*) FROM \"OrderItem\" i JOIN \"Product\" p ON p.id = i.\"productId\" WHERE"
                + " i.\"orderId\" = ? AND p.code = 'RENT-FEE' AND i.\"rentalFeeType\" = 'rental'")
                .param(order.get("id")).query(Long.class).single()).isEqualTo(2);

        String dep = "/rental/agreements/" + id + "/deposit";
        JsonNode deposit = ok(200, "GET", dep, null);
        assertThat(deposit.get("id").asString()).isEqualTo(depositId);
        assertThat(dec(deposit.get("totalCollected"))).isEqualByComparingTo("54");
        assertThat(deposit.get("status").asString()).isEqualTo("partially_applied");
        assertThat(deposit.get("movements")).hasSize(1);
        assertThat(deposit.get("movements").get(0).get("type").asString()).isEqualTo("collected");
        assertThat(deposit.get("movements").get(0).get("method").asString()).isEqualTo("card");
        assertThat(deposit.get("movements").get(0).get("note").asString()).isEqualTo("Deposit collected at checkout");
        assertError(as("GET", "/rental/agreements/" + UUID.randomUUID() + "/deposit", null), 404,
                "No deposit for this agreement");

        // deposit ledger: collect / refund / forfeit (append-only movements, FOR UPDATE on the header)
        String collectKey = UUID.randomUUID().toString();
        Map<String, Object> collect = Map.of("method", "cash", "amount", 20, "note", "top-up");
        Res collected = money("POST", dep + "/collect", collect, collectKey);
        assertThat(collected.status()).as(collected.raw().body()).isEqualTo(200);
        assertThat(dec(collected.body().get("totalCollected"))).isEqualByComparingTo("74");
        assertThat(dec(money("POST", dep + "/collect", collect, collectKey).body().get("totalCollected")))
                .isEqualByComparingTo("74");
        assertError(money("POST", dep + "/collect", collect, null), 400, KEY_REQUIRED);
        assertError(money("POST", dep + "/collect", Map.of("method", "cash", "amount", 0),
                UUID.randomUUID().toString()), 400, "Deposit amount must be positive");
        assertError(money("POST", "/rental/agreements/" + UUID.randomUUID() + "/deposit/collect", collect,
                UUID.randomUUID().toString()), 404, "No deposit for this agreement");

        JsonNode refunded = paid(dep + "/refund", Map.of("method", "cash", "amount", 10));
        assertThat(dec(refunded.get("totalRefunded"))).isEqualByComparingTo("10");
        assertError(money("POST", dep + "/refund", Map.of("method", "cash", "amount", 1000),
                UUID.randomUUID().toString()), 400, "Cannot refund 1000: only 64 deposit is available");
        assertError(money("POST", dep + "/refund", Map.of("method", "cash", "amount", -1),
                UUID.randomUUID().toString()), 400, "Amount must be positive");
        assertError(money("POST", dep + "/refund", Map.of("method", "cash", "amount", 1), null), 400, KEY_REQUIRED);
        JsonNode forfeited = paid(dep + "/forfeit", Map.of("amount", 5, "note", "late pickup"));
        assertThat(dec(forfeited.get("totalForfeited"))).isEqualByComparingTo("5");
        assertThat(forfeited.get("status").asString()).isEqualTo("partially_applied");
        assertError(money("POST", dep + "/forfeit", Map.of("amount", 1000), UUID.randomUUID().toString()), 400,
                "Cannot forfeit 1000: only 59 deposit is available");
        assertError(money("POST", dep + "/forfeit", Map.of("amount", 1), null), 400, KEY_REQUIRED);
        List<String> types = new ArrayList<>();
        ok(200, "GET", dep, null).get("movements").forEach(m -> types.add(m.get("type").asString()));
        assertThat(types).containsExactly("collected", "collected", "refunded", "forfeited");
        // the deposit is a sub-ledger only, like Nest: no journal entry is ever linked to a movement
        assertThat(jdbc.sql("SELECT count(*) FROM \"RentalDepositMovement\" WHERE \"depositId\" = ? AND"
                + " \"journalEntryId\" IS NOT NULL").param(depositId).query(Long.class).single()).isZero();

        // extension of line A by one day: fee billed through RENT-EXTEND
        Instant newDue = dueAt.plus(Duration.ofDays(1));
        JsonNode ext = paid("/rental/extensions", Map.of("agreementId", id, "lineId", lineA, "newDueAt", iso(newDue)));
        assertThat(ext.get("additionalUnits").asInt()).isEqualTo(1);
        assertThat(dec(ext.get("additionalCharge"))).isEqualByComparingTo("50");
        assertThat(ext.get("approvedById").asString()).isEqualTo(adminId);
        assertThat(Instant.parse(ext.get("newDueAt").asString())).isEqualTo(newDue.truncatedTo(ChronoUnit.MILLIS));
        assertThat(jdbc.sql("SELECT \"dueAt\" FROM \"RentalAgreementLine\" WHERE id = ?").param(lineA)
                .query(java.sql.Timestamp.class).single().toLocalDateTime().toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(newDue.truncatedTo(ChronoUnit.MILLIS));
        JsonNode exts = ok(200, "GET", "/rental/extensions?agreementId=" + id, null);
        assertThat(exts.get("total").asLong()).isEqualTo(1);
        assertThat(exts.get("items").get(0).get("agreementLine").get("unit").get("id").asString()).isEqualTo(u.get(0));
        assertThat(exts.get("items").get(0).get("agreementLine").get("product").get("id").asString()).isEqualTo(p);

        // swap line A's unit u0 → u1 with a rate delta
        assertError(money("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineA, "toUnitId", u.get(0)),
                UUID.randomUUID().toString()), 400, "Target unit is the current unit");
        assertError(money("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineA, "toUnitId", u.get(2)),
                UUID.randomUUID().toString()), 400, "Target unit is 'checked_out', not available");
        assertError(money("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineA, "toUnitId",
                UUID.randomUUID().toString()), UUID.randomUUID().toString()), 400, "Target unit not found for this product");
        assertError(money("POST", "/rental/swaps", Map.of("agreementId", id, "lineId", lineA, "toUnitId", u.get(1)),
                null), 400, KEY_REQUIRED);
        JsonNode swap = paid("/rental/swaps", Map.of("agreementId", id, "lineId", lineA, "toUnitId", u.get(1),
                "reason", "flicker", "priceDelta", 15));
        assertThat(swap.get("fromUnitId").asString()).isEqualTo(u.get(0));
        assertThat(swap.get("toUnitId").asString()).isEqualTo(u.get(1));
        assertThat(dec(swap.get("priceDelta"))).isEqualByComparingTo("15");
        assertThat(unitStatus(u.get(0))).isEqualTo("cleaning");
        assertThat(unitStatus(u.get(1))).isEqualTo("checked_out");
        assertThat(onHand(p, "stock")).isEqualByComparingTo("7");
        assertThat(onHand(p, "out")).isEqualByComparingTo("2");
        assertThat(onHand(p, "cleaning")).isEqualByComparingTo("1");
        JsonNode swaps = ok(200, "GET", "/rental/swaps?agreementId=" + id, null);
        assertThat(swaps.get("total").asLong()).isEqualTo(1);
        assertThat(swaps.get("items").get(0).get("agreementLine").get("unitId").asString()).isEqualTo(u.get(1));

        List<Map<String, Object>> orders = feeOrders(id);
        assertThat(orders).hasSize(3);
        assertThat((String) orders.get(1).get("notes")).isEqualTo("Rental extension — agreement " + id);
        assertThat((String) orders.get(2).get("notes")).isEqualTo("Rental rental — agreement " + id);
        assertThat(assertBalanced((String) orders.get(1).get("invoiceId"))).isPositive();
        assertBalanced((String) orders.get(2).get("invoiceId"));

        // counter return of both lines → returned; late days from each line's due date
        assertError(as("POST", "/rental/returns", Map.of("agreementId", id, "items", List.of())), 400,
                "At least one returned item is required");
        String ghost = UUID.randomUUID().toString();
        assertError(as("POST", "/rental/returns", Map.of("agreementId", id, "items", List.of(Map.of("agreementLineId",
                ghost)))), 400, "Unknown agreement line " + ghost);
        assertError(as("POST", "/rental/returns", Map.of("agreementId", UUID.randomUUID().toString(), "items",
                List.of(Map.of("agreementLineId", lineA)))), 404, "Agreement not found");
        JsonNode ret = ok(201, "POST", "/rental/returns", Map.of("agreementId", id, "notes", "counter", "items", List.of(
                Map.of("agreementLineId", lineA, "quantity", 1), Map.of("agreementLineId", lineB, "conditionGrade",
                        "good"))));
        String retId = ret.get("id").asString();
        assertThat(ret.get("returnNumber").asString()).startsWith("RET-");
        assertThat(ret.get("receivedById").asString()).isEqualTo(adminId);
        assertThat(ok(200, "GET", "/rental/agreements/" + id, null).get("status").asString()).isEqualTo("returned");
        JsonNode got = ok(200, "GET", "/rental/returns/" + retId, null);
        assertThat(got.get("agreement").get("agreementNumber").asString()).isEqualTo(number);
        assertThat(got.get("lines")).hasSize(2);
        String retLineA = null;
        String retLineB = null;
        for (JsonNode l : got.get("lines")) {
            assertThat(l.get("damages")).isEmpty();
            if (l.get("agreementLineId").asString().equals(lineA)) {
                retLineA = l.get("id").asString();
                assertThat(l.get("unitId").asString()).isEqualTo(u.get(1));
                assertThat(l.get("lateDays").asInt()).isEqualTo(1);
                assertThat(dec(l.get("lateFeeAmount"))).isEqualByComparingTo("10");
            } else {
                retLineB = l.get("id").asString();
                assertThat(l.get("lateDays").asInt()).isEqualTo(2);
                assertThat(dec(l.get("lateFeeAmount"))).isEqualByComparingTo("20");
                assertThat(l.get("isMissing").asBoolean()).isFalse();
                assertThat(l.get("returnedAt").isNull()).isFalse();
            }
        }
        assertThat(ok(200, "GET", "/rental/returns?agreementId=" + id, null).get("total").asLong()).isEqualTo(1);
        assertError(as("GET", "/rental/returns/" + UUID.randomUUID(), null), 404, "Return not found");

        // inspection: A damaged (2 damages), B clean
        String ghostLine = UUID.randomUUID().toString();
        assertError(as("POST", "/rental/returns/" + retId + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                ghostLine)))), 400, "Unknown return line " + ghostLine);
        JsonNode inspected = ok(201, "POST", "/rental/returns/" + retId + "/inspect", Map.of("items", List.of(
                Map.of("returnLineId", retLineA, "conditionGrade", "poor", "damages", List.of(
                        Map.of("damageType", "scratch", "description", "lens", "chargeAmount", 25),
                        Map.of("damageType", "dent", "chargeAmount", 7))),
                Map.of("returnLineId", retLineB, "notes", "clean"))));
        // Nest answers with the return as committed before the inspection (read outside its transaction)
        for (JsonNode l : inspected.get("lines")) {
            assertThat(l.get("damages")).isEmpty();
            assertThat(l.get("inspectedAt").isNull()).isTrue();
        }
        JsonNode after = ok(200, "GET", "/rental/returns/" + retId, null);
        String dentId = null;
        for (JsonNode l : after.get("lines")) {
            assertThat(l.get("inspectedAt").isNull()).isFalse();
            if (l.get("id").asString().equals(retLineA)) {
                assertThat(l.get("conditionGrade").asString()).isEqualTo("poor");
                assertThat(l.get("damages")).hasSize(2);
                for (JsonNode d : l.get("damages")) {
                    if (d.get("damageType").asString().equals("dent")) {
                        dentId = d.get("id").asString();
                    }
                }
            } else {
                assertThat(l.get("conditionGrade").asString()).isEqualTo("good");
                assertThat(l.get("notes").asString()).isEqualTo("clean");
            }
        }
        assertThat(unitStatus(u.get(1))).isEqualTo("damaged");
        assertThat(unitStatus(u.get(2))).isEqualTo("available");
        assertThat(onHand(p, "damaged")).isEqualByComparingTo("1");
        assertThat(onHand(p, "out")).isEqualByComparingTo("1");
        assertThat(onHand(p, "cleaning")).isEqualByComparingTo("0");
        assertThat(onHand(p, "stock")).isEqualByComparingTo("8");

        JsonNode waived = ok(201, "POST", "/rental/damages/" + dentId + "/waive", Map.of("reason", "goodwill"));
        assertThat(waived.get("waivedById").asString()).isEqualTo(adminId);
        assertThat(waived.get("waiveReason").asString()).isEqualTo("goodwill");
        assertError(as("POST", "/rental/damages/" + UUID.randomUUID() + "/waive", null), 404,
                "Damage record not found");

        // settlement: late 30 + damage 25 (dent waived in the body), deposit 40 applied, 19 refunded
        String st = "/rental/returns/" + retId + "/settle";
        Map<String, Object> settleBody = Map.of("depositApplied", 40, "waiveDamageIds", List.of(dentId));
        assertError(money("POST", st, settleBody, null), 400, KEY_REQUIRED);
        String settleKey = UUID.randomUUID().toString();
        Res settled = money("POST", st, settleBody, settleKey);
        assertThat(settled.status()).as(settled.raw().body()).isEqualTo(200);
        assertThat(settled.body().get("id").asString()).isEqualTo(retId);
        assertThat(money("POST", st, settleBody, settleKey).status()).isEqualTo(200);
        assertError(money("POST", st, settleBody, UUID.randomUUID().toString()), 400,
                "Agreement status 'closed' cannot be settled");
        assertError(money("POST", "/rental/returns/" + UUID.randomUUID() + "/settle", Map.of(),
                UUID.randomUUID().toString()), 404, "Return not found");

        JsonNode closed = ok(200, "GET", "/rental/agreements/" + id, null);
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(dec(closed.get("settlementTotal"))).isEqualByComparingTo("55");
        assertThat(dec(closed.get("lateFeeTotal"))).isEqualByComparingTo("30");
        assertThat(dec(closed.get("damageTotal"))).isEqualByComparingTo("25");
        assertThat(dec(closed.get("depositApplied"))).isEqualByComparingTo("40");
        assertThat(closed.get("closedAt").isNull()).isFalse();
        JsonNode finalDeposit = ok(200, "GET", dep, null);
        assertThat(dec(finalDeposit.get("totalApplied"))).isEqualByComparingTo("40");
        assertThat(dec(finalDeposit.get("totalRefunded"))).isEqualByComparingTo("29");
        assertThat(finalDeposit.get("status").asString()).isEqualTo("applied");
        JsonNode lastMove = finalDeposit.get("movements").get(finalDeposit.get("movements").size() - 1);
        assertThat(lastMove.get("type").asString()).isEqualTo("refunded");
        assertThat(lastMove.get("note").asString()).startsWith("Deposit refund after RET-");
        assertThat(jdbc.sql("SELECT DISTINCT status::text FROM \"RentalBooking\" WHERE \"sourceId\" = ?").param(id)
                .query(String.class).list()).containsExactly("completed");

        orders = feeOrders(id);
        assertThat(orders).hasSize(4);
        Map<String, Object> settleOrder = orders.get(3);
        assertThat((String) settleOrder.get("notes")).isEqualTo("Rental damage — agreement " + id);
        // like Nest, the settlement invoice bills the full late + damage charges whatever the deposit covered
        assertThat(jdbc.sql("SELECT subtotal FROM \"Invoice\" WHERE id = ?").param(settleOrder.get("invoiceId"))
                .query(BigDecimal.class).single()).isEqualByComparingTo("55");
        assertBalanced((String) settleOrder.get("invoiceId"));
        assertThat(jdbc.sql("SELECT string_agg(i.\"rentalFeeType\"::text, ',' ORDER BY i.\"lineNumber\") FROM"
                + " \"OrderItem\" i WHERE i.\"orderId\" = ?").param(settleOrder.get("id")).query(String.class).single())
                .isEqualTo("late,damage");

        // score recomputed at settlement: 2 late lines (−10), 2 damage events (−30)
        JsonNode score = ok(200, "GET", "/rental/scores/" + renter, null);
        assertThat(score.get("score").asInt()).isEqualTo(60);
        assertThat(score.get("band").asString()).isEqualTo("caution");
        assertThat(score.get("lateReturns").asInt()).isEqualTo(2);
        assertThat(score.get("damageEvents").asInt()).isEqualTo(2);
        assertThat(score.get("lostItems").asInt()).isZero();
        assertThat(score.get("completedRentals").asInt()).isEqualTo(1);
        assertThat(score.get("manualHold").asBoolean()).isFalse();

        // reports
        JsonNode revenue = ok(200, "GET", "/rental/reports/revenue", null);
        assertThat(revenue.get("closedAgreements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dec(revenue.get("lateFeeIncome"))).isGreaterThanOrEqualTo(new BigDecimal("30"));
        assertThat(revenue.has("from")).isTrue();
        JsonNode util = ok(200, "GET", "/rental/reports/utilization?from=" + iso(now.minus(Duration.ofDays(10)))
                + "&to=" + iso(now.plus(Duration.ofDays(1))), null);
        assertThat(util.get("fleet").asLong()).isGreaterThan(0);
        assertThat(util.get("hiredUnitDays").asDouble()).isGreaterThan(0);
        assertThat(util.get("from").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(Instant.parse(util.get("from").asString()))
                .isEqualTo(now.minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MILLIS));
        JsonNode fleet = ok(200, "GET", "/rental/reports/fleet?status=damaged", null);
        assertThat(fleet.get("byStatus").get("damaged").asLong()).isGreaterThanOrEqualTo(1);
        boolean seen = false;
        for (JsonNode f : fleet.get("items")) {
            assertThat(f.get("status").asString()).isEqualTo("damaged");
            seen |= f.get("id").asString().equals(u.get(1));
            assertThat(f.has("product")).isTrue();
            assertThat(f.has("agreementLines")).isTrue();
        }
        assertThat(seen).isTrue();
    }

    // ------------------------------------------------------------------ cron, partial returns, low score gate

    @Test
    @DisplayName("housekeeping: expires held reservations and forfeits deposits of returns left unsettled 14 days;"
            + " partial returns; low-score gate")
    void housekeeping() {
        String renter = partner();
        String p = product("Speaker", false);
        List<String> u = units(p, 3);
        stockIn(p, 5);
        Instant now = Instant.now();
        String s = iso(now.minus(Duration.ofDays(1)));
        String e = iso(now.plus(Duration.ofDays(2)));

        JsonNode res = ok(201, "POST", "/rental/reservations", Map.of("partnerId", renter, "startAt",
                iso(now.plus(Duration.ofDays(20))), "endAt", iso(now.plus(Duration.ofDays(21))), "holdMinutes", 0,
                "lines", List.of(Map.of("productId", p))));
        assertThat(res.get("holdMinutes").asInt()).isZero();

        // single-line agreement, fully returned, never settled
        JsonNode y = ok(201, "POST", "/rental/agreements", Map.of("partnerId", renter, "startAt", s, "dueAt", e,
                "lines", List.of(Map.of("productId", p, "unitId", u.get(2), "unitRate", 20))));
        String yId = y.get("id").asString();
        ok(201, "POST", "/rental/agreements/" + yId + "/confirm", null);
        JsonNode yOut = paid("/rental/agreements/" + yId + "/checkout", Map.of("depositAmount", 40));
        assertThat(yOut.get("status").asString()).isEqualTo("checked_out");
        ok(201, "POST", "/rental/returns", Map.of("agreementId", yId, "items", List.of(Map.of("agreementLineId",
                y.get("lines").get(0).get("id").asString()))));
        JsonNode yReturned = ok(200, "GET", "/rental/agreements/" + yId, null);
        assertThat(yReturned.get("status").asString()).isEqualTo("returned");
        // Nest never stamps returnedAt on the agreement, so the auto-forfeit only sees rows stamped by other means
        assertThat(yReturned.get("returnedAt").isNull()).isTrue();
        jdbc.sql("UPDATE \"RentalAgreement\" SET \"returnedAt\" = now() - interval '15 days' WHERE id = ?").param(yId)
                .update();

        // two-line agreement returned line by line: each return only sees its own items → stays partially_returned
        JsonNode x = ok(201, "POST", "/rental/agreements", Map.of("partnerId", renter, "startAt", s, "dueAt", e,
                "lines", List.of(Map.of("productId", p, "unitId", u.get(0), "unitRate", 20),
                        Map.of("productId", p, "unitId", u.get(1), "unitRate", 20))));
        String xId = x.get("id").asString();
        ok(201, "POST", "/rental/agreements/" + xId + "/confirm", null);
        paid("/rental/agreements/" + xId + "/checkout", Map.of("depositAmount", 0));
        assertThat(dec(ok(200, "GET", "/rental/agreements/" + xId + "/deposit", null).get("totalCollected")))
                .isEqualByComparingTo("0");
        for (JsonNode l : x.get("lines")) {
            ok(201, "POST", "/rental/returns", Map.of("agreementId", xId, "items", List.of(Map.of("agreementLineId",
                    l.get("id").asString()))));
            assertThat(ok(200, "GET", "/rental/agreements/" + xId, null).get("status").asString())
                    .isEqualTo("partially_returned");
        }

        new RentalCronWorker(rentalRows, reservationService, depositService).runForOrg(demoOrg);

        assertThat(ok(201, "POST", "/rental/reservations/" + res.get("id").asString() + "/confirm", null)
                .get("status").asString()).isEqualTo("cancelled");
        JsonNode yDeposit = ok(200, "GET", "/rental/agreements/" + yId + "/deposit", null);
        assertThat(dec(yDeposit.get("totalForfeited"))).isEqualByComparingTo("40");
        assertThat(yDeposit.get("status").asString()).isEqualTo("forfeited");
        JsonNode auto = yDeposit.get("movements").get(yDeposit.get("movements").size() - 1);
        assertThat(auto.get("note").asString()).isEqualTo("Auto-forfeit after 14 days");
        assertThat(auto.get("method").asString()).isEqualTo("forfeited");

        // a score below 50 blocks checkout until a manager overrides
        String blocked = partner();
        ok(200, "GET", "/rental/scores/" + blocked, null);
        jdbc.sql("UPDATE \"RentalCustomerScore\" SET score = 40, band = 'blocked' WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ?").params(demoOrg, blocked).update();
        String q = units(p, 1).getFirst();
        JsonNode z = ok(201, "POST", "/rental/agreements", Map.of("partnerId", blocked,
                "startAt", "2034-01-01T00:00:00.000Z", "dueAt", "2034-01-02T00:00:00.000Z",
                "lines", List.of(Map.of("productId", p, "unitId", q, "unitRate", 20))));
        ok(201, "POST", "/rental/agreements/" + z.get("id").asString() + "/confirm", null);
        assertError(money("POST", "/rental/agreements/" + z.get("id").asString() + "/checkout", Map.of(),
                UUID.randomUUID().toString()), 400,
                "Partner blocked from checkout (low_score, score 40) — requires a manager override");
    }

    // ------------------------------------------------------------------ service orders + scores

    @Test
    @DisplayName("service orders: open takes the unit out of the pool, complete returns it, cancel rules")
    void serviceOrders() {
        String p = product("Drone", false);
        String unit = units(p, 1).getFirst();
        JsonNode repair = ok(201, "POST", "/rental/service-orders", Map.of("unitId", unit, "type", "repair", "vendor",
                "Fixit", "cost", 12.5, "notes", "rotor"));
        assertThat(repair.get("serviceNumber").asString()).startsWith("SRV-");
        assertThat(repair.get("status").asString()).isEqualTo("pending");
        assertThat(dec(repair.get("cost"))).isEqualByComparingTo("12.5");
        assertThat(unitStatus(unit)).isEqualTo("repair");
        JsonNode clean = ok(201, "POST", "/rental/service-orders", Map.of("unitId", unit, "type", "alteration"));
        assertThat(dec(clean.get("cost"))).isEqualByComparingTo("0");
        assertError(as("POST", "/rental/service-orders", Map.of("unitId", UUID.randomUUID().toString(), "type",
                "repair")), 404, "Rental unit not found");

        JsonNode list = ok(200, "GET", "/rental/service-orders?unitId=" + unit, null);
        assertThat(list.get("total").asLong()).isEqualTo(2);
        assertThat(list.get("items").get(0).get("unit").get("id").asString()).isEqualTo(unit);
        assertThat(ok(200, "GET", "/rental/service-orders?unitId=" + unit + "&status=pending", null).get("total")
                .asLong()).isEqualTo(2);

        String rid = repair.get("id").asString();
        JsonNode done = ok(201, "POST", "/rental/service-orders/" + rid + "/complete", Map.of("cost", 20));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("cost"))).isEqualByComparingTo("20");
        assertThat(done.get("vendor").asString()).isEqualTo("Fixit");
        assertThat(done.get("completedAt").isNull()).isFalse();
        assertThat(unitStatus(unit)).isEqualTo("available");
        assertError(as("POST", "/rental/service-orders/" + rid + "/complete", null), 400,
                "Service order already completed");
        assertError(as("POST", "/rental/service-orders/" + rid + "/cancel", null), 400,
                "Completed orders cannot be cancelled");
        assertThat(ok(201, "POST", "/rental/service-orders/" + clean.get("id").asString() + "/cancel", null)
                .get("status").asString()).isEqualTo("cancelled");
        assertError(as("POST", "/rental/service-orders/" + UUID.randomUUID() + "/cancel", null), 404,
                "Service order not found");
        assertError(as("POST", "/rental/service-orders/" + UUID.randomUUID() + "/complete", null), 404,
                "Service order not found");

        // a fresh partner scores 100 / good; the hold flag is upserted
        String fresh = partner();
        JsonNode score = ok(200, "GET", "/rental/scores/" + fresh, null);
        assertThat(score.get("score").asInt()).isEqualTo(100);
        assertThat(score.get("band").asString()).isEqualTo("good");
        JsonNode hold = ok(200, "PATCH", "/rental/scores/" + fresh + "/hold", Map.of("manualHold", "yes"));
        assertThat(hold.get("manualHold").asBoolean()).isTrue();
        assertThat(hold.get("score").asInt()).isEqualTo(100);
    }

    // ------------------------------------------------------------------ tenancy + authorization

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's rental data and bootstraps its own"
            + " locations")
    void tenantIsolation() {
        String p = product("Tripod", false);
        String unit = units(p, 1).getFirst();
        JsonNode ag = ok(201, "POST", "/rental/agreements", Map.of("partnerId", customer,
                "startAt", "2033-01-01T00:00:00.000Z", "dueAt", "2033-01-02T00:00:00.000Z",
                "lines", List.of(Map.of("productId", p, "unitId", unit, "unitRate", 10))));
        String id = ag.get("id").asString();

        assertThat(call("GET", "/rental/agreements", null, otherToken).body().get("total").asLong()).isZero();
        assertThat(call("GET", "/rental/agreements/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/rental/units?productId=" + p, null, otherToken).body().get("total").asLong()).isZero();
        assertThat(call("PATCH", "/rental/units/" + unit, Map.of("notes", "x"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/rental/agreements/" + id + "/confirm", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/rental/agreements/" + id + "/deposit", null, otherToken).status()).isEqualTo(404);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + otherToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/rental/agreements/" + id + "/deposit/collect", Map.of("method", "cash", "amount", 5),
                h).status()).isEqualTo(404);
        assertThat(call("GET", "/rental/catalog?productId=" + p, null, otherToken).body().get("rates")).isEmpty();
        assertThat(call("GET", "/rental/availability?productId=" + p + "&startAt=2033-01-01&endAt=2033-01-02", null,
                otherToken).body().asLong()).isZero();
        assertThat(call("GET", "/rental/reports/fleet", null, otherToken).body().get("items")).isEmpty();

        Res boot = call("POST", "/rental/locations/bootstrap", null, otherToken);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        assertThat(boot.body().size()).isEqualTo(5);
        assertThat(boot.body().get("stock").asString()).isNotEqualTo(locs.get("stock"));
        // DEMO's mapping is untouched and bootstrap stays idempotent
        assertThat(ok(201, "POST", "/rental/locations/bootstrap", null).get("stock").asString())
                .isEqualTo(locs.get("stock"));
        ok(201, "POST", "/rental/agreements/" + id + "/cancel", null);
    }

    @Test
    @DisplayName("authorization: every rental route is 401 without a token and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.rental")).isEqualTo(47);
    }
}
