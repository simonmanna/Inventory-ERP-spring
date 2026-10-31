package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 8 — the cement scenario end to end: a block plant requests cement → RFQ to two suppliers
 * (one answers through the supplier portal) → comparison and award → order → change request → supplier confirms
 * through the portal → receipt → inspection rejects part of it → quality claim settled by debit note → bill held until
 * inspection, then posted and matched. Commitments follow the order and close when it is fully received.
 */
class SourceToPayEndToEndIT extends ApiTestSupport {

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String method, String path, Object body) {
        Res r = call(method, path, body, key());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    @Test
    void cementFromRequestToMatchedBill() {
        String warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "E2E-" + suffix(), "name",
                "Block plant store " + suffix(), "type", "warehouse")).get("id").asString();
        String cement = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(cement, demoOrg, "CEM-" + suffix(), "Cement 42.5 " + suffix()).update();
        String hima = supplier("Hima Cement");
        String tororo = supplier("Tororo Cement");

        // 1. Request, approved.
        String prId = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Block run week 45",
                "lines", List.of(Map.of("productId", cement, "description", "Cement 42.5 (50 kg)", "quantity", 200,
                        "requiredDate", LocalDate.now().plusDays(7).toString())))).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + prId + "/submit", null);
        ok(200, "PATCH", "/procurement/purchase-requests/" + prId + "/approve", null);
        String reqLine = ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("lines").get(0).get("id")
                .asString();

        // 2. RFQ to two suppliers; Hima answers through its portal link, Tororo's quote is keyed in by the buyer.
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Cement week 45", "requestLineIds",
                List.of(reqLine), "supplierIds", List.of(hima, tororo)));
        String rfqId = rfq.get("id").asString();
        String rfqLine = rfq.get("lines").get(0).get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/send", null);
        String token = ok(201, "POST", "/procurement/portal-links", Map.of("documentType", "rfq", "documentId", rfqId,
                "partnerId", hima)).get("token").asString();
        Res portalQuote = call("POST", "/supplier-portal/" + token + "/quotation", Map.of("currencyCode", "UGX",
                "leadTimeDays", 2, "lines", List.of(Map.of("rfqLineId", rfqLine, "unitPrice", 34000))), Map.of());
        assertThat(portalQuote.status()).as(portalQuote.raw().body()).isEqualTo(200);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", Map.of("partnerId", tororo, "currencyCode", "UGX",
                "leadTimeDays", 5, "freightAmount", 600000, "lines", List.of(Map.of("rfqLineId", rfqLine, "unitPrice",
                        33000))));

        // 3. Comparison: Tororo is cheaper per bag but its freight makes Hima the lowest delivered cost.
        JsonNode cmp = ok(200, "GET", "/procurement/rfqs/" + rfqId + "/comparison", null);
        assertThat(cmp.get("suppliers")).hasSize(2);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(Map.of("rfqLineId", rfqLine,
                "partnerId", hima))));
        JsonNode orders = money("POST", "/procurement/rfqs/" + rfqId + "/orders", Map.of("warehouseId", warehouse));
        String poId = orders.get("orders").get(0).get("id").asString();
        JsonNode life = ok(200, "GET", "/procurement/purchase-orders/" + poId + "/lifecycle", null);
        assertThat(life.get("status").asString()).isEqualTo("active");
        String poLine = life.get("lines").get(0).get("id").asString();
        assertThat(commitmentOpen(poId)).isEqualByComparingTo("6800000");

        // 4. The plan shrinks: 180 bags. A decrease applies immediately and re-states the commitment.
        JsonNode change = money("POST", "/procurement/purchase-orders/" + poId + "/changes", Map.of("reason",
                "Second mixer down", "lines", List.of(Map.of("lineId", poLine, "quantity", 180))));
        assertThat(change.get("status").asString()).isEqualTo("applied");
        assertThat(commitmentOpen(poId)).isEqualByComparingTo("6120000");

        // 5. Hima confirms the revised order through a link.
        String poToken = ok(201, "POST", "/procurement/portal-links", Map.of("documentType", "purchase_order",
                "documentId", poId)).get("token").asString();
        Res confirm = call("POST", "/supplier-portal/" + poToken + "/confirm", Map.of("supplierReference", "HC-5521",
                "promisedDate", LocalDate.now().plusDays(2).toString()), Map.of());
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(200);

        // 6. Receive in full; the commitment closes.
        JsonNode received = money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", cement, "description",
                        "Cement", "quantity", 180))));
        String grnId = received.get("grn").get("id").asString();
        assertThat(commitmentOpen(poId)).isEqualByComparingTo("0");

        // 7. Inspection: 6 bags wet.
        JsonNode insp = ok(200, "POST", "/procurement/goods-receipts/" + grnId + "/inspection", null);
        String inspId = insp.get("id").asString();
        ok(200, "POST", "/procurement/inspections/" + inspId + "/results", Map.of("lines", List.of(Map.of("lineId",
                insp.get("lines").get(0).get("id").asString(), "rejectedQuantity", 6, "defectNotes", "wet"))));

        // 8. The bill is held while the inspection is open.
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", hima, "issueDate", LocalDate.now().toString(),
                "reference", "HC-INV-" + suffix(), "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of(
                        "productId", cement, "description", "Cement", "quantity", 180, "unitPrice", 34000))))
                .get("id").asString();
        assertThat(as("POST", "/vendor-bills/" + bill + "/post", Map.of()).status()).isEqualTo(400);
        JsonNode done = money("POST", "/procurement/inspections/" + inspId + "/complete", null);
        assertThat(done.get("status").asString()).isEqualTo("partial");

        // 9. The quality claim (6 × 34 000) is accepted and settled by a debit note.
        String claimId = done.get("claimId").asString();
        assertThat(ok(200, "GET", "/procurement/claims/" + claimId, null).get("amount").asString()).isEqualTo("204000");
        ok(200, "POST", "/procurement/claims/" + claimId + "/submit", null);
        ok(200, "POST", "/procurement/claims/" + claimId + "/accept", Map.of("response", "credit agreed"));
        JsonNode settled = money("POST", "/procurement/claims/" + claimId + "/settle", null);
        assertThat(settled.get("status").asString()).isEqualTo("settled");
        assertThat(settled.get("debitNoteId").isNull()).isFalse();

        // 10. Bill posts and matches the receipt exactly.
        JsonNode posted = ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        JsonNode match = ok(200, "GET", "/procurement/three-way-match?purchaseOrderId=" + poId, null);
        assertThat(match.get(0).get("status").asString()).isEqualTo("matched");

        // 11. The supplier's scorecard reflects the delivery and the reject.
        JsonNode card = ok(200, "GET", "/procurement/analytics/scorecards/" + hima, null);
        assertThat(card.get("orders").asInt()).isEqualTo(1);
        assertThat(card.get("rejectPct").asString()).isEqualTo("3.33");
        assertThat(card.get("claims").asInt()).isEqualTo(1);
    }

    BigDecimal commitmentOpen(String poId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"openAmount\"), 0) FROM \"Commitment\" WHERE \"sourceId\" = ?").param(poId)
                .query(BigDecimal.class).single();
    }
}
