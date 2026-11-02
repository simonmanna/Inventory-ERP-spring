package com.inventoryerp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.reporting.application.ReportScheduleService;
import com.inventoryerp.reporting.web.ReportLibraryController;
import com.inventoryerp.reporting.web.ReportScheduleController;
import com.inventoryerp.reporting.web.ReportViewController;
import com.inventoryerp.reporting.web.StatementLayoutController;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Financial reporting FR-0 (ADR-S043): library visibility by permission, layout-driven profit or loss and financial
 * position with comparatives, variances and integrity checks, drill-down to journal lines and source documents,
 * dimension and branch filters, the branch data scope, exports, saved views, schedules, the run log and its
 * reproducibility check, layouts, tenant isolation and the authorization matrix.
 */
class ReportEngineIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    AccountResolverService accounts;

    @Autowired
    ReportScheduleService schedules;

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static JsonNode row(JsonNode result, String id) {
        for (JsonNode r : result.get("rows")) {
            if (r.get("id").asString().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("no row " + id);
    }

    static JsonNode check(JsonNode result, String code) {
        for (JsonNode c : result.get("checks")) {
            if (c.get("code").asString().equals(code)) {
                return c;
            }
        }
        throw new AssertionError("no check " + code);
    }

    HttpResponse<byte[]> download(String path, Object body, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    String role(String org, String... permissions) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(id, org, "R-" + id, permissions).update();
        return id;
    }

    @Test
    @DisplayName("FR-0: statements, comparatives, drill, filters, scope, exports, views, schedules, run log")
    void engine() throws Exception {
        String code = "fr0-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Report Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant yesterday = now.minusSeconds(86_400);
        String branchA = UUID.randomUUID().toString();
        String branchB = UUID.randomUUID().toString();
        for (String[] b : new String[][] {{branchA, "A"}, {branchB, "B"}}) {
            jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                    + " now())").params(b[0], org, "BR-" + b[1], "Branch " + b[1]).update();
        }
        String project = UUID.randomUUID().toString();
        String invoiceId = UUID.randomUUID().toString();
        Map<String, String> acc = TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            Map<String, String> m = new HashMap<>();
            m.put("cash", accounts.ensureByCode("1105", "Test cash", "cash", null));
            m.put("rev", accounts.ensureByCode("4950", "Test revenue", "revenue", null));
            m.put("cogs", accounts.ensureByCode("5050", "Test cost of sales", "cost_of_goods_sold", null));
            m.put("opex", accounts.ensureByCode("6950", "Test overhead", "operating_expense", null));
            m.put("eq", accounts.ensureByCode("3950", "Test capital", "equity", null));
            Object[][] entries = {
                {yesterday, "eq", 500, null, null, null},
                {yesterday, "rev", 300, branchB, null, null},
                {now, "rev", 1000, branchA, "invoice", null},
                {now, "cogs", -600, branchA, null, null},
                {now, "opex", -100, branchA, null, project}};
            for (Object[] e : entries) {
                int amt = (Integer) e[2];
                String other = m.get((String) e[1]);
                String branch = (String) e[3];
                Map<String, Object> dims = e[5] == null ? null : Map.of("projectId", e[5]);
                List<Line> lines = amt > 0
                        ? List.of(new Line(m.get("cash"), amt, null, null, "dr", branch, null, dims),
                                new Line(other, null, amt, null, "cr", branch, null, dims))
                        : List.of(new Line(other, -amt, null, null, "dr", branch, null, dims),
                                new Line(m.get("cash"), null, -amt, null, "cr", branch, null, dims));
                posting.post(new PostingRequest("GEN", (Instant) e[0], "fr0", null, null, e[4] == null ? "test"
                        : (String) e[4], e[4] == null ? UUID.randomUUID().toString() : invoiceId, null, null, branch,
                        null, null, lines));
            }
            return m;
        });

        // library: categories in order, engine reports visible, catalogue links present
        JsonNode lib = call("GET", "/reports/library", null, token).body();
        assertThat(lib).hasSize(15);
        assertThat(lib.get(0).get("title").asString()).isEqualTo("Financial Statements");
        assertThat(lib.get(0).get("reports").toString()).contains("fs.profit-and-loss", "fs.balance-sheet",
                "legacy.trial-balance");
        assertThat(lib.get(14).get("reports").toString()).contains("legacy.quarry-cost-per-tonne");
        assertThat(call("GET", "/reports/library/fs.profit-and-loss", null, token).body().get("layouts")).hasSize(1);

        // profit or loss: today vs yesterday with variance
        String day = today.toString();
        JsonNode pl = call("POST", "/reports/run/fs.profit-and-loss", Map.of("from", day, "to", day, "compare",
                List.of("PRIOR_PERIOD", "VARIANCE", "VARIANCE_PCT")), token).body();
        assertThat(pl.get("header").get("company").asString()).isEqualTo("Report Co");
        assertThat(pl.get("header").get("resultHash").asString()).hasSize(64);
        assertThat(pl.get("columns").toString()).contains("PRIOR_PERIOD", "VARIANCE_PCT");
        assertThat(dec(row(pl, "REV").get("values").get("CURRENT"))).isEqualByComparingTo("1000");
        assertThat(dec(row(pl, "REV").get("values").get("PRIOR_PERIOD"))).isEqualByComparingTo("300");
        assertThat(dec(row(pl, "REV").get("values").get("VARIANCE"))).isEqualByComparingTo("700");
        assertThat(dec(row(pl, "REV").get("values").get("VARIANCE_PCT"))).isEqualByComparingTo("233.33");
        assertThat(dec(row(pl, "COS").get("values").get("CURRENT"))).isEqualByComparingTo("-600");
        assertThat(dec(row(pl, "GP").get("values").get("CURRENT"))).isEqualByComparingTo("400");
        assertThat(dec(row(pl, "NP").get("values").get("CURRENT"))).isEqualByComparingTo("300");
        assertThat(dec(row(pl, "NP").get("values").get("PRIOR_PERIOD"))).isEqualByComparingTo("300");
        assertThat(dec(row(pl, "GPM").get("values").get("CURRENT"))).isEqualByComparingTo("40");
        assertThat(row(pl, "GPM").get("kind").asString()).isEqualTo("kpi");
        assertThat(check(pl, "TIES_TO_GL_CURRENT").get("ok").asBoolean()).isTrue();
        assertThat(check(pl, "ALL_ACCOUNTS_MAPPED").get("ok").asBoolean()).isTrue();
        assertThat(row(pl, "REV:" + acc.get("rev")).get("kind").asString()).isEqualTo("account");

        // drill: statement cell → journal lines → source document
        JsonNode drill = row(pl, "REV").get("drill").get("CURRENT");
        assertThat(drill.get("reportId").asString()).isEqualTo("gl.journal-lines");
        JsonNode lines = call("POST", "/reports/run/gl.journal-lines", drill.get("params"), token).body();
        assertThat(lines.get("rows")).hasSize(2);
        assertThat(lines.get("rows").get(0).get("id").asString()).isEqualTo("opening");
        assertThat(dec(lines.get("rows").get(0).get("values").get("balance"))).isEqualByComparingTo("-300");
        JsonNode revLine = lines.get("rows").get(1);
        assertThat(dec(revLine.get("values").get("credit"))).isEqualByComparingTo("1000");
        assertThat(dec(revLine.get("values").get("balance"))).isEqualByComparingTo("-1300");
        assertThat(revLine.get("values").get("branch").asString()).isEqualTo("Branch A");
        assertThat(revLine.get("drill").get("source").get("route").asString()).isEqualTo("/invoices/" + invoiceId);
        assertThat(revLine.get("drill").get("_row").get("route").asString()).startsWith("/journal-entries/");

        // one account with opening and running balance
        JsonNode cash = call("POST", "/reports/run/gl.journal-lines", Map.of("from", day, "to", day, "accountIds",
                List.of(acc.get("cash"))), token).body();
        assertThat(dec(cash.get("rows").get(0).get("values").get("balance"))).isEqualByComparingTo("800");
        JsonNode lastCash = cash.get("rows").get(cash.get("rows").size() - 1);
        assertThat(dec(lastCash.get("values").get("balance"))).isEqualByComparingTo("1100");
        JsonNode all = call("POST", "/reports/run/gl.journal-lines", Map.of("from", day, "to", day), token).body();
        assertThat(check(all, "DEBITS_EQUAL_CREDITS").get("ok").asBoolean()).isTrue();

        // dimension and branch filters
        JsonNode proj = call("POST", "/reports/run/fs.profit-and-loss", Map.of("from", day, "to", day, "dimensions",
                Map.of("projectId", List.of(project))), token).body();
        assertThat(dec(row(proj, "NP").get("values").get("CURRENT"))).isEqualByComparingTo("-100");
        JsonNode brB = call("POST", "/reports/run/fs.profit-and-loss", Map.of("period", "THIS_YEAR", "branchIds",
                List.of(branchB)), token).body();
        assertThat(dec(row(brB, "NP").get("values").get("CURRENT"))).isEqualByComparingTo("300");
        assertThat(brB.get("header").get("branches").get(0).asString()).isEqualTo("Branch B");

        // financial position balances; profit not yet closed sits in equity
        JsonNode bs = call("POST", "/reports/run/fs.balance-sheet", Map.of("asOf", day, "compare",
                List.of("PRIOR_PERIOD")), token).body();
        assertThat(dec(row(bs, "CASH").get("values").get("CURRENT"))).isEqualByComparingTo("1100");
        assertThat(dec(row(bs, "CYE").get("values").get("CURRENT"))).isEqualByComparingTo("600");
        assertThat(dec(row(bs, "TA").get("values").get("CURRENT"))).isEqualByComparingTo("1100");
        assertThat(dec(row(bs, "TLE").get("values").get("CURRENT"))).isEqualByComparingTo("1100");
        assertThat(check(bs, "BALANCED_CURRENT").get("ok").asBoolean()).isTrue();
        assertThat(check(bs, "BALANCED_PRIOR_PERIOD").get("ok").asBoolean()).isTrue();
        assertThat(bs.get("header").get("asOf").asString()).isEqualTo(day);
        JsonNode cashDrill = row(bs, "CASH").get("drill").get("CURRENT");
        assertThat(cashDrill.get("params").get("from").asString()).isEqualTo("1900-01-01");
        JsonNode cashLines = call("POST", "/reports/run/gl.journal-lines", cashDrill.get("params"), token).body();
        JsonNode lastLine = cashLines.get("rows").get(cashLines.get("rows").size() - 1);
        assertThat(dec(lastLine.get("values").get("balance"))).as("drill covers all history").isEqualByComparingTo(
                "1100");

        // filter validation and links
        assertThat(call("POST", "/reports/run/fs.balance-sheet", Map.of("from", day), token).body().get("message")
                .asString()).isEqualTo("from is not a filter of report fs.balance-sheet");
        assertThat(call("POST", "/reports/run/fs.profit-and-loss", Map.of("compare", List.of("BUDGET")), token)
                .status()).isEqualTo(400);
        assertThat(call("POST", "/reports/run/legacy.trial-balance", Map.of(), token).status()).isEqualTo(400);
        assertThat(call("POST", "/reports/run/nope", Map.of(), token).status()).isEqualTo(404);
        assertThat(call("POST", "/reports/run/fs.profit-and-loss", Map.of("bogus", 1), token).status())
                .isEqualTo(400);

        // layouts: copy, refuse bad formulas, unmapped accounts surface as a failed check
        assertThat(call("POST", "/reports/fs-layouts", Map.of("code", "MY_PL", "name", "Mine", "copyFrom",
                "IFRS_SME_PL"), token).status()).isEqualTo(201);
        List<Map<String, Object>> mine = new ArrayList<>();
        for (JsonNode l : call("GET", "/reports/fs-layouts/MY_PL", null, token).body().get("lines")) {
            Map<String, Object> m = mapper.convertValue(l, Map.class);
            if (!"COS".equals(m.get("code"))) {
                mine.add(m);
            }
        }
        assertThat(call("PUT", "/reports/fs-layouts/MY_PL", Map.of("lines", mine), token).body().get("message")
                .asString()).contains("unknown line COS");
        mine.replaceAll(m -> "GP".equals(m.get("code")) ? new HashMap<>(Map.of("code", "GP", "label",
                "Gross profit", "lineType", "FORMULA", "formula", "NET_REV", "sign", "CREDIT", "indent", 0, "bold",
                true, "kpi", false, "percent", false)) : m);
        assertThat(call("PUT", "/reports/fs-layouts/MY_PL", Map.of("lines", mine), token).status()).isEqualTo(200);
        JsonNode unmapped = call("POST", "/reports/run/fs.profit-and-loss", Map.of("from", day, "to", day,
                "layoutCode", "MY_PL"), token).body();
        assertThat(check(unmapped, "ALL_ACCOUNTS_MAPPED").get("ok").asBoolean()).isFalse();
        assertThat(check(unmapped, "ALL_ACCOUNTS_MAPPED").get("detail").asString()).contains("5050");
        assertThat(call("POST", "/reports/run/fs.balance-sheet", Map.of("layoutCode", "MY_PL"), token).status())
                .isEqualTo(400);
        assertThat(call("DELETE", "/reports/fs-layouts/IFRS_SME_PL", null, token).status()).isEqualTo(400);
        assertThat(call("POST", "/reports/fs-layouts/IFRS_SME_PL/reset", null, token).status()).isEqualTo(200);

        // exports
        Map<String, Object> plReq = Map.of("from", day, "to", day);
        HttpResponse<byte[]> csv = download("/reports/run/fs.profit-and-loss/export?format=csv", plReq, token);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(new String(csv.body(), StandardCharsets.UTF_8)).contains("Report Co", "Net profit for the period");
        HttpResponse<byte[]> xlsx = download("/reports/run/fs.profit-and-loss/export?format=xlsx", plReq, token);
        assertThat(xlsx.statusCode()).isEqualTo(200);
        assertThat(new String(xlsx.body(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        HttpResponse<byte[]> pdf = download("/reports/run/fs.balance-sheet/export?format=pdf", Map.of(), token);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(pdf.headers().firstValue("Content-Disposition").orElse("")).contains(".pdf");
        assertThat(download("/reports/run/fs.balance-sheet/export?format=doc", Map.of(), token).statusCode())
                .isEqualTo(400);

        // run log and reproducibility
        JsonNode runs = call("GET", "/reports/runs?reportId=fs.profit-and-loss", null, token).body();
        assertThat(runs.size()).isGreaterThanOrEqualTo(4);
        String runId = pl.get("header").get("runId").asString();
        JsonNode verify = call("POST", "/reports/runs/" + runId + "/verify", null, token).body();
        assertThat(verify.get("reproducible").asBoolean()).as(verify.toString()).isTrue();
        assertThat(call("POST", "/reports/runs/" + runId + "/verify", null, otherToken).status()).isEqualTo(404);

        // saved views
        assertThat(call("POST", "/reports/views", Map.of("reportId", "fs.profit-and-loss", "name", "Monthly",
                "params", Map.of("period", "LAST_MONTH", "compare", List.of("PRIOR_YEAR"))), token).status())
                .isEqualTo(201);
        assertThat(call("POST", "/reports/views", Map.of("reportId", "fs.balance-sheet", "name", "Bad",
                "params", Map.of("from", day)), token).status()).isEqualTo(400);
        JsonNode views = call("GET", "/reports/views?reportId=fs.profit-and-loss", null, token).body();
        assertThat(views).hasSize(1);
        assertThat(call("DELETE", "/reports/views/" + views.get(0).get("id").asString(), null, token).status())
                .isEqualTo(200);

        // schedules: validate, run now, and the job delivers due schedules then moves the next run
        assertThat(call("POST", "/reports/schedules", Map.of("reportId", "fs.profit-and-loss", "name", "Weekly",
                "format", "pdf", "frequency", "WEEKLY", "recipients", List.of("cfo@x.test")), token).status())
                .isEqualTo(400);
        Res sched = call("POST", "/reports/schedules", Map.of("reportId", "fs.profit-and-loss", "name", "Daily P&L",
                "format", "xlsx", "frequency", "DAILY", "hourOfDay", 6, "params", Map.of("period", "THIS_MONTH"),
                "recipients", List.of("cfo@x.test")), token);
        assertThat(sched.status()).as(sched.raw().body()).isEqualTo(201);
        String scheduleId = sched.body().get("id").asString();
        assertThat(call("POST", "/reports/schedules/" + scheduleId + "/run", null, token).body().get("ok")
                .asBoolean()).isTrue();
        jdbc.sql("UPDATE \"ReportSchedule\" SET \"nextRunAt\" = now() - interval '1 minute' WHERE id = ?")
                .param(scheduleId).update();
        assertThat(schedules.runDue(Instant.now())).isGreaterThanOrEqualTo(1);
        Map<String, Object> after = jdbc.sql("SELECT \"lastStatus\", \"nextRunAt\" > now() AS future FROM"
                + " \"ReportSchedule\" WHERE id = ?").param(scheduleId).query().singleRow();
        assertThat(after.get("lastStatus")).isEqualTo("OK");
        assertThat(after.get("future")).isEqualTo(true);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ReportRun\" WHERE \"organizationId\" = ? AND trigger ="
                + " 'SCHEDULE'").param(org).query(Long.class).single()).isGreaterThanOrEqualTo(2L);

        // permissions: library needs report_library:read; each report its own permission
        assertThat(call("GET", "/reports/library", null, weakToken()).status()).isEqualTo(403);
        String libOnly = role(org, "report_library:read");
        createUser(org, "lib@" + code + ".test", "Lib@123456", libOnly);
        String libToken = login(code, "lib@" + code + ".test", "Lib@123456");
        assertThat(call("GET", "/reports/library", null, libToken).body().get(0).get("reports")).isEmpty();
        assertThat(call("POST", "/reports/run/fs.profit-and-loss", Map.of(), libToken).status()).isEqualTo(403);

        // branch data scope: a user granted branch B sees only branch B
        String scoped = role(org, "report_library:read", "report:accounting");
        String scopedUser = createUser(org, "scoped@" + code + ".test", "Scoped@1234", scoped);
        assertThat(call("PUT", "/user-data-scopes/" + scopedUser, Map.of("branchIds", List.of(branchB),
                "locationIds", List.of()), token).status()).isEqualTo(200);
        String scopedToken = login(code, "scoped@" + code + ".test", "Scoped@1234");
        JsonNode scopedPl = call("POST", "/reports/run/fs.profit-and-loss", Map.of("period", "THIS_YEAR"),
                scopedToken).body();
        assertThat(dec(row(scopedPl, "NP").get("values").get("CURRENT"))).isEqualByComparingTo("300");
        assertThat(call("POST", "/reports/run/fs.profit-and-loss", Map.of("branchIds", List.of(branchA)),
                scopedToken).status()).isEqualTo(403);

        // tenant isolation: another organization sees none of this ledger
        JsonNode other = call("POST", "/reports/run/fs.profit-and-loss", Map.of("from", day, "to", day), otherToken)
                .body();
        assertThat(other.get("header").get("company").asString()).isNotEqualTo("Report Co");
        assertThat(other.toString()).doesNotContain(acc.get("rev"));

        assertThat(assertAuthorizationMatrix(ReportLibraryController.class, ReportViewController.class,
                ReportScheduleController.class, StatementLayoutController.class)).isEqualTo(20);
    }
}
