package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.sequence.DocumentNumberCatalog;
import com.inventoryerp.kernel.sequence.DocumentNumberingController;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** CTRL-2 on the other organization of the suite, so formats never reach DEMO's numbers. */
class DocumentNumberingIT extends ApiTestSupport {

    int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();

    JsonNode okBy(int status, String method, String path, Object body) {
        Res r = call(method, path, body, otherToken);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String requestNumber() {
        return okBy(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description", "Item",
                "quantity", 1)))).get("requestNumber").asString();
    }

    @Test
    @DisplayName("a format changes the next number only, the counter carries on, reset restores the default")
    void format() {
        String first = requestNumber();
        assertThat(first).startsWith("PR-" + year + "-");
        long counter = Long.parseLong(first.substring(first.lastIndexOf('-') + 1));

        JsonNode saved = okBy(200, "PUT", "/document-numbering/pr", Map.of("prefix", "REQ/{YY}/", "suffix", "-X",
                "padding", 3));
        assertThat(saved.get("customized").asBoolean()).isTrue();
        String yy = String.valueOf(year).substring(2);
        assertThat(requestNumber()).isEqualTo("REQ/" + yy + "/" + String.format("%03d", counter + 1) + "-X");

        assertThat(okBy(200, "DELETE", "/document-numbering/pr", null).get("customized").asBoolean()).isFalse();
        assertThat(requestNumber()).isEqualTo("PR-" + year + "-" + String.format("%05d", counter + 2));
    }

    @Test
    @DisplayName("a counter only moves forward")
    void advance() {
        requestNumber();
        Res back = call("POST", "/document-numbering/pr/advance", Map.of("nextNumber", 1), otherToken);
        assertThat(back.status()).isEqualTo(400);
        okBy(200, "POST", "/document-numbering/pr/advance", Map.of("nextNumber", 5000));
        assertThat(requestNumber()).isEqualTo("PR-" + year + "-05000");
    }

    @Test
    @DisplayName("POS and unknown keys are not configurable; a yearly number must keep the year; bad text is refused")
    void validation() {
        for (String key : List.of("invoice", "receipt", "order", "kds_ticket", "journal", "nope")) {
            assertThat(call("PUT", "/document-numbering/" + key, Map.of("prefix", "X-", "padding", 3), otherToken)
                    .status()).as(key).isEqualTo(404);
        }
        Res noYear = call("PUT", "/document-numbering/po", Map.of("prefix", "PO-", "padding", 5), otherToken);
        assertThat(noYear.status()).isEqualTo(400);
        assertThat(call("PUT", "/document-numbering/stock_adj", Map.of("prefix", "A B", "padding", 5), otherToken)
                .status()).isEqualTo(400);
        assertThat(call("PUT", "/document-numbering/stock_adj", Map.of("prefix", "{DD}", "padding", 5), otherToken)
                .status()).isEqualTo(400);
        assertThat(call("PUT", "/document-numbering/stock_adj", Map.of("prefix", "A-", "padding", 13), otherToken)
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("render fills the date tokens and pads")
    void render() {
        assertThat(DocumentNumberCatalog.render("PO-{YYYY}/{MM}-", "/{YY}", 5, "42", 2026, 3))
                .isEqualTo("PO-2026/03-00042/26");
        assertThat(DocumentNumberCatalog.render("", "", 2, "12345", 2026, 11)).isEqualTo("12345");
        assertThat(DocumentNumberCatalog.configurable("po:2026")).isTrue();
        assertThat(DocumentNumberCatalog.configurable("invoice:2026")).isFalse();
    }

    @Test
    @DisplayName("tenancy: a format of another organization does not apply")
    void tenantIsolation() {
        okBy(200, "PUT", "/document-numbering/stock_out", Map.of("prefix", "OUT-", "padding", 3));
        JsonNode demo = ok(200, "GET", "/document-numbering", null);
        for (JsonNode f : demo) {
            if ("stock_out".equals(f.get("key").asString())) {
                assertThat(f.get("customized").asBoolean()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("authorization: every numbering route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(DocumentNumberingController.class)).isEqualTo(4);
    }
}
