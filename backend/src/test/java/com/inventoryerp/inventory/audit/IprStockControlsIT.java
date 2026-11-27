package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.security.Permissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 3, gate G3: the maker-checker and operational controls on stock.
 *
 * <ul>
 *   <li>S2: a new organisation starts with the stock adjustment, stock write-off and inventory valuation pairs on;
 *       existing organisations keep theirs.</li>
 *   <li>S9: an approval through the engine by someone who made none of the document clears the write-off pair.</li>
 *   <li>S-4: direct stock under the pair: no self-approval; a missing approver never matches another user.</li>
 *   <li>S3: a transit loss above the threshold needs a reason and an approval.</li>
 *   <li>C-5 / S-7 / S14: the manual reservation routes reserve and consume only {@code manual} sources.</li>
 *   <li>S5: a replenishment alert whose transfer is cancelled opens again.</li>
 *   <li>OPS-2: an inactive approver is not notified of a pending request.</li>
 * </ul>
 */
class IprStockControlsIT extends InventoryAuditSupport {

    private static final String PASSWORD = "Control@12345";
    private static final String PIN = "2468";
    private static final Pattern REQUEST_ID = Pattern.compile("Pending approval request ([0-9a-f-]{36})");

    /** A second administrator of the audit org with a PIN: the checker. */
    String checkerId;
    String checkerToken;
    /** A third administrator with a PIN: makes documents. */
    String makerId;
    String makerToken;

    @BeforeAll
    void users() {
        String[] checker = user(true);
        checkerId = checker[0];
        checkerToken = checker[1];
        String[] maker = user(true);
        makerId = maker[0];
        makerToken = maker[1];
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ? WHERE id = ?").params(passwords.hash(PIN), auditUserId).update();
    }

