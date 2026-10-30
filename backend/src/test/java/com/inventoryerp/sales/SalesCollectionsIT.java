package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
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
 * Phase S8 of ADR-S011: dunning levels and runs (level by oldest overdue days, e-mail / letter, late-fee draft
 * invoice, credit hold, no repeat of a level, promise-to-pay skip), promises settled kept / broken, collections
 * worklist by aging bucket, statement run.
 */
class SalesCollectionsIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String product;

    @BeforeAll
    void fixtures() {
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 1000,"
                + " 0, false, now())").params(product, demoOrg, "CO-" + sfx, "Haulage " + sfx).update();
        ok(201, "POST", "/sales/collections/levels", Map.of("sequence", 1, "name", "Friendly reminder", "daysOverdue", 1,
                "channel", "email", "subject", "Reminder for {{customer}}", "body", "{{amount}} is {{days}} days overdue"));
        ok(201, "POST", "/sales/collections/levels", Map.of("sequence", 2, "name", "Second notice", "daysOverdue", 30,
                "channel", "letter", "subject", "Second notice", "body", "Please pay {{amount}}", "feeAmount", 5000));
        ok(201, "POST", "/sales/collections/levels", Map.of("sequence", 3, "name", "Final notice", "daysOverdue", 60,
                "channel", "letter", "subject", "Final", "body", "Account on hold", "setCreditHold", true));
        assertThat(as("POST", "/sales/collections/levels", Map.of("sequence", 1, "name", "dup", "daysOverdue", 2,
                "subject", "x", "body", "y")).status()).isEqualTo(409);
    }

    String customer(String email) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, true, true, now())").params(id, demoOrg, "CC-" + UUID.randomUUID()
                        .toString().substring(0, 8), "Debtor " + sfx, email).update();
        return id;
    }

    String overdueInvoice(String partner, int amount, int daysOverdue) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", today, "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", amount)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        jdbc.sql("UPDATE \"Document\" SET \"dueDate\" = now() - make_interval(days => ?) WHERE id = ?").params(daysOverdue,
                inv).update();
        return inv;
    }

    JsonNode entryFor(JsonNode run, String partner) {
        for (JsonNode e : run.get("entries")) {
            if (partner.equals(e.get("partnerId").asString())) {
                return e;
            }
        }
        return null;
    }

    @Test
    @DisplayName("dunning run: levels by days, letter / e-mail, fee invoice, credit hold, no repeat, promise skips")
    void dunning() {
        String a = customer("a-" + sfx.toLowerCase() + "@demo.test");
        String b = customer(null);
        String c = customer("c-" + sfx.toLowerCase() + "@demo.test");
        String d = customer(null);
        overdueInvoice(a, 100000, 45);
        overdueInvoice(b, 20000, 10);
        overdueInvoice(c, 30000, 40);
        overdueInvoice(d, 50000, 70);
        ok(201, "POST", "/sales/collections/promises", Map.of("partnerId", c, "amount", 30000, "promisedDate",
                LocalDate.now().plusDays(5).toString()));
        assertThat(as("POST", "/sales/collections/promises", Map.of("partnerId", c, "amount", 1, "promisedDate",
                LocalDate.now().minusDays(1).toString())).status()).isEqualTo(400);
        JsonNode run = ok(201, "POST", "/sales/collections/runs", Map.of());
        JsonNode ea = entryFor(run, a);
        assertThat(ea.get("levelSequence").asInt()).isEqualTo(2);
        assertThat(ea.get("delivery").asString()).isEqualTo("letter");
        String fee = ea.get("feeInvoiceId").asString();
        assertThat(ok(200, "GET", "/invoices/" + fee, null).get("totalAmount").asDouble()).isEqualTo(5000);
        JsonNode eb = entryFor(run, b);
        assertThat(eb.get("levelSequence").asInt()).isEqualTo(1);
        assertThat(eb.get("delivery").asString()).isEqualTo("letter");
        assertThat(eb.get("error").asString()).contains("No e-mail");
        assertThat(entryFor(run, c)).isNull();
        JsonNode ed = entryFor(run, d);
        assertThat(ed.get("levelSequence").asInt()).isEqualTo(3);
        assertThat(ed.get("creditHoldSet").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT \"creditHold\" FROM \"Partner\" WHERE id = ?").param(d).query(Boolean.class).single())
                .isTrue();
        // a second run does not repeat the same level
        JsonNode again = ok(201, "POST", "/sales/collections/runs", Map.of());
        assertThat(entryFor(again, a)).isNull();
        assertThat(entryFor(again, d)).isNull();
        assertThat(ok(200, "GET", "/sales/collections/runs", null).size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("promises: kept when receipts since the promise reach the amount, else broken")
    void promises() {
        String kept = customer(null);
        String broken = customer(null);
        String i1 = overdueInvoice(kept, 10000, 5);
        overdueInvoice(broken, 10000, 5);
        String p1 = ok(201, "POST", "/sales/collections/promises", Map.of("partnerId", kept, "amount", 10000,
                "promisedDate", today)).get("id").asString();
        String p2 = ok(201, "POST", "/sales/collections/promises", Map.of("partnerId", broken, "amount", 10000,
                "promisedDate", today)).get("id").asString();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/payments", Map.of("partnerId", kept, "paymentDate", today, "amount", 10000,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", i1, "amount", 10000))), h).status())
                .isEqualTo(200);
        ok(200, "POST", "/sales/collections/promises/settle", Map.of("asOf", LocalDate.now().plusDays(1).toString()));
        List<String> statuses = new ArrayList<>();
        for (JsonNode t : ok(200, "GET", "/sales/collections/promises", null)) {
            if (t.get("id").asString().equals(p1)) {
                statuses.add("p1:" + t.get("status").asString());
            }
            if (t.get("id").asString().equals(p2)) {
                statuses.add("p2:" + t.get("status").asString());
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder("p1:kept", "p2:broken");
    }

    @Test
    @DisplayName("worklist by aging bucket; statement run counts; settings flags")
    void worklistAndStatements() {
        String w = customer("w-" + sfx.toLowerCase() + "@demo.test");
        overdueInvoice(w, 70000, 100);
        JsonNode list = ok(200, "GET", "/sales/collections/worklist?bucket=over90", null);
        JsonNode row = null;
        for (JsonNode r : list) {
            if (w.equals(r.get("partnerId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("over90").asDouble()).isEqualTo(70000);
        JsonNode st = ok(200, "POST", "/sales/collections/statements", Map.of());
        assertThat(st.get("sent").asInt() + st.get("skipped").asInt()).isPositive();
        assertThat(ok(200, "PUT", "/sales/settings", Map.of("dunningAutoRun", true)).get("dunningAutoRun").asBoolean())
                .isTrue();
        ok(200, "PUT", "/sales/settings", Map.of("dunningAutoRun", false));
    }
}
