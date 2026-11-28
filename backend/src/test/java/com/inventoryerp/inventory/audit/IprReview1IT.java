package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.InventoryCountService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * IPR review 1 (stream R1): regression tests for the findings of the independent reviews of the IPR changes.
 *
 * <ul>
 *   <li>R1-1: a POS sale and a cash-session open whose {@code occurredAt} is too old or too far ahead are accepted and
 *       the flag is persisted (report-only {@code offline_late_sync} / {@code offline_clock_skew}).</li>
 *   <li>R1-2 / R1-3: the transit-loss approval threshold compares the transfer's cumulative loss, valued at the
 *       transfer's own transit cost (a FIFO product whose cost price is 0).</li>
 *   <li>R1-4: the movement analysis hides values without {@code inventory_valuation:read}.</li>
 *   <li>R1-5: under the inventory valuation pair the person who previewed an opening-stock file cannot import it.</li>
 *   <li>R1-7: a consumed engine approval no longer clears the write-off pair.</li>
 *   <li>R1-8: the manual reservation route cannot release a document's reservation.</li>
 *   <li>R1-N1: the count submit drains sale stock postings with no transaction open (screen and device).</li>
 *   <li>R1-N4: the transit-loss approval gate runs before the transfer row lock.</li>
 *   <li>R1-N5: a device spot count on a location with another open draft is held, not failed.</li>
 * </ul>
 */
class IprReview1IT extends InventoryAuditSupport {

    private static final String PASSWORD = "Review@12345";
    private static final String PIN = "1357";
    private static final Pattern REQUEST_ID = Pattern.compile("Pending approval request ([0-9a-f-]{36})");

    String checkerId;
    String checkerToken;
    String makerId;
    String makerToken;
    String sessionId;
    String deviceToken;
    String deviceId;
    int seq;

