package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.TestBillSettings;
import com.inventoryerp.TestSuppliers;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.invoicing.application.VendorBillService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * IPR T19 (R2 consolidated): races on the paths wave 3 changed, run in-process with a {@code lock_timeout} so a lock
 * wait that never ends fails the test instead of hanging it, and a deadlock (40P01) fails it through {@link #race}.
 * The races that earlier waves already proved are listed in {@code docs/audits/ipr-evidence/w3-engine-closeout.md}
 * (not repeated here).
 */
class InventoryConcurrencyIT extends InventoryAuditSupport {

    @Autowired
    VendorBillService bills;

    /** {@code body} in the audit org's tenant transaction with a 20 s lock timeout (L1–L7 waits are bounded). */
    private <T> T bounded(Supplier<T> body) {
        return asOrg(() -> {
            jdbc.sql("SET LOCAL lock_timeout = '20s'").update();
            return body.get();
        });
    }

    private String autoReceivedBill(String productId, int qty) {
        String supplier = TestSuppliers.supplier(jdbc, org);
        String[] id = new String[1];
        TestBillSettings.autoReceive(jdbc, org, settingResolver, () -> {
            id[0] = okA(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate",
                    LocalDate.now().toString(), "lines", List.of(Map.of("productId", productId, "quantity", qty,
                            "unitPrice", 3)))).get("id").asString();
            okA(200, "POST", "/vendor-bills/" + id[0] + "/post", Map.of());
        });
        return id[0];
    }

    private String stockLocation(String productId) {
        return jdbc.sql("SELECT \"locationId\" FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND quantity > 0 LIMIT 1").params(org, productId).query(String.class).single();
    }

    @Test
    @DisplayName("R2: a bill cancel (issue-back of its receipt) racing a sale of the same units — one wins, never"
            + " negative, books tie")
    void billCancelRacesSale() throws Exception {
        String p = product("T19-BILL");
        String bill = autoReceivedBill(p, 6);
        String loc = stockLocation(p);
        List<Callable<Object>> tasks = List.of(
                () -> bounded(() -> bills.cancel(bill, "Raised in error")),
                () -> bounded(() -> stock.issue(StockMove.of(p, loc, BigDecimal.valueOf(4))
                        .source("audit_sale", "sale-" + UUID.randomUUID()).requireAvailable(true))));
        List<Raced<Object>> out = race(Duration.ofSeconds(90), tasks);
        long won = out.stream().filter(Raced::ok).count();
        BigDecimal left = onHand(p, loc);
        if (won == 2) {
            throw new AssertionError("both won: " + left);
        }
        assertThat(won).as(out.toString()).isEqualTo(1);
        assertThat(left).as("cancel won → 0, sale won → 2").isIn(BigDecimal.ZERO.setScale(left.scale()),
                BigDecimal.valueOf(2).setScale(left.scale()));
        assertThat(left.signum()).isNotNegative();
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("R2: two cancels of the same bill race — the receipt is issued back once")
    void doubleBillCancel() throws Exception {
        String p = product("T19-BILL2");
        String bill = autoReceivedBill(p, 5);
        String loc = stockLocation(p);
        List<Callable<Object>> tasks = List.of(
                () -> bounded(() -> bills.cancel(bill, "Raised in error")),
                () -> bounded(() -> bills.cancel(bill, "Raised in error")));
        race(Duration.ofSeconds(90), tasks);
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"referenceType\" = 'vendor_bill_cancel'", org, p)).as("issued back once").isEqualTo(1);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("R2: a sales-order reservation racing a non-holder issue of the same cell — the issue never eats the"
            + " reservation (one guard, engine lock order)")
    void reservationRacesIssue() throws Exception {
        String p = product("T19-RES");
        receive(p, main, 5, 2, null);
        String line = "so-line-" + UUID.randomUUID();
        List<Callable<Object>> tasks = List.of(
                () -> bounded(() -> reservations.reserve(new com.inventoryerp.inventory.application
                        .StockReservationService.Reserve(p, null, main, BigDecimal.valueOf(4), "sales_order_line", line,
                        "T19"))),
                () -> bounded(() -> stock.issue(StockMove.of(p, main, BigDecimal.valueOf(3))
                        .source("audit_sale", "sale-" + UUID.randomUUID()).honourReservations(null))));
        List<Raced<Object>> out = race(Duration.ofSeconds(90), tasks);
        assertThat(out.get(0).ok()).as("the reservation is recorded: " + out.get(0).message()).isTrue();
        if (out.get(1).ok()) {
            assertThat(onHand(p, main)).as("issue first, reservation after (soft)").isEqualByComparingTo("2");
        } else {
            assertThat(out.get(1).message()).contains("reserved for sales orders");
            assertThat(onHand(p, main)).isEqualByComparingTo("5");
        }
        asOrgRun(() -> reservations.release("sales_order_line", line));
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Autowired
    com.inventoryerp.inventory.application.StockReservationService reservations;
}
