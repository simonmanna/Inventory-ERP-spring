package com.inventoryerp.communication;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.communication.web.DiscussController;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Discuss (ADR-S064) on Testcontainers Postgres: #general, channels, DMs and group chats, membership and moderation,
 * messages (mentions, replies, reactions, pins, stars, edit, delete, attachments, paging), the inbox and in-app
 * notifications, settings and badges, archive, search, record chatter with followers and notes, department channels
 * synced from HR, tenant isolation and the authorization matrix.
 */
class DiscussIT extends ApiTestSupport {

    String staffRole;
    String adminId;
    String bobId;
    String bobToken;
    String carolId;
    String carolToken;

    @BeforeAll
    void users() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test' AND \"organizationId\" = ?")
                .param(demoOrg).query(String.class).single();
        staffRole = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(staffRole, demoOrg, "Staff-" + staffRole, new String[] {"communication:conversation:read",
                        "communication:conversation:write", "communication:message:send"})
                .update();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        bobId = createUser(demoOrg, "bob-" + suffix + "@demo.test", "Bob@12345", staffRole);
        carolId = createUser(demoOrg, "carol-" + suffix + "@demo.test", "Carol@12345", staffRole);
        jdbc.sql("UPDATE \"User\" SET \"firstName\" = 'Bob', \"lastName\" = 'Baker' WHERE id = ?").param(bobId).update();
        jdbc.sql("UPDATE \"User\" SET \"firstName\" = 'Carol', \"lastName\" = 'Cook' WHERE id = ?").param(carolId)
                .update();
        bobToken = login("DEMO", "bob-" + suffix + "@demo.test", "Bob@12345");
        carolToken = login("DEMO", "carol-" + suffix + "@demo.test", "Carol@12345");
    }

    private Res asUser(String token, String method, String path, Object body) {
        return call(method, path, body, token);
    }

    private JsonNode okAs(String token, int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        if (status == 201 && path.endsWith("/messages")) {
            // @Idempotent routes answer 200 (the Nest interceptor overwrites the route status).
            assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(200, 201);
        } else {
            assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        }
        return r.body();
    }

    @Override
    protected JsonNode ok(int status, String method, String path, Object body) {
        return okAs(adminToken, status, method, path, body);
    }

    private String channel(String name, String visibility, List<String> members) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("visibility", visibility);
        body.put("memberIds", members);
        return ok(201, "POST", "/communication/discuss/channels", body).get("id").asString();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    @DisplayName("#general is created once and every user joins it on first use, but is not re-added after leaving")
    void general() {
        JsonNode sidebar = ok(200, "GET", "/communication/discuss/sidebar", null);
        assertThat(sidebar.get("me").get("id").asString()).isEqualTo(adminId);
        String general = null;
        for (JsonNode c : sidebar.get("channels")) {
            if (c.get("autoJoin").asBoolean()) {
                general = c.get("id").asString();
            }
        }
        assertThat(general).isNotNull();
        JsonNode bobs = okAs(bobToken, 200, "GET", "/communication/discuss/sidebar", null);
        assertThat(bobs.get("channels").toString()).contains(general);
        okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + general + "/leave", null);
        assertThat(okAs(bobToken, 200, "GET", "/communication/discuss/sidebar", null).get("channels").toString())
                .doesNotContain(general);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Conversation\" WHERE \"organizationId\" = ? AND \"autoJoin\""
                + " AND \"deletedAt\" IS NULL").param(demoOrg).query(Long.class).single()).isEqualTo(1);
        okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + general + "/join", null);
    }

    @Test
    @DisplayName("channels: create, unique name, browse, join, mention → inbox + notification, moderation, archive")
    void channels() {
        String name = unique("ops");
        String id = channel(name, "public", List.of());
        Res dup = as("POST", "/communication/discuss/channels", Map.of("name", "#" + name.toUpperCase()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("message").asString()).isEqualTo("A channel named '" + name.toUpperCase()
                + "' already exists.");

        assertThat(okAs(bobToken, 200, "GET", "/communication/discuss/channels", null).toString()).contains(id);
        JsonNode joined = okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + id + "/join", null);
        assertThat(joined.get("members").toString()).contains(bobId, adminId);
        assertThat(joined.get("canManage").asBoolean()).isFalse();

        JsonNode msg = okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + id + "/messages",
                Map.of("body", "@T please check the dock", "mentionUserIds", List.of(adminId)));
        assertThat(msg.get("author").get("name").asString()).isEqualTo("Bob Baker");
        assertThat(msg.get("mentions").toString()).contains(adminId);
        String msgId = msg.get("id").asString();

        JsonNode inbox = ok(200, "GET", "/communication/discuss/inbox?unreadOnly=true", null);
        assertThat(inbox.get("items").toString()).contains(msgId).contains("\"reason\":\"mention\"");
        assertThat(ok(200, "GET", "/communication/discuss/counters", null).get("inbox").asLong()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND category = 'discuss'"
                + " AND payload->>'messageId' = ?").params(adminId, msgId).query(Long.class).single()).isEqualTo(1);
        // Reading the conversation clears the mention.
        ok(201, "POST", "/communication/discuss/conversations/" + id + "/read", Map.of());
        assertThat(ok(200, "GET", "/communication/discuss/inbox?unreadOnly=true", null).get("items").toString())
                .doesNotContain(msgId);

        // Moderation: Bob (member) cannot rename or remove; the owner can.
        Res rename = asUser(bobToken, "PATCH", "/communication/discuss/conversations/" + id, Map.of("name", "x"));
        assertThat(rename.status()).isEqualTo(403);
        assertThat(rename.body().get("message").asString()).isEqualTo("Only a channel moderator can do this.");
        JsonNode renamed = ok(200, "PATCH", "/communication/discuss/conversations/" + id,
                Map.of("description", "Dock and yard", "visibility", "private"));
        assertThat(renamed.get("description").asString()).isEqualTo("Dock and yard");
        assertThat(renamed.get("visibility").asString()).isEqualTo("private");
        // Carol cannot see the now-private channel.
        assertThat(asUser(carolToken, "GET", "/communication/discuss/conversations/" + id, null).status())
                .isEqualTo(403);
        assertThat(okAs(carolToken, 200, "GET", "/communication/discuss/channels", null).toString()).doesNotContain(id);

        // Bob adds Carol (members may invite); the owner removes her again.
        okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + id + "/members",
                Map.of("userIds", List.of(carolId)));
        okAs(carolToken, 200, "GET", "/communication/discuss/conversations/" + id, null);
        assertThat(asUser(bobToken, "DELETE", "/communication/discuss/conversations/" + id + "/members/" + carolId,
                null).status()).isEqualTo(403);
        ok(200, "DELETE", "/communication/discuss/conversations/" + id + "/members/" + carolId, null);
        assertThat(asUser(carolToken, "GET", "/communication/discuss/conversations/" + id, null).status())
                .isEqualTo(403);
        Res unknown = as("POST", "/communication/discuss/conversations/" + id + "/members",
                Map.of("userIds", List.of("nobody")));
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unknown user(s): nobody");

        // Archive: read-only for Discuss and for the Nest send route; hidden from the sidebar.
        ok(201, "POST", "/communication/discuss/conversations/" + id + "/archive", null);
        Res archived = asUser(bobToken, "POST", "/communication/discuss/conversations/" + id + "/messages",
                Map.of("body", "hello?"));
        assertThat(archived.status()).isEqualTo(400);
        assertThat(archived.body().get("message").asString()).isEqualTo("This conversation is archived.");
        Res nest = as("POST", "/communication/conversations/" + id + "/messages", Map.of("body", "hello?"));
        assertThat(nest.body().get("message").asString()).isEqualTo("This conversation is archived.");
        assertThat(okAs(bobToken, 200, "GET", "/communication/discuss/sidebar", null).toString()).doesNotContain(id);
        assertThat(ok(200, "GET", "/communication/discuss/channels?archived=true", null).toString()).contains(id);
        ok(201, "POST", "/communication/discuss/conversations/" + id + "/unarchive", null);
    }

    @Test
    @DisplayName("direct messages are unique per pair, private, cannot be left; group chats; offline DM notifies")
    void chats() {
        String dm = okAs(bobToken, 201, "POST", "/communication/discuss/chats", Map.of("userIds", List.of(carolId)))
                .get("id").asString();
        JsonNode again = okAs(carolToken, 201, "POST", "/communication/discuss/chats",
                Map.of("userIds", List.of(bobId)));
        assertThat(again.get("id").asString()).isEqualTo(dm);
        assertThat(again.get("kind").asString()).isEqualTo("direct");
        assertThat(again.get("name").asString()).isEqualTo("Bob Baker");
        assertThat(again.get("directUserId").asString()).isEqualTo(bobId);

        // Admin holds read_all but a DM is private.
        assertThat(as("GET", "/communication/discuss/conversations/" + dm + "/messages", null).status())
                .isEqualTo(403);
        Res leave = asUser(bobToken, "POST", "/communication/discuss/conversations/" + dm + "/leave", null);
        assertThat(leave.body().get("message").asString()).isEqualTo("You cannot leave a direct conversation.");
        Res add = asUser(bobToken, "POST", "/communication/discuss/conversations/" + dm + "/members",
                Map.of("userIds", List.of(adminId)));
        assertThat(add.body().get("message").asString()).isEqualTo("Members cannot be added to a direct conversation.");

        String msgId = okAs(bobToken, 201, "POST", "/communication/discuss/conversations/" + dm + "/messages",
                Map.of("body", "Lunch?")).get("id").asString();
        // Carol has no open stream: she gets an in-app notification.
        assertThat(jdbc.sql("SELECT title FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'messageId' = ?")
                .params(carolId, msgId).query(String.class).single()).isEqualTo("New message from Bob Baker");
        JsonNode carolSide = okAs(carolToken, 200, "GET", "/communication/discuss/sidebar", null);
        JsonNode item = null;
        for (JsonNode c : carolSide.get("chats")) {
            if (c.get("id").asString().equals(dm)) {
                item = c;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("unread").asLong()).isEqualTo(1);
        assertThat(item.get("badge").asLong()).isEqualTo(1);
        assertThat(item.get("lastMessage").get("authorName").asString()).isEqualTo("Bob Baker");

        // Mute → no badge; notify level "mentions" → badge counts mentions only.
        JsonNode muted = okAs(carolToken, 200, "PATCH", "/communication/discuss/conversations/" + dm + "/settings",
                Map.of("muteMinutes", 60, "pinned", true));
        assertThat(muted.get("muted").asBoolean()).isTrue();
        assertThat(muted.get("badge").asLong()).isZero();
        assertThat(muted.get("pinned").asBoolean()).isTrue();
        JsonNode mentionsOnly = okAs(carolToken, 200, "PATCH",
                "/communication/discuss/conversations/" + dm + "/settings",
                Map.of("muteMinutes", 0, "notifyLevel", "mentions"));
        assertThat(mentionsOnly.get("muted").asBoolean()).isFalse();
        assertThat(mentionsOnly.get("badge").asLong()).isZero();
        okAs(carolToken, 201, "POST", "/communication/discuss/conversations/" + dm + "/read", Map.of());
        JsonNode detail = okAs(bobToken, 200, "GET", "/communication/discuss/conversations/" + dm, null);
        assertThat(detail.get("members").toString()).contains("\"lastReadSeq\"");

        JsonNode group = okAs(bobToken, 201, "POST", "/communication/discuss/chats",
                Map.of("userIds", List.of(carolId, adminId)));
        assertThat(group.get("kind").asString()).isEqualTo("group");
        assertThat(group.get("memberCount").asLong()).isEqualTo(3);
        JsonNode renamed = okAs(carolToken, 200, "PATCH", "/communication/discuss/conversations/"
                + group.get("id").asString(), Map.of("name", "Night shift"));
        assertThat(renamed.get("name").asString()).isEqualTo("Night shift");
    }

    @Test
    @DisplayName("messages: reply, reactions, star, pin, edit, delete, paging, attachments, search")
    void messages() {
        String id = channel(unique("msgs"), "public", List.of(bobId));
        String base = "/communication/discuss/conversations/" + id;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(ok(201, "POST", base + "/messages", Map.of("body", "line " + i + " zebra")).get("id").asString());
        }
        JsonNode reply = okAs(bobToken, 201, "POST", base + "/messages",
                Map.of("body", "replying", "replyToMessageId", ids.get(4)));
        assertThat(reply.get("replyTo").get("body").asString()).isEqualTo("line 4 zebra");
        String replyId = reply.get("id").asString();

        JsonNode page = ok(200, "GET", base + "/messages?limit=3", null);
        assertThat(page.get("messages").size()).isEqualTo(3);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.get("messages").get(2).get("id").asString()).isEqualTo(replyId);
        String oldest = page.get("messages").get(0).get("seq").asString();
        JsonNode older = ok(200, "GET", base + "/messages?limit=3&beforeSeq=" + oldest, null);
        assertThat(older.get("messages").get(2).get("seq").asLong()).isLessThan(Long.parseLong(oldest));
        assertThat(ok(200, "GET", base + "/messages?afterSeq=" + oldest, null).get("messages").size()).isEqualTo(2);

        JsonNode reacted = okAs(bobToken, 201, "POST", "/communication/discuss/messages/" + ids.get(0) + "/reactions",
                Map.of("emoji", "👍"));
        assertThat(reacted.get("reactions").get(0).get("count").asLong()).isEqualTo(1);
        JsonNode both = ok(201, "POST", "/communication/discuss/messages/" + ids.get(0) + "/reactions",
                Map.of("emoji", "👍"));
        assertThat(both.get("reactions").get(0).get("count").asLong()).isEqualTo(2);
        JsonNode toggled = ok(201, "POST", "/communication/discuss/messages/" + ids.get(0) + "/reactions",
                Map.of("emoji", "👍"));
        assertThat(toggled.get("reactions").get(0).get("count").asLong()).isEqualTo(1);

        assertThat(ok(201, "POST", "/communication/discuss/messages/" + ids.get(1) + "/star", null)
                .get("starred").asBoolean()).isTrue();
        assertThat(ok(200, "GET", "/communication/discuss/starred", null).toString()).contains(ids.get(1));
        JsonNode pinned = okAs(bobToken, 201, "POST", "/communication/discuss/messages/" + ids.get(2) + "/pin", null);
        assertThat(pinned.get("pinnedAt").isNull()).isFalse();
        assertThat(ok(200, "GET", base + "/pinned", null).toString()).contains(ids.get(2));
        assertThat(ok(200, "GET", base, null).get("pinnedCount").asLong()).isEqualTo(1);

        Res notMine = asUser(bobToken, "PATCH", "/communication/discuss/messages/" + ids.get(3), Map.of("body", "x"));
        assertThat(notMine.body().get("message").asString()).isEqualTo("You can only edit your own messages.");
        JsonNode edited = ok(200, "PATCH", "/communication/discuss/messages/" + ids.get(3), Map.of("body", "fixed"));
        assertThat(edited.get("body").asString()).isEqualTo("fixed");
        assertThat(edited.get("editedAt").isNull()).isFalse();
        Res noDelete = asUser(bobToken, "DELETE", "/communication/discuss/messages/" + ids.get(3), null);
        assertThat(noDelete.body().get("message").asString()).isEqualTo("You can only delete your own messages.");
        // The owner (moderator) may delete Bob's message.
        ok(200, "DELETE", "/communication/discuss/messages/" + replyId, null);
        assertThat(ok(200, "GET", base + "/messages", null).toString()).doesNotContain(replyId);
        assertThat(as("POST", "/communication/discuss/messages/" + replyId + "/star", null).status()).isEqualTo(404);

        Res empty = as("POST", base + "/messages", Map.of("body", "   "));
        assertThat(empty.body().get("message").asString()).isEqualTo("Message body is required.");

        // Attachments: upload a draft, attach it once; a second use is refused; others cannot use my draft.
        Res up = multipart("/communication/discuss/attachments", "notes.txt", "text/plain", "hello", bobToken);
        assertThat(up.status()).as(up.raw().body()).isEqualTo(201);
        String fileId = up.body().get("id").asString();
        Res stolen = as("POST", base + "/messages", Map.of("body", "", "attachmentIds", List.of(fileId)));
        assertThat(stolen.body().get("message").asString()).isEqualTo("Attachment " + fileId + " is not available.");
        JsonNode withFile = okAs(bobToken, 201, "POST", base + "/messages",
                Map.of("body", "", "attachmentIds", List.of(fileId)));
        assertThat(withFile.get("attachments").get(0).get("filename").asString()).isEqualTo("notes.txt");
        assertThat(withFile.get("attachments").get(0).get("url").asString()).contains("/files/" + fileId);
        Res reuse = okAsRes(bobToken, "POST", base + "/messages", Map.of("body", "again",
                "attachmentIds", List.of(fileId)));
        assertThat(reuse.status()).isEqualTo(400);
        assertThat(ok(200, "GET", base + "/attachments", null).toString()).contains(fileId);

        JsonNode found = ok(200, "GET", "/communication/discuss/search?q=ZEBRA", null);
        assertThat(found.toString()).contains(ids.get(0));
        assertThat(okAs(carolToken, 200, "GET", "/communication/discuss/search?q=zebra&conversationId=" + id, null)
                .size()).isEqualTo(4);
        Res shortQ = as("GET", "/communication/discuss/search?q=z", null);
        assertThat(shortQ.body().get("message").asString()).isEqualTo("Search needs at least 2 characters.");
    }

    private Res okAsRes(String token, String method, String path, Object body) {
        return call(method, path, body, token);
    }

    @Test
    @DisplayName("record chatter: registered types only, record permission, follow, notes, follower inbox, mention shares")
    void recordChatter() {
        String record = UUID.randomUUID().toString();
        String path = "/communication/discuss/records/SalesOrder/" + record;
        Res bad = as("GET", "/communication/discuss/records/Nope/" + record, null);
        assertThat(bad.body().get("message").asString()).isEqualTo("Unsupported record type 'Nope'.");
        JsonNode empty = ok(200, "GET", path, null);
        assertThat(empty.get("conversationId").isNull()).isTrue();
        // Bob has no sales:order:read.
        assertThat(asUser(bobToken, "GET", path, null).status()).isEqualTo(403);

        JsonNode note = ok(201, "POST", path + "/messages", Map.of("body", "Customer asked for Friday", "subtype",
                "note"));
        assertThat(note.get("subtype").asString()).isEqualTo("note");
        JsonNode state = ok(200, "GET", path, null);
        assertThat(state.get("following").asBoolean()).isTrue();
        String conv = state.get("conversationId").asString();
        assertThat(ok(201, "POST", "/communication/conversations/resolve-context",
                Map.of("contextType", "SalesOrder", "contextId", record)).get("id").asString()).isEqualTo(conv);

        // Mentioning Bob shares the thread with him (he becomes a follower) and fills his inbox.
        JsonNode mention = ok(201, "POST", path + "/messages", Map.of("body", "@Bob can you deliver?",
                "mentionUserIds", List.of(bobId)));
        JsonNode bobState = okAs(bobToken, 200, "GET", path, null);
        assertThat(bobState.get("following").asBoolean()).isTrue();
        assertThat(okAs(bobToken, 200, "GET", "/communication/discuss/inbox", null).toString())
                .contains(mention.get("id").asString());
        // A comment notifies other followers (the admin), a note does not.
        JsonNode comment = okAs(bobToken, 201, "POST", path + "/messages", Map.of("body", "Yes, Friday 9am"));
        okAs(bobToken, 201, "POST", path + "/messages", Map.of("body", "internal", "subtype", "note"));
        assertThat(jdbc.sql("SELECT reason FROM \"MessageInboxItem\" WHERE \"messageId\" = ? AND \"userId\" = ?")
                .params(comment.get("id").asString(), adminId).query(String.class).single()).isEqualTo("follower");
        assertThat(jdbc.sql("SELECT count(*) FROM \"MessageInboxItem\" i JOIN \"Message\" m ON m.id = i.\"messageId\""
                + " WHERE m.\"conversationId\" = ? AND m.subtype = 'note' AND i.\"userId\" = ?")
                .params(conv, adminId).query(Long.class).single()).isZero();
        JsonNode unfollowed = okAs(bobToken, 201, "POST", path + "/unfollow", null);
        assertThat(unfollowed.get("following").asBoolean()).isFalse();
        assertThat(asUser(bobToken, "GET", "/communication/discuss/conversations/" + conv + "/messages", null)
                .status()).isEqualTo(403);
        // Record threads never show in the sidebar.
        assertThat(ok(200, "GET", "/communication/discuss/sidebar", null).toString()).doesNotContain(conv);
    }

    @Test
    @DisplayName("department channels follow HR: created once, members added and removed, manual leave respected")
    void departments() {
        String dept = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrDepartment\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(dept, demoOrg, "D-" + dept.substring(0, 5), unique("Warehouse")).update();
        String empBob = employee(dept, bobId);
        employee(dept, carolId);
        JsonNode res = ok(201, "POST", "/communication/discuss/departments/sync", null);
        assertThat(res.get("created").asInt()).isPositive();
        String channelId = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"departmentId\" = ?").param(dept)
                .query(String.class).single();
        JsonNode detail = okAs(bobToken, 200, "GET", "/communication/discuss/conversations/" + channelId, null);
        assertThat(detail.get("members").toString()).contains(bobId, carolId).doesNotContain(adminId);
        JsonNode dir = okAs(bobToken, 200, "GET", "/communication/discuss/directory", null);
        assertThat(dir.get("departments").toString()).contains(channelId);
        assertThat(dir.get("users").toString()).contains("\"departmentId\":\"" + dept + "\"");

        // Bob moves out of the department; Carol leaves by hand.
        jdbc.sql("UPDATE \"HrEmployee\" SET \"departmentId\" = NULL WHERE id = ?").param(empBob).update();
        okAs(carolToken, 201, "POST", "/communication/discuss/conversations/" + channelId + "/leave", null);
        JsonNode second = ok(201, "POST", "/communication/discuss/departments/sync", null);
        assertThat(second.get("membersRemoved").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ConversationParticipant\" WHERE \"conversationId\" = ?"
                + " AND \"leftAt\" IS NULL").param(channelId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Conversation\" WHERE \"departmentId\" = ?").param(dept)
                .query(Long.class).single()).isEqualTo(1);
    }

    private String employee(String dept, String userId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"firstName\", \"userId\","
                        + " \"departmentId\", \"updatedAt\") VALUES (?, ?, ?, 'E', ?, ?, now())")
                .params(id, demoOrg, "E-" + id.substring(0, 8), userId, dept).update();
        return id;
    }

    @Test
    @DisplayName("typing, presence and validation; another organization cannot reach a conversation")
    void misc() {
        String id = channel(unique("misc"), "public", List.of());
        ok(201, "POST", "/communication/discuss/conversations/" + id + "/typing", Map.of("typing", true));
        assertThat(as("POST", "/communication/discuss/conversations/" + id + "/typing", Map.of()).status())
                .isEqualTo(400);
        assertThat(ok(200, "GET", "/communication/discuss/presence", null).has("online")).isTrue();
        assertThat(as("PATCH", "/communication/discuss/conversations/" + id + "/settings",
                Map.of("notifyLevel", "loud")).status()).isEqualTo(400);
        assertThat(as("POST", "/communication/discuss/channels", Map.of("name", "")).status()).isEqualTo(400);
        assertThat(as("POST", "/communication/discuss/chats", Map.of("userIds", List.of())).status()).isEqualTo(400);
        Res join = asUser(bobToken, "POST", "/communication/discuss/conversations/" + id + "/join", null);
        assertThat(join.status()).isEqualTo(201);

        Res other = call("GET", "/communication/discuss/conversations/" + id + "/messages", null, otherToken);
        assertThat(other.status()).isIn(403, 404);
        Res otherPost = call("POST", "/communication/discuss/conversations/" + id + "/messages", Map.of("body", "hi"),
                otherToken);
        assertThat(otherPost.status()).isIn(403, 404);
        assertThat(call("GET", "/communication/discuss/search?q=misc", null, otherToken).body().toString())
                .doesNotContain(id);
    }

    @Test
    @DisplayName("authorization matrix: every Discuss route needs a token and its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(DiscussController.class)).isGreaterThanOrEqualTo(37);
        assertThat(sweepGetRoutes("com.inventoryerp.communication.web")).isEmpty();
    }

    Res multipart(String path, String filename, String contentType, String content, String token) {
        try {
            String boundary = "----discuss" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content.getBytes(StandardCharsets.UTF_8));
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
