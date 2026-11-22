package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.sync.application.SyncLedger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-S083 C1: the applied-op ledger row commits with the op's first business commit and only with it (owner
 * decision 2026-11-20: anchored to the first business commit, not one transaction per op).
 */
class SyncLedgerIT extends ApiTestSupport {

    @Autowired
    SyncLedger ledger;

    SyncLedger.Context ctx(String opId) {
        return new SyncLedger.Context(demoOrg, opId, "hash-" + opId, "test.op", null, 1, List.of("device:t"), null);
    }

    void writeSetting(String key) {
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", \"scopeType\", \"scopeId\", key, value, \"updatedAt\")"
                + " VALUES (?, ?, 'organization', ?, ?, '1'::jsonb, now())").params(UUID.randomUUID().toString(), demoOrg,
                demoOrg, key).update();
    }

    long settings(String key) {
        return jdbc.sql("SELECT count(*) FROM \"Setting\" WHERE key = ?").param(key).query(Long.class).single();
    }

    <T> T asDemo(java.util.function.Supplier<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, null), body);
    }

    @Test
    @DisplayName("a committed business transaction carries the ledger row (state committed), then complete() fills the result")
    void anchorsOnCommit() {
        String opId = "ledger-" + UUID.randomUUID();
        String key = "o0.probe." + opId;
        SyncLedger.Context c = ctx(opId);
        asDemo(() -> ledger.run(c, () -> new TransactionTemplate(txm).execute(s -> {
            writeSetting(key);
            return null;
        })));
        assertThat(c.anchored()).isTrue();
        assertThat(settings(key)).isEqualTo(1);
        SyncLedger.Entry e = ledger.find(demoOrg, opId);
        assertThat(e.state()).isEqualTo("committed");
        assertThat(e.payloadHash()).isEqualTo("hash-" + opId);
        ledger.complete(c, 201, Map.of("id", "x"));
        SyncLedger.Entry done = ledger.find(demoOrg, opId);
        assertThat(done.applied()).isTrue();
        assertThat(done.result().get("id").asString()).isEqualTo("x");
    }

    @Test
    @DisplayName("a rollback mid-op leaves neither the business row nor a ledger row")
    void rollbackLeavesNothing() {
        String opId = "ledger-" + UUID.randomUUID();
        String key = "o0.probe." + opId;
        SyncLedger.Context c = ctx(opId);
        assertThatThrownBy(() -> asDemo(() -> ledger.run(c, () -> new TransactionTemplate(txm).execute(s -> {
            writeSetting(key);
            throw new IllegalStateException("boom after the business write");
        })))).hasMessageContaining("boom");
        assertThat(c.anchored()).isFalse();
        assertThat(settings(key)).isZero();
        assertThat(ledger.find(demoOrg, opId)).isNull();
    }

    @Test
    @DisplayName("read-only and retry-safe transactions do not anchor; only the first business commit does")
    void onlyBusinessCommitsAnchor() {
        String opId = "ledger-" + UUID.randomUUID();
        SyncLedger.Context c = ctx(opId);
        TransactionTemplate readOnly = new TransactionTemplate(txm);
        readOnly.setReadOnly(true);
        TransactionTemplate retrySafe = new TransactionTemplate(txm);
        retrySafe.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        retrySafe.setName(BusinessOutcome.RETRY_SAFE_TX);
        asDemo(() -> ledger.run(c, () -> {
            readOnly.execute(s -> jdbc.sql("SELECT 1").query(Integer.class).single());
            retrySafe.execute(s -> {
                writeSetting("o0.retrysafe." + opId);
                return null;
            });
            return null;
        }));
        assertThat(c.anchored()).isFalse();
        assertThat(ledger.find(demoOrg, opId)).isNull();
    }

    @Test
    @DisplayName("(organizationId, opId) is unique; complete() never overwrites a row with a different payload hash")
    void uniquePerOrgAndOp() {
        String opId = "ledger-" + UUID.randomUUID();
        ledger.complete(ctx(opId), 201, Map.of("id", "first"));
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO \"SyncAppliedOp\" (id, \"organizationId\", \"opId\", \"payloadHash\","
                + " \"opType\", \"serverId\", state) VALUES (?, ?, ?, 'h', 't', 'hq', 'applied')")
                .params(UUID.randomUUID().toString(), demoOrg, opId).update()).isInstanceOf(DuplicateKeyException.class);
        SyncLedger.Context other = new SyncLedger.Context(demoOrg, opId, "different", "test.op", null, 1, List.of(), null);
        ledger.complete(other, 500, Map.of("id", "second"));
        assertThat(ledger.find(demoOrg, opId).result().get("id").asString()).isEqualTo("first");
    }
}
