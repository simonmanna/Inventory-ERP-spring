package com.inventoryerp.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * DMS on Testcontainers Postgres: registry seeding + type admin, lifecycle engine (idempotency, guards, version
 * lock, reversal counterpart + CANCELS relation, snapshots), generic patch, preview / print / reprint, templates,
 * relations, sources, attachments, timeline, Express route-shadowing parity, authorization and tenant isolation.
 */
class DocumentsIT extends ApiTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    com.inventoryerp.documents.application.DmsRegistrySeeder dmsSeeder;

    String partner;

    @BeforeAll
    void fixtures() {
        // DEMO may have been seeded by a suite that skipped module contributors: make sure the system roles hold
        // the per-type DMS keys (idempotent).
        new org.springframework.transaction.support.TransactionTemplate(txm)
                .executeWithoutResult(st -> dmsSeeder.seed(demoOrg));
        partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, 'DMS Customer', true, now())")
                .params(partner, demoOrg, "DMS-" + UUID.randomUUID().toString().substring(0, 6)).update();
    }

    String invoice() {
        return ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("description", "Consulting", "quantity", 2, "unitPrice", 25)))).get("id")
                .asString();
    }

    Res action(String id, String action, Map<String, Object> body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call("POST", "/documents/" + id + "/actions/" + action, body == null ? Map.of() : body, h);
    }

    @Test
    @DisplayName("registry: seeded lifecycles / types / templates; type admin; Nest route shadowing kept")
    void registry() {
        JsonNode types = ok(200, "GET", "/documents/types", null);
        assertThat(types.size()).isGreaterThanOrEqualTo(17);
        assertThat(ok(200, "GET", "/documents/types/sales_invoice", null).get("lifecycleId").isNull()).isFalse();
        JsonNode lifecycles = ok(200, "GET", "/documents/lifecycles", null);
        assertThat(lifecycles.size()).isGreaterThanOrEqualTo(6);
        assertThat(ok(200, "GET", "/documents/lifecycles/sales_doc", null).get("transitions").size()).isEqualTo(9);
        Res cats = as("GET", "/documents/types/categories", null);
        assertThat(cats.status()).isEqualTo(404);
        assertThat(cats.body().get("message").asString()).isEqualTo("Unknown document type code: categories");
        Res tpl = as("GET", "/documents/templates", null);
        assertThat(tpl.status()).isEqualTo(404);
        assertThat(tpl.body().get("message").asString()).isEqualTo("Document not found");

        String code = "custom_" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode created = ok(201, "POST", "/documents/types", Map.of("code", code, "name", "Custom"));
        assertThat(created.get("category").asString()).isEqualTo("custom");
        assertThat(ok(200, "PATCH", "/documents/types/" + code, Map.of("name", "Renamed")).get("name").asString())
                .isEqualTo("Renamed");
        assertThat(ok(200, "DELETE", "/documents/types/" + code, null).get("isActive").asBoolean()).isFalse();
        Res system = as("DELETE", "/documents/types/sales_invoice", null);
        assertThat(system.body().get("message").asString()).isEqualTo("System document types cannot be deleted");
        long templates = jdbc.sql("SELECT count(*) FROM \"TemplateDefinition\" WHERE \"templateKey\" = 'sales_invoice:a4'")
                .query(Long.class).single();
        assertThat(templates).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("engine: key required, illegal action, submit → approve → post with snapshot, replay, version lock")
    void lifecycle() {
        String id = invoice();
        assertThat(action(id, "submit", null, null).body().get("message").asString())
                .isEqualTo("Idempotency-Key header is required for lifecycle actions");
        Res illegal = action(id, "post", null, UUID.randomUUID().toString());
        assertThat(illegal.status()).isEqualTo(400);
        assertThat(illegal.body().get("message").asString()).isEqualTo("Action 'post' is not allowed from status 'draft'");
        Res cancel = action(id, "cancel", Map.of(), UUID.randomUUID().toString());
        assertThat(cancel.status()).isEqualTo(422);
        assertThat(cancel.body().get("message").asString()).isEqualTo("A reason is required for this action");

        String key = UUID.randomUUID().toString();
        Res submit = action(id, "submit", null, key);
        assertThat(submit.status()).as(submit.raw().body()).isEqualTo(201);
        assertThat(submit.body().get("toStatus").asString()).isEqualTo("submitted");
        assertThat(submit.body().get("version").asInt()).isEqualTo(2);
        assertThat(submit.body().get("replayed").asBoolean()).isFalse();
        Res replay = action(id, "submit", null, key);
        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        assertThat(replay.body().get("at").asString()).isEqualTo(submit.body().get("at").asString());
        action(id, "approve", null, UUID.randomUUID().toString());
        Res post = action(id, "post", null, UUID.randomUUID().toString());
        assertThat(post.body().get("toStatus").asString()).isEqualTo("posted");
        assertThat(post.body().get("snapshotId").isNull()).isFalse();
        String seal = jdbc.sql("SELECT seal FROM \"DocumentSnapshot\" WHERE id = ?").param(post.body().get("snapshotId")
                .asString()).query(String.class).single();
        assertThat(seal).hasSize(64);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'document.posted' AND"
                + " payload->>'documentId' = ?").param(id).query(Long.class).single()).isEqualTo(1);
        Res reverse = action(id, "reverse", Map.of("reason", "wrong"), UUID.randomUUID().toString());
        assertThat(reverse.body().get("message").asString())
                .isEqualTo("Document has no posted effects — use cancel instead of reverse");

        JsonNode timeline = ok(200, "GET", "/documents/" + id + "/timeline", null);
        assertThat(timeline.get("documentTypeCode").asString()).isEqualTo("sales_invoice");
        assertThat(timeline.get("events").get(0).get("kind").asString()).isIn("action", "snapshot");
        assertThat(timeline.get("version").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("reverse a GL-posted invoice: credit-note counterpart, CANCELS relation, one-shot")
    void reversal() {
        String id = invoice();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        Res rev = action(id, "reverse", Map.of("reason", "customer returned"), UUID.randomUUID().toString());
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(201);
        String cp = rev.body().get("counterpartId").asString();
        Map<String, Object> counterpart = jdbc.sql("SELECT \"documentType\"::text AS type, \"totalAmount\","
                + " \"reversedDocumentId\", status::text AS status FROM \"Document\" WHERE id = ?").param(cp).query()
                .singleRow();
        assertThat(counterpart.get("type")).isEqualTo("credit_note");
        assertThat(counterpart.get("reversedDocumentId")).isEqualTo(id);
        assertThat(counterpart.get("status")).isEqualTo("posted");
        assertThat(new java.math.BigDecimal(counterpart.get("totalAmount").toString())).isEqualByComparingTo("-50");
        JsonNode rels = ok(200, "GET", "/documents/" + id + "/relations", null);
        assertThat(rels.get(0).get("relationType").asString()).isEqualTo("CANCELS");
        Res again = action(id, "reverse", Map.of("reason", "again"), UUID.randomUUID().toString());
        assertThat(again.body().get("message").asString()).isEqualTo("Document has already been reversed");
    }

    @Test
    @DisplayName("patch, detail, preview, print / reprint, templates, relations, sources, attachments")
    void surface() {
        String id = invoice();
        Res noVersion = as("PATCH", "/documents/" + id, Map.of("fields", Map.of("notes", "x")));
        assertThat(noVersion.body().get("message").asString()).isEqualTo("version is required for optimistic locking");
        Res badField = as("PATCH", "/documents/" + id, Map.of("version", 1, "fields", Map.of("totalAmount", 1)));
        assertThat(badField.body().get("message").asString()).isEqualTo("Field 'totalAmount' is not patchable");
        JsonNode patched = ok(200, "PATCH", "/documents/" + id, Map.of("version", 1, "fields", Map.of("notes", "hi")));
        assertThat(patched.get("version").asInt()).isEqualTo(2);
        Res stale = as("PATCH", "/documents/" + id, Map.of("version", 1, "fields", Map.of("notes", "again")));
        assertThat(stale.status()).isEqualTo(409);

        JsonNode detail = ok(200, "GET", "/documents/" + id, null);
        assertThat(detail.get("editPolicy").asString()).isEqualTo("IMMUTABLE_AFTER_ISSUE");
        assertThat(detail.get("lines")).hasSize(1);
        JsonNode list = ok(200, "GET", "/documents?type=sales_invoice&pageSize=5", null);
        assertThat(list.get("pageSize").asInt()).isEqualTo(5);
        assertThat(list.get("items").get(0).get("documentTypeCode").asString()).isEqualTo("sales_invoice");
        assertThat(as("GET", "/documents?type=nope", null).body().get("message").asString())
                .isEqualTo("Unknown document type 'nope'");

        JsonNode html = ok(200, "GET", "/documents/" + id + "/preview", null);
        assertThat(html.get("contentType").asString()).isEqualTo("text/html");
        // No html template is seeded (a4 / thermal only), so the built-in fallback renders.
        assertThat(html.get("content").asString()).contains("Sales Invoice").contains("INV-");
        assertThat(html.get("templateKey").asString()).isEqualTo("sales_invoice:a4:fallback");
        assertThat(html.get("renderedFrom").asString()).isEqualTo("live");
        assertThat(as("GET", "/documents/" + id + "/preview?format=pdfx", null).body().get("message").asString())
                .isEqualTo("Unsupported format 'pdfx' (a4|thermal|html|plain|email)");

        Map<String, String> h = new LinkedHashMap<>(Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        Res printed = call("POST", "/documents/" + id + "/print", Map.of("format", "a4"), h);
        assertThat(printed.status()).as(printed.raw().body()).isEqualTo(201);
        assertThat(printed.body().get("action").asString()).isEqualTo("PRINT");
        assertThat(printed.body().get("pdf").asBoolean()).isTrue();
        assertThat(printed.body().get("base64").asString()).isNotEmpty();
        Res replay = call("POST", "/documents/" + id + "/print", Map.of("format", "a4"), h);
        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/documents/" + id + "/print", Map.of("format", "thermal"), h).body().get("message")
                .asString()).isEqualTo("A reprint requires a reason");

        JsonNode tpl = ok(201, "POST", "/documents/templates", Map.of("typeCode", "sales_invoice", "format", "html",
                "content", "<b>{{documentNumber}}</b>"));
        assertThat(tpl.get("isActive").asBoolean()).isFalse();
        ok(201, "POST", "/documents/templates/" + tpl.get("id").asString() + "/activate", null);
        assertThat(ok(200, "GET", "/documents/" + id + "/preview?format=html", null).get("content").asString())
                .startsWith("<b>INV-");

        String other = invoice();
        JsonNode rel = ok(201, "POST", "/documents/" + id + "/relations", Map.of("toDocumentId", other,
                "relationType", "REFERENCES"));
        assertThat(rel.get("id").isNull()).isFalse();
        assertThat(ok(200, "GET", "/documents/" + other + "/relations", null).get(0).get("direction").asString())
                .isEqualTo("in");
        assertThat(ok(200, "GET", "/documents/" + id + "/relations/chain?type=references", null).get("chain").size())
                .isGreaterThanOrEqualTo(1);
        assertThat(as("GET", "/documents/" + id + "/relations/chain", null).body().get("message").asString())
                .isEqualTo("?type= is required (e.g. FULFILLS)");
        assertThat(ok(200, "DELETE", "/documents/" + id + "/relations/" + rel.get("id").asString(), null)
                .get("deleted").asBoolean()).isTrue();

        JsonNode src = ok(201, "POST", "/documents/" + id + "/sources", Map.of("sourceType", "PurchaseOrder",
                "sourceId", "po-1"));
        assertThat(ok(200, "GET", "/documents/" + id + "/sources", null).get(0).get("relationship").asString())
                .isEqualTo("primary");
        ok(200, "DELETE", "/documents/" + id + "/sources/" + src.get("id").asString(), null);
        assertThat(as("DELETE", "/documents/" + id + "/sources/" + src.get("id").asString(), null).body().get("message")
                .asString()).isEqualTo("Source link not found");
        assertThat(as("POST", "/documents/" + id + "/attachments", Map.of()).body().get("message").asString())
                .isEqualTo("multipart field \"file\" is required");
        assertThat(ok(200, "GET", "/documents/" + id + "/attachments", null)).isEmpty();
    }

    @Test
    @DisplayName("tenant isolation and authorization matrix (28 routes)")
    void isolationAndAuthz() {
        String id = invoice();
        assertThat(call("GET", "/documents/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(assertAuthorizationMatrix("com.inventoryerp.documents")).isEqualTo(30);
    }
}
