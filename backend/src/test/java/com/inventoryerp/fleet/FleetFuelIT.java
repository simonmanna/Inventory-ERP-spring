package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetFuelPostingService;
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
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.5 — fuel &amp; energy over HTTP: fuel cards (masked), station purchases (fuel card → card payable, cash →
 * expense paid, credit → expense paid later with the vehicle cost centre), site tanks (GRN in, stock issue out at AVCO,
 * exactly once, reversal), efficiency + anomalies + review, dip reconciliation (immediate and approval-gated),
 * concurrent tank issues, validation, tenant isolation and the authorization matrix. Every posting is checked for
 * debits = credits.
 */
class FleetFuelIT extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    @Autowired
    FleetFuelPostingService postings;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String today;
    String tipper;
    String excavator;
    String costCenter;
    String fuel;
    String supplier;
    String customer;
    String provider;
    String employee;
    String bank;
    String approverToken;
    String clerkToken;
    int plates;

    @BeforeAll
    void fuelFixtures() {
        today = LocalDate.now(BusinessZone.get()).toString();
        tipper = category("TIPPER");
        excavator = category("EXCAVATOR");
        costCenter = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, category, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Haulage fleet', 'vehicle', now())").params(costCenter, demoOrg, "CC-F" + sfx).update();
        fuel = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"salesPrice\", \"costPrice\","
                + " \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'Diesel (L)', 0, 0, true, now())")
                .params(fuel, demoOrg, "DSL-" + sfx).update();
        supplier = partner("Fuel supplier", true);
        provider = partner("Fuel card company", true);
        customer = partner("Customer", false);
        employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Musa', 'Driver', now())").params(employee, demoOrg, "EMP-" + sfx)
                .update();
        bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'").param(demoOrg)
                .query(String.class).single();
        money("/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 50_000_000,
                "paymentMethod", "bank"));
        approverToken = user("Fuel controller", "fleet_fuel:read", "fleet_fuel:post");
        clerkToken = user("Fuel clerk", "fleet_fuel:read", "fleet_fuel:create");
        // Create the fuel accounts once, so concurrent first postings never race on them.
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> {
                postings.fuelExpenseAccount();
                postings.cardPayableAccount();
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "P-" + UUID.randomUUID().toString().substring(0, 8), name + " " + sfx, isSupplier,
                        !isSupplier)
                .update();
        return id;
    }

    String user(String roleName, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, roleName + " " + sfx + role.substring(0, 4),
                        permissions).update();
        String email = "fuel-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Fuel@12345", role);
        return login("DEMO", email, "Fuel@12345");
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** Admin call with a fresh Idempotency-Key that must answer 200. */
    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    Res keyed(String path, Object body) {
        return call("POST", path, body, key(adminToken));
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String vehicle(String categoryId, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("registrationPlate", "UFL " + sfx + "-" + (++plates));
        body.put("tankCapacityL", 400);
        body.put("costCenterId", costCenter);
        body.putAll(extra);
        return ok(201, "POST", "/fleet/vehicles", body).get("id").asString();
    }

    String location() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Diesel tank', 'storage_room', now())").params(id, demoOrg, "TNK-" + sfx + "-"
                        + UUID.randomUUID().toString().substring(0, 4)).update();
        return id;
    }

    /** Fuel bought into the tank: the normal procurement goods receipt into the tank's location. */
    void grn(String locationId, int litres, int unitCost) {
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", locationId,
                "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", fuel, "description",
                        "Diesel delivery", "quantity", litres, "unitCost", unitCost))));
    }

    JsonNode tank(String locationId, int capacity) {
        return ok(201, "POST", "/fleet/fuel-tanks", Map.of("name", "Quarry diesel tank " + sfx, "locationId",
                locationId, "productId", fuel, "capacityL", capacity, "reorderLevelL", 100));
    }

    BigDecimal stockAt(String locationId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ?").params(demoOrg, fuel, locationId).query(BigDecimal.class)
                .single();
    }

    /** Debits = credits (and something was posted) on a journal entry. */
    void assertBalanced(String entryId) {
        Map<String, Object> sums = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        BigDecimal debit = (BigDecimal) sums.get("d");
        assertThat(debit).isPositive();
        assertThat(debit).isEqualByComparingTo((BigDecimal) sums.get("c"));
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(String.class).single();
    }

    Map<String, Object> station(String vehicleId, Object litres, Object odometer, String method,
            Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("source", "station");
        body.put("litres", litres);
        body.put("unitPrice", 5000);
        if (odometer != null) {
            body.put("odometer", odometer);
        }
        body.put("paymentMethod", method);
        body.putAll(extra);
        return body;
    }

    Res upload(String path, String filename, String contentType, byte[] content) {
        try {
            String boundary = "fuel-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ cards + station purchases

    @Test
    @DisplayName("fuel card: masked only; fuel-card purchase posts Dr fuel expense (vehicle cost centre) / Cr card payable")
    void fuelCardPurchase() {
        String digits = String.valueOf(1_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                8_999_999_999_999L));
        String number = "7000" + digits.substring(1, 13);
        JsonNode card = ok(201, "POST", "/fleet/fuel-cards", Map.of("cardNumber", number.substring(0, 4) + " "
                + number.substring(4, 8) + " " + number.substring(8, 12) + " " + number.substring(12),
                "label", "Tipper card", "providerPartnerId", provider, "limitAmount", 1_500_000, "limitPeriod",
                "monthly"));
        String cardId = card.get("id").asString();
        assertThat(card.get("maskedNumber").asString()).startsWith("**** ");
        assertThat(card.has("numberHash")).isFalse();
        assertThat(card.toString()).doesNotContain(number.substring(0, 8));
        assertThat(card.get("provider").get("id").asString()).isEqualTo(provider);
        Res dup = as("POST", "/fleet/fuel-cards", Map.of("cardNumber", number.substring(0, 8) + "-"
                + number.substring(8)));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT \"newValues\"::text FROM \"AuditLog\" WHERE \"entityId\" = ?").param(cardId)
                .query(String.class).single()).doesNotContain(number.substring(0, 8)).doesNotContain("numberHash");

        String v = vehicle(tipper, Map.of("openingOdometer", 1000));
        JsonNode log = money("/fleet/fuel-logs", station(v, 200, 1250, "fuel_card", Map.of("fuelCardId", cardId,
                "stationName", "Total Jinja Rd", "receiptNumber", "R-1", "employeeId", employee, "post", true)));
        assertThat(log.get("status").asString()).isEqualTo("posted");
        assertThat(dec(log.get("amount"))).isEqualByComparingTo("1000000");
        assertThat(log.get("fuelCard").get("maskedNumber").asString()).isEqualTo(card.get("maskedNumber").asString());
        assertThat(log.get("employee").get("name").asString()).isEqualTo("Musa Driver");
        assertThat(log.get("costCenterId").asString()).isEqualTo(costCenter);
        String entry = log.get("journalEntryId").asString();
        assertBalanced(entry);
        JsonNode lines = log.get("journal").get(0).get("lines");
        assertThat(lines).hasSize(2);
        String expenseAcc = mapped("fleet_fuel_expense");
        String payable = mapped("fleet_fuel_card_payable");
        Map<String, Object> debit = jdbc.sql("SELECT \"accountId\", \"costCenterId\", dimensions::text AS dims FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ? AND debit > 0").param(entry).query().singleRow();
        assertThat(debit.get("accountId")).isEqualTo(expenseAcc);
        assertThat(debit.get("costCenterId")).isEqualTo(costCenter);
        assertThat((String) debit.get("dims")).contains(v);
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(entry).query(String.class).single()).isEqualTo(payable);
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(entry).query(String.class).single()).isEqualTo(provider);
        // The odometer was recorded as a meter reading of the vehicle (source fuel).
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source = 'fuel'"
                + " AND value = 1250").param(v).query(Long.class).single()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/vehicles/" + v, null).get("meters").get("odometer").get("value").asString())
                .isEqualTo("1250");
        // Card spend this period and the limit.
        JsonNode after = ok(200, "GET", "/fleet/fuel-cards/" + cardId, null);
        assertThat(dec(after.get("periodSpend"))).isEqualByComparingTo("1000000");
        assertThat(dec(after.get("limitRemaining"))).isEqualByComparingTo("500000");
        // A second fill over the limit is posted but flagged (the provider enforces the limit at the pump).
        JsonNode over = money("/fleet/fuel-logs", station(v, 150, 1500, "fuel_card", Map.of("fuelCardId", cardId,
                "post", true)));
        assertThat(over.get("anomalies").toString()).contains("card_limit_exceeded");
        // A card assigned to another vehicle is flagged too.
        String other = vehicle(tipper, Map.of());
        ok(200, "PATCH", "/fleet/fuel-cards/" + cardId, Map.of("vehicleId", other, "clear", List.of("limitAmount")));
        JsonNode mismatch = money("/fleet/fuel-logs", station(v, 50, 1600, "fuel_card", Map.of("fuelCardId", cardId,
                "post", true)));
        assertThat(mismatch.get("anomalies").toString()).contains("card_vehicle_mismatch");
        // Blocked cards cannot be used; cards with logs cannot be deleted.
        ok(200, "PATCH", "/fleet/fuel-cards/" + cardId, Map.of("status", "blocked"));
        Res blocked = keyed("/fleet/fuel-logs", station(v, 50, 1700, "fuel_card", Map.of("fuelCardId", cardId)));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).isEqualTo("Fuel card " + card.get("maskedNumber").asString() + " is blocked");
        assertThat(msg(as("DELETE", "/fleet/fuel-cards/" + cardId, null))).endsWith("set its status to cancelled instead");
        JsonNode list = ok(200, "GET", "/fleet/fuel-cards?status=blocked", null);
        assertThat(list.toString()).contains(cardId).doesNotContain(number.substring(0, 8));
    }

    @Test
    @DisplayName("cash purchase → expense paid (Dr fuel / Cr bank); credit purchase → expense paid later, still tagged")
    void expensePurchases() {
        String v = vehicle(tipper, Map.of());
        JsonNode cash = money("/fleet/fuel-logs", station(v, 40, 2000, "cash", Map.of("paymentAccountId", bank,
                "supplierId", supplier, "post", true)));
        assertThat(cash.get("expense").get("status").asString()).isEqualTo("POSTED");
        assertThat(cash.get("expense").get("paymentStatus").asString()).isEqualTo("PAID");
        String entry = cash.get("journalEntryId").asString();
        assertBalanced(entry);
        Map<String, Object> debit = jdbc.sql("SELECT \"accountId\", \"costCenterId\" FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(entry).query().singleRow();
        assertThat(debit.get("accountId")).isEqualTo(mapped("fleet_fuel_expense"));
        assertThat(debit.get("costCenterId")).isEqualTo(costCenter);
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(entry).query(String.class).single()).isEqualTo(bank);
        assertThat(jdbc.sql("SELECT c.name FROM \"Expense\" e JOIN \"ExpenseCategory\" c ON c.id = e.\"categoryId\""
                + " WHERE e.id = ?").param(cash.get("expenseId").asString()).query(String.class).single())
                .isEqualTo("Fleet fuel");

        JsonNode credit = money("/fleet/fuel-logs", station(v, 60, 2300, "credit", Map.of("supplierId", supplier,
                "amount", 300_000, "post", true)));
        assertThat(credit.get("journal")).isEmpty();
        String expenseId = credit.get("expenseId").asString();
        assertThat(credit.get("expense").get("paymentStatus").asString()).isEqualTo("UNPAID");
        JsonNode paid = money("/expenses/" + expenseId + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId",
                bank));
        assertThat(paid.get("paymentStatus").asString()).isEqualTo("PAID");
        JsonNode detail = ok(200, "GET", "/fleet/fuel-logs/" + credit.get("id").asString(), null);
        String payEntry = detail.get("journal").get(0).get("id").asString();
        assertBalanced(payEntry);
        // Paid in Expenses, yet the expense line carries the vehicle's cost centre and the fuel account.
        Map<String, Object> payDebit = jdbc.sql("SELECT \"accountId\", \"costCenterId\" FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(payEntry).query().singleRow();
        assertThat(payDebit.get("costCenterId")).isEqualTo(costCenter);
        assertThat(payDebit.get("accountId")).isEqualTo(mapped("fleet_fuel_expense"));

        // Reversal voids the expense and mirrors its payment entry.
        JsonNode reversed = money("/fleet/fuel-logs/" + cash.get("id").asString() + "/reverse",
                Map.of("reason", "Duplicate receipt"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(reversed.get("expense").get("status").asString()).isEqualTo("VOID");
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entry).query(String.class)
                .single()).isEqualTo("reversed");
        String mirror = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entry)
                .query(String.class).single();
        assertBalanced(mirror);
        assertThat(reversed.get("journal")).hasSize(2);
    }

    // ------------------------------------------------------------------ site tanks

    @Test
    @DisplayName("tank issue: stock issued once at AVCO + Dr fuel / Cr stock; idempotent replay; reversal")
    void tankIssue() {
        String loc = location();
        String tankId = tank(loc, 5000).get("id").asString();
        grn(loc, 1000, 4000);
        assertThat(stockAt(loc)).isEqualByComparingTo("1000");
        JsonNode t = ok(200, "GET", "/fleet/fuel-tanks/" + tankId, null);
        assertThat(dec(t.get("bookLitres"))).isEqualByComparingTo("1000");
        assertThat(dec(t.get("fillPct"))).isEqualByComparingTo("20.0");

        String v = vehicle(tipper, Map.of());
        JsonNode draft = money("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "tank", "tankId", tankId,
                "litres", 120, "odometer", 5000, "unitPrice", 1, "paymentMethod", "cash"));
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("unitPrice").isNull()).isTrue();
        assertThat(draft.get("paymentMethod").isNull()).isTrue();
        assertThat(stockAt(loc)).isEqualByComparingTo("1000");
        assertThat(ok(200, "GET", "/fleet/fuel-tanks/" + tankId, null).get("draftIssues").asLong()).isEqualTo(1);
        String id = draft.get("id").asString();

        Res noKey = as("POST", "/fleet/fuel-logs/" + id + "/post", null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(msg(noKey)).isEqualTo(
                "An Idempotency-Key header is required for this operation so a retry can never charge twice.");
        Map<String, String> k = key(adminToken);
        Res first = call("POST", "/fleet/fuel-logs/" + id + "/post", null, k);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        JsonNode posted = first.body();
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(dec(posted.get("amount"))).isEqualByComparingTo("480000");
        assertThat(dec(posted.get("unitPrice"))).isEqualByComparingTo("4000");
        assertThat(stockAt(loc)).isEqualByComparingTo("880");
        String entry = posted.get("journalEntryId").asString();
        assertBalanced(entry);
        Map<String, Object> debit = jdbc.sql("SELECT \"accountId\", \"costCenterId\", debit FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(entry).query().singleRow();
        assertThat(debit.get("accountId")).isEqualTo(mapped("fleet_fuel_expense"));
        assertThat(debit.get("costCenterId")).isEqualTo(costCenter);
        assertThat((BigDecimal) debit.get("debit")).isEqualByComparingTo("480000");
        assertThat(jdbc.sql("SELECT type::text FROM \"InventoryLedger\" WHERE \"referenceType\" = 'fleet_fuel_issue'"
                + " AND \"referenceId\" = ?").param(id).query(String.class).list()).containsExactly("internal_use");

        // Same key: the first result is replayed, nothing moves twice.
        Res replay = call("POST", "/fleet/fuel-logs/" + id + "/post", null, k);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("journalEntryId").asString()).isEqualTo(entry);
        assertThat(stockAt(loc)).isEqualByComparingTo("880");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'fleet_fuel_log' AND"
                + " \"sourceId\" = ?").param(id).query(Long.class).single()).isEqualTo(1);
        // A new key is a new attempt: refused, still exactly once.
        Res again = keyed("/fleet/fuel-logs/" + id + "/post", null);
        assertThat(again.status()).isEqualTo(400);
        assertThat(msg(again)).isEqualTo("Fuel log " + posted.get("code").asString()
                + " is posted and cannot be posted");
        assertThat(stockAt(loc)).isEqualByComparingTo("880");
        Res edit = as("PATCH", "/fleet/fuel-logs/" + id, Map.of("litres", 1));
        assertThat(msg(edit)).endsWith("is posted and cannot be edited");

        JsonNode moves = ok(200, "GET", "/fleet/fuel-tanks/" + tankId + "/movements", null);
        assertThat(moves.get("meta").get("total").asLong()).isEqualTo(2);
        assertThat(moves.get("data").get(0).get("fuelLogCode").asString()).isEqualTo(posted.get("code").asString());
        assertThat(ok(200, "GET", "/fleet/fuel-logs?tankId=" + tankId, null).get("meta").get("total").asLong())
                .isEqualTo(1);

        // A tank cannot issue more than it holds.
        Res short1 = keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "tank", "tankId", tankId, "litres",
                5000, "odometer", 5100, "post", true));
        assertThat(short1.status()).isEqualTo(400);
        assertThat(msg(short1)).startsWith("Insufficient stock for Diesel (L)");
        assertThat(stockAt(loc)).isEqualByComparingTo("880");

        // Reversal: stock back at its cost, entry mirrored.
        Res noReason = keyed("/fleet/fuel-logs/" + id + "/reverse", Map.of());
        assertThat(noReason.status()).isEqualTo(400);
        JsonNode reversed = money("/fleet/fuel-logs/" + id + "/reverse", Map.of("reason", "Wrong vehicle"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(stockAt(loc)).isEqualByComparingTo("1000");
        String mirror = reversed.get("reversalJournalEntryId").asString();
        assertBalanced(mirror);
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entry).query(String.class)
                .single()).isEqualTo("reversed");
        assertThat(jdbc.sql("SELECT \"costCenterId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(mirror).query(String.class).single()).isEqualTo(costCenter);
        assertThat(msg(keyed("/fleet/fuel-logs/" + id + "/reverse", Map.of("reason", "again"))))
                .startsWith("Only a posted fuel log can be reversed");
        assertThat(jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"referenceId\" = ? AND \"referenceType\" IN ('fleet_fuel_issue', 'fleet_fuel_issue_reversal')")
                .param(id).query(BigDecimal.class).single()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("concurrency: five simultaneous 30 L issues from a 90 L tank — exactly three succeed")
    void concurrentTankIssues() throws Exception {
        String loc = location();
        String tankId = tank(loc, 1000).get("id").asString();
        grn(loc, 100, 4000);
        String warm = vehicle(tipper, Map.of());
        money("/fleet/fuel-logs", Map.of("vehicleId", warm, "source", "tank", "tankId", tankId, "litres", 10,
                "odometer", 100, "post", true));
        List<String> vehicles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            vehicles.add(vehicle(tipper, Map.of()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (String v : vehicles) {
            futures.add(pool.submit(() -> {
                start.await();
                return call("POST", "/fleet/fuel-logs", Map.of("vehicleId", v, "source", "tank", "tankId", tankId,
                        "litres", 30, "odometer", 100, "post", true), key(adminToken));
            }));
        }
        start.countDown();
        int okCount = 0;
        int refused = 0;
        for (Future<Res> f : futures) {
            Res r = f.get();
            if (r.status() == 200) {
                okCount++;
                assertBalanced(r.body().get("journalEntryId").asString());
            } else {
                assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
                assertThat(msg(r)).startsWith("Insufficient stock");
                refused++;
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(3);
        assertThat(refused).isEqualTo(2);
        assertThat(stockAt(loc)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetFuelLog\" WHERE \"tankId\" = ? AND status = 'posted'")
                .param(tankId).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    @DisplayName("dips: variance adjusted at once under the threshold; above it waits for approval (SoD); reject")
    void dipReconciliation() {
        String loc = location();
        JsonNode t = tank(loc, 10000);
        String tankId = t.get("id").asString();
        grn(loc, 1000, 4000);

        JsonNode even = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 1000));
        assertThat(dec(even.get("varianceLitres"))).isEqualByComparingTo("0");
        JsonNode accepted = money("/fleet/fuel-dips/" + even.get("id").asString() + "/reconcile", Map.of());
        assertThat(accepted.get("status").asString()).isEqualTo("accepted");

        JsonNode small = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 990,
                "notes", "Morning dip"));
        assertThat(dec(small.get("bookLitres"))).isEqualByComparingTo("1000");
        assertThat(dec(small.get("varianceLitres"))).isEqualByComparingTo("-10");
        String smallId = small.get("id").asString();
        Res noReason = keyed("/fleet/fuel-dips/" + smallId + "/reconcile", Map.of());
        assertThat(msg(noReason)).isEqualTo("A reason is required to reconcile a dip variance");
        JsonNode adjusted = money("/fleet/fuel-dips/" + smallId + "/reconcile", Map.of("reason", "Evaporation"));
        assertThat(adjusted.get("status").asString()).isEqualTo("adjusted");
        assertThat(adjusted.get("adjustmentLedgerCode").asString()).startsWith("STK/");
        assertThat(stockAt(loc)).isEqualByComparingTo("990");
        String adjEntry = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'fleet_tank_dip' AND"
                + " \"sourceId\" = ?").param(smallId).query(String.class).single();
        assertBalanced(adjEntry);
        assertThat(msg(keyed("/fleet/fuel-dips/" + smallId + "/reconcile", Map.of("reason", "x"))))
                .isEqualTo("This dip is already adjusted");

        // 290 L short is above the 50 L threshold: approval first, the requester cannot approve.
        JsonNode big = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 700));
        String bigId = big.get("id").asString();
        JsonNode pending = money("/fleet/fuel-dips/" + bigId + "/reconcile", Map.of("reason", "Suspected theft"));
        assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
        String requestId = pending.get("approvalRequestId").asString();
        assertThat(stockAt(loc)).isEqualByComparingTo("990");
        JsonNode other = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 600));
        Res second = keyed("/fleet/fuel-dips/" + other.get("id").asString() + "/reconcile", Map.of("reason", "x"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(msg(keyed("/fleet/fuel-dips/" + bigId + "/sync", null)))
                .startsWith("The reconciliation is waiting for approval");
        Res self = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"), adminToken);
        assertThat(msg(self)).isEqualTo("You cannot approve a request you created");
        Res decided = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"),
                approverToken);
        assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
        JsonNode applied = awaitDip(bigId);
        assertThat(applied.get("status").asString()).isEqualTo("adjusted");
        assertThat(applied.get("decidedBy").asString()).isNotBlank();
        assertThat(stockAt(loc)).isEqualByComparingTo("700");
        assertBalanced(jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'fleet_tank_dip' AND"
                + " \"sourceId\" = ?").param(bigId).query(String.class).single());

        // A rejected reconciliation leaves stock alone.
        JsonNode gain = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 900));
        assertThat(dec(gain.get("varianceLitres"))).isEqualByComparingTo("200");
        JsonNode p2 = money("/fleet/fuel-dips/" + gain.get("id").asString() + "/reconcile", Map.of("reason",
                "Unrecorded delivery?"));
        call("POST", "/approvals/" + p2.get("approvalRequestId").asString() + "/decide", Map.of("status", "rejected"),
                approverToken);
        JsonNode rejected = money("/fleet/fuel-dips/" + gain.get("id").asString() + "/sync", null);
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(stockAt(loc)).isEqualByComparingTo("700");

        assertThat(ok(200, "GET", "/fleet/fuel-tanks/" + tankId + "/dips", null)).hasSize(5);
        JsonNode detail = ok(200, "GET", "/fleet/fuel-tanks/" + tankId, null);
        assertThat(detail.get("recentDips")).hasSize(5);
        assertThat(detail.get("lastDip").get("id").asString()).isNotBlank();
        Res future = as("POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 10, "dippedAt",
                Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        assertThat(msg(future)).isEqualTo("Dip time cannot be in the future");
        assertThat(msg(as("POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 20000))))
                .startsWith("Dip 20000 L is above the tank capacity");
        assertThat(msg(as("DELETE", "/fleet/fuel-tanks/" + tankId, null))).endsWith("deactivate it instead");
    }

    JsonNode awaitDip(String dipId) {
        long until = System.currentTimeMillis() + 20_000;
        while (true) {
            outbox.tick();
            JsonNode d = ok(200, "GET", "/fleet/fuel-dips/" + dipId, null);
            if (!"pending_approval".equals(d.get("status").asString()) || System.currentTimeMillis() > until) {
                return d;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return d;
            }
        }
    }

    // ------------------------------------------------------------------ efficiency + anomalies

    @Test
    @DisplayName("efficiency full-to-full, back-dated recompute, band / overfill / meter anomalies, review, reversal")
    void efficiencyAndAnomalies() {
        String v = vehicle(tipper, Map.of("tankCapacityL", 300));
        Instant t0 = Instant.now().minus(5, ChronoUnit.DAYS);
        JsonNode l1 = post(v, 100, true, 10000, t0.plus(1, ChronoUnit.DAYS));
        JsonNode l2 = post(v, 50, false, 10200, t0.plus(2, ChronoUnit.DAYS));
        JsonNode l3 = post(v, 100, true, 10500, t0.plus(3, ChronoUnit.DAYS));
        assertThat(l1.get("lPer100Km").isNull()).isTrue();
        assertThat(l2.get("lPer100Km").isNull()).isTrue();
        assertThat(dec(l3.get("segmentLitres"))).isEqualByComparingTo("150");
        assertThat(dec(l3.get("distanceKm"))).isEqualByComparingTo("500");
        assertThat(dec(l3.get("lPer100Km"))).isEqualByComparingTo("30");
        assertThat(l3.get("previousFullLog").get("id").asString()).isEqualTo(l1.get("id").asString());
        assertThat(l3.get("anomalies")).isEmpty();

        JsonNode l4 = post(v, 100, true, 10600, t0.plus(4, ChronoUnit.DAYS));
        assertThat(dec(l4.get("lPer100Km"))).isEqualByComparingTo("100");
        assertThat(types(l4)).containsExactly("high_consumption");
        JsonNode l5 = post(v, 350, true, 10600, t0.plus(4, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));
        assertThat(types(l5)).containsExactlyInAnyOrder("overfill", "meter_not_increasing");
        assertThat(l5.get("lPer100Km").isNull()).isTrue();

        // Back-dated first fill: the chain is recomputed, l1 now has an efficiency.
        post(v, 60, true, 9800, t0);
        JsonNode l1b = ok(200, "GET", "/fleet/fuel-logs/" + l1.get("id").asString(), null);
        assertThat(dec(l1b.get("lPer100Km"))).isEqualByComparingTo("50");

        // A reading below the meter's history is refused by the meter rule: logged and flagged, not recorded.
        JsonNode low = post(v, 40, false, 9000, Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(low.get("status").asString()).isEqualTo("posted");
        assertThat(low.get("odometerReadingId").isNull()).isTrue();
        assertThat(low.get("meterNote").asString()).contains("is lower than the previous reading");
        assertThat(types(low)).contains("meter_not_increasing");

        // Review queue: filter, acknowledge with a note (audited), once.
        JsonNode queue = ok(200, "GET", "/fleet/fuel-anomalies?vehicleId=" + v, null);
        assertThat(queue.get("meta").get("total").asLong()).isEqualTo(4);
        String highId = null;
        for (JsonNode a : queue.get("data")) {
            if ("high_consumption".equals(a.get("type").asString())) {
                highId = a.get("id").asString();
                assertThat(a.get("fuelLog").get("code").asString()).isEqualTo(l4.get("code").asString());
                assertThat(dec(a.get("expectedMax"))).isEqualByComparingTo("60");
            }
        }
        assertThat(validationMessages(as("POST", "/fleet/fuel-anomalies/" + highId + "/acknowledge", Map.of())))
                .isNotEmpty();
        JsonNode ack = ok(201, "POST", "/fleet/fuel-anomalies/" + highId + "/acknowledge", Map.of("note",
                "Driver confirmed a long idle at the quarry face"));
        assertThat(ack.get("status").asString()).isEqualTo("acknowledged");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'FleetFuelAnomaly' AND \"entityId\" = ?")
                .param(highId).query(Long.class).single()).isEqualTo(1);
        assertThat(msg(as("POST", "/fleet/fuel-anomalies/" + highId + "/acknowledge", Map.of("note", "x"))))
                .startsWith("Only an open anomaly can be acknowledged");
        assertThat(ok(200, "GET", "/fleet/fuel-anomalies?status=acknowledged&vehicleId=" + v, null).get("meta")
                .get("total").asLong()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/fuel-logs?vehicleId=" + v + "&anomaly=high_consumption", null).get("meta")
                .get("total").asLong()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/fuel-logs?vehicleId=" + v + "&anomaly=any", null).get("meta")
                .get("total").asLong()).isEqualTo(3);

        // Reversing l4 resolves its anomaly and re-links l5 to l3.
        money("/fleet/fuel-logs/" + l4.get("id").asString() + "/reverse", Map.of("reason", "Fuel never delivered"));
        assertThat(ok(200, "GET", "/fleet/fuel-anomalies?status=resolved&vehicleId=" + v, null).toString())
                .contains(highId);
        JsonNode l5b = ok(200, "GET", "/fleet/fuel-logs/" + l5.get("id").asString(), null);
        assertThat(l5b.get("previousFullLogId").asString()).isEqualTo(l3.get("id").asString());
        assertThat(dec(l5b.get("lPer100Km"))).isEqualByComparingTo("350");

        JsonNode tab = ok(200, "GET", "/fleet/vehicles/" + v + "/fuel", null);
        assertThat(tab.get("summary").get("logs").asLong()).isEqualTo(6);
        assertThat(tab.get("trend").size()).isGreaterThanOrEqualTo(2);
        assertThat(dec(tab.get("band").get("maxLPer100Km"))).isEqualByComparingTo("60");
        assertThat(tab.get("recent").size()).isEqualTo(7);
        assertThat(dec(tab.get("summary").get("averageLPer100Km"))).isPositive();

        // Bands per class are configurable.
        JsonNode settings = ok(200, "GET", "/fleet/fuel-settings", null);
        assertThat(settings.get("bands").toString()).contains("TIPPER");
        JsonNode band = ok(200, "PUT", "/fleet/fuel-settings/bands/" + excavator, Map.of("minLPerHour", 10,
                "maxLPerHour", 30));
        assertThat(dec(band.get("maxLPerHour"))).isEqualByComparingTo("30");
        assertThat(msg(as("PUT", "/fleet/fuel-settings/bands/" + excavator, Map.of("minLPerHour", 30,
                "maxLPerHour", 10)))).isEqualTo("The maximum L/h must not be below the minimum");
        JsonNode updated = ok(200, "PUT", "/fleet/fuel-settings", Map.of("overfillTolerancePct", 3));
        assertThat(dec(updated.get("overfillTolerancePct"))).isEqualByComparingTo("3");
        ok(200, "PUT", "/fleet/fuel-settings", Map.of("overfillTolerancePct", 2));
    }

    JsonNode post(String vehicleId, int litres, boolean full, int odometer, Instant at) {
        return money("/fleet/fuel-logs", station(vehicleId, litres, odometer, "fuel_card", Map.of("fullTank", full,
                "fuelledAt", at.toString(), "fuelCardId", card(), "post", true)));
    }

    String shared;

    /** An unlimited card shared by the efficiency test. */
    String card() {
        if (shared == null) {
            shared = ok(201, "POST", "/fleet/fuel-cards", Map.of("cardNumber", "8800" + Math.abs(sfx.hashCode()),
                    "providerPartnerId", provider)).get("id").asString();
        }
        return shared;
    }

    static List<String> types(JsonNode log) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : log.get("anomalies")) {
            if (!"resolved".equals(a.get("status").asString())) {
                out.add(a.get("type").asString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ drafts, validation, receipt

    @Test
    @DisplayName("drafts: validation messages, edit, receipt photo, delete; quick entry needs fleet_fuel:post to post")
    void draftsAndValidation() {
        String v = vehicle(tipper, Map.of());
        String plant = vehicle(excavator, Map.of("registrationPlate", "PLANT-" + sfx));
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "odometer", 10, "unitPrice", 1)))).isEqualTo(
                "Choose how the fuel was paid: cash, fuel card or supplier credit");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "odometer", 10, "paymentMethod", "cash", "unitPrice", 1))))
                .isEqualTo("Choose the cash or bank account the fuel was paid from");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "odometer", 10, "paymentMethod", "credit")))).isEqualTo("Enter the amount paid or the price per litre");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "odometer", 10, "paymentMethod", "credit", "unitPrice", 5000, "amount", 40000))))
                .isEqualTo("The amount 40000 does not match 10 L × 5000 = 50000");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "paymentMethod", "credit", "supplierId", supplier, "unitPrice", 5000))))
                .isEqualTo("Enter the odometer of vehicle " + code(v));
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", plant, "source", "station", "litres", 10,
                "odometer", 10, "paymentMethod", "credit", "supplierId", supplier, "unitPrice", 5000))))
                .isEqualTo("Vehicle " + code(plant) + " does not track an odometer");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "tank", "litres", 10,
                "odometer", 10)))).isEqualTo("Choose the site tank the fuel was issued from");
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", 10,
                "odometer", 10, "paymentMethod", "credit", "unitPrice", 5000, "fuelledAt",
                Instant.now().plus(1, ChronoUnit.DAYS).toString())))).isEqualTo("Fuelling time cannot be in the future");
        assertThat(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "station", "litres", -1)).status())
                .isEqualTo(400);

        // Quick entry by a clerk: draft yes, post no.
        Map<String, Object> body = station(plant, 80, null, "credit", Map.of("hours", 1200, "supplierId", supplier,
                "employeeId", employee));
        Res clerkDraft = call("POST", "/fleet/fuel-logs", body, key(clerkToken));
        assertThat(clerkDraft.status()).as(clerkDraft.raw().body()).isEqualTo(200);
        String id = clerkDraft.body().get("id").asString();
        assertThat(clerkDraft.body().get("code").asString()).startsWith("FL-");
        assertThat(dec(clerkDraft.body().get("amount"))).isEqualByComparingTo("400000");
        Map<String, Object> postNow = new LinkedHashMap<>(body);
        postNow.put("post", true);
        Res clerkPost = call("POST", "/fleet/fuel-logs", postNow, key(clerkToken));
        assertThat(clerkPost.status()).isEqualTo(403);
        assertThat(msg(clerkPost)).isEqualTo("Missing required permission(s): fleet_fuel:post");
        assertThat(call("POST", "/fleet/fuel-logs/" + id + "/post", null, key(clerkToken)).status()).isEqualTo(403);

        JsonNode edited = ok(200, "PATCH", "/fleet/fuel-logs/" + id, Map.of("litres", 100, "fullTank", false,
                "clear", List.of("employeeId")));
        assertThat(dec(edited.get("amount"))).isEqualByComparingTo("500000");
        assertThat(edited.get("fullTank").asBoolean()).isFalse();
        assertThat(edited.get("employee").isNull()).isTrue();
        assertThat(msg(as("PATCH", "/fleet/fuel-logs/" + id, Map.of("clear", List.of("vehicleId")))))
                .isEqualTo("Field vehicleId cannot be cleared");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        Res receipt = upload("/fleet/fuel-logs/" + id + "/receipt", "receipt.png", "image/png", png);
        assertThat(receipt.status()).as(receipt.raw().body()).isEqualTo(201);
        assertThat(receipt.body().get("receipt").get("url").asString()).startsWith("/api/v1/files/");
        assertThat(receipt.body().get("receipt").get("kind").asString()).isEqualTo("photo");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + plant + "/attachments", null).toString())
                .contains("Fuel receipt " + clerkDraft.body().get("code").asString());

        JsonNode list = ok(200, "GET", "/fleet/fuel-logs?status=draft&vehicleId=" + plant, null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(list.get("summary").has("litres")).isTrue();
        assertThat(ok(200, "GET", "/fleet/fuel-logs?search=" + clerkDraft.body().get("code").asString(), null)
                .get("meta").get("total").asLong()).isEqualTo(1);
        ok(200, "DELETE", "/fleet/fuel-logs/" + id, null);
        assertThat(as("GET", "/fleet/fuel-logs/" + id, null).status()).isEqualTo(404);

        JsonNode lookups = ok(200, "GET", "/fleet/fuel/lookups", null);
        assertThat(lookups.get("vehicles").toString()).contains(v);
        assertThat(lookups.get("paymentAccounts").toString()).contains(bank);
        assertThat(ok(200, "GET", "/fleet/fuel/lookups/employees?search=Musa", null).toString()).contains(employee);
        assertThat(ok(200, "GET", "/fleet/fuel/lookups/products?search=DSL-" + sfx, null).toString()).contains(fuel);
        assertThat(lookups.get("locations").isArray()).isTrue();
        assertThat(lookups.get("suppliers").toString()).contains(supplier);
        assertThat(lookups.get("costCenters").toString()).contains(costCenter);
        JsonNode overview = ok(200, "GET", "/fleet/fuel/overview", null);
        assertThat(overview.has("openAnomalies")).isTrue();
        assertThat(overview.get("month").has("litres")).isTrue();

        // Tank register rules.
        String loc = location();
        JsonNode t = tank(loc, 2000);
        assertThat(t.get("code").asString()).startsWith("TANK-");
        assertThat(as("POST", "/fleet/fuel-tanks", Map.of("name", "Again", "locationId", loc, "productId", fuel,
                "capacityL", 10)).status()).isEqualTo(409);
        assertThat(msg(as("PATCH", "/fleet/fuel-tanks/" + t.get("id").asString(), Map.of("reorderLevelL", 5000))))
                .isEqualTo("The reorder level cannot exceed the tank capacity");
        JsonNode off = ok(200, "PATCH", "/fleet/fuel-tanks/" + t.get("id").asString(), Map.of("isActive", false));
        assertThat(off.get("isActive").asBoolean()).isFalse();
        assertThat(msg(keyed("/fleet/fuel-logs", Map.of("vehicleId", v, "source", "tank", "tankId",
                t.get("id").asString(), "litres", 10, "odometer", 10)))).endsWith("is inactive");
        assertThat(ok(200, "GET", "/fleet/fuel-tanks", null).toString()).doesNotContain(t.get("id").asString());
        assertThat(ok(200, "GET", "/fleet/fuel-tanks?includeInactive=true", null).toString())
                .contains(t.get("id").asString());
        ok(200, "DELETE", "/fleet/fuel-tanks/" + t.get("id").asString(), null);
    }

    String code(String vehicleId) {
        return jdbc.sql("SELECT code FROM \"FleetVehicle\" WHERE id = ?").param(vehicleId).query(String.class).single();
    }

    // ------------------------------------------------------------------ security

    @Test
    @DisplayName("tenant isolation: another organization sees and touches none of DEMO's fuel records")
    void tenantIsolation() {
        String v = vehicle(tipper, Map.of());
        String loc = location();
        String tankId = tank(loc, 1000).get("id").asString();
        JsonNode draft = money("/fleet/fuel-logs", station(v, 20, 10, "credit", Map.of("supplierId", supplier)));
        String id = draft.get("id").asString();
        String cardId = ok(201, "POST", "/fleet/fuel-cards", Map.of("cardNumber", "99" + Math.abs(id.hashCode())))
                .get("id").asString();
        String dipId = ok(201, "POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 0)).get("id")
                .asString();
        assertThat(call("GET", "/fleet/fuel-logs/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/fuel-logs/" + id, Map.of("litres", 1), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/fuel-logs/" + id + "/post", null, key(otherToken)).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/fuel-cards/" + cardId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/fuel-tanks/" + tankId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/fuel-dips/" + dipId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/fuel-tanks/" + tankId + "/dips", Map.of("dipLitres", 1), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/vehicles/" + v + "/fuel", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/fuel-logs?search=" + draft.get("code").asString(), null, otherToken).body()
                .get("meta").get("total").asLong()).isZero();
        assertThat(call("GET", "/fleet/fuel-tanks", null, otherToken).body().toString()).doesNotContain(tankId);
        assertThat(call("GET", "/fleet/fuel-cards", null, otherToken).body().toString()).doesNotContain(cardId);
        Res foreign = call("POST", "/fleet/fuel-logs", station(v, 20, 10, "credit", Map.of("supplierId", supplier)),
                key(otherToken));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(msg(foreign)).isEqualTo("Vehicle not found");
        assertThat(call("GET", "/fleet/fuel/lookups", null, otherToken).body().get("vehicles").toString())
                .doesNotContain(v);
    }

    @Test
    @DisplayName("authorization matrix: 401 without a token and 403 without the permission on every fuel route")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fleet.web.fuel")).isEqualTo(34);
        Res denied = call("PUT", "/fleet/fuel-settings", Map.of("overfillTolerancePct", 1), clerkToken);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet:settings");
        assertThat(call("GET", "/fleet/fuel-logs", null, clerkToken).status()).isEqualTo(200);
        assertThat(call("POST", "/fleet/fuel-tanks", Map.of(), clerkToken).status()).isEqualTo(403);
    }
}
