package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.web.ProfitabilityController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-9 (ADR-S040): on clean books, ACTUAL segments + unallocated + out of scope = GL net income
 * exactly; contribution margins CM1 / CM2 / CM3 by customer and product; STANDARD from invoiced quantities; ESTIMATE
 * from approved project budgets; layers never mixed in one response; rebuild supersedes; tenant isolation and the
 * authorization matrix.
 */
@ExtendWith(BooksGuard.class)
class ProfitabilityReconciliationIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    AccountResolverService accounts;

    String org;
    String token;
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String customer = UUID.randomUUID().toString();
    String product = UUID.randomUUID().toString();
    String project = UUID.randomUUID().toString();

    @BeforeAll
    void books() {
        String code = "pf-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Profit Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        org = boot.body().get("organization").get("id").asString();
        token = login(code, "owner@" + code + ".test", "Owner@12345");
        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            String cash = accounts.ensureByCode("1105", "Test cash", "cash", null);
            String rev = accounts.ensureByCode("4950", "Test revenue", "revenue", null);
            String cogs = accounts.ensureByCode("5050", "Test cost of sales", "cost_of_goods_sold", null);
            String variance = accounts.ensureByCode("5955", "Test variance", "cost_of_goods_sold", null);
            String dist = accounts.ensureByCode("6460", "Outbound Freight & Transport", "operating_expense", null);
            String ovh = accounts.ensureByCode("6950", "Test overhead", "operating_expense", null);
            String oos = accounts.ensureByCode("7950", "Test finance cost", "other_expense", null);
            Map<String, Object> cp = Map.of("customerId", customer, "productId", product);
            post(cash, rev, 1000, cp);
            post(cogs, cash, 600, cp);
            post(variance, cash, 50, Map.of("productId", product));
            post(dist, cash, 30, Map.of("customerId", customer));
            post(ovh, cash, 100, Map.of("projectId", project));
            post(ovh, cash, 70, null);
            post(oos, cash, 20, null);
            return null;
        });
    }

    void post(String debit, String credit, int amount, Map<String, Object> dims) {
        posting.post(new PostingRequest("GEN", Instant.now(), "pf test", null, null, "test", UUID.randomUUID()
                .toString(), null, null, null, null, null, List.of(new Line(debit, amount, null, null, "dr", null,
                null, dims), new Line(credit, null, amount, null, "cr", null, null, dims))));
    }

    Map<String, String> auth() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        return h;
    }

    JsonNode get(String path) {
        Res r = call("GET", path, null, auth());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static JsonNode row(JsonNode facts, String segment) {
        for (JsonNode r : facts.get("rows")) {
            if (segment == null ? r.get("segment").isNull() : segment.equals(r.get("segment").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + segment);
    }

    @Test
    @DisplayName("actual reconciles to the GL exactly; margins; estimate layer; layers never mixed; rebuild")
    void reconciliation() {
        // an approved project budget feeds the ESTIMATE layer
        String pid = call("POST", "/projects", Map.of("code", "P1", "name", "Estimate"), auth()).body().get("id")
                .asString();
        String w = call("POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "All"), auth()).body()
                .get("id").asString();
        String b = call("POST", "/projects/" + pid + "/budgets", Map.of("lines", List.of(Map.of("wbsId", w,
                "costElement", "material", "amount", 800, "revenue", 1200))), auth()).body().get("id").asString();
        assertThat(call("POST", "/projects/budgets/" + b + "/approve", null, auth()).status()).isEqualTo(200);

        Res built = call("POST", "/costing/profitability/runs", Map.of("from", today, "to", today), auth());
        assertThat(built.status()).as(built.raw().body()).isEqualTo(201);
        JsonNode run = built.body();
        String id = run.get("id").asString();
        assertThat(num(run.get("glNetIncome"))).isEqualByComparingTo("130");
        assertThat(num(run.get("segmentNet"))).isEqualByComparingTo("220");
        assertThat(num(run.get("unallocated"))).isEqualByComparingTo("-70");
        assertThat(num(run.get("outOfScope"))).isEqualByComparingTo("-20");
        assertThat(num(run.get("difference"))).isEqualByComparingTo("0");

        JsonNode rec = get("/costing/profitability/runs/" + id + "/reconciliation");
        assertThat(rec.get("accounts")).hasSize(2);
        assertThat(num(rec.get("statisticalExcluded"))).isEqualByComparingTo("0");

        JsonNode byCustomer = get("/costing/profitability/runs/" + id + "/facts?layer=actual&groupBy=customer");
        JsonNode c = row(byCustomer, customer);
        assertThat(num(c.get("cm1"))).isEqualByComparingTo("400");
        assertThat(num(c.get("cm2"))).isEqualByComparingTo("370");
        assertThat(num(c.get("cm3"))).isEqualByComparingTo("370");
        JsonNode byProduct = get("/costing/profitability/runs/" + id + "/facts?layer=actual&groupBy=product");
        assertThat(num(row(byProduct, product).get("cm2"))).isEqualByComparingTo("350");
        JsonNode byProject = get("/costing/profitability/runs/" + id + "/facts?layer=actual&groupBy=project");
        assertThat(num(row(byProject, project).get("cm3"))).isEqualByComparingTo("-100");

        JsonNode estimate = get("/costing/profitability/runs/" + id + "/facts?layer=estimate&groupBy=project");
        assertThat(estimate.get("layer").asString()).isEqualTo("estimate");
        JsonNode est = row(estimate, pid);
        assertThat(num(est.get("revenue"))).isEqualByComparingTo("1200");
        assertThat(num(est.get("cm1"))).isEqualByComparingTo("400");
        for (JsonNode r : estimate.get("rows")) {
            assertThat(r.get("segment").asString()).as("no actual project in the estimate layer").isNotEqualTo(
                    project);
        }
        assertThat(call("GET", "/costing/profitability/runs/" + id + "/facts", null, auth()).status())
                .as("a layer is required").isEqualTo(400);
        assertThat(call("GET", "/costing/profitability/runs/" + id + "/facts?layer=all", null, auth()).status())
                .isEqualTo(400);
        assertThat(get("/costing/profitability/runs/" + id + "/facts?layer=standard").get("rows")).isEmpty();

        JsonNode again = call("POST", "/costing/profitability/runs", Map.of("from", today, "to", today), auth())
                .body();
        assertThat(num(again.get("difference"))).isEqualByComparingTo("0");
        assertThat(get("/costing/profitability/runs/" + id).get("status").asString()).isEqualTo("superseded");

        assertThat(call("GET", "/costing/profitability/runs/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(ProfitabilityController.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("demo books: the reconciliation difference is zero for a busy period too")
    void demoBooks() {
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(30).toString();
        Res r = call("POST", "/costing/profitability/runs", Map.of("from", from, "to", today), adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(num(r.body().get("difference"))).isEqualByComparingTo("0");
    }
}
