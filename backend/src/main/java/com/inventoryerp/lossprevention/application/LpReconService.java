package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCaseService.StoredFinding;
import com.inventoryerp.lossprevention.application.LpCommands.ReplayInput;
import com.inventoryerp.lossprevention.application.LpCommands.RunQuery;
import com.inventoryerp.lossprevention.application.LpEngine.Finding;
import com.inventoryerp.lossprevention.application.LpEngine.Input;
import com.inventoryerp.lossprevention.application.LpEngine.LedgerEvent;
import com.inventoryerp.lossprevention.application.LpEngine.RuleConfig;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation (ADR-S077 §5). A window is one engine, one branch and one business day. An accepted event makes its
 * window dirty; after the settling time the window is run. Every run is stored with what it read and the settings it
 * used, a newer run supersedes the older one, and nothing is overwritten.
 */
@Service
public class LpReconService {

    private static final Logger log = LoggerFactory.getLogger(LpReconService.class);
    static final int MAX_REPLAY_DAYS = 92;

    /** The window an accepted event belongs to. */
    public record WindowKey(String engine, String branchId, LocalDate businessDate, String type) {}

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpRuleService rules;
    private final LpSettingsService settings;
    private final LpCaseService cases;
    private final Map<String, LpEngine> engines = new LinkedHashMap<>();
    private final TransactionTemplate newTx;
    private final Clock clock = Clock.systemUTC();

