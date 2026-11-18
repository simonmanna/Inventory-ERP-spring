package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestApprovers;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps h-04 (procurement controls and sourcing), stream gE: sealed bids, no quotes after the deadline and
 * no zero-quantity offers (F6), supplier approval is maker-checker (F11), and a draft policy is approved only while it
 * is a draft (F11).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcurementControlsGapsIT extends ApiTestSupport {

    String approverToken;

    @BeforeAll
    void fixtures() {
        approverToken = TestApprovers.secondAdmin(this);
    }

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

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, 0, now())").params(id, demoOrg, "PC-" + suffix(), name + " " + suffix()).update();
        return id;
    }

    /** A sent RFQ for 10 of one product, offered to {@code suppliers}; returns the RFQ. */
    JsonNode sentRfq(List<String> suppliers) {
        String prId = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Gaps", "lines",
                List.of(Map.of("productId", product("Cement"), "description", "Cement", "quantity", 10))))
                .get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + prId + "/submit", null);
        assertThat(call("PATCH", "/procurement/purchase-requests/" + prId + "/approve", null, approverToken).status())
                .isEqualTo(200);
        String reqLine = ok(200, "GET", "/procurement/purchase-requests/" + prId, null).get("lines").get(0).get("id")
                .asString();
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "Gaps " + suffix(), "requestLineIds",
                List.of(reqLine), "supplierIds", suppliers, "deadline", LocalDate.now().plusDays(3).toString()));
        ok(200, "POST", "/procurement/rfqs/" + rfq.get("id").asString() + "/send", null);
        return rfq;
    }

    Map<String, Object> quote(String partner, String rfqLineId, Object qty, Object price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("rfqLineId", rfqLineId);
        line.put("unitPrice", price);
        if (qty != null) {
            line.put("quantity", qty);
        }
        return new LinkedHashMap<>(Map.of("partnerId", partner, "currencyCode", "UGX", "lines", List.of(line)));
    }

    @Test
    @DisplayName("h-04 F6: a zero-quantity offer is refused; no award while bids are sealed; no quote after the deadline")
    void sealedBidsAndDeadline() {
        String a = supplier("Alpha");
        String b = supplier("Bravo");
        JsonNode rfq = sentRfq(List.of(a, b));
        String rfqId = rfq.get("id").asString();
        String line = rfq.get("lines").get(0).get("id").asString();

        Res zero = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, 0, 5));
        assertThat(zero.status()).as(zero.raw().body()).isEqualTo(400);
        assertThat(zero.raw().body()).contains("positive");

        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(a, line, null, 5));
        Res sealed = as("POST", "/procurement/rfqs/" + rfqId + "/award", Map.of("lines", List.of(Map.of("rfqLineId",
                line, "partnerId", a, "quantity", 10))));
        assertThat(sealed.status()).as(sealed.raw().body()).isEqualTo(400);
        assertThat(sealed.body().get("message").asString()).startsWith("An RFQ cannot be awarded while its bids are sealed");

        // The deadline passes: the tender is closed to new quotations.
        jdbc.sql("UPDATE \"Rfq\" SET deadline = now() - interval '1 day' WHERE id = ?").param(rfqId).update();
        Res late = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote(b, line, null, 4));
        assertThat(late.status()).as(late.raw().body()).isEqualTo(400);
        assertThat(late.body().get("message").asString()).startsWith("Quotations cannot be recorded after the RFQ deadline");
    }

    @Test
    @DisplayName("h-04 F11: the person who set up a supplier cannot approve it; another user can")
    void supplierApprovalMakerChecker() {
        String partner = supplier("Maker");
        ok(200, "PUT", "/procurement/suppliers/" + partner + "/profile", Map.of("notes", "Set up by admin"));
        Res self = as("POST", "/procurement/suppliers/" + partner + "/profile/approve", Map.of());
        assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("A supplier must be approved by someone other than the person who set it up or last edited it");

        Res other = call("POST", "/procurement/suppliers/" + partner + "/profile/approve", Map.of(), approverToken);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
        assertThat(other.body().get("profile").get("lifecycleStatus").asString()).isEqualTo("approved");
    }
}
