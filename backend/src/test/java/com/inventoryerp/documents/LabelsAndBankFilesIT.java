package com.inventoryerp.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.web.BankStatementFileController;
import com.inventoryerp.documents.web.LabelController;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** INT-3 — statement file import (dry run, dedupe, scope) and label rendering over HTTP. */
class LabelsAndBankFilesIT extends ApiTestSupport {

    static final String CSV = """
            Date,Description,Amount
            01/10/2026,Bank charge,-3500
            01/10/2026,Bank charge,-3500
            02/10/2026,Customer deposit,120000
            """;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String bankAccount;
    String product;

    @BeforeAll
    void fixtures() {
        String glAccount = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        bankAccount = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"BankAccount\" (id, \"organizationId\", \"accountId\", name, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, now())").params(bankAccount, demoOrg, glAccount, "Stanbic " + sfx).update();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(product, demoOrg, "LBL-" + sfx, "Aggregate " + sfx).update();
    }

    Res upload(String query, String token) {
        try {
            String boundary = "stmt-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"s.csv\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(CSV.getBytes(StandardCharsets.UTF_8));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v1/bank-reconciliation/import-file?" + query))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long statementLines() {
        return jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"bankAccountId\" = ?").param(bankAccount)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("statement file: dry run writes nothing; import adds each line once; a second import adds none")
    void statementFile() {
        Res dry = upload("bankAccountId=" + bankAccount + "&dryRun=true", adminToken);
        assertThat(dry.status()).as(dry.raw().body()).isEqualTo(200);
        assertThat(dry.body().get("lines").asInt()).isEqualTo(3);
        assertThat(dry.body().get("format").asString()).isEqualTo("csv");
        assertThat(statementLines()).isZero();

        JsonNode first = upload("bankAccountId=" + bankAccount, adminToken).body();
        assertThat(first.get("imported").asInt()).isEqualTo(3);
        JsonNode again = upload("bankAccountId=" + bankAccount, adminToken).body();
        assertThat(again.get("imported").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(3);
        assertThat(statementLines()).isEqualTo(3);
    }

    @Test
    @DisplayName("statement file: another organization cannot import into this bank account")
    void statementTenant() {
        assertThat(upload("bankAccountId=" + bankAccount, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("labels: sources by search; PDF and ZPL render; unknown or foreign ids are 404")
    void labels() throws Exception {
        JsonNode sources = ok(200, "GET", "/labels/sources/product?search=LBL-" + sfx, null);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("code").asString()).isEqualTo("LBL-" + sfx);
        HttpRequest pdf = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/labels/render"))
                .header("Authorization", "Bearer " + adminToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("type", "product", "ids",
                        List.of(product), "copies", 2)))).build();
        HttpResponse<byte[]> r = http.send(pdf, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(new String(r.body(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(as("POST", "/labels/render", Map.of("type", "product", "ids", List.of(UUID.randomUUID().toString())))
                .status()).isEqualTo(404);
        assertThat(call("POST", "/labels/render", Map.of("type", "product", "ids", List.of(product)), otherToken)
                .status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/labels/sources/stockpile", null).isArray()).isTrue();
        assertThat(ok(200, "GET", "/labels/sources/ticket", null).isArray()).isTrue();
    }

    @Test
    @DisplayName("authorization matrix: labels and the statement file route")
    void authorization() {
        assertThat(assertAuthorizationMatrix(LabelController.class, BankStatementFileController.class)).isEqualTo(3);
    }
}
