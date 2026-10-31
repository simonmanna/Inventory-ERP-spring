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

/** PROC-4: open vendor bills by payment date, the plan and hold on one bill, tenant isolation. */
class SupplierPaymentScheduleIT extends ApiTestSupport {

    String supplier;
    LocalDate today = LocalDate.now();

    @BeforeAll
    void fixtures() {
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(supplier, demoOrg, "S-" + supplier.substring(0, 8), "Schedule Supplier " + supplier).update();
    }

    String postedBill(int price) {
        String id = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today.toString(),
                "dueDate", today.plusDays(3).toString(), "lines",
                List.of(Map.of("description", "Blasting service", "quantity", 1, "unitPrice", price))))
                .get("id").asString();
        ok(200, "POST", "/vendor-bills/" + id + "/post", Map.of());
        return id;
    }

    JsonNode row(JsonNode schedule, String id) {
        for (JsonNode r : schedule.get("rows")) {
            if (id.equals(r.get("documentId").asString())) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a bill without a plan is scheduled on its due date; a plan moves it, a hold takes it out of the "
            + "total")
    void planAndHold() {
        String bill = postedBill(100);
        String path = "/vendor-bills/payment-schedule?partnerId=" + supplier;

        JsonNode before = row(ok(200, "GET", path, null), bill);
        assertThat(before.get("paymentDate").asString()).isEqualTo(today.plusDays(3).toString());
        assertThat(before.get("bucket").asString()).isEqualTo("next_7_days");
        assertThat(new BigDecimal(before.get("scheduledAmount").asString())).isEqualByComparingTo("100");

        ok(200, "PUT", "/vendor-bills/" + bill + "/payment-schedule",
                Map.of("plannedDate", today.plusDays(20).toString(), "plannedAmount", 40));
        JsonNode planned = ok(200, "GET", path, null);
        assertThat(row(planned, bill).get("bucket").asString()).isEqualTo("next_30_days");
        assertThat(new BigDecimal(row(planned, bill).get("scheduledAmount").asString())).isEqualByComparingTo("40");

        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("onHold", true);
        hold.put("notes", "Disputed quantity");
        ok(200, "PUT", "/vendor-bills/" + bill + "/payment-schedule", hold);
        JsonNode held = ok(200, "GET", path, null);
        assertThat(row(held, bill).get("bucket").asString()).isEqualTo("on_hold");
        assertThat(new BigDecimal(held.get("totals").get("onHold").asString()))
                .isGreaterThanOrEqualTo(new BigDecimal("100"));
        assertThat(row(held, bill).get("plannedDate").isNull()).isTrue();
    }

    @Test
    @DisplayName("a plan cannot exceed the outstanding balance or touch a draft bill")
    void validation() {
        String bill = postedBill(50);
        Res over = as("PUT", "/vendor-bills/" + bill + "/payment-schedule", Map.of("plannedAmount", 51));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).isEqualTo("Planned amount cannot exceed the outstanding "
                + "balance");

        String draft = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today.toString(),
                "lines", List.of(Map.of("description", "Draft", "quantity", 1, "unitPrice", 5)))).get("id").asString();
        assertThat(as("PUT", "/vendor-bills/" + draft + "/payment-schedule", Map.of("onHold", true)).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("tenant isolation: another organization neither sees nor plans the bill")
    void tenantIsolation() {
        String bill = postedBill(30);
        Res list = call("GET", "/vendor-bills/payment-schedule", null, otherToken);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("rows")).isEmpty();
        assertThat(call("PUT", "/vendor-bills/" + bill + "/payment-schedule", Map.of("onHold", true),
                otherToken).status())
                .isEqualTo(404);
    }
}
