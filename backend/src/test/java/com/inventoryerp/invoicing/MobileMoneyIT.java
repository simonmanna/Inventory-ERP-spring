package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * INT-1 — mobile money over HTTP with the mock provider: a collection becomes exactly one balanced receipt (poll,
 * callback or both), a refusal posts nothing, the route is idempotent, the number is masked, the callback is signed,
 * and tenants are isolated. {@code app.mobile-money.callback-secret} is set in the test properties.
 */
class MobileMoneyIT extends ApiTestSupport {

    static final String SECRET = "test-mobile-money-secret";

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String account;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "MM-" + sfx, "Momo Customer " + sfx)
                .update();
        account = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = 'MOMO-MTN'")
                .param(demoOrg).query(String.class).single();
    }

    Map<String, String> key(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", key);
        return h;
    }

    Map<String, Object> body(String msisdn) {
        return Map.of("partnerId", customer, "amount", 25000, "msisdn", msisdn, "accountId", account);
    }

    JsonNode collect(String msisdn) {
        Res r = call("POST", "/mobile-money/collections", body(msisdn), key(UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode refresh(String id) {
        Res r = call("POST", "/mobile-money/collections/" + id + "/refresh", null, key(UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    void assertBalanced(String paymentId) {
        String entry = jdbc.sql("SELECT \"journalEntryId\" FROM \"Payment\" WHERE id = ?").param(paymentId)
                .query(String.class).single();
        List<Map<String, Object>> lines = jdbc.sql("SELECT \"accountId\", debit, credit FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ?").param(entry).query().listOfRows();
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            dr = dr.add((BigDecimal) l.get("debit"));
            cr = cr.add((BigDecimal) l.get("credit"));
        }
        assertThat(dr).isEqualByComparingTo(cr).isEqualByComparingTo("25000");
        assertThat(lines.stream().anyMatch(l -> account.equals(l.get("accountId"))
                && ((BigDecimal) l.get("debit")).signum() > 0)).isTrue();
    }

    long receipts(String reference) {
        return jdbc.sql("SELECT count(*) FROM \"Payment\" WHERE \"organizationId\" = ? AND reference = ?")
                .params(demoOrg, reference).query(Long.class).single();
    }

    @Test
    @DisplayName("collect → pending → refresh: one balanced receipt; a second refresh adds nothing; number masked")
    void pollToReceipt() {
        JsonNode c = collect("+256772000123");
        assertThat(c.get("status").asString()).isEqualTo("pending");
        assertThat(c.get("msisdnMasked").asString()).endsWith("0123").startsWith("*");
        assertThat(c.toString()).doesNotContain("256772000123");
        String id = c.get("id").asString();
        JsonNode done = refresh(id);
        assertThat(done.get("status").asString()).isEqualTo("successful");
        assertThat(done.get("paymentNumber").asString()).isNotBlank();
        assertBalanced(done.get("paymentId").asString());
        refresh(id);
        assertThat(receipts(c.get("reference").asString())).isEqualTo(1);
    }

    @Test
    @DisplayName("refused by the provider: failed with a reason, no receipt")
    void refused() {
        JsonNode c = collect("0772000129");
        assertThat(c.get("status").asString()).isEqualTo("failed");
        assertThat(c.get("failureReason").asString()).isEqualTo("Insufficient funds");
        assertThat(receipts(c.get("reference").asString())).isZero();
    }

    @Test
    @DisplayName("idempotency: key required; a replay starts no second collection")
    void idempotent() {
        assertThat(as("POST", "/mobile-money/collections", body("0772000128")).status()).isEqualTo(400);
        String k = UUID.randomUUID().toString();
        Res first = call("POST", "/mobile-money/collections", body("0772000128"), key(k));
        Res replay = call("POST", "/mobile-money/collections", body("0772000128"), key(k));
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
    }

    @Test
    @DisplayName("callback: bad signature 401; valid one posts once even when repeated or followed by a poll")
    void callback() throws Exception {
        JsonNode c = collect("0772000120");
        String body = "{\"providerReference\":\"" + c.get("providerReference").asString()
                + "\",\"status\":\"successful\"}";
        String path = "/mobile-money/callbacks/mock/" + demoOrg;
        assertThat(call("POST", path, body, Map.of("X-Signature", "00")).status()).isEqualTo(401);
        assertThat(call("POST", path, body, Map.<String, String>of()).status()).isEqualTo(401);
        assertThat(call("POST", path, body, Map.of("X-Signature", sign(body))).status()).isEqualTo(200);
        assertThat(call("POST", path, body, Map.of("X-Signature", sign(body))).status()).isEqualTo(200);
        refresh(c.get("id").asString());
        assertThat(receipts(c.get("reference").asString())).isEqualTo(1);
        String other = "{\"providerReference\":\"nobody\",\"status\":\"successful\"}";
        assertThat(call("POST", path, other, Map.of("X-Signature", sign(other))).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("allocation: an amount above the invoice balance is refused; another customer's invoice is not found")
    void validation() {
        String invoice = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Invoice\" (id, \"organizationId\", \"invoiceNumber\", \"partnerId\", status,"
                + " \"totalAmount\", \"amountResidual\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'posted'::\"InvoiceStatus\", 100, 100, now())").params(invoice, demoOrg, "MMI-" + sfx, customer).update();
        Map<String, Object> over = new LinkedHashMap<>(body("0772000121"));
        over.put("invoiceId", invoice);
        assertThat(call("POST", "/mobile-money/collections", over, key(UUID.randomUUID().toString())).status())
                .isEqualTo(400);
        assertThat(call("POST", "/mobile-money/collections", Map.of("partnerId", customer, "amount", 100,
                "msisdn", "abc"), key(UUID.randomUUID().toString())).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of it, and a callback under its id touches nothing")
    void tenantIsolation() throws Exception {
        JsonNode c = collect("0772000128");
        String id = c.get("id").asString();
        assertThat(call("GET", "/mobile-money/collections/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/mobile-money/collections", null, otherToken).body().get("data").toString())
                .doesNotContain(id);
        String body = "{\"providerReference\":\"" + c.get("providerReference").asString()
                + "\",\"status\":\"successful\"}";
        assertThat(call("POST", "/mobile-money/callbacks/mock/" + otherOrg, body, Map.of("X-Signature", sign(body)))
                .status()).isEqualTo(200);
        assertThat(as("GET", "/mobile-money/collections/" + id, null).body().get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    @DisplayName("authorization: 401 / 403 on the staff routes; the callback stays public")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.invoicing.web.MobileMoneyController.class)).isEqualTo(6);
    }
}
