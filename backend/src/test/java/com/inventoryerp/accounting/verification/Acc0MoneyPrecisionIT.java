package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ACC-0 reproduction: floating-point money (finding B12). The payroll {@code double} path is reproduced in
 * {@code com.inventoryerp.hr.application.Acc0PayrollPrecisionTest} (package-private method).
 */
class Acc0MoneyPrecisionIT extends AccountingFixtures {

    @Test
    @DisplayName("[B12][owner:accounting][ACC-3] a bill linked to three POs splits its total exactly (Σ links = total)")
    void vendorBillLinkSplitIsExact() {
        String supplier = demoPartner("ACC0 split supplier", true);
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"PurchaseOrder\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, ?, now())").params(id, demoOrg, "A0-PO-" + id.substring(0, 8), supplier).update();
            pos.add(id);
        }
        Res bill = as("POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "purchaseOrderIds",
                pos, "lines", List.of(Map.of("description", "Split", "quantity", 1, "unitPrice", 100))));
        assertThat(bill.status()).as(bill.raw().body()).isEqualTo(200);
        List<BigDecimal> shares = jdbc.sql("SELECT amount FROM \"VendorBillLink\" WHERE \"vendorBillId\" = ? ORDER BY"
                + " amount").param(bill.body().get("id").asString()).query(BigDecimal.class).list();
        assertThat(shares).hasSize(3);
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).as("Σ link amounts " + shares)
                .isEqualByComparingTo("100");
        assertThat(shares).as("currency-precision shares").allSatisfy(s ->
                assertThat(s.stripTrailingZeros().scale()).isLessThanOrEqualTo(2));
    }
}
