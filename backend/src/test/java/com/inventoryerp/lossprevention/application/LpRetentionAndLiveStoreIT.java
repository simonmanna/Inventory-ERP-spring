package com.inventoryerp.lossprevention.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Ledger retention (LP0 §8 item 3) and the shared live view store (LP1 §12, V577). DEMO keeps the default of 24
 * months, so the events written here three years back go and those one year back stay.
 */
class LpRetentionAndLiveStoreIT extends ApiTestSupport {

    @Autowired
    LpLedgerRetentionService retention;

    @Autowired
    LpEvidenceCipher cipher;

    final String source = "ret-" + UUID.randomUUID().toString().substring(0, 8);

    String branch() {
        return jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY id"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
    }

    String insertEvent(String branch, LocalDate day) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpEvent\" (id, \"organizationId\", \"branchId\", \"eventId\", source, \"sourceEventId\","
                + " type, \"schemaVersion\", \"businessDate\", \"occurredAt\", \"evidenceLevel\", \"evidenceSource\","
                + " \"observationStatus\") VALUES (?, ?, ?, ?, ?, ?, 'sale.completed', 1, ?, ?, 'order', 'pos',"
                + " 'observed')").params(id, demoOrg, branch, id, source, id, java.sql.Date.valueOf(day),
                        java.sql.Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC))).update();
        return id;
    }

    boolean exists(String id) {
        return jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE id = ?").param(id).query(Long.class).single() > 0;
    }

    void openCase(String branch, LocalDate day) {
        int seq = jdbc.sql("SELECT coalesce(max(\"caseSeq\"), 0) + 1 FROM \"LpCase\" WHERE \"organizationId\" = ?")
                .param(demoOrg).query(Integer.class).single();
        jdbc.sql("INSERT INTO \"LpCase\" (id, \"organizationId\", \"caseSeq\", \"caseNumber\", fingerprint, engine,"
                + " \"ruleCode\", \"branchId\", \"businessDate\", title, severity) VALUES (?, ?, ?, ?, ?, 'cashier', 'C01',"
                + " ?, ?, 'Retention test', 'info')").params(UUID.randomUUID().toString(), demoOrg, seq, "RET-" + seq,
                        source + "-" + seq, branch, java.sql.Date.valueOf(day)).update();
    }

    @Test
    @DisplayName("events older than retentionMonths are deleted, except days with an open case; newer ones stay")
    void retentionDeletes() {
        String branch = branch();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String old = insertEvent(branch, today.minusYears(3));
        String oldNoBranch = insertEvent(null, today.minusYears(3).minusDays(1));
        String recent = insertEvent(branch, today.minusYears(1));
        LocalDate investigated = today.minusYears(3).minusDays(10);
        String kept = insertEvent(branch, investigated);
        openCase(branch, investigated);

        Map<String, Object> done = TenantContext.run(TenantStore.organization(demoOrg), () -> retention.apply());
        assertThat(((Number) done.get("deleted")).longValue()).isGreaterThanOrEqualTo(2);
        assertThat(exists(old)).isFalse();
        assertThat(exists(oldNoBranch)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(kept)).as("a day with an open case keeps its events").isTrue();
        assertThat(LpLedgerRetentionService.cutoff(today, 0)).isNull();
        assertThat(LpLedgerRetentionService.cutoff(today, 24)).isEqualTo(today.minusMonths(24));
    }

    @Test
    @DisplayName("an event older than the ledger keeps is refused, and so is a replay before the limit")
    void refusedBeforeTheLimit() {
        String branch = branch();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusYears(3);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", source);
        e.put("sourceEventId", "late-1");
        e.put("branchId", branch);
        e.put("deviceId", "till-1");
        e.put("transactionId", "txn-late-1");
        e.put("occurredAt", day.atTime(12, 0).toInstant(ZoneOffset.UTC).toString());
        e.put("businessDate", day.toString());
        e.put("schemaVersion", 1);
        e.put("type", "sale.completed");
        e.put("payload", Map.of("total", "12.50"));
        e.put("evidenceLevel", "order");
        e.put("evidenceSource", "pos");
        e.put("observationStatus", "observed");
        JsonNode r = ok(201, "POST", "/loss-prevention/events", Map.of("events", List.of(e)));
        assertThat(r.get("rejected").asInt()).as(r.toString()).isEqualTo(1);
        assertThat(r.get("results").get(0).get("reason").asString())
                .startsWith("businessDate is older than the ledger keeps (before ");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + adminToken);
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Res replay = call("POST", "/loss-prevention/runs/replay", Map.of("engine", "cashier", "from", day.toString(),
                "to", day.plusDays(1).toString()), headers);
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(400);
        assertThat(replay.body().get("message").asString()).startsWith("The ledger keeps events from ");
    }

    static byte[] jpeg(int marker) {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[10] = (byte) marker;
        b[62] = (byte) 0xFF;
        b[63] = (byte) 0xD9;
        return b;
    }

    @Test
    @DisplayName("the database store shares a session's latest frame, encrypted, and ends it once")
    void sharedLiveStore() throws Exception {
        String agent = "agent-" + source;
        String camera = "camera-" + source;
        LpLiveStore a = new LpLiveJdbcStore(jdbc, cipher);
        LpLiveStore b = new LpLiveJdbcStore(jdbc, cipher);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        a.create(new LpLiveStore.Session(id, demoOrg, agent, camera, now.plusSeconds(300), now.plusSeconds(1800),
                false, false, null));
        assertThat(b.find(id).agentId()).isEqualTo(agent);
        assertThat(b.next(id, -1, 50)).as("no frame yet").isNull();

        assertThat(a.putFrame(id, demoOrg, agent, jpeg(1), now, Duration.ofMillis(250))).isTrue();
        // Too soon: dropped, but the agent keeps sending.
        assertThat(a.putFrame(id, demoOrg, agent, jpeg(2), now.plusMillis(100), Duration.ofMillis(250))).isTrue();
        LpLiveStore.Frame f = b.next(id, -1, 1000);
        assertThat(f.stopped()).isFalse();
        assertThat(f.jpeg()).isEqualTo(jpeg(1));
        assertThat(f.seq()).isEqualTo(1);
        byte[] stored = jdbc.sql("SELECT frame FROM \"LpLiveSession\" WHERE id = ?").param(id).query(byte[].class)
                .single();
        assertThat(stored).as("frames are encrypted at rest").isNotEqualTo(jpeg(1));
        assertThat(a.putFrame(id, demoOrg, "another-agent", jpeg(3), now.plusSeconds(1), Duration.ofMillis(250)))
                .isFalse();
        assertThat(a.putFrame(id, "another-org", agent, jpeg(3), now.plusSeconds(1), Duration.ofMillis(250)))
                .isFalse();

        b.viewer(id, now);
        assertThat(a.find(id).watched()).isTrue();
        assertThat(LpLiveService.over(a.find(id), now.plusSeconds(30))).isFalse();
        assertThat(LpLiveService.over(a.find(id), now.plusSeconds(90))).as("unwatched for a minute").isTrue();

        assertThat(a.stop(id)).isTrue();
        assertThat(b.stop(id)).as("only the first stop tells the agent").isFalse();
        assertThat(b.next(id, f.seq(), 50).stopped()).isTrue();
        assertThat(a.putFrame(id, demoOrg, agent, jpeg(4), now.plusSeconds(2), Duration.ofMillis(250))).isFalse();
        a.remove(id);
        assertThat(b.find(id)).isNull();

        a.putSnapshot(demoOrg, camera, jpeg(5), now);
        assertThat(b.snapshot(demoOrg, camera).jpeg()).isEqualTo(jpeg(5));
        assertThat(b.snapshot("another-org", camera)).isNull();
        b.pruneSnapshots(now.plusSeconds(1));
        assertThat(a.snapshot(demoOrg, camera)).isNull();
    }
}
