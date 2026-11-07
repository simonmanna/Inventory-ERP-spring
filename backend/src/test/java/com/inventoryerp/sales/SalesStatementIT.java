package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** SG-10: closing balance equals AR; aging reconciles or shows the difference; filters, log, confirmation, disputes. */
class SalesStatementIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "G10-" + sfx, "name", "SG10 " + sfx, "productType", "service", "salesPrice", 100)).get("id").asString();
    }

    String customer(String email) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, true, now())")
                .params(id, demoOrg, "G10-" + UUID.randomUUID(), "SG10 " + UUID.randomUUID(), email).update();
        return id;
    }

    String invoice(String partner, int amount) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(), "lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", amount))))
                .get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    @Test
    @DisplayName("closing balance equals the AR statement and reconciles to open documents, credits included")
    void reconciles() {
        String c = customer("a@x.test");
        invoice(c, 300);
        invoice(c, 200);
        JsonNode s = ok(200, "GET", "/sales/statements/" + c, null);
        assertThat(s.get("closingBalance").asDouble()).isEqualTo(500);
        assertThat(s.get("closingBalance").asDouble()).isEqualTo(ok(200, "GET", "/reports/ar/customers/" + c + "/statement", null).get("closingBalance").asDouble());
        assertThat(s.get("reconciliation").get("reconciled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a receivable with no open document shows as a difference instead of hiding")
    void showsDifference() {
        String c = customer("b@x.test");
        String inv = invoice(c, 300);
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = 100 WHERE id = ?").param(inv).update();
        JsonNode r = ok(200, "GET", "/sales/statements/" + c, null).get("reconciliation");
        assertThat(r.get("reconciled").asBoolean()).isFalse();
        assertThat(r.get("difference").asDouble()).isEqualTo(200);
        assertThat(r.get("note").asString()).isNotBlank();
    }

    @Test
    @DisplayName("filtering by type narrows the lines and says the statement is filtered")
    void filters() {
        String c = customer("c@x.test");
        invoice(c, 100);
        JsonNode s = ok(200, "GET", "/sales/statements/" + c + "?type=credit", null);
        assertThat(s.get("filtered").asBoolean()).isTrue();
        assertThat(s.get("lines").size()).isZero();
        assertThat(ok(200, "GET", "/sales/statements/" + c + "?type=invoice", null).get("lines").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("sending logs every attempt; bulk continues past a customer with no email; confirmation and disputes are recorded")
    void logConfirmDispute() {
        String good = customer("d@x.test");
        String none = customer(null);
        invoice(good, 100);
        invoice(none, 100);
        JsonNode bulk = ok(200, "POST", "/sales/statements/send-bulk", Map.of("partnerIds", List.of(good, none)));
        assertThat(bulk.get("total").asInt()).isEqualTo(2);
        assertThat(bulk.get("failed").asInt()).isEqualTo(1);
        JsonNode s = ok(200, "GET", "/sales/statements/" + good, null);
        String log = s.get("log").get(0).get("id").asString();
        assertThat(as("POST", "/sales/statements/log/" + log + "/confirm", Map.of()).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/statements/log/" + log + "/confirm", Map.of("confirmedBy", "Accounts clerk")).get("confirmedBy").asString())
                .isEqualTo("Accounts clerk");
        String d = ok(201, "POST", "/sales/statements/" + good + "/disputes", Map.of("reason", "Invoice not ours", "amount", 100)).get("id").asString();
        assertThat(ok(200, "POST", "/sales/statements/disputes/" + d + "/resolve", Map.of("status", "resolved", "resolution", "Credit issued")).get("status").asString())
                .isEqualTo("resolved");
        assertThat(ok(200, "GET", "/sales/statements/outstanding", null).toString()).contains(good);
    }

    @Test
    @DisplayName("statement routes need their permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.sales.web.SalesStatementController.class)).isGreaterThan(6);
    }
}
