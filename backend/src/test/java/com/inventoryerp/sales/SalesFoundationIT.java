package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.sales.application.SalesQuotationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase S0 of ADR-S011: quotations owned by the sales module behind the unchanged {@code /crm/quotations} routes,
 * revision snapshots, the legacy approval entity, write-blocked CRM tables, sales settings (quote policy shown
 * through the CRM settings), commercial master-data columns and the sales permissions.
 */
class SalesFoundationIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String product;

    @BeforeAll
    void fixtures() {
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())")
                .params(customer, demoOrg, "SF-" + sfx, "Foundation Customer " + sfx).update();
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 40,"
                + " 0, false, now())").params(product, demoOrg, "SF-" + sfx, "Aggregate 20mm " + sfx).update();
    }

    JsonNode quote(int qty) {
        return ok(201, "POST", "/crm/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                product, "quantity", qty))));
    }

    @Test
    @DisplayName("CRM route creates the quotation in SalesQuotation; same JSON and numbering")
    void quotationsLiveInSales() {
        JsonNode q = quote(5);
        String id = q.get("id").asString();
        assertThat(q.get("number").asString()).startsWith("QT-");
        assertThat(q.get("lines").get(0).get("lineSubtotal").asDouble()).isEqualTo(200.0);
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesQuotation\" WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"CrmQuotation\" WHERE id = ?").param(id).query(Long.class).single())
                .isZero();
        assertThat(ok(200, "GET", "/crm/quotations/" + id, null).get("partnerId").asString()).isEqualTo(customer);
        JsonNode list = ok(200, "GET", "/crm/quotations?q=" + q.get("number").asString(), null);
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("revise keeps an immutable snapshot of the previous revision")
    void revisionSnapshots() {
        String id = quote(3).get("id").asString();
        ok(200, "POST", "/crm/quotations/" + id + "/submit", null);
        JsonNode revised = ok(200, "POST", "/crm/quotations/" + id + "/revise", null);
        assertThat(revised.get("revision").asInt()).isEqualTo(1);
        assertThat(revised.get("status").asString()).isEqualTo("draft");
        Map<String, Object> snap = jdbc.sql("SELECT revision, status, total, snapshot::text AS s FROM"
                + " \"SalesQuotationRevision\" WHERE \"quotationId\" = ?").param(id).query().singleRow();
        assertThat(((Number) snap.get("revision")).intValue()).isZero();
        assertThat(snap.get("status")).isEqualTo("approved");
        assertThat(snap.get("s").toString()).contains("\"lines\"").contains(product);
    }

    @Test
    @DisplayName("approval engine decisions on the legacy crm_quotation entity still apply")
    void legacyApprovalEntity() {
        assertThat(SalesQuotationService.APPROVAL_ENTITY).isEqualTo("crm_quotation");
        ok(200, "PUT", "/crm/config/settings", Map.of("quoteApprovalAmount", 100));
        try {
            String id = quote(10).get("id").asString();
            JsonNode pending = ok(200, "POST", "/crm/quotations/" + id + "/submit", null);
            assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
            assertThat(ok(200, "POST", "/crm/quotations/" + id + "/approve", Map.of()).get("status").asString())
                    .isEqualTo("approved");
        } finally {
            ok(200, "PUT", "/crm/config/settings", Map.of("quoteApprovalAmount", 0));
        }
    }

    @Test
    @DisplayName("quote policy is stored in SalesSettings and shown unchanged through /crm/config/settings")
    void settingsMoved() {
        JsonNode s = ok(200, "PUT", "/crm/config/settings", Map.of("quoteValidityDays", 45));
        assertThat(s.get("quoteValidityDays").asInt()).isEqualTo(45);
        assertThat(jdbc.sql("SELECT \"quoteValidityDays\" FROM \"SalesSettings\" WHERE \"organizationId\" = ?")
                .param(demoOrg).query(Integer.class).single()).isEqualTo(45);
        assertThat(s.get("rottingDays").asInt()).isPositive();
        ok(200, "PUT", "/crm/config/settings", Map.of("quoteValidityDays", 30));
        Map<String, Object> row = jdbc.sql("SELECT \"physicalMovement\", \"ownershipTransfer\", \"billingPolicy\" FROM"
                + " \"SalesSettings\" WHERE \"organizationId\" = ?").param(demoOrg).query().singleRow();
        assertThat(row).containsEntry("physicalMovement", "direct").containsEntry("ownershipTransfer", "at_dispatch")
                .containsEntry("billingPolicy", "delivered");
    }

    @Test
    @DisplayName("policy combination 'direct + ownership after dispatch' is rejected by the database")
    void invalidPolicyCombination() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"SalesSettings\" SET \"ownershipTransfer\" = 'at_acceptance' WHERE"
                + " \"organizationId\" = ? AND \"physicalMovement\" = 'direct'").param(demoOrg).update())
                .hasMessageContaining("check");
    }

    @Test
    @DisplayName("CRM quotation tables are write-blocked")
    void crmTablesReadOnly() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM \"CrmQuotation\" WHERE id = 'none'").update())
                .hasMessageContaining("read-only since V24");
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"CrmQuotationLine\" SET quantity = 1 WHERE id = 'none'").update())
                .hasMessageContaining("read-only since V24");
    }

    @Test
    @DisplayName("commercial master-data columns, defaults and sales permissions")
    void masterDataAndPermissions() {
        Map<String, Object> p = jdbc.sql("SELECT \"invoicePolicy\", \"billingBasis\" FROM \"Product\" WHERE id = ?")
                .param(product).query().singleRow();
        assertThat(p).containsEntry("invoicePolicy", "delivered").containsEntry("billingBasis", "accepted");
        jdbc.sql("UPDATE \"Partner\" SET \"creditRiskCategory\" = 'medium', \"creditPeriodDays\" = 30 WHERE id = ?")
                .param(customer).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"Partner\" SET \"creditRiskCategory\" = 'weird' WHERE id = ?")
                .param(customer).update()).hasMessageContaining("check");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Permission\" WHERE key LIKE 'sales:%'").query(Long.class).single())
                .isEqualTo(20);
        List<String> admin = jdbc.sql("SELECT unnest(permissions) FROM \"Role\" WHERE \"organizationId\" = ? AND name ="
                + " 'Administrator' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).list();
        assertThat(admin).contains("sales:quotation:approve", "sales:order:confirm", "sales:configure");
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of these quotations")
    void isolation() {
        String id = quote(1).get("id").asString();
        assertThat(call("GET", "/crm/quotations/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/crm/quotations?q=" + sfx, null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
    }
}
