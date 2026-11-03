package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ACC-0 reproduction: AR / AP documents (findings B8, B9). Scenarios are written as the commercial event (price
 * claim against a supplier bill, partial customer credit) plus the original document and its tax, not the label.
 */
class Acc0ArApIT extends AccountingFixtures {

    String vat;
    String service;

    @BeforeAll
    void fixtures() {
        vat = ok(201, "POST", "/taxes", Map.of("name", "ACC0 VAT " + UUID.randomUUID().toString().substring(0, 6),
                "rate", 18, "type", "vat", "vatCategory", "standard")).get("id").asString();
        service = demoProduct("ACC0 haulage", false, 100, 0);
    }

    BigDecimal residual(String documentId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(BigDecimal.class).single();
    }

    /** Net base movement on {@code key}'s account over every entry whose source is {@code sourceId}. */
    BigDecimal net(String key, String sourceId) {
        return netForSources(demoOrg, mapped(demoOrg, key), List.of(sourceId));
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B8][owner:accounting][ACC-4] supplier's extra charge (inbound debit note) with VAT books input VAT"
            + " and is traceable to the bill")
    void supplierChargeWithVat() {
        // Commercial event: the supplier charges an extra 10 + 18 % VAT against bill B. "inbound" = received from the
        // supplier (DebitNotesService B1: Dr expense / Cr AP). Expected: Dr expense 10, Dr input VAT 1.8, Cr AP 11.8,
        // and the extra payable is open against bill B. A claim AGAINST the supplier that reduces AP has no document
        // type today — capability gap (ACC-4), recorded in ACC-0-verification.md.
        String supplier = demoPartner("ACC0 charge supplier", true);
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "lines",
                List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 100, "taxId", vat)))).get("id")
                .asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(residual(bill)).as("bill incl. 18% VAT").isEqualByComparingTo("118");
        assertThat(net("tax_receivable", bill)).as("input VAT on the bill").isEqualByComparingTo("18");

        JsonNode note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId",
                supplier, "documentId", bill, "reason", "price_adjustment", "issueDate", today(), "lines",
                List.of(Map.of("description", "Extra charge", "quantity", 1, "unitPrice", 10, "taxId", vat))));
        String id = note.get("id").asString();
        Res posted = call("PATCH", "/procurement/debit-notes/" + id + "/post", null, demoKey());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(net("accounts_payable", id)).as("AP raised incl. VAT").isEqualByComparingTo("-11.8");
        soft.assertThat(net("tax_receivable", id)).as("input VAT on the charge").isEqualByComparingTo("1.8");
        soft.assertThat(residual(bill)).as("bill B's open payable incl. the charge").isEqualByComparingTo("129.8");
        soft.assertAll();
        LedgerInvariants.partnerControlReconciles(jdbc, demoOrg, supplier, false);
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B9][owner:accounting][ACC-4] a credit note cannot credit more quantity than was invoiced")
    void creditNoteQuantityCapped() {
        String customer = demoPartner("ACC0 qty customer", false);
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", service, "quantity", 2, "unitPrice", 50)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        Res cn = as("POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(), "reversedDocumentId",
                inv, "lines", List.of(Map.of("productId", service, "quantity", 3, "unitPrice", 50))));
        Res posted = cn.status() < 300
                ? as("POST", "/credit-notes/" + cn.body().get("id").asString() + "/post", null) : cn;
        assertThat(posted.status()).as("crediting 3 of 2 invoiced units → " + posted.raw().body())
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B9][owner:accounting][ACC-4] open items: invoice 10,000 − receipt 4,000 − credit 1,000 = 5,000, "
            + "with an allocation record for the credit")
    void openItemAllocation() {
        String customer = demoPartner("ACC0 open-item customer", false);
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", service, "quantity", 100, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        demoMoney("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", 4000,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 4000))));
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(),
                "reversedDocumentId", inv, "lines", List.of(Map.of("productId", service, "quantity", 10, "unitPrice",
                        100)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);

        assertThat(residual(inv)).as("invoice residual").isEqualByComparingTo("5000");
        LedgerInvariants.partnerControlReconciles(jdbc, demoOrg, customer, true);
        long creditAllocations = jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"documentId\" = ?")
                .param(inv).query(Long.class).single();
        assertThat(creditAllocations).as("allocation history for invoice (receipt + credit note)").isEqualTo(2);
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B9][owner:accounting][ACC-4] credit larger than the residual leaves a reusable unallocated credit")
    void creditBeyondResidual() {
        String customer = demoPartner("ACC0 over-credit customer", false);
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        demoMoney("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", 60,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 60))));
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today(),
                "reversedDocumentId", inv, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice",
                        100)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(residual(inv)).as("invoice settled").isEqualByComparingTo("0");
        assertThat(residual(cn)).as("unallocated credit kept on the credit note").isEqualByComparingTo("60");
        LedgerInvariants.partnerControlReconciles(jdbc, demoOrg, customer, true);
    }
}
