package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestApprovers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Procure-to-pay gaps of the readiness audit h-03 (findings F3–F9), its residuals (a) and (b), and h-06 F6. Each test
 * replays the finding's scenario and asserts the corrected outcome; see
 * {@code docs/audits/readiness-gaps/gD-p2p-core.md}.
 */
class ProcureToPayGapsIT extends ApiTestSupport {

    /** ISO 4217 test currency: never a real rate in the seed. */
    static final String TEST_CCY = "XTS";

    String warehouse;
    String functional;
    String approver;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PG-" + suffix(), "name", "P2P gaps "
                + suffix(), "type", "warehouse")).get("id").asString();
        functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        jdbc.sql("INSERT INTO \"Currency\" (id, code, symbol, name, \"updatedAt\") VALUES (?, ?, ?, 'Test currency',"
                + " now()) ON CONFLICT DO NOTHING").params(UUID.randomUUID().toString(), TEST_CCY, TEST_CCY).update();
        approver = TestApprovers.secondAdmin(this);
    }

    // ------------------------------------------------------------------ helpers

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PG-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String type, boolean tracked) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " ?::\"ProductType\", ?, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "PG-" + suffix(), "Item " + suffix(), type, tracked).update();
        return id;
    }

    String stockProduct() {
        return product("stockable", true);
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(productId)
                .query(BigDecimal.class).single();
    }

    BigDecimal onHandAt(String productId, String locationId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(productId, locationId).query(BigDecimal.class).single();
    }

    /** What the AP ledger owes the supplier in the functional currency (credit − debit, lines tagged with them). */
    BigDecimal supplierAp(String partnerId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\""
                + " AND m.key = 'accounts_payable' AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND"
                + " l.\"partnerId\" = ?").params(demoOrg, partnerId).query(BigDecimal.class).single();
    }

    /** The same in the test currency: the open item FX revaluation sees. */
    BigDecimal foreignAp(String partnerId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.credit - l.debit), 0) FROM \"JournalLine\" l JOIN \"AccountMapping\" m"
                + " ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key ="
                + " 'accounts_payable' AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND l.\"partnerId\" = ?"
                + " AND l.\"currencyId\" = ?").params(demoOrg, partnerId, TEST_CCY).query(BigDecimal.class).single();
    }

    /** Net movement (base debit − credit) on a mapped account. */
    BigDecimal mappedBalance(String key) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\""
                + " AND m.key = ? AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ?").params(key, demoOrg)
                .query(BigDecimal.class).single();
    }

    BigDecimal billedQuantity(String grnId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"billedQuantity\"), 0) FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\""
                + " = ?").param(grnId).query(BigDecimal.class).single();
    }

    BigDecimal residual(String documentId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(BigDecimal.class).single();
    }

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, key(adminToken));
    }

    JsonNode money(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static Map<String, Object> line(String productId, Object qty, Object price) {
        return Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price);
    }

    Map<String, Object> order(String supplier, Map<String, Object> extra, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "lines", lines));
        body.putAll(extra);
        return body;
    }

    JsonNode createPo(String supplier, String productId, Object qty, Object price) {
        return ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of(), List.of(line(productId, qty,
                price))));
    }

    JsonNode receive(String poId, String productId, Object qty) {
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))));
    }

    JsonNode adhoc(String supplier, String locationId, String productId, Object qty, Object unitCost) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                locationId, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitCost", unitCost))));
    }

    /** A return-to-vendor note of {@code qty} × {@code price} (no bill named); {@code locationId} may be null. */
    JsonNode returnNote(String supplier, String locationId, String productId, Object qty, Object price) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("direction", "inbound", "partnerId", supplier, "reason",
                "returned_goods", "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity",
                        qty, "unitPrice", price))));
        if (locationId != null) {
            body.put("locationId", locationId);
        }
        return ok(201, "POST", "/procurement/debit-notes", body);
    }

    String postedBill(String supplier, String productId, Object qty, Object price) {
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(line(productId, qty, price)))).get("id").asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of("override3WM", true, "overrideApproval", true));
        return bill;
    }

    String pendingRequest(String entityType, String entityId) {
        return jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = ? AND"
                + " \"entityId\" = ? AND status = 'pending' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(demoOrg, entityType, entityId).query(String.class).optional().orElse(null);
    }

    void decide(String requestId, String status) {
        Map<String, Object> body = "rejected".equals(status) ? Map.of("status", status, "comment", "Not this one")
                : Map.of("status", status);
        Res r = call("POST", "/approvals/" + requestId + "/decide", body, approver);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
    }

    String workflow(String entityType, Object minAmount) {
        return ok(201, "POST", "/approval-workflows", Map.of("name", "P2P gaps " + suffix(), "entityType", entityType,
                "minAmount", minAmount, "steps", List.of(Map.of("stepOrder", 1, "name", "Finance",
                        "approverPermissions", List.of("approvals:decide"), "requiredCount", 1)))).get("id").asString();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    // ------------------------------------------------------------------ F3

    @Test
    @DisplayName("h03-F3: a receipt of a foreign order reverses, and its currency open item closes")
    void foreignReceiptReverses() {
        String supplier = partner("F3 Foreign");
        String product = stockProduct();
        String poId = ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode", TEST_CCY,
                "exchangeRate", 2500), List.of(line(product, 10, 2)))).get("id").asString();
        String grn = receive(poId, product, 10).get("grn").get("id").asString();
        assertThat(supplierAp(supplier)).isEqualByComparingTo("50000");
        assertThat(foreignAp(supplier)).isEqualByComparingTo("20");

        Res reversed = keyed("PATCH", "/procurement/goods-receipts/" + grn + "/reverse", Map.of("reason",
                "F3 wrong delivery"));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(onHand(product)).isEqualByComparingTo("0");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
        assertThat(foreignAp(supplier)).as("the mirror keeps the order currency").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("h03-F3: a cash purchase in another currency settles the payable in that currency too")
    void cashForeignSettlement() {
        String cashier = TestApprovers.secondAdmin(this);
        String bank = accountId("1200");
        Res fund = call("POST", "/accounts/cash-flow/deposit", Map.of("accountId", bank, "counterpartAccountId",
                accountId("3100"), "operationType", "owner_contribution", "amount", 100000, "description",
                "F3 float funding"), key(adminToken));
        assertThat(fund.status()).as(fund.raw().body()).isLessThan(300);
        String code = "F3" + suffix().substring(0, 6);
        String registerId = ok(201, "POST", "/cash-registers", Map.of("code", code, "name", "F3 till")).get("id")
                .asString();
        try {
            Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 60000,
                    "openingSourceAccountId", bank, "notes", "F3 float"), key(cashier));
            assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
            String supplier = partner("F3 Cash");
            String product = stockProduct();
            Res po = call("POST", "/procurement/purchase-orders", order(supplier, Map.of("paymentType", "cash",
                    "currencyCode", TEST_CCY, "exchangeRate", 2500), List.of(line(product, 10, 2))), cashier);
            assertThat(po.status()).as(po.raw().body()).isEqualTo(201);
            Res received = call("POST", "/procurement/purchase-orders/" + po.body().get("id").asString() + "/receive",
                    Map.of("warehouseId", warehouse, "lines", List.of(Map.of("productId", product, "description",
                            "Cement", "quantity", 10))), key(cashier));
            assertThat(received.status()).as(received.raw().body()).isEqualTo(200);
            assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
            assertThat(foreignAp(supplier)).as("Cr AP 20 XTS by the voucher, Dr AP 20 XTS by the settlement")
                    .isEqualByComparingTo("0");
        } finally {
            forceCloseSessions(List.of(registerId));
        }
    }

    // ------------------------------------------------------------------ F4

    @Test
    @DisplayName("h03-F4: a return of unbilled goods clears their GRNI and receipt quantity; the bill for the rest"
            + " matches; reversing the return gives the quantity back")
    void returnOfUnbilledGoods() {
        String supplier = partner("F4 Unbilled");
        String product = stockProduct();
        BigDecimal grni0 = mappedBalance("grni_accrued");
        String grn = adhoc(supplier, warehouse, product, 10, 100).get("id").asString();
        assertThat(mappedBalance("grni_accrued").subtract(grni0)).isEqualByComparingTo("-1000");

        String note = returnNote(supplier, warehouse, product, 2, 100).get("id").asString();
        money("PATCH", "/procurement/debit-notes/" + note + "/post", null);
        assertThat(onHand(product)).isEqualByComparingTo("8");
        assertThat(supplierAp(supplier)).as("never billed, so nothing to credit").isEqualByComparingTo("0");
        assertThat(mappedBalance("grni_accrued").subtract(grni0)).isEqualByComparingTo("-800");
        assertThat(billedQuantity(grn)).isEqualByComparingTo("2");

        // The supplier bills the 8 kept: the bill takes the receipt's 8 open, GRNI clears, AP is the bill.
        postedBill(supplier, product, 8, 100);
        assertThat(onHand(product)).isEqualByComparingTo("8");
        assertThat(mappedBalance("grni_accrued").subtract(grni0)).isEqualByComparingTo("0");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("800");

        money("PATCH", "/procurement/debit-notes/" + note + "/reverse", Map.of("reason", "F4 goods came back"));
        assertThat(onHand(product)).isEqualByComparingTo("10");
        assertThat(billedQuantity(grn)).as("the 2 are open to bill again").isEqualByComparingTo("8");
        assertThat(mappedBalance("grni_accrued").subtract(grni0)).isEqualByComparingTo("-200");
    }

    @Test
    @DisplayName("h03-F4: a note names only a posted bill of its own supplier, in that bill's currency; it lowers it")
    void noteDocumentChecks() {
        String a = partner("F4 A");
        String b = partner("F4 B");
        String service = product("service", false);
        String billB = postedBill(b, service, 1, 100);
        Res foreign = as("POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", a,
                "documentId", billB, "reason", "returned_goods", "lines", List.of(Map.of("description", "Fee",
                        "quantity", 1, "unitPrice", 10))));
        assertThat(foreign.status()).as(foreign.raw().body()).isEqualTo(400);
        assertThat(foreign.body().get("message").asString())
                .isEqualTo("The referenced document must be a posted vendor bill of this supplier");

        String billA = postedBill(a, service, 1, 100);
        Res otherCurrency = as("POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", a,
                "documentId", billA, "reason", "returned_goods", "currencyCode", TEST_CCY, "exchangeRate", 2500,
                "lines", List.of(Map.of("description", "Fee", "quantity", 1, "unitPrice", 10))));
        assertThat(otherCurrency.status()).isEqualTo(400);
        assertThat(otherCurrency.body().get("message").asString())
                .isEqualTo("A debit note against a bill must be in the document's currency (" + functional + ")");

        JsonNode own = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", a,
                "documentId", billA, "reason", "returned_goods", "lines", List.of(Map.of("description", "Fee",
                        "quantity", 1, "unitPrice", 30))));
        assertThat(own.get("currencyCode").asString()).isEqualTo(functional);
        money("PATCH", "/procurement/debit-notes/" + own.get("id").asString() + "/post", null);
        assertThat(residual(billA)).isEqualByComparingTo("70");
        assertThat(supplierAp(a)).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("h03-F4: a note in another currency posts at its rate, tagged with the currency; a rate of 0 is refused")
    void noteInForeignCurrency() {
        String supplier = partner("F4 FX");
        Map<String, Object> body = new LinkedHashMap<>(Map.of("direction", "inbound", "partnerId", supplier, "reason",
                "overcharge", "currencyCode", TEST_CCY, "exchangeRate", 2500, "lines", List.of(Map.of("description",
                        "Freight", "quantity", 1, "unitPrice", 10))));
        String note = ok(201, "POST", "/procurement/debit-notes", body).get("id").asString();
        money("PATCH", "/procurement/debit-notes/" + note + "/post", null);
        assertThat(supplierAp(supplier)).as("10 XTS at 2500").isEqualByComparingTo("25000");
        assertThat(foreignAp(supplier)).isEqualByComparingTo("10");

        body.put("exchangeRate", 0);
        Res zero = as("POST", "/procurement/debit-notes", body);
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.body().get("message").asString()).isEqualTo("exchangeRate must be greater than zero");
    }

    @Test
    @DisplayName("h03-F4: a return without a location ships from the warehouse that received the goods")
    void returnShipsFromReceivingWarehouse() {
        String supplier = partner("F4 Where");
        String product = stockProduct();
        String yard = ok(201, "POST", "/inventory/locations", Map.of("code", "PGY-" + suffix(), "name", "Yard "
                + suffix(), "type", "warehouse")).get("id").asString();
        adhoc(supplier, yard, product, 5, 10);
        String note = returnNote(supplier, null, product, 2, 10).get("id").asString();
        money("PATCH", "/procurement/debit-notes/" + note + "/post", null);
        assertThat(onHandAt(product, yard)).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------ F5

    @Test
    @DisplayName("h03-F5: an open claim or a return on a receipt stops its reversal until it is undone")
    void reversalBlockedByClaimsAndReturns() {
        String supplier = partner("F5 Blocks");
        String product = stockProduct();
        JsonNode grn = adhoc(supplier, warehouse, product, 10, 100);
        String grnId = grn.get("id").asString();
        String number = grn.get("receiptNumber").asString();
        JsonNode claim = ok(201, "POST", "/procurement/claims", Map.of("partnerId", supplier, "type", "shortage",
                "amount", 100, "description", "One short", "goodsReceiptId", grnId));
        Res withClaim = keyed("PATCH", "/procurement/goods-receipts/" + grnId + "/reverse", Map.of("reason", "F5"));
        assertThat(withClaim.status()).as(withClaim.raw().body()).isEqualTo(400);
        assertThat(withClaim.body().get("message").asString()).isEqualTo("Goods receipt " + number
                + " has open supplier claim " + claim.get("claimNumber").asString()
                + " — cancel or reject the claim first");
        ok(200, "POST", "/procurement/claims/" + claim.get("id").asString() + "/cancel", null);

        JsonNode note = returnNote(supplier, warehouse, product, 2, 100);
        money("PATCH", "/procurement/debit-notes/" + note.get("id").asString() + "/post", null);
        Res withReturn = keyed("PATCH", "/procurement/goods-receipts/" + grnId + "/reverse", Map.of("reason", "F5"));
        assertThat(withReturn.status()).isEqualTo(400);
        assertThat(withReturn.body().get("message").asString()).isEqualTo("Goods receipt " + number
                + " has goods returned to the supplier on debit note " + note.get("noteNumber").asString()
                + " — reverse the debit note first");

        money("PATCH", "/procurement/debit-notes/" + note.get("id").asString() + "/reverse", Map.of("reason", "F5"));
        money("PATCH", "/procurement/goods-receipts/" + grnId + "/reverse", Map.of("reason", "F5"));
        assertThat(onHand(product)).isEqualByComparingTo("0");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
    }

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                        + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }

    @Test
    @DisplayName("h03-F5: reversing a receipt counted in pieces of a box order takes back boxes, not pieces")
    void reversalInOrderUnits() {
        String pcs = unit("PCS");
        String box = unit("BOX");
        String product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, ?, ?, now())")
                .params(product, demoOrg, "PGU-" + suffix(), "Soda " + suffix(), pcs, pcs).update();
        ok(200, "PATCH", "/products/" + product, Map.of("units", List.of(Map.of("uomId", box, "factor", 24,
                "isPurchase", true, "isSales", true))));
        String supplier = partner("F5 Units");
        Map<String, Object> poLine = new LinkedHashMap<>(line(product, 3, 24000));
        poLine.put("unitOfMeasureId", box);
        String poId = ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode",
                functional), List.of(poLine))).get("id").asString();
        List<String> grns = new ArrayList<>();
        for (int pieces : new int[] {24, 48}) {
            String grn = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "warehouseId",
                    warehouse, "lines", List.of(Map.of("productId", product, "description", "Soda", "quantity", pieces,
                            "uomId", pcs)))).get("id").asString();
            money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
            grns.add(grn);
        }
        String received = "SELECT \"receivedQuantity\" FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?";
        assertThat(jdbc.sql(received).param(poId).query(BigDecimal.class).single()).isEqualByComparingTo("3");

        money("PATCH", "/procurement/goods-receipts/" + grns.getFirst() + "/reverse", Map.of("reason", "F5 units"));
        assertThat(jdbc.sql(received).param(poId).query(BigDecimal.class).single()).as("3 boxes − 24 pieces (1 box)")
                .isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------ F6

    @Test
    @DisplayName("h03-F6: a bill edited after its approval, or whose approval was rejected, does not post")
    void billApprovalDecisionsCount() {
        String wf = workflow("vendor_bill", 1000000);
        try {
            String supplier = partner("F6 Bills");
            String service = product("service", false);
            String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                    List.of(line(service, 1, 2000000)))).get("id").asString();
            String first = pendingRequest("vendor_bill", bill);
            assertThat(first).isNotNull();
            decide(first, "approved");
            ok(200, "PATCH", "/vendor-bills/" + bill, Map.of("partnerId", supplier, "issueDate", today, "lines",
                    List.of(line(service, 1, 2500000))));
            Res edited = as("POST", "/vendor-bills/" + bill + "/post", Map.of());
            assertThat(edited.status()).as(edited.raw().body()).isEqualTo(403);
            assertThat(edited.body().get("message").asString())
                    .startsWith("This bill changed after it was approved; it needs approval again (request ");
            String second = pendingRequest("vendor_bill", bill);
            assertThat(second).as("a new request survives the refusal").isNotNull().isNotEqualTo(first);
            decide(second, "approved");
            assertThat(ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of()).get("status").asString())
                    .isEqualTo("posted");

            String other = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                    List.of(line(service, 1, 2000000)))).get("id").asString();
            decide(pendingRequest("vendor_bill", other), "rejected");
            Res rejected = as("POST", "/vendor-bills/" + other + "/post", Map.of());
            assertThat(rejected.status()).isEqualTo(403);
            assertThat(rejected.body().get("message").asString()).isEqualTo("This bill's approval was rejected — change"
                    + " the bill and post it again to ask for a new decision");
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("h03-F6: whoever raised a purchase request cannot approve it, even without a workflow")
    void requestSelfApproval() {
        String pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "Gloves", "quantity", 2)))).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + pr + "/submit", null);
        Res self = as("PATCH", "/procurement/purchase-requests/" + pr + "/approve", null);
        assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
        assertThat(self.body().get("message").asString())
                .isEqualTo("You cannot approve a purchase request you raised — another approver must decide");
        Res other = call("PATCH", "/procurement/purchase-requests/" + pr + "/approve", null, approver);
        assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
        assertThat(other.body().get("status").asString()).isEqualTo("approved");
    }

    @Test
    @DisplayName("h03-F6: a draft order whose approval request was withdrawn does not activate on its creator's word")
    void activationPassesTheGate() {
        String wf = workflow("purchase_order", 1000000);
        try {
            String supplier = partner("F6 Orders");
            String product = stockProduct();
            JsonNode po = createPo(supplier, product, 1, 2000000);
            String poId = po.get("id").asString();
            assertThat(po.get("status").asString()).isEqualTo("draft");
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"organizationId\" = ? AND"
                    + " \"entityType\" = 'purchase_order' AND \"entityId\" = ? AND status = 'pending'")
                    .params(demoOrg, poId).update();
            Res activate = as("POST", "/procurement/purchase-orders/" + poId + "/activate", null);
            assertThat(activate.status()).as(activate.raw().body()).isEqualTo(403);
            assertThat(activate.body().get("message").asString())
                    .isEqualTo("This purchase order is awaiting approval. Approve or reject the request first.");
            assertThat(pendingRequest("purchase_order", poId)).as("raised again").isNotNull();
            assertThat(jdbc.sql("SELECT status::text FROM \"PurchaseOrder\" WHERE id = ?").param(poId)
                    .query(String.class).single()).isEqualTo("draft");
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    // ------------------------------------------------------------------ F7

    String raiseClaim(String supplier, String type, Object amount, String poId) {
        String id = ok(201, "POST", "/procurement/claims", Map.of("partnerId", supplier, "type", type, "amount",
                amount, "description", "F7 " + type, "purchaseOrderId", poId)).get("id").asString();
        ok(200, "POST", "/procurement/claims/" + id + "/submit", null);
        return id;
    }

    @Test
    @DisplayName("h03-F7: an accepted claim is capped by what its document leaves open to claims")
    void claimCappedByDocument() {
        String supplier = partner("F7 Cap");
        String product = stockProduct();
        String poId = createPo(supplier, product, 10, 10).get("id").asString();
        receive(poId, product, 10);
        String first = raiseClaim(supplier, "price", 50, poId);
        Res huge = as("POST", "/procurement/claims/" + first + "/accept", Map.of("amount", 5000, "response", "ok"));
        assertThat(huge.status()).as(huge.raw().body()).isEqualTo(400);
        assertThat(huge.body().get("message").asString()).startsWith("Claim CLM-")
                .endsWith(" for 5000 exceeds what the claimed document leaves open to claims (100 of 100); agree a"
                        + " lower amount");
        ok(200, "POST", "/procurement/claims/" + first + "/accept", Map.of("amount", 60, "response", "ok"));

        String second = raiseClaim(supplier, "damage", 50, poId);
        Res over = as("POST", "/procurement/claims/" + second + "/accept", Map.of("response", "ok"));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).contains("(40 of 100)");
        ok(200, "POST", "/procurement/claims/" + second + "/accept", Map.of("amount", 40, "response", "ok"));
    }

    @Test
    @DisplayName("h03-F7: with the supplier-claim pair on, whoever raised a claim cannot accept or settle it")
    void claimMakerChecker() {
        String supplier = partner("F7 SoD");
        String product = stockProduct();
        String poId = createPo(supplier, product, 10, 10).get("id").asString();
        receive(poId, product, 10);
        String rule = "/approval-controls/sod-rules/supplier_claim_settlement";
        ok(200, "PUT", rule, Map.of("isActive", true));
        try {
            String claim = raiseClaim(supplier, "price", 30, poId);
            Res self = as("POST", "/procurement/claims/" + claim + "/accept", Map.of("response", "ok"));
            assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
            assertThat(self.body().get("message").asString()).isEqualTo("Separation of duties: the person who made"
                    + " this document cannot also clear it (Supplier claim settlement)");
            Res accepted = call("POST", "/procurement/claims/" + claim + "/accept", Map.of("response", "ok"), approver);
            assertThat(accepted.status()).as(accepted.raw().body()).isEqualTo(200);
            Res selfSettle = keyed("POST", "/procurement/claims/" + claim + "/settle", Map.of("classification",
                    "price_variance"));
            assertThat(selfSettle.status()).isEqualTo(403);
            Res settled = call("POST", "/procurement/claims/" + claim + "/settle", Map.of("classification",
                    "price_variance"), key(approver));
            assertThat(settled.status()).as(settled.raw().body()).isEqualTo(200);
            assertThat(supplierAp(supplier)).isEqualByComparingTo("70");
        } finally {
            ok(200, "PUT", rule, Map.of("isActive", false));
        }
    }

    // ------------------------------------------------------------------ F8

    @Test
    @DisplayName("h03-F8: a purchase-order receipt is valued at the order price; a different unit cost is refused")
    void receiptValuedAtOrderPrice() {
        String supplier = partner("F8 Price");
        String product = stockProduct();
        JsonNode po = createPo(supplier, product, 10, 100);
        String poId = po.get("id").asString();
        String message = "Receipt line \"Cement\" costs 1000 but the order price is 100; a purchase order receipt is"
                + " valued at the order price — change the order or bill the difference";
        Res inflated = keyed("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 10,
                        "unitCost", 1000))));
        assertThat(inflated.status()).as(inflated.raw().body()).isEqualTo(400);
        assertThat(inflated.body().get("message").asString()).isEqualTo(message);
        Res draft = as("POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "warehouseId", warehouse,
                "lines", List.of(Map.of("purchaseOrderLineId", po.get("lines").get(0).get("id").asString(), "productId",
                        product, "description", "Cement", "quantity", 10, "unitCost", 1000))));
        assertThat(draft.status()).isEqualTo(400);
        assertThat(draft.body().get("message").asString()).isEqualTo(message);

        receive(poId, product, 10);
        assertThat(supplierAp(supplier)).isEqualByComparingTo("1000");
        assertThat(jdbc.sql("SELECT COALESCE(SUM(quantity * \"runningAverageCost\"), 0) FROM \"StockItem\" WHERE \"productId\" = ?")
                .param(product).query(BigDecimal.class).single()).isEqualByComparingTo("1000");
    }

    // ------------------------------------------------------------------ F9

    @Test
    @DisplayName("h03-F9: deleting an order gives its quantity back to the request it was raised from")
    void deleteReturnsRequestBalance() {
        String supplier = partner("F9 Delete");
        String product = stockProduct();
        String pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("productId",
                product, "description", "Bolts", "quantity", 4, "estimatedUnitPrice", 10)))).get("id").asString();
        ok(200, "PATCH", "/procurement/purchase-requests/" + pr + "/submit", null);
        assertThat(call("PATCH", "/procurement/purchase-requests/" + pr + "/approve", null, approver).status())
                .isEqualTo(200);
        String requestLine = ok(200, "GET", "/procurement/purchase-requests/" + pr, null).get("lines").get(0).get("id")
                .asString();
        Res order = call("POST", "/procurement/sourcing/orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "currencyCode", functional, "lines", List.of(Map.of("requestLineId", requestLine))), key(adminToken));
        assertThat(order.status()).as(order.raw().body()).isEqualTo(200);
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + pr, null).get("status").asString())
                .isEqualTo("converted");

        money("DELETE", "/procurement/purchase-orders/" + order.body().get("id").asString(), null);
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + pr, null).get("status").asString())
                .isEqualTo("approved");
        JsonNode open = ok(200, "GET", "/procurement/sourcing/open-lines?requestId=" + pr, null);
        assertThat(open).hasSize(1);
        assertThat(num(open.get(0).get("openQuantity"))).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("h03-F9: receipt and note lines are bounded; a rate of 0 is refused; ad-hoc receipts need a key")
    void validationGaps() {
        String supplier = partner("F9 Validation");
        String product = stockProduct();
        Res negative = as("POST", "/procurement/goods-receipts", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", -5, "unitCost",
                        10))));
        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.body().get("message").asString())
                .isEqualTo("Receipt line \"Cement\": quantity must be a number greater than zero");

        Res noteQty = as("POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", supplier,
                "reason", "other", "lines", List.of(Map.of("description", "x", "quantity", -1, "unitPrice", 1))));
        assertThat(noteQty.status()).isEqualTo(400);
        Res notePrice = as("POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", supplier,
                "reason", "other", "lines", List.of(Map.of("description", "x", "quantity", 1, "unitPrice", -1))));
        assertThat(notePrice.status()).isEqualTo(400);

        Res rate = as("POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode", TEST_CCY,
                "exchangeRate", 0), List.of(line(product, 1, 4))));
        assertThat(rate.status()).isEqualTo(400);
        assertThat(rate.body().get("message").asString()).isEqualTo("exchangeRate must be greater than zero");

        Map<String, String> noKey = Map.of("Authorization", "Bearer " + adminToken, NO_IDEMPOTENCY_KEY, "1");
        Res adhoc = call("POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", product,
                        "description", "Cement", "quantity", 1, "unitCost", 10))), noKey);
        assertThat(adhoc.status()).isEqualTo(400);
        assertThat(onHand(product)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ residuals

    @Test
    @DisplayName("h03 residual (a): a goods receipt without a purchase order must name its supplier")
    void receiptWithoutOrderNeedsSupplier() {
        String product = stockProduct();
        String message = "A goods receipt without a purchase order needs a supplier; select the supplier who delivered"
                + " the goods";
        List<Map<String, Object>> lines = List.of(Map.of("productId", product, "description", "Cement", "quantity", 1,
                "unitCost", 10));
        Res draft = as("POST", "/procurement/goods-receipts", Map.of("warehouseId", warehouse, "lines", lines));
        assertThat(draft.status()).isEqualTo(400);
        assertThat(draft.body().get("message").asString()).isEqualTo(message);
        Res adhoc = as("POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "receivedAt",
                Instant.now().toString(), "lines", lines));
        assertThat(adhoc.status()).isEqualTo(400);
        assertThat(adhoc.body().get("message").asString()).isEqualTo(message);
        assertThat(onHand(product)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("h03 residual (b): concurrent order payments to one supplier never pay more than the ledger owes")
    void concurrentPaymentsToOneSupplier() throws Exception {
        String supplier = partner("Residual B");
        String product = stockProduct();
        String po1 = createPo(supplier, product, 10, 10).get("id").asString();
        receive(po1, product, 10);
        String po2 = createPo(supplier, product, 10, 10).get("id").asString();
        receive(po2, product, 10);
        // 15 of the supplier's unbilled goods returned without a bill: AP falls to 50. (Purchase readiness MEDIUM #5,
        // 2026-11-27: the setup used to return another supplier's goods on this supplier's note, which is now refused.)
        money("PATCH", "/procurement/debit-notes/" + returnNote(supplier, warehouse, product, 15, 10).get("id")
                .asString() + "/post", null);
        assertThat(supplierAp(supplier)).isEqualByComparingTo("50");

        String bankAccount = verifiedSupplierBankAccount(supplier);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Res>> futures = new ArrayList<>();
        try {
            for (String po : List.of(po1, po2, po1, po2)) {
                futures.add(pool.submit(() -> keyed("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of(
                        "amount", 50, "method", "bank", "supplierBankAccountId", bankAccount))));
            }
            int paid = 0;
            for (Future<Res> f : futures) {
                Res r = f.get();
                if (r.status() == 200) {
                    paid++;
                } else {
                    assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
                }
            }
            assertThat(paid).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ h06-F6

    @Test
    @DisplayName("h06-F6: the order's payable figures are the ones the pay route enforces")
    void payableFigures() {
        String supplier = partner("Payable");
        String product = stockProduct();
        String poId = createPo(supplier, product, 10, 10).get("id").asString();
        String path = "/procurement/purchase-orders/" + poId + "/payable";
        JsonNode none = ok(200, "GET", path, null);
        assertThat(none.get("canPay").asBoolean()).isFalse();
        assertThat(none.get("message").asString()).startsWith("Nothing has been received on this purchase order yet");

        receive(poId, product, 4);
        JsonNode some = ok(200, "GET", path, null);
        assertThat(num(some.get("received"))).isEqualByComparingTo("40");
        assertThat(num(some.get("remaining"))).isEqualByComparingTo("40");
        assertThat(num(some.get("supplierOwed"))).isEqualByComparingTo("40");
        assertThat(num(some.get("payableNow"))).isEqualByComparingTo("40");
        assertThat(some.get("canPay").asBoolean()).isTrue();
        assertThat(some.get("message").isNull()).isTrue();

        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank",
                "supplierBankAccountId", poSupplierBankAccount(poId)));
        assertThat(num(pay.get("amount"))).isEqualByComparingTo("40");
        JsonNode after = ok(200, "GET", path, null);
        assertThat(num(after.get("paid"))).isEqualByComparingTo("40");
        assertThat(num(after.get("payableNow"))).isEqualByComparingTo("0");
        assertThat(after.get("canPay").asBoolean()).isFalse();
        assertThat(call("GET", path, null, weakToken()).status()).isEqualTo(403);
    }
}