    public LpReconService(JdbcClient jdbc, LpScope scope, LpRuleService rules, LpSettingsService settings,
            LpCaseService cases, List<LpEngine> engines, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.rules = rules;
        this.settings = settings;
        this.cases = cases;
        engines.forEach(e -> this.engines.put(e.code(), e));
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The engine that reads {@code type}, or {@code null}. */
    public String engineOf(String type) {
        for (LpEngine e : engines.values()) {
            if (e.eventTypes().contains(type)) {
                return e.code();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ dirty marking (called by ingest)

    /**
     * Marks the windows of newly stored events dirty, in the caller's transaction. A window that was already
     * reconciled becomes dirty again (a late event). Later windows that look back at this day become dirty too.
     */
    public void markDirty(Set<WindowKey> keys) {
        String org = LpScope.org();
        Set<String> done = new HashSet<>();
        for (WindowKey k : keys) {
            if (done.add(k.engine() + "|" + k.branchId() + "|" + k.businessDate())) {
                jdbc.sql("INSERT INTO \"LpWindow\" (id, \"organizationId\", engine, \"branchId\", \"businessDate\")"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", engine, \"branchId\","
                        + " \"businessDate\") DO UPDATE SET \"dirtyAt\" = CASE WHEN \"LpWindow\".state = 'dirty' THEN"
                        + " \"LpWindow\".\"dirtyAt\" ELSE CURRENT_TIMESTAMP END, \"pendingTrigger\" = CASE WHEN"
                        + " \"LpWindow\".state = 'dirty' THEN \"LpWindow\".\"pendingTrigger\" WHEN"
                        + " \"LpWindow\".\"latestRunId\" IS NULL THEN 'scheduled' ELSE 'late_event' END, state ="
                        + " 'dirty', \"updatedAt\" = CURRENT_TIMESTAMP")
                        .params(UUID.randomUUID().toString(), org, k.engine(), k.branchId(),
                                java.sql.Date.valueOf(k.businessDate()))
                        .update();
            }
            Integer lookback = engines.get(k.engine()).lookbackDays().get(k.type());
            if (lookback != null && done.add("fwd|" + k.engine() + "|" + k.branchId() + "|" + k.businessDate())) {
                jdbc.sql("UPDATE \"LpWindow\" SET state = 'dirty', \"dirtyAt\" = CURRENT_TIMESTAMP, \"pendingTrigger\""
                        + " = 'late_event', \"updatedAt\" = CURRENT_TIMESTAMP WHERE \"organizationId\" = ? AND engine"
                        + " = ? AND \"branchId\" = ? AND \"businessDate\" > ? AND \"businessDate\" <= ? AND state ="
                        + " 'reconciled'")
                        .params(org, k.engine(), k.branchId(), java.sql.Date.valueOf(k.businessDate()),
                                java.sql.Date.valueOf(k.businessDate().plusDays(lookback)))
                        .update();
            }
        }
    }

    // ------------------------------------------------------------------ running

    /** The last business day that is over and settled for these settings. */
    private LocalDate finalCutoff(Map<String, Integer> s) {
        return clock.instant().minus(Duration.ofMinutes(s.get(LpSettingsService.DAILY_SETTLE)))
                .atZone(BusinessZone.get()).toLocalDate().minusDays(1);
    }

    private static int settleMinutes(String engine, Map<String, Integer> s) {
        return s.get(LpRuleCatalog.BAR.equals(engine) ? LpSettingsService.BAR_SETTLE
                : LpSettingsService.CASHIER_SETTLE);
    }

    /**
     * Runs every window of the caller's organization that is due: dirty and quiet for its settling time, or reconciled
     * but not yet run on its finished day. Each window runs in its own transaction; one failing window does not stop
     * the others. Returns how many ran.
     */
    public int runDue() {
        List<Map<String, Object>> candidates = SqlRows.list(jdbc.sql("SELECT * FROM \"LpWindow\" WHERE"
                + " \"organizationId\" = ? AND (state = 'dirty' OR \"finalDone\" = false) ORDER BY \"businessDate\","
                + " engine LIMIT 500").param(LpScope.org()));
        Instant now = clock.instant();
        Map<String, Map<String, Integer>> byBranch = new HashMap<>();
        int ran = 0;
        for (Map<String, Object> w : candidates) {
            String branch = (String) w.get("branchId");
            Map<String, Integer> s = byBranch.computeIfAbsent(branch, settings::effective);
            String trigger;
            if ("dirty".equals(w.get("state"))) {
                Instant dirtyAt = (Instant) w.get("dirtyAt");
                if (dirtyAt.plus(Duration.ofMinutes(settleMinutes((String) w.get("engine"), s))).isAfter(now)) {
                    continue;
                }
                trigger = (String) w.get("pendingTrigger");
            } else if (!((LocalDate) w.get("businessDate")).isAfter(finalCutoff(s))) {
                trigger = "final";
            } else {
                continue;
            }
            String id = (String) w.get("id");
            try {
                newTx.executeWithoutResult(tx -> runWindow(id, trigger));
                ran++;
            } catch (RuntimeException err) {
                log.error("loss-prevention run failed for window {}: {}", id, err.toString());
            }
        }
        return ran;
    }

    /** Reconciles one window now, in the caller's transaction, and returns the run. */
    public Map<String, Object> runWindow(String windowId, String trigger) {
        String org = LpScope.org();
        Map<String, Object> w = SqlRows.one(jdbc.sql("SELECT * FROM \"LpWindow\" WHERE id = ? AND \"organizationId\" ="
                + " ? FOR UPDATE").params(windowId, org));
        if (w == null) {
            throw ApiException.notFound("Window not found");
        }
        LpEngine engine = engines.get((String) w.get("engine"));
        String branch = (String) w.get("branchId");
        LocalDate date = (LocalDate) w.get("businessDate");
        Map<String, Integer> s = settings.effective(branch);
        boolean dayFinal = !date.isAfter(finalCutoff(s));

        // Claim first: an event stored from here on makes the window dirty again and is picked up by the next run.
        jdbc.sql("UPDATE \"LpWindow\" SET state = 'reconciled', \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .param(windowId).update();
        long maxSeq = jdbc.sql("SELECT coalesce(max(seq), 0) FROM \"LpEvent\" WHERE \"organizationId\" = ?")
                .param(org).query(Long.class).single();
        List<LedgerEvent> events = load(engine, branch, date, maxSeq);
        Map<String, RuleConfig> config = engineRules(engine.code(), rules.effective());
        List<Finding> findings = engine.evaluate(new Input(branch, date, dayFinal, events, config));

        List<String> previous = jdbc.sql("SELECT fingerprint FROM \"LpDiscrepancy\" WHERE \"windowId\" = ? AND status ="
                + " 'active'").param(windowId).query(String.class).list();
        jdbc.sql("UPDATE \"LpDiscrepancy\" SET status = 'superseded' WHERE \"windowId\" = ? AND status = 'active'")
                .param(windowId).update();

        Map<String, Object> ruleVersions = new LinkedHashMap<>();
        Map<String, Object> ruleConfig = new LinkedHashMap<>();
        config.forEach((code, r) -> {
            ruleVersions.put(code, r.version());
            ruleConfig.put(code, Obj.of("version", r.version(), "enabled", r.enabled(), "severity", r.severity(),
                    "params", r.params()));
        });
        Map<String, StoredFinding> stored = new LinkedHashMap<>();
        for (Finding f : findings) {
            String fingerprint = fingerprint(engine.code(), f.ruleCode(), branch, date, f.key());
            stored.putIfAbsent(fingerprint, new StoredFinding(UUID.randomUUID().toString(), fingerprint,
                    engine.code(), branch, date, config.get(f.ruleCode()).severity(), f));
        }
        String runId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpReconRun\" (id, \"organizationId\", \"windowId\", engine, \"engineVersion\","
                + " \"branchId\", \"businessDate\", trigger, \"isFinal\", \"ruleVersions\", config, \"inputMaxSeq\","
                + " \"inputCount\", \"inputHash\", \"findingCount\", \"supersedesRunId\", \"createdBy\") VALUES (?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)")
                .params(java.util.Arrays.asList(runId, org, windowId, engine.code(), engine.version(), branch,
                        java.sql.Date.valueOf(date), trigger, dayFinal, scope.json(ruleVersions),
                        scope.json(Obj.of("rules", ruleConfig, "settings", s, "dayFinal", dayFinal)), maxSeq,
                        events.size(), hash(events), stored.size(), w.get("latestRunId"), TenantContext.userId()))
                .update();
        for (StoredFinding sf : stored.values()) {
            Finding f = sf.finding();
            jdbc.sql("INSERT INTO \"LpDiscrepancy\" (id, \"organizationId\", \"runId\", \"windowId\", engine,"
                    + " \"ruleCode\", \"ruleVersion\", \"branchId\", \"businessDate\", fingerprint, severity, title,"
                    + " \"subjectType\", \"subjectId\", expected, observed, delta, unit, \"exposureAmount\","
                    + " \"exposureMethod\", detail, \"evidenceEventIds\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::text[])")
                    .params(java.util.Arrays.asList(sf.discrepancyId(), org, runId, windowId, engine.code(),
                            f.ruleCode(), config.get(f.ruleCode()).version(), branch, java.sql.Date.valueOf(date),
                            sf.fingerprint(), sf.severity(), f.title(), f.subjectType(), f.subjectId(), f.expected(),
                            f.observed(), f.delta(), f.unit(), f.exposureAmount(), f.exposureMethod(),
                            scope.json(f.detail() == null ? Map.of() : f.detail()),
                            f.evidenceEventIds().toArray(String[]::new)))
                    .update();
        }
        jdbc.sql("UPDATE \"LpWindow\" SET \"latestRunId\" = ?, \"finalDone\" = \"finalDone\" OR ?, \"pendingTrigger\" ="
                + " 'scheduled', \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?").params(runId, dayFinal, windowId)
                .update();
        List<String> withdrawn = previous.stream().filter(fp -> !stored.containsKey(fp)).toList();
        cases.sync(runId, new ArrayList<>(stored.values()), withdrawn);
        return Obj.of("runId", runId, "windowId", windowId, "engine", engine.code(), "branchId", branch,
                "businessDate", date, "trigger", trigger, "isFinal", dayFinal, "findingCount", stored.size(),
                "withdrawn", withdrawn.size());
    }

    private static Map<String, RuleConfig> engineRules(String engine, Map<String, RuleConfig> all) {
        Map<String, RuleConfig> out = new LinkedHashMap<>();
        all.forEach((code, r) -> {
            if (engine.equals(LpRuleCatalog.definition(code).engine())) {
                out.put(code, r);
            }
        });
        return out;
    }

    /** The window's events and the engine's look-back, up to a ledger position, in ledger order. */
    private List<LedgerEvent> load(LpEngine engine, String branch, LocalDate date, long maxSeq) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, seq, type, \"actorId\", \"transactionId\", \"orderId\","
                + " \"ticketId\", \"lineId\", \"sessionId\", \"businessDate\", \"occurredAt\", \"evidenceLevel\","
                + " \"observationStatus\", payload FROM \"LpEvent\" WHERE \"organizationId\" = ? AND"
                + " coalesce(\"branchId\", '') = ? AND seq <= ? AND ((\"businessDate\" = ? AND type = ANY(?::text[]))");
        params.add(LpScope.org());
        params.add(branch);
        params.add(maxSeq);
        params.add(java.sql.Date.valueOf(date));
        params.add(engine.eventTypes().toArray(String[]::new));
        engine.lookbackDays().forEach((type, days) -> {
            sql.append(" OR (type = ? AND \"businessDate\" >= ? AND \"businessDate\" < ?)");
            params.add(type);
            params.add(java.sql.Date.valueOf(date.minusDays(days)));
            params.add(java.sql.Date.valueOf(date));
        });
        sql.append(") ORDER BY seq");
        List<LedgerEvent> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql.toString()).params(params))) {
            out.add(event(r));
        }
        return out;
    }

    private static LedgerEvent event(Map<String, Object> r) {
        Map<String, Object> payload = LpJson.map(r.get("payload"));
        return new LedgerEvent((String) r.get("id"), ((Number) r.get("seq")).longValue(), (String) r.get("type"),
                (String) r.get("actorId"), (String) r.get("transactionId"), (String) r.get("orderId"),
                (String) r.get("ticketId"), (String) r.get("lineId"), (String) r.get("sessionId"),
                (LocalDate) r.get("businessDate"), (Instant) r.get("occurredAt"), (String) r.get("evidenceLevel"),
                (String) r.get("observationStatus"), payload);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(List<LedgerEvent> events) {
        StringBuilder ids = new StringBuilder();
        events.forEach(e -> ids.append(e.id()).append('\n'));
        return sha(ids.toString());
    }

    static String fingerprint(String engine, String rule, String branch, LocalDate date, String key) {
        return sha(engine + "|" + rule + "|" + branch + "|" + date + "|" + key).substring(0, 40);
    }

    // ------------------------------------------------------------------ replay

    private static LocalDate day(String v, String field) {
        LocalDate d = v != null && v.length() == 10 ? LpEventValidator.date(v) : null;
        if (d == null) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD)");
        }
        return d;
    }

    /**
     * Reconciles again every window of an engine in a date range, with the rules as they are now. The earlier runs
     * and their findings stay; compare a new run with the one it supersedes to see what changed.
     */
    @Transactional
    public Map<String, Object> replay(ReplayInput dto) {
        if (!engines.containsKey(dto.engine())) {
            throw ApiException.badRequest("engine must be one of: " + String.join(", ", engines.keySet()));
        }
        LocalDate from = day(dto.from(), "from");
        LocalDate to = day(dto.to(), "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (from.plusDays(MAX_REPLAY_DAYS).isBefore(to)) {
            throw ApiException.badRequest("A replay covers at most " + MAX_REPLAY_DAYS + " days");
        }
        scope.assertBranch(dto.branchId());
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("", params))
                .append(" AND engine = ? AND \"businessDate\" >= ? AND \"businessDate\" <= ?");
        params.add(dto.engine());
        params.add(java.sql.Date.valueOf(from));
        params.add(java.sql.Date.valueOf(to));
        if (LpScope.has(dto.branchId())) {
            w.append(" AND \"branchId\" = ?");
            params.add(dto.branchId());
        }
        List<String> windows = jdbc.sql("SELECT id FROM \"LpWindow\"" + w + " ORDER BY \"businessDate\", \"branchId\"")
                .params(params).query(String.class).list();
        List<Map<String, Object>> runs = new ArrayList<>();
        int findings = 0;
        for (String id : windows) {
            Map<String, Object> run = runWindow(id, "replay");
            findings += (Integer) run.get("findingCount");
            runs.add(run);
        }
        return Obj.of("engine", dto.engine(), "from", from, "to", to, "windows", windows.size(), "findings",
                findings, "runs", runs);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(RunQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        scope.assertBranch(q.branchId());
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(scope.where("", params));
        String[][] equals = {{"engine", q.engine()}, {"branchId", q.branchId()}, {"windowId", q.windowId()}};
        for (String[] f : equals) {
            if (LpScope.has(f[1])) {
                w.append(" AND \"").append(f[0]).append("\" = ?");
                params.add(f[1]);
            }
        }
        if (LpScope.has(q.from())) {
            w.append(" AND \"businessDate\" >= ?");
            params.add(java.sql.Date.valueOf(day(q.from(), "from")));
        }
        if (LpScope.has(q.to())) {
            w.append(" AND \"businessDate\" <= ?");
            params.add(java.sql.Date.valueOf(day(q.to(), "to")));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"LpReconRun\"" + w).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql("SELECT * FROM \"LpReconRun\"" + w + " ORDER BY \"createdAt\" DESC,"
                + " id LIMIT ? OFFSET ?").params(pageParams)), page, size, total);
    }

    private Map<String, Object> run(String id) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("", params) + " AND id = ?";
        params.add(id);
        Map<String, Object> run = SqlRows.one(jdbc.sql("SELECT * FROM \"LpReconRun\"" + w).params(params));
        if (run == null) {
            throw ApiException.notFound("Run not found");
        }
        return run;
    }

    /** Findings of a run, without those whose case is about the caller. */
    private List<Map<String, Object>> findings(String runId) {
        return SqlRows.list(jdbc.sql("SELECT d.*, c.id AS \"caseId\", c.\"caseNumber\", c.state AS \"caseState\","
                + " c.outcome AS \"caseOutcome\" FROM \"LpDiscrepancy\" d LEFT JOIN \"LpCase\" c ON"
                + " c.\"organizationId\" = d.\"organizationId\" AND c.fingerprint = d.fingerprint WHERE d.\"runId\" = ?"
                + " AND c.\"subjectUserId\" IS DISTINCT FROM ? ORDER BY d.\"ruleCode\", d.id")
                .params(java.util.Arrays.asList(runId, TenantContext.userId())));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = new LinkedHashMap<>(run(id));
        out.put("findings", findings(id));
        return out;
    }

    /** What changed between two runs of one window: findings added, removed and changed, each with its case. */
    @Transactional(readOnly = true)
    public Map<String, Object> compare(String fromId, String toId) {
        Map<String, Object> from = run(fromId);
        Map<String, Object> to = run(toId);
        if (!from.get("windowId").equals(to.get("windowId"))) {
            throw ApiException.badRequest("Runs of different windows cannot be compared");
        }
        Map<String, Map<String, Object>> before = byFingerprint(findings(fromId));
        Map<String, Map<String, Object>> after = byFingerprint(findings(toId));
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        int unchanged = 0;
        for (Map.Entry<String, Map<String, Object>> e : after.entrySet()) {
            Map<String, Object> old = before.get(e.getKey());
            if (old == null) {
                added.add(e.getValue());
            } else if (differs(old, e.getValue())) {
                changed.add(Obj.of("fingerprint", e.getKey(), "before", old, "after", e.getValue()));
            } else {
                unchanged++;
            }
        }
        before.forEach((fp, f) -> {
            if (!after.containsKey(fp)) {
                removed.add(f);
            }
        });
        return Obj.of("from", from, "to", to, "added", added, "removed", removed, "changed", changed, "unchanged",
                unchanged, "configChanged", !String.valueOf(from.get("config")).equals(String.valueOf(to.get("config"))),
                "inputChanged", !from.get("inputHash").equals(to.get("inputHash")));
    }

    private static Map<String, Map<String, Object>> byFingerprint(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put((String) r.get("fingerprint"), r));
        return out;
    }

    private static boolean differs(Map<String, Object> a, Map<String, Object> b) {
        for (String k : new String[] {"expected", "observed", "delta", "exposureAmount"}) {
            BigDecimal x = LpNumbers.decimal(a.get(k));
            BigDecimal y = LpNumbers.decimal(b.get(k));
            if ((x == null) != (y == null) || (x != null && x.compareTo(y) != 0)) {
                return true;
            }
        }
        return !String.valueOf(a.get("severity")).equals(String.valueOf(b.get("severity")));
    }

    /**
     * Repeats a stored run from what it recorded (its ledger position and its rule settings) and says whether the
     * findings come out the same. Stores nothing.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String id) {
        Map<String, Object> run = run(id);
        LpEngine engine = engines.get((String) run.get("engine"));
        String branch = (String) run.get("branchId");
        LocalDate date = (LocalDate) run.get("businessDate");
        List<LedgerEvent> events = load(engine, branch, date, ((Number) run.get("inputMaxSeq")).longValue());
        Map<String, Object> config = LpJson.map(run.get("config"));
        Map<String, RuleConfig> ruleConfig = new LinkedHashMap<>();
        ((Map<String, Object>) config.get("rules")).forEach((code, v) -> {
            Map<String, Object> r = (Map<String, Object>) v;
            ruleConfig.put(code, new RuleConfig(code, ((Number) r.get("version")).intValue(),
                    (Boolean) r.get("enabled"), (String) r.get("severity"), (Map<String, Object>) r.get("params")));
        });
        List<Finding> findings = engine.evaluate(new Input(branch, date, Boolean.TRUE.equals(config.get("dayFinal")),
                events, ruleConfig));
        Set<String> reproduced = new HashSet<>();
        findings.forEach(f -> reproduced.add(fingerprint(engine.code(), f.ruleCode(), branch, date, f.key())
                + "|" + (f.delta() == null ? "" : f.delta().stripTrailingZeros().toPlainString())));
        Set<String> stored = new HashSet<>();
        for (Map<String, Object> d : SqlRows.list(jdbc.sql("SELECT fingerprint, delta FROM \"LpDiscrepancy\" WHERE"
                + " \"runId\" = ?").param(id))) {
            BigDecimal delta = LpNumbers.decimal(d.get("delta"));
            stored.add(d.get("fingerprint") + "|" + (delta == null ? "" : delta.stripTrailingZeros().toPlainString()));
        }
        boolean sameInput = hash(events).equals(run.get("inputHash"));
        return Obj.of("runId", id, "identical", sameInput && reproduced.equals(stored), "inputUnchanged", sameInput,
                "stored", stored.size(), "reproduced", reproduced.size());
    }
}
