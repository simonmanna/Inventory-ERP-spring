package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.reporting.spi.SubledgerReconciliation;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-S083 C2: the post-restore reconciliation. After a database restore, devices and branches re-push what they did
 * not see acknowledged and dedup decides apply or replay; this report shows what still needs a person:
 * <ol>
 *   <li>{@code watermarks}: devices and branch nodes whose acknowledged sequence ({@code lastPushSeq}) is ahead of
 *       everything the ledger and dead letters hold for them — acknowledged ops the restore lost and that have not
 *       come back yet;</li>
 *   <li>{@code committedOps}: ledger rows still {@code committed} (business rows committed, result never recorded);</li>
 *   <li>{@code unbalancedJournals}: posted journal entries whose base debits and credits differ;</li>
 *   <li>{@code receivables} / {@code payables}: the AR / AP subledger against its control accounts (reporting engine);</li>
 *   <li>{@code stock}: cached on-hand ({@code StockItem}) against the sum of stock ledger movements;</li>
 *   <li>{@code deadLetters}: open dead letters by failure code (information, not an issue by itself).</li>
 * </ol>
 * Read-only: it never changes business data. Runs keep their report; a run whose issues differ from the previous
 * run's alerts the organisation's administrators in the app.
 */
@Service
public class SyncReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SyncReconciliationService.class);
    static final int ROWS = 50;
    static final BigDecimal GL_TOLERANCE = new BigDecimal("0.005");
    static final BigDecimal STOCK_TOLERANCE = new BigDecimal("0.000001");

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final SubledgerReconciliation subledger;
    private final NotificationsService notifications;
    private final TransactionTemplate tx;

    public SyncReconciliationService(JdbcClient jdbc, JsonMapper mapper, SubledgerReconciliation subledger,
            NotificationsService notifications, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.subledger = subledger;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Runs for the caller's organisation ({@code POST /sync/reconciliation/run}). */
    public Map<String, Object> runForCurrent() {
        return run(TenantContext.organizationId(), "manual", TenantContext.userId());
    }

    /** Runs the reconciliation for every organisation (start-up and nightly job). */
    public void runAll(String trigger) {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" ORDER BY id").query(String.class).list();
        for (String org : orgs) {
            try {
                run(org, trigger, null);
            } catch (RuntimeException e) {
                log.warn("Sync reconciliation for {} failed: {}", org, e.toString());
            }
        }
    }

    /** One run for {@code org}: every check, stored, with an alert when its issues changed. */
    public Map<String, Object> run(String org, String trigger, String userId) {
        String id = UUID.randomUUID().toString();
        Instant started = Instant.now();
        List<Map<String, Object>> checks = new ArrayList<>();
        String status;
        String failure = null;
        try {
            TenantContext.run(TenantStore.organization(org), () -> tx.executeWithoutResult(s -> {
                checks.add(watermarks(org));
                checks.add(committedOps(org));
                checks.add(unbalancedJournals(org));
                checks.add(stock(org));
                checks.add(deadLetters(org));
            }));
            checks.add(4, subledgerCheck(org, "AR", "receivables", "Receivables subledger vs control accounts"));
            checks.add(5, subledgerCheck(org, "AP", "payables", "Payables subledger vs control accounts"));
            status = checks.stream().anyMatch(c -> Boolean.FALSE.equals(c.get("ok"))) ? "issues" : "clean";
        } catch (RuntimeException e) {
            status = "failed";
            failure = e.toString();
            log.warn("Sync reconciliation {} for {} failed", id, org, e);
        }
        int issues = 0;
        List<String> sig = new ArrayList<>();
        for (Map<String, Object> c : checks) {
            if (Boolean.FALSE.equals(c.get("ok"))) {
                issues += ((Number) c.get("count")).intValue();
                sig.add(c.get("key") + "=" + c.get("count"));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id);
        report.put("organizationId", org);
        report.put("trigger", trigger);
        report.put("status", status);
        report.put("issueCount", issues);
        report.put("startedAt", JsonConfig.toJsIso(started));
        report.put("finishedAt", JsonConfig.toJsIso(Instant.now()));
        report.put("checks", checks);
        if (failure != null) {
            report.put("error", failure);
        }
        String signature = status + ":" + String.join(",", sig);
        String finalStatus = status;
        int finalIssues = issues;
        String previous = TenantContext.run(TenantStore.organization(org), () -> tx.execute(s -> {
            String prev = jdbc.sql("SELECT signature FROM \"SyncReconciliationRun\" WHERE \"organizationId\" = ? ORDER BY"
                    + " \"startedAt\" DESC LIMIT 1").param(org).query(String.class).optional().orElse(null);
            jdbc.sql("INSERT INTO \"SyncReconciliationRun\" (id, \"organizationId\", trigger, status, \"issueCount\","
                    + " signature, report, \"createdById\", \"startedAt\", \"finishedAt\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb,"
                    + " ?, ?, now())").params(id, org, trigger, finalStatus, finalIssues, hash(signature),
                            EntityJson.toJson(report), userId, java.sql.Timestamp.from(started)).update();
            return prev;
        }));
        if (!"clean".equals(status) && !hash(signature).equals(previous)) {
            alert(org, id, status, issues);
        }
        return report;
    }

    private void alert(String org, String runId, String status, int issues) {
        String title = "failed".equals(status) ? "Sync reconciliation could not run"
                : "Sync reconciliation found " + issues + " issue(s)";
        List<String> admins = TenantContext.run(TenantStore.organization(org), () -> tx.execute(s -> jdbc.sql(
                "SELECT DISTINCT u.id FROM \"User\" u JOIN \"_UserRoles\" ur ON ur.\"B\" = u.id JOIN \"Role\" r ON r.id ="
                + " ur.\"A\" WHERE u.\"organizationId\" = ? AND u.\"isActive\" AND u.\"deletedAt\" IS NULL AND ? ="
                + " ANY(r.permissions) ORDER BY u.id LIMIT 20").params(org, Permissions.ORGANIZATION__UPDATE)
                .query(String.class).list()));
        for (String user : admins) {
            try {
                TenantContext.run(TenantStore.organization(org), () -> notifications.send(new SendInput(org, user,
                        "in_app", "sync", title, "Open Settings → Sync reconciliation for the details (ADR-S083 C2).",
                        Map.of("entityType", "sync_reconciliation", "entityId", runId))));
            } catch (RuntimeException e) {
                log.warn("Sync reconciliation alert not sent: {}", e.getMessage());
            }
        }
    }

    /** Recent runs, newest first, without their reports. */
    public List<Map<String, Object>> runs() {
        return TenantContext.run(TenantStore.organization(TenantContext.organizationId()), () -> tx.execute(s ->
                SqlRows.list(jdbc.sql("SELECT id, trigger, status, \"issueCount\", " + iso("\"startedAt\"")
                        + " AS \"startedAt\", " + iso("\"finishedAt\"") + " AS \"finishedAt\" FROM \"SyncReconciliationRun\""
                        + " WHERE \"organizationId\" = ? ORDER BY \"startedAt\" DESC LIMIT 30")
                        .param(TenantContext.organizationId()))));
    }

    /** One run's report. */
    public Map<String, Object> report(String id) {
        String text = jdbc.sql("SELECT report::text FROM \"SyncReconciliationRun\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, TenantContext.organizationId()).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Reconciliation run not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) mapper.readerFor(Map.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
        return m;
    }

    // ---------------------------------------------------------------- checks

    private static Map<String, Object> check(String key, String label, List<Map<String, Object>> rows, long count,
            Boolean ok, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("label", label);
        c.put("ok", ok);
        c.put("count", count);
        c.put("detail", detail);
        c.put("rows", rows.size() > ROWS ? rows.subList(0, ROWS) : rows);
        c.put("truncated", count > ROWS);
        return c;
    }

    private Map<String, Object> watermarks(String org) {
        // Devices that never wrote a ledger row (pre-O0 pushes only) are not compared: their history predates it.
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id, d.name, d.platform, d.\"branchId\","
                + " d.\"lastPushSeq\", m.seq AS \"ledgerSeq\" FROM \"PosDevice\" d JOIN LATERAL (SELECT GREATEST("
                + "(SELECT max(a.\"deviceSeq\") FROM \"SyncAppliedOp\" a WHERE a.\"organizationId\" = d.\"organizationId\""
                + " AND a.\"deviceId\" = d.id), (SELECT max(l.\"deviceSeq\") FROM \"SyncOpDeadLetter\" l WHERE"
                + " l.\"organizationId\" = d.\"organizationId\" AND l.\"deviceId\" = d.id)) AS seq) m ON true WHERE"
                + " d.\"organizationId\" = ? AND m.seq IS NOT NULL AND d.\"lastPushSeq\" > m.seq ORDER BY d.name")
                .param(org));
        return check("watermarks", "Devices and branch nodes ahead of the ledger (acknowledged ops lost by a restore)",
                rows, rows.size(), rows.isEmpty(), rows.isEmpty() ? "Every device's acknowledged sequence is in the ledger"
                        : rows.size() + " device(s) acknowledged ops the ledger no longer has; they come back when the"
                                + " device re-pushes — check it is online and syncing");
    }

    private Map<String, Object> committedOps(String org) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT \"opId\", \"opType\", \"deviceId\", \"deviceSeq\","
                + " " + iso("\"committedAt\"") + " AS \"committedAt\" FROM \"SyncAppliedOp\" WHERE \"organizationId\" = ?"
                + " AND state = 'committed' AND \"committedAt\" < now() - interval '5 minutes' ORDER BY \"committedAt\""
                + " LIMIT " + (ROWS + 1)).param(org));
        long count = jdbc.sql("SELECT count(*) FROM \"SyncAppliedOp\" WHERE \"organizationId\" = ? AND state ="
                + " 'committed' AND \"committedAt\" < now() - interval '5 minutes'").param(org).query(Long.class).single();
        return check("committedOps", "Ledger rows committed without a recorded result", rows, count, count == 0,
                count == 0 ? "None" : count + " op(s) committed on the server without a recorded result; verify their"
                        + " business rows (OPERATION_INCOMPLETE dead letters)");
    }

    private Map<String, Object> unbalancedJournals(String org) {
        String body = " FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id WHERE"
                + " e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') GROUP BY e.id, e.\"entryNumber\","
                + " e.\"postingDate\", e.\"sourceType\" HAVING abs(sum(l.\"baseDebit\") - sum(l.\"baseCredit\")) > ?";
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.\"entryNumber\", " + iso("e.\"postingDate\"")
                + " AS \"postingDate\", e.\"sourceType\", sum(l.\"baseDebit\") AS debit, sum(l.\"baseCredit\") AS credit"
                + body + " ORDER BY e.\"postingDate\" LIMIT " + (ROWS + 1)).params(org, GL_TOLERANCE));
        long count = jdbc.sql("SELECT count(*) FROM (SELECT e.id" + body + ") x").params(org, GL_TOLERANCE)
                .query(Long.class).single();
        for (Map<String, Object> r : rows) {
            BigDecimal d = dec(r.get("debit"));
            BigDecimal c = dec(r.get("credit"));
            r.put("debit", d.toPlainString());
            r.put("credit", c.toPlainString());
            r.put("difference", d.subtract(c).toPlainString());
        }
        return check("unbalancedJournals", "Posted journal entries whose debits and credits differ", rows, count,
                count == 0, count == 0 ? "Every posted journal balances" : count + " posted journal(s) do not balance");
    }

    private Map<String, Object> subledgerCheck(String org, String side, String key, String label) {
        try {
            SubledgerReconciliation.Result r = subledger.reconcile(org, side);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subledger", r.subledger().toPlainString());
            row.put("gl", r.gl().toPlainString());
            row.put("difference", r.subledger().subtract(r.gl()).toPlainString());
            return check(key, label, List.of(row), r.reconciles() ? 0 : 1, r.reconciles(), r.detail());
        } catch (RuntimeException e) {
            log.warn("{} reconciliation for {} could not run: {}", side, org, e.toString());
            return check(key, label, List.of(), 1, false, "Could not run: " + e.getMessage());
        }
    }

    private Map<String, Object> stock(String org) {
        String cte = "WITH si AS (SELECT \"productId\" AS pid, \"variantKey\" AS vk, \"locationId\" AS lid, sum(quantity) AS"
                + " cached FROM \"StockItem\" WHERE \"organizationId\" = ? GROUP BY 1, 2, 3), lg AS (SELECT \"productId\" AS"
                + " pid, COALESCE(\"variantId\", '') AS vk, \"locationId\" AS lid, sum(\"quantityChange\") AS ledger FROM"
                + " \"InventoryLedger\" WHERE \"organizationId\" = ? GROUP BY 1, 2, 3), d AS (SELECT COALESCE(si.pid, lg.pid)"
                + " AS pid, COALESCE(si.vk, lg.vk) AS vk, COALESCE(si.lid, lg.lid) AS lid, COALESCE(si.cached, 0) AS cached,"
                + " COALESCE(lg.ledger, 0) AS ledger FROM si FULL JOIN lg ON lg.pid = si.pid AND lg.vk = si.vk AND lg.lid ="
                + " si.lid) ";
        String drift = " WHERE abs(d.cached - d.ledger) > ?";
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(cte + "SELECT d.pid AS \"productId\", d.vk AS"
                + " \"variantKey\", d.lid AS \"locationId\", p.code, p.name, d.cached, d.ledger FROM d LEFT JOIN"
                + " \"Product\" p ON p.id = d.pid" + drift + " ORDER BY p.code, d.lid LIMIT " + (ROWS + 1))
                .params(org, org, STOCK_TOLERANCE));
        long count = jdbc.sql(cte + "SELECT count(*) FROM d" + drift).params(org, org, STOCK_TOLERANCE).query(Long.class)
                .single();
        for (Map<String, Object> r : rows) {
            BigDecimal c = dec(r.get("cached"));
            BigDecimal l = dec(r.get("ledger"));
            r.put("cached", c.stripTrailingZeros().toPlainString());
            r.put("ledger", l.stripTrailingZeros().toPlainString());
            r.put("drift", c.subtract(l).stripTrailingZeros().toPlainString());
        }
        return check("stock", "On-hand (StockItem) vs the sum of stock movements", rows, count, count == 0,
                count == 0 ? "Every stock cell matches its movements" : count + " stock cell(s) differ from their movements");
    }

    private Map<String, Object> deadLetters(String org) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT coalesce(\"failureCode\", 'UNKNOWN') AS"
                + " \"failureCode\", count(*) AS count FROM \"SyncOpDeadLetter\" WHERE \"organizationId\" = ? AND status"
                + " IN ('open', 'investigating') GROUP BY 1 ORDER BY 2 DESC, 1").param(org));
        long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        return check("deadLetters", "Open dead letters by failure code", rows, total, null,
                total == 0 ? "None open" : total + " open; work them under Sync dead letters");
    }

    // ---------------------------------------------------------------- helpers

    private static String iso(String col) {
        return SyncRelayFeedService.ISO.formatted(col);
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
