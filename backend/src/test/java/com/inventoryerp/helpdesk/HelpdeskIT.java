package com.inventoryerp.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Helpdesk and correspondence (HD-1, HD-2, ADR-S045): default team and stages, numbering, assignment rules, thread,
 * stage moves with the close permission, incoming letter register with scans in the DMS, outgoing letters rendered to
 * PDF, reply chains, dispatch, register filters, dashboard, tenant isolation and the authorization matrix.
 */
class HelpdeskIT extends ApiTestSupport {

    String partnerId;
    String adminId;
    int year = LocalDate.now(ZoneOffset.UTC).getYear();

    @BeforeAll
    void fixtures() {
        partnerId = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\" LIMIT 1").param(demoOrg).query(String.class).single();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    Res multipart(String path, Map<String, String> fields, String filename, String content) {
        try {
            String boundary = "----hd" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (var f : fields.entrySet()) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                        + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (filename != null) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                        + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1"
                    + path)).header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            return new Res(r.statusCode(), mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "hd-" + role, permissions).update();
        String email = "hd-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Hd@123456", role);
        return login("DEMO", email, "Hd@123456");
    }

    @Test
    @DisplayName("HD-1: ticket lifecycle — number, default stage, thread, stage moves, close needs helpdesk:ticket:close")
    void ticketLifecycle() {
        JsonNode t = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Printer jammed", "priority", "high",
                "channel", "phone", "partnerId", partnerId));
        String id = t.get("id").asString();
        assertThat(t.get("number").asString()).startsWith("HD-" + year + "-");
        assertThat(t.get("stageName").asString()).isEqualTo("New");
        assertThat(t.get("partnerName").isNull()).isFalse();
        assertThat(t.get("stages")).hasSize(5);
        assertThat(t.get("letterNumber").isNull()).isTrue();

        JsonNode noted = ok(201, "POST", "/helpdesk/tickets/" + id + "/messages", Map.of("kind", "note", "body",
                "Checked toner"));
        assertThat(noted.get("messages").findValuesAsString("kind")).contains("system", "note");
        ok(201, "POST", "/helpdesk/tickets/" + id + "/messages", Map.of("kind", "reply", "body", "On our way"));
        assertThat(jdbc.sql("SELECT \"firstResponseAt\" IS NOT NULL FROM \"HdTicket\" WHERE id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        assertThat(as("POST", "/helpdesk/tickets/" + id + "/messages", Map.of("kind", "shout", "body", "x")).status())
                .isEqualTo(400);

        String closedStage = null;
        String progress = null;
        for (JsonNode s : t.get("stages")) {
            if (s.get("isClosed").asBoolean() && closedStage == null) {
                closedStage = s.get("id").asString();
            }
            if ("In progress".equals(s.get("name").asString())) {
                progress = s.get("id").asString();
            }
        }
        String agent = tokenWith(Permissions.HELPDESK__TICKET_READ, Permissions.HELPDESK__TICKET_WRITE);
        assertThat(call("POST", "/helpdesk/tickets/" + id + "/stage", Map.of("stageId", progress), agent).status())
                .isEqualTo(200);
        Res denied = call("POST", "/helpdesk/tickets/" + id + "/stage", Map.of("stageId", closedStage), agent);
        assertThat(denied.status()).isEqualTo(403);
        JsonNode closed = ok(200, "POST", "/helpdesk/tickets/" + id + "/close", Map.of("resolution", "Replaced roller"));
        assertThat(closed.get("stageClosed").asBoolean()).isTrue();
        assertThat(closed.get("closedAt").isNull()).isFalse();
        JsonNode reopened = ok(200, "POST", "/helpdesk/tickets/" + id + "/reopen", null);
        assertThat(reopened.get("closedAt").isNull()).isTrue();

        assertThat(ok(200, "GET", "/helpdesk/tickets?q=Printer%20jammed&state=open", null).get("data")
                .findValuesAsString("id")).contains(id);
        assertThat(as("POST", "/helpdesk/tickets", Map.of("subject", "x", "priority", "asap")).status()).isEqualTo(400);
        assertThat(as("POST", "/helpdesk/tickets", Map.of("priority", "low")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("HD-1: round-robin team assigns members in turn and notifies them")
    void roundRobin() {
        String a = createUser(demoOrg, "rr-a-" + UUID.randomUUID().toString().substring(0, 6) + "@demo.test", "Rr@123456",
                null);
        String b = createUser(demoOrg, "rr-b-" + UUID.randomUUID().toString().substring(0, 6) + "@demo.test", "Rr@123456",
                null);
        JsonNode team = ok(201, "POST", "/helpdesk/teams", Map.of("name", "Field " + UUID.randomUUID().toString()
                .substring(0, 6), "assignment", "round_robin", "memberIds", List.of(a, b)));
        String teamId = team.get("id").asString();
        String first = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "One", "teamId", teamId))
                .get("assigneeId").asString();
        String second = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Two", "teamId", teamId))
                .get("assigneeId").asString();
        String third = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Three", "teamId", teamId))
                .get("assigneeId").asString();
        assertThat(List.of(first, second)).containsExactlyInAnyOrder(a, b);
        assertThat(third).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" IN (?, ?) AND category ="
                + " 'helpdesk'").params(a, b).query(Long.class).single()).isEqualTo(3);
        assertThat(as("POST", "/helpdesk/teams", Map.of("name", "Bad", "assignment", "random")).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("HD-2: incoming letter → IN number, scan in Correspondence/Incoming/<year>, reply letter → OUT PDF")
    void correspondence() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subject", "Notice of price revision");
        fields.put("partnerId", partnerId);
        fields.put("externalRef", "ACME/2026/77");
        fields.put("letterDate", LocalDate.now(ZoneOffset.UTC).minusDays(2).toString());
        fields.put("replyDueOn", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        fields.put("confidentiality", "confidential");
        Res in = multipart("/helpdesk/letters/incoming", fields, "scan.txt", "scanned letter text");
        assertThat(in.status()).as(in.raw().body()).isEqualTo(201);
        JsonNode letter = in.body();
        String inId = letter.get("id").asString();
        assertThat(letter.get("letterNumber").asString()).startsWith("IN-" + year + "-");
        assertThat(letter.get("channel").asString()).isEqualTo("letter");
        assertThat(letter.get("receivedAt").isNull()).isFalse();
        assertThat(letter.get("attachments")).hasSize(1);
        String scanDoc = letter.get("attachments").get(0).get("id").asString();
        String folderName = jdbc.sql("SELECT f.name FROM \"DmsDocument\" d JOIN \"DmsFolder\" f ON f.id = d.\"folderId\""
                + " WHERE d.id = ?").param(scanDoc).query(String.class).single();
        assertThat(folderName).isEqualTo(String.valueOf(year));
        assertThat(ok(200, "GET", "/helpdesk/letters?direction=inbound&awaitingReply=true", null).get("data")
                .findValuesAsString("id")).contains(inId);
        assertThat(ok(200, "GET", "/helpdesk/tickets/" + inId, null).get("replyOverdue").asBoolean()).isTrue();

        String ack = ok(200, "GET", "/helpdesk/letter-templates", null).findValues("code").stream()
                .filter(n -> n.asString().equals("acknowledgement")).count() == 1 ? templateId("acknowledgement") : null;
        assertThat(ack).isNotNull();
        JsonNode reply = ok(201, "POST", "/helpdesk/tickets/" + inId + "/reply-letter", Map.of("letter",
                Map.of("templateId", ack, "signatureName", "Head of Sales")));
        assertThat(reply.get("letterNumber").asString()).startsWith("OUT-" + year + "-");
        assertThat(reply.get("direction").asString()).isEqualTo("outbound");
        assertThat(reply.get("parentTicketId").asString()).isEqualTo(inId);
        assertThat(reply.get("externalRef").asString()).isEqualTo(letter.get("letterNumber").asString());
        assertThat(reply.get("attachments").get(0).get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(reply.get("description").asString()).contains("ACME/2026/77");
        JsonNode original = ok(200, "GET", "/helpdesk/tickets/" + inId, null);
        assertThat(original.get("repliedByTicketId").asString()).isEqualTo(reply.get("id").asString());
        assertThat(original.get("children").findValuesAsString("id")).contains(reply.get("id").asString());
        assertThat(ok(200, "GET", "/helpdesk/letters?direction=inbound&awaitingReply=true", null).get("data")
                .findValuesAsString("id")).doesNotContain(inId);

        String replyId = reply.get("id").asString();
        assertThat(as("POST", "/helpdesk/tickets/" + replyId + "/dispatch", Map.of("method", "pigeon")).status())
                .isEqualTo(400);
        JsonNode sent = ok(200, "POST", "/helpdesk/tickets/" + replyId + "/dispatch", Map.of("method",
                "registered_post", "trackingNumber", "RR123456789"));
        assertThat(sent.get("dispatchMethod").asString()).isEqualTo("registered_post");
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(as("POST", "/helpdesk/tickets/" + inId + "/dispatch", Map.of("method", "post")).status())
                .isEqualTo(400);
        String clerk = tokenWith(Permissions.HELPDESK__TICKET_READ, Permissions.HELPDESK__TICKET_WRITE);
        assertThat(call("POST", "/helpdesk/tickets/" + replyId + "/dispatch", Map.of("method", "post"), clerk).status())
                .isEqualTo(403);

        assertThat(ok(200, "GET", "/helpdesk/letters?direction=outbound&year=" + year, null).get("data")
                .findValuesAsString("id")).contains(replyId);
        JsonNode dash = ok(200, "GET", "/helpdesk/dashboard", null);
        assertThat(dash.get("open").asLong()).isGreaterThan(0);
        // the ticket's files are visible through the DMS record panel
        assertThat(ok(200, "GET", "/dms/records/helpdesk_ticket/" + replyId + "/attachments", null)).hasSize(1);
    }

    String templateId(String code) {
        return jdbc.sql("SELECT id FROM \"HdLetterTemplate\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, code).query(String.class).single();
    }

    @Test
    @DisplayName("HD-2: an outgoing letter from scratch renders a PDF with the merged body")
    void outgoingLetter() {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("partnerId", partnerId);
        ticket.put("subject", "Statement of account");
        JsonNode out = ok(201, "POST", "/helpdesk/letters/outgoing", Map.of("ticket", ticket, "letter",
                Map.of("body", "Dear {{contact.name}},\n\nPlease find your statement. Ref {{letter.number}}.")));
        assertThat(out.get("letterNumber").asString()).startsWith("OUT-");
        assertThat(out.get("description").asString()).contains(out.get("letterNumber").asString());
        assertThat(out.get("attachments")).hasSize(1);
        assertThat(as("POST", "/helpdesk/letters/outgoing", Map.of("ticket", Map.of("partnerId", partnerId),
                "letter", Map.of("body", "x"))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("HD-1 config: team edit, stages add / rename / delete guards, ticket types, letter templates")
    void configuration() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        JsonNode team = ok(201, "POST", "/helpdesk/teams", Map.of("code", "Ops " + sfx, "name", "Ops " + sfx,
                "leaderId", adminId, "email", "ops@demo.test"));
        String teamId = team.get("id").asString();
        assertThat(team.get("code").asString()).isEqualTo("ops_" + sfx);
        assertThat(as("POST", "/helpdesk/teams", Map.of("code", "Ops " + sfx, "name", "Again")).status())
                .isEqualTo(409);
        assertThat(as("POST", "/helpdesk/teams", Map.of("name", "Ghost", "memberIds",
                List.of(UUID.randomUUID().toString()))).status()).isEqualTo(400);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", "Operations " + sfx);
        patch.put("description", "Field operations");
        patch.put("assignment", "balanced");
        patch.put("leaderId", adminId);
        patch.put("memberIds", List.of(adminId));
        patch.put("isActive", true);
        JsonNode updated = ok(200, "PATCH", "/helpdesk/teams/" + teamId, patch);
        assertThat(updated.get("name").asString()).isEqualTo("Operations " + sfx);
        assertThat(updated.get("assignment").asString()).isEqualTo("balanced");
        assertThat(as("PATCH", "/helpdesk/teams/" + teamId, Map.of("assignment", "random")).status()).isEqualTo(400);
        assertThat(as("PATCH", "/helpdesk/teams/" + UUID.randomUUID(), Map.of("name", "x")).status()).isEqualTo(404);
        assertThat(ok(200, "GET", "/helpdesk/teams/" + teamId, null).get("id").asString()).isEqualTo(teamId);
        assertThat(ok(200, "GET", "/helpdesk/teams", null).findValuesAsString("id")).contains(teamId);

        JsonNode stage = ok(201, "POST", "/helpdesk/teams/" + teamId + "/stages", Map.of("name", "Waiting on parts",
                "sequence", 25));
        String stageId = stage.get("id").asString();
        JsonNode renamed = ok(200, "PATCH", "/helpdesk/stages/" + stageId, Map.of("name", "Awaiting parts",
                "folded", true));
        assertThat(renamed.get("name").asString()).isEqualTo("Awaiting parts");
        assertThat(renamed.get("folded").asBoolean()).isTrue();
        String ticket = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Uses stage", "teamId", teamId))
                .get("id").asString();
        ok(200, "POST", "/helpdesk/tickets/" + ticket + "/stage", Map.of("stageId", stageId));
        Res inUse = as("DELETE", "/helpdesk/stages/" + stageId, null);
        assertThat(inUse.status()).isEqualTo(400);
        assertThat(inUse.body().get("message").asString()).isEqualTo("Move the 1 ticket(s) of this stage first");
        String other = jdbc.sql("SELECT id FROM \"HdStage\" WHERE \"teamId\" = ? AND id <> ? AND NOT \"isClosed\""
                + " ORDER BY sequence LIMIT 1").params(teamId, stageId).query(String.class).single();
        ok(200, "POST", "/helpdesk/tickets/" + ticket + "/stage", Map.of("stageId", other));
        ok(200, "DELETE", "/helpdesk/tickets/" + ticket, null);
        assertThat(as("GET", "/helpdesk/tickets/" + ticket, null).status()).isEqualTo(404);
        assertThat(ok(200, "DELETE", "/helpdesk/stages/" + stageId, null).get("deleted").asBoolean()).isTrue();
        assertThat(as("DELETE", "/helpdesk/stages/" + stageId, null).status()).isEqualTo(404);
        assertThat(as("PATCH", "/helpdesk/stages/" + UUID.randomUUID(), Map.of("name", "x")).status()).isEqualTo(404);

        JsonNode type = ok(201, "POST", "/helpdesk/types", Map.of("name", "Warranty " + sfx));
        String typeId = type.get("id").asString();
        assertThat(as("POST", "/helpdesk/types", Map.of("name", "Warranty " + sfx)).status()).isEqualTo(409);
        assertThat(ok(200, "PATCH", "/helpdesk/types/" + typeId, Map.of("isActive", false)).get("isActive")
                .asBoolean()).isFalse();
        assertThat(as("PATCH", "/helpdesk/types/" + UUID.randomUUID(), Map.of("isActive", true)).status())
                .isEqualTo(404);
        assertThat(ok(200, "GET", "/helpdesk/types", null).findValuesAsString("id")).contains(typeId);

        Map<String, Object> tpl = new LinkedHashMap<>();
        tpl.put("name", "Overdue notice " + sfx);
        tpl.put("subject", "Overdue balance");
        tpl.put("body", "Dear {{contact.name}}, your balance is overdue.");
        tpl.put("closingLine", "Yours faithfully");
        tpl.put("signatureName", "Credit Control");
        tpl.put("signatureTitle", "Manager");
        JsonNode created = ok(201, "POST", "/helpdesk/letter-templates", tpl);
        String tplId = created.get("id").asString();
        assertThat(created.get("code").asString()).isEqualTo("overdue_notice_" + sfx);
        assertThat(as("POST", "/helpdesk/letter-templates", tpl).status()).isEqualTo(409);
        JsonNode edited = ok(200, "PATCH", "/helpdesk/letter-templates/" + tplId, Map.of("subject", "Final notice",
                "isActive", false));
        assertThat(edited.get("subject").asString()).isEqualTo("Final notice");
        assertThat(edited.get("isActive").asBoolean()).isFalse();
        assertThat(as("PATCH", "/helpdesk/letter-templates/" + UUID.randomUUID(), Map.of("name", "x")).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("HD-1 tickets: edit with optimistic version, team move, assign / unassign, list filters")
    void ticketEditing() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String teamId = ok(201, "POST", "/helpdesk/teams", Map.of("name", "Edit " + sfx)).get("id").asString();
        JsonNode t = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Edit me " + sfx, "channel", "email",
                "priority", "low", "partnerId", partnerId));
        String id = t.get("id").asString();
        int version = t.get("version").asInt();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("priority", "urgent");
        patch.put("contactName", "Jane Doe");
        patch.put("contactEmail", "jane@example.com");
        patch.put("contactPhone", "+256700000000");
        patch.put("description", "Updated description");
        patch.put("tags", List.of("vip", "onsite"));
        patch.put("externalRef", "EXT-" + sfx);
        patch.put("ourRef", "OUR-" + sfx);
        patch.put("relatedResType", "partner");
        patch.put("relatedResId", partnerId);
        patch.put("teamId", teamId);
        patch.put("assigneeId", adminId);
        patch.put("version", version);
        JsonNode updated = ok(200, "PATCH", "/helpdesk/tickets/" + id, patch);
        assertThat(updated.get("priority").asString()).isEqualTo("urgent");
        assertThat(updated.get("teamId").asString()).isEqualTo(teamId);
        assertThat(updated.get("assigneeId").asString()).isEqualTo(adminId);
        assertThat(updated.get("messages").findValuesAsString("body")).anyMatch(b -> b.startsWith("Moved to team"));
        Res stale = as("PATCH", "/helpdesk/tickets/" + id, Map.of("priority", "low", "version", version));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asString())
                .isEqualTo("Ticket was changed by someone else; reload and try again");
        assertThat(as("PATCH", "/helpdesk/tickets/" + id, Map.of("subject", " ")).status()).isEqualTo(400);

        String q = "/helpdesk/tickets?";
        assertThat(ok(200, "GET", q + "teamId=" + teamId, null).get("data").findValuesAsString("id")).contains(id);
        assertThat(ok(200, "GET", q + "stageId=" + updated.get("stageId").asString(), null).get("data")
                .findValuesAsString("id")).contains(id);
        assertThat(ok(200, "GET", q + "assigneeId=" + adminId + "&priority=urgent&channel=email", null).get("data")
                .findValuesAsString("id")).contains(id);
        assertThat(ok(200, "GET", q + "mine=true&state=open", null).get("data").findValuesAsString("id"))
                .contains(id);
        assertThat(ok(200, "GET", q + "partnerId=" + partnerId + "&relatedResType=partner&relatedResId=" + partnerId,
                null).get("data").findValuesAsString("id")).contains(id);
        assertThat(ok(200, "GET", q + "direction=inbound&state=closed", null).get("data").findValuesAsString("id"))
                .doesNotContain(id);
        assertThat(as("GET", q + "state=pending", null).status()).isEqualTo(400);

        JsonNode unassigned = ok(200, "POST", "/helpdesk/tickets/" + id + "/assign", Map.of());
        assertThat(unassigned.get("assigneeId").isNull()).isTrue();
        assertThat(ok(200, "GET", q + "unassigned=true&teamId=" + teamId, null).get("data")
                .findValuesAsString("id")).contains(id);
        assertThat(ok(200, "POST", "/helpdesk/tickets/" + id + "/assign", Map.of()).get("assigneeId").isNull())
                .isTrue();
        assertThat(as("POST", "/helpdesk/tickets/" + id + "/assign", Map.of("assigneeId",
                UUID.randomUUID().toString())).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/helpdesk/tickets/" + id + "/assign", Map.of("assigneeId", adminId))
                .get("assigneeId").asString()).isEqualTo(adminId);
    }

    @Test
    @DisplayName("tickets are invisible to another organization")
    void tenantIsolation() {
        String id = ok(201, "POST", "/helpdesk/tickets", Map.of("subject", "Private")).get("id").asString();
        assertThat(call("GET", "/helpdesk/tickets/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/helpdesk/tickets", null, otherToken).body().get("data").findValuesAsString("id"))
                .doesNotContain(id);
        assertThat(call("POST", "/helpdesk/tickets/" + id + "/messages", Map.of("kind", "note", "body", "x"),
                otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("every /helpdesk route answers 401 anonymously and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.helpdesk")).isGreaterThan(25);
        assertThat(sweepGetRoutes("com.inventoryerp.helpdesk")).isEmpty();
    }
}
