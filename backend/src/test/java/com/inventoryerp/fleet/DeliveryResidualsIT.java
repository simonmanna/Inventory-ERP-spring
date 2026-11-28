package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.sales.application.SalesBillingService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * IPR residuals of Phase 1a (stream H), each scenario in its own organisation and ending with the integrity report
 * (report-only checks included, {@code feature_risk} aside) green and the GL tied out:
 * <ul>
 *   <li>a delivery order from an invoice whose stock issue belongs to its sales order is refused (it used to be created
 *       in stock mode invoiced and deliver goods that never left stock, or, from a draft, issue them twice);</li>
 *   <li>F5: stock reserved for sales orders is refused to the other issuers the guard covers — the invoice auto-issue,
 *       {@code issue-undelivered}, a fleet dispatch of a delivery order that is not a sales order's, and a cash-sale
 *       confirm;</li>
 *   <li>LK-3: a sales order and its lines are locked in that order by the invoice subscriber
 *       ({@code recomputeInvoiced}) and by a sales route, proven by a forced interleaving.</li>
 * </ul>
 */
class DeliveryResidualsIT extends InventoryAuditSupport {

    @Autowired
    SalesBillingService billing;

    @Autowired
    AccountResolverService accounts;

    void fresh() {
        AuditOrg o = newOrg();
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, org, "P-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, supplier, !supplier).update();
        return id;
    }

    Res moneyRes(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", path, body, h);
    }

    JsonNode money(String path, Object body) {
        Res r = moneyRes(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String msg(Res r) {
        return r.body() == null || r.body().get("message") == null ? r.raw().body() : r.body().get("message").asString();
    }

    String today() {
        return LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString();
    }

    String salesOrder(String customer, String product, String location, int qty) {
        return salesOrder(customer, product, location, qty, 200);
    }

    /** A confirmed sales order (priced at or above the list price, so it needs no discount approval). */
    String salesOrder(String customer, String product, String location, int qty, int unitPrice) {
        String so = okA(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", location, "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", unitPrice)))).get("id").asString();
        okA(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    BigDecimal reserved(String product, String location) {
        return asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND status = 'active' AND"
                + " \"sourceType\" = 'sales_order_line'").params(org, product, location).query(BigDecimal.class).single());
    }

    String draftInvoice(String customer, String product, int qty) {
        return okA(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 200)))).get("id").asString();
    }

