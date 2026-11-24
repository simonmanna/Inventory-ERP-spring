package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ACC-8 posting integrity: the VAT-period lock also holds for reversals and approved drafts, and concurrent reversals
 * of one entry serialize (one reversal, never two).
 */
class Acc8PostingIntegrityIT extends AccountingFixtures {

    String partner(Books b, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, b.org(), "T-" + id.substring(0, 8),
                "Tax partner " + id.substring(0, 4), supplier, !supplier).update();
        return id;
    }

    String product(Books b, String taxId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"taxId\", \"updatedAt\") VALUES (?, ?, ?, 'Service',"
                        + " 'service', 100, 0, false, ?, now())")
                .params(id, b.org(), "S-" + id.substring(0, 6), taxId).update();
        return id;
    }

    /** Books with a posted sale and purchase yesterday, VAT settled from 1 January through yesterday. */
    record Settled(Books b, String invoiceId, String invoiceEntryId, String settlementEntryId, String yesterday) {}

    Settled settledBooks() {
        Books b = freshBooks();
        String vat = okAs(b, 201, "POST", "/taxes", Map.of("name", "VAT 18", "rate", 18, "type", "vat",
                "vatCategory", "standard")).get("id").asString();
        String item = product(b, vat);
        String yesterday = LocalDate.now().minusDays(1).toString();
        String from = LocalDate.now().minusDays(1).withDayOfYear(1).toString();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate", yesterday,
                "lines", List.of(Map.of("productId", item, "quantity", 2, "unitPrice", 100)))).get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", partner(b, true), "issueDate",
                yesterday, "lines", List.of(Map.of("description", "Supplies", "quantity", 1, "unitPrice", 100,
                        "taxId", vat)))).get("id").asString();
        okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        String settlement = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday))
                .get("journalEntryId").asString();
        String invoiceEntry = okAs(b, 200, "GET", "/invoices/" + inv, null).get("journalEntryId").asString();
        return new Settled(b, inv, invoiceEntry, settlement, yesterday);
    }

    String postingDay(String entryId) {
        return jdbc.sql("SELECT to_char(\"postingDate\", 'YYYY-MM-DD') FROM \"JournalEntry\" WHERE id = ?")
                .param(entryId).query(String.class).single();
    }

    @Test
    @DisplayName("[ACC-8] cancelling an invoice of a settled VAT period reverses it today; the filed period is untouched")
    void cancelAfterSettlementReversesIntoCurrentPeriod() {
        Settled s = settledBooks();
        String taxPayable = mappedCode(s.b().org(), "tax_payable");
        BigDecimal filed = tbAsOf(s.b().org(), s.yesterday(), TbView.POST_CLOSING).get(taxPayable);

        Res cancel = as(s.b(), "POST", "/invoices/" + s.invoiceId() + "/cancel",
                Map.of("reason", "Wrong customer"));
        assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(200);

        String reversal = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?")
                .param(s.invoiceEntryId()).query(String.class).single();
        assertThat(postingDay(reversal)).as("reversal date").isEqualTo(LocalDate.now().toString());
        assertThat(tbAsOf(s.b().org(), s.yesterday(), TbView.POST_CLOSING).get(taxPayable))
                .as("VAT payable of the settled period").isEqualByComparingTo(filed);
    }

    @Test
    @DisplayName("[ACC-8] a reversal dated inside a settled VAT period is refused")
    void explicitReversalIntoSettledPeriodRefused() {
        Books b = freshBooks();
        String yesterday = LocalDate.now().minusDays(1).toString();
        String from = LocalDate.now().minusDays(1).withDayOfYear(1).toString();
        // A manual VAT correction yesterday (an invoice's own entry is reversed by cancelling the invoice instead).
        jdbc.sql("UPDATE \"Account\" SET \"allowManualPosting\" = true WHERE id = ?")
                .param(mapped(b.org(), "tax_payable")).update();
        String entry = postManualJe(b, yesterday, List.of(JeLine.dr(mappedCode(b.org(), "default_cash"), 40),
                JeLine.cr(mappedCode(b.org(), "tax_payable"), 40)));
        okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday));

        Res back = moneyAs(b, "POST", "/journal-entries/" + entry + "/reverse", Map.of("date", yesterday));
        assertThat(back.status()).as(back.raw().body()).isEqualTo(400);
        assertThat(back.body().get("message").asString()).startsWith("VAT is settled through");
        assertThat(postingDay(entry)).isEqualTo(yesterday);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entry)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("[ACC-8] a draft touching VAT, staged before settlement and approved after, is refused")
    void draftApprovedAfterSettlementRefused() {
        Books b = freshBooks();
        String yesterday = LocalDate.now().minusDays(1).toString();
        String from = LocalDate.now().minusDays(1).withDayOfYear(1).toString();
        String taxPayable = mapped(b.org(), "tax_payable");
        String cash = mapped(b.org(), "default_cash");
        jdbc.sql("UPDATE \"Account\" SET \"allowManualPosting\" = true WHERE id = ?").param(taxPayable).update();
        JsonNode draft = okAs(b, 200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", yesterday,
                "description", "VAT correction", "lines", List.of(Map.of("accountId", cash, "debit", 50),
                        Map.of("accountId", taxPayable, "credit", 50))));
        // Something to settle: a sale with VAT yesterday.
        String vat = okAs(b, 201, "POST", "/taxes", Map.of("name", "VAT 18", "rate", 18, "type", "vat",
                "vatCategory", "standard")).get("id").asString();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate", yesterday,
                "lines", List.of(Map.of("productId", product(b, vat), "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday));

        String role = jdbc.sql("SELECT \"A\" FROM \"_UserRoles\" WHERE \"B\" = ? LIMIT 1").param(b.ownerId())
                .query(String.class).single();
        String email = "checker-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Check@1234567", role);
        String checker = login(b.code(), email, "Check@1234567");
        Res post = call("POST", "/journal-entries/" + draft.get("id").asString() + "/post", null,
                Map.of("Authorization", "Bearer " + checker, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).startsWith("VAT is settled through");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(draft.get("id").asString())
                .query(String.class).single()).isEqualTo("draft");
    }

    @Test
    @DisplayName("[ACC-8] two concurrent reversals of one entry → exactly one reversal")
    void concurrentReversalsSerialize() throws Exception {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String entry = postJe(b, LocalDate.now().toString(), List.of(JeLine.dr(cash, 75), JeLine.cr("3100", 75)));

        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return inTenant(b.org(), b.ownerId(), () -> posting.reverse(entry, null, null));
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        long reversals = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entry)
                .query(Long.class).single();
        assertThat(reversals).as("reversal entries").isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof Map).count()).as("successful reversals").isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ApiException).map(o -> ((ApiException) o).getMessage()))
                .hasSize(n - 1).allMatch("Only posted entries can be reversed"::equals);
    }
}
