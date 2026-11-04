package com.inventoryerp.dms;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.dms.application.DmsJobs;
import com.inventoryerp.dms.application.DmsTextExtractor;
import com.inventoryerp.kernel.security.Permissions;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Document management (DM-0..DM-7, ADR-S044/S046): file-vault hardening, workspaces / folders / tags, uploads,
 * versions, record attachments with the record's permission, workspace access rules, search over extracted text,
 * expiry reminders, requests, approvals, legacy surfacing, share links, tenant isolation and the authorization matrix.
 */
class DmsIT extends ApiTestSupport {

    @Autowired
    DmsTextExtractor extractor;

    @Autowired
    DmsJobs jobs;

    String partnerId;

    @BeforeAll
    void fixtures() {
        partnerId = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\" LIMIT 1").param(demoOrg).query(String.class).single();
    }

    record Part(String name, String value, String filename, String contentType) {
        static Part field(String name, String value) {
            return new Part(name, value, null, null);
        }

        static Part file(String filename, String contentType, String content) {
            return new Part("file", content, filename, contentType);
        }
    }

    Res multipart(String path, List<Part> parts, String token, Map<String, String> headers) {
        try {
            String boundary = "----dms" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (Part p : parts) {
                StringBuilder h = new StringBuilder("--").append(boundary).append("\r\nContent-Disposition: form-data;"
                        + " name=\"").append(p.name()).append('"');
                if (p.filename() != null) {
                    h.append("; filename=\"").append(p.filename()).append("\"\r\nContent-Type: ").append(p.contentType());
                }
                h.append("\r\n\r\n");
                body.write(h.toString().getBytes(StandardCharsets.UTF_8));
                body.write(p.value().getBytes(StandardCharsets.UTF_8));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Res multipart(String path, List<Part> parts) {
        return multipart(path, parts, adminToken, Map.of());
    }

    JsonNode uploadOne(List<Part> parts) {
        Res r = multipart("/dms/documents", parts);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get(0);
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "dms-" + role, permissions).update();
        String email = "dms-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Dms@12345", role);
        return login("DEMO", email, "Dms@12345");
    }

    String workspace(String code) {
        ok(200, "GET", "/dms/workspaces", null);
        return jdbc.sql("SELECT id FROM \"DmsWorkspace\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, code).query(String.class).single();
    }

    // ---- DM-0 ----

    @Test
    @DisplayName("DM-0: /files routes need files:* and signed URLs never cross organizations")
    void fileVaultHardening() {
        String weak = weakToken();
        Res denied = multipart("/files/upload", List.of(Part.file("a.txt", "text/plain", "hello")), weak, Map.of());
        assertThat(denied.status()).isEqualTo(403);
        Res up = multipart("/files/upload", List.of(Part.file("a.txt", "text/plain", "hello")));
        assertThat(up.status()).as(up.raw().body()).isEqualTo(201);
        String fileId = up.body().get("id").asString();
        Res foreign = call("POST", "/files/" + fileId + "/signed-url", null, otherToken);
        assertThat(foreign.status()).isEqualTo(404);
        Res exe = multipart("/files/upload", List.of(Part.file("x.exe", "application/octet-stream", "MZ")));
        assertThat(exe.status()).isEqualTo(400);
        // a token minted for the file cannot be replayed on another file id
        String url = up.body().get("url").asString();
        String other = multipart("/files/upload", List.of(Part.file("b.txt", "text/plain", "x"))).body().get("id")
                .asString();
        HttpResponse<String> tampered = get(url.replace(fileId, other));
        assertThat(tampered.statusCode()).isEqualTo(400);
        assertThat(get(url + "&inline=true").headers().firstValue("Content-Disposition").orElse(""))
                .startsWith("inline");
    }

    HttpResponse<String> raw(String path, String password) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("X-Share-Password", password).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> get(String apiUrl) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + apiUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- DM-1 / DM-2 ----

    @Test
    @DisplayName("DM-1 admin: workspace edit / archive guards, folder rename / move / cycle guard, tags and categories")
    void workspaceFolderTagAdmin() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        JsonNode parent = ok(201, "POST", "/dms/workspaces", Map.of("name", "Legal " + sfx));
        String parentId = parent.get("id").asString();
        assertThat(parent.get("code").asString()).isEqualTo("legal_" + sfx);
        JsonNode child = ok(201, "POST", "/dms/workspaces", Map.of("code", "leases_" + sfx, "name", "Leases " + sfx,
                "parentId", parentId, "sortOrder", 5));
        String childId = child.get("id").asString();
        assertThat(as("POST", "/dms/workspaces", Map.of("code", "leases_" + sfx, "name", "Dup")).status())
                .isEqualTo(409);
        assertThat(as("POST", "/dms/workspaces", Map.of("code", "Bad Code!", "name", "Bad")).status()).isEqualTo(400);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", "Leases and rentals " + sfx);
        patch.put("description", "Property leases");
        patch.put("icon", "file");
        patch.put("color", "#336699");
        patch.put("requiresApproval", false);
        patch.put("sortOrder", 7);
        patch.put("parentId", "");
        JsonNode edited = ok(200, "PATCH", "/dms/workspaces/" + childId, patch);
        assertThat(edited.get("name").asString()).isEqualTo("Leases and rentals " + sfx);
        assertThat(edited.get("parentId").isNull()).isTrue();
        assertThat(as("PATCH", "/dms/workspaces/" + childId, Map.of("parentId", childId)).status()).isEqualTo(400);
        assertThat(ok(200, "PATCH", "/dms/workspaces/" + childId, Map.of("parentId", parentId)).get("parentId")
                .asString()).isEqualTo(parentId);

        JsonNode a = ok(201, "POST", "/dms/folders", Map.of("workspaceId", childId, "name", "A"));
        String aId = a.get("id").asString();
        JsonNode b = ok(201, "POST", "/dms/folders", Map.of("workspaceId", childId, "parentId", aId, "name", "B"));
        String bId = b.get("id").asString();
        assertThat(as("POST", "/dms/folders", Map.of("workspaceId", childId, "name", "A")).status()).isEqualTo(409);
        String otherWsFolder = ok(201, "POST", "/dms/folders", Map.of("workspaceId", parentId, "name", "X"))
                .get("id").asString();
        assertThat(as("POST", "/dms/folders", Map.of("workspaceId", childId, "parentId", otherWsFolder, "name", "Y"))
                .status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/dms/workspaces/" + childId + "/folders", null).findValuesAsString("id"))
                .contains(aId, bId);

        assertThat(ok(200, "PATCH", "/dms/folders/" + bId, Map.of("name", "B renamed")).get("name").asString())
                .isEqualTo("B renamed");
        Res cycle = as("PATCH", "/dms/folders/" + aId, Map.of("parentId", bId));
        assertThat(cycle.status()).isEqualTo(400);
        assertThat(cycle.body().get("message").asString()).isEqualTo("A folder cannot be moved into its own subfolder");
        assertThat(as("PATCH", "/dms/folders/" + aId, Map.of("parentId", aId)).status()).isEqualTo(400);
        assertThat(as("PATCH", "/dms/folders/" + bId, Map.of("parentId", otherWsFolder)).status()).isEqualTo(400);
        assertThat(as("PATCH", "/dms/folders/" + bId, Map.of("name", "A", "moveToRoot", true)).status())
                .isEqualTo(409);
        assertThat(ok(200, "PATCH", "/dms/folders/" + bId, Map.of("moveToRoot", true)).get("parentId").isNull())
                .isTrue();
        assertThat(ok(200, "DELETE", "/dms/folders/" + bId, null).get("deleted").asBoolean()).isTrue();
        assertThat(ok(200, "DELETE", "/dms/folders/" + aId, null).get("deleted").asBoolean()).isTrue();

        JsonNode archived = ok(200, "DELETE", "/dms/workspaces/" + childId, null);
        assertThat(archived.get("archivedAt").isNull()).isFalse();
        String general = workspace("general");
        uploadOne(List.of(Part.file("keep.txt", "text/plain", "keep"), Part.field("workspaceId", parentId)));
        assertThat(as("DELETE", "/dms/workspaces/" + parentId, null).status()).isEqualTo(400);
        Res system = as("DELETE", "/dms/workspaces/" + general, null);
        assertThat(system.status()).isEqualTo(400);
        assertThat(system.body().get("message").asString()).isEqualTo("System workspaces cannot be archived");

        JsonNode cat = ok(201, "POST", "/dms/tag-categories", Map.of("name", "Status " + sfx, "sortOrder", 2));
        String catId = cat.get("id").asString();
        assertThat(as("POST", "/dms/tag-categories", Map.of("name", "Status " + sfx)).status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/dms/tag-categories", null).findValuesAsString("id")).contains(catId);
        JsonNode tag = ok(201, "POST", "/dms/tags", Map.of("name", "Signed " + sfx, "categoryId", catId, "color",
                "green"));
        String tagId = tag.get("id").asString();
        ok(201, "POST", "/dms/tags", Map.of("name", "Draft " + sfx));
        assertThat(as("POST", "/dms/tags", Map.of("name", "Signed " + sfx)).status()).isEqualTo(409);
        assertThat(as("POST", "/dms/tags", Map.of("name", "Nope", "categoryId", UUID.randomUUID().toString()))
                .status()).isEqualTo(400);
        assertThat(ok(200, "PATCH", "/dms/tags/" + tagId, Map.of("name", "Executed " + sfx, "color", "blue"))
                .get("name").asString()).isEqualTo("Executed " + sfx);
        assertThat(as("PATCH", "/dms/tags/" + tagId, Map.of("name", "Draft " + sfx)).status()).isEqualTo(409);
        assertThat(as("PATCH", "/dms/tags/" + UUID.randomUUID(), Map.of("name", "x")).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/dms/tags", null).findValuesAsString("id")).contains(tagId);
        ok(200, "DELETE", "/dms/tags/" + tagId, null);
        assertThat(as("DELETE", "/dms/tags/" + tagId, null).status()).isEqualTo(404);
        ok(200, "DELETE", "/dms/tag-categories/" + catId, null);
        assertThat(as("DELETE", "/dms/tag-categories/" + catId, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("DM-1/2: document tags by id, expiry, partner, workspace move, unlink a record, submit without approval")
    void documentEditing() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String ws = workspace("general");
        String target = ok(201, "POST", "/dms/workspaces", Map.of("name", "Archive " + sfx)).get("id").asString();
        String tagId = ok(201, "POST", "/dms/tags", Map.of("name", "Edit " + sfx)).get("id").asString();
        JsonNode doc = uploadOne(List.of(Part.file("edit.txt", "text/plain", "edit me"), Part.field("workspaceId",
                ws)));
        String id = doc.get("id").asString();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("description", "Signed lease");
        patch.put("tagIds", List.of(tagId));
        patch.put("expiresOn", LocalDate.now(ZoneOffset.UTC).plusDays(60).toString());
        patch.put("reminderLeadDays", 14);
        patch.put("partnerId", partnerId);
        patch.put("workspaceId", target);
        JsonNode edited = ok(200, "PATCH", "/dms/documents/" + id, patch);
        assertThat(edited.get("workspaceId").asString()).isEqualTo(target);
        assertThat(edited.get("tags").findValuesAsString("id")).containsExactly(tagId);
        assertThat(edited.get("expiresOn").isNull()).isFalse();
        JsonNode cleared = ok(200, "PATCH", "/dms/documents/" + id, Map.of("clearExpiry", true, "tagIds", List.of(),
                "moveToRoot", true));
        assertThat(cleared.get("expiresOn").isNull()).isTrue();
        assertThat(cleared.get("tags")).isEmpty();

        JsonNode linked = ok(201, "POST", "/dms/documents/" + id + "/links", Map.of("resType", "partner", "resId",
                partnerId));
        String linkId = linked.get("links").get(0).get("id").asString();
        JsonNode unlinked = ok(200, "DELETE", "/dms/documents/" + id + "/links/" + linkId, null);
        assertThat(unlinked.get("links")).isEmpty();
        assertThat(as("DELETE", "/dms/documents/" + id + "/links/" + linkId, null).status()).isEqualTo(404);

        assertThat(ok(200, "POST", "/dms/documents/" + id + "/submit", null).get("status").asString())
                .isEqualTo("pending_approval");
        Res again = as("POST", "/dms/documents/" + id + "/submit", null);
        assertThat(again.status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/dms/documents/resource-types", null)).isNotEmpty();
        assertThat(ok(200, "GET", "/dms/documents?tagId=" + tagId + "&partnerId=" + partnerId + "&q=edit", null)
                .get("data")).isNotNull();
    }

    @Test
    @DisplayName("DM-1: upload, rename, tag, move to folder, version, restore version, lock, archive, delete, restore")
    void documentLifecycle() {
        String ws = workspace("general");
        JsonNode folder = ok(201, "POST", "/dms/folders", Map.of("workspaceId", ws, "name", "Contracts "
                + UUID.randomUUID().toString().substring(0, 6)));
        JsonNode doc = uploadOne(List.of(Part.file("contract.txt", "text/plain", "version one"),
                Part.field("workspaceId", ws), Part.field("tags", "Contract,Legal")));
        String id = doc.get("id").asString();
        assertThat(doc.get("status").asString()).isEqualTo("active");
        assertThat(doc.get("tags")).hasSize(2);
        assertThat(doc.get("currentVersion").asInt()).isEqualTo(1);

        JsonNode moved = ok(200, "PATCH", "/dms/documents/" + id, Map.of("name", "Supply contract.txt", "folderId",
                folder.get("id").asString(), "version", doc.get("version").asInt()));
        assertThat(moved.get("folderId").asString()).isEqualTo(folder.get("id").asString());
        assertThat(moved.get("name").asString()).isEqualTo("Supply contract.txt");
        Res stale = as("PATCH", "/dms/documents/" + id, Map.of("name", "x", "version", 0));
        assertThat(stale.status()).isEqualTo(409);

        Res v2 = multipart("/dms/documents/" + id + "/versions", List.of(Part.file("contract-v2.txt", "text/plain",
                "version two"), Part.field("note", "signed")));
        assertThat(v2.status()).as(v2.raw().body()).isEqualTo(201);
        assertThat(v2.body().get("currentVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().get("versions")).hasSize(2);
        JsonNode restored = ok(200, "POST", "/dms/documents/" + id + "/versions/1/restore", null);
        assertThat(restored.get("currentVersion").asInt()).isEqualTo(3);
        assertThat(restored.get("fileId").asString()).isEqualTo(doc.get("fileId").asString());

        JsonNode signed = ok(201, "POST", "/dms/documents/" + id + "/signed-url", null);
        HttpResponse<String> body = get(signed.get("url").asString());
        assertThat(body.body()).isEqualTo("version one");
        assertThat(signed.get("previewUrl").asString()).contains("inline=true");

        ok(200, "POST", "/dms/documents/" + id + "/lock", null);
        String writer = tokenWith(Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        Res locked = call("PATCH", "/dms/documents/" + id, Map.of("name", "y"), writer);
        assertThat(locked.status()).isEqualTo(409);
        ok(200, "POST", "/dms/documents/" + id + "/unlock", null);

        assertThat(ok(200, "POST", "/dms/documents/" + id + "/archive", null).get("status").asString())
                .isEqualTo("archived");
        ok(200, "POST", "/dms/documents/" + id + "/unarchive", null);
        ok(200, "DELETE", "/dms/documents/" + id, null);
        assertThat(as("GET", "/dms/documents?workspaceId=" + ws, null).body().get("data").findValuesAsString("id"))
                .doesNotContain(id);
        assertThat(ok(200, "GET", "/dms/documents?deleted=true", null).get("data").findValuesAsString("id"))
                .contains(id);
        ok(200, "POST", "/dms/documents/" + id + "/restore", null);
        List<String> actions = ok(200, "GET", "/dms/documents/" + id + "/timeline", null).findValuesAsString("action");
        assertThat(actions).contains("uploaded", "moved", "versioned", "version_restored", "locked", "archived",
                "deleted", "restored");
        Res nonEmpty = as("DELETE", "/dms/folders/" + folder.get("id").asString(), null);
        assertThat(nonEmpty.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("DM-2: record attachments need the record's permission; one file can link to several records")
    void recordAttachments() {
        Res up = multipart("/dms/records/partner/" + partnerId + "/attachments",
                List.of(Part.file("kyc.txt", "text/plain", "kyc"), Part.file("id.txt", "text/plain", "id")));
        assertThat(up.status()).as(up.raw().body()).isEqualTo(201);
        assertThat(up.body()).hasSize(2);
        JsonNode first = up.body().get(0);
        assertThat(first.get("links").get(0).get("resType").asString()).isEqualTo("partner");
        assertThat(first.get("links").get(0).get("webPath").asString()).isEqualTo("/crm/customers/" + partnerId);
        assertThat(ok(200, "GET", "/dms/records/partner/" + partnerId + "/count", null).get("count").asLong())
                .isGreaterThanOrEqualTo(2);

        // DMS rights alone do not reveal a partner's files
        String dmsOnly = tokenWith(Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        assertThat(call("GET", "/dms/records/partner/" + partnerId + "/attachments", null, dmsOnly).status())
                .isEqualTo(403);
        String withPartner = tokenWith(Permissions.DMS__DOCUMENT_READ, Permissions.PARTNER__READ);
        assertThat(call("GET", "/dms/records/partner/" + partnerId + "/attachments", null, withPartner).body())
                .isNotEmpty();

        assertThat(as("GET", "/dms/records/partner/" + UUID.randomUUID() + "/attachments", null).status())
                .isEqualTo(404);
        assertThat(as("GET", "/dms/records/nope/x/attachments", null).status()).isEqualTo(400);

        String product = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        JsonNode linked = ok(201, "POST", "/dms/documents/" + first.get("id").asString() + "/links",
                Map.of("resType", "product", "resId", product));
        assertThat(linked.get("links")).hasSize(2);
        assertThat(as("POST", "/dms/documents/" + first.get("id").asString() + "/links",
                Map.of("resType", "product", "resId", product)).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("DM-1: workspace access rules and required permissions limit who sees a workspace")
    void workspaceAccess() {
        String code = "board_" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode ws = ok(201, "POST", "/dms/workspaces", Map.of("code", code, "name", "Board"));
        String wsId = ws.get("id").asString();
        uploadOne(List.of(Part.file("minutes.txt", "text/plain", "secret minutes"), Part.field("workspaceId", wsId)));
        String reader = tokenWith(Permissions.DMS__DOCUMENT_READ);
        assertThat(call("GET", "/dms/workspaces", null, reader).body().findValuesAsString("code")).contains(code);

        String adminRole = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN"
                + " \"User\" u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        ok(200, "PUT", "/dms/workspaces/" + wsId + "/access", Map.of("rules", List.of(Map.of("roleId", adminRole,
                "level", "manage"))));
        assertThat(call("GET", "/dms/workspaces", null, reader).body().findValuesAsString("code")).doesNotContain(code);
        assertThat(call("GET", "/dms/documents?workspaceId=" + wsId, null, reader).body().get("data")).isEmpty();
        assertThat(as("PUT", "/dms/workspaces/" + wsId + "/access", Map.of("rules", List.of(Map.of("roleId",
                adminRole, "level", "owner")))).status()).isEqualTo(400);

        // the sales workspace needs sales:order:read on top of DMS read
        String sales = workspace("sales");
        assertThat(call("GET", "/dms/workspaces", null, reader).body().findValuesAsString("id")).doesNotContain(sales);
        assertThat(as("DELETE", "/dms/workspaces/" + sales, null).status()).isEqualTo(400);
    }

    // ---- DM-5 ----

    @Test
    @DisplayName("DM-5: extracted text is searchable with a highlighted snippet")
    void fullTextSearch() {
        String word = "zebra" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode doc = uploadOne(List.of(Part.file("memo.txt", "text/plain", "The quarterly memo mentions " + word
                + " twice: " + word + ".")));
        extractor.processPending(500);
        String status = jdbc.sql("SELECT \"textStatus\" FROM \"DmsDocument\" WHERE id = ?")
                .param(doc.get("id").asString()).query(String.class).single();
        assertThat(status).isEqualTo("done");
        JsonNode hits = ok(200, "GET", "/dms/documents?q=" + word, null).get("data");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("snippet").asString()).contains("«" + word + "»");
        assertThat(ok(200, "GET", "/dms/documents?q=memo.txt", null).get("data").findValuesAsString("id"))
                .contains(doc.get("id").asString());
    }

    // ---- DM-6 ----

    @Test
    @DisplayName("DM-6: expiry reminders fire once, expired documents emit dms.document.expired")
    void expiryReminders() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        JsonNode soon = uploadOne(List.of(Part.file("licence.txt", "text/plain", "licence"),
                Part.field("expiresOn", today.plusDays(5).toString()), Part.field("reminderLeadDays", "10")));
        JsonNode late = uploadOne(List.of(Part.file("permit.txt", "text/plain", "permit")));
        jdbc.sql("UPDATE \"DmsDocument\" SET \"expiresOn\" = ? WHERE id = ?")
                .params(java.sql.Date.valueOf(today.minusDays(1)), late.get("id").asString()).update();
        jobs.remindExpiring();
        jobs.remindExpiring();
        long notes = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category = 'dms'"
                + " AND payload->>'documentId' IN (?, ?)").params(demoOrg, soon.get("id").asString(),
                late.get("id").asString()).query(Long.class).single();
        assertThat(notes).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'dms.document.expired' AND"
                + " payload->>'documentId' = ?").param(late.get("id").asString()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(ok(200, "GET", "/dms/documents?expiringBefore=" + today.plusDays(30), null).get("data")
                .findValuesAsString("id")).contains(soon.get("id").asString(), late.get("id").asString());
        // a new version (renewal) re-arms the reminder
        multipart("/dms/documents/" + soon.get("id").asString() + "/versions", List.of(Part.file("licence2.txt",
                "text/plain", "renewed")));
        assertThat(jdbc.sql("SELECT \"expiryNotifiedAt\" IS NULL FROM \"DmsDocument\" WHERE id = ?")
                .param(soon.get("id").asString()).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("DM-6: a document request is fulfilled by the requested user's upload")
    void documentRequests() {
        String me = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        JsonNode req = ok(201, "POST", "/dms/requests", Map.of("title", "Signed NDA", "requestedFromUserId", me,
                "resType", "partner", "resId", partnerId, "dueOn", LocalDate.now(ZoneOffset.UTC).minusDays(1)
                        .toString()));
        assertThat(ok(200, "GET", "/dms/requests", null).findValuesAsString("id")).contains(req.get("id").asString());
        assertThat(jobs.remindOverdueRequests()).isGreaterThanOrEqualTo(1);
        Res done = multipart("/dms/requests/" + req.get("id").asString() + "/fulfil",
                List.of(Part.file("nda.txt", "text/plain", "signed")));
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(done.body().get("status").asString()).isEqualTo("fulfilled");
        String docId = done.body().get("fulfilledDocumentId").asString();
        assertThat(ok(200, "GET", "/dms/records/partner/" + partnerId + "/attachments", null).findValuesAsString("id"))
                .contains(docId);
        assertThat(as("POST", "/dms/requests/" + req.get("id").asString() + "/cancel", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("DM-6: a workspace that requires approval holds uploads until an approver decides")
    void approvals() {
        String code = "qa_" + UUID.randomUUID().toString().substring(0, 6);
        String ws = ok(201, "POST", "/dms/workspaces", Map.of("code", code, "name", "QA", "requiresApproval", true))
                .get("id").asString();
        JsonNode doc = uploadOne(List.of(Part.file("sop.txt", "text/plain", "sop"), Part.field("workspaceId", ws)));
        assertThat(doc.get("status").asString()).isEqualTo("pending_approval");
        String writer = tokenWith(Permissions.DMS__DOCUMENT_READ, Permissions.DMS__DOCUMENT_WRITE);
        assertThat(call("POST", "/dms/documents/" + doc.get("id").asString() + "/approve", Map.of(), writer).status())
                .isEqualTo(403);
        JsonNode approved = ok(200, "POST", "/dms/documents/" + doc.get("id").asString() + "/approve",
                Map.of("comment", "ok"));
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(as("POST", "/dms/documents/" + doc.get("id").asString() + "/reject", Map.of()).status())
                .isEqualTo(400);
    }

    // ---- DM-4 ----

    @Test
    @DisplayName("DM-4: module attachments are surfaced read-only and follow deletes")
    void legacySurfacing() {
        Res up = multipart("/files/upload", List.of(Part.file("statement.txt", "text/plain", "legacy"),
                Part.field("ownerType", "partner"), Part.field("ownerId", partnerId)));
        String fileId = up.body().get("id").asString();
        jobs.indexLegacy();
        jobs.indexLegacy();
        List<String> docs = jdbc.sql("SELECT id FROM \"DmsDocument\" WHERE \"fileId\" = ? AND \"sourceKind\" = 'linked'")
                .param(fileId).query(String.class).list();
        assertThat(docs).hasSize(1);
        JsonNode doc = ok(200, "GET", "/dms/documents/" + docs.getFirst(), null);
        assertThat(doc.get("readOnly").asBoolean()).isTrue();
        assertThat(doc.get("links").get(0).get("resId").asString()).isEqualTo(partnerId);
        assertThat(as("PATCH", "/dms/documents/" + docs.getFirst(), Map.of("name", "x")).status()).isEqualTo(400);
        ok(200, "DELETE", "/files/" + fileId, null);
        jobs.indexLegacy();
        assertThat(jdbc.sql("SELECT \"deletedAt\" IS NOT NULL FROM \"DmsDocument\" WHERE id = ?").param(docs.getFirst())
                .query(Boolean.class).single()).isTrue();
    }

    // ---- DM-7 ----

    @Test
    @DisplayName("DM-7: share links — password, download limit, revoke, folder upload into approval")
    void shareLinks() {
        JsonNode doc = uploadOne(List.of(Part.file("price-list.txt", "text/plain", "prices")));
        String docId = doc.get("id").asString();
        JsonNode link = ok(201, "POST", "/dms/share-links", Map.of("documentIds", List.of(docId), "password",
                "s3cret!!", "maxDownloads", 1, "expiresInDays", 3));
        String token = link.get("token").asString();
        assertThat(link.get("url").asString()).endsWith("/share/" + token);
        assertThat(link.has("tokenHash")).isFalse();
        assertThat(call("GET", "/public/dms/share/" + token, null, (String) null).status()).isEqualTo(403);
        assertThat(call("GET", "/public/dms/share/" + token, null, Map.of("X-Share-Password", "wrong")).status())
                .isEqualTo(403);
        JsonNode view = call("GET", "/public/dms/share/" + token, null, Map.of("X-Share-Password", "s3cret!!")).body();
        assertThat(view.get("documents").findValuesAsString("id")).containsExactly(docId);
        assertThat(view.get("documents").get(0).has("fileId")).isFalse();
        String dl = "/public/dms/share/" + token + "/documents/" + docId + "/download";
        HttpResponse<String> first = raw(dl, "s3cret!!");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("prices");
        assertThat(raw(dl, "s3cret!!").statusCode()).isEqualTo(403);
        ok(200, "POST", "/dms/share-links/" + link.get("id").asString() + "/revoke", null);
        assertThat(call("GET", "/public/dms/share/" + token, null, Map.of("X-Share-Password", "s3cret!!")).status())
                .isEqualTo(404);
        assertThat(call("GET", "/public/dms/share/not-a-real-token-at-all-xx", null, (String) null).status())
                .isEqualTo(404);

        String inbox = workspace("shared_uploads");
        JsonNode drop = ok(201, "POST", "/dms/share-links", Map.of("workspaceId", inbox, "allowUpload", true,
                "allowDownload", false));
        Res uploaded = multipart("/public/dms/share/" + drop.get("token").asString() + "/upload",
                List.of(Part.file("invoice-from-vendor.txt", "text/plain", "bill"), Part.field("uploaderName",
                        "Acme Ltd")), null, Map.of());
        assertThat(uploaded.status()).as(uploaded.raw().body()).isEqualTo(201);
        String sharedDoc = uploaded.body().get(0).get("id").asString();
        assertThat(ok(200, "GET", "/dms/documents/" + sharedDoc, null).get("status").asString())
                .isEqualTo("pending_approval");
        // other documents of the org are never reachable through a link
        assertThat(call("GET", "/public/dms/share/" + drop.get("token").asString() + "/documents/" + docId
                + "/download", null, (String) null).status()).isEqualTo(403);
    }

    // ---- tenancy + matrix ----

    @Test
    @DisplayName("another organization cannot read, list or link DEMO documents")
    void tenantIsolation() {
        JsonNode doc = uploadOne(List.of(Part.file("private.txt", "text/plain", "demo only")));
        String id = doc.get("id").asString();
        assertThat(call("GET", "/dms/documents/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/dms/documents/" + id + "/signed-url", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/dms/documents?q=private", null, otherToken).body().get("data").findValuesAsString("id"))
                .doesNotContain(id);
        assertThat(call("GET", "/dms/records/partner/" + partnerId + "/attachments", null, otherToken).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("every /dms route answers 401 anonymously and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.dms")).isGreaterThan(40);
        assertThat(sweepGetRoutes("com.inventoryerp.dms")).isEmpty();
    }

    @Test
    @DisplayName("bulk move, tag and archive")
    void bulk() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(uploadOne(List.of(Part.file("bulk" + i + ".txt", "text/plain", "b" + i))).get("id").asString());
        }
        String tag = ok(201, "POST", "/dms/tags", Map.of("name", "Bulk " + UUID.randomUUID().toString().substring(0, 6)))
                .get("id").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("action", "tag");
        body.put("tagIds", List.of(tag));
        assertThat(ok(200, "POST", "/dms/documents/bulk", body).get("updated").asInt()).isEqualTo(3);
        assertThat(ok(200, "GET", "/dms/documents?tagId=" + tag, null).get("meta").get("total").asLong()).isEqualTo(3);
        body.put("action", "archive");
        ok(200, "POST", "/dms/documents/bulk", body);
        assertThat(ok(200, "GET", "/dms/documents?status=archived&tagId=" + tag, null).get("meta").get("total")
                .asLong()).isEqualTo(3);
        body.put("action", "explode");
        assertThat(as("POST", "/dms/documents/bulk", body).status()).isEqualTo(400);
    }
}
