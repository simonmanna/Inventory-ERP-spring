package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Purchase readiness fixes, stream "pay" (owner-approved 2026-11-27): the supplier-overpayment cap counts unallocated
 * payments and foreign bills at their carrying rate (B1), the AP aging ties to the payable control accounts (B2), the
 * PO pay route passes the supplier payment controls (H5), a supplier-payment approval authorises one payment (H6), the
 * supplier's status and verified bank account gate both payment routes (H7), and a held bill / a register drawer
 * account cannot be paid from (M). Runs in DEMO; every assertion is scoped to the partners the test created.
 */
class PurchaseReadinessPayIT extends AccountingFixtures {

    String warehouse;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PRP-" + sfx(), "name", "Pay readiness "
                + sfx(), "type", "warehouse")).get("id").asString();
    }

    // ------------------------------------------------------------------ helpers

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String supplier(String name) {
        return demoPartner("PRP " + name, true);
    }

    String service() {
        return demoProduct("PRP service " + sfx(), false, 100, 0);
    }

    /** A posted vendor bill of one service line. */
    String bill(String supplier, int amount) {
        return bill(supplier, null, amount);
    }

    String bill(String supplier, String currency, int amount) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", service(), "description", "Haulage", "quantity", 1, "unitPrice", amount))));
        if (currency != null) {
            body.put("currencyId", currency);
        }
        String id = ok(200, "POST", "/vendor-bills", body).get("id").asString();
        ok(200, "POST", "/vendor-bills/" + id + "/post", Map.of("override3WM", true, "overrideApproval", true));
        return id;
    }

    Map<String, Object> payment(String supplier, Object amount, Object... extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "paymentDate", today, "amount",
                amount, "paymentMethod", "cheque"));
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return body;
    }

    Res pay(Map<String, Object> body) {
        return call("POST", "/supplier-payments", body, demoKey());
    }

    String demoRate(String ccy, int rate) {
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", baseCurrency(demoOrg), "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", rate));
        return ccy;
    }

    /** What the ledger owes the supplier on the payable control accounts (credit-normal). */
    BigDecimal apLedger(String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                        + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE l.\"organizationId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND c.key = 'payable' AND l.\"partnerId\" = ?")
                .params(demoOrg, partnerId).query(BigDecimal.class).single();
    }

    static JsonNode partnerRow(JsonNode aging, String partnerId) {
        for (JsonNode p : aging.get("partners")) {
            if (partnerId.equals(p.get("partnerId").asString())) {
                return p;
            }
        }
        return null;
    }

    long postedPayments(String supplier) {
        int n = 0;
        for (JsonNode p : ok(200, "GET", "/supplier-payments?pageSize=50&partnerId=" + supplier, null).get("data")) {
            if (!"cancelled".equals(p.get("status").asString())) {
                n++;
            }
        }
        return n;
    }

    static List<Res> race(int n, Callable<Res> call) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(call));
            }
            List<Res> out = new ArrayList<>();
            for (Future<Res> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdown();
        }
    }

    String approvalWorkflow() {
        String wf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, 'PRP supplier payments', 'supplier_payment', now())").params(wf, demoOrg).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                        + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Finance', ?, now())")
                .params(UUID.randomUUID().toString(), demoOrg, wf, new String[] {"approvals:decide"}).update();
        return wf;
    }

    void endWorkflow(String wf) {
        jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(wf).update();
        jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"workflowId\" = ? AND status = 'pending'")
                .param(wf).update();
    }

    static String requestId(Res refused) {
        String msg = refused.body().get("message").asString();
        assertThat(msg).startsWith("Vendor payment requires approval. Request ID: ");
        return msg.substring("Vendor payment requires approval. Request ID: ".length(), msg.indexOf(". Please"));
    }

    void approve(String requestId) {
        jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'approved' WHERE id = ?").param(requestId).update();
    }

    String receivedPo(String supplier, List<Map<String, Object>> lines) {
        String po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", lines)).get("id").asString();
        List<Map<String, Object>> receive = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            receive.add(Map.of("productId", l.get("productId"), "description", l.get("description"), "quantity",
                    l.get("quantity")));
        }
        Res r = call("POST", "/procurement/purchase-orders/" + po + "/receive", Map.of("warehouseId", warehouse,
                "lines", receive), demoKey());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return po;
    }

    // ------------------------------------------------------------------ B1

    @Test
    @DisplayName("B1: an unallocated supplier payment uses up the open bills, so the same bill cannot be paid twice")
    void unallocatedPaymentsCountAgainstTheCap() {
        String s = supplier("B1 sequential");
        String bill = bill(s, 100);
        Res first = pay(payment(s, 100));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(first.body().get("unallocatedAmount").asString()).isEqualTo("100");

        Res second = pay(payment(s, 100));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.body().get("message").asString()).isEqualTo("Payment amount 100 exceeds supplier outstanding"
                + " balance 0 (open bills less payments not yet allocated to a bill). Overpayments require"
                + " allowOverpayment=true.");
        Res allocated = pay(payment(s, 100, "allocations", List.of(Map.of("documentId", bill, "amount", 100))));
        assertThat(allocated.status()).as("an allocated payment of the bill the advance already covers")
                .isEqualTo(400);
        assertThat(postedPayments(s)).isEqualTo(1);
        assertThat(apLedger(s)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("B1: concurrent unallocated payments of one bill — the supplier row serialises them, one passes")
    void concurrentUnallocatedPaymentsPayOnce() {
        String s = supplier("B1 race");
        bill(s, 100);
        List<Res> results = race(4, () -> pay(payment(s, 100)));
        assertThat(results.stream().filter(r -> r.status() == 200).count()).as(results.toString()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 400).count()).isEqualTo(3);
        assertThat(postedPayments(s)).isEqualTo(1);
        assertThat(apLedger(s)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("B1: a functional-currency payment counts a foreign bill at its carrying rate, not its foreign amount")
    void functionalPaymentCountsForeignBillAtCarryingRate() {
        String ccy = demoRate(testCurrency(), 2);
        String s = supplier("B1 FX");
        bill(s, ccy, 10);
        assertThat(apLedger(s)).isEqualByComparingTo("20");
        Res within = pay(payment(s, 15));
        assertThat(within.status()).as("before the fix the cap was the bill's 10 " + ccy + ": " + within.raw().body())
                .isEqualTo(200);
        Res over = pay(payment(s, 6));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).contains("exceeds supplier outstanding balance 5 ");
        // a foreign payment is capped in the bill's own currency, less the functional advance beyond the functional
        // bills (15 at this payment's rate 2 = 7.5): 2.5 left
        Res foreignOver = pay(payment(s, 3, "currencyCode", ccy, "exchangeRate", 2));
        assertThat(foreignOver.status()).isEqualTo(400);
        assertThat(foreignOver.body().get("message").asString()).contains("exceeds supplier outstanding balance 2.5 ");
        Res foreignOk = pay(payment(s, 1, "currencyCode", ccy, "exchangeRate", 2));
        assertThat(foreignOk.status()).as(foreignOk.raw().body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ B2

    @Test
    @DisplayName("B2: the AP aging (live and snapshot) equals the supplier's payable ledger — foreign bill at its rate,"
            + " unallocated payment netted, received service line left to its bill")
    void apAgingTiesToTheLedger() {
        String ccy = demoRate(testCurrency(), 3);
        String s = supplier("B2 aging");
        bill(s, 500);
        bill(s, ccy, 10);
        Res advance = pay(payment(s, 200));
        assertThat(advance.status()).as(advance.raw().body()).isEqualTo(200);
        String stock = demoProduct("PRP stock " + sfx(), true, 0, 0);
        receivedPo(s, List.of(Map.of("productId", stock, "description", "Cement", "quantity", 2, "unitPrice", 100),
                Map.of("productId", service(), "description", "Delivery", "quantity", 1, "unitPrice", 50)));

        BigDecimal ledger = apLedger(s);
        assertThat(ledger).as("500 + 10 × 3 − 200 + 200 received stock; the service line waits for its bill")
                .isEqualByComparingTo("530");
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        JsonNode live = ok(200, "GET", "/reports/ap/aging", null);
        assertThat(live.get("source").asString()).isEqualTo("live");
        JsonNode row = partnerRow(live, s);
        assertThat(row).isNotNull();
        assertThat(new BigDecimal(row.get("total").asString())).isEqualByComparingTo(ledger);
        assertThat(new BigDecimal(row.get("unapplied").asString())).isEqualByComparingTo("200");
        assertThat(new BigDecimal(row.get("buckets").get("current").asString())).isEqualByComparingTo("730");

        // the snapshot branch stores the same measure
        ok(201, "POST", "/reports/accounting/rebuild-snapshots", null);
        JsonNode snap = ok(200, "GET", "/reports/ap/aging", null);
        assertThat(snap.get("source").asString()).isEqualTo("snapshot");
        JsonNode snapRow = partnerRow(snap, s);
        assertThat(new BigDecimal(snapRow.get("total").asString())).isEqualByComparingTo(ledger);
        assertThat(new BigDecimal(snapRow.get("unapplied").asString())).isEqualByComparingTo("200");
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();

        // and the AP reconciliation report (the tie-out measure) shows no difference for the supplier
        Res rec = call("POST", "/reports/run/ap.reconciliation", Map.of("asOf", today), adminToken);
        assertThat(rec.status()).as(rec.raw().body()).isEqualTo(200);
        JsonNode recRow = null;
        for (JsonNode r : rec.body().get("rows")) {
            if (s.equals(r.get("id").asString())) {
                recRow = r;
            }
        }
        assertThat(recRow).isNotNull();
        assertThat(new BigDecimal(recRow.get("values").get("subledger").asString())).isEqualByComparingTo(ledger);
        assertThat(new BigDecimal(recRow.get("values").get("difference").asString())).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ H5 / H6

    @Test
    @DisplayName("H6: an approved supplier payment authorises one payment; the next needs a new approval, and two"
            + " concurrent payments cannot share one approval; H5: the PO pay route is gated too")
    void approvalAuthorisesOnePayment() {
        String s = supplier("H6 approval");
        bill(s, 1000);
        String wf = approvalWorkflow();
        try {
            Res refused = pay(payment(s, 100));
            assertThat(refused.status()).isEqualTo(403);
            String first = requestId(refused);
            approve(first);
            Res paid = pay(payment(s, 100));
            assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT \"consumedRef\" FROM \"ApprovalRequest\" WHERE id = ?").param(first)
                    .query(String.class).single()).isEqualTo("Payment:" + paid.body().get("id").asString());

            Res again = pay(payment(s, 100));
            assertThat(again.status()).as("the used approval covers no second payment").isEqualTo(403);
            String second = requestId(again);
            assertThat(second).isNotEqualTo(first);
            approve(second);
            List<Res> results = race(2, () -> pay(payment(s, 100)));
            assertThat(results.stream().filter(r -> r.status() == 200).count()).as(results.toString()).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.status() == 409 || r.status() == 403).count()).isEqualTo(1);
            assertThat(postedPayments(s)).isEqualTo(2);

            // H5: the PO route asks for the same approval
            String stock = demoProduct("PRP stock " + sfx(), true, 0, 0);
            String po = receivedPo(s, List.of(Map.of("productId", stock, "description", "Cement", "quantity", 1,
                    "unitPrice", 100)));
            Res poPay = call("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of("method", "bank",
                    "supplierBankAccountId", verifiedSupplierBankAccount(s)), demoKey());
            assertThat(poPay.status()).as(poPay.raw().body()).isEqualTo(403);
            String poRequest = requestId(poPay);
            approve(poRequest);
            Res poPaid = call("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of("method", "bank",
                    "supplierBankAccountId", verifiedSupplierBankAccount(s)), demoKey());
            assertThat(poPaid.status()).as(poPaid.raw().body()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT \"consumedRef\" FROM \"ApprovalRequest\" WHERE id = ?").param(poRequest)
                    .query(String.class).single()).isEqualTo("PurchasePayment:" + poPaid.body().get("id").asString());
        } finally {
            endWorkflow(wf);
        }
    }

    @Test
    @DisplayName("H6: an idempotent replay of an approved payment returns it and uses the approval once")
    void approvedPaymentReplay() {
        String s = supplier("H6 replay");
        bill(s, 500);
        String wf = approvalWorkflow();
        try {
            String first = requestId(pay(payment(s, 100)));
            approve(first);
            Map<String, String> key = demoKey();
            Res a = call("POST", "/supplier-payments", payment(s, 100), key);
            Res b = call("POST", "/supplier-payments", payment(s, 100), key);
            assertThat(a.status()).as(a.raw().body()).isEqualTo(200);
            assertThat(b.status()).as(b.raw().body()).isEqualTo(200);
            assertThat(b.body().get("id").asString()).isEqualTo(a.body().get("id").asString());
            assertThat(postedPayments(s)).isEqualTo(1);
        } finally {
            endWorkflow(wf);
        }
    }

    // ------------------------------------------------------------------ H7

    @Test
    @DisplayName("H7: a suspended or blacklisted supplier is not paid on either route")
    void blockedSupplierIsNotPaid() {
        String s = supplier("H7 suspended");
        bill(s, 300);
        String stock = demoProduct("PRP stock " + sfx(), true, 0, 0);
        String po = receivedPo(s, List.of(Map.of("productId", stock, "description", "Cement", "quantity", 1,
                "unitPrice", 100)));
        String account = verifiedSupplierBankAccount(s);
        ok(200, "PUT", "/procurement/suppliers/" + s + "/profile", Map.of("riskClass", "low"));
        ok(200, "POST", "/procurement/suppliers/" + s + "/profile/suspend", Map.of("reason", "Quality"));

        Res sp = pay(payment(s, 100));
        assertThat(sp.status()).isEqualTo(400);
        assertThat(sp.body().get("message").asString()).isEqualTo("Supplier " + jdbc.sql("SELECT name FROM \"Partner\""
                + " WHERE id = ?").param(s).query(String.class).single() + " is suspended (Quality); payments to a"
                + " suspended or blacklisted supplier are refused");
        Res poPay = call("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of("method", "bank",
                "supplierBankAccountId", account), demoKey());
        assertThat(poPay.status()).isEqualTo(400);
        assertThat(poPay.body().get("message").asString()).contains("is suspended");

        ok(200, "POST", "/procurement/suppliers/" + s + "/profile/blacklist", Map.of("reason", "Fake invoices"));
        assertThat(pay(payment(s, 100)).body().get("message").asString()).contains("is blacklisted");
        assertThat(postedPayments(s)).isZero();
    }

    @Test
    @DisplayName("H7: a bank transfer names a verified bank account of the supplier (default block); the account is"
            + " stored on the payment; off makes it optional")
    void bankTransferNeedsVerifiedAccount() {
        String s = supplier("H7 bank");
        bill(s, 1000);
        Res none = pay(payment(s, 100, "paymentMethod", "bank"));
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.body().get("message").asString()).isEqualTo("A bank transfer to a supplier must name one of the"
                + " supplier's verified bank accounts (supplierBankAccountId). Register the account on the supplier"
                + " and have someone else verify it first.");
        String pending = ok(201, "POST", "/procurement/suppliers/" + s + "/bank-accounts", Map.of("bankName", "Bank",
                "accountName", "Pending", "accountNumber", "99998888")).get("id").asString();
        Res unverified = pay(payment(s, 100, "paymentMethod", "bank", "supplierBankAccountId", pending));
        assertThat(unverified.status()).isEqualTo(400);
        assertThat(unverified.body().get("message").asString()).isEqualTo("The supplier bank account is pending"
                + " verification; only a verified bank account can receive a payment");
        String others = verifiedSupplierBankAccount(supplier("H7 other"));
        Res foreignAccount = pay(payment(s, 100, "paymentMethod", "bank", "supplierBankAccountId", others));
        assertThat(foreignAccount.body().get("message").asString())
                .isEqualTo("The supplier bank account does not belong to this supplier");

        String account = verifiedSupplierBankAccount(s);
        JsonNode listed = ok(200, "GET", "/procurement/suppliers/" + s + "/bank-accounts/verified", null);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asString()).isEqualTo(account);
        assertThat(listed.get(0).has("accountNumberEnc")).isFalse();
        Res ok = pay(payment(s, 100, "paymentMethod", "bank", "supplierBankAccountId", account));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.body().get("supplierBankAccountId").asString()).isEqualTo(account);

        // PO pay: same rule
        String stock = demoProduct("PRP stock " + sfx(), true, 0, 0);
        String po = receivedPo(s, List.of(Map.of("productId", stock, "description", "Cement", "quantity", 1,
                "unitPrice", 100)));
        Res poNone = call("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of("amount", 50), demoKey());
        assertThat(poNone.status()).isEqualTo(400);
        assertThat(poNone.body().get("message").asString()).startsWith("A bank transfer to a supplier must name");
        Res poOk = call("POST", "/procurement/purchase-orders/" + po + "/pay", Map.of("amount", 50,
                "supplierBankAccountId", account), demoKey());
        assertThat(poOk.status()).as(poOk.raw().body()).isEqualTo(200);
        assertThat(poOk.body().get("supplierBankAccountId").asString()).isEqualTo(account);

        ok(200, "PUT", "/settings/procurement.supplierBankVerification", Map.of("value", "off"));
        try {
            Res optional = pay(payment(s, 100, "paymentMethod", "bank"));
            assertThat(optional.status()).as(optional.raw().body()).isEqualTo(200);
            Res stillChecked = pay(payment(s, 100, "paymentMethod", "bank", "supplierBankAccountId", pending));
            assertThat(stillChecked.status()).as("a named account is still checked").isEqualTo(400);
        } finally {
            as("DELETE", "/settings/procurement.supplierBankVerification", null);
        }
    }

    // ------------------------------------------------------------------ M

    @Test
    @DisplayName("M: a bill on payment hold takes no allocation (payment or advance allocation)")
    void heldBillTakesNoAllocation() {
        String s = supplier("M hold");
        String held = bill(s, 200);
        String open = bill(s, 100);
        ok(200, "PUT", "/vendor-bills/" + held + "/payment-schedule", Map.of("onHold", true));
        Res refused = pay(payment(s, 50, "allocations", List.of(Map.of("documentId", held, "amount", 50))));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).endsWith("is on payment hold; release the hold in the"
                + " payment schedule before paying it");
        Res over = pay(payment(s, 150));
        assertThat(over.status()).as("a held bill is left out of the cap").isEqualTo(400);
        Res advance = pay(payment(s, 100));
        assertThat(advance.status()).as(advance.raw().body()).isEqualTo(200);
        Res allocate = call("POST", "/payments/" + advance.body().get("id").asString() + "/allocations",
                Map.of("allocations", List.of(Map.of("documentId", held, "amount", 50))), demoKey());
        assertThat(allocate.status()).isEqualTo(400);
        assertThat(allocate.body().get("message").asString()).contains("is on payment hold");
        Res allocateOpen = call("POST", "/payments/" + advance.body().get("id").asString() + "/allocations",
                Map.of("allocations", List.of(Map.of("documentId", open, "amount", 100))), demoKey());
        assertThat(allocateOpen.status()).as(allocateOpen.raw().body()).isEqualTo(200);
    }

    @Test
    @DisplayName("M: a supplier payment outside a shift cannot credit a register's drawer account")
    void drawerAccountOnlyFromTheShift() {
        String drawer = null;
        for (JsonNode r : ok(200, "GET", "/cash-registers", null).path("data")) {
            if (r.hasNonNull("defaultAccountId")) {
                drawer = r.get("defaultAccountId").asString();
                break;
            }
        }
        Assumptions.assumeTrue(drawer != null, "DEMO has a register with a drawer account");
        String s = supplier("M drawer");
        bill(s, 100);
        Res refused = pay(payment(s, 50, "paymentMethod", "cash", "accountId", drawer));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("A supplier payment cannot use the drawer"
                + " account of register");
    }
}
