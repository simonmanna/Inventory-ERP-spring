package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finance readiness audit B5 (idempotency keys): a key refused while its request was bound or validated is released,
 * and an uploaded file is part of the request fingerprint.
 */
class IdempotencyB5IT extends ApiTestSupport {

    private Map<String, String> keyed(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", key);
        return h;
    }

    private String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    private long records(String key) {
        return jdbc.sql("SELECT count(*) FROM \"IdempotencyRecord\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(Long.class).single();
    }

    @Test
    @DisplayName("B5: a request refused by validation releases its key; a corrected retry with the same key runs")
    void validationErrorReleasesTheKey() {
        String key = UUID.randomUUID().toString();
        Res invalid = call("POST", "/journal-entries", Map.of("journalCode", "GEN", "description", "B5 idem"),
                keyed(key));
        assertThat(invalid.status()).as(invalid.raw().body()).isEqualTo(400);
        assertThat(records(key)).as("nothing ran: the key is not kept pending").isZero();

        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", account("1100"));
        debit.put("debit", 5);
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("accountId", account("1200"));
        credit.put("credit", 5);
        Res valid = call("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", java.time.LocalDate.now()
                .toString(), "description", "B5 idem", "lines", List.of(debit, credit)), keyed(key));
        assertThat(valid.status()).as(valid.raw().body()).isEqualTo(200);
    }

    private String bankAccount;

    /** A {@code BankAccount} row on the DEMO bank's GL account: the file import is keyed on it. */
    private String bankAccount() {
        if (bankAccount == null) {
            bankAccount = jdbc.sql("SELECT id FROM \"BankAccount\" WHERE \"accountId\" = ?").param(account("1200"))
                    .query(String.class).optional().orElse(null);
        }
        if (bankAccount == null) {
            bankAccount = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"BankAccount\" (id, \"organizationId\", \"accountId\", name, \"updatedAt\")"
                    + " VALUES (?, ?, ?, 'B5 idempotency bank', now())").params(bankAccount, demoOrg, account("1200"))
                    .update();
        }
        return bankAccount;
    }

    private Res upload(String key, String csv) {
        try {
            String boundary = "stmt-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"s.csv\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v1/bank-reconciliation/import-file?dryRun=true&bankAccountId=" + bankAccount()))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("B5: a key reused with a different uploaded file is refused (409); the same file replays")
    void uploadedFileIsFingerprinted() {
        String key = UUID.randomUUID().toString();
        String a = "Date,Description,Amount\n2026-10-01,Bank charge,-3500\n";
        String b = "Date,Description,Amount\n2026-10-02,Customer deposit,120000\n";
        Res first = upload(key, a);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res same = upload(key, a);
        assertThat(same.status()).as(same.raw().body()).isEqualTo(200);
        // a replay answers from the cache, decimals as JSON numbers (Nest parity: IdempotencyService.cacheMapper)
        assertThat(same.body().get("preview").get(0).get("externalRef").asString())
                .isEqualTo(first.body().get("preview").get(0).get("externalRef").asString());
        assertThat(same.body().get("moneyOut").decimalValue())
                .isEqualByComparingTo(first.body().get("moneyOut").asString());
        Res other = upload(key, b);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(409);
    }
}
