package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S083 C2: the post-restore reconciliation. Each defect a restore can leave behind is planted, the run must
 * report it (and alert the administrators when the issues change), and nothing is changed by the run itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncReconciliationIT extends ApiTestSupport {

    @Autowired
    Flyway flyway;

    JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key + " in " + report);
    }

    static List<String> column(JsonNode check, String field) {
        List<String> out = new ArrayList<>();
        check.get("rows").forEach(r -> out.add(r.get(field).asString()));
        return out;
    }

    /** As the schema owner with the table's own triggers off: how a pre-V513 backup or a corruption looks. */
    void asOwnerWithoutTriggers(String... sql) throws Exception {
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE \"JournalEntry\" DISABLE TRIGGER USER");
                st.execute("ALTER TABLE \"JournalLine\" DISABLE TRIGGER USER");
                for (String s : sql) {
                    st.execute(s);
                }
                st.execute("ALTER TABLE \"JournalLine\" ENABLE TRIGGER USER");
                st.execute("ALTER TABLE \"JournalEntry\" ENABLE TRIGGER USER");
            }
            c.commit();
        }
    }

    @Test
    @DisplayName("reports device watermarks ahead of the ledger, committed ops, unbalanced journals, stock drift; alerts")
    void reportsPlantedDefects() throws Exception {
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        // 1. A device whose acknowledged sequence is ahead of the ledger (the restore lost its op 2..5).
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Recon " + UUID.randomUUID()
                .toString().substring(0, 6), "platform", "web"));
        String deviceId = device.get("id").asString();
        String cust = UUID.randomUUID().toString();
        Res push = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(Map.of("opId", cust,
                "deviceSeq", 1, "type", "customer.upsert", "actorUserId", admin, "occurredAt", Instant.now().toString(),
                "payload", Map.of("clientId", cust, "id", cust, "name", "Recon " + cust.substring(0, 6))))),
                Map.of("X-Device-Token", device.get("deviceToken").asString(), "X-Sync-Protocol", "2.0"));
        assertThat(push.status()).as(push.raw().body()).isEqualTo(201);
        jdbc.sql("UPDATE \"PosDevice\" SET \"lastPushSeq\" = 5 WHERE id = ?").param(deviceId).update();
        // 2. A ledger row committed without a result.
        String committed = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SyncAppliedOp\" (id, \"organizationId\", \"opId\", \"payloadHash\", \"opType\","
                + " \"deviceId\", \"deviceSeq\", \"serverId\", state, \"committedAt\") VALUES (?, ?, ?, 'h', 'sale.checkout',"
                + " ?, 1, 'hq', 'committed', now() - interval '10 minutes')").params(UUID.randomUUID().toString(),
                        demoOrg, committed, deviceId).update();
        // 3. An unbalanced posted journal.
        String entry = UUID.randomUUID().toString();
        String journal = jdbc.sql("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        List<String> accounts = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? ORDER BY code LIMIT 2")
                .param(demoOrg).query(String.class).list();
        asOwnerWithoutTriggers(
                "INSERT INTO \"JournalEntry\" (id, \"organizationId\", \"journalId\", \"entryNumber\", \"postingDate\","
                        + " status, \"updatedAt\") VALUES ('" + entry + "', '" + demoOrg + "', '" + journal + "', 'RECON-"
                        + entry.substring(0, 6) + "', now(), 'posted', now())",
                "INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", debit, \"baseDebit\")"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + demoOrg + "', '" + entry + "', '" + accounts.get(0)
                        + "', 100, 100)",
                "INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", credit,"
                        + " \"baseCredit\") VALUES ('" + UUID.randomUUID() + "', '" + demoOrg + "', '" + entry + "', '"
                        + accounts.get(1) + "', 90, 90)");
        // 4. On-hand with no movements behind it.
        String product = ok(201, "POST", "/products", Map.of("code", "RCN-" + entry.substring(0, 6), "name", "Recon item",
                "productType", "stockable", "trackInventory", true, "salesPrice", 5, "costPrice", 1)).get("id").asString();
        String location = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type ="
                + " 'warehouse' ORDER BY code LIMIT 1").param(demoOrg).query(String.class).single();
        String item = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 7, now())").params(item, demoOrg, product, location).update();
        long partnersBefore = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        try {
            JsonNode report = ok(201, "POST", "/sync/reconciliation/run", Map.of());
            assertThat(report.get("status").asString()).isEqualTo("issues");
            assertThat(report.get("trigger").asString()).isEqualTo("manual");
            assertThat(column(check(report, "watermarks"), "id")).contains(deviceId);
            assertThat(column(check(report, "committedOps"), "opId")).contains(committed);
            JsonNode journals = check(report, "unbalancedJournals");
            assertThat(journals.get("ok").asBoolean()).isFalse();
            assertThat(column(journals, "id")).contains(entry);
            assertThat(column(check(report, "stock"), "productId")).contains(product);
            assertThat(check(report, "receivables").get("ok").isBoolean()).isTrue();
            assertThat(check(report, "payables").get("ok").isBoolean()).isTrue();
            assertThat(check(report, "deadLetters").get("ok").isNull()).as("information only").isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ?").param(demoOrg)
                    .query(Long.class).single()).as("read-only").isEqualTo(partnersBefore);

            String runId = report.get("id").asString();
            assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category = 'sync'"
                    + " AND payload->>'entityId' = ?").params(demoOrg, runId).query(Long.class).single())
                    .as("administrators alerted").isPositive();
            JsonNode runs = ok(200, "GET", "/sync/reconciliation/runs", null);
            assertThat(runs.get(0).get("id").asString()).isEqualTo(runId);
            JsonNode stored = ok(200, "GET", "/sync/reconciliation/runs/" + runId, null);
            assertThat(stored.get("issueCount").asInt()).isEqualTo(report.get("issueCount").asInt());
            assertThat(call("GET", "/sync/reconciliation/runs/" + runId, null, otherToken).status())
                    .as("another organisation cannot read it").isEqualTo(404);

            // The same issues again: stored, not re-alerted.
            String again = ok(201, "POST", "/sync/reconciliation/run", Map.of()).get("id").asString();
            assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE payload->>'entityId' = ?").param(again)
                    .query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DELETE FROM \"StockItem\" WHERE id = ?").param(item).update();
            jdbc.sql("DELETE FROM \"SyncAppliedOp\" WHERE \"opId\" = ?").param(committed).update();
            jdbc.sql("UPDATE \"PosDevice\" SET \"lastPushSeq\" = 1 WHERE id = ?").param(deviceId).update();
            asOwnerWithoutTriggers("DELETE FROM \"JournalLine\" WHERE \"journalEntryId\" = '" + entry + "'",
                    "DELETE FROM \"JournalEntry\" WHERE id = '" + entry + "'");
        }
        JsonNode after = ok(201, "POST", "/sync/reconciliation/run", Map.of());
        assertThat(column(check(after, "watermarks"), "id")).doesNotContain(deviceId);
        assertThat(column(check(after, "unbalancedJournals"), "id")).doesNotContain(entry);
        assertThat(column(check(after, "stock"), "productId")).doesNotContain(product);
    }

    @Test
    @DisplayName("run needs organization:update, reading needs organization:read")
    void permissions() {
        String weak = weakToken();
        assertThat(call("POST", "/sync/reconciliation/run", Map.of(), weak).status()).isEqualTo(403);
        assertThat(call("GET", "/sync/reconciliation/runs", null, weak).status()).isEqualTo(403);
    }
}
