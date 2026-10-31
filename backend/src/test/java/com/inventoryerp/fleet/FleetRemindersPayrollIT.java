package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetDb;
import com.inventoryerp.fleet.application.FleetReminderJob;
import com.inventoryerp.fleet.application.FleetReminderService;
import com.inventoryerp.kernel.common.BusinessZone;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.9 — reminder job and the driver-fine payroll wiring: compliance documents, driver licences, stored tares and
 * services due are reminded once per item per day (log + in-app notification + {@code fleet.reminder.due} event +
 * metrics; a re-run sends nothing new); a fine charged to a driver queues an hr payroll deduction that the next run
 * subtracts from net pay (balanced payroll journal crediting the fines-withheld account), settles the fine through the
 * {@code hr.payroll.inputs_included} event, re-opens it when the run is reversed, waits when it does not fit the net
 * pay, and is withdrawn when the charge is reversed.
 */
class FleetRemindersPayrollIT extends ApiTestSupport {

    @Autowired
    FleetDb fleetDb;

    @Autowired
    FleetReminderService reminderService;

    @Autowired
    MeterRegistry meters;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    LocalDate today;
    String tipper;
    int seq;

    @BeforeAll
    void fixtures() {
        today = LocalDate.now(BusinessZone.get());
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipper = c.get("id").asString();
            }
        }
    }

    String vehicle() {
        return ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", "URM " + sfx + "-"
                + (++seq), "openingOdometer", 5000)).get("id").asString();
    }

    String employee(String last, long salary) {
        return ok(201, "POST", "/hr/employees", Map.of("firstName", "Reminder", "lastName", last + " " + sfx,
                "baseSalary", salary)).get("id").asString();
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    long logged(String type, String key) {
        return jdbc.sql("SELECT count(*) FROM \"FleetReminderLog\" WHERE \"organizationId\" = ? AND \"itemType\" = ?"
                + " AND \"itemKey\" = ?").params(demoOrg, type, key).query(Long.class).single();
    }

    static <T> T await(Supplier<T> read, T expected) {
        long until = System.currentTimeMillis() + 20_000;
        T v = read.get();
        while (!expected.equals(v) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            v = read.get();
        }
        return v;
    }

    BigDecimal imbalance(String journalId) {
        return jdbc.sql("SELECT sum(debit) - sum(credit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                .param(journalId).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ reminders

    @Test
    @DisplayName("reminders: documents, licences, tares and services once per item per day; log, notification, event,"
            + " metrics; the job runs every organization")
    void reminders() {
        String v = vehicle();
        String doc = ok(201, "POST", "/fleet/vehicles/" + v + "/compliance-documents", Map.of("docType", "road_licence",
                "expiresOn", today.plusDays(5).toString(), "reminderLeadDays", 30)).get("id").asString();
        String emp = employee("Licence", 400000);
        String driver = ok(201, "POST", "/fleet/drivers", Map.of("employeeId", emp, "licenceNumber", "DLR-" + sfx,
                "licenceExpiresOn", today.plusDays(3).toString(), "reminderLeadDays", 14, "qualifiedCategoryIds",
                List.of(tipper))).get("id").asString();
        String tare = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetVehicleTare\" (id, \"organizationId\", \"vehicleId\", \"registrationPlate\","
                + " \"tareKg\", \"weighedAt\", \"expiresAt\", source, \"manualReason\", status, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 11000, ?, ?, 'manual', 'test', 'active', now())").params(tare, demoOrg, v, "URM TARE"
                + sfx, java.sql.Timestamp.from(Instant.now().minus(29, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS))).update();
        String schedule = ok(201, "POST", "/fleet/vehicles/" + v + "/service-schedules", Map.of("name",
                "Monthly service " + sfx, "intervalDays", 30, "lastDoneOn", today.minusDays(40).toString()))
                .get("id").asString();
        double sentBefore = meters.counter("fleet.reminders.sent", "type", "compliance_document").count();

        JsonNode run = ok(201, "POST", "/fleet/reminders/run", Map.of());
        assertThat(run.get("sent").get("compliance_document").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(logged("compliance_document", doc)).isEqualTo(1);
        assertThat(logged("driver_document", driver + ":licence")).isEqualTo(1);
        assertThat(logged("vehicle_tare", tare)).isEqualTo(1);
        assertThat(logged("service_due", schedule)).isEqualTo(1);
        assertThat(meters.counter("fleet.reminders.sent", "type", "compliance_document").count())
                .isGreaterThan(sentBefore);
        Map<String, Object> logRow = jdbc.sql("SELECT state, title, \"notificationId\" FROM \"FleetReminderLog\" WHERE"
                + " \"itemKey\" = ?").param(doc).query().singleRow();
        assertThat(logRow.get("state")).isEqualTo("expiring");
        assertThat((String) logRow.get("title")).contains("Road licence").contains(today.plusDays(5).toString());
        assertThat(jdbc.sql("SELECT category FROM \"Notification\" WHERE id = ?").param(logRow.get("notificationId"))
                .query(String.class).single()).isEqualTo("fleet");
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.reminder.due' AND"
                + " payload->>'itemId' = ?").param(doc).query(Long.class).single()).isEqualTo(1);

        // Same day again (route and job): nothing new for these items.
        JsonNode again = ok(201, "POST", "/fleet/reminders/run", Map.of());
        assertThat(again.get("skipped").asInt()).isGreaterThanOrEqualTo(4);
        new FleetReminderJob(fleetDb, reminderService).runForOrg(demoOrg);
        assertThat(logged("compliance_document", doc)).isEqualTo(1);
        assertThat(logged("service_due", schedule)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.reminder.due' AND"
                + " payload->>'itemId' = ?").param(doc).query(Long.class).single()).isEqualTo(1);
        // Next day: reminded again.
        ok(201, "POST", "/fleet/reminders/run", Map.of("asOf", today.plusDays(1).toString()));
        assertThat(logged("compliance_document", doc)).isEqualTo(2);

        JsonNode list = ok(200, "GET", "/fleet/reminders?itemType=driver_document&from=" + today, null);
        assertThat(list.get("data").toString()).contains(driver).doesNotContain("compliance_document");
        assertThat(list.get("meta").get("total").asInt()).isGreaterThanOrEqualTo(1);
        // The renewals report shows the same items.
        JsonNode renewals = ok(200, "GET", "/fleet/reports/renewals?days=30", null);
        assertThat(renewals.get("rows").toString()).contains(doc).contains(tare).contains(schedule);
    }

    // ------------------------------------------------------------------ fines → payroll

    @Test
    @DisplayName("fine charged to a driver → hr payroll deduction: net pay reduced, balanced journal, settled by event,"
            + " re-opened on run reversal, withdrawn on charge reversal")
    void fineDeductionInPayroll() {
        String v = vehicle();
        String emp = employee("Payroll", 1_000_000);
        String fine = ok(201, "POST", "/fleet/fines", Map.of("vehicleId", v, "offenceAt", Instant.now().minus(1,
                ChronoUnit.DAYS).toString(), "offence", "Overloading", "amount", 150_000, "driverEmployeeId", emp))
                .get("id").asString();
        JsonNode charged = money("/fleet/fines/" + fine + "/charge", Map.of("chargeTo", "driver"));
        assertThat(charged.get("deductionStatus").asString()).isEqualTo("pending");
        String deduction = charged.get("deductionId").asString();
        Map<String, Object> input = jdbc.sql("SELECT id, kind, amount, status, \"creditAccountId\" FROM"
                + " \"HrPayrollInput\" WHERE source = 'fleet_fine_deduction' AND \"sourceId\" = ?").param(deduction)
                .query().singleRow();
        assertThat(input.get("kind")).isEqualTo("deduction");
        assertThat((BigDecimal) input.get("amount")).isEqualByComparingTo("150000");
        assertThat(input.get("status")).isEqualTo("pending");
        String withheld = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2186'")
                .param(demoOrg).query(String.class).single();
        assertThat(input.get("creditAccountId")).isEqualTo(withheld);
        assertThat(jdbc.sql("SELECT \"payrollInputId\" FROM \"FleetFineDeduction\" WHERE id = ?").param(deduction)
                .query(String.class).single()).isEqualTo(input.get("id"));
        // A second, larger fine that cannot fit the net pay waits for a later run.
        String big = ok(201, "POST", "/fleet/fines", Map.of("vehicleId", v, "offenceAt", Instant.now().minus(1,
                ChronoUnit.DAYS).toString(), "offence", "Accident damage", "amount", 9_000_000, "driverEmployeeId",
                emp)).get("id").asString();
        String bigDeduction = money("/fleet/fines/" + big + "/charge", Map.of("chargeTo", "driver")).get("deductionId")
                .asString();

        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true AND id <> ? RETURNING id").params(demoOrg, emp).query(String.class).list();
        try {
            LocalDate month = today.plusYears(7).withDayOfMonth(1);
            JsonNode period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", month.toString(),
                    "endDate", month.plusMonths(1).minusDays(1).toString(), "periodCode", "PRD-FIN-" + sfx));
            String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", period.get("id").asString()))
                    .get("id").asString();
            JsonNode calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            JsonNode item = calc.get("items").get(0);
            assertThat(item.get("deductions").toString()).contains("Traffic fine").doesNotContain("Accident damage");
            BigDecimal gross = new BigDecimal(item.get("grossPay").asString());
            BigDecimal total = new BigDecimal(item.get("totalDeductions").asString());
            BigDecimal net = new BigDecimal(item.get("netPay").asString());
            assertThat(gross.subtract(total)).isEqualByComparingTo(net);
            assertThat(total.subtract(new BigDecimal(item.get("taxAmount").asString()))
                    .subtract(new BigDecimal(item.get("pensionAmount").asString()))
                    .subtract(new BigDecimal(item.get("socialSecurityAmount").asString()))
                    .subtract(new BigDecimal(item.get("insuranceAmount").asString()))
                    .subtract(new BigDecimal(item.get("otherDeductions").asString()))
                    .subtract(new BigDecimal(item.get("loanDeduction").asString()))
                    .subtract(new BigDecimal(item.get("advanceDeduction").asString()))).isEqualByComparingTo("150000");
            assertThat(jdbc.sql("SELECT \"runId\" FROM \"HrPayrollInput\" WHERE source = 'fleet_fine_deduction' AND"
                    + " \"sourceId\" = ?").param(bigDeduction).query(String.class).optional().orElse(null)).isNull();
            // In a run: the charge cannot be reversed.
            Res inRun = call("POST", "/fleet/fines/" + fine + "/reverse-charge", Map.of("reason", "x"), key());
            assertThat(inRun.status()).isEqualTo(400);

            JsonNode run = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
            String journal = run.get("journalEntryId").asString();
            assertThat(imbalance(journal)).isEqualByComparingTo("0");
            assertThat(jdbc.sql("SELECT credit FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                    .params(journal, withheld).query(BigDecimal.class).single()).isEqualByComparingTo("150000");
            assertThat(await(() -> jdbc.sql("SELECT status FROM \"FleetFineDeduction\" WHERE id = ?").param(deduction)
                    .query(String.class).single(), "settled")).isEqualTo("settled");
            assertThat(ok(200, "GET", "/fleet/fines/" + fine, null).get("status").asString()).isEqualTo("settled");
            assertThat(jdbc.sql("SELECT status FROM \"FleetFineDeduction\" WHERE id = ?").param(bigDeduction)
                    .query(String.class).single()).isEqualTo("pending");

            // Reversing the run re-opens the deduction and the fine.
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "Re-run"));
            assertThat(await(() -> jdbc.sql("SELECT status FROM \"FleetFineDeduction\" WHERE id = ?").param(deduction)
                    .query(String.class).single(), "pending")).isEqualTo("pending");
            assertThat(await(() -> jdbc.sql("SELECT status FROM \"FleetFine\" WHERE id = ?").param(fine)
                    .query(String.class).single(), "charged")).isEqualTo("charged");

            // Reversing the charges withdraws their payroll inputs.
            money("/fleet/fines/" + fine + "/reverse-charge", Map.of("reason", "Charged in error"));
            money("/fleet/fines/" + big + "/reverse-charge", Map.of("reason", "Insurance pays"));
            assertThat(jdbc.sql("SELECT count(*) FROM \"HrPayrollInput\" WHERE source = 'fleet_fine_deduction' AND"
                    + " \"sourceId\" IN (?, ?) AND status <> 'cancelled'").params(deduction, bigDeduction)
                    .query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE id = ?").param(emp).update();
        }
    }

    @Test
    @DisplayName("an inactive driver cannot be paid through payroll: the deduction request stays pending without input")
    void inactiveDriverDeduction() {
        String v = vehicle();
        String emp = employee("Former", 300000);
        jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE id = ?").param(emp).update();
        String fine = ok(201, "POST", "/fleet/fines", Map.of("vehicleId", v, "offenceAt", Instant.now().minus(1,
                ChronoUnit.DAYS).toString(), "offence", "Parking", "amount", 20_000, "driverEmployeeId", emp))
                .get("id").asString();
        JsonNode charged = money("/fleet/fines/" + fine + "/charge", Map.of("chargeTo", "driver"));
        assertThat(charged.get("deductionStatus").asString()).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT \"payrollInputId\" FROM \"FleetFineDeduction\" WHERE id = ?")
                .param(charged.get("deductionId").asString()).query(String.class).optional().orElse(null)).isNull();
    }
}
