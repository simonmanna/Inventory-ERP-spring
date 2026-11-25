package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcGate;
import com.inventoryerp.qualitycontrol.application.QcSourceDoc;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** QC4 of the quality-control module (ADR-S087 §12): SPC on recorded measurements, summary, Pareto. */
class QualityControlStatsIT extends ApiTestSupport {

    @Autowired
    QcGate gate;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String product;
    String material;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        product = product("Block " + sfx);
        String fc = ok(201, "POST", "/quality-control/failure-classes", Map.of("code", "DIM" + sfx, "name",
                "Dimension " + sfx)).get("id").asString();
        material = ok(201, "POST", "/quality-control/failure-classes", Map.of("code", "MAT" + sfx, "name",
                "Material " + sfx)).get("id").asString();
        dimension = fc;
    }

    String dimension;

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, now())")
                .params(id, demoOrg, "QCS-" + UUID.randomUUID().toString().substring(0, 8), name).update();
        return id;
    }

    Map<String, Object> measurePoint(String productId, String subgroup, Integer size) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", "Width " + sfx + "-" + (++seq));
        p.put("operation", "manufacturing");
        p.put("checkType", "measure");
        p.put("productIds", List.of(productId));
        p.put("norm", 5);
        p.put("toleranceMin", 4);
        p.put("toleranceMax", 6);
        p.put("unit", "cm");
        p.put("decimals", 2);
        p.put("blocking", false);
        p.put("autoAlert", false);
        p.put("spcSubgroup", subgroup);
        if (size != null) {
            p.put("spcSubgroupSize", size);
        }
        return p;
    }

    /** A generated check of {@code point} on a fresh (fake) production order. */
    String check(String productId, String point) {
        String source = UUID.randomUUID().toString();
        sync(source, productId);
        return jdbc.sql("SELECT id FROM \"QcCheck\" WHERE \"sourceId\" = ? AND \"controlPointId\" = ?")
                .params(source, point).query(String.class).single();
    }

    void sync(String source, String productId) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> gate.sync(new QcSourceDoc("production_order", source, "MO-" + source
                        .substring(0, 6), "manufacturing", null, null, null, null, null,
                        List.of(new QcSourceDoc.Line("L1", productId, null, BigDecimal.TEN, null))))));
    }

    JsonNode spc(String point, String extra) {
        return ok(200, "GET", "/quality-control/statistics/spc?controlPointId=" + point + extra, null);
    }

    static double num(JsonNode n) {
        return Double.parseDouble(n.asString());
    }

    @Test
    @DisplayName("I-MR on recorded values: limits from the data, samples count as values, cancelled and pending excluded,"
            + " a spec change starts a new segment")
    void individualsChart() {
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint(product, "individual", null))
                .get("id").asString();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 29; i++) {
            double v = 5 + ((i * 7) % 11 - 5) / 20.0;
            values.add(v);
            ok(200, "POST", "/quality-control/checks/" + check(product, point) + "/execute", Map.of("measureValue", v));
        }
        // one execution with two samples: both values join the series, in sample order
        ok(200, "POST", "/quality-control/checks/" + check(product, point) + "/execute", Map.of("samples", List.of(
                Map.of("sampleRef", "S1", "measureValue", 5.1), Map.of("sampleRef", "S2", "measureValue", 4.9))));
        values.add(5.1);
        values.add(4.9);
        check(product, point); // pending: no current execution
        String cancelledSource = UUID.randomUUID().toString();
        sync(cancelledSource, product);
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> gate.cancelFor("production_order", cancelledSource, List.of(), "test")));

        JsonNode r = spc(point, "");
        JsonNode chart = r.get("chart");
        assertThat(chart.get("method").asString()).isEqualTo("I-MR");
        assertThat(chart.get("values").asInt()).isEqualTo(31);
        assertThat(chart.get("limitsType").asString()).isEqualTo("trial");
        assertThat(chart.get("provisional").asBoolean()).isFalse();
        assertThat(r.get("excluded").get("cancelled").asInt()).isEqualTo(1);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double mr = 0;
        for (int i = 1; i < values.size(); i++) {
            mr += Math.abs(values.get(i) - values.get(i - 1));
        }
        mr /= values.size() - 1;
        assertThat(num(chart.get("center"))).isCloseTo(mean, within(1e-6));
        assertThat(num(chart.get("ucl"))).isCloseTo(mean + 3 * mr / 1.128, within(1e-6));
        assertThat(num(chart.get("lcl"))).isCloseTo(mean - 3 * mr / 1.128, within(1e-6));
        assertThat(r.get("spec").get("lsl").asString()).isEqualTo("4");
        assertThat(r.get("spec").get("usl").asString()).isEqualTo("6");
        JsonNode cap = r.get("capability");
        assertThat(cap.get("valid").asBoolean()).as(cap.toString()).isTrue();
        double sigma = mr / 1.128;
        assertThat(num(cap.get("cp"))).isCloseTo(2 / (6 * sigma), within(1e-5));
        assertThat(num(cap.get("cpk"))).isCloseTo(Math.min(6 - mean, mean - 4) / (3 * sigma), within(1e-5));
        assertThat(r.get("dataset").get("checkIds")).hasSize(30);

        // a tolerance change splits the series; limits never span two specifications
        Map<String, Object> edited = measurePoint(product, "individual", null);
        edited.put("title", ok(200, "GET", "/quality-control/control-points/" + point, null).get("title").asString());
        edited.put("toleranceMax", 7);
        edited.put("reason", "new drawing");
        ok(200, "PUT", "/quality-control/control-points/" + point, edited);
        ok(200, "POST", "/quality-control/checks/" + check(product, point) + "/execute", Map.of("measureValue", 6.5));
        JsonNode latest = spc(point, "");
        assertThat(latest.get("segments")).hasSize(2);
        assertThat(latest.get("segment").asInt()).isEqualTo(1);
        assertThat(latest.get("chart").get("values").asInt()).isEqualTo(1);
        assertThat(latest.get("spec").get("usl").asString()).isEqualTo("7");
        assertThat(latest.get("capability").get("valid").asBoolean()).isFalse();
        assertThat(spc(point, "&segment=0").get("chart").get("values").asInt()).isEqualTo(31);
        assertThat(as("GET", "/quality-control/statistics/spc?controlPointId=" + point + "&segment=5", null).body()
                .get("message").asString()).isEqualTo("Segment 5 does not exist; this point has 2 specification segment(s).");

        JsonNode base = spc(point, "&segment=0&baseline=10&rules=1,2,3,4");
        assertThat(base.get("chart").get("limitsType").asString()).isEqualTo("baseline");
        assertThat(base.get("chart").get("baselineSubgroups").asInt()).isEqualTo(10);
        assertThat(base.get("chart").get("provisional").asBoolean()).isTrue();
        assertThat(base.get("rules")).hasSize(4);
    }

    @Test
    @DisplayName("consecutive subgroups of 5 give an X̄-R chart; the incomplete tail is excluded and counted")
    void consecutiveSubgroups() {
        String p = product("Kerb " + sfx);
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint(p, "consecutive", 5)).get("id")
                .asString();
        for (int i = 0; i < 12; i++) {
            ok(200, "POST", "/quality-control/checks/" + check(p, point) + "/execute", Map.of("measureValue",
                    5 + (i % 3) * 0.1));
        }
        JsonNode r = spc(point, "");
        assertThat(r.get("chart").get("method").asString()).isEqualTo("Xbar-R");
        assertThat(r.get("chart").get("subgroups").asInt()).isEqualTo(2);
        assertThat(r.get("chart").get("points").get(0).get("n").asInt()).isEqualTo(5);
        assertThat(r.get("excluded").get("incompleteSubgroup").asInt()).isEqualTo(2);
        assertThat(r.get("chart").get("provisional").asBoolean()).isTrue();
        assertThat(r.get("capability").get("reason").asString()).startsWith("At least 30 values");
    }

    @Test
    @DisplayName("summary pass rate and Pareto by failure class; SPC refuses a non-measure point; tenant and matrix")
    void summaryParetoAndSecurity() {
        String p = product("Slab " + sfx);
        String point = ok(201, "POST", "/quality-control/control-points", measurePoint(p, "individual", null)).get("id")
                .asString();
        for (int i = 0; i < 3; i++) {
            ok(200, "POST", "/quality-control/checks/" + check(p, point) + "/execute", Map.of("measureValue", 5));
        }
        for (int i = 0; i < 3; i++) {
            ok(200, "POST", "/quality-control/checks/" + check(p, point) + "/execute", Map.of("measureValue", 7,
                    "failureClassId", dimension));
        }
        ok(200, "POST", "/quality-control/checks/" + check(p, point) + "/execute", Map.of("measureValue", 3,
                "failureClassId", material));

        JsonNode s = ok(200, "GET", "/quality-control/statistics/summary?interval=day&controlPointId=" + point, null);
        assertThat(s.get("totals").get("passed").asInt()).isEqualTo(3);
        assertThat(s.get("totals").get("failed").asInt()).isEqualTo(4);
        assertThat(s.get("totals").get("passRate").asString()).isEqualTo("42.86");
        assertThat(s.get("trend")).hasSize(1);
        assertThat(s.get("byFailureClass").get(0).get("label").asString()).isEqualTo("Dimension " + sfx);
        assertThat(s.get("byControlPoint").get(0).get("failRate").asString()).isEqualTo("57.14");

        JsonNode pareto = ok(200, "GET", "/quality-control/statistics/pareto?dimension=failure_class&controlPointId="
                + point, null);
        assertThat(pareto.get("total").asInt()).isEqualTo(4);
        assertThat(pareto.get("rows").get(0).get("count").asInt()).isEqualTo(3);
        assertThat(pareto.get("rows").get(0).get("percent").asString()).isEqualTo("75.00");
        assertThat(pareto.get("rows").get(1).get("cumulativePercent").asString()).isEqualTo("100.00");

        Map<String, Object> pf = new LinkedHashMap<>(measurePoint(p, "individual", null));
        pf.put("checkType", "pass_fail");
        pf.keySet().removeAll(List.of("norm", "toleranceMin", "toleranceMax", "unit", "decimals"));
        String passFail = ok(201, "POST", "/quality-control/control-points", pf).get("id").asString();
        assertThat(as("GET", "/quality-control/statistics/spc?controlPointId=" + passFail, null).body().get("message")
                .asString()).isEqualTo("SPC needs a measure control point.");
        assertThat(as("GET", "/quality-control/statistics/spc?controlPointId=" + point + "&rules=5", null).status())
                .isEqualTo(400);

        assertThat(call("GET", "/quality-control/statistics/spc?controlPointId=" + point, null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/quality-control/statistics/summary?controlPointId=" + point, null, otherToken).body()
                .get("totals").get("failed").asInt()).isZero();
        assertThat(assertAuthorizationMatrix("com.inventoryerp.qualitycontrol")).isEqualTo(47);
    }
}
