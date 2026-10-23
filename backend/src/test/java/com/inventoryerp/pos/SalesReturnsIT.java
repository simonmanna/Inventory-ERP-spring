package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Sales returns register ({@code /returns}): prepare, record via the refund engine, list, inspect, isolate. */
class SalesReturnsIT extends ApiTestSupport {

    static final String PIN = "4321";

    String productId;
    String sessionId;
    String adminId;
    String suffix;

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    @BeforeAll
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "RET-" + suffix, "name", "Mug " + suffix,
                "productType", "service", "salesPrice", 500)).get("id").asString();
        String registerId = ok(201, "POST", "/cash-registers", Map.of("code", "RREG-" + suffix, "name", "Returns till"))
                .get("id").asString();
        Res open = keyed("POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0));
        assertThat(open.status()).as(open.raw().body()).isEqualTo(200);
        sessionId = open.body().get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
    }

    String sale(int qty) {
        Res r = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(Map.of(
                "productId", productId, "description", "Mug", "quantity", qty, "unitPrice", 500)),
                "tenders", List.of(Map.of("method", "cash", "amount", qty * 500))));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("invoiceId").asString();
    }

    @Test
    @DisplayName("prepare → partial return → register lists it with lines, payout and remaining quantity")
    void returnLifecycle() {
        String invoiceId = sale(4);
        String number = jdbc.sql("SELECT \"invoiceNumber\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(String.class).single();

        JsonNode found = ok(200, "GET", "/returns/invoices?search=" + number, null);
        assertThat(found.findValuesAsString("id")).contains(invoiceId);
        assertThat(found.get(0).get("channel").asString()).isEqualTo("retail");

        JsonNode inv = ok(200, "GET", "/returns/invoices/" + invoiceId, null);
        JsonNode line = inv.get("lines").get(0);
        assertThat(line.get("remaining").asDouble()).isEqualTo(4.0);
        assertThat(line.get("unitRefund").asDouble()).isEqualTo(500.0);
        assertThat(ok(200, "GET", "/returns/registers", null).findValuesAsString("id")).contains(sessionId);

        Res refund = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason", "Chipped",
                "overrideById", adminId, "overridePin", PIN, "stockDisposition", "no_return", "cashSessionId", sessionId,
                "lines", List.of(Map.of("lineId", line.get("id").asString(), "quantity", 1))));
        assertThat(refund.status()).as(refund.raw().body()).isEqualTo(200);
        String refundId = refund.body().get("refundId").asString();

        assertThat(ok(200, "GET", "/returns/invoices/" + invoiceId, null).get("lines").get(0).get("remaining")
                .asDouble()).isEqualTo(3.0);

        JsonNode list = ok(200, "GET", "/returns?search=" + number, null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(1);
        JsonNode row = list.get("data").get(0);
        assertThat(row.get("id").asString()).isEqualTo(refundId);
        assertThat(row.get("returnNumber").asString()).isEqualTo(number + "-R1");
        assertThat(row.get("amount").asDouble()).isEqualTo(500.0);
        assertThat(row.get("itemCount").asInt()).isEqualTo(1);
        assertThat(list.get("summary").get("amount").asDouble()).isEqualTo(500.0);
        assertThat(ok(200, "GET", "/returns?search=" + number + "&stockDisposition=restock", null).get("meta")
                .get("total").asLong()).isZero();
        assertThat(ok(200, "GET", "/returns?search=" + number + "&channel=restaurant", null).get("meta")
                .get("total").asLong()).isZero();

        JsonNode detail = ok(200, "GET", "/returns/" + refundId, null);
        assertThat(detail.get("reason").asString()).isEqualTo("Chipped");
        assertThat(detail.get("lines").get(0).get("quantity").asDouble()).isEqualTo(1.0);
        assertThat(detail.get("lines").get(0).get("description").asString()).isEqualTo("Mug");
        assertThat(detail.get("payments").get(0).get("paymentMethod").asString()).isEqualTo("cash");
        assertThat(detail.get("journalEntryNumber").isNull()).isFalse();
    }

    @Test
    @DisplayName("returns are tenant-isolated and fully refunded sales drop out of the returnable list")
    void isolationAndExhaustion() {
        String invoiceId = sale(1);
        Res refund = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason", "Wrong item",
                "overrideById", adminId, "overridePin", PIN, "stockDisposition", "no_return", "cashSessionId", sessionId));
        assertThat(refund.status()).as(refund.raw().body()).isEqualTo(200);
        String refundId = refund.body().get("refundId").asString();
        String number = jdbc.sql("SELECT \"invoiceNumber\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(String.class).single();

        assertThat(ok(200, "GET", "/returns/invoices?search=" + number, null).findValuesAsString("id"))
                .doesNotContain(invoiceId);
        assertThat(call("GET", "/returns/" + refundId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/returns/invoices/" + invoiceId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/returns?search=" + number, null, otherToken).body().get("meta").get("total").asLong())
                .isZero();
        assertThat(call("GET", "/returns", null, weakToken()).status()).isEqualTo(403);
        assertThat(call("GET", "/returns/invoices", null, weakToken()).status()).isEqualTo(403);
    }
}
