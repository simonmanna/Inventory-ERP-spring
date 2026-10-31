package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.web.CrmCommissionController;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SALES-4: earn on a posted invoice, stay idempotent, approve, pay through payroll, cancel with the invoice. */
class CrmCommissionIT extends ApiTestSupport {

    String seller;
    String customer;
    String service;
    String today = LocalDate.now().toString();
    String rule;

    @BeforeAll
    void fixtures() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        seller = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"lastName\","
                + " \"hireDate\", \"isActive\", \"updatedAt\") VALUES (?, ?, ?, 'Sally', 'Seller', now(), true, now())")
                .params(seller, demoOrg, "SP-" + s).update();
        customer = ok(201, "POST", "/partners", Map.of("code", "CMC-" + s, "name", "Comm cust " + s, "isCustomer", true))
                .get("id").asString();
        service = ok(201, "POST", "/products", Map.of("code", "CMS-" + s, "name", "Comm svc " + s, "productType",
                "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        rule = ok(200, "POST", "/commissions/rules", Map.of("name", "Sally " + s, "employeeId", seller, "ratePercent",
                5)).get("id").asString();
    }

    String postedInvoice(int qty) {
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "salespersonId",
                seller, "lines", List.of(Map.of("productId", service, "quantity", qty, "unitPrice", 100))));
        String id = inv.get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        return id;
    }

    long entries(String invoice, String status) {
        return jdbc.sql("SELECT count(*) FROM \"CommissionEntry\" WHERE \"documentId\" = ? AND status = ?")
                .params(invoice, status).query(Long.class).single();
    }

    @Test
    @DisplayName("calculate earns once, approve then export creates one payroll earning")
    void lifecycle() {
        String invoice = postedInvoice(4);
        ok(200, "POST", "/commissions/calculate", Map.of("from", today, "to", today));
        ok(200, "POST", "/commissions/calculate", Map.of("from", today, "to", today));
        assertThat(entries(invoice, "accrued")).isEqualTo(1);
        String entry = jdbc.sql("SELECT id FROM \"CommissionEntry\" WHERE \"documentId\" = ?").param(invoice)
                .query(String.class).single();
        assertThat(jdbc.sql("SELECT amount FROM \"CommissionEntry\" WHERE id = ?").param(entry)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("20");
        ok(200, "POST", "/commissions/approve", Map.of("ids", List.of(entry)));
        assertThat(as("POST", "/commissions/approve", Map.of("ids", List.of(entry))).status()).isEqualTo(409);
        ok(200, "POST", "/commissions/export-payroll", Map.of("to", today));
        assertThat(entries(invoice, "exported")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"HrPayrollInput\" WHERE \"employeeId\" = ? AND source ="
                + " 'sales_commission'").param(seller).query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("a cancelled invoice cancels its unpaid commission")
    void cancelled() {
        String invoice = postedInvoice(1);
        ok(200, "POST", "/commissions/calculate", Map.of("from", today, "to", today));
        assertThat(entries(invoice, "accrued")).isEqualTo(1);
        ok(200, "POST", "/invoices/" + invoice + "/cancel", null);
        ok(200, "POST", "/commissions/calculate", Map.of("from", today, "to", today));
        assertThat(entries(invoice, "cancelled")).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix")
    void guards() {
        assertThat(call("PUT", "/commissions/rules/" + rule, Map.of("name", "x", "ratePercent", 1), otherToken).status())
                .isEqualTo(404);
        assertThat(assertAuthorizationMatrix(CrmCommissionController.class)).isEqualTo(9);
    }
}
