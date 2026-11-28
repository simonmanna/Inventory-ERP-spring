package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IPR Phase 1a, lock order between goods receipts and vendor bills (lock audit LK-1, matrix M-29). A bill post takes
 * the supplier lock, then the receipt lines, then the purchase-order lines; a receipt used to update the purchase-order
 * lines first and take the supplier lock last — a deadlock cycle. The interleaving is forced, not hoped for: a schema
 * owner connection holds the first receipt's lines so the bill post stops right after its supplier lock, the receipt
 * then runs as far as it can, the hold is released, and both must finish without a 40P01. M-29: a bill cancel waits
 * for a receipt that is absorbing the bill's auto-received stock and is then refused, instead of returning the same
 * stock a second time.
 */
class ReceiptBillLockOrderIT extends ApiTestSupport {

    @Autowired
    SettingResolverService settingResolver;

    @Autowired
    ObjectProvider<FlywayConnectionDetails> ownerDetails;

    String warehouse;
    final String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "LKO-" + suffix(), "name", "Lock order "
                + suffix(), "type", "warehouse")).get("id").asString();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    Map<String, String> keyedHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String partner() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "LKO-" + suffix(), "Lock " + suffix())
                .update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO', 0, 0, now())")
                .params(id, demoOrg, "LKO-" + suffix(), "Item " + suffix()).update();
        return id;
    }

    <T> T read(Callable<T> body) {
        return TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> new TransactionTemplate(txm).execute(s -> {
                    try {
                        return body.call();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    BigDecimal num(String sql, Object... params) {
        return read(() -> jdbc.sql(sql).params(params).query(BigDecimal.class).single());
    }

    BigDecimal onHand(String productId) {
        return num("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?", demoOrg, productId);
    }

    String po(String supplier, String productId, int qty, int price) {
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitPrice", price)))).get("id").asString();
    }

    Res receiveRes(String poId, String productId, int qty) {
        return call("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))),
                keyedHeaders());
    }

    String receive(String poId, String productId, int qty) {
        Res r = receiveRes(poId, productId, qty);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("grn").get("id").asString();
    }

    String draftReceipt(String poId, String productId, int qty) {
        String poLine = read(() -> jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"organizationId\" = ? AND"
                + " \"purchaseOrderId\" = ?").params(demoOrg, poId).query(String.class).single());
        return ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "warehouseId", warehouse,
                "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", productId, "description",
                        "Cement", "quantity", qty)))).get("id").asString();
    }

    String bill(String supplier, String productId, int qty, int price) {
        return ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price))))
                .get("id").asString();
    }

    Res postBill(String billId) {
        return call("POST", "/vendor-bills/" + billId + "/post", Map.of("override3WM", true), adminToken);
    }

    Connection ownerConnection() throws Exception {
        // The schema owner; on an external test database (IT_EXTERNAL_DB) Flyway reuses the application URL.
        FlywayConnectionDetails o = ownerDetails.getIfAvailable();
        String url = o != null && o.getJdbcUrl() != null ? o.getJdbcUrl() : System.getenv("JDBC_DATABASE_URL");
        String user = o != null && o.getUsername() != null ? o.getUsername() : System.getenv("FLYWAY_USER");
        String password = o != null && o.getPassword() != null ? o.getPassword() : System.getenv("FLYWAY_PASSWORD");
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(false);
        return c;
    }

    /** Hold row locks on {@code table} rows where {@code column} = {@code value} (schema owner: no RLS). */
    void hold(Connection c, String table, String column, String value) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM \"" + table + "\" WHERE \"" + column
                + "\" = ? FOR UPDATE")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("rows to hold in " + table).isTrue();
            }
        }
    }

    /** Wait until at least {@code n} sessions of this database wait on a lock (row or advisory). */
    void awaitWaiters(Connection c, int n) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname ="
                    + " current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) >= n) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("fewer than " + n + " sessions waiting on a lock");
    }

    static boolean deadlocked(Res r) {
        return r.raw().body() != null && r.raw().body().toLowerCase().contains("deadlock");
    }

    /**
     * The forced interleaving: the bill post stops after its supplier lock (the first receipt's lines are held), the
     * receipt runs until it blocks, the hold is released. Returns {bill post, receipt}.
     */
    Res[] interleave(String grnToHold, String billId, Callable<Res> receipt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection c = ownerConnection()) {
            hold(c, "GoodsReceiptLine", "goodsReceiptId", grnToHold);
            Future<Res> b = pool.submit(() -> postBill(billId));
            awaitWaiters(c, 1);
            Future<Res> a = pool.submit(receipt);
            awaitWaiters(c, 2);
            c.rollback();
            return new Res[] {b.get(60, TimeUnit.SECONDS), a.get(60, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("LK-1: a purchase-order receipt and a bill post on the same order, forced to interleave: no deadlock")
    void poReceiveVersusBillPost() throws Exception {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 10, 50);
        String grn1 = receive(poId, item, 5);
        String billId = bill(supplier, item, 5, 50);
        Res[] out = interleave(grn1, billId, () -> receiveRes(poId, item, 5));
        assertThat(deadlocked(out[0]) || deadlocked(out[1])).as("40P01: bill " + out[0].raw().body() + " / receipt "
                + out[1].raw().body()).isFalse();
        assertThat(out[0].status()).as(out[0].raw().body()).isEqualTo(200);
        assertThat(out[1].status()).as(out[1].raw().body()).isEqualTo(200);
        assertThat(onHand(item)).isEqualByComparingTo("10");
        assertThat(num("SELECT \"billedQuantity\" FROM \"PurchaseOrderLine\" WHERE \"organizationId\" = ? AND"
                + " \"purchaseOrderId\" = ?", demoOrg, poId)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("LK-1: a draft goods receipt posted while a bill posts on the same order, forced to interleave: no"
            + " deadlock")
    void goodsReceiptPostVersusBillPost() throws Exception {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 10, 50);
        String grn1 = receive(poId, item, 5);
        String grn2 = draftReceipt(poId, item, 5);
        String billId = bill(supplier, item, 5, 50);
        Res[] out = interleave(grn1, billId, () -> call("PATCH", "/procurement/goods-receipts/" + grn2 + "/post", null,
                keyedHeaders()));
        assertThat(deadlocked(out[0]) || deadlocked(out[1])).as("40P01: bill " + out[0].raw().body() + " / receipt "
                + out[1].raw().body()).isFalse();
        assertThat(out[0].status()).as(out[0].raw().body()).isEqualTo(200);
        assertThat(out[1].status()).as(out[1].raw().body()).isEqualTo(200);
        assertThat(onHand(item)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("M-29: a bill cancel while a receipt absorbs the bill's auto-received stock waits for it and is"
            + " refused; the stock is counted once")
    void billCancelVersusAbsorb() throws Exception {
        String supplier = partner();
        String item = product();
        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'procurement.billWithoutReceipt', '\"auto_receive\"'::jsonb, 'organization', now())")
                .params(setting, demoOrg).update();
        settingResolver.invalidate(demoOrg);
        try {
            String billId = bill(supplier, item, 4, 50);
            assertThat(postBill(billId).status()).isEqualTo(200);
            assertThat(onHand(item)).isEqualByComparingTo("4");
            String poId = po(supplier, item, 6, 50);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Res receipt;
            Res cancel;
            try (Connection c = ownerConnection()) {
                // The receipt stops at the bill's auto-receipt rows (it holds the supplier lock by then).
                hold(c, "VendorBillAutoReceipt", "vendorBillId", billId);
                Future<Res> a = pool.submit(() -> receiveRes(poId, item, 6));
                awaitWaiters(c, 1);
                Future<Res> k = pool.submit(() -> call("POST", "/vendor-bills/" + billId + "/cancel",
                        Map.of("reason", "Raised in error"), adminToken));
                Thread.sleep(1500);
                c.rollback();
                receipt = a.get(60, TimeUnit.SECONDS);
                cancel = k.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(receipt.status()).as(receipt.raw().body()).isEqualTo(200);
            String grn = receipt.body().get("grn").get("id").asString();
            BigDecimal absorbed = num("SELECT COALESCE(SUM(\"billedQuantity\"), 0) FROM \"GoodsReceiptLine\" WHERE"
                    + " \"organizationId\" = ? AND \"goodsReceiptId\" = ?", demoOrg, grn);
            assertThat(cancel.status()).as("cancel " + cancel.raw().body() + ", absorbed " + absorbed).isEqualTo(400);
            assertThat(absorbed).isEqualByComparingTo("4");
            assertThat(onHand(item)).as("4 by the bill, 2 more by the receipt").isEqualByComparingTo("6");
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            settingResolver.invalidate(demoOrg);
        }
    }
}
