package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ACC-7: fiscal positions that map taxes, VAT settlement and the tax-period lock (fresh books). */
class Acc7TaxIT extends AccountingFixtures {

    String partner(Books b, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, b.org(), "T-" + id.substring(0, 8),
                "Tax partner " + id.substring(0, 4), supplier, !supplier).update();
        return id;
    }

    String product(Books b, String taxId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"taxId\", \"updatedAt\") VALUES (?, ?, ?, 'Service',"
                        + " 'service', 100, 0, false, ?, now())")
                .params(id, b.org(), "S-" + id.substring(0, 6), taxId).update();
        return id;
    }

    JsonNode invoice(Books b, String customer, String product, String day) {
        String id = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", day, "lines",
                List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        return okAs(b, 200, "GET", "/invoices/" + id, null);
    }

    @Test
    @DisplayName("[ACC-7] a fiscal position maps VAT to exempt for its partner; others keep the product's VAT")
    void fiscalPositionExemptsTax() {
        Books b = freshBooks();
        String vat = okAs(b, 201, "POST", "/taxes", Map.of("name", "VAT 18", "rate", 18, "type", "vat",
                "vatCategory", "standard")).get("id").asString();
        String item = product(b, vat);
        String position = okAs(b, 201, "POST", "/fiscal-positions", Map.of("code", "EXP", "name", "Export"))
                .get("id").asString();
        Map<String, Object> exempt = new HashMap<>();
        exempt.put("sourceTaxId", vat);
        exempt.put("targetTaxId", null);
        JsonNode mapped = okAs(b, 200, "PUT", "/fiscal-positions/" + position + "/taxes",
                Map.of("mappings", List.of(exempt)));
        assertThat(mapped).hasSize(1);
        String exporter = partner(b, false);
        okAs(b, 200, "POST", "/fiscal-positions/" + position + "/partners", Map.of("partnerId", exporter));
        String day = LocalDate.now().toString();
        assertThat(new BigDecimal(invoice(b, exporter, item, day).get("taxAmount").asString()))
                .as("export customer: VAT mapped to exempt").isEqualByComparingTo("0");
        assertThat(new BigDecimal(invoice(b, partner(b, false), item, day).get("taxAmount").asString()))
                .as("domestic customer keeps VAT").isEqualByComparingTo("18");
        // A document stating the position applies it whoever the partner is.
        String stated = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate", day,
                "fiscalPositionId", position, "lines", List.of(Map.of("productId", item, "quantity", 1, "unitPrice",
                        100)))).get("id").asString();
        assertThat(new BigDecimal(okAs(b, 200, "GET", "/invoices/" + stated, null).get("taxAmount").asString()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("[ACC-7] VAT settlement offsets input against output, records the period and locks it")
    void vatSettlementAndLock() {
        Books b = freshBooks();
        String vat = okAs(b, 201, "POST", "/taxes", Map.of("name", "VAT 18", "rate", 18, "type", "vat",
                "vatCategory", "standard")).get("id").asString();
        String item = product(b, vat);
        String yesterday = LocalDate.now().minusDays(1).toString();
        String from = LocalDate.now().withDayOfYear(1).toString();
        // Sale 200 + 36 VAT, purchase 100 + 18 VAT.
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate", yesterday,
                "lines", List.of(Map.of("productId", item, "quantity", 2, "unitPrice", 100)))).get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", partner(b, true), "issueDate",
                yesterday, "lines", List.of(Map.of("description", "Supplies", "quantity", 1, "unitPrice", 100,
                        "taxId", vat)))).get("id").asString();
        okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());

        JsonNode preview = okAs(b, 200, "GET", "/tax-settlements/preview?from=" + from + "&to=" + yesterday, null);
        assertThat(new BigDecimal(preview.get("outputTax").asString())).isEqualByComparingTo("36");
        assertThat(new BigDecimal(preview.get("inputTax").asString())).isEqualByComparingTo("18");
        assertThat(new BigDecimal(preview.get("netPayable").asString())).isEqualByComparingTo("18");
        JsonNode settled = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday));
        String entry = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> tb = tbAsOf(b.org(), yesterday, TbView.POST_CLOSING);
        assertThat(tb.getOrDefault(mappedCode(b.org(), "tax_receivable"), BigDecimal.ZERO)).as("input VAT cleared")
                .isEqualByComparingTo("0");
        assertThat(tb.get(mappedCode(b.org(), "tax_payable"))).as("net VAT due").isEqualByComparingTo("-18");
        LedgerInvariants.entriesBalanced(jdbc, b.org(), Collections.singletonList(entry));

        Res again = as(b, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday));
        assertThat(again.status()).as("overlapping settlement").isEqualTo(400);
        String late = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate", yesterday,
                "lines", List.of(Map.of("productId", item, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        Res locked = as(b, "POST", "/invoices/" + late + "/post", null);
        assertThat(locked.status()).as(locked.raw().body()).isEqualTo(400);
        assertThat(locked.body().get("message").asString()).startsWith("VAT is settled through");
        String today = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", partner(b, false), "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("productId", item, "quantity", 1, "unitPrice",
                        100)))).get("id").asString();
        assertThat(as(b, "POST", "/invoices/" + today + "/post", null).status()).as("after the settled period")
                .isEqualTo(200);
    }
}
