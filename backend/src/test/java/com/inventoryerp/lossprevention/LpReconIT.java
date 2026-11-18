package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.lossprevention.web.LossPreventionCaseController;
import com.inventoryerp.lossprevention.web.LossPreventionReportController;
import com.inventoryerp.lossprevention.web.LossPreventionRuleController;
import com.inventoryerp.lossprevention.web.LossPreventionRunController;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * Ledger → reconciliation run → case → late event → new run, end to end: runs are kept and comparable, a run can be
 * repeated from its recorded input, the person a case is about never sees it, and every route needs its permission.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpReconIT extends ApiTestSupport {

    final String source = "it-" + UUID.randomUUID().toString().substring(0, 8);
    /** A finished business day nobody else uses: the DEMO organization is shared between runs. */
    final LocalDate day = LocalDate.of(2015, 1, 1).plusDays((long) (Math.random() * 3000));
    String branch;
    String cashier;
    String cashierToken;
    String caseId;
    String firstRun;

    String branch() {
        if (branch == null) {
            branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                    + " id LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        }
        return branch;
    }

    Map<String, Object> event(String type, String sourceEventId, String time, String level, String evidenceSource,
            Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", source);
        e.put("sourceEventId", sourceEventId);
        e.put("branchId", branch());
        e.put("actorId", cashier);
        e.put("sessionId", "session-" + source);
        e.put("businessDate", day.toString());
        e.put("occurredAt", day + "T" + time + "Z");
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.put("payload", payload);
        e.put("evidenceLevel", level);
        e.put("evidenceSource", evidenceSource);
        e.put("observationStatus", "observed");
        return e;
    }

    void ingest(Map<String, Object> e) {
        JsonNode r = ok(201, "POST", "/loss-prevention/events", Map.of("events", List.of(e)));
        assertThat(r.get("accepted").asInt()).as(r.toString()).isEqualTo(1);
    }

    JsonNode replay(String engine) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + adminToken);
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/loss-prevention/runs/replay", Map.of("engine", engine, "from", day.toString(), "to",
                day.toString()), headers);
        // A route behind an Idempotency-Key answers 200, like every such route here.
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String period() {
        return "from=" + day + "&to=" + day;
    }

    @Test
    @Order(1)
    @DisplayName("rules: defaults until changed, each change a new version, bad input refused")
    void rules() {
        JsonNode list = ok(200, "GET", "/loss-prevention/rules", null);
        assertThat(list.size()).isEqualTo(13);
        assertThat(as("PUT", "/loss-prevention/rules/NOPE", Map.of("enabled", true, "severity", "info")).status())
                .isEqualTo(404);
        assertThat(as("PUT", "/loss-prevention/rules/C08", Map.of("enabled", true, "severity", "loud")).status())
                .isEqualTo(400);
        assertThat(as("PUT", "/loss-prevention/rules/C08", Map.of("enabled", true, "severity", "warning", "params",
                Map.of("bogus", 1))).status()).isEqualTo(400);
        assertThat(as("PUT", "/loss-prevention/rules/C08", Map.of("enabled", true, "severity", "warning", "params",
                Map.of("amount", -1))).status()).isEqualTo(400);
        int before = ok(200, "GET", "/loss-prevention/rules/C08/versions", null).size();
        JsonNode saved = ok(200, "PUT", "/loss-prevention/rules/C08", Map.of("enabled", true, "severity", "warning",
                "params", Map.of("amount", 5)));
        assertThat(saved.get("configured").asBoolean()).isTrue();
        assertThat(saved.get("customized").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/loss-prevention/rules/C08/versions", null).size()).isEqualTo(before + 1);
        // Another organization keeps the default: no threshold, so the rule does not run there.
        for (JsonNode r : call("GET", "/loss-prevention/rules", null, otherToken).body()) {
            if ("C08".equals(r.get("code").asString())) {
                assertThat(r.get("configured").asBoolean()).isFalse();
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("a short cash session is stored, reconciled and becomes one case with its evidence")
    void runOpensCase() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, "LP-" + role, Permissions.ALL.toArray(
                        String[]::new)).update();
        String email = "cashier-" + source + "@demo.test";
        cashier = createUser(demoOrg, email, "Cashier@12345", role);
        cashierToken = login("DEMO", email, "Cashier@12345");

        ingest(event("cash_session.closed", "close-1", "18:00:00", "session", "pos", Map.of("expected", "100.00",
                "counted", "80.00", "difference", "-20.00")));
        JsonNode replayed = replay("cashier");
        assertThat(replayed.get("windows").asInt()).isEqualTo(1);
        assertThat(replayed.get("findings").asInt()).isEqualTo(1);
        firstRun = replayed.get("runs").get(0).get("runId").asString();

        JsonNode list = ok(200, "GET", "/loss-prevention/cases?ruleCode=C08&" + period(), null);
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);
        JsonNode c = list.get("data").get(0);
        caseId = c.get("id").asString();
        assertThat(c.get("state").asString()).isEqualTo("open");
        assertThat(c.get("caseNumber").asString()).startsWith("LP-");
        assertThat(c.get("subjectUserId").asString()).isEqualTo(cashier);
        assertThat(c.get("exposureAmount").asDouble()).isEqualTo(20d);

        JsonNode detail = ok(200, "GET", "/loss-prevention/cases/" + caseId, null);
        assertThat(detail.get("findings").size()).isEqualTo(1);
        assertThat(detail.get("events").size()).isEqualTo(1);
        assertThat(detail.get("events").get(0).get("sourceEventId").asString()).isEqualTo("close-1");
        List<String> kinds = new ArrayList<>();
        detail.get("timeline").forEach(t -> kinds.add(t.get("kind").asString()));
        assertThat(kinds).contains("opened", "viewed");
    }

    @Test
    @Order(3)
    @DisplayName("the person a case is about, and another organization, cannot see it")
    void hiddenFromSubject() {
        assertThat(call("GET", "/loss-prevention/cases/" + caseId, null, cashierToken).status()).isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/cases?" + period(), null, cashierToken).body().get("meta")
                .get("total").asInt()).isZero();
        assertThat(call("POST", "/loss-prevention/cases/" + caseId + "/start-review", null, cashierToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/cases/" + caseId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/loss-prevention/runs/" + firstRun, null, otherToken).status()).isEqualTo(404);
        JsonNode run = call("GET", "/loss-prevention/runs/" + firstRun, null, cashierToken).body();
        assertThat(run.get("findings").size()).isZero();
        assertThat(as("POST", "/loss-prevention/cases/" + caseId + "/assign", Map.of("assigneeId", cashier)).status())
                .isEqualTo(400);
    }

    @Test
    @Order(4)
    @DisplayName("review: an outcome is required, 'explained' needs a cause, a closed case changes only by reopening")
    void review() {
        String base = "/loss-prevention/cases/" + caseId;
        assertThat(ok(201, "POST", base + "/start-review", null).get("state").asString()).isEqualTo("reviewing");
        ok(201, "POST", base + "/notes", Map.of("note", "Counted the float again with the supervisor."));
        assertThat(as("POST", base + "/close", Map.of("outcome", "theft")).status()).isEqualTo(400);
        assertThat(as("POST", base + "/close", Map.of("outcome", "explained")).status()).isEqualTo(400);
        JsonNode closed = ok(201, "POST", base + "/close", Map.of("outcome", "confirmed_variance", "note",
                "Cause not established."));
        assertThat(closed.get("state").asString()).isEqualTo("closed");
        assertThat(closed.get("outcome").asString()).isEqualTo("confirmed_variance");
        assertThat(as("POST", base + "/notes", Map.of("note", "x")).status()).isEqualTo(201);
        assertThat(as("POST", base + "/start-review", null).status()).isEqualTo(400);
        assertThat(ok(201, "POST", base + "/reopen", Map.of("reason", "New count sheet found.")).get("state")
                .asString()).isEqualTo("reviewing");
    }

    @Test
    @Order(5)
    @DisplayName("a late event gives a new run; the old run and its finding are kept and the two can be compared")
    void lateEvent() {
        // The session was reopened and closed again with the cash found: a later close of the same session.
        ingest(event("cash_session.closed", "close-2", "19:30:00", "session", "pos", Map.of("expected", "100.00",
                "counted", "100.00", "difference", "0.00")));
        String pending = jdbc.sql("SELECT \"pendingTrigger\" FROM \"LpWindow\" WHERE \"organizationId\" = ? AND engine"
                + " = 'cashier' AND \"businessDate\" = ?").params(demoOrg, java.sql.Date.valueOf(day))
                .query(String.class).single();
        assertThat(pending).isEqualTo("late_event");

        JsonNode replayed = replay("cashier");
        assertThat(replayed.get("findings").asInt()).isZero();
        String secondRun = replayed.get("runs").get(0).get("runId").asString();

        JsonNode runs = ok(200, "GET", "/loss-prevention/runs?engine=cashier&" + period(), null);
        assertThat(runs.get("meta").get("total").asInt()).isEqualTo(2);
        JsonNode second = ok(200, "GET", "/loss-prevention/runs/" + secondRun, null);
        assertThat(second.get("supersedesRunId").asString()).isEqualTo(firstRun);
        JsonNode first = ok(200, "GET", "/loss-prevention/runs/" + firstRun, null);
        assertThat(first.get("findings").size()).isEqualTo(1);
        assertThat(first.get("findings").get(0).get("status").asString()).isEqualTo("superseded");

        JsonNode diff = ok(200, "GET", "/loss-prevention/runs/" + firstRun + "/compare/" + secondRun, null);
        assertThat(diff.get("removed").size()).isEqualTo(1);
        assertThat(diff.get("added").size()).isZero();
        assertThat(diff.get("inputChanged").asBoolean()).isTrue();
        assertThat(diff.get("removed").get(0).get("caseState").asString()).isEqualTo("reviewing");

        // The case was under review, so it is told and left for its reviewer.
        JsonNode detail = ok(200, "GET", "/loss-prevention/cases/" + caseId, null);
        assertThat(detail.get("state").asString()).isEqualTo("reviewing");
        List<String> kinds = new ArrayList<>();
        detail.get("timeline").forEach(t -> kinds.add(t.get("kind").asString()));
        assertThat(kinds).contains("finding_withdrawn");
    }

    @Test
    @Order(6)
    @DisplayName("a stored run repeats from its recorded input with the same findings")
    void reproducible() {
        JsonNode verified = ok(200, "GET", "/loss-prevention/runs/" + firstRun + "/verify", null);
        assertThat(verified.get("identical").asBoolean()).as(verified.toString()).isTrue();
        assertThat(verified.get("stored").asInt()).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("bar: an unexplained bottle variance becomes a case; nobody started it, so a corrected count closes it")
    void bar() {
        Map<String, Object> line = event("stock_count.line", "line-1", "22:00:00", "session", "stock", Map.of(
                "productId", "gin-" + source, "productName", "Gin", "systemMl", "700", "countedMl", "550",
                "varianceMl", "-150"));
        line.put("lineId", "line-" + source);
        ingest(line);
        assertThat(replay("bar").get("findings").asInt()).isEqualTo(1);
        JsonNode open = ok(200, "GET", "/loss-prevention/cases?ruleCode=B01&" + period(), null);
        assertThat(open.get("meta").get("total").asInt()).isEqualTo(1);
        String barCase = open.get("data").get(0).get("id").asString();

        Map<String, Object> corrected = event("stock_count.line", "line-1-corrected", "22:30:00", "session", "stock",
                Map.of("productId", "gin-" + source, "productName", "Gin", "systemMl", "700", "countedMl", "695",
                        "varianceMl", "-5"));
        corrected.put("lineId", "line-" + source);
        ingest(corrected);
        assertThat(replay("bar").get("findings").asInt()).isZero();
        JsonNode closed = ok(200, "GET", "/loss-prevention/cases/" + barCase, null);
        assertThat(closed.get("state").asString()).isEqualTo("closed");
        assertThat(closed.get("outcome").asString()).isEqualTo("false_alarm");
        assertThat(closed.get("closedBy").isNull()).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("the sweep of this ERP's own records runs every source without an error or a refused event")
    void projection() {
        JsonNode r = ok(201, "POST", "/loss-prevention/projection/run", null);
        JsonNode sources = r.get("projection");
        assertThat(sources.size()).isEqualTo(8);
        sources.forEach(s -> {
            assertThat(s.get("error").isNull()).as(s.toString()).isTrue();
            assertThat(s.get("rejected").asInt()).as(s.toString()).isZero();
        });
        // A second sweep re-reads the overlap; what it reads again is a duplicate, never an error.
        JsonNode again = ok(201, "POST", "/loss-prevention/projection/run", null).get("projection");
        again.forEach(s -> assertThat(s.get("error").isNull()).as(s.toString()).isTrue());
    }

    @Test
    @Order(9)
    @DisplayName("dashboard, reports and settings answer; settings refuse an unknown key")
    void reportsAndSettings() {
        assertThat(ok(200, "GET", "/loss-prevention/dashboard", null).get("totals").get("open").asInt())
                .isGreaterThanOrEqualTo(1);
        JsonNode perf = ok(200, "GET", "/loss-prevention/reports/performance?" + period(), null);
        assertThat(perf.get("summary").get("generated").asInt()).isEqualTo(2);
        assertThat(perf.get("summary").get("falseAlarm").asInt()).isEqualTo(1);
        assertThat(perf.get("disclaimer").asString()).contains("not a finding of financial loss");
        JsonNode people = ok(200, "GET", "/loss-prevention/reports/by-cashier?" + period(), null);
        assertThat(people.get("rows").size()).isEqualTo(1);
        assertThat(call("GET", "/loss-prevention/reports/by-cashier?" + period(), null, cashierToken).body()
                .get("rows").size()).isZero();
        JsonNode barReport = ok(200, "GET", "/loss-prevention/reports/bar-variance?" + period(), null);
        assertThat(barReport.get("rows").size()).isEqualTo(1);
        assertThat(as("GET", "/loss-prevention/reports/performance?from=nope", null).status()).isEqualTo(400);

        assertThat(ok(200, "GET", "/loss-prevention/settings", null).get("defaults").get("cashierSettleMinutes")
                .asInt()).isEqualTo(10);
        assertThat(as("PUT", "/loss-prevention/settings", Map.of("values", Map.of("bogus", 1))).status())
                .isEqualTo(400);
        JsonNode saved = ok(200, "PUT", "/loss-prevention/settings", Map.of("values", Map.of(
                "cashierSettleMinutes", 15)));
        assertThat(saved.get("effective").get("cashierSettleMinutes").asInt()).isEqualTo(15);
        ok(200, "PUT", "/loss-prevention/settings", Map.of("values", Map.of()));
    }

    @Test
    @Order(10)
    @DisplayName("every route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(LossPreventionRuleController.class, LossPreventionRunController.class,
                LossPreventionCaseController.class, LossPreventionReportController.class)).isEqualTo(24);
    }
}
