package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.web.CostSimulationController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Cost Management CM-11 (ADR-S042): versioned scenarios, validation, a simulation against a profitability build by
 * product compared with the ACTUAL layer, deterministic results, zero writes to journals / stock / facts, tenant
 * isolation and the authorization matrix.
 */
class SimulationIsolationIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    AccountResolverService accounts;

    long rows(String table, String org) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("simulation: versions, validation, simulated vs actual, deterministic, no ledger writes")
    void simulation() {
        String code = "sim-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Sim Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        String product = UUID.randomUUID().toString();
        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            String cash = accounts.ensureByCode("1105", "Test cash", "cash", null);
            String rev = accounts.ensureByCode("4950", "Test revenue", "revenue", null);
            String cogs = accounts.ensureByCode("5050", "Test cost of sales", "cost_of_goods_sold", null);
            String ovh = accounts.ensureByCode("6950", "Test overhead", "operating_expense", null);
            Map<String, Object> dims = Map.of("productId", product);
            for (Object[] p : new Object[][] {{cash, rev, 1000}, {cogs, cash, 600}, {ovh, cash, 100}}) {
                posting.post(new PostingRequest("GEN", Instant.now(), "sim", null, null, "test", UUID.randomUUID()
                        .toString(), null, null, null, null, null, List.of(new Line((String) p[0], p[2], null, null,
                        "dr", null, null, dims), new Line((String) p[1], null, p[2], null, "cr", null, null, dims))));
            }
            return null;
        });
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String run = call("POST", "/costing/profitability/runs", Map.of("from", today, "to", today), token).body()
                .get("id").asString();

        assertThat(call("POST", "/costing/simulation/scenarios", Map.of("name", "Fuel shock", "parameters",
                Map.of("oilPct", 5)), token).status()).isEqualTo(400);
        assertThat(call("POST", "/costing/simulation/scenarios", Map.of("name", "Fuel shock", "parameters",
                Map.of("volumePct", -100)), token).status()).isEqualTo(400);
        JsonNode v1 = call("POST", "/costing/simulation/scenarios", Map.of("name", "Prices", "parameters",
                Map.of("materialPricePct", 10)), token).body();
        JsonNode v2 = call("POST", "/costing/simulation/scenarios", Map.of("name", "Prices", "parameters",
                Map.of("materialPricePct", 10, "sellingPricePct", 5)), token).body();
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v2.get("version").asInt()).isEqualTo(2);

        long journals = rows("JournalEntry", org);
        long lines = rows("JournalLine", org);
        long ledger = rows("InventoryLedger", org);
        long facts = rows("ProfitabilityFact", org);
        String scenario = v2.get("id").asString();
        Res sim = call("POST", "/costing/simulation/scenarios/" + scenario + "/simulate", Map.of(
                "profitabilityRunId", run, "groupBy", "product"), token);
        assertThat(sim.status()).as(sim.raw().body()).isEqualTo(201);
        JsonNode result = sim.body();
        JsonNode row = result.get("results").get(0);
        assertThat(row.get("segment").asString()).isEqualTo(product);
        assertThat(new BigDecimal(row.get("simulated").get("revenue").asString())).isEqualByComparingTo("1050");
        assertThat(new BigDecimal(row.get("simulated").get("cogs").asString())).isEqualByComparingTo("660");
        assertThat(new BigDecimal(row.get("simulated").get("cm3").asString())).isEqualByComparingTo("290");
        assertThat(new BigDecimal(row.get("actual").get("cm3").asString())).isEqualByComparingTo("300");
        assertThat(new BigDecimal(result.get("totals").get("delta").asString())).isEqualByComparingTo("-10");
        JsonNode again = call("POST", "/costing/simulation/scenarios/" + scenario + "/simulate", Map.of(
                "profitabilityRunId", run, "groupBy", "product"), token).body();
        assertThat(again.get("results")).as("deterministic").isEqualTo(result.get("results"));

        assertThat(rows("JournalEntry", org)).isEqualTo(journals);
        assertThat(rows("JournalLine", org)).isEqualTo(lines);
        assertThat(rows("InventoryLedger", org)).isEqualTo(ledger);
        assertThat(rows("ProfitabilityFact", org)).isEqualTo(facts);
        assertThat(call("GET", "/costing/simulation/scenarios/" + scenario + "/results", null, token).body())
                .hasSize(2);
        assertThat(call("GET", "/costing/simulation/scenarios/" + scenario, null, otherToken).status())
                .isEqualTo(404);
        assertThat(assertAuthorizationMatrix(CostSimulationController.class)).isEqualTo(6);
    }
}