    String trip(String deliveryOrder) {
        String trip = okA(201, "POST", "/fleet/delivery-orders/" + deliveryOrder + "/plan-loads", Map.of("payloadT", 20))
                .get("createdTripIds").get(0).asString();
        String transporter = partner("Haulier", true);
        String rate = okA(201, "POST", "/fleet/transporter-rates", Map.of("partnerId", transporter, "name",
                "Run " + UUID.randomUUID().toString().substring(0, 6), "basis", "per_trip", "rate", 1000)).get("id")
                .asString();
        okA(201, "POST", "/fleet/trips/" + trip + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "transporterRateId", rate, "transporterPlate", "UAX 123B", "transporterDriverName", "Okello"));
        return trip;
    }

    void assertClean() {
        assertIntegrityGreen(true, "feature_risk");
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ fromInvoice of a sales-order invoice

    @Test
    @DisplayName("residual F1: a delivery order from an invoice whose goods leave through its sales order is refused"
            + " (posted and draft); the sales order's own delivery issues them, once")
    void salesOrderInvoiceNotDeliveredFromInvoice() {
        fresh();
        okA(200, "PUT", "/sales/settings", Map.of("reservationTrigger", "none"));
        String customer = partner("Customer", false);
        String p = product("SOI", Map.of("invoicePolicy", "ordered"));
        receive(p, 50, 100);

        // Posted invoice of an ordered-policy sales order: nothing has left stock yet.
        String so = salesOrder(customer, p, main, 5);
        String inv = money("/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        okA(200, "POST", "/invoices/" + inv + "/post", null);
        drainOutboxFully();
        assertThat(onHand(p, main)).as("an order invoice never issues stock").isEqualByComparingTo("50");
        Res posted = api("POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", inv, "locationId", main));
        assertThat(posted.status()).as("a posted sales-order invoice: " + posted.raw().body()).isEqualTo(409);
        assertThat(msg(posted)).contains("whose deliveries issue its goods");

        // Draft invoice of a second order, its goods already delivered by the order: a dispatch-mode order from the
        // invoice would issue them a second time.
        String so2 = salesOrder(customer, p, main, 4);
        JsonNode dl = money("/sales/orders/" + so2 + "/deliveries", Map.of("vehiclePlate", "UAX 7"));
        String delivery = dl.get("id").asString();
        money("/sales/deliveries/" + delivery + "/issue", null);
        money("/sales/deliveries/" + delivery + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", dl.get("lines").get(0).get("id").asString(), "deliveredQty", 4))));
        drainOutboxFully();
        assertThat(onHand(p, main)).isEqualByComparingTo("46");
        String draft = money("/sales/orders/" + so2 + "/invoices", Map.of()).get("id").asString();
        Res fromDraft = api("POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", draft, "locationId",
                main));
        assertThat(fromDraft.status()).as("a draft sales-order invoice: " + fromDraft.raw().body()).isEqualTo(409);
        okA(200, "POST", "/invoices/" + draft + "/post", null);
        drainOutboxFully();
        assertThat(onHand(p, main)).as("the delivered goods left stock once").isEqualByComparingTo("46");

        // The first order's goods leave through its own delivery, which issues them.
        JsonNode dl1 = money("/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 8"));
        money("/sales/deliveries/" + dl1.get("id").asString() + "/issue", null);
        drainOutboxFully();
        assertThat(onHand(p, main)).as("the sales order's delivery issues the invoiced goods").isEqualByComparingTo(
                "41");
        assertClean();
    }

    // ------------------------------------------------------------------ F5

    @Test
    @DisplayName("F5: stock reserved for a sales order is refused to the invoice auto-issue, issue-undelivered and a"
            + " fleet dispatch of an invoice's delivery order; the free part still issues")
    void reservationRefusalOnInvoiceAndFleetPaths() {
        fresh();
        String customer = partner("Customer", false);
        String p = product("RSV");
        receive(p, 10, 100);
        String so = salesOrder(customer, p, main, 8);
        assertThat(reserved(p, main)).as("confirm reserves the order").isEqualByComparingTo("8");

        // 1) invoice auto-issue
        String inv = draftInvoice(customer, p, 5);
        Res post = api("POST", "/invoices/" + inv + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
        assertThat(msg(post)).contains("Only 2 of").contains("is reserved for sales orders");
        assertThat(onHand(p, main)).isEqualByComparingTo("10");

        // 2) fleet dispatch of a delivery order made from a draft invoice (not a sales order's own delivery)
        String inv2 = draftInvoice(customer, p, 5);
        JsonNode dO = okA(201, "POST", "/fleet/delivery-orders/from-invoice", Map.of("sourceId", inv2, "locationId",
                main));
        assertThat(dO.get("stockMode").asString()).isEqualTo("dispatch");
        String t = trip(dO.get("id").asString());
        Res dispatch = moneyRes("/fleet/trips/" + t + "/dispatch", null);
        assertThat(dispatch.status()).as(dispatch.raw().body()).isEqualTo(400);
        assertThat(msg(dispatch)).contains("is reserved for sales orders");
        assertThat(onHand(p, main)).isEqualByComparingTo("10");

        // 3) issue-undelivered of the same invoice once its delivery order is cancelled (awaiting delivery)
        okA(200, "POST", "/invoices/" + inv2 + "/post", null);
        okA(201, "POST", "/fleet/delivery-orders/" + dO.get("id").asString() + "/cancel", Map.of("reason", "No truck"));
        Res collect = moneyRes("/invoices/" + inv2 + "/issue-undelivered", Map.of("locationId", main));
        assertThat(collect.status()).as(collect.raw().body()).isEqualTo(400);
        assertThat(msg(collect)).contains("is reserved for sales orders");
        assertThat(onHand(p, main)).isEqualByComparingTo("10");

        // The free part (2) still issues; releasing the order's reservation frees the rest.
        String small = draftInvoice(customer, p, 2);
        okA(200, "POST", "/invoices/" + small + "/post", null);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        okA(200, "POST", "/sales/orders/" + so + "/cancel", Map.of("reason", "Customer withdrew"));
        assertThat(reserved(p, main)).isEqualByComparingTo("0");
        Res collected = moneyRes("/invoices/" + inv2 + "/issue-undelivered", Map.of("locationId", main));
        assertThat(collected.status()).as(collected.raw().body()).isEqualTo(200);
        assertThat(onHand(p, main)).isEqualByComparingTo("3");
        assertClean();
    }

    @Test
    @DisplayName("F5: a cash-sale confirm leaves stock reserved for a sales order alone (refused, nothing posted)")
    void reservationRefusalOnCashSaleConfirm() {
        fresh();
        String sfx = UUID.randomUUID().toString().substring(0, 4).toUpperCase().replaceAll("[^A-Z0-9]", "X");
        String cash = TenantContext.run(TenantStore.user(org, auditUserId), () -> new TransactionTemplate(txm)
                .execute(s -> accounts.ensureByCode("10" + (1000 + (int) (Math.random() * 8999)), "Showroom cash "
                        + sfx, "cash", null)));
        String branch = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch, org, "B-" + sfx, "Showroom " + sfx).update();
        String store = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"branchId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'store', ?, now())").params(store, org, "SR-" + sfx, "Showroom floor " + sfx,
                        branch).update();
        String walkIn = partner("Walk-in", false);
        okA(201, "POST", "/sales/branch-profiles", Map.of("branchId", branch, "locationId", store, "cashAccountId", cash,
                "walkInPartnerId", walkIn, "numberPrefix", "CS" + sfx.substring(0, 2)));
        String customer = partner("Customer", false);
        String p = product("CSR", Map.of("salesPrice", 300));
        receive(p, store, 10, 100, null);
        assertThat(onHand(p, store)).isEqualByComparingTo("10");
        String so = salesOrder(customer, p, store, 8, 300);
        assertThat(reserved(p, store)).as(() -> "order " + okA(200, "GET", "/sales/orders/" + so, null) + "; reservations "
                + asOrg(() -> jdbc.sql("SELECT \"locationId\", quantity, status, \"sourceType\" FROM \"StockReservation\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ?").params(org, p).query().listOfRows()))
                .isEqualByComparingTo("8");

        String sale = okA(201, "POST", "/sales/cash-sales", Map.of("branchId", branch, "customerName", "Walk-in buyer",
                "lines", List.of(Map.of("productId", p, "quantity", 5, "unitPrice", 300)))).get("id").asString();
        double total = okA(200, "GET", "/sales/cash-sales/" + sale, null).get("grandTotal").asDouble();
        JsonNode paid = okA(201, "POST", "/sales/cash-sales/" + sale + "/payments", Map.of("method", "cash", "amount",
                total));
        String payment = paid.get("payments").get(0).get("id").asString();
        okA(200, "POST", "/sales/cash-sales/" + sale + "/payments/" + payment + "/verify", Map.of("status",
                "verified"));
        long journals = asOrg(() -> journalEntries());
        Res confirm = moneyRes("/sales/cash-sales/" + sale + "/confirm", null);
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(400);
        assertThat(msg(confirm)).contains("Only 2 of").contains("is reserved for sales orders");
        assertThat(onHand(p, store)).isEqualByComparingTo("10");
        assertThat(asOrg(() -> journalEntries())).as("a refused confirm posts nothing").isEqualTo(journals);
        assertClean();
    }

    // ------------------------------------------------------------------ LK-3

    Connection ownerConnection() throws Exception {
        // The schema owner; on an external test database (IT_EXTERNAL_DB) Flyway reuses the application URL.
        var o = ownerDetails.getIfAvailable();
        String url = o != null && o.getJdbcUrl() != null ? o.getJdbcUrl() : System.getenv("JDBC_DATABASE_URL");
        String user = o != null && o.getUsername() != null ? o.getUsername() : System.getenv("FLYWAY_USER");
        String password = o != null && o.getPassword() != null ? o.getPassword() : System.getenv("FLYWAY_PASSWORD");
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(false);
        return c;
    }

    /** Wait until at least {@code n} other sessions of this database wait on a lock. */
    static void awaitWaiters(Connection c, int n) throws Exception {
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

    @Test
    @DisplayName("LK-3 (forced interleaving): the invoice subscriber's recomputeInvoiced and a close-short of a line"
            + " lock the sales order before its lines — both finish, no deadlock")
    void salesOrderBeforeLines() throws Exception {
        fresh();
        String customer = partner("Customer", false);
        String p = product("LK3");
        receive(p, 50, 100);
        String so = salesOrder(customer, p, main, 10);
        String line = okA(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        try (Connection hold = ownerConnection()) {
            // The schema owner holds the sales order row: the close-short queues on it first; the subscriber comes
            // second. Before LK-3 the subscriber took the lines (its UPDATE) and then waited for the order, so the
            // close-short, once it had the order, waited for a line the subscriber held: a deadlock cycle.
            try (PreparedStatement ps = hold.prepareStatement("SELECT id FROM \"SalesOrder\" WHERE id = ? FOR UPDATE")) {
                ps.setString(1, so);
                ps.executeQuery().close();
            }
            List<Callable<Object>> racers = List.of(
                    () -> api("POST", "/sales/orders/" + so + "/lines/" + line + "/close-short", Map.of("reason",
                            "Customer needs 10 less")),
                    () -> {
                        awaitWaiters(hold, 1);
                        return TenantContext.run(TenantStore.user(org, auditUserId), () -> {
                            billing.recomputeInvoiced(so);
                            return "recomputed";
                        });
                    },
                    () -> {
                        awaitWaiters(hold, 2);
                        hold.rollback();
                        return "released";
                    });
            List<Raced<Object>> out = race(Duration.ofSeconds(60), racers);
            for (Raced<Object> r : out) {
                assertThat(r.ok()).as(r.message()).isTrue();
            }
            Res closed = (Res) out.get(0).value();
            assertThat(closed.status()).as(closed.raw().body()).isIn(200, 201);
            assertThat(out.get(1).value()).isEqualTo("recomputed");
        }
        assertThat(okA(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("qtyCancelled").asDouble())
                .isEqualTo(10);
        assertClean();
    }
}
