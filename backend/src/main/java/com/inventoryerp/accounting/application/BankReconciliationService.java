package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank reconciliation (port of {@code bank-reconciliation.service.ts}): statement import (idempotent on
 * {@code externalRef}), greedy exact-amount match against posted payments within a date tolerance, unmatch and
 * status.
 */
@Service
public class BankReconciliationService {

    /** One imported statement line. */
    public record StatementLine(String postedAt, String externalRef, String description, BigDecimal amount,
            String currencyCode) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;

    public BankReconciliationService(JdbcClient jdbc, AuditService audit, EventBus events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Transactional
    public Map<String, Object> importStatement(String bankAccountId, List<StatementLine> lines, String currencyCode) {
        String organizationId = TenantContext.organizationId();
        int imported = 0;
        int skipped = 0;
        for (StatementLine l : lines) {
            if (l.externalRef() != null && !l.externalRef().isEmpty()) {
                long existing = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ?"
                        + " AND \"bankAccountId\" = ? AND \"externalRef\" = ?").params(organizationId, bankAccountId, l.externalRef())
                        .query(Long.class).single();
                if (existing > 0) {
                    skipped++;
                    continue;
                }
            }
            jdbc.sql("INSERT INTO \"BankStatementLine\" (id, \"organizationId\", \"bankAccountId\", \"postedAt\", \"externalRef\","
                            + " description, amount, \"currencyCode\", status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'unmatched')")
                    .params(UUID.randomUUID().toString(), organizationId, bankAccountId,
                            FiscalPeriodControl.ts(OrgDates.parse(l.postedAt())), l.externalRef(), l.description(),
                            l.amount(), l.currencyCode() != null ? l.currencyCode() : currencyCode)
                    .update();
            imported++;
        }
        int i = imported;
        int s = skipped;
        AfterCommit.run(() -> events.publish("bank_statement.imported", body("organizationId", organizationId,
                "bankAccountId", bankAccountId, "imported", i, "skipped", s)));
        return body("imported", imported, "skipped", skipped);
    }

    @Transactional
    public Map<String, Object> match(String bankAccountId, Double dateToleranceDays, String notes) {
        String organizationId = TenantContext.organizationId();
        double tolerance = dateToleranceDays == null ? 3 : dateToleranceDays;
        String runId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"BankReconciliationRun\" (id, \"organizationId\", \"bankAccountId\", \"startedAt\", \"totalAmount\","
                        + " notes, \"createdById\") VALUES (?, ?, ?, now(), 0, ?, ?)")
                .params(runId, organizationId, bankAccountId, notes, TenantContext.userId()).update();
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT * FROM \"BankStatementLine\" WHERE \"organizationId\" = ?"
                + " AND \"bankAccountId\" = ? AND status = 'unmatched' ORDER BY \"postedAt\" ASC").params(organizationId, bankAccountId));
        int matched = 0;
        Set<String> used = new HashSet<>();
        long tolMs = (long) (tolerance * 24 * 60 * 60 * 1000);
        for (Map<String, Object> line : lines) {
            Instant at = (Instant) line.get("postedAt");
            String candidate = jdbc.sql("SELECT id FROM \"Payment\" WHERE \"organizationId\" = ? AND \"accountId\" = ? AND status = 'posted'"
                            + " AND \"paymentDate\" >= ? AND \"paymentDate\" <= ? AND amount = ? LIMIT 1")
                    .params(organizationId, bankAccountId, FiscalPeriodControl.ts(at.minus(Duration.ofMillis(tolMs))),
                            FiscalPeriodControl.ts(at.plus(Duration.ofMillis(tolMs))), SqlRows.dec(line, "amount"))
                    .query(String.class).optional().orElse(null);
            if (candidate != null && !used.contains(candidate)) {
                jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'matched', \"matchedPaymentId\" = ?, \"matchedRunId\" = ? WHERE id = ?")
                        .params(candidate, runId, line.get("id")).update();
                used.add(candidate);
                matched++;
            }
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            totalAmount = totalAmount.add(SqlRows.dec(l, "amount"));
        }
        int unmatched = lines.size() - matched;
        jdbc.sql("UPDATE \"BankReconciliationRun\" SET \"finishedAt\" = now(), matched = ?, unmatched = ?, \"totalAmount\" = ?"
                + " WHERE id = ?").params(matched, unmatched, totalAmount, runId).update();
        audit.recordInTx(AuditInput.of("BankReconciliationRun", runId, "create", null, body("matched", matched,
                "unmatched", unmatched)));
        int m = matched;
        AfterCommit.run(() -> events.publish("bank_reconciliation.ran", body("organizationId", organizationId,
                "bankAccountId", bankAccountId, "runId", runId, "matched", m, "unmatched", unmatched)));
        return body("runId", runId, "matched", matched, "unmatched", unmatched);
    }

    /** Manually unmatch a single line (Nest throws a plain Error → 500 when the line is unknown). */
    @Transactional
    public Map<String, Object> unmatch(String lineId) {
        int n = jdbc.sql("UPDATE \"BankStatementLine\" SET status = 'unmatched', \"matchedPaymentId\" = NULL, \"matchedRunId\" = NULL"
                + " WHERE id = ? AND \"organizationId\" = ?").params(lineId, TenantContext.organizationId()).update();
        if (n == 0) {
            throw new IllegalStateException("Line not found");
        }
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String bankAccountId) {
        String org = TenantContext.organizationId();
        long unmatched = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?"
                + " AND status = 'unmatched'").params(org, bankAccountId).query(Long.class).single();
        long matched = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?"
                + " AND status = 'matched'").params(org, bankAccountId).query(Long.class).single();
        long total = jdbc.sql("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ?")
                .params(org, bankAccountId).query(Long.class).single();
        Map<String, Object> latest = SqlRows.one(jdbc.sql("SELECT \"startedAt\", matched, unmatched FROM \"BankReconciliationRun\""
                + " WHERE \"organizationId\" = ? AND \"bankAccountId\" = ? ORDER BY \"startedAt\" DESC LIMIT 1").params(org, bankAccountId));
        Map<String, Object> out = body("unmatched", unmatched, "matched", matched, "total", total);
        if (latest != null) {
            out.put("latestRun", latest);
        }
        return out;
    }
}
