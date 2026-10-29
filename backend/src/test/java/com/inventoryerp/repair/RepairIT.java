package com.inventoryerp.repair;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.repair.application.RepairContractService;
import com.inventoryerp.repair.application.RepairCronWorker;
import com.inventoryerp.repair.application.RepairDb;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Repair & maintenance (RMMS) on Testcontainers Postgres (ENABLE_REPAIR on in the test profile): labour catalog and
 * technicians, the order lifecycle with status history, diagnosis and quotations (approve / reject / revise), jobs,
 * parts reserve / issue through the stock engine, billing through the Order → Invoice spine, warranties and claims,
 * contracts and schedules, the housekeeping cron, reports, tenant isolation and the 401/403 matrix (64 routes).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairIT extends ApiTestSupport {

    @Autowired
    RepairDb repairDb;

    @Autowired
    SequenceService sequence;

    @Autowired
    RepairContractService contractService;

    String sfx;
    String serviceProductId;
    String partProductId;
    String customerId;
    String warehouseId;
    String adminId;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        serviceProductId = ok(201, "POST", "/products", Map.of("code", "RPR-SVC-" + sfx, "name", "Bench labour " + sfx,
                "productType", "service", "salesPrice", 40, "costPrice", 0)).get("id").asString();
        partProductId = ok(201, "POST", "/products", Map.of("code", "RPR-PART-" + sfx, "name", "Screen " + sfx,
                "productType", "stockable", "salesPrice", 120, "costPrice", 50)).get("id").asString();
        customerId = ok(201, "POST", "/partners", Map.of("code", "RPR-C-" + sfx, "name", "Repair Cust " + sfx,
                "isCustomer", true)).get("id").asString();
        warehouseId = pinPosWarehouse();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", "repair-it-stock-" + sfx);
        Res in = call("POST", "/inventory/direct-stock/in", Map.of("locationId", warehouseId,
                "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of("productId",
                        partProductId, "quantity", 10, "unitCost", 50))), h);
        assertThat(in.status()).as(in.raw().body()).isIn(200, 201);
    }

    JsonNode order(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("itemType", "Phone", "brand", "Acme", "serialNumber",
                "SN-" + UUID.randomUUID().toString().substring(0, 8)));
        body.putAll(extra);
        return ok(201, "POST", "/repair/orders", body);
    }

    JsonNode move(String id, String status) {
        return ok(201, "POST", "/repair/orders/" + id + "/transition", Map.of("status", status));
    }

    void walk(String id, String... statuses) {
        for (String s : statuses) {
            move(id, s);
        }
    }

    String status(String orderId) {
        return jdbc.sql("SELECT status::text FROM \"RepairOrder\" WHERE id = ?").param(orderId).query(String.class)
                .single();
    }

    JsonNode labourType(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("code", "LAB-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Labour " + UUID.randomUUID().toString().substring(0, 6), "price", 25));
        body.putAll(extra);
        return ok(201, "POST", "/repair/labour-types", body);
    }

    static final String KEY_REQUIRED =
            "An Idempotency-Key header is required for this operation so a retry can never charge twice.";

    /** A money route call with an {@code Idempotency-Key} ({@code null} = no header). */
    Res money(String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call(method, path, body, h);
    }

    static String key() {
        return "repair-it-" + UUID.randomUUID();
    }

    /** Debits = credits over the journal entries of {@code sourceType/sourceId}; returns the debit total. */
    BigDecimal assertJournalBalanced(String sourceType, String sourceId) {
        Map<String, Object> row = jdbc.sql("SELECT coalesce(sum(l.debit), 0) AS d, coalesce(sum(l.credit), 0) AS c,"
                        + " count(*) AS n FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                        + " WHERE e.\"organizationId\" = ? AND e.\"sourceType\" = ? AND e.\"sourceId\" = ?")
                .params(demoOrg, sourceType, sourceId).query().singleRow();
        assertThat(((Number) row.get("n")).longValue()).as(sourceType + " " + sourceId).isPositive();
        assertThat((BigDecimal) row.get("d")).isEqualByComparingTo((BigDecimal) row.get("c"));
        assertThat((BigDecimal) row.get("d")).isPositive();
        return (BigDecimal) row.get("d");
    }

    /** The journal entry referenced by {@code table.journalEntryId}: posted, debits = credits; returns the total. */
    BigDecimal assertEntryBalanced(String table, String id) {
        Map<String, Object> row = jdbc.sql("SELECT coalesce(sum(l.debit), 0) AS d, coalesce(sum(l.credit), 0) AS c,"
                        + " count(*) AS n FROM \"JournalLine\" l JOIN \"" + table + "\" x ON x.\"journalEntryId\" ="
                        + " l.\"journalEntryId\" WHERE x.id = ? AND x.\"organizationId\" = ?")
                .params(id, demoOrg).query().singleRow();
        assertThat(((Number) row.get("n")).longValue()).as(table + " " + id).isPositive();
        assertThat((BigDecimal) row.get("d")).isEqualByComparingTo((BigDecimal) row.get("c"));
        return (BigDecimal) row.get("d");
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    @DisplayName("catalog: labour types and technicians — defaults, filters, update, soft delete, 404")
    void catalog() {
        JsonNode lt = labourType(Map.of("isActive", false));
        String ltId = lt.get("id").asString();
        assertThat(lt.get("durationMinutes").asInt()).isEqualTo(60);
        assertThat(lt.get("price").asString()).isEqualTo("25");
        assertThat(ok(200, "GET", "/repair/labour-types?isActive=false", null).toString()).contains(ltId);
        assertThat(ok(200, "GET", "/repair/labour-types?isActive=true", null).toString()).doesNotContain(ltId);
        assertThat(ok(200, "GET", "/repair/labour-types?isActive=all", null).toString()).contains(ltId);
        assertThat(ok(200, "PATCH", "/repair/labour-types/" + ltId, Map.of("price", "30.5")).get("price").asString())
                .isEqualTo("30.5");
        assertThat(ok(200, "DELETE", "/repair/labour-types/" + ltId, null).get("deletedAt").isNull()).isFalse();
        Res gone = as("PATCH", "/repair/labour-types/" + ltId, Map.of("price", 1));
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("Record not found");
        assertThat(as("DELETE", "/repair/labour-types/" + ltId, null).status()).isEqualTo(404);

        JsonNode tech = ok(201, "POST", "/repair/technicians", Map.of("name", "Zed " + sfx, "skills", List.of("phones"),
                "hourlyRate", 12));
        String techId = tech.get("id").asString();
        assertThat(tech.get("code").asString()).startsWith("TECH-");
        assertThat(tech.get("availability").asString()).isEqualTo("available");
        assertThat(tech.get("skills").get(0).asString()).isEqualTo("phones");
        assertThat(ok(201, "POST", "/repair/technicians", Map.of("name", "Coded", "code", "T-" + sfx))
                .get("code").asString()).isEqualTo("T-" + sfx);
        assertThat(ok(200, "PATCH", "/repair/technicians/" + techId, Map.of("availability", "busy"))
                .get("availability").asString()).isEqualTo("busy");
        assertThat(ok(200, "GET", "/repair/technicians?availability=busy&isActive=true", null).toString())
                .contains(techId);
        assertThat(ok(200, "DELETE", "/repair/technicians/" + techId, null).get("id").asString()).isEqualTo(techId);
        assertThat(ok(200, "GET", "/repair/technicians", null).toString()).doesNotContain(techId);
        assertThat(as("PATCH", "/repair/technicians/" + techId, Map.of()).status()).isEqualTo(404);
        assertThat(as("DELETE", "/repair/technicians/" + techId, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("orders: create with items, history, list/search, items, attachments, notes, transitions, assign")
    void orders() {
        JsonNode o = order(Map.of("items", List.of(Map.of("itemType", "Charger"), Map.of("brand", "B2",
                "accessories", List.of("cable"))), "dueDate", "2026-12-01", "accessories", List.of("case")));
        String id = o.get("id").asString();
        assertThat(o.get("repairNumber").asString()).startsWith("RPR-");
        assertThat(o.get("status").asString()).isEqualTo("received");
        assertThat(o.get("priority").asString()).isEqualTo("medium");
        assertThat(o.get("orderType").asString()).isEqualTo("repair");
        assertThat(o.get("accessories").get(0).asString()).isEqualTo("case");
        assertThat(o.get("items")).hasSize(2);
        assertThat(o.get("items").get(1).get("lineNumber").asInt()).isEqualTo(1);
        assertThat(o.get("items").get(1).get("accessories").get(0).asString()).isEqualTo("cable");
        assertThat(o.get("statusHistory").get(0).get("note").asString()).isEqualTo("Order received");
        for (String k : List.of("diagnosis", "quotations", "jobs", "parts", "attachments", "warranties")) {
            assertThat(o.get(k).isArray()).as(k).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title = ?")
                .params(demoOrg, "Repair order " + o.get("repairNumber").asString()).query(Long.class).single())
                .isEqualTo(1);

        JsonNode list = ok(200, "GET", "/repair/orders?search=" + o.get("serialNumber").asString().toLowerCase(), null);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("items")).hasSize(2);
        assertThat(ok(200, "GET", "/repair/orders?status=received&priority=medium&orderType=repair&take=500", null)
                .get("rows").size()).isPositive();
        assertThat(as("GET", "/repair/orders?take=abc", null).status()).isEqualTo(500);

        JsonNode item = ok(201, "POST", "/repair/orders/" + id + "/items", Map.of("itemType", "Case", "color", "red"));
        assertThat(item.get("lineNumber").asInt()).isEqualTo(2);
        assertThat(ok(200, "DELETE", "/repair/orders/items/" + item.get("id").asString(), null).get("count").asLong())
                .isEqualTo(1);
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/items", Map.of()).body().get("message")
                .asString()).isEqualTo("Repair order not found");

        JsonNode att = ok(201, "POST", "/repair/orders/" + id + "/attachments", Map.of("url", "https://x/y.png"));
        assertThat(att.get("kind").asString()).isEqualTo("receipt");
        assertThat(ok(200, "DELETE", "/repair/orders/attachments/" + att.get("id").asString(), null).get("count")
                .asLong()).isEqualTo(1);

        Res noNote = as("POST", "/repair/orders/" + id + "/notes", Map.of("note", "  "));
        assertThat(noNote.status()).isEqualTo(400);
        assertThat(noNote.body().get("message").asString()).isEqualTo("Note is required");
        JsonNode note = ok(201, "POST", "/repair/orders/" + id + "/notes", Map.of("note", "Customer called"));
        assertThat(note.get("action").asString()).isEqualTo("note");
        assertThat(note.get("toStatus").asString()).isEqualTo("received");

        Res noStatus = as("POST", "/repair/orders/" + id + "/transition", Map.of());
        assertThat(noStatus.status()).isEqualTo(400);
        assertThat(noStatus.body().get("message").asString()).isEqualTo("status is required");
        JsonNode diag = ok(201, "POST", "/repair/orders/" + id + "/transition", Map.of("to", "diagnosis",
                "note", "bench"));
        assertThat(diag.get("status").asString()).isEqualTo("diagnosis");
        assertThat(diag.get("diagnosedAt").isNull()).isFalse();
        Map<String, Object> hist = jdbc.sql("SELECT \"fromStatus\", action FROM \"RepairStatusHistory\""
                + " WHERE \"repairOrderId\" = ? AND \"toStatus\" = 'diagnosis'").param(id).query().singleRow();
        assertThat(hist.get("fromStatus")).isEqualTo("received");
        assertThat(hist.get("action")).isEqualTo("diagnose");
        assertThat(as("POST", "/repair/orders/" + id + "/transition", Map.of("status", "closed")).status())
                .isEqualTo(500);
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/transition", Map.of("status", "cancelled"))
                .status()).isEqualTo(404);

        String tech = ok(201, "POST", "/repair/technicians", Map.of("name", "Assignee")).get("id").asString();
        assertThat(ok(200, "PATCH", "/repair/orders/" + id + "/technician", Map.of("technicianId", tech))
                .get("technicianId").asString()).isEqualTo(tech);
        assertThat(as("PATCH", "/repair/orders/" + UUID.randomUUID() + "/technician", Map.of("technicianId", tech))
                .status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/repair/orders?technicianId=" + tech, null).get("total").asLong()).isEqualTo(1);

        JsonNode cancelled = ok(201, "POST", "/repair/orders/" + id + "/transition", Map.of("status", "cancelled",
                "extra", Map.of("notes", "gave up")));
        assertThat(cancelled.get("cancelledAt").isNull()).isFalse();
        assertThat(cancelled.get("notes").asString()).isEqualTo("gave up");
        assertThat(as("GET", "/repair/orders/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Repair order not found");

        String withPartner = order(Map.of("partnerId", customerId)).get("id").asString();
        move(withPartner, "diagnosis");
        assertThat(jdbc.sql("SELECT body FROM \"Notification\" WHERE \"organizationId\" = ?"
                + " AND payload->>'repairOrderId' = ? AND title LIKE '%→ diagnosis'").params(demoOrg, withPartner)
                .query(String.class).single())
                .isEqualTo("Repair Cust " + sfx + " — undefined");
    }

    @Test
    @DisplayName("diagnosis and quotations: pricing from catalog, approve moves the order, reject, revise, delete")
    void quotations() {
        String id = order(Map.of()).get("id").asString();
        JsonNode d = ok(201, "POST", "/repair/orders/" + id + "/diagnosis", Map.of("symptoms", "No power",
                "faults", List.of("PSU"), "estimatedCost", 80));
        assertThat(d.get("faults").get(0).asString()).isEqualTo("PSU");
        assertThat(ok(200, "GET", "/repair/orders/" + id, null).get("diagnosisId").asString())
                .isEqualTo(d.get("id").asString());
        assertThat(ok(200, "PATCH", "/repair/diagnosis/" + d.get("id").asString(), Map.of("rootCause", "Fuse"))
                .get("rootCause").asString()).isEqualTo("Fuse");
        assertThat(as("PATCH", "/repair/diagnosis/" + UUID.randomUUID(), Map.of()).body().get("message").asString())
                .isEqualTo("Record not found");

        String ltId = labourType(Map.of("price", 30, "name", "Bench " + sfx)).get("id").asString();
        Res badQty = as("POST", "/repair/orders/" + id + "/quotations", Map.of("lines",
                List.of(Map.of("kind", "labour", "quantity", 0))));
        assertThat(badQty.status()).isEqualTo(400);
        assertThat(badQty.body().get("message").asString()).isEqualTo("Line 1: quantity must be positive");
        JsonNode q = ok(201, "POST", "/repair/orders/" + id + "/quotations", Map.of("validUntil", "2026-12-31",
                "lines", List.of(Map.of("kind", "labour", "labourTypeId", ltId, "quantity", 1.5),
                        Map.of("kind", "part", "productId", partProductId, "quantity", 1),
                        Map.of("kind", "part", "description", "Glue", "unitPrice", 2.333, "quantity", 3))));
        String qid = q.get("id").asString();
        assertThat(q.get("quotationNumber").asString()).startsWith("QT-");
        assertThat(q.get("status").asString()).isEqualTo("draft");
        assertThat(q.get("lines")).hasSize(3);
        assertThat(q.get("lines").get(0).get("description").asString()).isEqualTo("Bench " + sfx);
        assertThat(q.get("lines").get(0).get("amount").asString()).isEqualTo("45");
        assertThat(q.get("lines").get(1).get("description").asString()).isEqualTo("Screen " + sfx);
        assertThat(q.get("lines").get(1).get("unitPrice").asString()).isEqualTo("120");
        assertThat(q.get("lines").get(2).get("amount").asString()).isEqualTo("7");
        assertThat(q.get("labourTotal").asString()).isEqualTo("45");
        assertThat(q.get("partsTotal").asString()).isEqualTo("127");
        assertThat(q.get("totalAmount").asString()).isEqualTo("172");
        assertThat(ok(200, "GET", "/repair/orders/" + id, null).get("quotationId").asString()).isEqualTo(qid);
        assertThat(ok(200, "GET", "/repair/quotations/" + qid, null).get("lines")).hasSize(3);
        assertThat(ok(200, "GET", "/repair/quotations?repairOrderId=" + id + "&status=draft", null)).hasSize(1);
        assertThat(as("GET", "/repair/quotations/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Quotation not found");

        assertThat(ok(201, "POST", "/repair/quotations/" + qid + "/submit", null).get("status").asString())
                .isEqualTo("pending");
        walk(id, "diagnosis", "waiting_approval");
        JsonNode approved = ok(201, "POST", "/repair/quotations/" + qid + "/approve", Map.of());
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("approvedBy").asString()).isEqualTo(adminId);
        assertThat(status(id)).isEqualTo("approved");
        assertThat(jdbc.sql("SELECT note FROM \"RepairStatusHistory\" WHERE \"repairOrderId\" = ?"
                + " AND action = 'approve'")
                .param(id).query(String.class).single()).isEqualTo("Quotation " + q.get("quotationNumber").asString()
                + " approved");
        Res again = as("POST", "/repair/quotations/" + qid + "/approve", null);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Quotation is approved — only pending/draft can be approved");
        assertThat(as("DELETE", "/repair/quotations/" + qid, null).body().get("message").asString())
                .isEqualTo("Approved quotation cannot be deleted");

        JsonNode rev = ok(201, "POST", "/repair/quotations/" + qid + "/revise", Map.of("notes", "v2"));
        assertThat(rev.get("revision").asInt()).isEqualTo(2);
        assertThat(rev.get("lines")).hasSize(3);
        assertThat(rev.get("totalAmount").asString()).isEqualTo("172");
        String revId = rev.get("id").asString();
        JsonNode rejected = ok(201, "POST", "/repair/quotations/" + revId + "/reject", Map.of("reason", "too dear"));
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(rejected.get("rejectedReason").asString()).isEqualTo("too dear");
        JsonNode rev3 = ok(201, "POST", "/repair/quotations/" + revId + "/revise", Map.of("lines",
                List.of(Map.of("description", "Flat fee", "unitPrice", 10))));
        assertThat(rev3.get("revision").asInt()).isEqualTo(3);
        assertThat(rev3.get("lines").get(0).get("kind").asString()).isEqualTo("labour");
        assertThat(ok(200, "DELETE", "/repair/quotations/" + rev3.get("id").asString(), null).get("status").asString())
                .isEqualTo("cancelled");
        for (String p : List.of("/submit", "/approve", "/reject", "/revise")) {
            assertThat(as("POST", "/repair/quotations/" + UUID.randomUUID() + p, Map.of()).status()).as(p)
                    .isEqualTo(404);
        }
        assertThat(as("DELETE", "/repair/quotations/" + UUID.randomUUID(), null).status()).isEqualTo(404);

        // Nest asserts waiting_approval --revision--> diagnosis, which the lifecycle does not define: 500 and the
        // rejection rolls back with the order.
        String other = order(Map.of()).get("id").asString();
        String q2 = ok(201, "POST", "/repair/orders/" + other + "/quotations", Map.of()).get("id").asString();
        walk(other, "diagnosis", "waiting_approval");
        assertThat(as("POST", "/repair/quotations/" + q2 + "/reject", Map.of()).status()).isEqualTo(500);
        assertThat(jdbc.sql("SELECT status::text FROM \"RepairQuotation\" WHERE id = ?").param(q2).query(String.class)
                .single()).isEqualTo("draft");
        assertThat(status(other)).isEqualTo("waiting_approval");

        move(other, "cancelled");
        Res closed = as("POST", "/repair/orders/" + other + "/diagnosis", Map.of());
        assertThat(closed.status()).isEqualTo(400);
        assertThat(closed.body().get("message").asString()).isEqualTo("Order is closed/cancelled — no new diagnosis");
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/diagnosis", Map.of()).status()).isEqualTo(404);
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/quotations", Map.of()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("jobs: create, list with order brief, update, checklist, start/test/complete, terminal guard, cancel")
    void jobs() {
        String id = order(Map.of("itemType", "Laptop")).get("id").asString();
        JsonNode job = ok(201, "POST", "/repair/orders/" + id + "/jobs", Map.of("instructions", "Replace fan",
                "deadline", "2026-12-01T10:00:00Z", "checklist", List.of(Map.of("label", "Open", "completed", false))));
        String jid = job.get("id").asString();
        assertThat(job.get("jobNumber").asString()).startsWith("JOB-");
        assertThat(job.get("status").asString()).isEqualTo("pending");
        assertThat(job.get("repairOrder").get("parts").isArray()).isTrue();
        assertThat(job.get("repairOrder").get("items").isArray()).isTrue();
        JsonNode list = ok(200, "GET", "/repair/jobs?repairOrderId=" + id, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("repairOrder").get("itemType").asString()).isEqualTo("Laptop");
        assertThat(list.get(0).get("repairOrder").has("status")).isFalse();
        assertThat(ok(200, "PATCH", "/repair/jobs/" + jid, Map.of("notes", "noisy")).get("notes").asString())
                .isEqualTo("noisy");

        JsonNode c1 = ok(201, "POST", "/repair/jobs/" + jid + "/checklist", Map.of("label", "Open", "completed", true));
        assertThat(c1.get("checklist").get(0).get("completed").asBoolean()).isTrue();
        assertThat(c1.get("checklist").get(0).get("completedAt").asString()).endsWith("Z");
        JsonNode c2 = ok(201, "POST", "/repair/jobs/" + jid + "/checklist", Map.of("label", "Clean",
                "completed", false));
        assertThat(c2.get("checklist")).hasSize(2);
        assertThat(c2.get("checklist").get(1).get("completedAt").isNull()).isTrue();

        assertThat(ok(201, "POST", "/repair/jobs/" + jid + "/start", null).get("startedAt").isNull()).isFalse();
        assertThat(ok(201, "POST", "/repair/jobs/" + jid + "/test", null).get("status").asString())
                .isEqualTo("testing");
        JsonNode done = ok(201, "POST", "/repair/jobs/" + jid + "/complete", Map.of("actualHours", 2.5));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("actualHours").asString()).isEqualTo("2.5");
        Res terminal = as("POST", "/repair/jobs/" + jid + "/start", null);
        assertThat(terminal.status()).isEqualTo(400);
        assertThat(terminal.body().get("message").asString()).isEqualTo("Job is completed — no further transitions");

        String j2 = ok(201, "POST", "/repair/orders/" + id + "/jobs", Map.of()).get("id").asString();
        JsonNode cancelled = ok(201, "POST", "/repair/jobs/" + j2 + "/cancel", Map.of("reason", "dup"));
        assertThat(cancelled.get("notes").asString()).isEqualTo("dup");
        assertThat(as("POST", "/repair/jobs/" + j2 + "/cancel", null).body().get("message").asString())
                .isEqualTo("Job is cancelled — no further transitions");
        for (String p : List.of("/start", "/complete", "/test", "/cancel", "/checklist")) {
            assertThat(as("POST", "/repair/jobs/" + UUID.randomUUID() + p, Map.of()).status()).as(p).isEqualTo(404);
        }
        assertThat(as("GET", "/repair/jobs/" + UUID.randomUUID(), null).body().get("message").asString())
                .isEqualTo("Repair job not found");
        assertThat(as("PATCH", "/repair/jobs/" + UUID.randomUUID(), Map.of()).status()).isEqualTo(404);
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/jobs", Map.of()).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/repair/jobs?status=cancelled", null).toString()).contains(j2);
    }

    @Test
    @DisplayName("parts: reserve, issue through the stock engine (move + GL source), guards, delete")
    void parts() {
        String id = order(Map.of()).get("id").asString();
        Res badQty = as("POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId, "quantity", -1));
        assertThat(badQty.body().get("message").asString()).isEqualTo("Quantity must be positive");
        assertThat(as("POST", "/repair/orders/" + id + "/parts", Map.of("productId", UUID.randomUUID().toString()))
                .body().get("message").asString()).isEqualTo("Product not found");
        assertThat(as("POST", "/repair/orders/" + UUID.randomUUID() + "/parts", Map.of()).body().get("message")
                .asString()).isEqualTo("Repair order not found");

        JsonNode part = ok(201, "POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId,
                "quantity", 2));
        String pid = part.get("id").asString();
        assertThat(part.get("status").asString()).isEqualTo("reserved");
        assertThat(part.get("unitCost").asString()).isEqualTo("50");
        assertThat(part.get("totalCost").asString()).isEqualTo("100");
        Res noLoc = as("POST", "/repair/parts/" + pid + "/issue", Map.of());
        assertThat(noLoc.body().get("message").asString()).isEqualTo("A source location is required to issue a part");

        JsonNode issued = ok(201, "POST", "/repair/parts/" + pid + "/issue", Map.of("locationId", warehouseId,
                "unitCost", 55));
        assertThat(issued.get("status").asString()).isEqualTo("issued");
        assertThat(issued.get("totalCost").asString()).isEqualTo("110");
        assertThat(issued.get("locationId").asString()).isEqualTo(warehouseId);
        Map<String, Object> mv = jdbc.sql("SELECT \"quantityChange\" AS q, abs(\"totalValue\") AS v,"
                + " \"locationId\" AS loc FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"referenceType\" = 'repair_part_issue' AND \"referenceId\" = ?").params(demoOrg, id).query()
                .singleRow();
        assertThat((BigDecimal) mv.get("q")).isEqualByComparingTo("-2");
        assertThat(mv.get("loc")).isEqualTo(warehouseId);
        // COGS at issue time: Dr expense / Cr stock valuation, balanced, for the ledger value of the relief.
        assertThat(assertJournalBalanced("repair_part_issue", id)).isEqualByComparingTo((BigDecimal) mv.get("v"));
        assertThat(as("POST", "/repair/parts/" + pid + "/issue", Map.of("locationId", warehouseId)).body()
                .get("message").asString()).isEqualTo("Part already issued");
        assertThat(as("DELETE", "/repair/parts/" + pid, null).body().get("message").asString())
                .isEqualTo("Issued parts cannot be deleted — reverse via stock adjustment");

        String loose = ok(201, "POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId,
                "locationId", warehouseId, "unitCost", 1)).get("id").asString();
        jdbc.sql("UPDATE \"RepairPart\" SET \"productId\" = NULL WHERE id = ?").param(loose).update();
        assertThat(as("POST", "/repair/parts/" + loose + "/issue", Map.of()).body().get("message").asString())
                .isEqualTo("Part has no linked product — stock cannot be relieved");
        assertThat(ok(200, "GET", "/repair/parts?repairOrderId=" + id + "&status=issued", null)).hasSize(1);
        assertThat(ok(200, "DELETE", "/repair/parts/" + loose, null).get("count").asLong()).isEqualTo(1);
        assertThat(as("DELETE", "/repair/parts/" + loose, null).body().get("message").asString())
                .isEqualTo("Repair part not found");
        assertThat(as("POST", "/repair/parts/" + loose + "/issue", Map.of()).status()).isEqualTo(404);

        // A failing stock relief (unknown location) rolls back with the part: still reserved, nothing moved.
        String bad = ok(201, "POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId))
                .get("id").asString();
        Res noLocation = as("POST", "/repair/parts/" + bad + "/issue", Map.of("locationId",
                UUID.randomUUID().toString()));
        assertThat(noLocation.status()).isEqualTo(404);
        assertThat(noLocation.body().get("message").asString()).isEqualTo("Location not found");
        assertThat(jdbc.sql("SELECT status::text FROM \"RepairPart\" WHERE id = ?").param(bad).query(String.class)
                .single()).isEqualTo("reserved");

        // Two concurrent issues of one part: the row lock lets exactly one relieve stock.
        String raced = ok(201, "POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId,
                "quantity", 1)).get("id").asString();
        List<java.util.concurrent.CompletableFuture<Res>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> as("POST", "/repair/parts/" + raced
                    + "/issue", Map.of("locationId", warehouseId))));
        }
        List<Integer> statuses = runs.stream().map(java.util.concurrent.CompletableFuture::join).map(Res::status)
                .sorted().toList();
        assertThat(statuses).containsExactly(201, 400);
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'repair_part_issue' AND \"referenceId\" = ?", demoOrg, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("billing: approved quotation → Order (repair kind, repairOrderLineId) + Invoice; balanced GL;"
            + " idempotent bill and pay; no half-commit; payment settles")
    void billing() {
        String ltId = labourType(Map.of("price", 40, "productId", serviceProductId)).get("id").asString();
        String id = order(Map.of("partnerId", customerId)).get("id").asString();
        String bill = "/repair/orders/" + id + "/bill";
        Res noKey = as("POST", bill, Map.of());
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString()).isEqualTo(KEY_REQUIRED);
        Res none = money("POST", bill, null, key());
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.body().get("message").asString())
                .isEqualTo("No approved quotation to bill — approve one first");
        String qid = ok(201, "POST", "/repair/orders/" + id + "/quotations", Map.of("lines", List.of(
                Map.of("kind", "labour", "labourTypeId", ltId, "quantity", 2),
                Map.of("kind", "part", "productId", partProductId, "unitPrice", 100)))).get("id").asString();
        ok(201, "POST", "/repair/quotations/" + qid + "/approve", Map.of());

        Res noProduct = money("POST", bill, Map.of("lines", List.of(Map.of("kind", "labour",
                "description", "Mystery " + sfx))), key());
        assertThat(noProduct.body().get("message").asString()).isEqualTo("Line \"Mystery " + sfx + "\" has no billable"
                + " product — labour types need a billing product; parts need a productId");
        Res zero = money("POST", bill, Map.of("lines", List.of(Map.of("kind", "part", "description", "P",
                "productId", partProductId, "quantity", 0))), key());
        assertThat(zero.body().get("message").asString()).isEqualTo("Line \"P\": quantity must be positive");
        assertThat(money("POST", bill, Map.of("lines", List.of()), key()).body().get("message").asString())
                .isEqualTo("No billable lines");

        // A failing invoice (invalid payment mode) rolls the whole bill back: no Order, the repair order stays
        // billable. (Nest kept the Order and marked the repair order billed.)
        assertThat(money("POST", bill, Map.of("paymentMode", "bogus"), key()).status()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM \"Order\" WHERE \"repairOrderId\" = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM \"RepairOrder\" WHERE id = ? AND \"orderId\" IS NULL"
                + " AND \"invoiceId\" IS NULL", id)).isEqualTo(1);

        String billKey = key();
        Res billedRes = money("POST", bill, Map.of("paymentMode", "credit"), billKey);
        assertThat(billedRes.status()).as(billedRes.raw().body()).isEqualTo(200);
        JsonNode billed = billedRes.body();
        String orderId = billed.get("orderId").asString();
        String invoiceId = billed.get("invoiceId").asString();
        assertThat(billed.get("order").get("transactionKind").asString()).isEqualTo("repair");
        assertThat(billed.get("order").get("orderNumber").asString()).startsWith("OR-");
        assertThat(billed.get("invoice").get("id").asString()).isEqualTo(invoiceId);
        List<Map<String, Object>> items = jdbc.sql("SELECT \"repairOrderLineId\", \"repairFeeType\", \"productId\""
                + " FROM \"OrderItem\" WHERE \"orderId\" = ? ORDER BY \"lineNumber\"").param(orderId).query()
                .listOfRows();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("repairFeeType")).isEqualTo("labour");
        assertThat(items.get(0).get("productId")).isEqualTo(serviceProductId);
        assertThat(items.get(1).get("repairOrderLineId")).isEqualTo(id);
        Map<String, Object> ro = jdbc.sql("SELECT \"orderId\", \"invoiceId\", \"labourTotal\", \"partsTotal\","
                + " \"totalAmount\" FROM \"RepairOrder\" WHERE id = ?").param(id).query().singleRow();
        assertThat(ro.get("orderId")).isEqualTo(orderId);
        assertThat(ro.get("invoiceId")).isEqualTo(invoiceId);
        assertThat((BigDecimal) ro.get("labourTotal")).isEqualByComparingTo("80");
        assertThat((BigDecimal) ro.get("partsTotal")).isEqualByComparingTo("100");
        assertThat((BigDecimal) ro.get("totalAmount")).isEqualByComparingTo("180");
        assertThat(jdbc.sql("SELECT \"repairOrderId\" FROM \"Order\" WHERE id = ?").param(orderId).query(String.class)
                .single()).isEqualTo(id);
        // Dr receivable 180 / Cr revenue 180 through the posting engine.
        assertThat(assertEntryBalanced("Invoice", invoiceId)).isEqualByComparingTo("180");

        // Replay with the same key answers the stored response; the same key with another body is refused.
        Res replay = money("POST", bill, Map.of("paymentMode", "credit"), billKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("invoiceId").asString()).isEqualTo(invoiceId);
        assertThat(money("POST", bill, Map.of("paymentMode", "cash"), billKey).status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM \"Order\" WHERE \"repairOrderId\" = ?", id)).isEqualTo(1);
        assertThat(money("POST", bill, null, key()).body().get("message").asString())
                .isEqualTo("Repair order already billed");

        String pay = "/repair/invoices/" + invoiceId + "/pay";
        Map<String, Object> tender = Map.of("tenders", List.of(Map.of("method", "bank", "amount", 180,
                "reference", "TRX-" + sfx)));
        assertThat(as("POST", pay, tender).body().get("message").asString()).isEqualTo(KEY_REQUIRED);
        String payKey = key();
        Res paid = money("POST", pay, tender, payKey);
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        assertThat(paid.body().get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(paid.body().get("invoiceId").asString()).isEqualTo(invoiceId);
        String paymentId = paid.body().get("paymentIds").get(0).asString();
        assertThat(assertEntryBalanced("Payment", paymentId)).isEqualByComparingTo("180");
        assertThat(jdbc.sql("SELECT status::text FROM \"Invoice\" WHERE id = ?").param(invoiceId).query(String.class)
                .single()).isEqualTo("paid");
        Res payReplay = money("POST", pay, tender, payKey);
        assertThat(payReplay.status()).isEqualTo(200);
        assertThat(payReplay.body().get("paymentIds").get(0).asString()).isEqualTo(paymentId);
        assertThat(count("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"invoiceId\" = ?", invoiceId))
                .isEqualTo(1);
        assertThat(money("POST", pay, tender, key()).body().get("message").asString())
                .isEqualTo("Invoice is already fully paid");
        assertThat(money("POST", "/repair/invoices/" + UUID.randomUUID() + "/pay", Map.of("paymentMethod", "bank"),
                key()).status()).isEqualTo(404);

        // walk-in: an order without a partner bills against WALKIN, the labour line resolved by name.
        String walkIn = order(Map.of()).get("id").asString();
        String name = jdbc.sql("SELECT name FROM \"RepairLabourType\" WHERE id = ?").param(ltId).query(String.class)
                .single();
        Res w = money("POST", "/repair/orders/" + walkIn + "/bill", Map.of("paymentMode", "credit",
                "lines", List.of(Map.of("kind", "labour", "description", name.toUpperCase(), "unitPrice", 5))), key());
        assertThat(w.status()).as(w.raw().body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT p.code FROM \"Order\" o JOIN \"Partner\" p ON p.id = o.\"partnerId\""
                + " WHERE o.id = ?")
                .param(w.body().get("orderId").asString()).query(String.class).single()).isEqualTo("WALKIN");
        assertEntryBalanced("Invoice", w.body().get("invoiceId").asString());

        String closed = order(Map.of()).get("id").asString();
        move(closed, "cancelled");
        assertThat(money("POST", "/repair/orders/" + closed + "/bill", null, key()).body().get("message").asString())
                .isEqualTo("Repair order is closed/cancelled — cannot bill");
        assertThat(money("POST", "/repair/orders/" + UUID.randomUUID() + "/bill", null, key()).status())
                .isEqualTo(404);

        walk(id, "diagnosis", "waiting_approval", "approved", "repairing", "testing", "ready_pickup", "delivered",
                "closed");
        JsonNode revenue = ok(200, "GET", "/repair/reports/revenue?from=2000-01-01", null);
        assertThat(revenue.toString()).contains(Instant.now().toString().substring(0, 7));
        JsonNode op = ok(200, "GET", "/repair/reports/operational?from=2000-01-01&to=2999-12-31", null);
        assertThat(op.get("closedOrders").asLong()).isPositive();
        assertThat(op.get("revenue").asDouble()).isGreaterThanOrEqualTo(180);
        assertThat(op.get("byType").isArray()).isTrue();
    }

    @Test
    @DisplayName("warranties and claims: period → coverage end on the order, claim, resolve, expired guard")
    void warranties() {
        String id = order(Map.of()).get("id").asString();
        JsonNode w = ok(201, "POST", "/repair/warranties", Map.of("repairOrderId", id, "warrantyPeriodMonths", 1,
                "coverageStart", "2026-01-31T00:00:00Z"));
        String wid = w.get("id").asString();
        assertThat(w.get("warrantyType").asString()).isEqualTo("repair_warranty");
        assertThat(w.get("coverageEnd").asString()).startsWith("2026-03-03");
        JsonNode o = ok(200, "GET", "/repair/orders/" + id, null);
        assertThat(o.get("warrantyPeriodMonths").asInt()).isEqualTo(1);
        assertThat(o.get("warrantyExpiresAt").asString()).startsWith("2026-03-03");
        assertThat(o.get("warranties").get(0).get("claims").isArray()).isTrue();

        JsonNode claim = ok(201, "POST", "/repair/warranties/" + wid + "/claims", Map.of("description", "Again broken",
                "amount", 15));
        assertThat(claim.get("status").asString()).isEqualTo("pending");
        assertThat(ok(200, "GET", "/repair/warranties?repairOrderId=" + id, null).get(0).get("status").asString())
                .isEqualTo("claimed");
        Res bad = as("PATCH", "/repair/claims/" + claim.get("id").asString(), Map.of("status", "maybe"));
        assertThat(bad.body().get("message").asString()).isEqualTo("Invalid claim status: maybe");
        assertThat(as("PATCH", "/repair/claims/" + claim.get("id").asString(), Map.of()).body().get("message")
                .asString()).isEqualTo("Invalid claim status: undefined");
        JsonNode rejected = ok(200, "PATCH", "/repair/claims/" + claim.get("id").asString(), Map.of("status",
                "rejected", "resolution", "wear"));
        assertThat(rejected.get("resolution").asString()).isEqualTo("wear");
        assertThat(ok(200, "GET", "/repair/warranties?status=active&warrantyType=repair_warranty", null).toString())
                .contains(wid);
        assertThat(as("PATCH", "/repair/claims/" + UUID.randomUUID(), Map.of("status", "settled")).body()
                .get("message").asString()).isEqualTo("Warranty claim not found");

        assertThat(ok(200, "PATCH", "/repair/warranties/" + wid, Map.of("status", "expired")).get("status").asString())
                .isEqualTo("expired");
        assertThat(as("POST", "/repair/warranties/" + wid + "/claims", Map.of()).body().get("message").asString())
                .isEqualTo("Warranty is expired — claim not accepted");
        assertThat(as("POST", "/repair/warranties/" + UUID.randomUUID() + "/claims", Map.of()).body().get("message")
                .asString()).isEqualTo("Warranty not found");
        assertThat(as("PATCH", "/repair/warranties/" + UUID.randomUUID(), Map.of()).status()).isEqualTo(404);
        JsonNode asset = ok(201, "POST", "/repair/warranties", Map.of("warrantyType", "manufacturer",
                "coverageEnd", "2027-01-01"));
        assertThat(asset.get("coverageStart").isNull()).isTrue();
    }

    @Test
    @DisplayName("contracts and schedules: CRUD, activate, cancel, soft delete; housekeeping cron; reports")
    void contractsSchedulesCron() {
        JsonNode c = ok(201, "POST", "/repair/contracts", Map.of("title", "Office AC", "partnerId", customerId,
                "endDate", Instant.now().plusSeconds(86_400 * 10).toString(), "includedLabour", List.of("service")));
        String cid = c.get("id").asString();
        assertThat(c.get("contractNumber").asString()).startsWith("CTR-");
        assertThat(c.get("status").asString()).isEqualTo("draft");
        assertThat(c.get("frequencyDays").asInt()).isEqualTo(30);
        assertThat(ok(200, "PATCH", "/repair/contracts/" + cid, Map.of("slaHours", 4)).get("updatedBy").asString())
                .isEqualTo(adminId);
        JsonNode active = ok(201, "POST", "/repair/contracts/" + cid + "/activate", null);
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/repair/contracts?status=active&partnerId=" + customerId, null)).hasSize(1);
        JsonNode overview = ok(200, "GET", "/repair/reports/contracts", null);
        assertThat(overview.get("active").asLong()).isPositive();
        assertThat(overview.get("expiringSoon").asLong()).isPositive();

        JsonNode s = ok(201, "POST", "/repair/schedules", Map.of("title", "Filter clean " + sfx, "contractId", cid,
                "intervalDays", 7, "nextDueAt", "2020-01-01T00:00:00Z"));
        String sid = s.get("id").asString();
        assertThat(s.get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", "/repair/schedules?contractId=" + cid + "&status=active", null)).hasSize(1);
        assertThat(ok(200, "PATCH", "/repair/schedules/" + sid, Map.of("taskTemplate", "Clean filters " + sfx))
                .get("taskTemplate").asString()).isEqualTo("Clean filters " + sfx);
        assertThat(ok(200, "GET", "/repair/reports/contracts", null).get("upcomingRuns").asLong()).isPositive();

        String wid = ok(201, "POST", "/repair/warranties", Map.of("coverageEnd", "2020-01-01")).get("id").asString();
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            assertThat(contractService.dueSchedules(Instant.now())).extracting(m -> m.get("id")).contains(sid);
            new RepairCronWorker(repairDb, sequence).housekeeping();
        });
        Map<String, Object> gen = jdbc.sql("SELECT \"orderType\"::text AS t, \"problemDescription\" AS p FROM"
                + " \"RepairOrder\" WHERE notes = ?").param("Auto-generated from schedule " + sid).query().singleRow();
        assertThat(gen.get("t")).isEqualTo("preventive");
        assertThat(gen.get("p")).isEqualTo("Clean filters " + sfx);
        Instant next = jdbc.sql("SELECT \"nextDueAt\" FROM \"RepairSchedule\" WHERE id = ?").param(sid)
                .query(java.sql.Timestamp.class).single().toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
        assertThat(next).isAfter(Instant.now().plusSeconds(86_400 * 6));
        assertThat(jdbc.sql("SELECT status::text FROM \"RepairWarranty\" WHERE id = ?").param(wid).query(String.class)
                .single()).isEqualTo("expired");
        assertThat(count("SELECT count(*) FROM \"RepairStatusHistory\" h JOIN \"RepairOrder\" o ON o.id ="
                + " h.\"repairOrderId\" WHERE o.notes = ? AND h.action = 'preventive'", "Auto-generated from schedule "
                + sid)).isEqualTo(1);
        TenantContext.run(TenantStore.organization(demoOrg), () -> new RepairCronWorker(repairDb, sequence)
                .housekeeping());
        assertThat(count("SELECT count(*) FROM \"RepairOrder\" WHERE notes = ?", "Auto-generated from schedule " + sid))
                .as("a rolled-forward schedule is not due again").isEqualTo(1);
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            assertThat(contractService.rollSchedule(sid).get("lastRunAt")).isNotNull();
            try {
                contractService.rollSchedule(UUID.randomUUID().toString());
                throw new AssertionError("expected 404");
            } catch (com.inventoryerp.shared.web.ApiException e) {
                assertThat(e.getMessage()).isEqualTo("Repair schedule not found");
            }
        });

        assertThat(ok(201, "POST", "/repair/contracts/" + cid + "/cancel", Map.of("reason", "moved")).get("notes")
                .asString()).isEqualTo("moved");
        assertThat(ok(200, "DELETE", "/repair/schedules/" + sid, null).get("deletedAt").isNull()).isFalse();
        assertThat(ok(200, "DELETE", "/repair/contracts/" + cid, null).get("deletedAt").isNull()).isFalse();
        assertThat(ok(200, "GET", "/repair/contracts", null).toString()).doesNotContain(cid);
        for (String p : List.of("/repair/contracts/", "/repair/schedules/")) {
            String x = UUID.randomUUID().toString();
            assertThat(as("PATCH", p + x, Map.of()).body().get("message").asString()).isEqualTo("Record not found");
            assertThat(as("DELETE", p + x, null).status()).isEqualTo(404);
        }
        assertThat(as("POST", "/repair/contracts/" + cid + "/activate", null).status()).isEqualTo(404);
        assertThat(as("POST", "/repair/contracts/" + cid + "/cancel", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("dashboard and workshop reports")
    void reports() {
        String tech = ok(201, "POST", "/repair/technicians", Map.of("name", "Loaded " + sfx)).get("id").asString();
        String id = order(Map.of()).get("id").asString();
        ok(201, "POST", "/repair/orders/" + id + "/jobs", Map.of("technicianId", tech));
        ok(201, "POST", "/repair/orders/" + id + "/jobs", Map.of("technicianId", tech));
        JsonNode dash = ok(200, "GET", "/repair/dashboard", null);
        for (String k : List.of("openOrders", "inWorkshop", "readyPickup", "awaitingApproval", "completed30",
                "revenue30", "labourTypes", "technicians", "byStatus")) {
            assertThat(dash.has(k)).as(k).isTrue();
        }
        assertThat(dash.get("openOrders").asLong()).isPositive();
        assertThat(dash.get("byStatus").get("received").asLong()).isPositive();
        JsonNode load = ok(200, "GET", "/repair/reports/technician-load", null);
        JsonNode mine = null;
        for (JsonNode r : load) {
            if (r.get("id").asString().equals(tech)) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("pending").asLong()).isEqualTo(2);
        assertThat(mine.get("in_progress").asLong()).isZero();
        ok(201, "POST", "/repair/warranties", Map.of());
        assertThat(ok(200, "GET", "/repair/reports/warranty-health", null).get("active").asLong()).isPositive();
        JsonNode op = ok(200, "GET", "/repair/reports/operational", null);
        assertThat(op.get("totalOrders").asLong()).isPositive();
        assertThat(ok(200, "GET", "/repair/reports/revenue?to=2000-01-01", null)).isEmpty();
    }

    @Test
    @DisplayName("tenant isolation and 401/403 matrix (64 routes)")
    void isolationAndAuth() {
        String id = order(Map.of()).get("id").asString();
        String lt = labourType(Map.of()).get("id").asString();
        Res foreign = call("GET", "/repair/orders/" + id, null, otherToken);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(call("GET", "/repair/orders", null, otherToken).body().get("total").asLong()).isZero();
        assertThat(call("PATCH", "/repair/labour-types/" + lt, Map.of("price", 1), otherToken).status()).isEqualTo(404);
        List<String> others = new ArrayList<>();
        call("GET", "/repair/labour-types", null, otherToken).body().forEach(n -> others.add(n.get("id").asString()));
        assertThat(others).doesNotContain(lt);

        // Reads and writes by id from the other organization find nothing.
        String item = ok(201, "POST", "/repair/orders/" + id + "/items", Map.of("itemType", "Cable")).get("id")
                .asString();
        String att = ok(201, "POST", "/repair/orders/" + id + "/attachments", Map.of("caption", "intake")).get("id")
                .asString();
        String diag = ok(201, "POST", "/repair/orders/" + id + "/diagnosis", Map.of()).get("id").asString();
        String quote = ok(201, "POST", "/repair/orders/" + id + "/quotations", Map.of()).get("id").asString();
        String job = ok(201, "POST", "/repair/orders/" + id + "/jobs", Map.of()).get("id").asString();
        String part = ok(201, "POST", "/repair/orders/" + id + "/parts", Map.of("productId", partProductId)).get("id")
                .asString();
        String warranty = ok(201, "POST", "/repair/warranties", Map.of("repairOrderId", id)).get("id").asString();
        String contract = ok(201, "POST", "/repair/contracts", Map.of("title", "Iso " + sfx)).get("id").asString();
        assertThat(call("DELETE", "/repair/orders/items/" + item, null, otherToken).body().get("count").asLong())
                .isZero();
        assertThat(call("DELETE", "/repair/orders/attachments/" + att, null, otherToken).body().get("count").asLong())
                .isZero();
        assertThat(call("PATCH", "/repair/diagnosis/" + diag, Map.of("rootCause", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/repair/quotations/" + quote, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/repair/quotations/" + quote + "/approve", Map.of(), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/repair/jobs/" + job, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/repair/jobs/" + job + "/start", null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/repair/parts/" + part + "/issue", Map.of("locationId", warehouseId), otherToken)
                .status()).isEqualTo(404);
        assertThat(call("DELETE", "/repair/parts/" + part, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/repair/warranties/" + warranty + "/claims", Map.of(), otherToken).status())
                .isEqualTo(404);
        assertThat(call("PATCH", "/repair/contracts/" + contract, Map.of("notes", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/repair/orders/" + id + "/transition", Map.of("status", "cancelled"), otherToken)
                .status()).isEqualTo(404);
        Map<String, String> otherKeyed = new LinkedHashMap<>();
        otherKeyed.put("Authorization", "Bearer " + otherToken);
        otherKeyed.put("Idempotency-Key", key());
        assertThat(call("POST", "/repair/orders/" + id + "/bill", Map.of(), otherKeyed).status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM \"RepairOrderItem\" WHERE id IN (?, ?)", item, item)).isEqualTo(1);
        assertThat(status(id)).isEqualTo("received");

        // The other organization cannot write rows onto this organization's order (Nest would: no order lookup).
        long history = count("SELECT count(*) FROM \"RepairStatusHistory\" WHERE \"repairOrderId\" = ?", id);
        assertThat(call("POST", "/repair/orders/" + id + "/notes", Map.of("note", "hi"), otherToken).status())
                .isEqualTo(500);
        assertThat(call("POST", "/repair/orders/" + id + "/attachments", Map.of("caption", "x"), otherToken)
                .status()).isEqualTo(500);
        assertThat(call("POST", "/repair/warranties", Map.of("repairOrderId", id), otherToken).status())
                .isEqualTo(500);
        assertThat(count("SELECT count(*) FROM \"RepairStatusHistory\" WHERE \"repairOrderId\" = ?", id))
                .isEqualTo(history);
        assertThat(count("SELECT count(*) FROM \"RepairAttachment\" WHERE \"repairOrderId\" = ? AND"
                + " \"organizationId\" <> ?", id, demoOrg)).isZero();
        assertThat(count("SELECT count(*) FROM \"RepairWarranty\" WHERE \"repairOrderId\" = ? AND"
                + " \"organizationId\" <> ?", id, demoOrg)).isZero();
        String foreignOrder = call("POST", "/repair/orders", Map.of("itemType", "Foreign"), otherToken).body()
                .get("id").asString();
        String foreignJob = call("POST", "/repair/orders/" + foreignOrder + "/jobs", Map.of(), otherToken).body()
                .get("id").asString();
        assertThat(call("PATCH", "/repair/jobs/" + foreignJob, Map.of("repairOrderId", id), otherToken).status())
                .isEqualTo(500);
        assertThat(call("POST", "/repair/orders/" + foreignOrder + "/items", Map.of("repairOrderId", id), otherToken)
                .status()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM \"RepairJob\" WHERE \"repairOrderId\" = ? AND"
                + " \"organizationId\" <> ?", id, demoOrg)).isZero();
        assertThat(count("SELECT count(*) FROM \"RepairOrderItem\" WHERE \"repairOrderId\" = ? AND"
                + " \"organizationId\" <> ?", id, demoOrg)).isZero();

        // PATCH bodies: no tenant hop, unknown keys (incl. a quoted column name) are rejected before any SQL.
        assertThat(as("PATCH", "/repair/labour-types/" + lt, Map.of("organizationId", otherOrg)).status())
                .isEqualTo(500);
        assertThat(as("POST", "/repair/orders/" + id + "/transition", Map.of("status", "diagnosis",
                "extra", Map.of("organizationId", otherOrg))).status()).isEqualTo(500);
        assertThat(status(id)).isEqualTo("received");
        assertThat(as("PATCH", "/repair/jobs/" + job, Map.of("bogus", 1)).status()).isEqualTo(500);
        assertThat(as("PATCH", "/repair/jobs/" + job, Map.of("notes\" = 'x', \"organizationId", otherOrg)).status())
                .isEqualTo(500);
        assertThat(jdbc.sql("SELECT \"organizationId\" FROM \"RepairLabourType\" WHERE id = ?").param(lt)
                .query(String.class).single()).isEqualTo(demoOrg);
        assertThat(jdbc.sql("SELECT \"organizationId\" FROM \"RepairJob\" WHERE id = ?").param(job)
                .query(String.class).single()).isEqualTo(demoOrg);
        assertThat(ok(200, "PATCH", "/repair/labour-types/" + lt, Map.of("organizationId", demoOrg, "price", 9))
                .get("price").asString()).isEqualTo("9");

        assertThat(assertAuthorizationMatrix("com.inventoryerp.repair")).isEqualTo(64);
    }
}
