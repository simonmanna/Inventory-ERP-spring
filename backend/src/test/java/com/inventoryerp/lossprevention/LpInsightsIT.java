package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpInsightsService;
import com.inventoryerp.lossprevention.web.LossPreventionInsightsController;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Operations insights (LP6a): events in the ledger → dirty day → computed summaries → the eight metrics, each checked
 * against figures worked out by hand. A late event recomputes its day; per-person figures need lp:investigate.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpInsightsIT extends ApiTestSupport {

    @Autowired
    LpInsightsService insights;

    final String source = "ins-" + UUID.randomUUID().toString().substring(0, 8);
    /** A business day nobody else uses: the DEMO organization is shared between runs. */
    // A past day within the ledger's 24-month retention, in a range no other loss-prevention suite uses.
    final LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(523 + (long) (Math.random() * 100));
    String branch;
    String cashier;
    String roleName;
    String viewerToken;
    String tableId;
    String productId;

    String branch() {
        if (branch == null) {
            branch = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " ORDER BY id LIMIT 1").param(demoOrg).query(String.class).single();
        }
        return branch;
    }

    Map<String, Object> event(String type, String sourceEventId, String time, String level, String evidence,
            Map<String, Object> ids, Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", source);
        e.put("sourceEventId", sourceEventId);
        e.put("branchId", branch());
        e.put("actorId", cashier);
        e.put("businessDate", day.toString());
        e.put("occurredAt", day + "T" + time + "Z");
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.putAll(ids);
        e.put("payload", payload);
        e.put("evidenceLevel", level);
        e.put("evidenceSource", evidence);
        e.put("observationStatus", "observed");
        return e;
    }

    void ingest(List<Map<String, Object>> events) {
        JsonNode r = ok(201, "POST", "/loss-prevention/events", Map.of("events", events));
        assertThat(r.get("accepted").asInt()).as(r.toString()).isEqualTo(events.size());
    }

    /** Lets the day settle at once and runs what the job runs. */
    void compute() {
        jdbc.sql("UPDATE \"LpMetricDay\" SET \"dirtyAt\" = \"dirtyAt\" - INTERVAL '1 hour' WHERE \"organizationId\" = ?"
                + " AND \"businessDate\" BETWEEN ? AND ?").params(demoOrg, java.sql.Date.valueOf(day.minusDays(1)),
                java.sql.Date.valueOf(day)).update();
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> insights.computeDue());
    }

    String state() {
        return jdbc.sql("SELECT state FROM \"LpMetricDay\" WHERE \"organizationId\" = ? AND \"branchId\" = ? AND"
                + " \"businessDate\" = ?").params(demoOrg, branch(), java.sql.Date.valueOf(day)).query(String.class)
                .single();
    }

    String q() {
        return "from=" + day + "&to=" + day + "&branchId=" + branch();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    @Test
    @Order(1)
    @DisplayName("ledger events make their day dirty, and the job computes the eight metrics")
    void computesTheEightMetrics() {
        String role = UUID.randomUUID().toString();
        roleName = "LP-insights-" + role.substring(0, 8);
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, roleName, new String[] {"pos:sell"}).update();
        cashier = createUser(demoOrg, "cashier-" + source + "@demo.test", "Cashier@12345", role);
        tableId = "table-" + source;
        productId = "product-" + source;

        List<Map<String, Object>> events = new ArrayList<>();
        // Sales: 100 (table), 50 after a discount of 10 (table), 30. Items 2 + 1 + 3.
        events.add(event("sale.completed", "sale-1", "09:15:00", "order", "pos", Map.of("transactionId", "s1"),
                Map.of("total", "100", "discountTotal", "0", "tableId", tableId, "lines", List.of(Map.of("quantity",
                        "2")))));
        events.add(event("sale.completed", "sale-2", "10:20:00", "order", "pos", Map.of("transactionId", "s2"),
                Map.of("total", "50", "discountTotal", "10", "tableId", tableId, "lines", List.of(Map.of("quantity",
                        1)))));
        events.add(event("sale.completed", "sale-3", "10:40:00", "order", "pos", Map.of("transactionId", "s3"),
                Map.of("total", 30, "lines", List.of(Map.of("quantity", "1.5"), Map.of("quantity", "1.5")))));
        // A line voided in two steps: the larger counts once.
        events.add(event("sale.line_voided", "void-1a", "10:21:00", "item", "pos", Map.of("lineId", "line-1"),
                Map.of("quantity", "1", "firedQuantity", "0", "amount", "5")));
        events.add(event("sale.line_voided", "void-1b", "10:22:00", "item", "pos", Map.of("lineId", "line-1"),
                Map.of("quantity", "2", "firedQuantity", "0", "amount", "10")));
        events.add(event("refund.issued", "refund-1", "11:00:00", "order", "pos", Map.of(), Map.of("amount", "20")));
        // Kitchen: ticket 1 ready after 300 s, dispatched after 480 s; ticket 2 ready after 600 s and recalled.
        events.add(event("kitchen.ticket_created", "k1-created", "10:00:00", "ticket", "kds", Map.of("ticketId",
                "t1-" + source), Map.of("station", "grill")));
        events.add(event("kitchen.stage", "k1-prepared", "10:05:00", "ticket", "kds", Map.of("ticketId", "t1-"
                + source), Map.of("stage", "prepared", "station", "grill")));
        events.add(event("kitchen.stage", "k1-dispatched", "10:08:00", "ticket", "kds", Map.of("ticketId", "t1-"
                + source), Map.of("stage", "dispatched", "station", "grill")));
        events.add(event("kitchen.ticket_created", "k2-created", "11:00:00", "ticket", "kds", Map.of("ticketId",
                "t2-" + source), Map.of("station", "grill")));
        events.add(event("kitchen.stage", "k2-prepared", "11:10:00", "ticket", "kds", Map.of("ticketId", "t2-"
                + source), Map.of("stage", "prepared", "station", "grill")));
        events.add(event("kitchen.stage", "k2-remade", "11:12:00", "ticket", "kds", Map.of("ticketId", "t2-"
                + source), Map.of("stage", "remade", "station", "grill", "recallCount", 1)));
        // Cash: one balanced session; one closed short by 7.50, reopened and closed again short by 5.
        events.add(event("cash_session.closed", "cs-1", "18:00:00", "session", "pos", Map.of("sessionId", "cs1-"
                + source), Map.of("difference", "0")));
        events.add(event("cash_session.closed", "cs-2a", "18:05:00", "session", "pos", Map.of("sessionId", "cs2-"
                + source), Map.of("difference", "-7.50")));
        events.add(event("cash_session.closed", "cs-2b", "18:30:00", "session", "pos", Map.of("sessionId", "cs2-"
                + source), Map.of("difference", "-5")));
        // Bar: sold 700 ml, 50 ml short → consumed 750 ml, yield 700/750, cost 750 × 0.1. Sent twice: the last wins.
        events.add(event("stock_count.line", "count-1a", "23:00:00", "item", "stock", Map.of("lineId", "cl-"
                + source), Map.of("productId", productId, "systemMl", "1000", "countedMl", "900", "varianceMl",
                "-100", "soldMl", "600", "costPerMl", "0.1")));
        events.add(event("stock_count.line", "count-1b", "23:05:00", "item", "stock", Map.of("lineId", "cl-"
                + source), Map.of("productId", productId, "systemMl", "1000", "countedMl", "950", "varianceMl",
                "-50", "soldMl", "700", "costPerMl", "0.1")));
        ingest(events);
        assertThat(state()).isEqualTo("dirty");

        compute();
        assertThat(state()).isEqualTo("computed");

        JsonNode s = ok(200, "GET", "/loss-prevention/insights/summary?" + q(), null);
        JsonNode m = s.get("metrics");
        JsonNode sales = m.get("sales");
        assertThat(sales.get("count").asLong()).isEqualTo(3);
        assertThat(dec(sales.get("total"))).isEqualByComparingTo("180");
        assertThat(dec(sales.get("averageTicket"))).isEqualByComparingTo("60");
        assertThat(dec(sales.get("itemsPerSale"))).isEqualByComparingTo("2");
        assertThat(sales.get("voids").get("count").asLong()).isEqualTo(1);
        assertThat(dec(sales.get("voids").get("amount"))).isEqualByComparingTo("10");
        assertThat(dec(sales.get("refunds").get("amount"))).isEqualByComparingTo("20");
        assertThat(dec(sales.get("refunds").get("amountRate"))).isEqualByComparingTo("0.1111");
        assertThat(sales.get("discounts").get("sales").asLong()).isEqualTo(1);
        // 10 ÷ (180 + 10)
        assertThat(dec(sales.get("discounts").get("rate"))).isEqualByComparingTo("0.0526");

        JsonNode kitchen = m.get("kitchen");
        assertThat(kitchen.get("tickets").asLong()).isEqualTo(2);
        assertThat(dec(kitchen.get("averagePrepSeconds"))).isEqualByComparingTo("450");
        assertThat(dec(kitchen.get("averageServeSeconds"))).isEqualByComparingTo("480");
        assertThat(dec(kitchen.get("recallRate"))).isEqualByComparingTo("0.5");

        JsonNode tables = m.get("tables");
        assertThat(tables.get("orders").asLong()).isEqualTo(2);
        assertThat(tables.get("tableDays").asLong()).isEqualTo(1);
        assertThat(dec(tables.get("turnsPerTablePerDay"))).isEqualByComparingTo("2");

        // sale-3 has no tableId field, like a sale projected before LP6a: turnover is partial, not historical.
        JsonNode cover = s.get("coverage");
        assertThat(cover.get("sales").get("daysWithData").asLong()).isEqualTo(1);
        assertThat(cover.get("sales").get("historical").asBoolean()).isTrue();
        JsonNode tableCover = cover.get("tables");
        assertThat(tableCover.get("sales").asLong()).isEqualTo(3);
        assertThat(tableCover.get("salesRecordingTable").asLong()).isEqualTo(2);
        assertThat(dec(tableCover.get("recordedShare"))).isEqualByComparingTo("0.6667");
        assertThat(tableCover.get("partial").asBoolean()).isTrue();
        assertThat(tableCover.get("historical").asBoolean()).isFalse();

        JsonNode cash = m.get("cash");
        assertThat(cash.get("sessions").asLong()).isEqualTo(2);
        assertThat(cash.get("balanced").asLong()).isEqualTo(1);
        assertThat(cash.get("short").asLong()).isEqualTo(1);
        assertThat(dec(cash.get("shortAmount"))).isEqualByComparingTo("-5");
        assertThat(dec(cash.get("balancedRate"))).isEqualByComparingTo("0.5");

        JsonNode pour = m.get("pour");
        assertThat(pour.get("lines").asLong()).isEqualTo(1);
        assertThat(dec(pour.get("consumedMl"))).isEqualByComparingTo("750");
        assertThat(dec(pour.get("yield"))).isEqualByComparingTo("0.9333");
        assertThat(dec(pour.get("consumedCost"))).isEqualByComparingTo("75");

        assertThat(s.get("trend")).hasSize(1);
        assertThat(s.get("definitions").has("kitchen_prep")).isTrue();
        assertThat(s.get("previous").get("metrics").get("sales").get("count").asLong()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("hourly, kitchen, table and pour views break the same figures down")
    void breakdowns() {
        JsonNode hourly = ok(200, "GET", "/loss-prevention/insights/hourly?" + q(), null).get("rows");
        assertThat(hourly).hasSize(24);
        // Business time zone is UTC in tests: sales 2 and 3 at 10:xx, ticket 1 created at 10:00.
        assertThat(hourly.get(10).get("sales").asLong()).isEqualTo(2);
        assertThat(hourly.get(10).get("kitchenTickets").asLong()).isEqualTo(1);
        assertThat(dec(hourly.get(10).get("averagePrepSeconds"))).isEqualByComparingTo("300");

        JsonNode stations = ok(200, "GET", "/loss-prevention/insights/kitchen?" + q(), null).get("rows");
        assertThat(stations).hasSize(1);
        assertThat(stations.get(0).get("station").asString()).isEqualTo("grill");
        assertThat(dec(stations.get(0).get("worstDayP90PrepSeconds"))).isEqualByComparingTo("570");

        JsonNode tables = ok(200, "GET", "/loss-prevention/insights/tables?" + q(), null).get("rows");
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).get("tableId").asString()).isEqualTo(tableId);
        assertThat(dec(tables.get(0).get("averagePerOrder"))).isEqualByComparingTo("75");

        JsonNode pour = ok(200, "GET", "/loss-prevention/insights/pour?" + q(), null).get("rows");
        assertThat(pour).hasSize(1);
        assertThat(pour.get(0).get("productId").asString()).isEqualTo(productId);
    }

    @Test
    @Order(3)
    @DisplayName("staff figures are per role for lp:view; per person needs lp:investigate")
    void staffByRoleAndPerson() {
        JsonNode roles = ok(200, "GET", "/loss-prevention/insights/staff?" + q(), null);
        assertThat(roles.get("groupBy").asString()).isEqualTo("role");
        JsonNode row = null;
        for (JsonNode r : roles.get("rows")) {
            if (roleName.equals(r.get("role").asString())) {
                row = r;
            }
        }
        assertThat(row).as(roles.toString()).isNotNull();
        assertThat(row.get("people").asLong()).isEqualTo(1);
        assertThat(row.get("count").asLong()).isEqualTo(3);
        assertThat(row.has("actorId")).isFalse();

        JsonNode people = ok(200, "GET", "/loss-prevention/insights/staff?groupBy=person&" + q(), null);
        assertThat(people.get("rows").get(0).get("actorId").asString()).isEqualTo(cashier);

        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES"
                + " (?, ?, ?, false, ?, now())").params(role, demoOrg, "LP-viewer-" + role, new String[] {
                    Permissions.LOSS_PREVENTION__VIEW}).update();
        String email = "viewer-" + source + "@demo.test";
        createUser(demoOrg, email, "Viewer@12345", role);
        viewerToken = login("DEMO", email, "Viewer@12345");
        assertThat(call("GET", "/loss-prevention/insights/staff?" + q(), null, viewerToken).status()).isEqualTo(200);
        Res denied = call("GET", "/loss-prevention/insights/staff?groupBy=person&" + q(), null, viewerToken);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.raw().body()).contains("Figures per person need the lp:investigate permission");
        assertThat(call("GET", "/loss-prevention/insights/staff?groupBy=shift&" + q(), null, adminToken).status())
                .isEqualTo(400);
    }

    @Test
    @Order(4)
    @DisplayName("a late event makes its day dirty again and the next computation includes it")
    void lateEventRecomputes() {
        ingest(List.of(event("sale.completed", "sale-4", "12:00:00", "order", "pos", Map.of("transactionId", "s4"),
                Map.of("total", "20"))));
        assertThat(state()).isEqualTo("dirty");
        JsonNode before = ok(200, "GET", "/loss-prevention/insights/summary?" + q(), null);
        assertThat(before.get("status").get("pendingDays").asLong()).isGreaterThanOrEqualTo(1);
        compute();
        JsonNode sales = ok(200, "GET", "/loss-prevention/insights/summary?" + q(), null).get("metrics").get("sales");
        assertThat(sales.get("count").asLong()).isEqualTo(4);
        assertThat(dec(sales.get("total"))).isEqualByComparingTo("200");
    }

    @Test
    @Order(5)
    @DisplayName("recompute marks the days of a period dirty, within limits and permissions")
    void recompute() {
        JsonNode r = ok(200, "POST", "/loss-prevention/insights/recompute", Map.of("from", day.toString(), "to",
                day.toString(), "branchId", branch()));
        assertThat(r.get("days").asInt()).isEqualTo(1);
        assertThat(state()).isEqualTo("dirty");
        compute();
        assertThat(state()).isEqualTo("computed");
        assertThat(call("POST", "/loss-prevention/insights/recompute", Map.of("from", day.toString(), "to",
                day.plusDays(100).toString()), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/loss-prevention/insights/recompute", Map.of("from", "yesterday", "to",
                day.toString()), adminToken).status()).isEqualTo(400);
        assertThat(call("POST", "/loss-prevention/insights/recompute", Map.of("from", day.toString(), "to",
                day.toString()), viewerToken).status()).isEqualTo(403);
    }

    @Test
    @Order(6)
    @DisplayName("every insights route needs its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(LossPreventionInsightsController.class)).isEqualTo(10);
    }
}
