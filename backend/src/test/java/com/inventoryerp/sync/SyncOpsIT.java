package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Device ops {@link SyncIT} does not push: master-data upserts and deletes (product, category, register, table, tax,
 * accompaniment / modifier groups, customers), reservations, expenses and device messages.
 */
class SyncOpsIT extends ApiTestSupport {

    String token;
    String deviceId;
    String adminId;
    int seq;

    @BeforeAll
    void fixtures() {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Ops tablet", "platform",
                "android"));
        token = device.get("deviceToken").asString();
        deviceId = device.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status = 'open'")
                .param(deviceId).update();
    }

    Map<String, Object> op(String type, Map<String, Object> payload) {
        seq++;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(List<Map<String, Object>> ops) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", ops), Map.of("X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        JsonNode results = r.body().get("results");
        for (JsonNode res : results) {
            assertThat(res.get("status").asString()).as(res.toString()).isEqualTo("applied");
        }
        return results;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    String deletedAt(String table, String id) {
        return jdbc.sql("SELECT \"deletedAt\"::text FROM \"" + table + "\" WHERE id = ?").param(id).query(String.class)
                .single();
    }

    @Test
    @DisplayName("master data: create, update and delete through device ops")
    void masterData() {
        String category = id();
        String product = id();
        String register = id();
        String table = id();
        String tax = id();
        String sides = id();
        String customer = id();
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("productCategory.upsert", Map.of("id", category, "name", "Device cat")));
        ops.add(op("tax.upsert", Map.of("id", tax, "name", "Device VAT", "rate", 18, "code", "DV" + seq)));
        ops.add(op("product.upsert", Map.of("id", product, "name", "Device tea", "salesPrice", 300, "costPrice", 100,
                "categoryId", category, "productType", "service")));
        ops.add(op("product.upsert", Map.of("id", product, "name", "Device green tea", "salesPrice", 350)));
        ops.add(op("cashRegister.upsert", Map.of("id", register, "name", "Device till")));
        ops.add(op("cashRegister.upsert", Map.of("id", register, "name", "Device till 2")));
        ops.add(op("posTable.upsert", Map.of("id", table, "number", 70_000 + new java.util.Random().nextInt(20_000),
                "seats", 4, "zone", "patio")));
        ops.add(op("accompanimentGroup.upsert", Map.of("id", sides, "name", "Device sides", "minSelect", 0,
                "maxSelect", 1, "options", List.of(Map.of("id", id(), "name", "Rice", "priceImpact", 0)))));
        ops.add(op("customer.upsert", Map.of("id", customer, "name", "Device Dan", "phone", "+256711")));
        push(ops);

        assertThat(jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(product).query(String.class).single())
                .isEqualTo("Device green tea");
        assertThat(jdbc.sql("SELECT name FROM \"CashRegister\" WHERE id = ?").param(register).query(String.class)
                .single()).isEqualTo("Device till 2");
        assertThat(jdbc.sql("SELECT seats FROM \"PosTable\" WHERE id = ?").param(table).query(Integer.class).single())
                .isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AccompanimentOption\" WHERE \"groupId\" = ?").param(sides)
                .query(Long.class).single()).isEqualTo(1);

        push(List.of(op("product.delete", Map.of("id", product)), op("productCategory.delete", Map.of("id", category)),
                op("cashRegister.delete", Map.of("id", register)), op("posTable.delete", Map.of("id", table)),
                op("tax.delete", Map.of("id", tax)), op("accompanimentGroup.delete", Map.of("id", sides)),
                op("customer.delete", Map.of("id", customer))));
        assertThat(deletedAt("Product", product)).isNotNull();
        assertThat(deletedAt("ProductCategory", category)).isNotNull();
        assertThat(deletedAt("Tax", tax)).isNotNull();
        assertThat(deletedAt("Partner", customer)).isNotNull();
        assertThat(jdbc.sql("SELECT active FROM \"PosTable\" WHERE id = ?").param(table).query(Boolean.class).single())
                .isFalse();
    }

    @Test
    @DisplayName("reservations: create, cancel and no-show from a device")
    void reservations() {
        String table = ok(201, "POST", "/pos/tables", Map.of("name", "Sync res " + seq, "number",
                90_000 + new java.util.Random().nextInt(9_000)))
                .get("id").asString();
        Instant start = Instant.now().plusSeconds(86_400 * 3);
        JsonNode created = push(List.of(op("reservation.create", Map.of("tableId", table, "customerName", "Ola",
                "startAt", start.toString(), "endAt", start.plusSeconds(5400).toString(), "partySize", 2))));
        String first = created.get(0).get("mapping").get("reservationId").asString();
        JsonNode second = push(List.of(op("reservation.create", Map.of("tableId", table, "customerName", "Pia",
                "startAt", start.plusSeconds(86_400).toString(), "endAt", start.plusSeconds(91_800).toString(),
                "partySize", 3))));
        String other = second.get(0).get("mapping").get("reservationId").asString();
        push(List.of(op("reservation.cancel", Map.of("reservationId", first)),
                op("reservation.noShow", Map.of("reservationId", other))));
        assertThat(jdbc.sql("SELECT status::text FROM \"PosTableReservation\" WHERE id = ?").param(first)
                .query(String.class).single()).isEqualTo("cancelled");
        Res missing = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops",
                List.of(op("reservation.seat", Map.of()))), Map.of("X-Device-Token", token));
        assertThat(missing.body().get("results").get(0).get("status").asString()).isNotEqualTo("applied");
    }

    @Test
    @DisplayName("expense and a message to a device-visible conversation")
    void expenseAndMessage() {
        String expenseAcc = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5200'")
                .param(demoOrg).query(String.class).single();
        String cat = ok(201, "POST", "/expense-categories", Map.of("name", "Device " + seq + UUID.randomUUID()
                .toString().substring(0, 4), "ledgerAccountId", expenseAcc)).get("id").asString();
        String supplier = ok(201, "POST", "/partners", Map.of("code", "DSUP-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Device supplier", "isSupplier", true)).get("id").asString();
        JsonNode exp = push(List.of(op("expense.create", Map.of("title", "Device ice", "amount", 25, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CREDIT", "categoryId", cat, "supplierId", supplier))));
        String expenseId = exp.get(0).get("mapping").get("expenseId").asString();
        assertThat(jdbc.sql("SELECT title FROM \"Expense\" WHERE id = ?").param(expenseId).query(String.class)
                .single()).isEqualTo("Device ice");

        String conv = ok(200, "POST", "/communication/conversations", Map.of("kind", "group", "name", "Floor",
                "visibility", "org")).get("id").asString();
        jdbc.sql("UPDATE \"Conversation\" SET \"syncToDevices\" = true WHERE id = ?").param(conv).update();
        String messageId = id();
        push(List.of(op("message.send", Map.of("clientId", messageId, "conversationId", conv, "body",
                "Table 4 ready"))));
        push(List.of(op("message.markRead", Map.of("conversationId", conv, "messageId", messageId))));
        assertThat(jdbc.sql("SELECT body FROM \"Message\" WHERE id = ?").param(messageId).query(String.class).single())
                .isEqualTo("Table 4 ready");
    }
}
