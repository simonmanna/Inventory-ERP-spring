package com.inventoryerp.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.quality.web.QualityLabController;
import com.inventoryerp.quality.web.QualityLotController;
import com.inventoryerp.quality.web.QualityNcrController;
import com.inventoryerp.quality.web.QualitySpecController;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Quality 2.6 over HTTP: NCR → CAPA lifecycle (SoD on verification, overdue query, close rules), batch traceability
 * backward / forward on a seeded chain (supplier GRN → production order → block batch → delivery → customer site),
 * reports by work centre, the authorization matrix of every quality route and tenant isolation.
 */
class QualityNcrTraceIT extends QualityTestSupport {

    @Test
    @DisplayName("NCR → CAPA: actions, verification gate, owner cannot verify, effectiveness notes, NCR close rules")
    void ncrCapaLifecycle() {
        JsonNode ncr = ok(201, "POST", "/quality/ncrs", Map.of("title", "Blocks below 7 N/mm² " + sfx, "source",
                "final", "severity", "major", "quantityAffected", 400, "supplierPartnerId", supplier));
        String ncrId = ncr.get("id").asString();
        assertThat(ncr.get("ncrNumber").asString()).startsWith("NCR-");
        assertThat(ncr.get("status").asString()).isEqualTo("open");
        assertThat(as("POST", "/quality/ncrs", Map.of("title", "x", "ownerUserId", UUID.randomUUID().toString()))
                .status()).isEqualTo(404);
        ok(200, "POST", "/quality/ncrs/" + ncrId + "/status", Map.of("status", "investigating"));

        JsonNode capa = ok(201, "POST", "/quality/capas", Map.of("ncrId", ncrId, "title", "Fix curing " + sfx,
                "rootCause", "Curing chamber humidity sensor drift", "rootCauseMethod", "five_why", "ownerUserId", adminId,
                "dueDate", LocalDate.now().plusDays(10).toString(), "actions", List.of(
                        Map.of("kind", "corrective", "description", "Recalibrate humidity sensor", "ownerUserId", adminId,
                                "dueDate", LocalDate.now().minusDays(2).toString()),
                        Map.of("kind", "preventive", "description", "Monthly sensor calibration in PM plan"))));
        String capaId = capa.get("id").asString();
        assertThat(capa.get("capaNumber").asString()).startsWith("CAPA-");
        assertThat(capa.get("actions")).hasSize(2);
        assertThat(ok(200, "GET", "/quality/ncrs/" + ncrId, null).get("status").asString()).isEqualTo("action");
        assertThat(msg(as("POST", "/quality/ncrs/" + ncrId + "/status", Map.of("status", "closed"))))
                .contains("has open CAPAs: " + capa.get("capaNumber").asString());

        // Overdue: the corrective action is two days late.
        JsonNode overdue = ok(200, "GET", "/quality/capa-actions/overdue", null);
        assertThat(overdue.get("actions").toString()).contains("Recalibrate humidity sensor");
        assertThat(ok(200, "GET", "/quality/capas?overdue=true&search=" + capa.get("capaNumber").asString(), null)
                .get("meta").get("total").asInt()).isEqualTo(1);

        assertThat(msg(as("POST", "/quality/capas/" + capaId + "/status", Map.of("status", "verification"))))
                .contains("cannot move from open to verification");
        ok(200, "POST", "/quality/capas/" + capaId + "/status", Map.of("status", "in_progress"));
        assertThat(msg(as("POST", "/quality/capas/" + capaId + "/status", Map.of("status", "verification"))))
                .isEqualTo("All actions must be done or cancelled before verification.");
        String a1 = capa.get("actions").get(0).get("id").asString();
        String a2 = capa.get("actions").get(1).get("id").asString();
        ok(200, "PATCH", "/quality/capa-actions/" + a2, Map.of("dueDate", LocalDate.now().plusDays(30).toString()));
        ok(200, "POST", "/quality/capa-actions/" + a1 + "/complete", Map.of("notes", "Sensor replaced"));
        assertThat(msg(as("POST", "/quality/capa-actions/" + a1 + "/complete", Map.of()))).contains("already done");
        JsonNode done = ok(200, "POST", "/quality/capa-actions/" + a2 + "/complete", Map.of("status", "done"));
        assertThat(done.get("openActionCount").asInt()).isZero();
        assertThat(ok(200, "GET", "/quality/capa-actions/overdue", null).get("actions").toString())
                .doesNotContain("Recalibrate humidity sensor");
        ok(200, "POST", "/quality/capas/" + capaId + "/status", Map.of("status", "verification"));
        Res owner = as("POST", "/quality/capas/" + capaId + "/status", Map.of("status", "closed", "notes", "ok"));
        assertThat(owner.status()).isEqualTo(403);
        assertThat(msg(owner)).contains("segregation of duties");
        String verifier = userWith("quality:read", "quality:capa")[0];
        assertThat(msg(call("POST", "/quality/capas/" + capaId + "/status", Map.of("status", "closed"), verifier)))
                .isEqualTo("Record the effectiveness check before closing the CAPA.");
        Res closed = call("POST", "/quality/capas/" + capaId + "/status", Map.of("status", "closed", "notes",
                "Three batches above 7.5 N/mm² after the fix"), verifier);
        assertThat(closed.status()).as(closed.raw().body()).isEqualTo(200);
        assertThat(closed.body().get("status").asString()).isEqualTo("closed");
        assertThat(closed.body().get("verifiedBy").isNull()).isFalse();
        assertThat(msg(as("POST", "/quality/capas/" + capaId + "/actions", Map.of("description", "late"))))
                .contains("is closed");
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'quality.capa.closed' AND"
                + " payload->>'capaId' = ?").param(capaId).query(Long.class).single()).isEqualTo(1);

        // A major NCR needs its root cause before closing.
        assertThat(msg(as("POST", "/quality/ncrs/" + ncrId + "/status", Map.of("status", "closed"))))
                .isEqualTo("A major or critical NCR needs a root cause before it can be closed.");
        ok(200, "PATCH", "/quality/ncrs/" + ncrId, Map.of("rootCause", "Curing humidity too low", "disposition",
                "Downgraded to second grade"));
        JsonNode closedNcr = ok(200, "POST", "/quality/ncrs/" + ncrId + "/status", Map.of("status", "closed", "notes",
                "Closed with CAPA"));
        assertThat(closedNcr.get("status").asString()).isEqualTo("closed");
        assertThat(closedNcr.get("capas")).hasSize(1);
        assertThat(msg(as("PATCH", "/quality/ncrs/" + ncrId, Map.of("title", "x")))).contains("is closed");
        assertThat(ok(200, "POST", "/quality/ncrs/" + ncrId + "/status", Map.of("status", "open")).get("status")
                .asString()).isEqualTo("open");
        assertThat(ok(200, "GET", "/quality/ncrs?severity=major&search=" + sfx, null).get("meta").get("total").asInt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("traceability: backward block batch → order → cement batch → GRN → supplier; forward → delivery → site")
    void traceability() {
        String cement = product("Cement for blocks", true);
        String blocks = product("Hollow block 6in", false);
        String cementLot = "CEM-T-" + sfx;
        String blockLot = "BLK-T-" + sfx;
        String grnNumber = grn(supplier, cement, 50, 31000, cementLot).get("receiptNumber").asString();
        String orderId = UUID.randomUUID().toString();
        String orderCode = "MO-Q" + sfx;
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"producedQty\", status, \"batchNumber\", \"completedAt\", \"postedAt\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1000, 1000, 'completed', ?, now(), now(), now())")
                .params(orderId, demoOrg, orderCode, blocks, warehouse, blockLot).update();
        jdbc.sql("INSERT INTO \"ProductionMaterial\" (id, \"organizationId\", \"orderId\", \"productId\", \"productName\","
                + " \"qtyPlanned\", \"qtyConsumed\", \"batchNumber\") VALUES (?, ?, ?, ?, 'Cement', 20, 20, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, orderId, cement, cementLot).update();
        jdbc.sql("INSERT INTO \"ProductionMaterial\" (id, \"organizationId\", \"orderId\", \"productId\", \"productName\","
                + " \"qtyPlanned\", \"qtyConsumed\") VALUES (?, ?, ?, ?, 'Quarry dust', 60, 60)")
                .params(UUID.randomUUID().toString(), demoOrg, orderId, product("Quarry dust", false)).update();
        jdbc.sql("INSERT INTO \"ProductionOutput\" (id, \"organizationId\", \"orderId\", \"productId\", \"productName\","
                + " \"qtyProduced\", \"batchNumber\") VALUES (?, ?, ?, ?, 'Blocks', 1000, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, orderId, blocks, blockLot).update();
        String customer = partner("Contractor", false);
        String site = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetCustomerSite\" (id, \"organizationId\", code, \"partnerId\", name) VALUES"
                + " (?, ?, ?, ?, 'Kira road site')").params(site, demoOrg, "S-" + sfx, customer).update();
        String dOrder = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"siteId\","
                + " \"locationId\", \"requestedDate\", status) VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE, 'delivered')")
                .params(dOrder, demoOrg, "DO-" + sfx, customer, site, warehouse).update();
        String dLine = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrderLine\" (id, \"organizationId\", \"deliveryOrderId\", \"lineNumber\","
                + " \"productId\", \"orderedQty\") VALUES (?, ?, ?, 1, ?, 500)").params(dLine, demoOrg, dOrder, blocks)
                .update();
        String trip = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence, \"plannedDate\","
                + " \"locationId\", status, \"dispatchedAt\", \"stockIssuedAt\", \"deliveryNoteNumber\") VALUES (?, ?, ?, ?,"
                + " 1, CURRENT_DATE, ?, 'delivered', now(), now(), ?)").params(trip, demoOrg, "TR-" + sfx, dOrder,
                warehouse, "DN-" + sfx).update();
        jdbc.sql("INSERT INTO \"FleetLoadLine\" (id, \"organizationId\", \"loadId\", \"deliveryOrderLineId\", \"productId\","
                + " \"plannedQty\", \"dispatchedQty\", \"batchNumber\") VALUES (?, ?, ?, ?, ?, 500, 500, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, trip, dLine, blocks, blockLot).update();
        // A final inspection lot on the block batch shows on its node.
        JsonNode spec = activeSpec(blocks, false);
        JsonNode plan = plan("final", blocks, spec.get("id").asString(), Map.of());
        String lotNumber = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", blocks,
                "quantity", 1000, "batchNumber", blockLot, "planId", plan.get("id").asString())).get("lotNumber")
                .asString();

        JsonNode back = ok(200, "GET", "/quality/traceability?productId=" + blocks + "&batchNumber=" + blockLot
                + "&direction=backward", null);
        JsonNode root = back.get("root");
        assertThat(root.get("kind").asString()).isEqualTo("batch");
        assertThat(root.get("lots").get(0).get("lotNumber").asString()).isEqualTo(lotNumber);
        JsonNode order = find(root, "production_order");
        assertThat(order.get("label").asString()).isEqualTo(orderCode);
        JsonNode cementNode = child(order, "batch", cementLot);
        assertThat(cementNode.get("productId").asString()).isEqualTo(cement);
        assertThat(child(order, "material", null).get("untracked").asBoolean()).isTrue();
        JsonNode grnNode = find(cementNode, "goods_receipt");
        assertThat(grnNode.get("label").asString()).isEqualTo(grnNumber);
        assertThat(grnNode.get("children").get(0).get("kind").asString()).isEqualTo("supplier");
        assertThat(grnNode.get("children").get(0).get("ref").asString()).isEqualTo(supplier);

        JsonNode fwd = ok(200, "GET", "/quality/traceability?productId=" + cement + "&batchNumber=" + cementLot
                + "&direction=forward", null).get("root");
        JsonNode fOrder = find(fwd, "production_order");
        JsonNode blockNode = child(fOrder, "batch", blockLot);
        JsonNode delivery = find(blockNode, "delivery");
        assertThat(delivery.get("label").asString()).contains("TR-" + sfx).contains("DN-" + sfx);
        JsonNode cust = delivery.get("children").get(0);
        assertThat(cust.get("kind").asString()).isEqualTo("customer");
        assertThat(cust.get("ref").asString()).isEqualTo(customer);
        assertThat(cust.get("children").get(0).get("label").asString()).isEqualTo("Kira road site");
        assertThat(ok(200, "GET", "/quality/traceability?productId=" + cement + "&batchNumber=" + cementLot
                + "&direction=forward&depth=1", null).get("root").get("children").get(0).get("children").get(0)
                .get("truncated").asBoolean()).isTrue();
        assertThat(as("GET", "/quality/traceability?productId=" + UUID.randomUUID() + "&batchNumber=x", null).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("reports: by work centre and product — lots, failed, pass rate, NCRs")
    void reportsByLine() {
        String wc = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"WorkCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(wc, demoOrg, "WC-" + sfx, "Block press line " + sfx).update();
        String paver = product("Paver 60mm", false);
        JsonNode spec = activeSpec(paver, false);
        JsonNode plan = plan("final", paver, spec.get("id").asString(), Map.of());
        List<String> lots = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            lots.add(ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", paver, "quantity",
                    800, "planId", plan.get("id").asString(), "workCenterId", wc)).get("id").asString());
        }
        ok(200, "POST", "/quality/inspection-lots/" + lots.get(0) + "/results", Map.of("results", List.of(
                num("MOIST", 1, 1), text("CLEAN", 1, "yes"))));
        ok(200, "POST", "/quality/inspection-lots/" + lots.get(1) + "/results", Map.of("results", List.of(
                num("MOIST", 1, 5), text("CLEAN", 1, "yes"))));
        JsonNode r = ok(200, "GET", "/quality/reports/summary?groupBy=work_center&stage=final", null);
        JsonNode row = null;
        for (JsonNode x : r.get("rows")) {
            if (wc.equals(x.get("key").asString())) {
                row = x;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("label").asString()).isEqualTo("Block press line " + sfx);
        assertThat(row.get("lots").asInt()).isEqualTo(2);
        assertThat(row.get("passed").asInt()).isEqualTo(1);
        assertThat(row.get("failed").asInt()).isEqualTo(1);
        assertThat(dec(row.get("passRate"))).isEqualByComparingTo("50.0");
        assertThat(row.get("ncrCount").asInt()).isEqualTo(1);
        JsonNode byProduct = ok(200, "GET", "/quality/reports/summary?groupBy=product&from="
                + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now().plusDays(1), null);
        assertThat(byProduct.get("rows").toString()).contains(paver);
        assertThat(byProduct.get("totals").get("lots").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(as("GET", "/quality/reports/summary?groupBy=colour", null).status()).isEqualTo(400);
        assertThat(as("GET", "/quality/reports/summary?from=2026-10-10&to=2026-10-01", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("authorization: 401 without a token and 403 without the permission on every quality route")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(QualitySpecController.class, QualityLotController.class,
                QualityNcrController.class, QualityLabController.class)).isEqualTo(45);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's quality records")
    void tenantIsolation() {
        String sand = product("Plaster sand", false);
        JsonNode spec = activeSpec(sand, false);
        JsonNode plan = plan("final", sand, spec.get("id").asString(), Map.of());
        String lotId = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", sand,
                "quantity", 10, "planId", plan.get("id").asString())).get("id").asString();
        String ncrId = ok(201, "POST", "/quality/ncrs", Map.of("title", "Isolation " + sfx)).get("id").asString();
        String capaId = ok(201, "POST", "/quality/capas", Map.of("title", "Isolation " + sfx)).get("id").asString();
        assertThat(call("GET", "/quality/inspection-lots/" + lotId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(
                num("MOIST", 1, 1))), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/quality/specifications/" + spec.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/quality/inspection-plans/" + plan.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/quality/ncrs/" + ncrId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/quality/capas/" + capaId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/quality/inspection-lots", null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
        assertThat(call("GET", "/quality/ncrs", null, otherToken).body().get("meta").get("total").asInt()).isZero();
        assertThat(call("GET", "/quality/traceability?productId=" + sand + "&batchNumber=x", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", sand, "quantity", 1),
                otherToken).status()).isEqualTo(404);
    }

    /** First child (depth-first) of the given kind. */
    static JsonNode find(JsonNode node, String kind) {
        for (JsonNode c : node.get("children")) {
            if (kind.equals(c.get("kind").asString())) {
                return c;
            }
        }
        throw new AssertionError("no " + kind + " under " + node.get("label"));
    }

    static JsonNode child(JsonNode node, String kind, String batch) {
        for (JsonNode c : node.get("children")) {
            if (kind.equals(c.get("kind").asString()) && (batch == null || batch.equals(c.path("batchNumber").asString()))) {
                return c;
            }
        }
        throw new AssertionError("no " + kind + " " + batch + " under " + node.get("label"));
    }
}
