package com.inventoryerp.rental;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR M-7 (P1, deliberate Nest deviation, owner decision P15-3): RENT-OUT is decremented exactly once per return. The
 * counter return moves what physically came back RENT-OUT → RENT-CLEAN; inspection moves it on from RENT-CLEAN
 * (clean → RENT-STOCK, damaged → RENT-DAMAGED). Missing units stay in RENT-OUT until found. A line cannot be
 * returned beyond its quantity, and a return line cannot be inspected twice (M-8, the part that would move stock again). Integrity and
 * the GL tie-out stay green after every step.
 */
class RentalStockLifecycleIT extends InventoryAuditSupport {

    String stockLoc;
    String outLoc;
    String cleanLoc;
    String damagedLoc;
    String partner;

    @BeforeAll
    void rental() {
        JsonNode boot = okA(201, "POST", "/rental/locations/bootstrap", null);
        stockLoc = boot.get("stock").asString();
        outLoc = boot.get("out").asString();
        cleanLoc = boot.get("cleaning").asString();
        damagedLoc = boot.get("damaged").asString();
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        partner = okA(201, "POST", "/partners", Map.of("code", "RNT-M7-" + sfx, "name", "Renter " + sfx,
                "isCustomer", true)).get("id").asString();
    }

    // ------------------------------------------------------------------ fixtures

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    /** A rentable product with {@code units} rental units and {@code stock} units in RENT-STOCK at cost 40. */
    String rentable(String code, boolean pooled, int stock) {
        String p = product(code, Map.of("rentalIsPooled", pooled, "costPrice", 40, "salesPrice", 100));
        receive(p, stockLoc, stock, 40, null);
        return p;
    }

    List<String> units(String productId, int count) {
        JsonNode r = okA(201, "POST", "/rental/units/generate", Map.of("productId", productId, "count", count,
                "codeTemplate", "M7" + UUID.randomUUID().toString().substring(0, 6) + "-{n}"));
        List<String> ids = new ArrayList<>();
        r.get("units").forEach(u -> ids.add(u.get("id").asString()));
        return ids;
    }

    static String iso(Instant i) {
        return i.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** Create, confirm and check out an agreement; returns {agreementId, lineId...} in line order. */
    List<String> checkedOut(List<Map<String, Object>> lines) {
        Instant now = Instant.now();
        JsonNode ag = okA(201, "POST", "/rental/agreements", Map.of("partnerId", partner, "startAt",
                iso(now.minus(Duration.ofHours(2))), "dueAt", iso(now.plus(Duration.ofDays(2))), "lines", lines));
        String id = ag.get("id").asString();
        okA(201, "POST", "/rental/agreements/" + id + "/confirm", null);
        Res co = keyed("POST", "/rental/agreements/" + id + "/checkout", Map.of("depositAmount", 0));
        assertThat(co.status()).as(co.raw().body()).isEqualTo(200);
        List<String> out = new ArrayList<>(List.of(id));
        // agreement lines in request order (matched by product + unit)
        JsonNode detail = okA(200, "GET", "/rental/agreements/" + id, null);
        for (Map<String, Object> l : lines) {
            for (JsonNode dl : detail.get("lines")) {
                boolean sameUnit = l.get("unitId") == null ? dl.get("unitId").isNull()
                        : l.get("unitId").equals(dl.get("unitId").asString());
                if (dl.get("productId").asString().equals(l.get("productId")) && sameUnit
                        && !out.contains(dl.get("id").asString())) {
                    out.add(dl.get("id").asString());
                    break;
                }
            }
        }
        return out;
    }

    /** Counter return; returns {returnId, returnLineId...} in item order. */
    List<String> returned(String agreementId, List<Map<String, Object>> items) {
        JsonNode ret = okA(201, "POST", "/rental/returns", Map.of("agreementId", agreementId, "items", items));
        String retId = ret.get("id").asString();
        List<String> out = new ArrayList<>(List.of(retId));
        JsonNode got = okA(200, "GET", "/rental/returns/" + retId, null);
        for (Map<String, Object> i : items) {
            for (JsonNode l : got.get("lines")) {
                if (l.get("agreementLineId").asString().equals(i.get("agreementLineId"))) {
                    out.add(l.get("id").asString());
                }
            }
        }
        return out;
    }

    /** Σ quantity change of the agreement's stock moves at RENT-OUT (checkout +, returns −). */
    BigDecimal outMoves(String agreementId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\""
                + " = ? AND \"locationId\" = ? AND \"referenceId\" = ?").params(org, outLoc, agreementId)
                .query(BigDecimal.class).single();
    }