    @BeforeAll
    void fixtures() {
        String[] checker = user();
        checkerId = checker[0];
        checkerToken = checker[1];
        String[] maker = user();
        makerId = maker[0];
        makerToken = maker[1];
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ? WHERE id = ?").params(passwords.hash(PIN), auditUserId).update();
        String register = okA(201, "POST", "/cash-registers", Map.of("code", "R1REG-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Review till")).get("id").asString();
        sessionId = keyed(auditToken, 200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", register,
                "openingFloat", 0)).get("id").asString();
        JsonNode d = okA(201, "POST", "/sync/devices/register", Map.of("name", "Review tablet", "platform", "web"));
        deviceToken = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
    }

    // ------------------------------------------------------------------ helpers

    /** {id, token} of a new audit-org user holding every permission, with the PIN. */
    String[] user() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "Review " + role,
                        Permissions.ALL.toArray(String[]::new)).update();
        String email = "review-" + UUID.randomUUID().toString().substring(0, 8) + "@audit.test";
        String id = createUser(org, email, PASSWORD, role);
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ? WHERE id = ?").params(passwords.hash(PIN), id).update();
        return new String[] {id, login(orgCode, email, PASSWORD)};
    }

    Res keyedRes(String token, String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyed(String token, int status, String method, String path, Object body) {
        Res r = keyedRes(token, method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    void sod(String pair, boolean on) {
        okA(200, "PUT", "/approval-controls/sod-rules/" + pair, Map.of("isActive", on));
    }

    String workflow(String entityType) {
        return okA(201, "POST", "/approval-workflows", Map.of("name", "R1 " + entityType + " " + UUID.randomUUID(),
                "entityType", entityType, "steps", List.of(Map.of("stepOrder", 1, "name", "Checker",
                        "approverPermissions", List.of(Permissions.INVENTORY_DOC__APPROVE), "requiredCount", 1))))
                .get("id").asString();
    }

    static String pendingRequest(Res r) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        Matcher m = REQUEST_ID.matcher(r.body().get("message").asString());
        assertThat(m.find()).as(r.raw().body()).isTrue();
        return m.group(1);
    }

    long flags(String organizationId, String kind) {
        return flags(organizationId, auditUserId, kind);
    }

    long flags(String organizationId, String userId, String kind) {
        return TenantContext.run(TenantStore.user(organizationId, userId), () -> new TransactionTemplate(txm).execute(
                st -> jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ?")
                        .params(organizationId, kind).query(Long.class).single()));
    }

    static Instant ts(Timestamp v) {
        return v == null ? null : v.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    Map<String, Object> op(String type, Map<String, Object> payload, Instant occurredAt) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", auditUserId);
        o.put("occurredAt", occurredAt.toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(Map<String, Object> op) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op)),
                Map.of("X-Device-Token", deviceToken));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    JsonNode dispatchedTransit(String p, int qty) {
        JsonNode trf = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "mode", "transit", "responsibleById", auditUserId, "approvedById", auditUserId,
                "items", List.of(Map.of("productId", p, "qtyRequested", qty))));
        String id = trf.get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        return okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
    }

    static Map<String, Object> receipt(String itemId, int received, int damaged, int shortQty, String reason,
            String approvedById, String pin) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("itemId", itemId);
        line.put("received", received);
        line.put("damaged", damaged);
        line.put("short", shortQty);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        if (reason != null) {
            body.put("lossReason", reason);
        }
        if (approvedById != null) {
            body.put("approvedById", approvedById);
        }
        if (pin != null) {
            body.put("approverPin", pin);
        }
        return body;
    }

    String order(String product, int qty) {
        return okA(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "cashSessionId",
                sessionId, "lines", List.of(new LinkedHashMap<>(Map.of("productId", product, "description", "Item",
                        "quantity", qty, "unitPrice", 100))))).get("id").asString();
    }

    Instant issueDate(String orderId) {
        return asOrg(() -> ts(jdbc.sql("SELECT i.\"issueDate\" FROM \"Invoice\" i JOIN \"Order\" o ON o.\"invoiceId\" ="
                + " i.id WHERE o.id = ?").param(orderId).query(Timestamp.class).single()));
    }

    // ------------------------------------------------------------------ R1-1 occurredAt flags

    @Test
    @DisplayName("R1-1: a POS sale 30 days old is accepted at its time and flagged lateSync once; 10 minutes ahead is"
            + " stored at the receive time and flagged clockSkew")
    void posSaleTimeFlags() {
        String p = product("R1-POS", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 10, 5, null);
        Instant old = Instant.now().minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS);
        long late = flags(org, "offline_late_sync");
        String o1 = order(p, 1);
        keyed(auditToken, 200, "POST", "/pos/orders/" + o1 + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 100)), "occurredAt", old.toString()));
        assertThat(issueDate(o1)).as("kept as sent").isEqualTo(old);
        assertThat(flags(org, "offline_late_sync")).as("one flag for the sale").isEqualTo(late + 1);

        long skew = flags(org, "offline_clock_skew");
        Instant before = Instant.now().minusSeconds(1);
        String o2 = order(p, 1);
        keyed(auditToken, 200, "POST", "/pos/orders/" + o2 + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 100)), "occurredAt",
                Instant.now().plus(Duration.ofMinutes(10)).toString()));
        assertThat(issueDate(o2)).as("receive time").isBetween(before, Instant.now().plusSeconds(1));
        assertThat(flags(org, "offline_clock_skew")).isEqualTo(skew + 1);
        String channel = asOrg(() -> jdbc.sql("SELECT payload->>'channel' FROM \"InventoryException\" WHERE"
                + " \"organizationId\" = ? AND kind = 'offline_clock_skew' ORDER BY \"createdAt\" DESC LIMIT 1")
                .param(org).query(String.class).single());
        assertThat(channel).isEqualTo("online");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("R1-1: a cash-session open 30 days old is accepted and flagged lateSync; 10 minutes ahead is opened"
            + " at the receive time and flagged clockSkew")
    void cashSessionOpenTimeFlags() {
        for (boolean ahead : new boolean[] {false, true}) {
            AuditOrg o = newOrg();
            String register = keyed(o.token(), 201, "POST", "/cash-registers", Map.of("code", "R1C-"
                    + UUID.randomUUID().toString().substring(0, 6), "name", "Flag till")).get("id").asString();
            Instant sent = ahead ? Instant.now().plus(Duration.ofMinutes(10))
                    : Instant.now().minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS);
            Instant before = Instant.now().minusSeconds(1);
            String id = keyed(o.token(), 200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", register,
                    "openingFloat", 0, "occurredAt", sent.toString())).get("id").asString();
            Instant openedAt = TenantContext.run(TenantStore.user(o.id(), o.userId()), () -> new TransactionTemplate(
                    txm).execute(st -> ts(jdbc.sql("SELECT \"openedAt\" FROM \"CashSession\" WHERE id = ?").param(id)
                            .query(Timestamp.class).single())));
            if (ahead) {
                assertThat(openedAt).isBetween(before, Instant.now().plusSeconds(1));
                assertThat(flags(o.id(), o.userId(), "offline_clock_skew")).isEqualTo(1);
            } else {
                assertThat(openedAt).isEqualTo(sent);
                assertThat(flags(o.id(), o.userId(), "offline_late_sync")).isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------------------ R1-2 / R1-3 transit loss

    @Test
    @DisplayName("R1-2: the transit-loss threshold compares the transfer's cumulative loss, so splitting the receipt"
            + " does not avoid the approval")
    void transitLossThresholdIsCumulative() {
        String p = product("R1-SPLIT", Map.of("costPrice", 5));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 40, 5))));
        JsonNode trf = dispatchedTransit(p, 30);
        String path = "/inventory/transfers/" + trf.get("id").asString() + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();
        orgSetting("inventory.transitLossApprovalThreshold", "100");
        try {
            okA(200, "POST", path, receipt(itemId, 8, 12, 0, null, null, null)); // 60 written off
            Res second = api("POST", path, receipt(itemId, 0, 0, 10, null, null, null)); // 50 more: 110 in all
            assertThat(second.status()).as(second.raw().body()).isEqualTo(400);
            assertThat(second.body().get("message").asString()).startsWith("Give a reason (lossReason)").contains("110");
            okA(200, "POST", path, receipt(itemId, 0, 0, 10, "lost carton", auditUserId, null));
        } finally {
            orgSetting("inventory.transitLossApprovalThreshold", "0");
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("R1-3: a FIFO product whose cost price is 0 is valued at its transit cost for the loss threshold")
    void transitLossValuedAtTransitCost() {
        // Two transfers of the same FIFO product are in transit together at different costs (50 and 10): the transit
        // location's average (30) is not what transfer A's goods cost.
        String p = product("R1-FIFO", Map.of("costingMethod", "FIFO", "batchTracking", true, "costPrice", 0));
        receive(p, main, 10, 50, m -> m.batch("R1-FIFO-A"));
        JsonNode trf = dispatchedTransit(p, 10);
        receive(p, main, 10, 10, m -> m.batch("R1-FIFO-B"));
        dispatchedTransit(p, 10);
        String path = "/inventory/transfers/" + trf.get("id").asString() + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();
        orgSetting("inventory.transitLossApprovalThreshold", "100");
        try {
            Res r = api("POST", path, receipt(itemId, 7, 3, 0, null, null, null)); // 3 × 50 = 150, not 3 × 30
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).startsWith("Give a reason (lossReason)").contains("150");
            okA(200, "POST", path, receipt(itemId, 7, 3, 0, "crushed", auditUserId, null));
        } finally {
            orgSetting("inventory.transitLossApprovalThreshold", "0");
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ R1-4 movement analysis

    @Test
    @DisplayName("R1-4: the movement analysis hides every value without inventory_valuation:read")
    void movementAnalysisHidesValues() {
        String p = product("R1-MA", Map.of("costPrice", 5));
        receive(p, main, 10, 5, null);
        issue(p, main, 2, null);
        String reader = readOnlyToken();
        Res r = call("GET", "/inventory/reports/movement-analysis", null, reader);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode s = r.body().get("summary");
        for (String k : List.of("valueIn", "valueOut", "valueAdjusted", "consumedValue", "lostValue", "lossRatePct")) {
            assertThat(s.get(k).isNull()).as("summary." + k + " " + s).isTrue();
        }
        assertThat(s.get("movements").asLong()).isPositive();
        for (JsonNode t : r.body().get("byType")) {
            assertThat(t.get("value").isNull()).as(t.toString()).isTrue();
        }
        for (JsonNode t : r.body().get("trend")) {
            assertThat(t.get("valueIn").isNull() && t.get("valueOut").isNull() && t.get("valueAdjusted").isNull())
                    .as(t.toString()).isTrue();
        }
        for (String list : List.of("topConsumed", "topLost", "topReceived")) {
            for (JsonNode t : r.body().get(list)) {
                assertThat(t.get("value").isNull()).as(list + " " + t).isTrue();
            }
        }
        JsonNode full = okA(200, "GET", "/inventory/reports/movement-analysis", null);
        assertThat(full.get("summary").get("valueIn").isNull()).isFalse();
    }

    /** An audit-org user holding inventory:read only. */
    String readOnlyToken() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "Reader " + role,
                        new String[] {Permissions.INVENTORY__READ}).update();
        String email = "reader-" + UUID.randomUUID().toString().substring(0, 8) + "@audit.test";
        createUser(org, email, PASSWORD, role);
        return login(orgCode, email, PASSWORD);
    }

    // ------------------------------------------------------------------ R1-5 opening import SoD

    @Test
    @DisplayName("R1-5: under the inventory valuation pair the previewer cannot import the file; another person can")
    void openingImportPreviewerCannotImport() {
        String code = "R1OS-" + UUID.randomUUID().toString().substring(0, 6);
        location(code, "Opening " + code, "warehouse");
        String p = product("R1-OS");
        String pCode = asOrg(() -> jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(p).query(String.class)
                .single());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowNo", 1);
        row.put("productCode", pCode);
        row.put("locationCode", code);
        row.put("quantity", "4");
        row.put("unitCost", "3");
        List<Map<String, Object>> rows = List.of(row);
        Res preview = call("POST", "/inventory/opening-stock/preview", Map.of("rows", rows), makerToken);
        assertThat(preview.status()).as(preview.raw().body()).isEqualTo(200);
        String token = preview.body().get("previewToken").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("previewToken", token);
        body.put("approve", true);
        body.put("fileName", "r1.csv");
        sod(SegregationOfDutiesService.INVENTORY_VALUATION, true);
        try {
            Res self = keyedRes(makerToken, "POST", "/inventory/opening-stock/import", body);
            assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
            assertThat(self.body().get("message").asString()).startsWith("Separation of duties");
            Res other = keyedRes(checkerToken, "POST", "/inventory/opening-stock/import", body);
            assertThat(other.status()).as(other.raw().body()).isIn(200, 201);
        } finally {
            sod(SegregationOfDutiesService.INVENTORY_VALUATION, false);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ R1-7 consumed alias approval

    @Test
    @DisplayName("R1-7: an approval already used for an earlier receipt does not clear the write-off pair for the"
            + " receiver's own approval of the next loss")
    void consumedAliasApprovalDoesNotClearThePair() {
        String p = product("R1-ALIAS", Map.of("costPrice", 4));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 4))));
        JsonNode trf = dispatchedTransit(p, 6);
        String path = "/inventory/transfers/" + trf.get("id").asString() + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();
        String wf = workflow("stock_transfer_loss");
        sod(SegregationOfDutiesService.STOCK_WRITE_OFF, true);
        try {
            // First loss: approved by the checker, used by the receiver's receipt.
            String first = pendingRequest(api("POST", path, receipt(itemId, 2, 1, 0, "wet", null, null)));
            assertThat(call("POST", "/approvals/" + first + "/decide", Map.of("status", "approved"), checkerToken)
                    .status()).isEqualTo(201);
            okA(200, "POST", path, receipt(itemId, 2, 1, 0, "wet", null, null));
            // Second loss: requested by another user, approved by the receiver, then received by the receiver.
            String second = pendingRequest(call("POST", path, receipt(itemId, 1, 1, 0, "wet", null, null), makerToken));
            assertThat(api("POST", "/approvals/" + second + "/decide", Map.of("status", "approved")).status())
                    .isEqualTo(201);
            Res own = api("POST", path, receipt(itemId, 1, 1, 0, "wet", null, null));
            assertThat(own.status()).as(own.raw().body()).isEqualTo(403);
            assertThat(own.body().get("message").asString()).startsWith("Separation of duties");
        } finally {
            sod(SegregationOfDutiesService.STOCK_WRITE_OFF, false);
            okA(200, "DELETE", "/approval-workflows/" + wf, null);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ R1-8 reservations

    @Test
    @DisplayName("R1-8: the manual route cannot release a document's reservation; a manual one still releases")
    void manualRouteReleasesManualOnly() {
        String p = product("R1-RES");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 1))));
        String src = "SOL-" + UUID.randomUUID();
        Res doc = api("POST", "/inventory/reservations/release", Map.of("sourceType", "sales_order_line", "sourceId",
                src));
        assertThat(doc.status()).as(doc.raw().body()).isEqualTo(400);
        assertThat(doc.body().get("message").asString()).startsWith("Only manual reservations can be released here");
        okA(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", main, "quantity", 3,
                "sourceType", "manual", "sourceId", src));
        assertThat(okA(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "manual", "sourceId", src))
                .get("released").asInt()).isEqualTo(1);
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ R1-N1 drain outside a transaction

    @Test
    @DisplayName("R1-N1: the count submit drains the sale stock postings with no transaction open, on screen and on"
            + " a device")
    void countDrainRunsOutsideATransaction() {
        String p = product("R1-DRAIN", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 10, 5, null);
        List<Boolean> seen = new CopyOnWriteArrayList<>();
        InventoryCountService.drainObserver = seen::add;
        try {
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("locationId", main);
            start.put("countType", "spot");
            start.put("scopeProductIds", List.of(p));
            JsonNode session = okA(201, "POST", "/inventory/counts/start", start);
            String lineId = session.get("lines").get(0).get("id").asString();
            okA(200, "PATCH", "/inventory/counts/" + session.get("id").asString() + "/draft", Map.of("lines",
                    List.of(Map.of("lineId", lineId, "countedQty", 9, "reason", "counted"))));
            okA(200, "POST", "/inventory/counts/" + session.get("id").asString() + "/submit", Map.of());
            assertThat(seen).as("screen submit").isNotEmpty().containsOnly(false);
            seen.clear();
            JsonNode r = push(op("stock.count", Map.of("locationId", main, "lines", List.of(Map.of("productId", p,
                    "countedQty", 8))), Instant.now()));
            assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
            assertThat(seen).as("device count").isNotEmpty().containsOnly(false);
        } finally {
            InventoryCountService.drainObserver = null;
        }
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ R1-N4 approval before the transfer lock

    @Test
    @DisplayName("R1-N4: the transit-loss approval request is raised before the transfer row is locked")
    void transitLossGateBeforeTheTransferLock() throws Exception {
        String p = product("R1-GATE", Map.of("costPrice", 4));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 4))));
        JsonNode trf = dispatchedTransit(p, 6);
        String transferId = trf.get("id").asString();
        String path = "/inventory/transfers/" + transferId + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();
        String wf = workflow("stock_transfer_loss");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> asOrgRun(() -> {
                jdbc.sql("SELECT id FROM \"StockTransfer\" WHERE id = ? FOR UPDATE").param(transferId).query()
                        .listOfRows();
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Res> receipt = CompletableFuture.supplyAsync(() -> api("POST", path,
                    receipt(itemId, 2, 1, 0, "wet", null, null)));
            long requests = 0;
            for (int i = 0; i < 50 && requests == 0; i++) {
                Thread.sleep(100);
                requests = asOrg(() -> jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ?"
                        + " AND \"entityType\" = 'stock_transfer_loss' AND \"entityId\" = ?").params(org, transferId)
                        .query(Long.class).single());
            }
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            Res r = receipt.get(60, TimeUnit.SECONDS);
            assertThat(requests).as("the request exists while the transfer row is still locked").isEqualTo(1);
            pendingRequest(r);
        } finally {
            release.countDown();
            okA(200, "DELETE", "/approval-workflows/" + wf, null);
        }
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ R1-N5 device count held

    @Test
    @DisplayName("R1-N5: a device spot count on a location with an open back-office cycle count is held for staff,"
            + " not failed, and no second draft is opened")
    void deviceCountHeldBehindAnotherDraft() {
        String loc = location("R1N5-" + UUID.randomUUID().toString().substring(0, 6), "Held count store", "warehouse");
        String p = product("R1-N5");
        receive(p, loc, 10, 5, null);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("locationId", loc);
        start.put("countType", "cycle");
        start.put("scopeProductIds", List.of(p));
        okA(201, "POST", "/inventory/counts/start", start);
        long held = flags(org, "offline_held_posting");
        JsonNode r = push(op("stock.count", Map.of("locationId", loc, "lines", List.of(Map.of("productId", p,
                "countedQty", 7))), Instant.now()));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        assertThat(flags(org, "offline_held_posting")).isEqualTo(held + 1);
        assertThat(asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventoryCountSession\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND status = 'draft'").params(org, loc).query(Long.class).single()))
                .as("only the back-office draft").isEqualTo(1);
        assertThat(onHand(p, loc)).as("nothing posted").isEqualByComparingTo(BigDecimal.TEN);
        assertIntegrityGreen();
    }
}