    /** {id, token} of a new audit-org user holding every permission, with the PIN. */
    String[] user(boolean active) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "Control " + role,
                        Permissions.ALL.toArray(String[]::new)).update();
        String email = "control-" + UUID.randomUUID().toString().substring(0, 8) + "@audit.test";
        String id = createUser(org, email, PASSWORD, role);
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ? WHERE id = ?").params(passwords.hash(PIN), id).update();
        String token = login(orgCode, email, PASSWORD);
        if (!active) {
            jdbc.sql("UPDATE \"User\" SET \"isActive\" = false WHERE id = ?").param(id).update();
        }
        return new String[] {id, token};
    }

    void sod(String pair, boolean on) {
        okA(200, "PUT", "/approval-controls/sod-rules/" + pair, Map.of("isActive", on));
    }

    String workflow(String entityType) {
        return okA(201, "POST", "/approval-workflows", Map.of("name", "IPR " + entityType + " " + UUID.randomUUID(),
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

    Res as(String token, String method, String path, Object body) {
        return call(method, path, body, token);
    }

    String stockOut(String token, String productId, int qty) {
        Res r = as(token, "POST", "/inventory/stock-outs", Map.of("locationId", main, "responsibleById", auditUserId,
                "approvedById", auditUserId, "category", "sample", "items", List.of(Map.of("productId", productId,
                        "qty", qty))));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("id").asString();
    }

    // ------------------------------------------------------------------ S2

    @Test
    @DisplayName("S2: a new organisation starts with the three inventory pairs on; an existing one is unchanged")
    void newOrganisationSeedsTheInventoryPairs() {
        String code = "ipr-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "IPR Controls Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner",
                "adminPassword", "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        Map<String, Boolean> rules = new HashMap<>();
        for (JsonNode r : call("GET", "/approval-controls/sod-rules", null, token).body()) {
            rules.put(r.get("pairKey").asString(), r.get("isActive").asBoolean());
        }
        assertThat(rules).containsEntry(SegregationOfDutiesService.STOCK_ADJUSTMENT, true)
                .containsEntry(SegregationOfDutiesService.STOCK_WRITE_OFF, true)
                .containsEntry(SegregationOfDutiesService.INVENTORY_VALUATION, true)
                .containsEntry(SegregationOfDutiesService.DOCUMENT_REVERSAL, false);
        // The audit org was created before (not through bootstrap): nothing switched on.
        Map<String, Boolean> existing = new HashMap<>();
        for (JsonNode r : okA(200, "GET", "/approval-controls/sod-rules", null)) {
            existing.put(r.get("pairKey").asString(), r.get("isActive").asBoolean());
        }
        assertThat(existing).containsEntry(SegregationOfDutiesService.STOCK_ADJUSTMENT, false)
                .containsEntry(SegregationOfDutiesService.STOCK_WRITE_OFF, false);
    }

    // ------------------------------------------------------------------ S9

    @Test
    @DisplayName("S9: an engine approval by a non-maker clears the write-off pair; a maker's own approval does not")
    void engineApprovalByACheckerClearsTheWriteOffPair() {
        String p = product("IPR-S9", Map.of("costPrice", 3));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 3))));
        sod(SegregationOfDutiesService.STOCK_WRITE_OFF, true);
        String wf = workflow("stock_out");
        try {
            String out = stockOut(makerToken, p, 2);
            String path = "/inventory/stock-outs/" + out + "/approve";
            String request = pendingRequest(as(makerToken, "POST", path, null));
            Res decided = as(checkerToken, "POST", "/approvals/" + request + "/decide", Map.of("status", "approved"));
            assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
            Res posted = as(makerToken, "POST", path, null);
            assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
            assertThat(onHand(p, main)).isEqualByComparingTo("8");

            // The maker approving their own request never clears the pair.
            String own = stockOut(makerToken, p, 1);
            String ownPath = "/inventory/stock-outs/" + own + "/approve";
            String ownRequest = pendingRequest(as(makerToken, "POST", ownPath, null));
            Res selfDecided = as(makerToken, "POST", "/approvals/" + ownRequest + "/decide",
                    Map.of("status", "approved"));
            if (selfDecided.status() == 201) {
                Res refused = as(makerToken, "POST", ownPath, null);
                assertThat(refused.status()).as(refused.raw().body()).isEqualTo(403);
            }
            assertThat(onHand(p, main)).isEqualByComparingTo("8");
        } finally {
            okA(200, "DELETE", "/approval-workflows/" + wf, null);
            sod(SegregationOfDutiesService.STOCK_WRITE_OFF, false);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ S-4

    @Test
    @DisplayName("S-4: under the pair a direct stock move cannot be self-approved; another approver with PIN can")
    void directStockNoSelfApprovalUnderSod() {
        String p = product("IPR-S4", Map.of("costPrice", 2));
        sod(SegregationOfDutiesService.STOCK_ADJUSTMENT, true);
        sod(SegregationOfDutiesService.STOCK_WRITE_OFF, true);
        try {
            Res self = api("POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 4, 2))));
            assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
            assertThat(self.body().get("message").asString()).startsWith("Separation of duties");
            assertThat(onHand(p, main)).isEqualByComparingTo("0");

            Map<String, Object> named = new LinkedHashMap<>(direct(main, List.of(item(p, 4, 2))));
            named.put("approvedById", checkerId);
            named.put("approverPin", PIN);
            okA(200, "POST", "/inventory/direct-stock/in", named);
            assertThat(onHand(p, main)).isEqualByComparingTo("4");

            Res selfOut = api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
            assertThat(selfOut.status()).as(selfOut.raw().body()).isEqualTo(403);
            Map<String, Object> namedOut = new LinkedHashMap<>(direct(main, List.of(item(p, 1, null))));
            namedOut.put("approvedById", checkerId);
            namedOut.put("approverPin", PIN);
            okA(200, "POST", "/inventory/direct-stock/out", namedOut);
            assertThat(onHand(p, main)).isEqualByComparingTo("3");
        } finally {
            sod(SegregationOfDutiesService.STOCK_ADJUSTMENT, false);
            sod(SegregationOfDutiesService.STOCK_WRITE_OFF, false);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("S-4: a missing approvedById never matches some other active user, even with a valid PIN")
    void missingApproverNeverMatchesAnyUser() {
        String p = product("IPR-S4N", Map.of("costPrice", 2));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationId", main);
        body.put("responsibleById", makerId);
        body.put("approverPin", PIN);
        body.put("items", List.of(item(p, 2, 2)));
        Res r = as(makerToken, "POST", "/inventory/direct-stock/in", body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString())
                .isEqualTo("Name the approver (approvedById) to post a direct stock movement");
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ S3

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

    @Test
    @DisplayName("S3: a transit loss above the threshold needs a reason and an approval; under the pair not the receiver")
    void transitLossNeedsReasonAndApproval() {
        String p = product("IPR-S3", Map.of("costPrice", 5));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 20, 5))));
        JsonNode trf = dispatchedTransit(p, 10);
        String path = "/inventory/transfers/" + trf.get("id").asString() + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();

        Res noReason = api("POST", path, receipt(itemId, 1, 1, 0, null, null, null));
        assertThat(noReason.status()).as(noReason.raw().body()).isEqualTo(400);
        assertThat(noReason.body().get("message").asString()).startsWith("Give a reason (lossReason)");
        Res noApprover = api("POST", path, receipt(itemId, 1, 1, 0, "crushed pallet", null, null));
        assertThat(noApprover.status()).as(noApprover.raw().body()).isEqualTo(400);
        assertThat(noApprover.body().get("message").asString()).startsWith("Name the approver (approvedById)");
        assertThat(onHand(p, alt)).isEqualByComparingTo("0");

        // Self-approval with the approver permission while the pair is off.
        JsonNode first = okA(200, "POST", path, receipt(itemId, 1, 1, 0, "crushed pallet", auditUserId, null));
        assertThat(first.get("status").asString()).isEqualTo("partially_received");

        sod(SegregationOfDutiesService.STOCK_WRITE_OFF, true);
        try {
            Res self = api("POST", path, receipt(itemId, 1, 0, 1, "missing carton", auditUserId, null));
            assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
            assertThat(self.body().get("message").asString()).startsWith("Separation of duties");
            okA(200, "POST", path, receipt(itemId, 1, 0, 1, "missing carton", checkerId, PIN));
        } finally {
            sod(SegregationOfDutiesService.STOCK_WRITE_OFF, false);
        }

        // At or below the threshold no reason is needed (setting, base currency).
        orgSetting("inventory.transitLossApprovalThreshold", "100");
        try {
            okA(200, "POST", path, receipt(itemId, 1, 1, 0, null, null, null));
        } finally {
            orgSetting("inventory.transitLossApprovalThreshold", "0");
        }
        assertThat(onHand(p, alt)).isEqualByComparingTo("3");
        String reason = asOrg(() -> jdbc.sql("SELECT notes FROM \"StockTransferReceipt\" WHERE \"transferId\" = ?"
                + " AND notes LIKE '%crushed pallet%' LIMIT 1").param(trf.get("id").asString()).query(String.class)
                .optional().orElse(null));
        assertThat(reason).contains("Transit loss");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("S3: through the approval engine the loss waits for a checker, and the approval is used once")
    void transitLossThroughTheApprovalEngine() {
        String p = product("IPR-S3E", Map.of("costPrice", 4));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 4))));
        JsonNode trf = dispatchedTransit(p, 6);
        String path = "/inventory/transfers/" + trf.get("id").asString() + "/receive";
        String itemId = trf.get("items").get(0).get("id").asString();
        String wf = workflow("stock_transfer_loss");
        try {
            String request = pendingRequest(api("POST", path, receipt(itemId, 2, 1, 0, "wet boxes", null, null)));
            assertThat(onHand(p, alt)).isEqualByComparingTo("0");
            Res decided = as(checkerToken, "POST", "/approvals/" + request + "/decide", Map.of("status", "approved"));
            assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
            okA(200, "POST", path, receipt(itemId, 2, 1, 0, "wet boxes", null, null));
            assertThat(onHand(p, alt)).isEqualByComparingTo("2");
            // The approval was used: the next loss asks again.
            pendingRequest(api("POST", path, receipt(itemId, 1, 1, 0, "wet boxes", null, null)));
        } finally {
            okA(200, "DELETE", "/approval-workflows/" + wf, null);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ C-5

    @Test
    @DisplayName("C-5: the manual routes reserve and consume only manual sources; release stays open")
    void manualReservationsOnly() {
        String p = product("IPR-C5");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 1))));
        String src = "SO-" + UUID.randomUUID();
        Res doc = api("POST", "/inventory/reservations", Map.of("productId", p, "locationId", main, "quantity", 3,
                "sourceType", "order", "sourceId", src));
        assertThat(doc.status()).as(doc.raw().body()).isEqualTo(400);
        assertThat(doc.body().get("message").asString()).startsWith("Only manual reservations can be created here");
        okA(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", main, "quantity", 3,
                "sourceType", "manual", "sourceId", src));
        Res consume = api("POST", "/inventory/reservations/consume", Map.of("sourceType", "order", "sourceId", src));
        assertThat(consume.status()).as(consume.raw().body()).isEqualTo(400);
        assertThat(okA(201, "POST", "/inventory/reservations/consume", Map.of("sourceType", "manual", "sourceId", src))
                .get("consumed").asInt()).isEqualTo(1);
        assertThat(okA(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "order", "sourceId", src))
                .get("released").asInt()).isZero();
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ S5

    @Test
    @DisplayName("S5: an alert whose transfer is cancelled opens again, and the rule raises it again")
    void replenishmentAlertReopensWhenItsTransferIsCancelled() {
        String p = product("IPR-S5");
        okA(200, "POST", "/inventory/direct-stock/in", direct(alt, List.of(item(p, 50, 1))));
        String rule = okA(201, "POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", main,
                "minQty", 10, "maxQty", 20, "route", "transfer", "sourceLocationId", alt)).get("id").asString();
        okA(201, "POST", "/inventory/replenishment/scan", null);
        String alert = asOrg(() -> jdbc.sql("SELECT id FROM \"InventoryReplenishmentAlert\" WHERE \"ruleId\" = ?")
                .param(rule).query(String.class).single());
        okA(201, "POST", "/inventory/replenishment/requisition", Map.of("alertIds", List.of(alert)));
        Map<String, Object> answered = asOrg(() -> jdbc.sql("SELECT status, \"documentId\" FROM"
                + " \"InventoryReplenishmentAlert\" WHERE id = ?").param(alert).query().singleRow());
        assertThat(answered.get("status")).isEqualTo("requisitioned");
        String transfer = (String) answered.get("documentId");
        okA(200, "POST", "/inventory/transfers/" + transfer + "/cancel", null);

        JsonNode scan = okA(201, "POST", "/inventory/replenishment/scan", null);
        Map<String, Object> reopened = asOrg(() -> jdbc.sql("SELECT status, \"statusReason\" FROM"
                + " \"InventoryReplenishmentAlert\" WHERE id = ?").param(alert).query().singleRow());
        assertThat(reopened.get("status")).as(reopened.toString()).isEqualTo("open");
        assertThat((String) reopened.get("statusReason")).startsWith("Re-opened");
        assertThat(scan.get("raised").asInt()).isEqualTo(1);
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ OPS-2

    @Test
    @DisplayName("OPS-2: approvers notified of a pending request are active, non-deleted users only")
    void inactiveApproverIsNotNotified() {
        String inactive = user(false)[0];
        String p = product("IPR-OPS2", Map.of("costPrice", 1));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 5, 1))));
        String wf = workflow("stock_out");
        try {
            String out = stockOut(makerToken, p, 1);
            String request = pendingRequest(as(makerToken, "POST", "/inventory/stock-outs/" + out + "/approve", null));
            drainOutboxFully();
            long toInactive = asOrg(() -> jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ?"
                    + " AND payload->>'requestId' = ?").params(inactive, request).query(Long.class).single());
            long toChecker = asOrg(() -> jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ?"
                    + " AND payload->>'requestId' = ?").params(checkerId, request).query(Long.class).single());
            assertThat(toInactive).isZero();
            assertThat(toChecker).isEqualTo(1);
        } finally {
            okA(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }
}
