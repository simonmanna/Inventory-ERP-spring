package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Invoicing + expenses on Testcontainers Postgres: invoice draft / preview / post / cancel, credit note, receipts
 * with allocations and void, vendor bills with stock-in and supplier payments with withholding, AR / AP reports,
 * expenses lifecycle, money-route idempotency, authorization matrix and tenant isolation.
 */
class InvoicingIT extends ApiTestSupport {

    String customer;
    String supplier;
    String service;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        customer = partner("Customer", false);
        supplier = partner("Supplier", true);
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'Advisory', 'service', 50, 0,"
                        + " false, now())")
                .params(service, demoOrg, "SV-" + UUID.randomUUID().toString().substring(0, 6)).update();
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "P-" + UUID.randomUUID().toString().substring(0, 8), name + " "
                        + UUID.randomUUID().toString().substring(0, 4), isSupplier, !isSupplier)
                .update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode invoice(int qty, int price) {
        return ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", service, "quantity", qty, "unitPrice", price))));
    }

    @Test
    @DisplayName("invoices: draft, preview, edit, post (AR/revenue JE), list merge, cancel reverses")
    void invoices() {
        JsonNode draft = invoice(2, 50);
        String id = draft.get("id").asString();
        assertThat(draft.get("documentNumber").asString()).startsWith("INV-" + LocalDate.now().getYear() + "-");
        assertThat(draft.get("totalAmount").asString()).isEqualTo("100");
        assertThat(draft.get("lines")).hasSize(1);
        assertThat(draft.get("partner").get("id").asString()).isEqualTo(customer);
        assertThat(validationMessages(as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today))))
                .containsExactly("lines must contain at least 1 elements", "lines must be an array");
        // DefaultValuePipe treats Number("x") = NaN as missing; ParseIntPipe still rejects a fraction.
        assertThat(ok(200, "GET", "/invoices?page=x", null).get("meta").get("page").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/invoices?page=1e1", null).get("meta").get("page").asInt()).isEqualTo(10);
        assertThat(as("GET", "/invoices?page=1.5", null).body().get("message").asString())
                .isEqualTo("Validation failed (numeric string is expected)");

        JsonNode preview = ok(201, "POST", "/invoices/journal-preview", Map.of("partnerId", customer, "issueDate", today,
                "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 10))));
        assertThat(preview.get("balanced").asBoolean()).isTrue();
        assertThat(preview.get("journal").get("code").asString()).isEqualTo("SALES");
        assertThat(preview.get("lines").get(0).get("debit").asString()).isEqualTo("10");

        JsonNode edited = ok(200, "PATCH", "/invoices/" + id, Map.of("partnerId", customer, "issueDate", today,
                "lines", List.of(Map.of("productId", service, "quantity", 3, "unitPrice", 50))));
        assertThat(edited.get("totalAmount").asString()).isEqualTo("150");

        JsonNode posted = ok(200, "POST", "/invoices/" + id + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("journalEntryId").isNull()).isFalse();
        assertThat(posted.get("amountResidual").asString()).isEqualTo("150");
        Res editPosted = as("PATCH", "/invoices/" + id, Map.of("partnerId", customer, "issueDate", today,
                "lines", List.of(Map.of("quantity", 1, "unitPrice", 5))));
        assertThat(editPosted.body().get("message").asString()).isEqualTo("Only draft invoices can be edited");

        JsonNode detail = ok(200, "GET", "/invoices/" + id, null);
        assertThat(detail.get("lines").get(0).has("account")).isTrue();
        JsonNode list = ok(200, "GET", "/invoices?partnerId=" + customer, null);
        assertThat(list.get("data").get(0).get("source").asString()).isEqualTo("manual");
        assertThat(list.get("data").get(0).get("paymentStatus").asString()).isEqualTo("unpaid");

        JsonNode cancelled = ok(200, "POST", "/invoices/" + id + "/cancel", null);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("amountResidual").asString()).isEqualTo("0");
        String jeStatus = jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?")
                .param(posted.get("journalEntryId").asString()).query(String.class).single();
        assertThat(jeStatus).isEqualTo("reversed");
        Res missing = as("POST", "/invoices", Map.of("issueDate", today, "lines", List.of()));
        assertThat(missing.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("receipts: allocation settles the invoice, key required, replay, void restores the residual")
    void receipts() {
        String id = invoice(1, 80).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        Res noKey = as("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 30,
                "paymentMethod", "bank"));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString()).isEqualTo(
                "An Idempotency-Key header is required for this operation so a retry can never charge twice.");
        Map<String, String> h = key();
        Map<String, Object> body = Map.of("partnerId", customer, "paymentDate", today, "amount", 30, "paymentMethod",
                "bank", "allocations", List.of(Map.of("documentId", id, "amount", 30)));
        Res first = call("POST", "/payments", body, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", "/payments", body, h);
        assertThat(replay.body().get("paymentNumber").asString()).isEqualTo(first.body().get("paymentNumber").asString());
        long count = jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"documentId\" = ?").param(id)
                .query(Long.class).single();
        assertThat(count).isEqualTo(1);
        JsonNode doc = ok(200, "GET", "/invoices/" + id, null);
        assertThat(doc.get("amountResidual").asString()).isEqualTo("50");
        assertThat(doc.get("paymentStatus").asString()).isEqualTo("partial");
        assertThat(doc.get("allocations").get(0).get("payment").get("id").asString())
                .isEqualTo(first.body().get("id").asString());
        Res over = call("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 100,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", id, "amount", 100))), key());
        assertThat(over.body().get("message").asString()).startsWith("Allocation to document ");

        String paymentId = first.body().get("id").asString();
        Res noReason = call("POST", "/payments/" + paymentId + "/void", Map.of("reason", ""), key());
        assertThat(noReason.status()).isEqualTo(400);
        JsonNode voided = money("/payments/" + paymentId + "/void", Map.of("reason", "duplicate"));
        assertThat(voided.get("status").asString()).isEqualTo("cancelled");
        assertThat(voided.get("allocatedAmount").asString()).isEqualTo("0");
        JsonNode restored = ok(200, "GET", "/invoices/" + id, null);
        assertThat(restored.get("amountResidual").asString()).isEqualTo("80");
        assertThat(restored.get("paymentStatus").asString()).isEqualTo("not_paid");
        assertThat(ok(200, "GET", "/payments?partnerId=" + customer, null).get("meta").get("total").asInt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("credit notes: post reverses revenue and reduces the invoice residual")
    void creditNotes() {
        String inv = invoice(1, 100).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        JsonNode cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today,
                "reversedDocumentId", inv, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 40))));
        assertThat(cn.get("documentNumber").asString()).startsWith("CN-");
        JsonNode posted = ok(200, "POST", "/credit-notes/" + cn.get("id").asString() + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("amountResidual").asString()).isEqualTo("0");
        assertThat(ok(200, "GET", "/invoices/" + inv, null).get("amountResidual").asString()).isEqualTo("60");
        Res again = as("POST", "/credit-notes/" + cn.get("id").asString() + "/post", null);
        assertThat(again.body().get("message").asString()).isEqualTo("Credit note is already posted");
        assertThat(ok(200, "GET", "/credit-notes", null).get("meta").get("total").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("vendor bills: post books AP (PURCH), receives stock; supplier payment with withholding; AP aging")
    void vendorBills() {
        String wh = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElseGet(() -> ok(201, "POST", "/inventory/locations",
                        Map.of("code", "WH-" + UUID.randomUUID().toString().substring(0, 6), "name", "WH",
                                "type", "warehouse")).get("id").asString());
        String stock = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costPrice\","
                        + " \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'Beans', 'stockable', 3, true, now())")
                .params(stock, demoOrg, "BN-" + UUID.randomUUID().toString().substring(0, 6)).update();
        Res future = as("POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", "2999-01-01", "lines",
                List.of(Map.of("quantity", 1, "unitPrice", 5))));
        assertThat(future.body().get("message").asString()).isEqualTo("Issue date cannot be in the future");
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", stock, "quantity", 10, "unitPrice", 4),
                        Map.of("description", "Delivery", "quantity", 1, "unitPrice", 20))));
        assertThat(bill.get("documentNumber").asString()).startsWith("BILL-");
        assertThat(bill.get("approvalRequestId").isNull()).isTrue();
        String id = bill.get("id").asString();
        JsonNode posted = ok(200, "POST", "/vendor-bills/" + id + "/post", Map.of());
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("amountResidual").asString()).isEqualTo("60");
        String journal = jdbc.sql("SELECT j.code FROM \"JournalEntry\" e JOIN \"Journal\" j ON j.id = e.\"journalId\""
                + " WHERE e.id = ?").param(posted.get("journalEntryId").asString()).query(String.class).single();
        assertThat(journal).isEqualTo("PURCH");
        BigDecimal received = jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"productId\" = ? AND \"referenceType\" = 'vendor_bill'").param(stock).query(BigDecimal.class).single();
        assertThat(received).isEqualByComparingTo("10");

        Res overpay = call("POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today, "amount",
                1000, "paymentMethod", "bank"), key());
        assertThat(overpay.body().get("message").asString()).startsWith("Payment amount 1000 exceeds supplier outstanding");
        JsonNode pay = money("/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today, "amount", 60,
                "paymentMethod", "bank", "withholdingAmount", 6, "allocations", List.of(Map.of("documentId", id,
                        "amount", 60))));
        assertThat(pay.get("paymentNumber").asString()).startsWith("PV-");
        assertThat(pay.get("withholdingAmount").asString()).isEqualTo("6");
        BigDecimal wht = jdbc.sql("SELECT l.credit FROM \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\""
                + " WHERE l.\"journalEntryId\" = ? AND a.code = '2160'").param(pay.get("journalEntryId").asString())
                .query(BigDecimal.class).single();
        assertThat(wht).isEqualByComparingTo("6");
        assertThat(ok(200, "GET", "/vendor-bills/" + id, null).get("status").asString()).isEqualTo("paid");
        JsonNode ledger = ok(200, "GET", "/vendor-bills/suppliers/" + supplier + "/ledger", null);
        // B2 (post-parity): the statement is the supplier's AP sub-ledger — bill and payment net to zero (Nest
        // dropped the paid bill and closed at -60).
        assertThat(ledger.get("closingBalance").asInt()).isZero();
        assertThat(ledger.get("transactions")).hasSize(2);
        assertThat(ledger.get("transactions").get(0).get("type").asString()).isEqualTo("bill");
        assertThat(ledger.get("transactions").get(1).get("type").asString()).isEqualTo("payment");
        // A suite that rebuilt report snapshots moments ago would make the aging answer from them.
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        assertThat(ok(200, "GET", "/reports/ap/aging", null).get("source").asString()).isEqualTo("live");
        assertThat(ok(200, "GET", "/supplier-payments?partnerId=" + supplier, null).get("data")).hasSize(1);
        assertThat(wh).isNotNull();
    }

    @Test
    @DisplayName("AR reports: aging buckets, balances, outstanding, revenue by customer / period")
    void arReports() {
        String inv = invoice(1, 70).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        JsonNode aging = ok(200, "GET", "/reports/ar/aging", null);
        assertThat(aging.get("buckets").has("d90_plus")).isTrue();
        assertThat(aging.get("rows").size()).isGreaterThan(0);
        JsonNode balances = ok(200, "GET", "/reports/ar/customer-balances", null);
        assertThat(balances.toString()).contains(customer);
        assertThat(ok(200, "GET", "/reports/ar/outstanding", null).size()).isGreaterThan(0);
        assertThat(ok(200, "GET", "/reports/ar/revenue-by-customer", null).toString()).contains(customer);
        JsonNode periods = ok(200, "GET", "/reports/ar/revenue-by-period", null);
        assertThat(periods.get(0).get("period").asString()).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("expenses: categories, credit → approved → pay (Dr expense / Cr bank) → void; cash on create; SoD")
    void expenses() {
        String bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'").param(demoOrg)
                .query(String.class).single();
        money("/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 500, "paymentMethod", "bank"));
        String expenseAcc = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5200'")
                .param(demoOrg).query(String.class).single();
        Res badLedger = as("POST", "/expense-categories", Map.of("name", "Bad", "ledgerAccountId",
                UUID.randomUUID().toString()));
        assertThat(badLedger.body().get("message").asString()).isEqualTo("Ledger account not found");
        JsonNode cat = ok(201, "POST", "/expense-categories", Map.of("name", "Fuel " + UUID.randomUUID().toString()
                .substring(0, 4), "ledgerAccountId", expenseAcc));
        assertThat(cat.get("ledgerAccount").get("id").asString()).isEqualTo(expenseAcc);
        assertThat(ok(200, "PATCH", "/expense-categories/" + cat.get("id").asString(), Map.of("icon", "fuel"))
                .get("icon").asString()).isEqualTo("fuel");

        // ADR-S051: a CREDIT expense accrues to its supplier's AP sub-ledger — own supplier, so the vendor-bill
        // statement assertions above stay independent of test order.
        String supplier = partner("Expense supplier", true);
        JsonNode credit = money("/expenses", Map.of("title", "Generator fuel", "amount", 40, "expenseDate", today,
                "paymentType", "CREDIT", "categoryId", cat.get("id").asString(), "supplierId", supplier));
        assertThat(credit.get("status").asString()).isEqualTo("APPROVED");
        assertThat(credit.get("amount").asInt()).isEqualTo(40);
        assertThat(credit.get("supplier").get("id").asString()).isEqualTo(supplier);
        String id = credit.get("id").asString();
        JsonNode paid = money("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank));
        assertThat(paid.get("paymentStatus").asString()).isEqualTo("PAID");
        assertThat(paid.get("status").asString()).isEqualTo("POSTED");
        assertThat(paid.get("paymentMethod").asString()).isEqualTo("BANK_TRANSFER");
        Res again = call("POST", "/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank),
                key());
        assertThat(again.body().get("message").asString()).isEqualTo("Expense is already fully paid");
        JsonNode voided = money("/expenses/" + id + "/void", Map.of("voidReason", "wrong supplier"));
        assertThat(voided.get("status").asString()).isEqualTo("VOID");
        assertThat(voided.get("amountPaid").asInt()).isZero();
        assertThat(ok(200, "GET", "/expenses/" + id + "/audit", null).size()).isGreaterThanOrEqualTo(3);

        JsonNode cash = money("/expenses", Map.of("title", "Courier", "amount", 15, "expenseDate", today,
                "paymentType", "CASH", "paymentMethod", "BANK_TRANSFER", "accountId", bank));
        assertThat(cash.get("paymentStatus").asString()).isEqualTo("PAID");
        Res cashMissing = call("POST", "/expenses", Map.of("title", "X", "amount", 1, "expenseDate", today,
                "paymentType", "CASH"), key());
        assertThat(cashMissing.body().get("message").asString()).isEqualTo("Cash expenses require paymentMethod and accountId");

        JsonNode draft = money("/expenses", Map.of("title", "Snacks", "amount", 5, "expenseDate", today,
                "paymentType", "CREDIT"));
        jdbc.sql("UPDATE \"Expense\" SET status = 'DRAFT' WHERE id = ?").param(draft.get("id").asString()).update();
        Res sod = as("POST", "/expenses/" + draft.get("id").asString() + "/approve", Map.of());
        assertThat(sod.status()).isEqualTo(403);
        assertThat(sod.body().get("message").asString()).isEqualTo("The person who raised an expense cannot approve it");
        JsonNode rejected = ok(201, "POST", "/expenses/" + draft.get("id").asString() + "/reject", Map.of("reason", "no"));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(as("DELETE", "/expenses/" + draft.get("id").asString(), null).status()).isEqualTo(204);

        JsonNode list = ok(200, "GET", "/expenses?limit=5", null);
        assertThat(list.get("limit").asInt()).isEqualTo(5);
        assertThat(ok(200, "GET", "/expenses/stats", null).get("count").asInt()).isGreaterThan(0);
        assertThat(ok(200, "GET", "/expenses/meta/accounts", null).toString()).contains(bank);
        assertThat(ok(200, "GET", "/expenses/meta/suppliers", null).toString()).contains(supplier);
        assertThat(as("DELETE", "/expense-categories/" + cat.get("id").asString(), null).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("tenant isolation: another organization cannot read invoices or expenses")
    void tenantIsolation() {
        String id = invoice(1, 10).get("id").asString();
        Res other = call("GET", "/invoices/" + id, null, otherToken);
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.body()).isNull();
        assertThat(call("GET", "/expenses", null, otherToken).body().get("total").asInt()).isZero();
        Res cross = call("POST", "/invoices/" + id + "/post", null, otherToken);
        assertThat(cross.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("authorization: invoicing (40 base + 10 ADR-S011 deposits / mobile money + 15 Phase 4 + 2 ADR-S025"
            + " line dimensions + 1 ACC-4 credit allocation + 4 ADR-S071 withholding certificates) and expenses (18 + 55 ADR-S052) routes answer 401 / 403")
    void authorizationMatrix() {
        // ADR-S071: +4 withholding certificate routes.
        assertThat(assertAuthorizationMatrix("com.inventoryerp.invoicing")).isEqualTo(72);
        // ADR-S052: +2 expense return/submit, +6 limits/settings, +18 claims/advances, +7 petty cash,
        // +14 schedules, +8 reports.
        assertThat(assertAuthorizationMatrix("com.inventoryerp.expenses")).isEqualTo(73);
    }
}
