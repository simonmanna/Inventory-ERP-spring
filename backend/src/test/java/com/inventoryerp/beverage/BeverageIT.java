package com.inventoryerp.beverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Beverage Control on Testcontainers Postgres: bottle-count lifecycle (start → weigh → reasons → manager approval →
 * variance adjustment + measurement log), cancel, reports, tenant isolation and the authorization matrix.
 */
class BeverageIT extends ApiTestSupport {

    String adminId;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "B-" + suffix(), "name", "Bar " + suffix(),
                "type", "warehouse")).get("id").asString();
    }

    /** 700 ml bottle: 500 g empty, 1200 g full (1 ml/g), 30 ml pour, 5 g tolerance, 0.05 per ml. */
    String bottleProduct() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costPrice\","
                        + " \"measurementMethod\", \"containerVolumeMl\", \"emptyBottleWeightG\", \"fullBottleWeightG\","
                        + " \"liquidWeightG\", \"conversionFactorMlPerG\", \"standardPourMl\", \"varianceToleranceG\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 0.05, 'digital_weight'::\"MeasurementMethod\","
                        + " 700, 500, 1200, 700, 1, 30, 5, now())")
                .params(id, demoOrg, "BEV-" + suffix(), "Whisky " + suffix()).update();
        return id;
    }

    void stockIn(String loc, String productId, int ml) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", productId, "quantity", ml,
                        "unitCost", 0.05))));
    }

    String approverToken(String email, boolean canApprove) {
        String role = UUID.randomUUID().toString();
        String[] perms = canApprove ? new String[] {"beverage:read", "beverage:approve"} : new String[] {"beverage:read"};
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "BevTest-" + role, perms).update();
        return createUser(demoOrg, email, "Bev@1234567", role);
    }

    JsonNode lineFor(JsonNode count, String productId) {
        for (JsonNode l : count.get("lines")) {
            if (l.get("productId").asString().equals(productId)) {
                return l;
            }
        }
        throw new AssertionError("no line for " + productId);
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("count lifecycle: snapshot, weigh, reason + manager approval gates, adjustment and measurement log")
    void lifecycle() {
        String loc = location();
        String p = bottleProduct();
        stockIn(loc, p, 700);

        JsonNode count = ok(201, "POST", "/beverage/counts/start", Map.of("locationId", loc, "countType", "closing"));
        assertThat(count.get("countCode").asString()).startsWith("BCNT-");
        assertThat(count.get("name").asString()).startsWith("Closing Bottle Count – ");
        assertThat(count.get("status").asString()).isEqualTo("draft");
        String id = count.get("id").asString();
        JsonNode line = lineFor(count, p);
        assertThat(line.get("systemMl").asString()).isEqualTo("700");
        assertThat(line.get("readings")).isEmpty();
        String lineId = line.get("id").asString();

        Res nothing = as("POST", "/beverage/counts/" + id + "/submit", Map.of());
        assertThat(nothing.status()).isEqualTo(400);
        assertThat(nothing.body().get("message").asString()).isEqualTo("Nothing weighed yet — weigh at least one bottle.");

        JsonNode saved = ok(200, "PATCH", "/beverage/counts/" + id + "/draft", Map.of("notes", "shift A",
                "lines", List.of(Map.of("lineId", lineId, "readings", List.of(Map.of("measuredWeightG", 1100,
                        "bottleNumber", "W-1"))))));
        JsonNode weighed = lineFor(saved, p);
        assertThat(weighed.get("countedMl").asString()).isEqualTo("600");
        assertThat(weighed.get("varianceMl").asString()).isEqualTo("-100");
        assertThat(weighed.get("varianceG").asString()).isEqualTo("-100");
        assertThat(weighed.get("expectedShots").asString()).isEqualTo("20");
        assertThat(weighed.get("confidence").asString()).isEqualTo("SUSPICIOUS");
        assertThat(weighed.get("readings").get(0).get("bottleNumber").asString()).isEqualTo("W-1");
        assertThat(saved.get("notes").asString()).isEqualTo("shift A");

        Res noReason = as("POST", "/beverage/counts/" + id + "/submit", Map.of());
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString())
                .isEqualTo("A reason is required for 1 flagged line(s) (variance over tolerance).");

        ok(200, "PATCH", "/beverage/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId", lineId,
                "reason", "Spillage", "readings", List.of(Map.of("measuredWeightG", 1100, "bottleNumber", "W-1"))))));
        Res noApprover = as("POST", "/beverage/counts/" + id + "/submit", Map.of());
        assertThat(noApprover.status()).isEqualTo(400);
        assertThat(noApprover.body().get("message").asString())
                .isEqualTo("This count has a variance over tolerance and requires manager approval.");
        Res self = as("POST", "/beverage/counts/" + id + "/submit", Map.of("approverId", adminId));
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("The person who counted cannot approve their own count.");
        Res unknown = as("POST", "/beverage/counts/" + id + "/submit", Map.of("approverId", UUID.randomUUID().toString()));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("message").asString()).isEqualTo("Approving manager not found");
        String weak = approverToken("bev-weak-" + suffix() + "@demo.test", false);
        Res noPerm = as("POST", "/beverage/counts/" + id + "/submit", Map.of("approverId", weak));
        assertThat(noPerm.status()).isEqualTo(401);
        assertThat(noPerm.body().get("message").asString()).isEqualTo("Approver does not hold beverage:approve");
        String managerEmail = "bev-mgr-" + suffix() + "@demo.test";
        String manager = approverToken(managerEmail, true);
        Res noPin = as("POST", "/beverage/counts/" + id + "/submit", Map.of("approverId", manager, "managerPin", "1234"));
        assertThat(noPin.status()).isEqualTo(400);
        assertThat(noPin.body().get("message").asString()).isEqualTo("Manager has not set a PIN");

        JsonNode done = ok(201, "POST", "/beverage/counts/" + id + "/submit",
                Map.of("approverEmail", managerEmail.toUpperCase()));
        assertThat(done.get("status").asString()).isEqualTo("submitted");
        assertThat(done.get("approvedById").asString()).isEqualTo(manager);
        assertThat(done.get("adjustmentId").isNull()).isFalse();
        assertThat(onHand(p, loc)).isEqualByComparingTo("600");
        long logged = jdbc.sql("SELECT count(*) FROM \"BottleMeasurement\" WHERE \"sessionId\" = ? AND \"productId\" = ?")
                .params(id, p).query(Long.class).single();
        assertThat(logged).isEqualTo(1);
        long audited = jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND action::text = 'measure'")
                .param(id).query(Long.class).single();
        assertThat(audited).isEqualTo(1);

        Res again = as("POST", "/beverage/counts/" + id + "/submit", Map.of());
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).isEqualTo("Cannot modify a submitted count");

        JsonNode list = ok(200, "GET", "/beverage/counts", null);
        assertThat(list.get(0).get("_count").get("lines").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode variance = ok(200, "GET", "/beverage/reports/variance?locationId=" + loc, null);
        JsonNode row = null;
        for (JsonNode r : variance) {
            if (r.get("productId").asString().equals(p)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("varianceMl").asDouble()).isEqualTo(-100);
        assertThat(row.get("variancePct").asDouble()).isEqualTo(-14.29);
        assertThat(row.get("lossValue").asDouble()).isEqualTo(5);
        assertThat(row.get("confidence").asString()).isEqualTo("SUSPICIOUS");

        JsonNode dash = ok(200, "GET", "/beverage/reports/dashboard?locationId=" + loc, null);
        assertThat(dash.get("openVariances").asInt()).isEqualTo(1);
        assertThat(dash.get("totalLossValue").asDouble()).isEqualTo(5);
        assertThat(dash.get("shrinkage").has("lastMonth")).isTrue();
        JsonNode yield = ok(200, "GET", "/beverage/reports/yield?from=2020-01-01", null);
        for (JsonNode r : yield) {
            if (r.get("productId").asString().equals(p)) {
                assertThat(r.get("shotsPerBottle").asDouble()).isEqualTo(23.3);
            }
        }
        assertThat(ok(200, "GET", "/beverage/reports/bartender", null).isArray()).isTrue();
    }

    @Test
    @DisplayName("start replaces an open draft of the same type; cancel; 404s; validation; tenant isolation")
    void draftsAndGuards() {
        String loc = location();
        bottleProduct();
        JsonNode first = ok(201, "POST", "/beverage/counts/start", Map.of("locationId", loc, "countType", "opening"));
        assertThat(first.get("name").asString()).startsWith("Opening Bottle Count – ");
        JsonNode second = ok(201, "POST", "/beverage/counts/start", Map.of("locationId", loc, "countType", "opening"));
        assertThat(ok(200, "GET", "/beverage/counts/" + first.get("id").asString(), null).get("status").asString())
                .isEqualTo("cancelled");
        String id = second.get("id").asString();
        assertThat(ok(201, "POST", "/beverage/counts/" + id + "/cancel", null).get("ok").asBoolean()).isTrue();
        Res mod = as("PATCH", "/beverage/counts/" + id + "/draft", Map.of("lines", List.of()));
        assertThat(mod.status()).isEqualTo(400);
        assertThat(mod.body().get("message").asString()).isEqualTo("Cannot modify a cancelled count");

        Res nf = as("GET", "/beverage/counts/" + UUID.randomUUID(), null);
        assertThat(nf.status()).isEqualTo(404);
        assertThat(nf.body().get("message").asString()).isEqualTo("Bottle count not found");
        Res noLoc = as("POST", "/beverage/counts/start", Map.of("locationId", UUID.randomUUID().toString()));
        assertThat(noLoc.status()).isEqualTo(404);
        assertThat(noLoc.body().get("message").asString()).isEqualTo("Location not found");
        Res badType = as("POST", "/beverage/counts/start", Map.of("locationId", loc, "countType", "cycle"));
        assertThat(badType.status()).isEqualTo(400);
        Res badReading = as("PATCH", "/beverage/counts/" + id + "/draft",
                Map.of("lines", List.of(Map.of("lineId", "x", "readings", List.of(Map.of("bottleNumber", "1"))))));
        assertThat(badReading.status()).isEqualTo(400);
        Res extra = as("POST", "/beverage/counts/start", Map.of("locationId", loc, "nope", 1));
        assertThat(extra.status()).isEqualTo(400);

        Res foreign = call("GET", "/beverage/counts/" + first.get("id").asString(), null, otherToken);
        assertThat(foreign.status()).isIn(403, 404);
    }

    @Test
    @DisplayName("ADR-S004: reason codes; opt-in unweighed-bottle exception needs a reason and manager approval")
    void controls() {
        String loc = location();
        String weighedP = bottleProduct();
        String skippedP = bottleProduct();
        stockIn(loc, weighedP, 700);
        stockIn(loc, skippedP, 700);
        String settingId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"updatedAt\")"
                        + " VALUES (?, ?, 'beverage.requireAllBottlesWeighed', 'true'::jsonb, now())")
                .params(settingId, demoOrg).update();
        try {
            JsonNode count = ok(201, "POST", "/beverage/counts/start", Map.of("locationId", loc));
            String id = count.get("id").asString();
            String lineId = lineFor(count, weighedP).get("id").asString();
            Res badCode = as("PATCH", "/beverage/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId",
                    lineId, "reasonCode", "theft"))));
            assertThat(badCode.status()).isEqualTo(400);
            JsonNode saved = ok(200, "PATCH", "/beverage/counts/" + id + "/draft", Map.of("lines", List.of(Map.of(
                    "lineId", lineId, "reasonCode", "complimentary", "readings", List.of(Map.of("measuredWeightG",
                            1200))))));
            JsonNode line = lineFor(saved, weighedP);
            assertThat(line.get("reasonCode").asString()).isEqualTo("complimentary");
            assertThat(line.get("confidence").asString()).isEqualTo("GOOD");

            Res skipped = as("POST", "/beverage/counts/" + id + "/submit", Map.of());
            assertThat(skipped.status()).isEqualTo(400);
            assertThat(skipped.body().get("message").asString()).endsWith(" bottle line(s) with stock were not weighed."
                    + " Weigh them, or submit an unweighed exception with a reason and manager approval.");
            Res noApprover = as("POST", "/beverage/counts/" + id + "/submit", Map.of("unweighedReason", "Locked cabinet"));
            assertThat(noApprover.status()).isEqualTo(400);
            assertThat(noApprover.body().get("message").asString())
                    .isEqualTo("Submitting with unweighed bottles requires manager approval.");
            String manager = approverToken("bev-exc-" + suffix() + "@demo.test", true);
            JsonNode done = ok(201, "POST", "/beverage/counts/" + id + "/submit",
                    Map.of("unweighedReason", " Locked cabinet ", "approverId", manager));
            assertThat(done.get("status").asString()).isEqualTo("submitted");
            assertThat(done.get("unweighedReason").asString()).isEqualTo("Locked cabinet");
            assertThat(done.get("unweighedCount").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(done.get("approvedById").asString()).isEqualTo(manager);
            assertThat(onHand(skippedP, loc)).isEqualByComparingTo("700");
            JsonNode variance = ok(200, "GET", "/beverage/reports/variance?locationId=" + loc, null);
            assertThat(variance.get(0).has("reasonCode")).isTrue();
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(settingId).update();
        }

        String loc2 = location();
        String p2 = bottleProduct();
        stockIn(loc2, p2, 700);
        JsonNode optOut = ok(201, "POST", "/beverage/counts/start", Map.of("locationId", loc2));
        String other = lineFor(optOut, p2).get("id").asString();
        assertThat(other).isNotEmpty();
        JsonNode anyLine = optOut.get("lines").get(0);
        ok(200, "PATCH", "/beverage/counts/" + optOut.get("id").asString() + "/draft", Map.of("lines", List.of(Map.of(
                "lineId", anyLine.get("id").asString(), "sealedFullCount", 0, "readings", List.of(Map.of("measuredWeightG",
                        anyLine.get("productId").asString().equals(p2) ? 1200 : 500))))));
        JsonNode nest = ok(201, "POST", "/beverage/counts/" + optOut.get("id").asString() + "/submit", Map.of());
        assertThat(nest.get("unweighedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("authorization matrix: 401 without token, 403 without permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.beverage")).isEqualTo(10);
    }
}