    void assertBooksGreen() {
        List<String> failing = failingChecks(integrityReport(), "checks");
        assertThat(failing).as("integrity checks: " + failing).isEmpty();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @DisplayName("M-7: out → return → clean → stock decrements RENT-OUT once; a second inspection is refused")
    void cleanReturnLeavesRentOut() {
        String p = rentable("M7-CLEAN", false, 3);
        List<String> u = units(p, 1);
        List<String> ag = checkedOut(List.of(Map.of("productId", p, "unitId", u.get(0), "unitRate", 20)));
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("1");
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("2");
        assertBooksGreen();

        List<String> ret = returned(ag.get(0), List.of(Map.of("agreementLineId", ag.get(1), "quantity", 1)));
        assertThat(onHand(p, outLoc)).as("the counter return takes the unit out of RENT-OUT").isEqualByComparingTo("0");
        assertThat(onHand(p, cleanLoc)).isEqualByComparingTo("1");
        assertThat(outMoves(ag.get(0))).isEqualByComparingTo("0");
        assertBooksGreen();

        okA(201, "POST", "/rental/returns/" + ret.get(0) + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                ret.get(1), "notes", "clean"))));
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, cleanLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("3");
        assertBooksGreen();

        Res again = api("POST", "/rental/returns/" + ret.get(0) + "/inspect", Map.of("items", List.of(Map.of(
                "returnLineId", ret.get(1)))));
        assertThat(again.status()).as(again.raw().body()).isEqualTo(400);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Return line " + ret.get(1) + " has already been inspected");
        Res twice = api("POST", "/rental/returns", Map.of("agreementId", ag.get(0), "items", List.of(Map.of(
                "agreementLineId", ag.get(1)))));
        assertThat(twice.status()).as(twice.raw().body()).isEqualTo(400);
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("3");
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("0");
        assertBooksGreen();
    }

    @Test
    @DisplayName("M-7: a damaged return goes RENT-OUT → RENT-CLEAN → RENT-DAMAGED; RENT-OUT decremented once")
    void damagedReturn() {
        String p = rentable("M7-DMG", false, 2);
        List<String> u = units(p, 1);
        List<String> ag = checkedOut(List.of(Map.of("productId", p, "unitId", u.get(0), "unitRate", 20)));
        List<String> ret = returned(ag.get(0), List.of(Map.of("agreementLineId", ag.get(1), "quantity", 1,
                "conditionGrade", "poor")));
        okA(201, "POST", "/rental/returns/" + ret.get(0) + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                ret.get(1), "conditionGrade", "poor", "damages", List.of(Map.of("damageType", "scratch",
                        "chargeAmount", 15))))));
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, cleanLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, damagedLoc)).isEqualByComparingTo("1");
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("1");
        assertThat(outMoves(ag.get(0))).isEqualByComparingTo("0");
        assertBooksGreen();
    }

    @Test
    @DisplayName("M-7: partial returns — one line now, the other reported missing (stays in RENT-OUT) and found later;"
            + " no line returns more than went out")
    void partialReturns() {
        String p = rentable("M7-PART", false, 3);
        List<String> u = units(p, 2);
        List<String> ag = checkedOut(List.of(Map.of("productId", p, "unitId", u.get(0), "unitRate", 20),
                Map.of("productId", p, "unitId", u.get(1), "unitRate", 20)));
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("2");

        // a return above what went out is refused, nothing moves
        Res over = api("POST", "/rental/returns", Map.of("agreementId", ag.get(0), "items", List.of(Map.of(
                "agreementLineId", ag.get(1), "quantity", 2))));
        assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
        assertThat(over.body().get("message").asString())
                .isEqualTo("Cannot return 2 on agreement line " + ag.get(1) + ": only 1 still out");
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("2");

        // first return: line 1 only → partially returned, one unit leaves RENT-OUT
        List<String> r1 = returned(ag.get(0), List.of(Map.of("agreementLineId", ag.get(1), "quantity", 1)));
        assertThat(okA(200, "GET", "/rental/agreements/" + ag.get(0), null).get("status").asString())
                .isEqualTo("partially_returned");
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("1");
        okA(201, "POST", "/rental/returns/" + r1.get(0) + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                r1.get(1)))));
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("2");
        assertBooksGreen();

        // second return: line 2 reported missing → it stays in RENT-OUT; its inspection moves nothing, even graded
        // damaged
        List<String> r2 = returned(ag.get(0), List.of(Map.of("agreementLineId", ag.get(2), "quantity", 1,
                "quantityMissing", 1)));
        assertThat(onHand(p, outLoc)).as("the missing unit stays out").isEqualByComparingTo("1");
        assertThat(onHand(p, cleanLoc)).isEqualByComparingTo("0");
        okA(201, "POST", "/rental/returns/" + r2.get(0) + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                r2.get(1), "conditionGrade", "poor", "damages", List.of(Map.of("damageType", "lost", "chargeAmount",
                        40))))));
        assertThat(onHand(p, damagedLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("1");
        assertThat(outMoves(ag.get(0))).isEqualByComparingTo("1");
        assertBooksGreen();

        // the missing unit is found: it may come back once
        List<String> r3 = returned(ag.get(0), List.of(Map.of("agreementLineId", ag.get(2), "quantity", 1)));
        assertThat(onHand(p, outLoc)).isEqualByComparingTo("0");
        assertThat(onHand(p, cleanLoc)).isEqualByComparingTo("1");
        okA(201, "POST", "/rental/returns/" + r3.get(0) + "/inspect", Map.of("items", List.of(Map.of("returnLineId",
                r3.get(1)))));
        assertThat(onHand(p, stockLoc)).isEqualByComparingTo("3");
        assertThat(outMoves(ag.get(0))).isEqualByComparingTo("0");
        Res again = api("POST", "/rental/returns", Map.of("agreementId", ag.get(0), "items", List.of(Map.of(
                "agreementLineId", ag.get(2), "quantity", 1))));
        assertThat(again.status()).as(again.raw().body()).isEqualTo(400);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Cannot return 1 on agreement line " + ag.get(2) + ": only 0 still out");
        assertBooksGreen();
    }
}
