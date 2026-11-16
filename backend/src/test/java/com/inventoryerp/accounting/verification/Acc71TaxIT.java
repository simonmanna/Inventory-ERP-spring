package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S071: tax groups and several taxes per line, withholding on bills and receipts, reverse-charge VAT. */
class Acc71TaxIT extends AccountingFixtures {

    String partner(Books b, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"taxNumber\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, now())").params(id, b.org(),
                "T-" + id.substring(0, 8), "Tax partner " + id.substring(0, 4), supplier, !supplier,
                "TIN" + id.substring(0, 6)).update();
        return id;
    }

    String tax(Books b, Map<String, Object> body) {
        return okAs(b, 201, "POST", "/taxes", body).get("id").asString();
    }

    String vat(Books b) {
        return tax(b, Map.of("name", "VAT 18", "rate", 18, "type", "vat", "vatCategory", "standard"));
    }

    String wht(Books b) {
        return tax(b, Map.of("name", "WHT 6", "rate", 6, "type", "withholding", "vatCategory", "out_of_scope"));
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    /** Account code to signed amount (debit +, credit −) of one entry. */
    Map<String, BigDecimal> entry(String entryId) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> l : lines(entryId)) {
            BigDecimal d = l.get("debit") == null ? BigDecimal.ZERO : (BigDecimal) l.get("debit");
            BigDecimal c = l.get("credit") == null ? BigDecimal.ZERO : (BigDecimal) l.get("credit");
            out.merge((String) l.get("code"), d.subtract(c), BigDecimal::add);
        }
        return out;
    }

    String journalOf(String documentId) {
        return jdbc.sql("SELECT \"journalEntryId\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(String.class).single();
    }

    Res money(Books b, String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + b.token());
        h.put("Idempotency-Key", key);
        return call(method, path, body, h);
    }

    @Test
    @DisplayName("[ADR-S071] a tax group and a tax list compute through one engine and post one line per tax account")
    void groupsAndTaxLists() {
        Books b = freshBooks();
        String vat = vat(b);
        String excise = tax(b, Map.of("name", "Excise 10", "rate", 10, "type", "sales_tax", "vatCategory", "standard",
                "accountId", account(b.org(), "2210")));
        String vatOnExcise = tax(b, Map.of("name", "VAT 18 compound", "rate", 18, "type", "vat", "isCompound", true,
                "vatCategory", "standard"));
        JsonNode group = okAs(b, 201, "POST", "/taxes", Map.of("name", "Excise + VAT", "type", "vat", "isGroup", true,
                "children", List.of(excise, vatOnExcise)));
        assertThat(group.get("children")).hasSize(2);
        assertThat(group.get("children").get(0).get("id").asString()).isEqualTo(excise);
        assertThat(okAs(b, 200, "GET", "/taxes/" + vat, null).has("children")).as("plain tax shape").isFalse();
        // a group cannot contain a group, nor be empty
        assertThat(as(b, "POST", "/taxes", Map.of("name", "Nested", "isGroup", true, "children",
                List.of(group.get("id").asString()))).status()).isEqualTo(400);
        assertThat(as(b, "POST", "/taxes", Map.of("name", "Empty", "isGroup", true)).status()).isEqualTo(400);

        String customer = partner(b, false);
        String day = LocalDate.now().toString();
        // Group: 1000 → excise 100, VAT 18% of 1100 = 198 (compound).
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", day, "lines",
                List.of(Map.of("description", "Cement", "quantity", 10, "unitPrice", 100, "taxId",
                        group.get("id").asString())))).get("id").asString();
        JsonNode doc = okAs(b, 200, "GET", "/invoices/" + inv, null);
        assertThat(dec(doc.get("taxAmount"))).isEqualByComparingTo("298");
        assertThat(dec(doc.get("totalAmount"))).isEqualByComparingTo("1298");
        assertThat(doc.has("withholdingAmount")).isFalse();
        JsonNode line = doc.get("lines").get(0);
        assertThat(line.get("taxes")).hasSize(2);
        assertThat(dec(line.get("taxes").get(1).get("base"))).as("compound base").isEqualByComparingTo("1100");
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        Map<String, BigDecimal> je = entry(journalOf(inv));
        assertThat(je.get("2210")).as("excise on its own account").isEqualByComparingTo("-100");
        assertThat(je.get(mappedCode(b.org(), "tax_payable"))).isEqualByComparingTo("-198");
        assertThat(je.get(mappedCode(b.org(), "accounts_receivable"))).isEqualByComparingTo("1298");

        // Tax list on a line: VAT + excise, both simple on 500.
        String inv2 = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", day, "lines",
                List.of(Map.of("description", "Steel", "quantity", 1, "unitPrice", 500, "taxIds",
                        List.of(vat, excise))))).get("id").asString();
        JsonNode doc2 = okAs(b, 200, "GET", "/invoices/" + inv2, null);
        assertThat(dec(doc2.get("taxAmount"))).isEqualByComparingTo("140");
        assertThat(doc2.get("lines").get(0).get("taxIds")).hasSize(2);
        okAs(b, 200, "POST", "/invoices/" + inv2 + "/post", null);
        LedgerInvariants.entriesBalanced(jdbc, b.org(), List.of(journalOf(inv), journalOf(inv2)));

        // Withholding is refused on a sales document.
        String whtId = wht(b);
        assertThat(as(b, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", day, "lines", List.of(Map.of(
                "description", "x", "quantity", 1, "unitPrice", 10, "taxIds", List.of(whtId))))).status())
                .isEqualTo(400);

        // The tax summary picks the breakdown up per tax: output VAT 198 + 90, excise 100 + 50.
        JsonNode summary = okAs(b, 200, "GET", "/reports/tax/summary?from=" + day + "&to=" + day, null);
        assertThat(dec(summary.get("output").get("taxTotal"))).isEqualByComparingTo("438");
    }

    @Test
    @DisplayName("[ADR-S071] withholding on a vendor bill: payable net, WHT payable, certificate, no second withholding")
    void billWithholding() {
        Books b = freshBooks();
        String vat = vat(b);
        String whtId = wht(b);
        String supplier = partner(b, true);
        String day = LocalDate.now().toString();
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", day,
                "withholdingTaxId", whtId, "lines", List.of(Map.of("description", "Haulage", "quantity", 1,
                        "unitPrice", 1000, "taxId", vat)))).get("id").asString();
        JsonNode draft = okAs(b, 200, "GET", "/vendor-bills/" + bill, null);
        assertThat(dec(draft.get("totalAmount"))).isEqualByComparingTo("1180");
        assertThat(dec(draft.get("withholdingAmount"))).isEqualByComparingTo("60");
        assertThat(dec(draft.get("amountResidual"))).isEqualByComparingTo("1120");
        okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        JsonNode posted = okAs(b, 200, "GET", "/vendor-bills/" + bill, null);
        assertThat(dec(posted.get("amountResidual"))).as("residual net of WHT").isEqualByComparingTo("1120");
        Map<String, BigDecimal> je = entry(journalOf(bill));
        assertThat(je.get(mappedCode(b.org(), "withholding_payable"))).isEqualByComparingTo("-60");
        assertThat(je.get(mappedCode(b.org(), "accounts_payable"))).isEqualByComparingTo("-1120");
        assertThat(je.get(mappedCode(b.org(), "tax_receivable"))).isEqualByComparingTo("180");

        JsonNode certs = okAs(b, 200, "GET", "/withholding-certificates?direction=issued", null);
        assertThat(certs.get("data")).hasSize(1);
        JsonNode cert = certs.get("data").get(0);
        assertThat(cert.get("number").asString()).startsWith("WHT-");
        assertThat(dec(cert.get("baseAmount"))).isEqualByComparingTo("1000");
        assertThat(dec(cert.get("rate"))).isEqualByComparingTo("6");
        assertThat(dec(cert.get("amount"))).isEqualByComparingTo("60");
        assertThat(cert.get("partnerId").asString()).isEqualTo(supplier);

        // A supplier payment may not withhold again on a bill that withheld at posting.
        Res twice = moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", day,
                "amount", 1120, "paymentMethod", "bank", "withholdingAmount", 60, "allocations",
                List.of(Map.of("documentId", bill, "amount", 1120))));
        assertThat(twice.status()).as(twice.raw().body()).isEqualTo(400);
        assertThat(twice.body().get("message").asString()).contains("already withheld");
        JsonNode pay = moneyAs(b, "POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", day,
                "amount", 1120, "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", bill,
                        "amount", 1120)))).body();
        assertThat(pay.get("id")).isNotNull();
        JsonNode paid = okAs(b, 200, "GET", "/vendor-bills/" + bill, null);
        assertThat(dec(paid.get("amountResidual"))).isEqualByComparingTo("0");
        assertThat(paid.get("paymentStatus").asString()).isEqualTo("paid");

        JsonNode report = okAs(b, 200, "GET", "/withholding-certificates/report?direction=issued&from=" + day + "&to="
                + day, null);
        assertThat(dec(report.get("amountTotal"))).isEqualByComparingTo("60");
        assertThat(dec(report.get("glDifference"))).as("ties to withholding payable").isEqualByComparingTo("0");
        assertThat(report.get("lines").get(0).get("partnerTaxNumber").asString()).startsWith("TIN");
        // Issued numbers come from the sequence.
        assertThat(as(b, "PATCH", "/withholding-certificates/" + cert.get("id").asString(), Map.of("number", "X-1"))
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[ADR-S071] reverse-charge VAT: input and output VAT of equal amount, nothing payable, in settlement")
    void reverseCharge() {
        Books b = freshBooks();
        String rc = tax(b, Map.of("name", "RC VAT 18", "rate", 18, "type", "vat", "reverseCharge", true,
                "vatCategory", "standard"));
        String supplier = partner(b, true);
        String yesterday = LocalDate.now().minusDays(1).toString();
        String bill = okAs(b, 200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", yesterday,
                "lines", List.of(Map.of("description", "Imported design service", "quantity", 1, "unitPrice", 500,
                        "taxId", rc)))).get("id").asString();
        okAs(b, 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        JsonNode posted = okAs(b, 200, "GET", "/vendor-bills/" + bill, null);
        assertThat(dec(posted.get("totalAmount"))).as("nothing added to the payable").isEqualByComparingTo("500");
        assertThat(dec(posted.get("amountResidual"))).isEqualByComparingTo("500");
        Map<String, BigDecimal> je = entry(journalOf(bill));
        assertThat(je.get(mappedCode(b.org(), "tax_receivable"))).isEqualByComparingTo("90");
        assertThat(je.get(mappedCode(b.org(), "tax_payable"))).isEqualByComparingTo("-90");
        assertThat(je.get(mappedCode(b.org(), "accounts_payable"))).isEqualByComparingTo("-500");

        String from = LocalDate.now().withDayOfYear(1).toString();
        JsonNode preview = okAs(b, 200, "GET", "/tax-settlements/preview?from=" + from + "&to=" + yesterday, null);
        assertThat(dec(preview.get("outputTax"))).isEqualByComparingTo("90");
        assertThat(dec(preview.get("inputTax"))).isEqualByComparingTo("90");
        assertThat(dec(preview.get("netPayable"))).isEqualByComparingTo("0");
        JsonNode summary = okAs(b, 200, "GET", "/reports/tax/summary?from=" + from + "&to=" + yesterday, null);
        assertThat(dec(summary.get("output").get("taxTotal"))).isEqualByComparingTo("90");
        assertThat(dec(summary.get("input").get("taxTotal"))).isEqualByComparingTo("90");
        okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from, "to", yesterday));
    }

    @Test
    @DisplayName("[ADR-S071] customer withholding on a receipt: WHT receivable, invoice settled, certificate, replay")
    void customerWithholding() {
        Books b = freshBooks();
        String customer = partner(b, false);
        String day = LocalDate.now().toString();
        String inv = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", day, "lines",
                List.of(Map.of("description", "Aggregate", "quantity", 1, "unitPrice", 1000)))).get("id").asString();
        okAs(b, 200, "POST", "/invoices/" + inv + "/post", null);
        Map<String, Object> body = Map.of("partnerId", customer, "paymentDate", day, "amount", 1000,
                "paymentMethod", "bank", "withholdingAmount", 60, "withholdingCertificateNumber", "CUST-77",
                "allocations", List.of(Map.of("documentId", inv, "amount", 1000)));
        String key = UUID.randomUUID().toString();
        Res first = money(b, "POST", "/payments", body, key);
        assertThat(first.status()).as(first.raw().body()).isLessThan(300);
        Res replay = money(b, "POST", "/payments", body, key);
        assertThat(replay.body().get("id").asString()).as("idempotent replay").isEqualTo(first.body().get("id")
                .asString());
        String paymentId = first.body().get("id").asString();
        String entryId = jdbc.sql("SELECT \"journalEntryId\" FROM \"Payment\" WHERE id = ?").param(paymentId)
                .query(String.class).single();
        Map<String, BigDecimal> je = entry(entryId);
        assertThat(je.get("1455")).as("withholding receivable").isEqualByComparingTo("60");
        assertThat(je.get(mappedCode(b.org(), "accounts_receivable"))).isEqualByComparingTo("-1000");
        assertThat(mappedCode(b.org(), "withholding_receivable")).isEqualTo("1455");
        assertThat(dec(okAs(b, 200, "GET", "/invoices/" + inv, null).get("amountResidual")))
                .isEqualByComparingTo("0");
        JsonNode certs = okAs(b, 200, "GET", "/withholding-certificates?direction=received", null);
        assertThat(certs.get("data")).as("one certificate despite the replay").hasSize(1);
        JsonNode cert = certs.get("data").get(0);
        assertThat(cert.get("number").asString()).isEqualTo("CUST-77");
        String certId = cert.get("id").asString();
        assertThat(okAs(b, 200, "PATCH", "/withholding-certificates/" + certId, Map.of("number", "CUST-78"))
                .get("number").asString()).isEqualTo("CUST-78");
        JsonNode report = okAs(b, 200, "GET", "/withholding-certificates/report?direction=received&from=" + day
                + "&to=" + day, null);
        assertThat(dec(report.get("amountTotal"))).isEqualByComparingTo("60");
        assertThat(dec(report.get("glDifference"))).isEqualByComparingTo("0");

        // A void cancels the certificate.
        Res voided = moneyAs(b, "POST", "/payments/" + paymentId + "/void", Map.of("reason", "bounced"));
        assertThat(voided.status()).as("void " + voided.raw().body()).isEqualTo(200);
        assertThat(okAs(b, 200, "GET", "/withholding-certificates/" + certId, null).get("status").asString())
                .isEqualTo("cancelled");

        // Tenant isolation: another organization neither lists nor reads it.
        Books other = freshBooks();
        assertThat(okAs(other, 200, "GET", "/withholding-certificates", null).get("data")).isEmpty();
        assertThat(as(other, "GET", "/withholding-certificates/" + certId, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[ADR-S071] withholding certificate routes need withholding permissions")
    void authorization() {
        String weak = weakToken();
        for (String[] r : List.of(new String[] {"GET", "/withholding-certificates"},
                new String[] {"GET", "/withholding-certificates/report?from=2026-01-01&to=2026-01-31"},
                new String[] {"GET", "/withholding-certificates/x"},
                new String[] {"PATCH", "/withholding-certificates/x"})) {
            assertThat(call(r[0], r[1], "PATCH".equals(r[0]) ? Map.of("notes", "n") : null, weak).status())
                    .as(r[0] + " " + r[1]).isEqualTo(403);
        }
    }
}
