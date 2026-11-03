package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ACC-0 full lifecycle in one isolated organization: purchase with VAT → goods receipt → vendor bill → partial supplier
 * payment → valuation → sale with VAT → invoice → partial customer receipt → bank statement + reconciliation →
 * month-end close. The scenario leaves residuals on purpose; every control account is asserted against its exact
 * expected balance (not zero), then the double-entry and sub-ledger invariants run.
 *
 * <pre>
 *   PO 10 × 50 + 18 % VAT; receive 8 (AP is raised by the PO receipt voucher: 400 + 72 VAT = 472)
 *   bill the 8 received units (PO-backed → vouchered: no second AP / VAT)
 *   pay supplier 300                                   → AP 172 Cr, bank −300
 *   sell 5 × 100 + 18 % VAT (AVCO cost 50)             → AR 590, revenue 500, VAT out 90, COGS 250, stock 150
 *   receive 400 from customer                          → AR 190, bank +400 (bank net 100)
 *   bank statement +400 / −300 → both matched; close the month → RE 250 Cr, P&L still 250
 * </pre>
 */
class Acc0LifecycleIT extends AccountingFixtures {

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[LIFE][owner:accounting][ACC-1] P2P → O2C → bank rec → month-end: exact residuals and invariants")
    void lifecycle() {
        Books b = freshBooks();
        String org = b.org();
        String today = today();
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String warehouse = okAs(b, 201, "POST", "/inventory/locations", Map.of("code", "LC-" + sfx, "name",
                "Lifecycle WH", "type", "warehouse")).get("id").asString();
        String vat = okAs(b, 201, "POST", "/taxes", Map.of("name", "VAT18 " + sfx, "rate", 18, "type", "vat",
                "vatCategory", "standard")).get("id").asString();
        String supplier = UUID.randomUUID().toString();
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'LC supplier', true, false, now()), (?, ?, ?, 'LC customer', false,"
                + " true, now())").params(supplier, org, "S-" + sfx, customer, org, "C-" + sfx).update();
        String item = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'LC cement', 'stockable', 100, 50,"
                + " true, now())").params(item, org, "LC-" + sfx).update();

        // 1. Purchase order with VAT, partial goods receipt.
        Map<String, Object> poLine = new LinkedHashMap<>();
        poLine.put("productId", item);
        poLine.put("description", "Cement");
        poLine.put("quantity", 10);
        poLine.put("unitPrice", 50);
        poLine.put("taxRate", 18);
        String po = okAs(b, 201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", List.of(poLine))).get("id").asString();
        Res grn = moneyAs(b, "POST", "/procurement/purchase-orders/" + po + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("productId", item, "description", "Cement", "quantity", 8,
                        "unitCost", 50))));
        assertThat(grn.status()).as("receive → " + grn.raw().body()).isLessThan(300);

        // 2. Vendor bill for the received units, linked to the PO.
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today,
                "purchaseOrderIds", List.of(po), "lines", List.of(Map.of("productId", item, "description", "Cement",
                        "quantity", 8, "unitPrice", 50, "taxId", vat)))).get("id").asString();
        Res billPost = as(b, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(billPost.status()).as("bill post → " + billPost.raw().body()).isEqualTo(200);
        SoftAssertions soft = new SoftAssertions();
        BigDecimal billResidual = jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(bill)
                .query(BigDecimal.class).single();
        soft.assertThat(billResidual).as("the vendor bill is the open AP item for the vouchered receipt")
                .isEqualByComparingTo("472");

        // 3. Partial supplier payment (allocated to the bill when it is open; else unallocated so the scenario goes on).
        Map<String, Object> pay = new LinkedHashMap<>(Map.of("partnerId", supplier, "paymentDate", today, "amount",
                300, "paymentMethod", "bank"));
        if (billResidual.signum() > 0) {
            pay.put("allocations", List.of(Map.of("documentId", bill, "amount", 300)));
        } else {
            pay.put("allowOverpayment", true);
        }
        Res sp = moneyAs(b, "POST", "/supplier-payments", pay);
        assertThat(sp.status()).as("supplier payment → " + sp.raw().body()).isEqualTo(200);

        // 4. Sale with VAT (stock issue at AVCO 50).
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", item, "quantity", 5, "unitPrice", 100, "taxId", vat)))).get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);

        // 5. Partial customer receipt.
        Res rc = moneyAs(b, "POST", "/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 400,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 400))));
        assertThat(rc.status()).as("receipt → " + rc.raw().body()).isEqualTo(200);

        // Exact control balances (base, debit-positive) before close.
        Map<String, BigDecimal> tb = tbAsOf(org, today, TbView.POST_CLOSING);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("stock_valuation", "150");
        expected.put("grni_accrued", "0");
        expected.put("accounts_payable", "-172");
        expected.put("tax_receivable", "72");
        expected.put("tax_payable", "-90");
        expected.put("accounts_receivable", "190");
        expected.put("cogs", "250");
        expected.put("sales_revenue", "-500");
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        for (String key : expected.keySet()) {
            actual.put(key, tb.getOrDefault(mappedCode(org, key), BigDecimal.ZERO));
        }
        String bankAccount = jdbc.sql("SELECT \"accountId\" FROM \"Payment\" WHERE \"organizationId\" = ? AND"
                + " direction::text = 'inbound' LIMIT 1").param(org).query(String.class).single();
        String bankCode = jdbc.sql("SELECT code FROM \"Account\" WHERE id = ?").param(bankAccount).query(String.class)
                .single();
        actual.put("bank", tb.getOrDefault(bankCode, BigDecimal.ZERO));
        expected.put("bank", "100");
        actual.forEach((k, v) -> soft.assertThat(v).as(k + " (all actual " + actual + ")")
                .isEqualByComparingTo(expected.get(k)));
        soft.check(() -> LedgerInvariants.trialBalanceBalanced(tb));
        soft.check(() -> LedgerInvariants.entriesBalanced(jdbc, org, null));
        soft.check(() -> LedgerInvariants.partnerControlReconciles(jdbc, org, customer, true));
        soft.check(() -> LedgerInvariants.partnerControlReconciles(jdbc, org, supplier, false));
        soft.check(() -> LedgerInvariants.inventoryTiesOut(okAs(b, 200, "GET",
                "/reports/inventory/gl-tieout?tolerance=0.05", null), new BigDecimal("0.05")));
        soft.check(() -> LedgerInvariants.taxReconciles(new BigDecimal("90"), actual.get("tax_payable").negate()));
        soft.check(() -> LedgerInvariants.postingComplete(jdbc, org, "invoice", inv, 1, false));
        soft.check(() -> LedgerInvariants.balanceSheetBalanced(balanceSheet(b, today)));

        // 6. Bank statement and reconciliation.
        okAs(b, 200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", bankAccount, "currencyCode",
                baseCurrency(org), "lines", List.of(
                        Map.of("postedAt", today, "externalRef", "LC-IN", "description", "Customer", "amount", 400),
                        Map.of("postedAt", today, "externalRef", "LC-OUT", "description", "Supplier", "amount", -300))));
        JsonNode match = okAs(b, 200, "POST", "/bank-reconciliation/match?bankAccountId=" + bankAccount,
                Map.of("signed", true));
        soft.assertThat(match.get("matched").asInt()).as("statement lines matched").isEqualTo(2);

        // 7. Month-end close: result moves to retained earnings; P&L keeps the period's result.
        String periodEnd = jdbc.sql("SELECT to_char(\"endDate\", 'YYYY-MM-DD') FROM \"FiscalPeriod\" WHERE id = ?")
                .param(periodFor(org, today)).query(String.class).single();
        closePeriod(b, today);
        // The closing entry is dated at the period end.
        Map<String, BigDecimal> post = tbAsOf(org, periodEnd, TbView.POST_CLOSING);
        soft.assertThat(post.get(mappedCode(org, "retained_earnings"))).as("retained earnings")
                .isEqualByComparingTo("-250");
        soft.check(() -> LedgerInvariants.trialBalanceBalanced(post));
        soft.check(() -> LedgerInvariants.balanceSheetBalanced(balanceSheet(b, periodEnd)));
        JsonNode p = pnl(b, today.substring(0, 4) + "-01-01", periodEnd);
        soft.assertThat(new BigDecimal(p.get("netProfit").asString())).as("P&L of the closed period")
                .isEqualByComparingTo("250");
        soft.assertAll();
    }
}
