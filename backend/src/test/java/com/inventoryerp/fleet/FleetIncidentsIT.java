package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.7 incidents, insurance claims and traffic fines over HTTP: incident report (vehicle → breakdown), photos,
 * status workflow, claim workflow and the close guard; fines charged to the company (expense on the vehicle's cost
 * centre: GL balanced, idempotent replay, reversal, CREDIT paid in Expenses settles the fine) or to the driver
 * (deduction request + event, export / settle, reversal rules); tenant isolation and the authorization matrix.
 */
class FleetIncidentsIT extends FleetSafetyTestSupport {

    @Autowired
    PostingService posting;

    String bank;

    @BeforeAll
    void moneyFixtures() {
        bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'").param(demoOrg)
                .query(String.class).single();
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'equity' AND a.\"isPostable\" AND a.\"isActive\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> posting.post(PostingRequest.of("GEN", Instant.now(), "Fleet fines float",
                        "fleet_it", sfx, "fleet_it:float:" + sfx, List.of(Line.debit(bank, "5000000", "Float"),
                                Line.credit(equity, "5000000", "Float"))))));
    }

    JsonNode fine(String vehicleId, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("offenceAt", Instant.now().minus(2, ChronoUnit.DAYS).toString());
        body.put("offence", "Overloading on Masaka road");
        body.put("authority", "Traffic Police");
        body.put("amount", 200_000);
        body.put("dueDate", LocalDate.now().plusDays(14).toString());
        body.putAll(extra);
        return ok(201, "POST", "/fleet/fines", body);
    }

    String expensePaymentEntry(String expenseId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'expense_payment' AND \"sourceId\" = ? ORDER BY \"createdAt\" LIMIT 1").params(demoOrg, expenseId)
                .query(String.class).single();
    }

    Res upload(String incidentId, String filename, String contentType, byte[] content, Map<String, String> fields) {
        try {
            String boundary = "fleet-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                        + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/incidents/"
                            + incidentId + "/attachments"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ incidents + claims

    @Test
    @DisplayName("incident: report (vehicle → breakdown), photos, edit, claim workflow, close guard, reopen, cancel")
    void incidentAndClaims() {
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String driver = employee("Okello", "Peter");
        Res future = as("POST", "/fleet/incidents", Map.of("vehicleId", vehicleId, "occurredAt",
                Instant.now().plus(1, ChronoUnit.DAYS).toString(), "description", "x"));
        assertThat(msg(future)).isEqualTo("The date cannot be in the future");
        assertThat(as("POST", "/fleet/incidents", Map.of("vehicleId", vehicleId, "occurredAt", Instant.now()
                .toString(), "description", "x", "driverEmployeeId", UUID.randomUUID().toString())).status())
                .isEqualTo(400);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("driverEmployeeId", driver);
        body.put("occurredAt", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        body.put("location", "Mukono quarry access road");
        body.put("latitude", 0.353611);
        body.put("longitude", 32.755278);
        body.put("incidentType", "collision");
        body.put("severity", "major");
        body.put("description", "Rear-ended a taxi while reversing at the gate");
        body.put("estimatedCost", 3_500_000);
        body.put("atFault", "own");
        body.put("thirdParties", List.of(Map.of("name", "Kato Taxi", "vehiclePlate", "uax 123b", "insurer",
                "Jubilee")));
        body.put("policeReportNumber", "SD 12/10/2026");
        body.put("markVehicleBreakdown", true);
        JsonNode inc = ok(201, "POST", "/fleet/incidents", body);
        String id = inc.get("id").asString();
        assertThat(inc.get("incidentNumber").asString()).startsWith("INC-");
        assertThat(inc.get("status").asString()).isEqualTo("reported");
        assertThat(inc.get("driver").get("name").asString()).isEqualTo("Okello Peter");
        assertThat(inc.get("thirdParties").get(0).get("vehiclePlate").asString()).isEqualTo("UAX 123B");
        assertThat(dec(inc.get("latitude"))).isEqualByComparingTo("0.353611");
        JsonNode v = ok(200, "GET", "/fleet/vehicles/" + vehicleId, null);
        assertThat(v.get("status").asString()).isEqualTo("breakdown");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + vehicleId + "/status-history", null).get(0).get("source")
                .asString()).isEqualTo("incident");
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.incident.reported' AND"
                + " payload->>'incidentId' = ?").param(id).query(Long.class).single()).isEqualTo(1);

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        Res photo = upload(id, "damage.png", "image/png", png, Map.of("kind", "photo"));
        assertThat(photo.status()).as(photo.raw().body()).isEqualTo(201);
        assertThat(photo.body().get("title").asString()).isEqualTo("Incident " + inc.get("incidentNumber").asString());
        JsonNode withPhoto = ok(200, "GET", "/fleet/incidents/" + id, null);
        assertThat(withPhoto.get("attachments")).hasSize(1);
        assertThat(withPhoto.get("attachments").get(0).get("url").asString()).startsWith("/api/v1/files/");
        String photoId = photo.body().get("id").asString();
        assertThat(as("DELETE", "/fleet/incidents/" + id + "/attachments/" + UUID.randomUUID(), null).status())
                .isEqualTo(404);
        ok(200, "DELETE", "/fleet/incidents/" + id + "/attachments/" + photoId, null);
        assertThat(ok(200, "GET", "/fleet/incidents/" + id, null).get("attachments")).isEmpty();

        JsonNode edited = ok(200, "PATCH", "/fleet/incidents/" + id, Map.of("repairReference", "Garage job 77",
                "repairCost", 3_100_000, "workOrderId", "wo-placeholder", "clear", List.of("policeStation", "notes")));
        assertThat(edited.get("repairReference").asString()).isEqualTo("Garage job 77");
        assertThat(edited.get("workOrderId").asString()).isEqualTo("wo-placeholder");
        assertThat(as("PATCH", "/fleet/incidents/" + id, Map.of("clear", List.of("description"))).status())
                .isEqualTo(400);

        // Claim: draft → submitted → approved (≤ claimed) → settled; closing waits for it.
        assertThat(msg(as("POST", "/fleet/incidents/" + id + "/claims", Map.of("amountClaimed", 5))))
                .isEqualTo("Choose the insurer or type its name");
        JsonNode claim = ok(201, "POST", "/fleet/incidents/" + id + "/claims", Map.of("insurerName", "UAP Old Mutual",
                "policyNumber", "MV-998", "amountClaimed", 3_000_000, "excessAmount", 250_000));
        String claimId = claim.get("id").asString();
        assertThat(claim.get("status").asString()).isEqualTo("draft");
        ok(201, "POST", "/fleet/insurance-claims/" + claimId + "/status", Map.of("status", "submitted"));
        Res tooMuch = as("POST", "/fleet/insurance-claims/" + claimId + "/status", Map.of("status", "approved",
                "amountApproved", 3_500_000));
        assertThat(msg(tooMuch)).isEqualTo("The approved amount cannot exceed the amount claimed");
        JsonNode approved = ok(201, "POST", "/fleet/insurance-claims/" + claimId + "/status", Map.of("status", "approved",
                "amountApproved", 2_750_000));
        assertThat(dec(approved.get("amountApproved"))).isEqualByComparingTo("2750000");
        assertThat(approved.get("decidedOn").isNull()).isFalse();
        assertThat(msg(as("PATCH", "/fleet/insurance-claims/" + claimId, Map.of("amountClaimed", 1))))
                .isEqualTo("The claimed amount cannot change after approval");
        ok(201, "POST", "/fleet/incidents/" + id + "/status", Map.of("status", "repairing"));
        Res closeEarly = as("POST", "/fleet/incidents/" + id + "/status", Map.of("status", "closed"));
        assertThat(msg(closeEarly)).contains("open insurance claim(s)");
        JsonNode settled = ok(201, "POST", "/fleet/insurance-claims/" + claimId + "/status", Map.of("status", "settled",
                "amountReceived", 2_750_000));
        assertThat(settled.get("status").asString()).isEqualTo("settled");
        JsonNode closed = ok(201, "POST", "/fleet/incidents/" + id + "/status", Map.of("status", "closed"));
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(closed.get("closedAt").isNull()).isFalse();
        assertThat(msg(as("POST", "/fleet/incidents/" + id + "/status", Map.of("status", "investigating"))))
                .isEqualTo("A reason is required to reopen an incident");
        JsonNode reopened = ok(201, "POST", "/fleet/incidents/" + id + "/status", Map.of("status", "investigating",
                "reason", "Third party disputes liability"));
        assertThat(reopened.get("closedAt").isNull()).isTrue();

        JsonNode claims = ok(200, "GET", "/fleet/insurance-claims?status=settled&search=" + inc.get("incidentNumber").asString(),
                null);
        assertThat(claims.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(claims.get("data").get(0).get("insurer").asString()).isEqualTo("UAP Old Mutual");
        JsonNode list = ok(200, "GET", "/fleet/incidents?vehicleId=" + vehicleId + "&status=investigating", null);
        assertThat(list.get("data").get(0).get("claimCount").asInt()).isEqualTo(1);
        assertThat(list.get("data").get(0).get("driver").get("name").asString()).isEqualTo("Okello Peter");
        assertThat(ok(200, "GET", "/fleet/incident-lookups/employees?search=Okello", null).toString()).contains(driver);

        // Cancel needs a reason; a cancelled incident takes no edits / claims.
        ok(201, "POST", "/fleet/incidents/" + id + "/status", Map.of("status", "cancelled", "reason", "Duplicate"));
        assertThat(msg(as("PATCH", "/fleet/incidents/" + id, Map.of("location", "x")))).endsWith("is cancelled");
        assertThat(as("POST", "/fleet/incidents/" + id + "/claims", Map.of("insurerName", "X", "amountClaimed", 1))
                .status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ fines

    @Test
    @DisplayName("fine → company (CASH): expense paid, Dr 6450 on the vehicle's cost centre = Cr bank, replay, reversal")
    void fineToCompany() {
        String cc = costCenter();
        String vehicleId = vehicle(Map.of("costCenterId", cc)).get("id").asString();
        String notice = "TP-" + sfx + "-1";
        JsonNode f = fine(vehicleId, Map.of("noticeNumber", notice));
        String id = f.get("id").asString();
        assertThat(f.get("fineNumber").asString()).startsWith("FIN-");
        assertThat(f.get("status").asString()).isEqualTo("open");
        assertThat(as("POST", "/fleet/fines", Map.of("vehicleId", vehicleId, "offenceAt", Instant.now().toString(),
                "offence", "x", "amount", 1, "noticeNumber", notice)).status()).isEqualTo(409);

        Map<String, Object> charge = Map.of("chargeTo", "company", "paymentType", "CASH", "paymentMethod",
                "BANK_TRANSFER", "accountId", bank, "paymentReference", "EFT-" + sfx);
        assertThat(as("POST", "/fleet/fines/" + id + "/charge", charge).status()).as("key required").isEqualTo(400);
        Map<String, String> headers = keyed(adminToken);
        Res first = call("POST", "/fleet/fines/" + id + "/charge", charge, headers);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(first.body().get("status").asString()).isEqualTo("settled");
        assertThat(first.body().get("chargedTo").asString()).isEqualTo("company");
        String expenseId = first.body().get("expense").get("id").asString();
        assertThat(first.body().get("expense").get("paymentStatus").asString()).isEqualTo("PAID");
        Res replay = call("POST", "/fleet/fines/" + id + "/charge", charge, headers);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("expense").get("id").asString()).isEqualTo(expenseId);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ExpenseSourceLink\" WHERE \"sourceType\" = 'fleet_fine' AND"
                + " \"sourceId\" LIKE ?").param(id + "#%").query(Long.class).single()).isEqualTo(1);
        assertThat(msg(money("POST", "/fleet/fines/" + id + "/charge", charge))).contains("is already charged");

        String entry = expensePaymentEntry(expenseId);
        BigDecimal[] totals = assertBalanced(entry);
        assertThat(totals[0]).isEqualByComparingTo("200000");
        Map<String, Object> debit = jdbc.sql("SELECT l.\"costCenterId\", l.dimensions::text AS dims, a.code FROM"
                + " \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND"
                + " l.debit > 0").param(entry).query().singleRow();
        assertThat(debit.get("code")).isEqualTo("6450");
        assertThat(debit.get("costCenterId")).isEqualTo(cc);
        assertThat((String) debit.get("dims")).contains(vehicleId).contains(id);
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(entry).query(String.class).single()).isEqualTo(bank);

        // Reverse: the expense is voided, its payment reversed; the fine is open and can be charged again.
        assertThat(msg(money("POST", "/fleet/fines/" + id + "/status", Map.of("status", "cancelled", "reason", "x"))))
                .contains("cannot be marked cancelled");
        Res reversed = money("POST", "/fleet/fines/" + id + "/reverse-charge", Map.of("reason", "Paid twice"));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(reversed.body().get("status").asString()).isEqualTo("open");
        assertThat(reversed.body().get("expenseId").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT status::text FROM \"Expense\" WHERE id = ?").param(expenseId).query(String.class)
                .single()).isEqualTo("VOID");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entry).query(String.class)
                .single()).isEqualTo("reversed");
        String reversal = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entry)
                .query(String.class).single();
        assertBalanced(reversal);
        assertThat(msg(money("POST", "/fleet/fines/" + id + "/reverse-charge", Map.of("reason", "again"))))
                .contains("is not charged");
        drainOutbox();
        assertThat(ok(200, "GET", "/fleet/fines/" + id, null).get("status").asString()).isEqualTo("open");
        Res recharge = money("POST", "/fleet/fines/" + id + "/charge", Map.of("chargeTo", "company"));
        assertThat(recharge.status()).as(recharge.raw().body()).isEqualTo(200);
        assertThat(recharge.body().get("chargeCount").asInt()).isEqualTo(2);
        assertThat(recharge.body().get("expense").get("id").asString()).isNotEqualTo(expenseId);
    }

    @Test
    @DisplayName("fine → company (CREDIT): paying the expense in Expenses posts on the cost centre and settles the fine")
    void fineToCompanyCredit() {
        String cc = costCenter();
        String vehicleId = vehicle(Map.of("costCenterId", cc)).get("id").asString();
        String id = fine(vehicleId, Map.of("amount", 75_000)).get("id").asString();
        Res charged = money("POST", "/fleet/fines/" + id + "/charge", Map.of("chargeTo", "company", "paymentType",
                "CREDIT"));
        assertThat(charged.status()).as(charged.raw().body()).isEqualTo(200);
        assertThat(charged.body().get("status").asString()).isEqualTo("charged");
        String expenseId = charged.body().get("expense").get("id").asString();
        assertThat(charged.body().get("expense").get("paymentStatus").asString()).isEqualTo("UNPAID");
        Res paid = money("POST", "/expenses/" + expenseId + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", bank));
        assertThat(paid.status()).as(paid.raw().body()).isLessThan(300);
        assertBalanced(expensePaymentEntry(expenseId));
        // ADR-S051: the CREDIT charge is recognised at approval (accrual on the vehicle's centre); payment clears AP.
        String entry = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'expense_accrual' AND \"sourceId\" = ?").params(demoOrg, expenseId).query(String.class).single();
        assertBalanced(entry);
        assertThat(jdbc.sql("SELECT \"costCenterId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND debit > 0")
                .param(entry).query(String.class).single()).isEqualTo(cc);
        drainOutbox();
        JsonNode after = ok(200, "GET", "/fleet/fines/" + id, null);
        assertThat(after.get("status").asString()).isEqualTo("settled");
        assertThat(after.get("settledAt").isNull()).isFalse();
        // Voided in Expenses: the fine reopens.
        assertThat(money("POST", "/expenses/" + expenseId + "/void", Map.of("voidReason", "Wrong vehicle")).status())
                .isLessThan(300);
        drainOutbox();
        assertThat(ok(200, "GET", "/fleet/fines/" + id, null).get("status").asString()).isEqualTo("open");
        // An ordinary expense (no source link) still posts to its category / default expense account.
        Res plain = money("POST", "/expenses", Map.of("title", "Office water " + sfx, "amount", 10, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CASH", "paymentMethod", "BANK_TRANSFER", "accountId", bank));
        assertThat(plain.status()).as(plain.raw().body()).isLessThan(300);
        String plainEntry = expensePaymentEntry(plain.body().get("id").asString());
        assertBalanced(plainEntry);
        assertThat(jdbc.sql("SELECT a.code FROM \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE"
                + " l.\"journalEntryId\" = ? AND l.debit > 0").param(plainEntry).query(String.class).single())
                .isNotEqualTo("6450");
        assertThat(jdbc.sql("SELECT count(*) FROM \"ExpenseSourceLink\" WHERE \"expenseId\" = ?")
                .param(plain.body().get("id").asString()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("fine → driver: deduction request + event, export, settle; reversal cancels unless settled")
    void fineToDriver() {
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String driver = employee("Namutebi", "Grace");
        String id = fine(vehicleId, Map.of("driverEmployeeId", driver, "amount", 150_000)).get("id").asString();
        ok(201, "POST", "/fleet/fines/" + id + "/status", Map.of("status", "disputed", "reason", "Driver denies"));
        assertThat(msg(money("POST", "/fleet/fines/" + id + "/charge", Map.of("chargeTo", "driver"))))
                .contains("is disputed");
        ok(201, "POST", "/fleet/fines/" + id + "/status", Map.of("status", "open"));
        Res charged = money("POST", "/fleet/fines/" + id + "/charge", Map.of("chargeTo", "driver"));
        assertThat(charged.status()).as(charged.raw().body()).isEqualTo(200);
        assertThat(charged.body().get("status").asString()).isEqualTo("charged");
        assertThat(charged.body().get("deductionStatus").asString()).isEqualTo("pending");
        String deductionId = charged.body().get("deductionId").asString();
        JsonNode event = mapper.readTree(jdbc.sql("SELECT payload::text FROM \"EventOutbox\" WHERE \"eventName\" ="
                + " 'fleet.fine.deduction_requested' AND payload->>'deductionId' = ?").param(deductionId)
                .query(String.class).single());
        assertThat(event.get("employeeId").asString()).isEqualTo(driver);
        assertThat(event.get("amount").asString()).isEqualTo("150000.00");
        assertThat(event.get("fineId").asString()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Expense\" e JOIN \"ExpenseSourceLink\" l ON l.\"expenseId\" = e.id"
                + " WHERE l.\"sourceId\" LIKE ?").param(id + "#%").query(Long.class).single()).isZero();

        JsonNode pending = ok(200, "GET", "/fleet/fine-deductions?status=pending&employeeId=" + driver, null);
        assertThat(pending.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(pending.get("data").get(0).get("employee").get("name").asString()).isEqualTo("Namutebi Grace");
        ok(201, "POST", "/fleet/fine-deductions/" + deductionId + "/status", Map.of("status", "exported",
                "reference", "PAYROLL-2026-10"));
        JsonNode settledDeduction = ok(201, "POST", "/fleet/fine-deductions/" + deductionId + "/status",
                Map.of("status", "settled"));
        assertThat(settledDeduction.get("status").asString()).isEqualTo("settled");
        assertThat(msg(as("POST", "/fleet/fine-deductions/" + deductionId + "/status", Map.of("status", "exported"))))
                .isEqualTo("A settled deduction cannot be marked exported");
        assertThat(ok(200, "GET", "/fleet/fines/" + id, null).get("status").asString()).isEqualTo("settled");
        assertThat(msg(money("POST", "/fleet/fines/" + id + "/reverse-charge", Map.of("reason", "x"))))
                .contains("already settled through payroll");

        // A second fine: charged to another employee, then reversed → deduction cancelled + event.
        String other = employee("Mugisha", "Ivan");
        String id2 = fine(vehicleId, Map.of()).get("id").asString();
        assertThat(msg(money("POST", "/fleet/fines/" + id2 + "/charge", Map.of("chargeTo", "driver"))))
                .startsWith("Choose the driver (employee)");
        Res c2 = money("POST", "/fleet/fines/" + id2 + "/charge", Map.of("chargeTo", "driver", "employeeId", other));
        assertThat(c2.status()).as(c2.raw().body()).isEqualTo(200);
        assertThat(c2.body().get("driver").get("name").asString()).isEqualTo("Mugisha Ivan");
        String d2 = c2.body().get("deductionId").asString();
        Res rev = money("POST", "/fleet/fines/" + id2 + "/reverse-charge", Map.of("reason", "Charged in error"));
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(rev.body().get("status").asString()).isEqualTo("open");
        assertThat(jdbc.sql("SELECT status FROM \"FleetFineDeduction\" WHERE id = ?").param(d2).query(String.class)
                .single()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.fine.deduction_cancelled'"
                + " AND payload->>'deductionId' = ?").param(d2).query(Long.class).single()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/fines/" + id2, null).get("deductions")).hasSize(1);

        JsonNode overdue = ok(200, "GET", "/fleet/fines?vehicleId=" + vehicleId + "&overdue=true", null);
        assertThat(overdue.get("meta").get("total").asInt()).isZero();
        JsonNode edited = ok(200, "PATCH", "/fleet/fines/" + id2, Map.of("dueDate", LocalDate.now().minusDays(1)
                .toString(), "clear", List.of("authority")));
        assertThat(edited.get("overdue").asBoolean()).isTrue();
        assertThat(edited.get("authority").isNull()).isTrue();
        assertThat(ok(200, "GET", "/fleet/fines?vehicleId=" + vehicleId + "&overdue=true", null).get("meta")
                .get("total").asInt()).isEqualTo(1);
        assertThat(as("PATCH", "/fleet/fines/" + id, Map.of("amount", 1)).status()).isEqualTo(400);
        ok(201, "POST", "/fleet/fines/" + id2 + "/status", Map.of("status", "cancelled", "reason", "Waived"));
    }

    // ------------------------------------------------------------------ security

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's incidents, claims, fines")
    void tenantIsolation() {
        String vehicleId = vehicle(Map.of()).get("id").asString();
        String inc = ok(201, "POST", "/fleet/incidents", Map.of("vehicleId", vehicleId, "occurredAt",
                Instant.now().toString(), "description", "Scratch")).get("id").asString();
        String claim = ok(201, "POST", "/fleet/incidents/" + inc + "/claims", Map.of("insurerName", "X",
                "amountClaimed", 10)).get("id").asString();
        String fine = fine(vehicleId, Map.of()).get("id").asString();
        assertThat(call("GET", "/fleet/incidents/" + inc, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/incidents/" + inc + "/status", Map.of("status", "closed"), otherToken)
                .status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/insurance-claims/" + claim + "/status", Map.of("status", "submitted"), otherToken)
                .status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/fines/" + fine, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/fines/" + fine + "/charge", Map.of("chargeTo", "company"), keyed(otherToken))
                .status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/fines", Map.of("vehicleId", vehicleId, "offenceAt", Instant.now().toString(),
                "offence", "x", "amount", 1), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/incidents?search=" + sfx, null, otherToken).body().get("meta").get("total")
                .asInt()).isZero();
        assertThat(call("GET", "/fleet/fines", null, otherToken).body().toString()).doesNotContain(fine);
        assertThat(call("GET", "/fleet/insurance-claims", null, otherToken).body().toString()).doesNotContain(claim);
    }

    @Test
    @DisplayName("authorization: 401 / 403 on every incident, claim and fine route; charging needs fleet_incident:charge")
    void authorization() {
        assertThat(assertRouteMatrix(Set.of(com.inventoryerp.fleet.web.FleetIncidentController.class,
                com.inventoryerp.fleet.web.FleetFineController.class))).isEqualTo(21);
        String clerk = tokenWith("fleet_incident:read", "fleet_incident:create", "fleet_incident:update");
        String vehicleId = vehicle(Map.of()).get("id").asString();
        Res created = call("POST", "/fleet/fines", Map.of("vehicleId", vehicleId, "offenceAt", Instant.now()
                .toString(), "offence", "Speeding", "amount", 50_000), clerk);
        assertThat(created.status()).isEqualTo(201);
        Res denied = call("POST", "/fleet/fines/" + created.body().get("id").asString() + "/charge",
                Map.of("chargeTo", "company"), keyed(clerk));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_incident:charge");
    }
}
