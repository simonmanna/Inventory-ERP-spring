package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.projects.web.ContractController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Cost Management CM-8b (ADR-S039): IFRS 15.35 criteria unlocking the over-time election; an approved and a pending
 * variation; a customer advance; two progress claims (conditional and time-only retention, advance recovery);
 * cost-to-cost revenue with contract asset / liability; a subcontract valuation with retention payable, back-charge
 * and release; customer retention release; onerous-contract assessment (indicator, exit penalty, maker-checker,
 * recognised then released); revenue run reversal; GL tie-outs; tenant isolation and the authorization matrix.
 */
@ExtendWith(BooksGuard.class)
class ContractRevenueScenarioIT extends ApiTestSupport {

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    String checkerToken;
    String customer;
    String supplier;

    @BeforeAll
    void fixtures() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, "Contract checker " + sfx,
                Permissions.ALL.toArray(String[]::new)).update();
        createUser(demoOrg, "checker-" + sfx + "@demo.test", "Checker@12345", role);
        checkerToken = login("DEMO", "checker-" + sfx + "@demo.test", "Checker@12345");
        customer = partner("CU", true);
        supplier = partner("SU", false);
    }

    String partner(String tag, boolean isCustomer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, tag + "-" + sfx, tag + " "
                + sfx, isCustomer, !isCustomer).update();
        return id;
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key(adminToken));
    }

    JsonNode posted(String path, Object body) {
        Res r = post(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    BigDecimal entry(String entryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(entryId, code).query(BigDecimal.class).single();
    }

    void assertTied(JsonNode position) {
        position.get("glTieOut").properties().forEach(e -> assertThat(num(e.getValue().get("difference")))
                .as("GL tie-out " + e.getKey()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("contract: criteria, variations, advance, claims with retention, revenue, subcontract, onerous")
    void contractScenario() {
        String pid = ok(201, "POST", "/projects", Map.of("code", "CT-" + sfx, "name", "Bridge " + sfx, "customerId",
                customer, "contractValue", 100000)).get("id").asString();
        String w = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Deck")).get("id")
                .asString();
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));

        // IFRS 15.35: over time only after an approved criteria assessment
        Map<String, Object> election = Map.of("election", "project_revenue_recognition", "scopeType", "contract",
                "scopeId", pid, "value", "over_time_cost_to_cost", "effectiveFrom", today, "justification",
                "Customer controls the asset as it is built (IFRS 15.35(b)).");
        assertThat(call("POST", "/costing/policies", election, adminToken).status()).isEqualTo(400);
        String crit = ok(201, "POST", "/projects/" + pid + "/revenue-criteria", Map.of("criterionB", true,
                "evidence", "Built on the customer's land; customer controls work in progress.")).get("id").asString();
        assertThat(call("POST", "/projects/revenue-criteria/" + crit + "/approve", null, adminToken).status())
                .isEqualTo(403);
        assertThat(call("POST", "/projects/revenue-criteria/" + crit + "/approve", null, checkerToken).status())
                .isEqualTo(200);
        JsonNode crits = ok(200, "GET", "/projects/" + pid + "/revenue-criteria", null);
        assertThat(crits.size()).isEqualTo(1);
        assertThat(crits.get(0).get("status").asString()).isEqualTo("approved");
        String policy = ok(201, "POST", "/costing/policies", election).get("id").asString();
        assertThat(call("POST", "/costing/policies/" + policy + "/approve", null, checkerToken).status())
                .isEqualTo(200);

        // variations: approved enters the price and the budget, pending does not
        String v1 = ok(201, "POST", "/projects/" + pid + "/variations", Map.of("description", "Extra span",
                "priceDelta", 10000, "costDelta", 8000, "wbsId", w, "costElement", "labour")).get("id").asString();
        ok(201, "POST", "/projects/" + pid + "/variations", Map.of("description", "Disputed lighting",
                "priceDelta", 5000));
        assertThat(call("POST", "/projects/variations/" + v1 + "/approve", null, adminToken).status())
                .isEqualTo(403);
        JsonNode approved = call("POST", "/projects/variations/" + v1 + "/approve", null, checkerToken).body();
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("budgetId").isNull()).isFalse();

        // advance and costs
        posted("/projects/" + pid + "/advances", Map.of("amount", 20000, "recoveryPct", 10));
        JsonNode advances = ok(200, "GET", "/projects/" + pid + "/advances", null);
        assertThat(advances.size()).isEqualTo(1);
        assertThat(num(advances.get(0).get("outstanding"))).isEqualByComparingTo("20000");
        posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "equipmentRef", "CRANE", "hours", 300,
                "ratePerHour", 100));
        ok(200, "PUT", "/projects/" + pid + "/forecast", Map.of("lines", List.of(Map.of("wbsId", w, "costElement",
                "labour", "costToComplete", 45000))));

        // claim 1: conditional retention, advance recovery
        String c1 = ok(201, "POST", "/projects/" + pid + "/claims", Map.of("cumulativeCertified", 40000,
                "retentionPct", 10)).get("id").asString();
        JsonNode cert1 = posted("/projects/claims/" + c1 + "/certify", null);
        assertThat(num(cert1.get("netAmount"))).isEqualByComparingTo("32000");
        assertThat(entry(cert1.get("journalEntryId").asString(), "1475")).isEqualByComparingTo("4000");
        assertThat(entry(cert1.get("journalEntryId").asString(), "2480")).isEqualByComparingTo("4000");
        BigDecimal invoiced = jdbc.sql("SELECT \"totalAmount\" FROM \"Document\" WHERE id = ?").param(
                cert1.get("invoiceId").asString()).query(BigDecimal.class).single();
        assertThat(invoiced).isEqualByComparingTo("32000");
        assertThat(post("/projects/claims/" + c1 + "/certify", null).status()).isEqualTo(400);

        // claim 2: time-only retention invoiced as a receivable due at release
        String c2 = ok(201, "POST", "/projects/" + pid + "/claims", Map.of("cumulativeCertified", 60000,
                "retentionPct", 5, "retentionCondition", "date_only", "retentionReleaseDate", today)).get("id")
                .asString();
        JsonNode cert2 = posted("/projects/claims/" + c2 + "/certify", null);
        assertThat(num(cert2.get("netAmount"))).isEqualByComparingTo("17000");
        assertThat(cert2.get("retentionInvoiceId").isNull()).isFalse();
        assertThat(call("POST", "/projects/" + pid + "/claims", Map.of("cumulativeCertified", 120000), adminToken)
                .status()).as("pending variation is not in the price").isEqualTo(400);

        // revenue: progress 30 000 / 75 000 = 40 % of 110 000
        JsonNode pv = ok(200, "GET", "/projects/" + pid + "/revenue-runs/preview?periodEnd=" + today, null);
        assertThat(pv.get("method").asString()).isEqualTo("over_time_cost_to_cost");
        assertThat(num(pv.get("cumulativeRevenue"))).isEqualByComparingTo("44000");
        JsonNode run = posted("/projects/" + pid + "/revenue-runs", Map.of("periodEnd", today));
        assertThat(entry(run.get("journalEntryId").asString(), "4610")).isEqualByComparingTo("-44000");
        assertThat(entry(run.get("journalEntryId").asString(), "5610")).isEqualByComparingTo("30000");
        assertThat(post("/projects/" + pid + "/revenue-runs", Map.of("periodEnd", today)).status()).isEqualTo(409);
        JsonNode pos = ok(200, "GET", "/projects/" + pid + "/contract-position", null);
        assertThat(num(pos.get("contractLiability"))).as("billed 60 000 > revenue 44 000").isEqualByComparingTo(
                "16000");
        assertThat(num(pos.get("advancesOutstanding"))).isEqualByComparingTo("14000");
        assertThat(num(pos.get("retentionContractAsset"))).isEqualByComparingTo("4000");
        assertThat(num(pos.get("retentionReceivable"))).isEqualByComparingTo("1000");
        JsonNode rep = ok(200, "GET", "/projects/" + pid + "/report", null);
        assertThat(num(rep.get("wipTieOut").get("difference"))).isEqualByComparingTo("0");

        // subcontractor: gross cost, retention payable, back-charge, release on evidence
        String sc = ok(201, "POST", "/projects/" + pid + "/subcontracts", Map.of("supplierId", supplier, "wbsId", w,
                "scope", "Piling", "value", 10000, "retentionPct", 5)).get("id").asString();
        JsonNode val = posted("/projects/subcontracts/" + sc + "/valuations", Map.of("cumulativeValue", 6000));
        assertThat(num(val.get("retention"))).isEqualByComparingTo("300");
        JsonNode subs = ok(200, "GET", "/projects/" + pid + "/subcontracts", null);
        assertThat(subs.size()).isEqualTo(1);
        assertThat(subs.get(0).get("supplierName").isNull()).isFalse();
        assertThat(subs.get(0).get("valuations").size()).isEqualTo(1);
        assertThat(entry(val.get("journalEntryId").asString(), "1460")).isEqualByComparingTo("6000");
        assertThat(entry(val.get("journalEntryId").asString(), "2485")).isEqualByComparingTo("-300");
        String payable = null;
        String customerRetention = null;
        for (JsonNode r : ok(200, "GET", "/projects/" + pid + "/retentions", null)) {
            if ("subcontractor".equals(r.get("side").asString())) {
                payable = r.get("id").asString();
            } else if ("contract_asset".equals(r.get("classification").asString())) {
                customerRetention = r.get("id").asString();
            }
        }
        posted("/projects/retentions/" + payable + "/back-charge", Map.of("amount", 100, "reason", "Damaged kerb"));
        assertThat(post("/projects/retentions/" + payable + "/release", Map.of()).status()).isEqualTo(400);
        JsonNode released = posted("/projects/retentions/" + payable + "/release", Map.of("evidence", "PC-001"));
        assertThat(released.get("status").asString()).isEqualTo("released");
        BigDecimal billed = jdbc.sql("SELECT \"totalAmount\" FROM \"Document\" WHERE id = ?").param(
                released.get("releaseDocumentId").asString()).query(BigDecimal.class).single();
        assertThat(billed).isEqualByComparingTo("200");
        JsonNode custRel = posted("/projects/retentions/" + customerRetention + "/release", Map.of("evidence",
                "PC-001"));
        assertThat(custRel.get("classification").asString()).isEqualTo("receivable");
        assertTied(ok(200, "GET", "/projects/" + pid + "/contract-position", null));

        // onerous: remaining price 66 000; fulfilling 60 000 + 10 000 (G&A 5 000 excluded); exit penalty 3 000
        ok(200, "PUT", "/projects/" + pid + "/forecast", Map.of("lines", List.of(
                Map.of("wbsId", w, "costElement", "labour", "costToComplete", 60000),
                Map.of("wbsId", w, "costElement", "equipment", "costToComplete", 10000, "fulfilmentClass",
                        "directly_related"),
                Map.of("wbsId", w, "costElement", "overhead", "costToComplete", 5000, "fulfilmentClass",
                        "excluded"))));
        assertThat(call("POST", "/projects/" + pid + "/onerous-assessments", Map.of(), adminToken).status())
                .isEqualTo(400);
        JsonNode oa = ok(201, "POST", "/projects/" + pid + "/onerous-assessments", Map.of("indicators",
                List.of("eac_increase"), "costOfExiting", 3000, "exitEvidence", "Clause 14.2 termination fee"));
        assertThat(num(oa.get("excludedCost"))).isEqualByComparingTo("5000");
        assertThat(num(oa.get("provision"))).as("exit-based amount").isEqualByComparingTo("3000");
        String oaId = oa.get("id").asString();
        assertThat(post("/projects/onerous-assessments/" + oaId + "/post", null).status()).isEqualTo(400);
        assertThat(call("POST", "/projects/onerous-assessments/" + oaId + "/approve", null, adminToken).status())
                .isEqualTo(403);
        assertThat(call("POST", "/projects/onerous-assessments/" + oaId + "/approve", null, checkerToken).status())
                .isEqualTo(200);
        JsonNode op = posted("/projects/onerous-assessments/" + oaId + "/post", null);
        assertThat(entry(op.get("journalEntryId").asString(), "2490")).isEqualByComparingTo("-3000");
        // no longer onerous: released
        ok(200, "PUT", "/projects/" + pid + "/forecast", Map.of("lines", List.of(
                Map.of("wbsId", w, "costElement", "labour", "costToComplete", 40000),
                Map.of("wbsId", w, "costElement", "equipment", "costToComplete", 0, "fulfilmentClass",
                        "directly_related"))));
        String oa2 = ok(201, "POST", "/projects/" + pid + "/onerous-assessments", Map.of("indicators",
                List.of("management_flag"))).get("id").asString();
        call("POST", "/projects/onerous-assessments/" + oa2 + "/approve", null, checkerToken);
        JsonNode rel = posted("/projects/onerous-assessments/" + oa2 + "/post", null);
        assertThat(num(rel.get("movement"))).isEqualByComparingTo("-3000");
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/contract-position", null).get("onerousProvision")))
                .isEqualByComparingTo("0");

        // reversal of the revenue run
        String runId = run.get("id").asString();
        assertThat(post("/projects/revenue-runs/" + runId + "/reverse", null).status()).isEqualTo(200);
        assertThat(post("/projects/revenue-runs/" + runId + "/reverse", null).status()).isEqualTo(400);
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/report", null).get("wipTieOut").get("difference")))
                .isEqualByComparingTo("0");
        assertTied(ok(200, "GET", "/projects/" + pid + "/contract-position", null));

        assertThat(call("GET", "/projects/" + pid + "/contract-position", null, otherToken).status()).isEqualTo(404);
        for (String list : List.of("revenue-criteria", "advances", "subcontracts")) {
            assertThat(call("GET", "/projects/" + pid + "/" + list, null, otherToken).status()).isEqualTo(404);
        }
        assertThat(call("GET", "/projects/claims/" + c1, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix(ContractController.class)).isEqualTo(32);
    }

    @Test
    @DisplayName("point in time: no revenue until the contract is completed, then everything")
    void pointInTime() {
        String pid = ok(201, "POST", "/projects", Map.of("code", "PT-" + sfx, "name", "Kiosk " + sfx, "customerId",
                customer, "contractValue", 5000)).get("id").asString();
        String w = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Build")).get("id")
                .asString();
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));
        posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", w, "hours", 10, "ratePerHour", 300));
        JsonNode open = ok(200, "GET", "/projects/" + pid + "/revenue-runs/preview?periodEnd=" + today, null);
        assertThat(open.get("method").asString()).isEqualTo("point_in_time");
        assertThat(num(open.get("cumulativeRevenue"))).isEqualByComparingTo("0");
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "completed"));
        JsonNode run = posted("/projects/" + pid + "/revenue-runs", Map.of("periodEnd", today));
        assertThat(num(run.get("cumulativeRevenue"))).isEqualByComparingTo("5000");
        assertThat(num(run.get("costThisPeriod"))).isEqualByComparingTo("3000");
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/report", null).get("wipTieOut").get("difference")))
                .isEqualByComparingTo("0");
    }
}
