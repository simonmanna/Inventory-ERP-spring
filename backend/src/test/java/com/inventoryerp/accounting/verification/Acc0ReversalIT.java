package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ACC-0 reproduction: reversal dates and invoice cancellation with stock (findings B6, B7). */
class Acc0ReversalIT extends AccountingFixtures {

    String product(String org, boolean stock, int price, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())")
                .params(id, org, "A0-" + id.substring(0, 6), "ACC0 item", stock ? "stockable" : "service", price, cost,
                        stock).update();
        return id;
    }

    String customer(String org) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, true, now())").params(id, org, "A0-" + id.substring(0, 8), "ACC0 customer " + id)
                .update();
        return id;
    }

    Map<String, Object> entry(String id) {
        return jdbc.sql("SELECT id, status::text AS status, \"postingDate\", \"reversalOfId\", \"reversedEntryId\" FROM"
                + " \"JournalEntry\" WHERE id = ?").param(id).query().singleRow();
    }

    String reversalOf(String entryId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"reversalOfId\" = ?").param(entryId).query(String.class)
                .single();
    }

    static LocalDate day(Object ts) {
        Instant at = ts instanceof java.sql.Timestamp t ? t.toInstant() : ((java.time.LocalDateTime) ts)
                .toInstant(ZoneOffset.UTC);
        return at.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @Test
    @DisplayName("[B6][owner:accounting][ACC-3] cancelling an invoice whose period is open reverses it on its own date")
    void reversalInOpenPeriodKeepsOriginalDate() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        String issue = months[0].plusDays(9).toString();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer(b.org()), "issueDate", issue,
                "lines", List.of(Map.of("productId", product(b.org(), false, 100, 0), "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        String je = okAs(b, 200, "POST", "/invoices/" + inv + "/post", null).get("journalEntryId").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/cancel", null);
        Map<String, Object> original = entry(je);
        Map<String, Object> reversal = entry(reversalOf(je));
        assertThat(original.get("status")).isEqualTo("reversed");
        assertThat(original.get("reversedEntryId")).as("permanent original → reversal link").isEqualTo(reversal.get("id"));
        LedgerInvariants.postingComplete(jdbc, b.org(), "invoice", inv, 1, true);
        assertThat(day(reversal.get("postingDate"))).as("reversal date (original " + day(original.get("postingDate"))
                + ", period still open)").isEqualTo(day(original.get("postingDate")));
    }

    @Test
    @DisplayName("[B6][owner:accounting][ACC-3] cancelling an invoice of a closed period reverses into an open period")
    void reversalOfClosedPeriodLandsInOpenPeriod() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        String issue = months[0].plusDays(9).toString();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer(b.org()), "issueDate", issue,
                "lines", List.of(Map.of("productId", product(b.org(), false, 100, 0), "quantity", 1, "unitPrice", 100))))
                .get("id").asString();
        String je = okAs(b, 200, "POST", "/invoices/" + inv + "/post", null).get("journalEntryId").asString();
        closePeriod(b, issue);
        Res cancel = as(b, "POST", "/invoices/" + inv + "/cancel", null);
        assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(200);
        Map<String, Object> reversal = entry(reversalOf(je));
        assertThat(day(reversal.get("postingDate"))).as("reversal lands in the open current month")
                .isAfterOrEqualTo(months[1]);
        assertThat(jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodFor(b.org(), issue))
                .query(String.class).single()).isEqualTo("closed");
    }

    @Test
    @DisplayName("[B7][owner:accounting][ACC-3] cancelling a stock invoice restores stock and reverses COGS")
    void cancelRestoresStock() {
        String supplier = demoPartner("ACC0 stock supplier", true);
        String customer = demoPartner("ACC0 stock customer", false);
        String item = demoProduct("ACC0 cement", true, 10, 4);
        ok(200, "POST", "/vendor-bills/" + ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate",
                today(), "lines", List.of(Map.of("productId", item, "quantity", 10, "unitPrice", 4)))).get("id")
                .asString() + "/post", Map.of());
        assertThat(onHand(item)).as("received").isEqualByComparingTo("10");
        BigDecimal valueBefore = stockValue(item);

        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", item, "quantity", 3, "unitPrice", 10)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        assertThat(onHand(item)).as("after sale").isEqualByComparingTo("7");
        String cogs = mapped(demoOrg, "cogs");
        BigDecimal cogsBooked = cogsForProduct(cogs, item, inv);
        assertThat(cogsBooked).as("COGS booked on sale").isEqualByComparingTo("12");

        ok(200, "POST", "/invoices/" + inv + "/cancel", null);
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        soft.assertThat(onHand(item)).as("stock after cancel").isEqualByComparingTo("10");
        soft.assertThat(stockValue(item)).as("stock value after cancel").isEqualByComparingTo(valueBefore);
        soft.assertThat(cogsForProduct(cogs, item, inv)).as("COGS after cancel").isEqualByComparingTo("0");
        // D-B7b: a repeated cancel keeps Nest's idempotent echo; what matters is that it changes nothing.
        Res again = as("POST", "/invoices/" + inv + "/cancel", null);
        soft.assertThat(again.status()).as("second cancel → " + again.raw().body()).isLessThan(500);
        soft.assertThat(onHand(item)).as("stock after a repeated cancel").isEqualByComparingTo("10");
        soft.assertThat(cogsForProduct(cogs, item, inv)).as("COGS after a repeated cancel").isEqualByComparingTo("0");
        soft.assertAll();
    }

    @Test
    @DisplayName("[B7][owner:accounting][ACC-3] cancel after selling more than on hand restores the issued quantity")
    void cancelAfterInsufficientStock() {
        String supplier = demoPartner("ACC0 neg supplier", true);
        String customer = demoPartner("ACC0 neg customer", false);
        String item = demoProduct("ACC0 aggregate", true, 10, 4);
        ok(200, "POST", "/vendor-bills/" + ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate",
                today(), "lines", List.of(Map.of("productId", item, "quantity", 3, "unitPrice", 4)))).get("id")
                .asString() + "/post", Map.of());
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", item, "quantity", 5, "unitPrice", 10)))).get("id").asString();
        Res posted = as("POST", "/invoices/" + inv + "/post", null);
        assertThat(posted.status()).as("over-sale post → " + posted.raw().body()).isEqualTo(200);
        BigDecimal afterSale = onHand(item);
        ok(200, "POST", "/invoices/" + inv + "/cancel", null);
        assertThat(onHand(item)).as("stock after cancel (sold 5 of 3, on hand after sale " + afterSale + ")")
                .isEqualByComparingTo("3");
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"productId\" = ?")
                .param(productId).query(BigDecimal.class).single();
    }

    BigDecimal stockValue(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity * \"runningAverageCost\"), 0) FROM \"StockItem\" WHERE \"productId\" = ?")
                .param(productId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    /** Net COGS (base) on the invoice's entries and the product's stock-issue entries, plus their reversals. */
    BigDecimal cogsForProduct(String cogsAccount, String productId, String invoiceId) {
        String scope = "(x.\"sourceId\" = ? OR x.\"postingKey\" LIKE ?)";
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND (e.id IN (SELECT"
                        + " x.id FROM \"JournalEntry\" x WHERE " + scope + ") OR e.\"reversalOfId\" IN (SELECT x.id FROM"
                        + " \"JournalEntry\" x WHERE " + scope + "))")
                .params(cogsAccount, invoiceId, "%" + productId + "%", invoiceId, "%" + productId + "%")
                .query(BigDecimal.class).single();
    }
}
