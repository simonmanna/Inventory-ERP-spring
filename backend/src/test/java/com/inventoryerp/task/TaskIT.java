package com.inventoryerp.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tasks on Testcontainers Postgres (ENABLE_TASKS on in the test profile): CRUD + activity log, reorder, checklist
 * progress, comments, verification, dashboard, labels / templates / auto-rules, query validation, tenant isolation
 * and the 401 matrix (Nest declares no route permissions).
 */
class TaskIT extends ApiTestSupport {

    JsonNode task(Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", "Clean espresso machine"));
        body.putAll(extra);
        return ok(201, "POST", "/tasks", body);
    }

    @Test
    @DisplayName("create defaults, includes and the created activity; update logs one entry per changed field")
    void crud() {
        JsonNode t = task(Map.of("priority", "HIGH", "dueDate", "2026-12-01T09:00:00.000Z"));
        assertThat(t.get("status").asString()).isEqualTo("PENDING");
        assertThat(t.get("taskType").asString()).isEqualTo("ONE_TIME");
        assertThat(t.get("checklistItems")).isEmpty();
        assertThat(t.get("labels")).isEmpty();
        assertThat(t.get("assignedTo").isNull()).isTrue();
        String id = t.get("id").asString();
        JsonNode updated = ok(200, "PATCH", "/tasks/" + id, Map.of("priority", "CRITICAL", "title",
                "Clean espresso machine"));
        assertThat(updated.get("priority").asString()).isEqualTo("CRITICAL");
        JsonNode one = ok(200, "GET", "/tasks/" + id, null);
        JsonNode log = one.get("activityLog");
        assertThat(log).hasSize(2);
        assertThat(log.get(0).get("action").asString()).isEqualTo("updated");
        assertThat(log.get(0).get("field").asString()).isEqualTo("priority");
        assertThat(log.get(0).get("oldValue").asString()).isEqualTo("HIGH");
        assertThat(log.get(0).get("newValue").asString()).isEqualTo("CRITICAL");
        assertThat(log.get(1).get("description").asString()).isEqualTo("Task created");
        assertThat(one.get("comments")).isEmpty();
        assertThat(one.get("children")).isEmpty();

        JsonNode moved = ok(200, "PATCH", "/tasks/" + id + "/reorder", Map.of("status", "IN_PROGRESS"));
        assertThat(moved.get("status").asString()).isEqualTo("IN_PROGRESS");
        JsonNode removed = ok(200, "DELETE", "/tasks/" + id, null);
        assertThat(removed.get("deletedAt").isNull()).isFalse();
        Res gone = as("GET", "/tasks/" + id, null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("Task not found");
    }

    @Test
    @DisplayName("checklist toggle recomputes progress; comments; verification")
    void checklistCommentsVerify() {
        JsonNode t = task(Map.of("requiresVerification", true));
        String id = t.get("id").asString();
        String item1 = UUID.randomUUID().toString();
        String item2 = UUID.randomUUID().toString();
        for (String item : List.of(item1, item2)) {
            jdbc.sql("INSERT INTO \"TaskChecklistItem\" (id, \"organizationId\", \"taskId\", description, \"updatedAt\")"
                    + " VALUES (?, ?, ?, 'step', now())").params(item, demoOrg, id).update();
        }
        JsonNode toggled = ok(200, "PATCH", "/tasks/" + id + "/checklist/" + item1, Map.of("isCompleted", true));
        assertThat(toggled.get("checklistProgress").asInt()).isEqualTo(50);
        assertThat(toggled.get("checklistTotal").asInt()).isEqualTo(2);

        Res badParent = as("POST", "/tasks/" + id + "/comments", Map.of("content", "hi", "parentId", "nope"));
        assertThat(badParent.status()).isEqualTo(400);
        JsonNode c = ok(201, "POST", "/tasks/" + id + "/comments", Map.of("content", "Done the first step",
                "mentions", List.of("barista")));
        assertThat(c.get("mentions").get(0).asString()).isEqualTo("barista");
        assertThat(c.get("replies")).isEmpty();
        ok(201, "POST", "/tasks/" + id + "/comments", Map.of("content", "reply", "parentId", c.get("id").asString()));
        JsonNode one = ok(200, "GET", "/tasks/" + id, null);
        assertThat(one.get("comments")).hasSize(2);
        assertThat(one.get("comments").get(0).get("replies")).hasSize(1);

        JsonNode verified = ok(201, "POST", "/tasks/" + id + "/verify", Map.of("verificationMethod", "PHOTO"));
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(verified.get("verifiedBy").get("id").isNull()).isFalse();
        String plain = task(Map.of()).get("id").asString();
        Res refused = as("POST", "/tasks/" + plain + "/verify", Map.of("verificationMethod", "PHOTO"));
        assertThat(refused.body().get("message").asString()).isEqualTo("This task does not require verification");
    }

    @Test
    @DisplayName("list envelope and query validation; dashboard; labels, templates, auto-rules")
    void listsAndConfig() {
        task(Map.of("title", "Restock milk " + UUID.randomUUID().toString().substring(0, 4)));
        JsonNode list = ok(200, "GET", "/tasks?search=restock&limit=5", null);
        assertThat(list.get("limit").asInt()).isEqualTo(5);
        assertThat(list.get("items").size()).isGreaterThan(0);
        Res bad = as("GET", "/tasks?page=0&foo=1", null);
        assertThat(bad.status()).isEqualTo(400);
        JsonNode dash = ok(200, "GET", "/tasks/dashboard", null);
        assertThat(dash.get("todayTotal").asInt()).isGreaterThan(0);
        assertThat(dash.has("topEmployees")).isTrue();

        JsonNode label = ok(201, "POST", "/tasks/labels", Map.of("name", "Kitchen"));
        assertThat(label.get("color").asString()).isEqualTo("#6366f1");
        assertThat(ok(200, "GET", "/tasks/labels", null).toString()).contains(label.get("id").asString());
        JsonNode tpl = ok(201, "POST", "/tasks/templates", Map.of("title", "Open shop", "recurrenceType", "DAILY",
                "checklistTemplate", List.of(Map.of("description", "Lights on"))));
        assertThat(tpl.get("taskType").asString()).isEqualTo("RECURRING");
        assertThat(ok(200, "GET", "/tasks/templates", null).toString()).contains(tpl.get("id").asString());
        JsonNode rule = ok(201, "POST", "/tasks/auto-rules", Map.of("name", "Low stock", "triggerEvent",
                "stock.low", "taskTitle", "Reorder", "taskType", "INVENTORY", "triggerConfig", Map.of("min", 5)));
        assertThat(rule.get("isActive").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/tasks/auto-rules", null).toString()).contains(rule.get("id").asString());
    }

    @Test
    @DisplayName("tenant isolation and 401 matrix (16 routes)")
    void isolationAndAuth() {
        String id = task(Map.of()).get("id").asString();
        assertThat(call("GET", "/tasks/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix("com.inventoryerp.task")).isEqualTo(16);
    }
}
