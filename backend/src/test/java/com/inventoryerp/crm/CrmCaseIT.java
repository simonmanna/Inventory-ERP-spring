package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmCaseService;
import com.inventoryerp.crm.web.CrmSuiteController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Complaints and service requests (CRM-1): lifecycle, delivery discrepancy link, tenancy and permissions. */
class CrmCaseIT extends ApiTestSupport {

    @Autowired
    CrmCaseService cases;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String discrepancy;

    String id() {
        return UUID.randomUUID().toString();
    }

    @BeforeAll
    void fixtures() {
        customer = id();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "CC-" + sfx, "Case Customer " + sfx)
                .update();
        String product = id();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costPrice\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 1, now())")
                .params(product, demoOrg, "CP-" + sfx, "Aggregate " + sfx).update();
        String location = ok(201, "POST", "/inventory/locations", Map.of("code", "CL-" + sfx, "name", "Yard " + sfx,
                "type", "warehouse")).get("id").asString();
        String order = id();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\") VALUES (?, ?, ?, ?, ?, CURRENT_DATE)")
                .params(order, demoOrg, "DO-" + sfx, customer, location).update();
        String orderLine = id();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrderLine\" (id, \"organizationId\", \"deliveryOrderId\", \"lineNumber\","
                + " \"productId\", \"orderedQty\") VALUES (?, ?, ?, 1, ?, 10)")
                .params(orderLine, demoOrg, order, product).update();
        String load = id();
        jdbc.sql("INSERT INTO \"FleetLoad\" (id, \"organizationId\", code, \"deliveryOrderId\", sequence,"
                + " \"plannedDate\", \"locationId\") VALUES (?, ?, ?, ?, 1, CURRENT_DATE, ?)")
                .params(load, demoOrg, "TR-" + sfx, order, location).update();
        String loadLine = id();
        jdbc.sql("INSERT INTO \"FleetLoadLine\" (id, \"organizationId\", \"loadId\", \"deliveryOrderLineId\","
                + " \"productId\", \"plannedQty\") VALUES (?, ?, ?, ?, ?, 10)")
                .params(loadLine, demoOrg, load, orderLine, product).update();
        discrepancy = id();
        jdbc.sql("INSERT INTO \"FleetDeliveryDiscrepancy\" (id, \"organizationId\", code, \"deliveryOrderId\", \"loadId\","
                + " \"loadLineId\", \"productId\", kind, reason, \"dispatchedQty\", \"deliveredQty\", \"differenceQty\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'shortage', 'spillage', 10, 9.5, 0.5)")
                .params(discrepancy, demoOrg, "DSC-" + sfx, order, load, loadLine, product).update();
    }

    JsonNode open(String priority) {
        return ok(201, "POST", "/crm/cases", Map.of("subject", "Late delivery " + sfx, "partnerId", customer, "priority",
                priority));
    }

    @Test
    @DisplayName("a case gets a code and a due time from its priority; required fields are checked")
    void create() {
        JsonNode c = open("urgent");
        assertThat(c.get("code").asString()).startsWith("CASE-");
        assertThat(c.get("status").asString()).isEqualTo("open");
        assertThat(c.get("dueAt").isNull()).isFalse();
        assertThat(c.get("partnerName").asString()).contains("Case Customer");
        assertThat(c.get("events")).hasSize(1);
        assertThat(as("POST", "/crm/cases", Map.of("partnerId", customer)).status()).isEqualTo(400);
        assertThat(as("POST", "/crm/cases", Map.of("subject", "X", "partnerId", id())).status()).isEqualTo(404);
        assertThat(as("POST", "/crm/cases", Map.of("subject", "X", "partnerId", customer, "priority", "huge")).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("lifecycle: start, resolve with a resolution, reopen, resolve, close; closed is final")
    void lifecycle() {
        String id = open("normal").get("id").asString();
        ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "in_progress"));
        assertThat(as("POST", "/crm/cases/" + id + "/status", Map.of("status", "resolved")).status()).isEqualTo(400);
        JsonNode resolved = ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "resolved", "note",
                "Redelivered"));
        assertThat(resolved.get("resolution").asString()).isEqualTo("Redelivered");
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();
        ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "in_progress"));
        ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "resolved", "note", "Credited"));
        JsonNode closed = ok(200, "POST", "/crm/cases/" + id + "/status", Map.of("status", "closed"));
        assertThat(closed.get("closedAt").isNull()).isFalse();
        assertThat(as("POST", "/crm/cases/" + id + "/status", Map.of("status", "open")).status()).isEqualTo(400);
        assertThat(as("PATCH", "/crm/cases/" + id, Map.of("subject", "New")).status()).isEqualTo(400);
        ok(201, "POST", "/crm/cases/" + id + "/notes", Map.of("body", "Customer thanked us"));
        assertThat(ok(200, "GET", "/crm/cases/" + id, null).get("events").size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("cancelling needs a reason; filters find a case; overdue cases come first")
    void listing() {
        String id = open("low").get("id").asString();
        assertThat(as("POST", "/crm/cases/" + id + "/status", Map.of("status", "cancelled")).status()).isEqualTo(400);
        String late = open("high").get("id").asString();
        jdbc.sql("UPDATE \"CrmCase\" SET \"dueAt\" = now() - interval '2 days' WHERE id = ?").param(late).update();
        JsonNode overdue = ok(200, "GET", "/crm/cases?overdue=true&partnerId=" + customer, null);
        assertThat(overdue.get("data").toString()).contains(late).doesNotContain(id);
        assertThat(overdue.get("data").get(0).get("overdue").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/crm/cases?q=Late%20delivery%20" + sfx + "&pageSize=1", null).get("data")).hasSize(1);
    }

    @Test
    @DisplayName("assigning needs a real user of the organization")
    void assign() {
        String id = open("normal").get("id").asString();
        assertThat(as("POST", "/crm/cases/" + id + "/assign", Map.of("assigneeId", id())).status()).isEqualTo(404);
        String admin = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        assertThat(ok(200, "POST", "/crm/cases/" + id + "/assign", Map.of("assigneeId", admin)).get("assigneeId").asString())
                .isEqualTo(admin);
    }

    @Test
    @DisplayName("a discrepancy opens one live case with its details, and fleet's decision lands on the timeline once")
    void fromDiscrepancy() {
        JsonNode c = ok(201, "POST", "/crm/cases/from-discrepancy/" + discrepancy, null);
        assertThat(c.get("category").asString()).isEqualTo("delivery");
        assertThat(c.get("priority").asString()).isEqualTo("high");
        assertThat(c.get("partnerId").asString()).isEqualTo(customer);
        assertThat(c.get("discrepancyCode").asString()).isEqualTo("DSC-" + sfx);
        assertThat(c.get("deliveryOrderCode").asString()).isEqualTo("DO-" + sfx);
        assertThat(c.get("description").asString()).contains("spillage");
        String again = ok(201, "POST", "/crm/cases/from-discrepancy/" + discrepancy, null).get("id").asString();
        assertThat(again).isEqualTo(c.get("id").asString());
        assertThat(as("POST", "/crm/cases/from-discrepancy/" + id(), null).status()).isEqualTo(404);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> cases.recordDiscrepancyDecision(discrepancy, "DSC-" + sfx, "approved", true));
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> cases.recordDiscrepancyDecision(discrepancy, "DSC-" + sfx, "approved", true));
        JsonNode read = ok(200, "GET", "/crm/cases/" + again, null);
        long notes = 0;
        for (JsonNode e : read.get("events")) {
            if ("discrepancy".equals(e.get("kind").asString())) {
                notes++;
                assertThat(e.get("body").asString()).contains("credit note");
            }
        }
        assertThat(notes).isEqualTo(1);
        assertThat(read.get("status").asString()).isEqualTo("open");
    }

    @Test
    @DisplayName("a case body cannot claim a fleet link")
    void fleetLinksComeFromFleet() {
        JsonNode c = ok(201, "POST", "/crm/cases", Map.of("subject", "Forged " + sfx, "partnerId", customer,
                "discrepancyId", discrepancy));
        assertThat(c.get("discrepancyId").isNull()).isTrue();
    }

    @Test
    @DisplayName("another organization cannot read a case")
    void tenantIsolation() {
        String id = open("normal").get("id").asString();
        assertThat(call("GET", "/crm/cases/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/crm/cases", null, otherToken).raw().body()).doesNotContain(id);
    }

    @Test
    @DisplayName("authorization matrix on all 8 routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(CrmSuiteController.CaseController.class)).isEqualTo(8);
    }
}
