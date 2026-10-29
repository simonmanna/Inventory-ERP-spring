package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetDispatchEligibilityService;
import com.inventoryerp.fleet.application.FleetMaintenanceJob;
import com.inventoryerp.fleet.application.FleetServiceScheduleService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.6 — maintenance over HTTP: service templates → schedules → due list → work-order generation (screen and
 * job, never twice), the work-order lifecycle with the unit's status and downtime, spare parts issued from stock with
 * a balanced GL entry on the unit's cost centre (idempotent replay, returns, reversals, concurrency on the last
 * units), labour and external bills (AP draft bill + accrual), inspections → defects → work orders and the dispatch
 * block, downtime and reliability, tenant isolation and the authorization matrix of every maintenance route.
 */
class FleetMaintenanceIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    SettingResolverService settingResolver;

    @Autowired
    com.inventoryerp.fleet.application.FleetDb fleetDb;

    @Autowired
    FleetServiceScheduleService scheduleService;

    @Autowired
    FleetDispatchEligibilityService eligibility;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String tipperClass;
    String costCenter;
    String store;
    String mechanic;
    String supplier;
    String readerToken;

    @BeforeAll
    void maintenanceFixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipperClass = c.get("id").asString();
            }
        }
        costCenter = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(costCenter, demoOrg, "FLT-" + sfx, "Fleet " + sfx).update();
        store = ok(201, "POST", "/inventory/locations", Map.of("code", "WS-" + sfx, "name", "Workshop store " + sfx,
                "type", "warehouse")).get("id").asString();
        mechanic = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"hourlyRate\", \"updatedAt\") VALUES (?, ?, ?, 'Musa', 'Mechanic', 12.5, now())")
                .params(mechanic, demoOrg, "MECH-" + sfx).update();
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "WSP-" + sfx, "Diesel Clinic " + sfx)
                .update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Maintenance reader " + sfx,
                        new String[] {"fleet_vehicle:read", "fleet_maintenance:read"}).update();
        String email = "maint-reader-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Reader@12345", role);
        readerToken = login("DEMO", email, "Reader@12345");
    }

    // ------------------------------------------------------------------ helpers

    String vehicle(Object openingOdometer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", tipperClass);
        body.put("registrationPlate", "UMT " + sfx + "-" + (++seq));
        body.put("make", "Sinotruk");
        body.put("openingOdometer", openingOdometer);
        body.put("costCenterId", costCenter);
        return ok(201, "POST", "/fleet/vehicles", body).get("id").asString();
    }

    String product(BigDecimal onHand, BigDecimal unitCost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "SP-" + sfx + "-" + (++seq), "Oil filter " + sfx + "-" + seq).update();
        TenantContext.run(TenantStore.user(demoOrg, adminId),
                () -> stock.receive(StockMove.of(id, store, onHand).unitCost(unitCost)));
        return id;
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, store).query(BigDecimal.class).single();
    }

    Map<String, String> key(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", key);
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(UUID.randomUUID().toString()));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String workOrder(String vehicleId, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("type", type);
        body.put("title", "Repair " + type);
        return ok(201, "POST", "/fleet/work-orders", body).get("id").asString();
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(String.class).single();
    }

    /** Asserts debits = credits and returns the entry's lines. */
    List<Map<String, Object>> balanced(String journalEntryId) {
        List<Map<String, Object>> lines = jdbc.sql("SELECT \"accountId\", debit, credit, \"costCenterId\" FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(journalEntryId).query().listOfRows();
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            dr = dr.add((BigDecimal) l.get("debit"));
            cr = cr.add((BigDecimal) l.get("credit"));
        }
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(dr).isEqualByComparingTo(cr);
        return lines;
    }

    static Map<String, Object> debitOf(List<Map<String, Object>> lines, String accountId) {
        return lines.stream().filter(l -> accountId.equals(l.get("accountId"))
                && ((BigDecimal) l.get("debit")).signum() > 0).findFirst().orElseThrow();
    }

    static JsonNode line(JsonNode wo, String type, int index) {
        List<JsonNode> out = new ArrayList<>();
        wo.get("lines").forEach(l -> {
            if (type.equals(l.get("lineType").asString())) {
                out.add(l);
            }
        });
        return out.get(index);
    }

    String vehicleStatus(String id) {
        return ok(200, "GET", "/fleet/vehicles/" + id, null).get("status").asString();
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    // ------------------------------------------------------------------ schedules → work orders

    @Test
    @DisplayName("schedule: template applied to a class, due by km, work order generated once (screen + job), "
            + "lifecycle holds the unit in workshop, completion re-bases the schedule")
    void scheduleToWorkOrder() {
        String v = vehicle(1000);
        Map<String, Object> tpl = new LinkedHashMap<>();
        tpl.put("code", "SVC-" + sfx);
        tpl.put("name", "A-service " + sfx);
        tpl.put("categoryId", tipperClass);
        tpl.put("intervalKm", 10000);
        tpl.put("intervalDays", 180);
        tpl.put("leadKm", 500);
        tpl.put("tasks", List.of("Change engine oil", "Replace oil filter"));
        String template = ok(201, "POST", "/fleet/service-templates", tpl).get("id").asString();
        assertThat(as("POST", "/fleet/service-templates", tpl).status()).isEqualTo(409);
        assertThat(as("POST", "/fleet/service-templates", Map.of("code", "X" + sfx, "name", "No interval"))
                .status()).isEqualTo(400);
        JsonNode applied = ok(201, "POST", "/fleet/service-templates/" + template + "/apply",
                Map.of("vehicleIds", List.of(v)));
        assertThat(applied.get("created").asInt()).isEqualTo(1);
        assertThat(ok(201, "POST", "/fleet/service-templates/" + template + "/apply",
                Map.of("vehicleIds", List.of(v))).get("skipped").asInt()).isEqualTo(1);
        JsonNode schedules = ok(200, "GET", "/fleet/vehicles/" + v + "/service-schedules", null);
        assertThat(schedules).hasSize(1);
        String schedule = schedules.get(0).get("id").asString();
        assertThat(new BigDecimal(schedules.get(0).get("lastDoneKm").asString())).isEqualByComparingTo("1000");
        assertThat(schedules.get(0).get("dueState").asString()).isEqualTo("ok");

        ok(201, "POST", "/fleet/vehicles/" + v + "/meter-readings", Map.of("meterType", "odometer", "value", 10600));
        JsonNode due = ok(200, "GET", "/fleet/service-schedules?state=due&vehicleId=" + v, null);
        assertThat(due.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(due.get("data").get(0).get("dueTrigger").asString()).isEqualTo("km");
        ok(201, "POST", "/fleet/vehicles/" + v + "/meter-readings", Map.of("meterType", "odometer", "value", 11100));
        assertThat(ok(200, "GET", "/fleet/service-schedules?state=overdue&vehicleId=" + v, null).get("meta")
                .get("total").asLong()).isEqualTo(1);
        assertThat(scheduleService.dueForOrganization(demoOrg, LocalDate.now()).toString()).contains(schedule);

        // The job raises the work order; the screen action and a second run find the live one.
        // Jobs are off in tests (app.jobs.enabled=false): drive the ShedLock job's per-org pass directly.
        Map<String, Object> run = new FleetMaintenanceJob(fleetDb, scheduleService).runForOrg(demoOrg);
        assertThat(((Number) run.get("created")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(as("POST", "/fleet/service-schedules/" + schedule + "/work-order", null).status()).isEqualTo(409);
        ok(201, "POST", "/fleet/service-schedules/generate", null);
        JsonNode wos = ok(200, "GET", "/fleet/work-orders?vehicleId=" + v, null);
        assertThat(wos.get("meta").get("total").asLong()).isEqualTo(1);
        String wo = wos.get("data").get(0).get("id").asString();
        JsonNode detail = ok(200, "GET", "/fleet/work-orders/" + wo, null);
        assertThat(detail.get("type").asString()).isEqualTo("preventive");
        assertThat(detail.get("status").asString()).isEqualTo("open");
        assertThat(detail.get("source").asString()).isEqualTo("schedule_job");
        assertThat(detail.get("priority").asString()).isEqualTo("high");
        assertThat(detail.get("tasks")).hasSize(2);
        assertThat(detail.get("costCenterId").asString()).isEqualTo(costCenter);

        JsonNode started = ok(201, "POST", "/fleet/work-orders/" + wo + "/start", null);
        assertThat(started.get("status").asString()).isEqualTo("in_progress");
        assertThat(started.get("downtime")).hasSize(1);
        assertThat(started.get("downtime").get(0).get("kind").asString()).isEqualTo("planned");
        assertThat(vehicleStatus(v)).isEqualTo("in_workshop");
        Res pending = as("POST", "/fleet/work-orders/" + wo + "/complete", Map.of());
        assertThat(pending.status()).isEqualTo(400);
        assertThat(msg(pending)).startsWith("2 task(s) of work order");
        for (JsonNode t : started.get("tasks")) {
            ok(200, "PATCH", "/fleet/work-orders/" + wo + "/tasks/" + t.get("id").asString(),
                    Map.of("status", "done"));
        }
        JsonNode done = ok(201, "POST", "/fleet/work-orders/" + wo + "/complete", Map.of("odometer", 11250,
                "resolution", "Serviced"));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(new BigDecimal(done.get("completionOdometer").asString())).isEqualByComparingTo("11250");
        assertThat(done.get("downtime").get(0).get("endedAt").isNull()).isFalse();
        assertThat(vehicleStatus(v)).isEqualTo("active");
        JsonNode rebased = ok(200, "GET", "/fleet/vehicles/" + v + "/service-schedules", null).get(0);
        assertThat(new BigDecimal(rebased.get("lastDoneKm").asString())).isEqualByComparingTo("11250");
        assertThat(rebased.get("dueState").asString()).isEqualTo("ok");
        assertThat(rebased.get("lastWorkOrderId").asString()).isEqualTo(wo);
        assertThat(ok(201, "POST", "/fleet/work-orders/" + wo + "/close", null).get("status").asString())
                .isEqualTo("closed");
        assertThat(as("POST", "/fleet/work-orders/" + wo + "/tasks", Map.of("description", "late")).status())
                .isEqualTo(400);
        assertThat(as("DELETE", "/fleet/service-templates/" + template, null).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ parts

    @Test
    @DisplayName("parts: stock issue + balanced GL on the unit's cost centre, idempotent replay, returns, reversals")
    void partsIssueReturnReverse() {
        String v = vehicle(5000);
        String wo = workOrder(v, "corrective");
        String part = product(new BigDecimal("10"), new BigDecimal("25"));
        Map<String, Object> body = Map.of("productId", part, "locationId", store, "quantity", 4);
        Res noKey = as("POST", "/fleet/work-orders/" + wo + "/parts", body);
        assertThat(noKey.status()).isEqualTo(400);
        String k = UUID.randomUUID().toString();
        Res first = call("POST", "/fleet/work-orders/" + wo + "/parts", body, key(k));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", "/fleet/work-orders/" + wo + "/parts", body, key(k));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("lines")).hasSize(1);
        assertThat(onHand(part)).isEqualByComparingTo("6");
        JsonNode issued = line(first.body(), "part", 0);
        assertThat(new BigDecimal(issued.get("amount").asString())).isEqualByComparingTo("100");
        List<Map<String, Object>> gl = balanced(issued.get("journalEntryId").asString());
        Map<String, Object> expense = debitOf(gl, mapped("fleet_maintenance_expense"));
        assertThat((BigDecimal) expense.get("debit")).isEqualByComparingTo("100");
        assertThat(expense.get("costCenterId")).isEqualTo(costCenter);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"postingKey\" = ?")
                .param("fleet:wo_part:" + issued.get("id").asString()).query(Long.class).single()).isEqualTo(1);

        String lineId = issued.get("id").asString();
        Res tooMany = call("POST", "/fleet/work-orders/" + wo + "/lines/" + lineId + "/return", Map.of("quantity", 5),
                key(UUID.randomUUID().toString()));
        assertThat(tooMany.status()).isEqualTo(400);
        JsonNode returned = money("/fleet/work-orders/" + wo + "/lines/" + lineId + "/return", Map.of("quantity", 1));
        assertThat(onHand(part)).isEqualByComparingTo("7");
        JsonNode ret = line(returned, "part_return", 0);
        assertThat(new BigDecimal(ret.get("amount").asString())).isEqualByComparingTo("-25");
        balanced(ret.get("journalEntryId").asString());
        assertThat(new BigDecimal(returned.get("partsCost").asString())).isEqualByComparingTo("75");
        Res blocked = call("POST", "/fleet/work-orders/" + wo + "/lines/" + lineId + "/reverse",
                Map.of("reason", "wrong part"), key(UUID.randomUUID().toString()));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).isEqualTo("Part of this line was returned; reverse the returns first");
        money("/fleet/work-orders/" + wo + "/lines/" + ret.get("id").asString() + "/reverse",
                Map.of("reason", "not returned after all"));
        assertThat(onHand(part)).isEqualByComparingTo("6");
        JsonNode reversed = money("/fleet/work-orders/" + wo + "/lines/" + lineId + "/reverse",
                Map.of("reason", "wrong part"));
        assertThat(onHand(part)).isEqualByComparingTo("10");
        assertThat(new BigDecimal(reversed.get("totalCost").asString())).isEqualByComparingTo("0");
        assertThat(line(reversed, "part", 0).get("status").asString()).isEqualTo("reversed");
        String reversal = line(reversed, "part", 0).get("reversalEntryId").asString();
        balanced(reversal);
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?")
                .param(issued.get("journalEntryId").asString()).query(String.class).single()).isEqualTo("reversed");
        Res again = call("POST", "/fleet/work-orders/" + wo + "/lines/" + lineId + "/reverse",
                Map.of("reason", "again"), key(UUID.randomUUID().toString()));
        assertThat(msg(again)).isEqualTo("This line is already reversed");
        // Short stock is refused (never below on-hand); a cancelled work order with no live cost is fine.
        Res short_ = call("POST", "/fleet/work-orders/" + wo + "/parts", Map.of("productId", part, "locationId",
                store, "quantity", 11), key(UUID.randomUUID().toString()));
        assertThat(short_.status()).isEqualTo(400);
        assertThat(msg(short_)).startsWith("Insufficient stock for ");
        assertThat(ok(201, "POST", "/fleet/work-orders/" + wo + "/cancel", Map.of("reason", "test")).get("status")
                .asString()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("concurrency: two work orders issue the same last units — exactly one gets them")
    void concurrentPartIssue() throws Exception {
        String part = product(new BigDecimal("5"), new BigDecimal("40"));
        String wo1 = workOrder(vehicle(100), "breakdown");
        String wo2 = workOrder(vehicle(100), "breakdown");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> results = new ArrayList<>();
        for (String wo : List.of(wo1, wo2)) {
            results.add(pool.submit(() -> {
                go.await();
                return call("POST", "/fleet/work-orders/" + wo + "/parts", Map.of("productId", part, "locationId",
                        store, "quantity", 4), key(UUID.randomUUID().toString()));
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Res> f : results) {
            statuses.add(f.get().status());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(onHand(part)).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetWorkOrderLine\" WHERE \"productId\" = ?").param(part)
                .query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ labour + bills

    @Test
    @DisplayName("labour (memo, then absorbed when the setting is on) and an external bill (AP draft + accrual)")
    void labourAndBills() {
        String v = vehicle(2000);
        String wo = workOrder(v, "corrective");
        JsonNode memo = money("/fleet/work-orders/" + wo + "/labour", Map.of("employeeId", mechanic, "hours", 2));
        JsonNode l1 = line(memo, "labour", 0);
        assertThat(new BigDecimal(l1.get("amount").asString())).isEqualByComparingTo("25.00");
        assertThat(l1.get("journalEntryId").isNull()).isTrue();
        assertThat(l1.get("employeeName").asString()).isEqualTo("Musa Mechanic");

        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'fleet.maintenanceLabourAbsorption', 'true'::jsonb, 'organization', now())")
                .params(setting, demoOrg).update();
        settingResolver.invalidate(demoOrg);
        try {
            JsonNode absorbed = money("/fleet/work-orders/" + wo + "/labour", Map.of("employeeId", mechanic,
                    "hours", 1.5, "rate", 20));
            JsonNode l2 = line(absorbed, "labour", 1);
            assertThat(new BigDecimal(l2.get("amount").asString())).isEqualByComparingTo("30.00");
            List<Map<String, Object>> gl = balanced(l2.get("journalEntryId").asString());
            assertThat(debitOf(gl, mapped("fleet_maintenance_expense")).get("costCenterId")).isEqualTo(costCenter);
            assertThat(gl.stream().anyMatch(l -> mapped("fleet_maintenance_labour_absorbed").equals(l.get("accountId"))
                    && ((BigDecimal) l.get("credit")).compareTo(new BigDecimal("30")) == 0)).isTrue();
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            settingResolver.invalidate(demoOrg);
        }

        JsonNode billed = money("/fleet/work-orders/" + wo + "/bills", Map.of("partnerId", supplier,
                "supplierReference", "INV-" + sfx, "amount", 450, "description", "Injector overhaul"));
        JsonNode b = line(billed, "external", 0);
        assertThat(b.get("vendorBill").get("status").asString()).isEqualTo("draft");
        String billId = b.get("vendorBillId").asString();
        Map<String, Object> doc = jdbc.sql("SELECT \"documentType\"::text AS t, \"totalAmount\", reference, \"partnerId\""
                + " FROM \"Document\" WHERE id = ?").param(billId).query().singleRow();
        assertThat(doc.get("t")).isEqualTo("vendor_bill");
        assertThat((BigDecimal) doc.get("totalAmount")).isEqualByComparingTo("450");
        assertThat(doc.get("reference")).isEqualTo("INV-" + sfx);
        List<Map<String, Object>> accrual = balanced(b.get("journalEntryId").asString());
        assertThat(debitOf(accrual, mapped("fleet_maintenance_expense")).get("costCenterId")).isEqualTo(costCenter);
        assertThat(accrual.stream().anyMatch(l -> mapped("fleet_maintenance_accrual").equals(l.get("accountId"))))
                .isTrue();
        assertThat(new BigDecimal(billed.get("externalCost").asString())).isEqualByComparingTo("450");
        assertThat(new BigDecimal(billed.get("labourCost").asString())).isEqualByComparingTo("55");
        assertThat(new BigDecimal(billed.get("totalCost").asString())).isEqualByComparingTo("505");

        // Cancelling needs the posted costs reversed first; the bill line reverses while the AP bill is a draft.
        Res cancel = as("POST", "/fleet/work-orders/" + wo + "/cancel", Map.of("reason", "x"));
        assertThat(cancel.status()).isEqualTo(400);
        JsonNode rev = money("/fleet/work-orders/" + wo + "/lines/" + b.get("id").asString() + "/reverse",
                Map.of("reason", "duplicate"));
        assertThat(new BigDecimal(rev.get("externalCost").asString())).isEqualByComparingTo("0");
        balanced(line(rev, "external", 0).get("reversalEntryId").asString());
        // A bill posted in AP must be cancelled there before the work-order line can be reversed.
        JsonNode billed2 = money("/fleet/work-orders/" + wo + "/bills", Map.of("partnerId", supplier,
                "supplierReference", "INV2-" + sfx, "amount", 80, "description", "Towing"));
        JsonNode b2 = line(billed2, "external", 1);
        Res post = as("POST", "/vendor-bills/" + b2.get("vendorBillId").asString() + "/post", Map.of());
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
        Res refused = call("POST", "/fleet/work-orders/" + wo + "/lines/" + b2.get("id").asString() + "/reverse",
                Map.of("reason", "x"), key(UUID.randomUUID().toString()));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(msg(refused)).contains("is posted in Accounts Payable");
    }

    // ------------------------------------------------------------------ inspections, defects, dispatch block

    @Test
    @DisplayName("inspection: failed critical item → defect → dispatch blocked until the work order completes")
    void inspectionDefectBlocksDispatch() {
        String v = vehicle(3000);
        JsonNode templates = ok(200, "GET", "/fleet/inspection-templates", null);
        String preTrip = null;
        for (JsonNode t : templates) {
            if ("PRE_TRIP".equals(t.get("code").asString())) {
                preTrip = t.get("id").asString();
            }
        }
        assertThat(preTrip).isNotNull();
        JsonNode tpl = null;
        for (JsonNode t : templates) {
            if (preTrip.equals(t.get("id").asString())) {
                tpl = t;
            }
        }
        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode item : tpl.get("items")) {
            String code = item.get("code").asString();
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("code", code);
            a.put("result", "BRAKES".equals(code) || "MIRRORS".equals(code) ? "fail" : "pass");
            if ("BRAKES".equals(code)) {
                a.put("notes", "Air leak at rear chamber");
            }
            answers.add(a);
        }
        Res missing = as("POST", "/fleet/inspections", Map.of("vehicleId", v, "templateId", preTrip, "items",
                answers.subList(0, 2)));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(msg(missing)).startsWith("Answer every checklist item; missing: ");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", v);
        body.put("templateId", preTrip);
        body.put("inspectorEmployeeId", mechanic);
        body.put("odometer", 3100);
        body.put("items", answers);
        JsonNode ins = ok(201, "POST", "/fleet/inspections", body);
        assertThat(ins.get("result").asString()).isEqualTo("fail");
        assertThat(ins.get("defects")).hasSize(2);
        assertThat(ins.get("defectCount").asInt()).isEqualTo(2);
        String critical = null;
        for (JsonNode d : ins.get("defects")) {
            if ("BRAKES".equals(d.get("itemCode").asString())) {
                critical = d.get("id").asString();
                assertThat(d.get("severity").asString()).isEqualTo("critical");
            }
        }
        assertThat(critical).isNotNull();
        JsonNode elig = ok(200, "GET", "/fleet/vehicles/" + v + "/dispatch-eligibility", null);
        assertThat(elig.get("eligible").asBoolean()).isFalse();
        assertThat(elig.get("reasons").get(0).asString()).startsWith("Critical defect DEF-");
        assertThat(elig.get("status").asString()).isEqualTo("active");
        // A repeat failure of the same item does not duplicate the open defect.
        body.put("odometer", 3150);
        assertThat(ok(201, "POST", "/fleet/inspections", body).get("defectCount").asInt()).isZero();

        JsonNode raised = ok(201, "POST", "/fleet/defects/" + critical + "/work-order", null);
        assertThat(raised.get("status").asString()).isEqualTo("in_repair");
        String wo = raised.get("workOrder").get("id").asString();
        JsonNode woDetail = ok(200, "GET", "/fleet/work-orders/" + wo, null);
        assertThat(woDetail.get("type").asString()).isEqualTo("inspection_defect");
        assertThat(woDetail.get("priority").asString()).isEqualTo("urgent");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + v + "/dispatch-eligibility", null).get("eligible")
                .asBoolean()).isFalse();
        ok(201, "POST", "/fleet/work-orders/" + wo + "/start", null);
        JsonNode inWorkshop = ok(200, "GET", "/fleet/vehicles/" + v + "/dispatch-eligibility", null);
        assertThat(inWorkshop.get("reasons")).hasSize(2);
        ok(201, "POST", "/fleet/work-orders/" + wo + "/complete", Map.of("resolution", "Brake chamber replaced"));
        JsonNode resolved = ok(200, "GET", "/fleet/defects/" + critical, null);
        assertThat(resolved.get("status").asString()).isEqualTo("resolved");
        assertThat(resolved.get("resolution").asString()).isEqualTo("Brake chamber replaced");
        JsonNode after = ok(200, "GET", "/fleet/vehicles/" + v + "/dispatch-eligibility", null);
        assertThat(after.get("eligible").asBoolean()).isTrue();
        // The minor mirror defect stays open but never blocks; dismissing needs a reason.
        JsonNode open = ok(200, "GET", "/fleet/defects?vehicleId=" + v, null);
        assertThat(open.get("meta").get("total").asLong()).isEqualTo(1);
        String minor = open.get("data").get(0).get("id").asString();
        assertThat(as("POST", "/fleet/defects/" + minor + "/close", Map.of("outcome", "dismissed")).status())
                .isEqualTo(400);
        assertThat(ok(201, "POST", "/fleet/defects/" + minor + "/close", Map.of("outcome", "resolved",
                "resolution", "Mirror adjusted")).get("status").asString()).isEqualTo("resolved");
        // A driver-reported critical defect blocks immediately; assertEligible says why.
        ok(201, "POST", "/fleet/defects", Map.of("vehicleId", v, "title", "Steering play", "severity", "critical"));
        String why = TenantContext.run(TenantStore.user(demoOrg, adminId), () -> {
            try {
                eligibility.assertEligible(v);
                return "eligible";
            } catch (com.inventoryerp.shared.web.ApiException e) {
                return e.getMessage();
            }
        });
        assertThat(why).startsWith("Vehicle ").contains("cannot be dispatched: Critical defect DEF-");
        assertThat(ok(200, "GET", "/fleet/inspections?vehicleId=" + v, null).get("meta").get("total").asLong())
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ downtime + reliability

    @Test
    @DisplayName("downtime: breakdown with work order, one open downtime per unit, end restores, reliability report")
    void downtimeAndReliability() {
        String v = vehicle(800);
        JsonNode d = ok(201, "POST", "/fleet/downtime", Map.of("vehicleId", v, "reasonCode", "hydraulic",
                "description", "Tipper ram leaking", "createWorkOrder", true));
        assertThat(d.get("kind").asString()).isEqualTo("unplanned");
        assertThat(d.get("workOrder").get("code").asString()).startsWith("WO-");
        assertThat(vehicleStatus(v)).isEqualTo("breakdown");
        Res twice = as("POST", "/fleet/downtime", Map.of("vehicleId", v, "reasonCode", "engine"));
        assertThat(twice.status()).isEqualTo(400);
        String wo = d.get("workOrder").get("id").asString();
        JsonNode woDetail = ok(200, "GET", "/fleet/work-orders/" + wo, null);
        assertThat(woDetail.get("type").asString()).isEqualTo("breakdown");
        assertThat(woDetail.get("source").asString()).isEqualTo("downtime");
        ok(201, "POST", "/fleet/work-orders/" + wo + "/start", null);
        assertThat(vehicleStatus(v)).isEqualTo("in_workshop");
        assertThat(ok(200, "GET", "/fleet/downtime?vehicleId=" + v, null).get("meta").get("total").asLong())
                .isEqualTo(1);
        ok(201, "POST", "/fleet/work-orders/" + wo + "/complete", Map.of());
        assertThat(vehicleStatus(v)).isEqualTo("active");
        JsonNode list = ok(200, "GET", "/fleet/downtime?vehicleId=" + v + "&open=false", null);
        assertThat(list.get("data").get(0).get("endedAt").isNull()).isFalse();

        String v2 = vehicle(900);
        String dt = ok(201, "POST", "/fleet/downtime", Map.of("vehicleId", v2, "kind", "planned", "reasonCode",
                "preventive_service")).get("id").asString();
        assertThat(vehicleStatus(v2)).isEqualTo("in_workshop");
        ok(201, "POST", "/fleet/downtime/" + dt + "/end", Map.of());
        assertThat(vehicleStatus(v2)).isEqualTo("active");
        assertThat(as("POST", "/fleet/downtime/" + dt + "/end", Map.of()).status()).isEqualTo(400);

        JsonNode rel = ok(200, "GET", "/fleet/maintenance/reliability?vehicleId=" + v, null);
        JsonNode row = rel.get("rows").get(0);
        assertThat(row.get("failures").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(row.get("availabilityPct").asString())).isLessThanOrEqualTo(new BigDecimal("100"));
        assertThat(row.get("mttrHours").isNull()).isFalse();
        JsonNode byClass = ok(200, "GET", "/fleet/maintenance/reliability?groupBy=class&categoryId=" + tipperClass,
                null);
        assertThat(byClass.get("rows").get(0).get("category").get("id").asString()).isEqualTo(tipperClass);
        assertThat(byClass.get("total").get("units").asInt()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ isolation + authorization

    @Test
    @DisplayName("tenant isolation: another organization sees and touches none of DEMO's maintenance records")
    void tenantIsolation() {
        String v = vehicle(100);
        String wo = workOrder(v, "corrective");
        String defect = ok(201, "POST", "/fleet/defects", Map.of("vehicleId", v, "title", "Cracked mirror",
                "severity", "minor")).get("id").asString();
        String schedule = ok(201, "POST", "/fleet/vehicles/" + v + "/service-schedules", Map.of("name",
                "Greasing", "intervalDays", 7)).get("id").asString();
        assertThat(call("GET", "/fleet/work-orders/" + wo, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/work-orders/" + wo + "/start", null, otherToken).status()).isEqualTo(404);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + otherToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/fleet/work-orders/" + wo + "/labour", Map.of("employeeId", mechanic, "hours", 1),
                h).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/defects/" + defect, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/service-schedules/" + schedule, Map.of("name", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/vehicles/" + v + "/dispatch-eligibility", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/work-orders", null, otherToken).body().toString()).doesNotContain(wo);
        assertThat(call("GET", "/fleet/defects?status=all", null, otherToken).body().toString())
                .doesNotContain(defect);
        assertThat(call("GET", "/fleet/service-schedules?state=all", null, otherToken).body().toString())
                .doesNotContain(schedule);
        assertThat(call("POST", "/fleet/work-orders", Map.of("vehicleId", v, "type", "corrective", "title", "x"),
                otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization matrix: 401 / 403 on every maintenance route; a reader can look but not change")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fleet.web.maintenance")).isEqualTo(51);
        assertThat(call("GET", "/fleet/work-orders", null, readerToken).status()).isEqualTo(200);
        assertThat(call("GET", "/fleet/maintenance/reliability", null, readerToken).status()).isEqualTo(200);
        Res denied = call("POST", "/fleet/work-orders", Map.of("vehicleId", "x", "type", "corrective", "title", "x"),
                readerToken);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_maintenance:create");
    }
}
