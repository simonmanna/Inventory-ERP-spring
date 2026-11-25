package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit B2: an opening-balance batch is reversed as a whole through its own route — invoices
 * cancelled, bills voided and the GL entry reversed in one transaction — while the generic journal reversal still
 * refuses it and names that route.
 */
class OpeningBalanceReverseIT extends ApiTestSupport {

    final String today = LocalDate.now().toString();

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    String partner(boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "P-" + suffix(), (supplier ? "Supplier " : "Customer ") + suffix(), supplier,
                        !supplier)
                .update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    record Batch(String id, String entryId, String invoiceId, String billId, String customer, String supplier) {}

    Batch postBatch() {
        String customer = partner(false);
        String supplier = partner(true);
        Res posted = call("POST", "/opening-balances", Map.of("date", today, "reference", "B2 " + suffix(),
                "accounts", List.of(Map.of("accountId", account("1100"), "debit", 500)),
                "customers", List.of(Map.of("partnerId", customer, "amount", 250, "dueDate", today)),
                "suppliers", List.of(Map.of("partnerId", supplier, "amount", 90))), key());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        JsonNode b = posted.body();
        return new Batch(b.get("batchId").asString(), b.get("journalEntry").get("id").asString(),
                b.get("invoices").get(0).get("id").asString(), b.get("bills").get(0).get("id").asString(), customer,
                supplier);
    }

    String entryStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(id).query(String.class).single();
    }

    String docStatus(String id) {
        return jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    @DisplayName("B2: the whole batch is reversed in one step — GL entry mirrored, invoice and bill cancelled, audited")
    void reversesTheWholeBatch() {
        Batch b = postBatch();
        String path = "/opening-balances/" + b.id() + "/reverse";

        Map<String, String> noKey = new LinkedHashMap<>();
        noKey.put("Authorization", "Bearer " + adminToken);
        noKey.put(NO_IDEMPOTENCY_KEY, "1");
        assertThat(call("POST", path, Map.of("reason", "Wrong cut-over"), noKey).status()).as("key required")
                .isEqualTo(400);
        assertThat(call("POST", path, Map.of("reason", " "), key()).status()).as("reason required").isEqualTo(400);
        assertThat(call("POST", "/opening-balances/" + UUID.randomUUID() + "/reverse", Map.of("reason", "x"), key())
                .status()).isEqualTo(404);

        Res r = call("POST", path, Map.of("reason", "Wrong cut-over"), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("reversedEntries")).hasSize(1);
        assertThat(r.body().get("cancelledDocuments")).hasSize(2);
        String reversalId = r.body().get("reversedEntries").get(0).get("reversalId").asString();

        assertThat(entryStatus(b.entryId())).isEqualTo("reversed");
        assertThat(docStatus(b.invoiceId())).isEqualTo("cancelled");
        assertThat(docStatus(b.billId())).isEqualTo("cancelled");
        // the batch entry and its mirror net to zero on every account
        List<BigDecimal> nets = jdbc.sql("SELECT sum(\"baseDebit\" - \"baseCredit\") FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" IN (?, ?) GROUP BY \"accountId\"").params(b.entryId(), reversalId)
                .query(BigDecimal.class).list();
        assertThat(nets).isNotEmpty().allSatisfy(n -> assertThat(n).isEqualByComparingTo("0"));
        assertThat(ok(200, "GET", "/reports/ar/customers/" + b.customer() + "/statement", null).get("closingBalance")
                .asString()).isEqualTo("0");
        assertThat(ok(200, "GET", "/reports/ap/suppliers/" + b.supplier() + "/statement", null).get("closingBalance")
                .asString()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'OpeningBalanceBatch' AND \"entityId\" = ?")
                .param(b.id()).query(Long.class).single()).isEqualTo(1);

        Res again = call("POST", path, Map.of("reason", "Again"), key());
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).isEqualTo("Opening-balance batch " + b.id()
                + " is already reversed");
    }

    @Test
    @DisplayName("B2: a settled document refuses the reversal and nothing changes; the generic route names the batch route")
    void settledDocumentRefusesAndNothingChanges() {
        Batch b = postBatch();
        // the bill is reversed after the invoice: its refusal must roll the invoice's cancellation back too
        jdbc.sql("UPDATE \"Document\" SET \"amountPaid\" = 10 WHERE id = ?").param(b.billId()).update();
        Res r = call("POST", "/opening-balances/" + b.id() + "/reverse", Map.of("reason", "Wrong cut-over"), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("is recorded as paid");
        assertThat(entryStatus(b.entryId())).isEqualTo("posted");
        assertThat(docStatus(b.invoiceId())).isEqualTo("posted");
        assertThat(docStatus(b.billId())).isEqualTo("posted");

        Res generic = as("POST", "/journal-entries/" + b.entryId() + "/reverse", null);
        assertThat(generic.status()).isEqualTo(400);
        assertThat(generic.body().get("message").asString()).contains("cannot be reversed generically")
                .contains("(POST /opening-balances/" + b.id() + "/reverse)");
        assertThat(entryStatus(b.entryId())).isEqualTo("posted");
    }

    @Test
    @DisplayName("B2: invoices already cancelled one by one are skipped; the rest of the batch is reversed")
    void skipsDocumentsAlreadyCancelled() {
        Batch b = postBatch();
        ok(200, "POST", "/invoices/" + b.invoiceId() + "/cancel", Map.of("reason", "Cancelled alone"));
        Res r = call("POST", "/opening-balances/" + b.id() + "/reverse", Map.of("reason", "Rest of it"), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("cancelledDocuments")).hasSize(1);
        assertThat(r.body().get("cancelledDocuments").get(0).get("id").asString()).isEqualTo(b.billId());
        assertThat(entryStatus(b.entryId())).isEqualTo("reversed");
    }
}
