package com.inventoryerp.costing.application;

import com.inventoryerp.costing.application.SimulationEngine.Baseline;
import com.inventoryerp.costing.application.SimulationEngine.CostContext;
import com.inventoryerp.costing.application.SimulationEngine.Result;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cost simulation (Cost Management CM-11, ADR-S042, AD-8). Versioned scenarios of drivers (material, fuel, energy,
 * FX, capacity, selling price, volume) are run against the ACTUAL layer of a profitability build (CM-9) by product
 * or customer, through the pure {@link SimulationEngine}. Results are stored in {@code SimulationResult} only and
 * shown beside the ACTUAL and STANDARD layers of the same build. This service never posts, never moves stock and never
 * writes a ledger (ArchUnit and an isolation test guard it).
 */
@Service
public class SimulationService {

    static final Set<String> PARAMETERS = Set.of("materialPricePct", "fuelPricePct", "energyTariffPct", "fxPct",
            "importedCostSharePct", "capacityPct", "sellingPricePct", "volumePct");
    static final Set<String> GROUPS = Set.of("product", "customer");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final JsonMapper json;

    public SimulationService(JdbcClient jdbc, AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Create a scenario; a scenario with the same name becomes a new version. */
    @Transactional
    public Map<String, Object> createScenario(String name, Map<String, Object> parameters, String notes) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : (parameters == null ? Map.<String, Object>of() : parameters).entrySet()) {
            if (!PARAMETERS.contains(e.getKey())) {
                throw ApiException.badRequest("parameters may only contain: " + String.join(", ", PARAMETERS.stream()
                        .sorted().toList()));
            }
            BigDecimal v;
            try {
                v = new BigDecimal(String.valueOf(e.getValue()));
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest(e.getKey() + " must be a number");
            }
            if (v.compareTo(BigDecimal.valueOf(-100)) <= 0 || v.compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw ApiException.badRequest(e.getKey() + " must be above -100 and at most 1000");
            }
            if ("importedCostSharePct".equals(e.getKey()) && (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100))
                    > 0)) {
                throw ApiException.badRequest("importedCostSharePct must be between 0 and 100");
            }
            p.put(e.getKey(), v.toPlainString());
        }
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM \"CostScenario\" WHERE \"organizationId\" = ?"
                + " AND name = ?").params(org(), name.trim()).query(Integer.class).single();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostScenario\" (id, \"organizationId\", name, version, parameters, notes, \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)").params(id, org(), name.trim(), version, json.writeValueAsString(
                p), notes, TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CostScenario", id, "create", null, Obj.of("name", name, "version", version)));
        return scenario(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> scenarios() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CostScenario\" WHERE \"organizationId\" = ? ORDER BY name, version"
                + " DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scenario(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"CostScenario\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Cost scenario not found");
        }
        return s;
    }

    /** Run a scenario against a profitability build; stores and returns the result (nothing is posted). */
    @Transactional
    public Map<String, Object> simulate(String scenarioId, String runId, String groupBy, Boolean includeUnallocated) {
        Map<String, Object> s = scenario(scenarioId);
        Map<String, Object> run = SqlRows.one(jdbc.sql("SELECT * FROM \"ProfitabilityRun\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(runId, org()));
        if (run == null) {
            throw ApiException.notFound("Profitability run not found");
        }
        String g = groupBy == null ? "product" : groupBy;
        if (!GROUPS.contains(g)) {
            throw ApiException.badRequest("groupBy must be one of the following values: product, customer");
        }
        String raw = jdbc.sql("SELECT parameters::text FROM \"CostScenario\" WHERE id = ?").param(scenarioId)
                .query(String.class).single();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = json.readValue(raw, Map.class);
        CostContext ctx = CostContext.of(params);
        List<Baseline> baseline = baseline(run, g);
        BigDecimal extra = Boolean.TRUE.equals(includeUnallocated) ? SqlRows.dec(run, "unallocated").negate()
                .max(BigDecimal.ZERO) : BigDecimal.ZERO;
        List<Result> results = SimulationEngine.simulate(baseline, ctx, extra);
        Map<String, Map<String, BigDecimal>> standard = layer(runId, "standard", g);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cm3 = BigDecimal.ZERO;
        BigDecimal baseCm3 = BigDecimal.ZERO;
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            Baseline b = baseline.get(i);
            BigDecimal actualCm3 = b.revenue().subtract(b.cogs()).subtract(b.distribution()).subtract(b.overhead());
            Map<String, BigDecimal> std = standard.getOrDefault(r.segment(), Map.of());
            rows.add(Obj.of("segment", r.segment().isEmpty() ? null : r.segment(), "simulated", Obj.of("quantity",
                    JsNumbers.dec(r.quantity()), "revenue", JsNumbers.dec(r.revenue()), "cogs", JsNumbers.dec(r.cogs()),
                    "distribution", JsNumbers.dec(r.distribution()), "overhead", JsNumbers.dec(r.overhead()), "cm1",
                    JsNumbers.dec(r.cm1()), "cm2", JsNumbers.dec(r.cm2()), "cm3", JsNumbers.dec(r.cm3()), "unitCost",
                    r.unitCost() == null ? null : JsNumbers.dec(r.unitCost()), "marginPct", r.marginPct() == null
                            ? null : JsNumbers.dec(r.marginPct()), "breakEvenQuantity", r.breakEvenQuantity() == null
                            ? null : JsNumbers.dec(r.breakEvenQuantity())),
                    "actual", Obj.of("quantity", JsNumbers.dec(b.quantity()), "revenue", JsNumbers.dec(b.revenue()),
                            "cm3", JsNumbers.dec(actualCm3)),
                    "standard", Obj.of("revenue", JsNumbers.dec(std.getOrDefault("revenue", BigDecimal.ZERO)), "cogs",
                            JsNumbers.dec(std.getOrDefault("cogs", BigDecimal.ZERO)))));
            revenue = revenue.add(r.revenue());
            cm3 = cm3.add(r.cm3());
            baseCm3 = baseCm3.add(actualCm3);
        }
        Map<String, Object> totals = Obj.of("revenue", JsNumbers.dec(revenue), "cm3", JsNumbers.dec(cm3),
                "actualCm3", JsNumbers.dec(baseCm3), "delta", JsNumbers.dec(cm3.subtract(baseCm3)));
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SimulationResult\" (id, \"organizationId\", \"scenarioId\", \"profitabilityRunId\","
                + " \"groupBy\", results, totals, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)").params(id,
                org(), scenarioId, runId, g, json.writeValueAsString(rows), json.writeValueAsString(totals),
                TenantContext.userId()).update();
        return result(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> results(String scenarioId) {
        scenario(scenarioId);
        return SqlRows.list(jdbc.sql("SELECT id, \"scenarioId\", \"profitabilityRunId\", \"groupBy\", totals,"
                + " \"createdAt\" FROM \"SimulationResult\" WHERE \"scenarioId\" = ? ORDER BY \"createdAt\" DESC")
                .param(scenarioId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> result(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"SimulationResult\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Simulation result not found");
        }
        return r;
    }

    /** ACTUAL facts of the build by segment, with invoiced quantities of the build's period (read only). */
    private List<Baseline> baseline(Map<String, Object> run, String g) {
        Map<String, Map<String, BigDecimal>> facts = layer((String) run.get("id"), "actual", g);
        LocalDate from = LocalDate.parse(run.get("periodFrom").toString());
        LocalDate to = LocalDate.parse(run.get("periodTo").toString());
        Map<String, BigDecimal> qty = new LinkedHashMap<>();
        for (Map<String, Object> q : SqlRows.list(jdbc.sql("SELECT " + ("product".equals(g) ? "l.\"productId\""
                : "d.\"partnerId\"") + " AS segment, sum(l.quantity) AS qty FROM \"Document\" d JOIN \"DocumentLine\" l"
                + " ON l.\"documentId\" = d.id WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice'"
                + " AND d.status::text NOT IN ('draft', 'cancelled') AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ? AND"
                + " l.\"productId\" IS NOT NULL GROUP BY 1").params(org(), Timestamp.from(from.atStartOfDay(
                ZoneOffset.UTC).toInstant()), Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())))) {
            qty.put(q.get("segment") == null ? "" : q.get("segment").toString(), SqlRows.dec(q, "qty"));
        }
        List<Baseline> out = new ArrayList<>();
        facts.forEach((seg, m) -> out.add(new Baseline(seg, qty.getOrDefault(seg, BigDecimal.ZERO), m.getOrDefault(
                "revenue", BigDecimal.ZERO), m.getOrDefault("cogs", BigDecimal.ZERO).add(m.getOrDefault("variances",
                BigDecimal.ZERO)), m.getOrDefault("distribution", BigDecimal.ZERO), m.getOrDefault("overhead",
                BigDecimal.ZERO))));
        return out;
    }

    private Map<String, Map<String, BigDecimal>> layer(String runId, String layer, String g) {
        String col = "product".equals(g) ? "\"productId\"" : "\"customerId\"";
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT " + col + " AS segment, measure, sum(amount) AS"
                + " amount FROM \"ProfitabilityFact\" WHERE \"runId\" = ? AND layer = ? AND NOT statistical GROUP BY 1, 2"
                + " ORDER BY 1 NULLS LAST, 2").params(runId, layer))) {
            out.computeIfAbsent(r.get("segment") == null ? "" : r.get("segment").toString(), k -> new LinkedHashMap<>())
                    .merge((String) r.get("measure"), SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        return out;
    }
}
