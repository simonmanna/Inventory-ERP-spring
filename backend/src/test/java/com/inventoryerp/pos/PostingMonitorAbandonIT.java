package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ACR-17): a failed stock-posting job that can never post is abandoned with a note — it stops
 * blocking the period close, is never claimed again, and its open exceptions are discarded with the same note.
 */
class PostingMonitorAbandonIT extends ApiTestSupport {

    String job(String status, String invoiceId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"StockPostingJob\" (id, \"organizationId\", \"invoiceId\", \"invoiceNumber\", status,"
                + " attempts, \"lastError\", \"idempotencyKey\") VALUES (?, ?, ?, ?, ?, 5, 'Product deleted', ?)")
                .params(id, demoOrg, invoiceId, "INV-AB-" + id.substring(0, 6), status, "abandon-it:" + id).update();
        return id;
    }

    String exception(String invoiceId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"invoiceId\", kind, reason) VALUES (?, ?,"
                + " ?, 'whole_invoice', 'Product deleted')").params(id, demoOrg, invoiceId).update();
        return id;
    }

    String status(String table, String id) {
        return jdbc.sql("SELECT status FROM \"" + table + "\" WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    @DisplayName("abandon: failed only, a real note, exceptions discarded, never retried or claimed again")
    void abandonFailedJob() {
        String invoice = UUID.randomUUID().toString();
        String failed = job("failed", invoice);
        String ex = exception(invoice);
        String path = "/pos/posting-monitor/jobs/" + failed + "/abandon";

        Res foreign = call("POST", path, Map.of("note", "Stock corrected by adjustment"), otherToken);
        assertThat(foreign.status()).as("another organisation cannot see the job").isEqualTo(404);
        assertThat(status("StockPostingJob", failed)).isEqualTo("failed");

        Res noNote = as("POST", path, Map.of("note", "too short"));
        assertThat(noNote.status()).isEqualTo(400);
        assertThat(noNote.body().get("message").asString()).startsWith("A note of at least 10 characters is required");
        assertThat(as("POST", path, Map.of()).status()).isEqualTo(400);

        JsonNode done = ok(201, "POST", path, Map.of("note", "Stock corrected by adjustment ADJ-17"));
        assertThat(done.get("status").asString()).isEqualTo("abandoned");
        assertThat(done.get("exceptionsDiscarded").asInt()).isEqualTo(1);
        assertThat(status("StockPostingJob", failed)).isEqualTo("abandoned");
        assertThat(status("InventoryException", ex)).isEqualTo("discarded");
        assertThat(jdbc.sql("SELECT \"resolutionNote\" FROM \"InventoryException\" WHERE id = ?").param(ex)
                .query(String.class).single()).isEqualTo("Stock posting job abandoned: Stock corrected by adjustment"
                        + " ADJ-17");

        // gone for good: not retryable, not abandonable twice
        Res retry = as("POST", "/pos/posting-monitor/jobs/" + failed + "/retry", null);
        assertThat(retry.status()).isEqualTo(400);
        assertThat(retry.body().get("message").asString()).isEqualTo("Only failed or pending jobs can be retried (this"
                + " one is abandoned)");
        Res twice = as("POST", path, Map.of("note", "Stock corrected by adjustment ADJ-17"));
        assertThat(twice.body().get("message").asString()).isEqualTo("Only failed jobs can be abandoned (this one is"
                + " abandoned)");

        String pending = job("pending", UUID.randomUUID().toString());
        Res notFailed = as("POST", "/pos/posting-monitor/jobs/" + pending + "/abandon", Map.of("note",
                "Stock corrected by adjustment"));
        assertThat(notFailed.body().get("message").asString()).isEqualTo("Only failed jobs can be abandoned (this one"
                + " is pending)");
        jdbc.sql("DELETE FROM \"StockPostingJob\" WHERE id IN (?, ?)").params(failed, pending).update();

        assertThat(call("POST", "/pos/posting-monitor/jobs/" + UUID.randomUUID() + "/abandon", Map.of("note",
                "Stock corrected by adjustment"), weakToken()).status()).isEqualTo(403);
    }
}
