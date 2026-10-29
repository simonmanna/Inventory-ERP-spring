package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fleet.web.FleetClaimController;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.events.OutboxWorker;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.2 — driver allowances and trip expenses over HTTP: allowance rules (overlap, specificity), claims with
 * allowance / expense lines and receipts, the approval engine (default workflow, SoD, reject, no-workflow
 * auto-approval), payment through expenses (GL Dr 6440 on the vehicle cost centre / Cr bank, balanced), export to hr
 * payroll (accrual Dr 6440 / Cr 2185, the payroll run settles the payable), idempotent replays, reversals, tenant
 * isolation and the authorization matrix.
 */
class FleetClaimsIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    OutboxWorker outbox;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final LocalDate today = LocalDate.now(BusinessZone.get());
    String tipper;
    String costCenter;
    String bank;
    String approverToken;
    String approverId;
    String tripId;
    int seq;

    @BeforeAll
    void fixtures() {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipper = c.get("id").asString();
            }
        }
        costCenter = ok(201, "POST", "/cost-centers", Map.of("code", "CLM-" + sfx, "name", "Haulage " + sfx))
                .get("id").asString();
        // A 1.3 trip the claims refer to (FleetDriverClaim."tripId" references FleetLoad since V931).
        String loc = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Claims yard', 'storage_room', now())").params(loc, demoOrg, "CLY-" + sfx).update();
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, true, now())").params(customer, demoOrg, "CLC-" + sfx, "Claims customer " + sfx).update();
        String order = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\") VALUES (?, ?, ?, ?, ?, current_date)").params(order, demoOrg, "DO-CL" + sfx,
                        customer, loc).update();
        tripId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence,"
                + " \"plannedDate\", \"locationId\") VALUES (?, ?, ?, ?, 1, current_date, ?)").params(tripId, demoOrg,
                        "TRP-CL" + sfx, order, loc).update();
        bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'").param(demoOrg)
                .query(String.class).single();
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.classification = 'equity' AND a.\"isPostable\" = true AND"
                + " a.\"isActive\" = true AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg)
                .query(String.class).single();
        TenantContext.run(TenantStore.organization(demoOrg), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> posting.post(PostingRequest.of("GEN", Instant.now(), "Fund bank for driver claims", "test",
                        sfx, "test:fleet-claims:" + sfx, List.of(Line.debit(bank, "5000000", "Opening"),
                                Line.credit(equity, "5000000", "Opening"))))));
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Claim approver " + sfx, new String[] {"fleet_driver:read",
                    "fleet_driver:approve_allowance", "expense:post"}).update();
        String email = "claim-approver-" + sfx.toLowerCase() + "@demo.test";
        approverId = createUser(demoOrg, email, "Approver@12345", role);
        approverToken = login("DEMO", email, "Approver@12345");
        // General and tipper-specific day rates, a night-out rate (this suite's own route zone keeps it isolated).
        rule("DAY-" + sfx, "per_day", 20000, null);
        rule("DAYT-" + sfx, "per_day", 25000, tipper);
        rule("NIGHT-" + sfx, "per_night", 35000, null);
    }

    // ------------------------------------------------------------------ helpers

    String zone() {
        return "Zone " + sfx;
    }

    JsonNode rule(String code, String basis, int amount, String categoryId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("code", code, "name", code, "basis", basis, "amount",
                amount, "effectiveFrom", today.minusMonths(1).toString(), "routeZone", zone()));
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        return ok(201, "POST", "/fleet/allowance-rules", body);
    }

    JsonNode vehicle() {
        return ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", "UCL " + sfx + "-"
                + (++seq), "openingOdometer", 1000, "costCenterId", costCenter));
    }

    JsonNode driver(int baseSalary) {
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Claim", "lastName", sfx + "-" + (++seq),
                "baseSalary", baseSalary)).get("id").asString();
        return ok(201, "POST", "/fleet/drivers", Map.of("employeeId", emp, "licenceNumber", "CL-" + sfx + "-" + seq,
                "licenceExpiresOn", today.plusYears(1).toString(), "qualifiedCategoryIds", List.of(tipper)));
    }

    /** A draft claim of a tipper trip: 2 days (tipper rate 25,000) + fuel 15,000 = 65,000. */
    JsonNode claim(JsonNode driver, JsonNode vehicle) {
        return ok(201, "POST", "/fleet/claims", Map.of("driverId", driver.get("id").asString(), "vehicleId",
                vehicle.get("id").asString(), "claimDate", today.toString(), "routeZone", zone(), "description",
                "Kampala → Gulu delivery", "tripId", tripId, "lines", List.of(
                        Map.of("lineType", "allowance", "basis", "per_day", "quantity", 2),
                        Map.of("lineType", "expense", "expenseType", "fuel", "amount", 15000, "description",
                                "Top-up at Karuma"))));
    }

    Res money(String method, String path, Object body, String token, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call(method, path, body, h);
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    BigDecimal imbalance(String journalId) {
        return jdbc.sql("SELECT sum(debit) - sum(credit) FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                .param(journalId).query(BigDecimal.class).single();
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL")
                .params(demoOrg, code).query(String.class).single();
    }

    /** Submit as admin, approve as the approver: an approved claim. */
    JsonNode approved(JsonNode draft) {
        String id = draft.get("id").asString();
        JsonNode submitted = ok(201, "POST", "/fleet/claims/" + id + "/submit", null);
        assertThat(submitted.get("status").asString()).isEqualTo("submitted");
        Res approved = call("POST", "/fleet/claims/" + id + "/approve", Map.of("notes", "OK"), approverToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(201);
        assertThat(approved.body().get("status").asString()).isEqualTo("approved");
        return approved.body();
    }

    Res receipt(String claimId) {
        try {
            String boundary = "claim-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nFuel receipt\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fuel.pdf\""
                    + "\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("%PDF-1.4 receipt".getBytes(StandardCharsets.UTF_8));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/claims/"
                            + claimId + "/receipts"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ rules

    @Test
    @DisplayName("allowance rules: org currency, overlap refused, patch, unused delete, used rule deactivated")
    void allowanceRules() {
        JsonNode rules = ok(200, "GET", "/fleet/allowance-rules?basis=per_day", null);
        assertThat(rules.toString()).contains("DAYT-" + sfx);
        JsonNode one = null;
        for (JsonNode r : rules) {
            if (("DAYT-" + sfx).equals(r.get("code").asString())) {
                one = r;
            }
        }
        assertThat(one).isNotNull();
        assertThat(one.get("categoryCode").asString()).isEqualTo("TIPPER");
        String currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        assertThat(one.get("currencyCode").asString()).isEqualTo(currency);

        Res overlap = as("POST", "/fleet/allowance-rules", Map.of("code", "DAY2-" + sfx, "name", "Again", "basis",
                "per_day", "amount", 1, "effectiveFrom", today.toString(), "routeZone", zone().toLowerCase()));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(msg(overlap)).isEqualTo("Allowance rule DAY-" + sfx + " already covers this basis, vehicle class and"
                + " route zone in an overlapping period");
        Res dupCode = as("POST", "/fleet/allowance-rules", Map.of("code", "day-" + sfx, "name", "x", "basis",
                "per_trip", "amount", 1, "effectiveFrom", today.toString()));
        assertThat(dupCode.status()).isEqualTo(409);
        Res badPeriod = as("POST", "/fleet/allowance-rules", Map.of("code", "TRIP-" + sfx, "name", "x", "basis",
                "per_trip", "amount", 1, "effectiveFrom", today.toString(), "effectiveTo",
                today.minusDays(1).toString()));
        assertThat(msg(badPeriod)).isEqualTo("The rule must end on or after its start date");
        Res zeroAmount = as("POST", "/fleet/allowance-rules", Map.of("code", "Z-" + sfx, "name", "x", "basis",
                "per_trip", "amount", 0, "effectiveFrom", today.toString()));
        assertThat(zeroAmount.status()).isEqualTo(400);

        JsonNode trip = ok(201, "POST", "/fleet/allowance-rules", Map.of("code", "TRP-" + sfx, "name", "Per trip",
                "basis", "per_trip", "amount", 5000, "effectiveFrom", today.toString(), "routeZone", zone()));
        JsonNode patched = ok(200, "PATCH", "/fleet/allowance-rules/" + trip.get("id").asString(), Map.of("amount",
                5500, "notes", "Fuel price change"));
        assertThat(new BigDecimal(patched.get("amount").asString())).isEqualByComparingTo("5500");
        JsonNode gone = ok(200, "DELETE", "/fleet/allowance-rules/" + trip.get("id").asString(), null);
        assertThat(gone.get("deleted").asBoolean()).isTrue();

        // A rule priced into a claim is only switched off.
        JsonNode used = ok(201, "POST", "/fleet/allowance-rules", Map.of("code", "TRP2-" + sfx, "name", "Per trip",
                "basis", "per_trip", "amount", 4000, "effectiveFrom", today.toString(), "routeZone", zone()));
        ok(201, "POST", "/fleet/claims", Map.of("driverId", driver(100000).get("id").asString(), "claimDate",
                today.toString(), "routeZone", zone(), "lines", List.of(Map.of("lineType", "allowance", "basis",
                        "per_trip", "quantity", 3))));
        JsonNode off = ok(200, "DELETE", "/fleet/allowance-rules/" + used.get("id").asString(), null);
        assertThat(off.get("deleted").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/fleet/allowance-rules", null).toString()).doesNotContain("TRP2-" + sfx);
        assertThat(ok(200, "GET", "/fleet/allowance-rules?includeInactive=true", null).toString())
                .contains("TRP2-" + sfx);
    }

    // ------------------------------------------------------------------ claims: draft, approval

    @Test
    @DisplayName("claim draft: rule pricing by class / zone, line validation, receipt, edit re-prices, submit rules")
    void draftAndValidation() {
        JsonNode d = driver(300000);
        JsonNode v = vehicle();
        JsonNode draft = claim(d, v);
        String id = draft.get("id").asString();
        assertThat(draft.get("code").asString()).startsWith("CLM-");
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("tripId").asString()).isEqualTo(tripId);
        assertThat(new BigDecimal(draft.get("totalAmount").asString())).isEqualByComparingTo("65000");
        JsonNode allowance = draft.get("lines").get(0);
        assertThat(allowance.get("ruleCode").asString()).isEqualTo("DAYT-" + sfx);
        assertThat(new BigDecimal(allowance.get("amount").asString())).isEqualByComparingTo("50000");

        Res withAmount = as("POST", "/fleet/claims/" + id + "/lines", Map.of("lineType", "allowance", "basis",
                "per_day", "amount", 1));
        assertThat(msg(withAmount)).isEqualTo("An allowance line takes its rate from the allowance rules; leave the"
                + " amount empty");
        Res noAmount = as("POST", "/fleet/claims/" + id + "/lines", Map.of("lineType", "expense", "expenseType",
                "toll"));
        assertThat(msg(noAmount)).isEqualTo("amount is required for an expense line");
        Res noBasis = as("POST", "/fleet/claims/" + id + "/lines", Map.of("lineType", "allowance"));
        assertThat(msg(noBasis)).isEqualTo("basis is required for an allowance line");
        Res noRule = as("POST", "/fleet/claims/" + id + "/lines", Map.of("lineType", "allowance", "basis",
                "per_trip"));
        assertThat(msg(noRule)).isEqualTo("No active allowance rule pays per trip on " + today
                + " for vehicle class Tipper truck in zone " + zone());
        JsonNode withNight = ok(201, "POST", "/fleet/claims/" + id + "/lines", Map.of("lineType", "allowance",
                "basis", "per_night", "quantity", 1));
        assertThat(new BigDecimal(withNight.get("totalAmount").asString())).isEqualByComparingTo("100000");
        String nightLine = withNight.get("lines").get(2).get("id").asString();
        JsonNode removed = ok(200, "DELETE", "/fleet/claims/" + id + "/lines/" + nightLine, null);
        assertThat(removed.get("lines")).hasSize(2);
        assertThat(as("DELETE", "/fleet/claims/" + id + "/lines/" + nightLine, null).status()).isEqualTo(404);

        // Without the vehicle only the general day rate applies: 2 × 20,000 + 15,000.
        JsonNode general = ok(200, "PATCH", "/fleet/claims/" + id, Map.of("clear", List.of("vehicleId")));
        assertThat(new BigDecimal(general.get("totalAmount").asString())).isEqualByComparingTo("55000");
        JsonNode back = ok(200, "PATCH", "/fleet/claims/" + id, Map.of("vehicleId", v.get("id").asString()));
        assertThat(new BigDecimal(back.get("totalAmount").asString())).isEqualByComparingTo("65000");
        Res future = as("PATCH", "/fleet/claims/" + id, Map.of("claimDate", today.plusDays(2).toString()));
        assertThat(msg(future)).isEqualTo("The claim date cannot be in the future");

        Res r = receipt(id);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.body().get("url").asString()).isNotBlank();
        assertThat(ok(200, "GET", "/fleet/claims/" + id, null).get("receipts")).hasSize(1);

        JsonNode empty = ok(201, "POST", "/fleet/claims", Map.of("driverId", d.get("id").asString(), "claimDate",
                today.toString()));
        Res noLines = as("POST", "/fleet/claims/" + empty.get("id").asString() + "/submit", null);
        assertThat(msg(noLines)).isEqualTo("Add at least one line before submitting the claim");
        JsonNode cancelled = ok(201, "POST", "/fleet/claims/" + empty.get("id").asString() + "/cancel", null);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");

        ok(201, "POST", "/fleet/claims/" + id + "/submit", null);
        Res locked = as("PATCH", "/fleet/claims/" + id, Map.of("description", "changed"));
        assertThat(msg(locked)).isEqualTo("Only draft claims can be changed (claim " + draft.get("code").asString()
                + " is submitted)");
        Res cancelSubmitted = as("POST", "/fleet/claims/" + id + "/cancel", null);
        assertThat(msg(cancelSubmitted)).startsWith("Only draft or rejected claims can be cancelled");
        JsonNode list = ok(200, "GET", "/fleet/claims?status=submitted&driverId=" + d.get("id").asString(), null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(list.get("data").get(0).get("driverCode").asString()).isEqualTo(d.get("code").asString());
        assertThat(ok(200, "GET", "/fleet/claims?search=" + draft.get("code").asString() + "&from=" + today + "&to="
                + today, null).get("meta").get("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("approval: default workflow, submitter cannot decide, reject needs a reason, outbox subscriber, sync")
    void approvalFlow() {
        JsonNode d = driver(300000);
        JsonNode v = vehicle();
        JsonNode draft = claim(d, v);
        String id = draft.get("id").asString();
        JsonNode submitted = ok(201, "POST", "/fleet/claims/" + id + "/submit", null);
        assertThat(submitted.get("approvalRequestId").isNull()).isFalse();
        assertThat(submitted.get("costCenterId").asString()).isEqualTo(costCenter);
        assertThat(submitted.get("approvalRequest").get("status").asString()).isEqualTo("pending");
        assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = 'fleet_driver_claim'").param(demoOrg).query(Long.class).single()).isEqualTo(1);
        Res self = as("POST", "/fleet/claims/" + id + "/approve", Map.of());
        assertThat(self.status()).isEqualTo(403);
        assertThat(msg(self)).isEqualTo("The person who submitted a claim cannot approve or reject it");
        Res noReason = call("POST", "/fleet/claims/" + id + "/reject", Map.of(), approverToken);
        assertThat(msg(noReason)).isEqualTo("A reason is required to reject a claim");
        Res pending = call("POST", "/fleet/claims/" + id + "/sync", null, approverToken);
        assertThat(msg(pending)).startsWith("The claim is waiting for approval");
        JsonNode rejected = call("POST", "/fleet/claims/" + id + "/reject", Map.of("notes", "Fuel receipt missing"),
                approverToken).body();
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(rejected.get("decisionNotes").asString()).isEqualTo("Fuel receipt missing");
        Res pay = money("POST", "/fleet/claims/" + id + "/pay", Map.of("paymentMethod", "CASH", "accountId", bank),
                approverToken, UUID.randomUUID().toString());
        assertThat(msg(pay)).isEqualTo("Only approved claims can be paid or exported (claim "
                + draft.get("code").asString() + " is rejected)");
        assertThat(ok(201, "POST", "/fleet/claims/" + id + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");

        // Decided in the approvals inbox: the approval.decided subscriber approves the claim.
        String id2 = claim(d, v).get("id").asString();
        String request = ok(201, "POST", "/fleet/claims/" + id2 + "/submit", null).get("approvalRequestId").asString();
        Res decided = call("POST", "/approvals/" + request + "/decide", Map.of("status", "approved"), approverToken);
        assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
        long until = System.currentTimeMillis() + 20_000;
        String status = "submitted";
        while ("submitted".equals(status) && System.currentTimeMillis() < until) {
            outbox.tick();
            status = ok(200, "GET", "/fleet/claims/" + id2, null).get("status").asString();
            if ("submitted".equals(status)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertThat(status).isEqualTo("approved");
        assertThat(call("POST", "/fleet/claims/" + id2 + "/sync", null, approverToken).body().get("status")
                .asString()).isEqualTo("approved");

        // Without an active workflow the engine auto-approves on submit.
        jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = 'fleet_driver_claim'").param(demoOrg).update();
        try {
            JsonNode auto = ok(201, "POST", "/fleet/claims/" + claim(d, v).get("id").asString() + "/submit", null);
            assertThat(auto.get("status").asString()).isEqualTo("approved");
            assertThat(auto.get("decisionNotes").asString()).contains("no active approval workflow");
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = true WHERE \"organizationId\" = ? AND"
                    + " \"entityType\" = 'fleet_driver_claim'").param(demoOrg).update();
        }
    }

    // ------------------------------------------------------------------ settlement

    @Test
    @DisplayName("pay via expenses: Idempotency-Key required + replay, GL Dr 6440 on the cost centre / Cr bank, reversal")
    void payViaExpenses() {
        JsonNode d = driver(300000);
        JsonNode v = vehicle();
        JsonNode claim = approved(claim(d, v));
        String id = claim.get("id").asString();
        Map<String, Object> body = Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank, "reference", "TRX-1");
        Res noKey = money("POST", "/fleet/claims/" + id + "/pay", body, approverToken, null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(msg(noKey)).isEqualTo("An Idempotency-Key header is required for this operation so a retry can never"
                + " charge twice.");
        // First paid by the administrator (any payer with expense:post may pay), reversed, then paid by the approver.
        Res adminPay = money("POST", "/fleet/claims/" + id + "/pay", body, adminToken, UUID.randomUUID().toString());
        assertThat(adminPay.status()).as(adminPay.raw().body()).isEqualTo(200);
        money("POST", "/fleet/claims/" + id + "/reverse", Map.of("reason", "Paid by the wrong person"), adminToken,
                UUID.randomUUID().toString());

        String key = UUID.randomUUID().toString();
        Res paid = money("POST", "/fleet/claims/" + id + "/pay", body, approverToken, key);
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        JsonNode p = paid.body();
        assertThat(p.get("status").asString()).isEqualTo("paid");
        assertThat(p.get("settlement").asString()).isEqualTo("expenses");
        assertThat(p.get("settlementCount").asInt()).isEqualTo(2);
        assertThat(p.get("expense").get("status").asString()).isEqualTo("POSTED");
        assertThat(p.get("expense").get("paymentStatus").asString()).isEqualTo("PAID");
        String expenseId = p.get("expenseId").asString();
        Res replay = money("POST", "/fleet/claims/" + id + "/pay", body, approverToken, key);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("expenseId").asString()).isEqualTo(expenseId);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Expense\" WHERE \"organizationId\" = ? AND title LIKE ? AND"
                + " status <> 'VOID'").params(demoOrg, "Driver claim " + claim.get("code").asString() + "%")
                .query(Long.class).single()).isEqualTo(1);
        Res again = money("POST", "/fleet/claims/" + id + "/pay", body, approverToken, UUID.randomUUID().toString());
        assertThat(msg(again)).isEqualTo("Only approved claims can be paid or exported (claim "
                + claim.get("code").asString() + " is paid)");

        String journal = p.get("journalEntryId").asString();
        assertThat(imbalance(journal)).isEqualByComparingTo("0");
        Map<String, Object> debit = jdbc.sql("SELECT \"accountId\", debit, \"costCenterId\", dimensions::text AS dims"
                + " FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND debit > 0").param(journal).query()
                .singleRow();
        assertThat(debit.get("accountId")).isEqualTo(account("6440"));
        assertThat((BigDecimal) debit.get("debit")).isEqualByComparingTo("65000");
        assertThat(debit.get("costCenterId")).isEqualTo(costCenter);
        assertThat((String) debit.get("dims")).contains(v.get("id").asString());
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(journal).query(String.class).single()).isEqualTo(bank);

        JsonNode reversed = money("POST", "/fleet/claims/" + id + "/reverse", Map.of("reason", "Duplicate trip"),
                approverToken, UUID.randomUUID().toString()).body();
        assertThat(reversed.get("status").asString()).isEqualTo("approved");
        assertThat(reversed.get("reversalReason").asString()).isEqualTo("Duplicate trip");
        assertThat(jdbc.sql("SELECT status::text FROM \"Expense\" WHERE id = ?").param(expenseId).query(String.class)
                .single()).isEqualTo("VOID");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(journal)
                .query(String.class).single()).isEqualTo("reversed");
        String reversal = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(journal)
                .query(String.class).single();
        assertThat(imbalance(reversal)).isEqualByComparingTo("0");
        Res notSettled = money("POST", "/fleet/claims/" + id + "/reverse", Map.of("reason", "x"), approverToken,
                UUID.randomUUID().toString());
        assertThat(msg(notSettled)).isEqualTo("Only paid or exported claims can be reversed (claim "
                + claim.get("code").asString() + " is approved)");
    }

    @Test
    @DisplayName("pay needs expense:post; SoD: the driver cannot approve their own claim")
    void payPermissionAndDriverSoD() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Claim approver only " + sfx, new String[] {"fleet_driver:read",
                    "fleet_driver:approve_allowance"}).update();
        String email = "claim-only-" + sfx.toLowerCase() + "@demo.test";
        String userId = createUser(demoOrg, email, "Approver@12345", role);
        String token = login("DEMO", email, "Approver@12345");
        JsonNode d = driver(300000);
        JsonNode v = vehicle();
        JsonNode c = approved(claim(d, v));
        Res pay = money("POST", "/fleet/claims/" + c.get("id").asString() + "/pay", Map.of("paymentMethod", "CASH",
                "accountId", bank), token, UUID.randomUUID().toString());
        assertThat(pay.status()).isEqualTo(403);
        assertThat(msg(pay)).isEqualTo("Paying a driver claim through expenses requires the expense:post permission");

        // The approver-only user is also the driver's employee user: they cannot approve their own claim.
        jdbc.sql("UPDATE \"HrEmployee\" SET \"userId\" = ? WHERE id = ?").params(userId,
                d.get("employeeId").asString()).update();
        String own = claim(d, v).get("id").asString();
        ok(201, "POST", "/fleet/claims/" + own + "/submit", null);
        Res mine = call("POST", "/fleet/claims/" + own + "/approve", Map.of(), token);
        assertThat(mine.status()).isEqualTo(403);
        assertThat(msg(mine)).isEqualTo("A driver cannot approve or reject their own claim");
    }

    @Test
    @DisplayName("export to payroll: accrual Dr 6440 / Cr 2185, payroll input in the run, Dr 2185 in payroll GL, reversals")
    void exportToPayroll() {
        JsonNode d = driver(500000);
        JsonNode v = vehicle();
        JsonNode draft = ok(201, "POST", "/fleet/claims", Map.of("driverId", d.get("id").asString(), "vehicleId",
                v.get("id").asString(), "claimDate", today.toString(), "routeZone", zone(), "lines",
                List.of(Map.of("lineType", "allowance", "basis", "per_night", "quantity", 1))));
        JsonNode c = approved(draft);
        String id = c.get("id").asString();
        String code = c.get("code").asString();
        Res noKey = money("POST", "/fleet/claims/" + id + "/export-payroll", Map.of(), approverToken, null);
        assertThat(noKey.status()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        Res exported = money("POST", "/fleet/claims/" + id + "/export-payroll", Map.of(), approverToken, key);
        assertThat(exported.status()).as(exported.raw().body()).isEqualTo(200);
        JsonNode e = exported.body();
        assertThat(e.get("status").asString()).isEqualTo("exported");
        assertThat(e.get("payrollInput").get("status").asString()).isEqualTo("pending");
        Res replay = money("POST", "/fleet/claims/" + id + "/export-payroll", Map.of(), approverToken, key);
        assertThat(replay.body().get("payrollInputId").asString()).isEqualTo(e.get("payrollInputId").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"HrPayrollInput\" WHERE source = 'fleet_driver_claim' AND"
                + " \"sourceId\" = ?").param(id).query(Long.class).single()).isEqualTo(1);
        String accrual = e.get("journalEntryId").asString();
        assertThat(imbalance(accrual)).isEqualByComparingTo("0");
        String payable = account("2185");
        Map<String, Object> dr = jdbc.sql("SELECT \"accountId\", \"costCenterId\" FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(accrual).query().singleRow();
        assertThat(dr.get("accountId")).isEqualTo(account("6440"));
        assertThat(dr.get("costCenterId")).isEqualTo(costCenter);
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(accrual).query(String.class).single()).isEqualTo(payable);

        // Payroll: the run picks the input up as an allowance line and debits the payable instead of salaries.
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true AND id <> ? RETURNING id").params(demoOrg, d.get("employeeId").asString())
                .query(String.class).list();
        try {
            LocalDate month = today.plusYears(6).withDayOfMonth(1);
            JsonNode period = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", month.toString(),
                    "endDate", month.plusMonths(1).minusDays(1).toString(), "periodCode", "PRD-FLT-" + sfx));
            String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", period.get("id").asString()))
                    .get("id").asString();
            JsonNode calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            JsonNode item = calc.get("items").get(0);
            assertThat(item.get("allowances").toString()).contains("Driver claim " + code);
            assertThat(new BigDecimal(item.get("allowancesTotal").asString())).isGreaterThanOrEqualTo(
                    new BigDecimal("35000"));
            // Recalculation releases and re-picks the input (still exactly once).
            calc = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null);
            assertThat(calc.get("items").get(0).get("allowances").toString().split("Driver claim " + code, -1))
                    .hasSize(2);
            Res inRun = money("POST", "/fleet/claims/" + id + "/reverse", Map.of("reason", "Wrong driver"),
                    approverToken, UUID.randomUUID().toString());
            assertThat(msg(inRun)).startsWith("The payroll input is already in payroll run ");

            JsonNode run = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
            String payrollJournal = run.get("journalEntryId").asString();
            assertThat(imbalance(payrollJournal)).isEqualByComparingTo("0");
            assertThat(jdbc.sql("SELECT debit FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                    .params(payrollJournal, payable).query(BigDecimal.class).single()).isEqualByComparingTo("35000");
            assertThat(ok(200, "GET", "/fleet/claims/" + id, null).get("payrollInput").get("status").asString())
                    .isEqualTo("included");
            // The payable is cleared: accrual credit 35,000 − payroll debit 35,000.
            assertThat(jdbc.sql("SELECT sum(debit) - sum(credit) FROM \"JournalLine\" WHERE \"accountId\" = ? AND"
                    + " \"journalEntryId\" IN (?, ?)").params(payable, accrual, payrollJournal)
                    .query(BigDecimal.class).single()).isEqualByComparingTo("0");

            // Reversing the run puts the input back to pending; then the claim export can be reversed.
            ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "Re-run"));
            assertThat(jdbc.sql("SELECT status FROM \"HrPayrollInput\" WHERE id = ?")
                    .param(e.get("payrollInputId").asString()).query(String.class).single()).isEqualTo("pending");
            JsonNode back = money("POST", "/fleet/claims/" + id + "/reverse", Map.of("reason", "Wrong driver"),
                    approverToken, UUID.randomUUID().toString()).body();
            assertThat(back.get("status").asString()).isEqualTo("approved");
            assertThat(jdbc.sql("SELECT status FROM \"HrPayrollInput\" WHERE id = ?")
                    .param(e.get("payrollInputId").asString()).query(String.class).single()).isEqualTo("cancelled");
            assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(accrual)
                    .query(String.class).single()).isEqualTo("reversed");
        } finally {
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
        }
    }

    // ------------------------------------------------------------------ isolation + authorization

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's claims or allowance rules")
    void tenantIsolation() {
        JsonNode d = driver(100000);
        JsonNode c = claim(d, vehicle());
        String id = c.get("id").asString();
        assertThat(call("GET", "/fleet/claims/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/claims/" + id, Map.of("notes", "x"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/claims/" + id + "/submit", null, otherToken).status()).isEqualTo(404);
        Map<String, String> h = new HashMap<>(Map.of("Authorization", "Bearer " + otherToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(call("POST", "/fleet/claims/" + id + "/export-payroll", Map.of(), h).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/claims?search=" + c.get("code").asString(), null, otherToken).body().get("meta")
                .get("total").asLong()).isZero();
        assertThat(call("GET", "/fleet/allowance-rules?includeInactive=true", null, otherToken).body().toString())
                .doesNotContain(sfx);
        assertThat(call("POST", "/fleet/claims", Map.of("driverId", d.get("id").asString(), "claimDate",
                today.toString()), otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization matrix: 401 without a token and 403 without the permission on every claim route")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(FleetClaimController.class)).isEqualTo(20);
        Res denied = call("POST", "/fleet/allowance-rules", Map.of("code", "X", "name", "X", "basis", "per_day",
                "amount", 1, "effectiveFrom", today.toString()), approverToken);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet:settings");
        assertThat(call("GET", "/fleet/lookups/payment-accounts", null, approverToken).body().toString())
                .contains(bank);
    }
}
