package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmPlannerService;
import com.inventoryerp.crm.application.CrmReminderJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** CRM-2: contacts, activity reminders, saved filters and customer segments, which had no test before. */
class CrmCustomerDataIT extends ApiTestSupport {

    @Autowired
    CrmPlannerService planner;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String customer;
    String adminId;

    @BeforeAll
    void fixtures() {
        customer = ok(201, "POST", "/partners", Map.of("name", "Segment Customer " + sfx, "isCustomer", true)).get("id")
                .asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
    }

    @Test
    @DisplayName("contacts: create, read, update, delete, and validation")
    void contacts() {
        assertThat(as("POST", "/contacts", Map.of("firstName", "Noor")).status()).isEqualTo(400);
        JsonNode created = ok(201, "POST", "/contacts", Map.of("partnerId", customer, "firstName", "Noor", "email",
                "noor@demo.test", "isPrimary", true));
        String id = created.get("id").asString();
        assertThat(ok(200, "GET", "/contacts/" + id, null).get("firstName").asString()).isEqualTo("Noor");
        assertThat(ok(200, "PATCH", "/contacts/" + id, Map.of("phone", "+256700")).get("phone").asString())
                .isEqualTo("+256700");
        assertThat(ok(200, "GET", "/contacts?search=Noor", null).get("data").toString()).contains(id);
        assertThat(call("GET", "/contacts/" + id, null, otherToken).status()).isIn(403, 404);
        assertThat(as("DELETE", "/contacts/" + id, null).status()).isEqualTo(204);
        assertThat(as("GET", "/contacts/" + id, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the reminder job notifies the assignee once, and leaves an activity that is not due alone")
    void reminders() {
        String due = "Call back " + sfx;
        String later = "Later " + sfx;
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        String dueId = ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", due, "partnerId", customer,
                "dueAt", future.toString(), "remindAt", past.toString())).get("id").asString();
        String laterId = ok(201, "POST", "/crm/planner", Map.of("type", "call", "title", later, "partnerId", customer,
                "dueAt", future.toString(), "remindAt", future.toString())).get("id").asString();
        CrmReminderJob job = new CrmReminderJob(jdbc, planner);
        job.tick();
        job.tick();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title = ? AND"
                + " \"userId\" = ?").params(demoOrg, "Reminder: " + due, adminId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT \"reminderSentAt\" IS NOT NULL FROM \"Activity\" WHERE id = ?").param(dueId)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT \"reminderSentAt\" IS NULL FROM \"Activity\" WHERE id = ?").param(laterId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("saved filters: scope is checked, a new default replaces the old, others cannot delete mine")
    void savedFilters() {
        assertThat(as("POST", "/crm/saved-filters", Map.of("scope", "nope", "name", "X")).status()).isEqualTo(400);
        assertThat(as("POST", "/crm/saved-filters", Map.of("scope", "pipeline")).status()).isEqualTo(400);
        JsonNode first = ok(201, "POST", "/crm/saved-filters", Map.of("scope", "pipeline", "name", "A " + sfx, "isDefault",
                true, "filters", Map.of("mine", "1")));
        assertThat(first.get("filters").get("mine").asString()).isEqualTo("1");
        JsonNode second = ok(201, "POST", "/crm/saved-filters", Map.of("scope", "pipeline", "name", "B " + sfx,
                "isDefault", true));
        boolean firstStillDefault = false;
        for (JsonNode f : ok(200, "GET", "/crm/saved-filters?scope=pipeline", null)) {
            if (f.get("id").asString().equals(first.get("id").asString())) {
                firstStillDefault = f.get("isDefault").asBoolean();
            }
        }
        assertThat(firstStillDefault).isFalse();
        assertThat(call("DELETE", "/crm/saved-filters/" + second.get("id").asString(), null, otherToken).status())
                .isIn(403, 404);
        ok(200, "DELETE", "/crm/saved-filters/" + second.get("id").asString(), null);
        assertThat(as("DELETE", "/crm/saved-filters/" + second.get("id").asString(), null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a customer segment is a saved filter: scope customers, shared, and its category filter finds the partner")
    void segments() {
        String category = ok(201, "POST", "/partner-categories", Map.of("name", "Contractors " + sfx)).get("id").asString();
        ok(200, "PATCH", "/partners/" + customer, Map.of("categoryId", category));
        JsonNode segment = ok(201, "POST", "/crm/saved-filters", Map.of("scope", "customers", "name", "Contractors " + sfx,
                "shared", true, "filters", Map.of("categoryId", category)));
        assertThat(segment.get("shared").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/crm/saved-filters?scope=customers", null).toString())
                .contains("Contractors " + sfx);
        JsonNode found = ok(200, "GET", "/crm/customers?categoryId=" + category, null);
        assertThat(found.get("data")).hasSize(1);
        assertThat(found.get("data").get(0).get("id").asString()).isEqualTo(customer);
    }

    @Test
    @DisplayName("product categories: parent, usage counts and delete")
    void productCategories() {
        JsonNode parent = ok(201, "POST", "/product-categories", Map.of("name", "Aggregates " + sfx));
        String parentId = parent.get("id").asString();
        JsonNode child = ok(201, "POST", "/product-categories", Map.of("name", "Sand " + sfx, "parentId", parentId));
        assertThat(ok(200, "GET", "/product-categories/" + child.get("id").asString(), null).get("parent").get("id")
                .asString()).isEqualTo(parentId);
        assertThat(ok(200, "GET", "/product-categories/" + parentId, null).get("_count").get("children").asInt())
                .isEqualTo(1);
        assertThat(ok(200, "GET", "/product-categories?search=Sand%20" + sfx, null).get("data")).hasSize(1);
        assertThat(ok(200, "PATCH", "/product-categories/" + child.get("id").asString(), Map.of("name", "Fine sand " + sfx))
                .get("name").asString()).startsWith("Fine sand");
        assertThat(as("DELETE", "/product-categories/" + child.get("id").asString(), null).status()).isEqualTo(204);
        assertThat(call("GET", "/product-categories/" + parentId, null, otherToken).status()).isIn(403, 404);
    }
}
