package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-S083 O6 — web/Android parity. The same logical op stream ({@code contract-tests/sync-protocol/parity/
 * stream.json}) as each client puts it on the wire: {@code android.json} (Android {@code SyncParityGoldenTest}) and
 * {@code web.json} (web {@code parity.test.ts}), golden files the clients' own tests keep current. Each batch is pushed
 * from its own device (protocol 2) with fresh client ids; the server state each produces must be identical: invoices,
 * payments, GL by account, the shift and its cash movements, the customer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncClientParityIT extends ApiTestSupport {

    static final Path DIR = Path.of("../contract-tests/sync-protocol/parity");
    static final Instant STREAM_END = Instant.parse("2026-11-21T09:30:00Z");
    static final Pattern CLIENT_ID = Pattern.compile("\"\\$(\\w+)\"");
    static final java.time.format.DateTimeFormatter MILLIS = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);
    static final java.time.format.DateTimeFormatter SECONDS = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC);
    static final Pattern OCCURRED = Pattern.compile("\"occurredAt\"\\s*:\\s*\"([^\"]+)\"");

    final JsonMapper json = JsonMapper.builder().build();
    final List<String> registers = new ArrayList<>();
    final List<String> devices = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String d : devices) {
            jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status IN ('open',"
                    + " 'investigating')").param(d).update();
        }
        forceCloseSessions(registers);
    }

    record Run(Map<String, String> ids, JsonNode results) {}

    /** Pushes one client's golden batch as that client's device, after binding the placeholders to fresh values. */
    Run push(String client) throws Exception {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Parity " + client + " "
                + UUID.randomUUID().toString().substring(0, 6), "platform", client.equals("android") ? "android" : "web"));
        String deviceId = device.get("id").asString();
        devices.add(deviceId);
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String register = ok(201, "POST", "/cash-registers", Map.of("code", "PAR-" + suffix, "name", "Parity till "
                + suffix)).get("id").asString();
        registers.add(register);
        String product = ok(201, "POST", "/products", Map.of("code", "PAR-" + suffix, "name", "Parity tea " + suffix,
                "productType", "service", "trackInventory", false, "salesPrice", 60, "costPrice", 10)).get("id")
                .asString();
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();

        String text = Files.readString(DIR.resolve(client + ".json"))
                .replace("{{deviceId}}", deviceId).replace("{{actor}}", admin).replace("{{registerId}}", register)
                .replace("{{productId}}", product).replace("{{suffix}}", suffix)
                .replace("{{counterAccountId}}", jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code ="
                        + " '3100'").param(demoOrg).query(String.class).single());
        Map<String, String> ids = new LinkedHashMap<>();
        text = replaceAll(CLIENT_ID, text, m -> "\"" + ids.computeIfAbsent(m.group(1), k -> UUID.randomUUID().toString())
                + "\"");
        // The stream is dated 2026-11-21; move it to now (same offsets) in the client's own timestamp format.
        Duration shift = Duration.between(STREAM_END, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        text = replaceAll(OCCURRED, text, m -> {
            Instant at = Instant.parse(m.group(1)).plus(shift);
            return "\"occurredAt\": \"" + (m.group(1).contains(".") ? MILLIS : SECONDS).format(at) + "\"";
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> batch = json.readValue(text, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) batch.get("ops");
        for (Map<String, Object> op : ops) {
            if ("cash_session.close".equals(op.get("type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) op.get("payload");
                op.put("payload", withUncountedTenders(payload)); // the org's tracked tenders, same for both clients
            }
        }
        // One open shift per cashier, and stray kitchen orders block a close: start each client clean.
        jdbc.sql("UPDATE \"CashSession\" SET status = 'closed', \"closedAt\" = now() WHERE \"organizationId\" = ? AND"
                + " status = 'open'").param(demoOrg).update();
        releaseHeldOrders();
        Res r = call("POST", "/sync/push", batch, Map.of("X-Device-Token", device.get("deviceToken").asString(),
                "X-Sync-Protocol", "2.0", "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return new Run(ids, r.body().get("results"));
    }

    static String replaceAll(Pattern p, String text, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The server state a run produced, with every id and date that legitimately differs left out. */
    Map<String, Object> state(Run run) {
        Map<String, Object> s = new LinkedHashMap<>();
        List<String> statuses = new ArrayList<>();
        run.results().forEach(r -> statuses.add(r.get("status").asString()));
        s.put("statuses", statuses);
        String session = run.results().get(0).get("mapping").get(run.ids().get("open")).asString();
        List<String> invoices = List.of(run.results().get(1).get("mapping").get("invoiceId").asString(),
                run.results().get(3).get("mapping").get("invoiceId").asString());
        List<Map<String, Object>> inv = new ArrayList<>();
        for (String id : invoices) {
            inv.add(jdbc.sql("SELECT i.subtotal, i.\"totalAmount\", i.\"amountResidual\", i.status::text AS status,"
                    + " (i.\"cashSessionId\" = ?) AS \"inShift\", (i.\"partnerId\" = ?) AS \"toCustomer\","
                    + " (SELECT COALESCE(sum(a.amount), 0) FROM \"PaymentAllocation\" a WHERE a.\"invoiceId\" = i.id) AS paid"
                    + " FROM \"Invoice\" i WHERE i.id = ?").params(session, run.ids().get("cust"), id).query().singleRow());
        }
        s.put("invoices", normalize(inv));
        s.put("shift", normalize(List.of(jdbc.sql("SELECT status::text AS status, \"openingFloat\", \"closingCounted\","
                + " \"closingExpected\", \"closingDifference\" FROM \"CashSession\" WHERE id = ?").param(session).query()
                .singleRow())));
        s.put("movements", normalize(jdbc.sql("SELECT \"movementType\"::text AS type, amount FROM \"CashMovement\""
                + " WHERE \"cashSessionId\" = ? ORDER BY \"movementType\", amount").param(session).query().listOfRows()));
        s.put("customer", normalize(List.of(jdbc.sql("SELECT left(name, 16) AS name, phone, \"isCustomer\" FROM"
                + " \"Partner\" WHERE id = ?").param(run.ids().get("cust")).query().singleRow())));
        // GL by account code: the invoices' and their payments' journal entries, and anything sourced from the shift.
        List<Object> sources = new ArrayList<>(invoices);
        sources.add(session);
        sources.addAll(jdbc.sql("SELECT DISTINCT a.\"paymentId\" FROM \"PaymentAllocation\" a WHERE a.\"invoiceId\" ="
                + " ANY(?::text[])").param(invoices.toArray(String[]::new)).query(String.class).list());
        sources.addAll(jdbc.sql("SELECT id FROM \"CashMovement\" WHERE \"cashSessionId\" = ?").param(session)
                .query(String.class).list());
        String[] src = sources.stream().map(String::valueOf).toArray(String[]::new);
        Map<String, BigDecimal> gl = new TreeMap<>();
        jdbc.sql("SELECT acc.code, sum(l.debit - l.credit) AS net FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" JOIN \"Account\" acc ON acc.id = l.\"accountId\" WHERE e.\"organizationId\""
                + " = ? AND (e.\"sourceId\" = ANY(?::text[]) OR e.id IN (SELECT \"journalEntryId\" FROM \"Invoice\" WHERE"
                + " id = ANY(?::text[])) OR e.id IN (SELECT p.\"journalEntryId\" FROM \"Payment\" p WHERE p.id ="
                + " ANY(?::text[]))) GROUP BY acc.code").params(demoOrg, src, invoices.toArray(String[]::new), src)
                .query().listOfRows().forEach(r -> gl.merge(
                        // each till has its own drawer account (DRW-<register code>): compare it as "the drawer"
                        String.valueOf(r.get("code")).startsWith("DRW-") ? "DRW-<register>" : String.valueOf(r.get("code")),
                        ((BigDecimal) r.get("net")).stripTrailingZeros(), BigDecimal::add));
        gl.values().removeIf(v -> v.signum() == 0);
        s.put("gl", gl);
        return s;
    }

    static List<String> statuses(Run run) {
        List<String> out = new ArrayList<>();
        run.results().forEach(r -> out.add(r.get("status").asString()));
        return out;
    }

    static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : v));
            out.add(m);
        }
        return out;
    }

    @Test
    @DisplayName("the same op stream from the Android and the web client gives identical server state")
    void sameStreamSameState() throws Exception {
        JsonNode androidBatch = json.readTree(Files.readString(DIR.resolve("android.json")));
        JsonNode webBatch = json.readTree(Files.readString(DIR.resolve("web.json")));
        assertThat(androidBatch.get("ops").size()).isEqualTo(webBatch.get("ops").size());

        List<String> allApplied = List.of("applied", "applied", "applied", "applied", "applied", "applied");
        Run android = push("android");
        assertThat(statuses(android)).as(android.results().toString()).isEqualTo(allApplied);
        Map<String, Object> a = state(android);
        Run web = push("web");
        assertThat(statuses(web)).as(web.results().toString()).isEqualTo(allApplied);
        Map<String, Object> w = state(web);
        assertThat(w).isEqualTo(a);
        // And the state is the stream's: 0 float + 60 + 120 + 10 pay-in = 190 expected and counted.
        assertThat(a.get("shift").toString()).contains("closingExpected=190").contains("status=closed");
        assertThat(((Map<?, ?>) a.get("gl"))).isNotEmpty();
    }
}
