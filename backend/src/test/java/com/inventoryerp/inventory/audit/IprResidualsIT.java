package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountingSeedContributor;
import com.inventoryerp.core.application.CoreSeedContributor;
import com.inventoryerp.inventory.application.InventorySeedContributor;
import com.inventoryerp.kernel.seed.SeedContributor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * IPR residuals of Phases 1a and 2 in the inventory / invoicing / seed area (stream H), each in its own organisation:
 * F11 (the bill auto-receipt's unit cost went through a {@code double}) and D-1 (an organisation seeded through
 * {@code SeedRunner.base()} got its opening stock ledger row without the journal).
 */
class IprResidualsIT extends InventoryAuditSupport {

    void use(AuditOrg o) {
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    String supplier() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\") VALUES (?, ?,"
                + " ?, 'Supplier', true, now())").params(id, org, "S-" + UUID.randomUUID().toString().substring(0, 8))
                .update();
        return id;
    }

    // ------------------------------------------------------------------ F11

    @Test
    @DisplayName("F11: a bill auto-receipt is valued at the bill line's subtotal to the ledger's 6 dp (no double in the"
            + " unit cost), and the GL ties out")
    void billReceiptCostIsDecimal() {
        use(newOrg());
        orgSetting("procurement.billWithoutReceipt", "\"auto_receive\"");
        String p = product("F11");
        String partner = supplier();
        // 3 × 33,333,333,333.333333 less 10 % = 89,999,999,999.999999 (6 dp): its unit cost 29,999,999,999.999999667
        // is not a double; the nearest double is 30,000,000,000, so the old receipt came to 90,000,000,000.000000.
        String bill = okA(200, "POST", "/vendor-bills", Map.of("partnerId", partner, "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("productId", p, "description", "Turbine",
                        "quantity", 3, "unitPrice", new BigDecimal("33333333333.333333"), "discountPercent", 10))))
                .get("id").asString();
        BigDecimal subtotal = asOrg(() -> jdbc.sql("SELECT subtotal FROM \"DocumentLine\" WHERE \"documentId\" = ?")
                .param(bill).query(BigDecimal.class).single());
        assertThat(subtotal.multiply(BigDecimal.valueOf(1_000_000)).remainder(BigDecimal.valueOf(3)).signum())
                .as("precondition: the subtotal " + subtotal + " does not divide by 3 at 6 dp").isNotZero();
        Res post = call("POST", "/vendor-bills/" + bill + "/post", Map.of("override3WM", true), auditToken);
        assertThat(post.status()).as(post.raw().body()).isIn(200, 201);
        assertThat(onHand(p, main)).isEqualByComparingTo("3");
        BigDecimal received = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(\"totalValue\"), 0) FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"quantityChange\" > 0").params(org, p)
                .query(BigDecimal.class).single());
        assertThat(received).as("receipt value = the bill line's subtotal").isEqualByComparingTo(subtotal.setScale(6,
                RoundingMode.HALF_UP));
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ D-1

    @Test
    @DisplayName("D-1: the base seed (contributors up to order 300) journals the opening stock row it writes, so a"
            + " base-only organisation ties out; a second run writes neither again")
    void baseSeedJournalsOpeningStock() {
        AuditOrg o = newOrg(); // Core (100) and Accounting (200), as SeedRunner.base() runs them
        use(o);
        Runnable base = () -> new TransactionTemplate(txm).executeWithoutResult(st -> seedContributors.stream()
                .filter(c -> c instanceof InventorySeedContributor)
                .sorted(Comparator.comparingInt(SeedContributor::order))
                .forEach(c -> c.seed(o.id())));
        assertThat(seedContributors.stream().filter(c -> c instanceof CoreSeedContributor
                || c instanceof AccountingSeedContributor || c instanceof InventorySeedContributor)
                .allMatch(c -> c.order() <= 300)).as("the three are base contributors").isTrue();
        base.run();
        String widget = asOrg(() -> jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code ="
                + " 'PRD-001'").param(org).query(String.class).single());
        assertThat(onHand(widget, main)).isEqualByComparingTo("100");
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'opening_balance' AND status = 'posted'", org))).as("the opening stock journal")
                .isEqualTo(1);
        assertThat(asOrg(() -> balance("stock_valuation"))).isEqualByComparingTo("600");
        assertIntegrityGreen();
        assertGlTieOut();
        base.run();
        assertThat(asOrg(() -> ledgerRows(widget))).isEqualTo(1);
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'opening_balance'", org))).isEqualTo(1);
        JsonNode t = okA(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.01", null);
        assertThat(t.get("withinTolerance").asBoolean()).as(t.toString()).isTrue();
    }
}
