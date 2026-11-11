package com.inventoryerp.communication;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.communication.application.DeliveryStatusService;
import com.inventoryerp.communication.application.DeliveryStatusService.ProviderStatusUpdate;
import com.inventoryerp.communication.application.InboundMessageService;
import com.inventoryerp.communication.application.InboundMessageService.InboundMessage;
import com.inventoryerp.communication.application.MessageDispatchWorker;
import com.inventoryerp.communication.application.RecipientResolverService;
import com.inventoryerp.communication.application.RuleEngineService;
import com.inventoryerp.communication.application.providers.ChannelSecrets;
import com.inventoryerp.communication.application.providers.ProviderHttp;
import com.inventoryerp.communication.application.providers.ProviderRegistry;
import com.inventoryerp.communication.application.providers.TelegramProvider;
import com.inventoryerp.communication.application.providers.WhatsAppCloudProvider;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Communication on Testcontainers Postgres (ENABLE_COMMUNICATION on in the test profile): conversations, messages and
 * read state, chatter context, templates/rules (incl. dedupe), channels with sealed credentials, the Telegram
 * webhook (secret + replay), the dispatch worker against a fake Telegram Bot API, the SSE inbox stream, tenant
 * isolation and the authorization matrix.
 */
class CommunicationIT extends ApiTestSupport {

    @Autowired
    ProviderRegistry registry;

    @Autowired
    ChannelSecrets channelSecrets;

    @Autowired
    MessageDispatchWorker worker;

    @Autowired
    RuleEngineService ruleEngine;

    @Autowired
    InboundMessageService inbound;

    @Autowired
    DeliveryStatusService deliveryStatus;

    @Autowired
    RecipientResolverService recipients;

    /** Fake Telegram Bot API: records every POST and answers with the next canned response. */
    final List<String[]> sent = Collections.synchronizedList(new ArrayList<>());
    volatile ProviderHttp.Response nextResponse;

    String adminId;

    @BeforeAll
    void registerFakeTelegram() {
        ProviderHttp fake = (url, headers, body) -> {
            sent.add(new String[] {url, body});
            return nextResponse;
        };
        registry.register(new TelegramProvider(channelSecrets, fake, mapper, "", "https://tg.test"));
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test' AND \"organizationId\" = ?")
                .param(demoOrg).query(String.class).single();
    }

    @AfterAll
    void unregisterFakeTelegram() {
        registry.unregister("telegram");
    }

    private Res idem(String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", key);
        return call(method, path, body, h);
    }

    private JsonNode telegramChannel(String secret) {
        return ok(201, "POST", "/communication/channels", Map.of("providerId", "telegram", "name",
                "Bot " + UUID.randomUUID().toString().substring(0, 6), "config",
                Map.of("botToken", "123:SECRET", "webhookSecret", secret)));
    }

    private Map<String, Object> update(long messageId, long chatId, String text) {
        return Map.of("update_id", messageId, "message", Map.of("message_id", messageId, "date", 1_790_000_000,
                "chat", Map.of("id", chatId, "type", "private"),
                "from", Map.of("id", chatId, "first_name", "Amina", "username", "amina"), "text", text));
    }

    @Test
    @DisplayName("conversation create (idempotent replay), list, get, send, messages, mark read; validation")
    void conversationsAndMessages() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("kind", "group", "name", "Kitchen crew", "visibility", "org");
        Res created = idem("POST", "/communication/conversations", body, key);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(200);
        String convId = created.body().get("id").asString();
        Res replay = idem("POST", "/communication/conversations", body, key);
        assertThat(replay.body().get("id").asString()).isEqualTo(convId);

        assertThat(ok(200, "GET", "/communication/conversations", null).toString()).contains(convId);
        JsonNode one = ok(200, "GET", "/communication/conversations/" + convId, null);
        assertThat(one.get("name").asString()).isEqualTo("Kitchen crew");

        Res msg = idem("POST", "/communication/conversations/" + convId + "/messages",
                Map.of("body", "  Milk delivery at 7  "), UUID.randomUUID().toString());
        assertThat(msg.status()).as(msg.raw().body()).isEqualTo(200);
        assertThat(msg.body().get("body").asString()).isEqualTo("Milk delivery at 7");
        String msgId = msg.body().get("id").asString();
        JsonNode list = ok(200, "GET", "/communication/conversations/" + convId + "/messages?limit=10", null);
        assertThat(list.toString()).contains(msgId);
        // Deviation: a non-numeric limit is clamped (Nest answers 500).
        ok(200, "GET", "/communication/conversations/" + convId + "/messages?limit=abc", null);
        // The internal delivery row is queued and drains as delivered (sent + deliveredAt) without any network.
        worker.tick();
        assertThat(jdbc.sql("SELECT status::text FROM \"MessageDelivery\" WHERE \"messageId\" = ?").param(msgId)
                .query(String.class).list()).containsOnly("delivered");

        ok(201, "POST", "/communication/conversations/" + convId + "/read", Map.of("messageId", msgId));
        ok(201, "POST", "/communication/conversations/" + convId + "/read", null);

        Res empty = as("POST", "/communication/conversations/" + convId + "/messages", Map.of("body", ""));
        assertThat(empty.status()).isEqualTo(400);
        Res badKind = as("POST", "/communication/conversations", Map.of("kind", "broadcast"));
        assertThat(badKind.status()).isEqualTo(400);
        Res missing = as("GET", "/communication/conversations/" + UUID.randomUUID(), null);
        assertThat(missing.status()).isIn(403, 404);
    }

    @Test
    @DisplayName("resolve-context is get-or-create per business record")
    void chatterContext() {
        String record = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("contextType", "Invoice", "contextId", record);
        String a = ok(201, "POST", "/communication/conversations/resolve-context", body).get("id").asString();
        String b = ok(201, "POST", "/communication/conversations/resolve-context", body).get("id").asString();
        assertThat(a).isEqualTo(b);
        Res bad = as("POST", "/communication/conversations/resolve-context", Map.of("contextType", "Invoice"));
        assertThat(bad.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("templates version per key; rules validate the event, toggle, delete and dedupe per event instance")
    void templatesAndRules() {
        String tplKey = "paid-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode v1 = ok(201, "POST", "/communication/templates", Map.of("key", tplKey, "subject", "Paid",
                "body", "Invoice {{invoiceId}} paid by {{ customer.name }}"));
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("variables").toString()).contains("invoiceId", "customer.name");
        JsonNode v2 = ok(201, "POST", "/communication/templates", Map.of("key", tplKey, "subject", "Paid",
                "body", "Invoice {{invoiceId}} settled by {{customer.name}}"));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        JsonNode templates = ok(200, "GET", "/communication/templates", null);
        assertThat(templates.toString()).contains(v2.get("id").asString());
        assertThat(jdbc.sql("SELECT active FROM \"MessageTemplate\" WHERE id = ?").param(v1.get("id").asString())
                .query(Boolean.class).single()).isFalse();

        assertThat(ok(200, "GET", "/communication/rules/eventable", null).toString()).contains("invoice.paid");
        Res notEventable = as("POST", "/communication/rules", Map.of("eventName", "user.login", "templateKey",
                tplKey, "recipientResolver", "user"));
        assertThat(notEventable.status()).isEqualTo(400);
        assertThat(notEventable.body().get("message").asString())
                .startsWith("Event 'user.login' is not rule-eventable. Allowed: pos.sale.completed, ");
        JsonNode rule = ok(201, "POST", "/communication/rules", Map.of("eventName", "invoice.paid", "templateKey",
                tplKey, "recipientResolver", "user:userId", "condition", Map.of("total", Map.of("$gte", 10))));
        assertThat(rule.get("channelSelector").asString()).isEqualTo("internal");
        String ruleId = rule.get("id").asString();
        assertThat(ok(200, "GET", "/communication/rules", null).toString()).contains(ruleId);

        String invoiceId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("organizationId", demoOrg, "invoiceId", invoiceId, "userId", adminId,
                "total", 25, "customer", Map.of("name", "Amina"));
        Runnable fire = () -> TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> ruleEngine.dispatch("invoice.paid", payload));
        fire.run();
        fire.run(); // a redelivered event must not notify twice
        String expected = "Invoice " + invoiceId + " settled by Amina";
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND body = ?")
                .params(adminId, expected).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"CommunicationDispatch\" WHERE \"ruleId\" = ?").param(ruleId)
                .query(Long.class).single()).isEqualTo(1);
        // Condition not met → nothing.
        String small = UUID.randomUUID().toString();
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> ruleEngine.dispatch("invoice.paid",
                Map.of("organizationId", demoOrg, "invoiceId", small, "userId", adminId, "total", 5)));
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE body LIKE ?").param("%" + small + "%")
                .query(Long.class).single()).isZero();

        ok(200, "PATCH", "/communication/rules/" + ruleId + "/enabled", Map.of("enabled", false));
        Res noBool = as("PATCH", "/communication/rules/" + ruleId + "/enabled", Map.of());
        assertThat(noBool.status()).isEqualTo(400);
        ok(200, "DELETE", "/communication/rules/" + ruleId, null);
        assertThat(as("DELETE", "/communication/rules/" + ruleId, null).status()).isEqualTo(500);
    }

    @Test
    @DisplayName("channels: credentials sealed at rest and masked; unknown provider; pairing, connect, disconnect, remove")
    void channels() {
        JsonNode ch = telegramChannel("hook-secret");
        String id = ch.get("id").asString();
        assertThat(ch.get("transport").asString()).isEqualTo("bot");
        assertThat(ch.get("config").get("botToken").asString()).isEqualTo("********");
        assertThat(ch.get("config").get("webhookSecret").asString()).isEqualTo("********");
        String stored = jdbc.sql("SELECT config::text FROM \"CommunicationChannel\" WHERE id = ?").param(id)
                .query(String.class).single();
        assertThat(stored).doesNotContain("123:SECRET").doesNotContain("hook-secret").contains("enc:v1:");
        assertThat(channelSecrets.channelConfig(id).get("botToken")).isEqualTo("123:SECRET");

        Res unknown = as("POST", "/communication/channels", Map.of("providerId", "sms", "name", "x"));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unknown provider 'sms'.");

        assertThat(ok(200, "GET", "/communication/channels", null).toString()).contains(id);
        JsonNode pairing = ok(200, "GET", "/communication/channels/" + id + "/pairing", null);
        assertThat(pairing.has("status")).isTrue();
        assertThat(ok(201, "POST", "/communication/channels/" + id + "/connect", null).get("ok").asBoolean())
                .isTrue();
        ok(201, "POST", "/communication/channels/" + id + "/disconnect", Map.of("logout", true));
        ok(200, "DELETE", "/communication/channels/" + id, null);
        Res gone = as("GET", "/communication/channels/" + id + "/pairing", null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("Channel not found.");

        JsonNode wa = ok(201, "POST", "/communication/channels", Map.of("providerId", "whatsapp", "name", "WA off " + UUID.randomUUID()));
        Res off = as("POST", "/communication/channels/" + wa.get("id").asString() + "/connect", null);
        assertThat(off.status()).isEqualTo(400);
        assertThat(off.body().get("message").asString())
                .isEqualTo("Provider 'whatsapp' is not enabled on this server.");
    }

    @Test
    @DisplayName("telegram webhook: bad secret ignored, replay deduped; reply goes out through the worker")
    void telegramInboundAndOutbound() {
        String channelId = telegramChannel("s3cret").get("id").asString();
        long chatId = 900_000_000L + (long) (Math.random() * 1_000_000);
        String path = "/communication/webhooks/telegram/" + channelId;

        Res wrong = call("POST", path, update(1, chatId, "hello"), Map.of("X-Telegram-Bot-Api-Secret-Token", "nope"));
        assertThat(wrong.status()).isEqualTo(200);
        assertThat(wrong.body().get("ok").asBoolean()).isTrue();
        Res unknownChannel = call("POST", "/communication/webhooks/telegram/" + UUID.randomUUID(),
                update(1, chatId, "hello"), Map.of());
        assertThat(unknownChannel.body().get("ok").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ExternalMessage\" WHERE \"externalConversationId\" = ?")
                .param(String.valueOf(chatId)).query(Long.class).single()).isZero();

        Map<String, String> good = Map.of("X-Telegram-Bot-Api-Secret-Token", "s3cret");
        call("POST", path, update(1, chatId, "Is the cafe open?"), good);
        call("POST", path, update(1, chatId, "Is the cafe open?"), good); // Telegram retry
        assertThat(jdbc.sql("SELECT count(*) FROM \"ExternalMessage\" WHERE \"externalConversationId\" = ?")
                .param(String.valueOf(chatId)).query(Long.class).single()).isEqualTo(1);
        String convId = jdbc.sql("""
                        SELECT m."conversationId" FROM "ExternalMessage" e JOIN "Message" m ON m.id = e."messageId"
                        WHERE e."externalConversationId" = ?""").param(String.valueOf(chatId))
                .query(String.class).single();
        JsonNode msgs = ok(200, "GET", "/communication/conversations/" + convId + "/messages", null);
        assertThat(msgs.toString()).contains("Is the cafe open?");

        // Reply: success first, then a terminal "blocked" error on a second reply.
        sent.clear();
        nextResponse = new ProviderHttp.Response(200,
                "{\"ok\":true,\"result\":{\"message_id\":77,\"chat\":{\"id\":" + chatId + "}}}", Map.of());
        String m1 = idem("POST", "/communication/conversations/" + convId + "/messages", Map.of("body", "Yes, until 9"),
                UUID.randomUUID().toString()).body().get("id").asString();
        worker.tick();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()[0]).isEqualTo("https://tg.test/bot123:SECRET/sendMessage");
        assertThat(sent.getFirst()[1]).contains("\"chat_id\":\"" + chatId + "\"").contains("Yes, until 9");
        Map<String, Object> d1 = jdbc.sql("SELECT status::text AS status, \"externalMessageId\" FROM \"MessageDelivery\""
                + " WHERE \"messageId\" = ?").param(m1).query().singleRow();
        assertThat(d1.get("status")).isEqualTo("sent");
        assertThat(d1.get("externalMessageId")).isEqualTo(chatId + ":77");

        nextResponse = new ProviderHttp.Response(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}",
                Map.of());
        String m2 = idem("POST", "/communication/conversations/" + convId + "/messages", Map.of("body", "Hello?"),
                UUID.randomUUID().toString()).body().get("id").asString();
        worker.tick();
        assertThat(jdbc.sql("SELECT status::text FROM \"MessageDelivery\" WHERE \"messageId\" = ?").param(m2)
                .query(String.class).single()).isEqualTo("failed");
    }

    @Test
    @DisplayName("whatsapp: inbound links the partner, a rule messages them, retries/backoff, read status, failures")
    void whatsappRuleRetriesAndStatus() {
        Deque<Object> replies = new ArrayDeque<>();
        List<String[]> waSent = Collections.synchronizedList(new ArrayList<>());
        ProviderHttp waFake = (url, headers, body) -> {
            waSent.add(new String[] {url, headers.get("Authorization"), body});
            Object next = replies.poll();
            if (next instanceof Exception e) {
                throw e;
            }
            return (ProviderHttp.Response) next;
        };
        registry.register(new WhatsAppCloudProvider(channelSecrets, waFake, mapper, "https://graph.test"));
        try {
            String channelId = ok(201, "POST", "/communication/channels", Map.of("providerId", "whatsapp", "name",
                    "WA " + UUID.randomUUID(), "config", Map.of("phoneNumberId", "PN1", "accessToken", "wa-token"))).get("id").asString();
            String phone = "+2567" + (10_000_000 + (int) (Math.random() * 89_999_999));
            String partnerId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"phoneE164\", \"isCustomer\","
                    + " \"updatedAt\") VALUES (?, ?, ?, 'Amina', ?, true, now())")
                    .params(partnerId, demoOrg, "WA-" + partnerId.substring(0, 8), phone).update();
            InboundMessage in = new InboundMessage(demoOrg, channelId, "whatsapp", phone, "wamid.IN-" + partnerId,
                    phone, phone, "Amina", "Hi there", "text", Instant.now(), Map.of());
            TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> inbound.ingest(in));
            assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"ExternalIdentity\" WHERE \"organizationId\" = ?"
                    + " AND \"providerId\" = 'whatsapp' AND \"externalUserId\" = ?").params(demoOrg, phone)
                    .query(String.class).single()).isEqualTo(partnerId);

            String tplKey = "wa-" + UUID.randomUUID().toString().substring(0, 6);
            ok(201, "POST", "/communication/templates", Map.of("key", tplKey, "body", "Thanks {{customer.name}}!"));
            ok(201, "POST", "/communication/rules", Map.of("eventName", "invoice.posted", "templateKey", tplKey,
                    "recipientResolver", "partner", "channelSelector", "whatsapp"));
            replies.add(new ProviderHttp.Response(200, "{\"messages\":[{\"id\":\"wamid.OUT-" + partnerId + "\"}]}",
                    Map.of()));
            TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> ruleEngine.dispatch(
                    "invoice.posted", Map.of("organizationId", demoOrg, "invoiceId", UUID.randomUUID().toString(),
                            "partnerId", partnerId, "customer", Map.of("name", "Amina"))));
            worker.tick();
            assertThat(waSent).hasSize(1);
            assertThat(waSent.getFirst()[0]).isEqualTo("https://graph.test/v19.0/PN1/messages");
            assertThat(waSent.getFirst()[1]).isEqualTo("Bearer wa-token");
            assertThat(waSent.getFirst()[2]).contains("\"to\":\"" + phone.substring(1) + "\"")
                    .contains("Thanks Amina!");
            String wamid = "wamid.OUT-" + partnerId;
            assertThat(jdbc.sql("SELECT status::text FROM \"MessageDelivery\" WHERE \"externalMessageId\" = ?")
                    .param(wamid).query(String.class).single()).isEqualTo("sent");
            deliveryStatus.applyProviderStatus(new ProviderStatusUpdate("whatsapp", wamid, "read", Instant.now()));
            deliveryStatus.applyProviderStatus(new ProviderStatusUpdate("whatsapp", wamid, "delivered",
                    Instant.now())); // never downgrades
            assertThat(jdbc.sql("SELECT status::text FROM \"MessageDelivery\" WHERE \"externalMessageId\" = ?")
                    .param(wamid).query(String.class).single()).isEqualTo("read");

            // An agent reply: 429 → retry with retry-after; network error → ambiguous, clamped to 3; 500 → failed.
            String convId = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"contextType\" = 'Partner'"
                    + " AND \"contextId\" = ?").param(partnerId).query(String.class).single();
            String msgId = ok(200, "POST", "/communication/conversations/" + convId + "/messages",
                    Map.of("body", "Your order is ready")).get("id").asString();
            replies.add(new ProviderHttp.Response(429, "slow down", Map.of("Retry-After", "7")));
            replies.add(new java.io.IOException("reset"));
            replies.add(new ProviderHttp.Response(500, "{\"error\":\"boom\"}", Map.of()));
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                jdbc.sql("UPDATE \"MessageDelivery\" SET \"nextAttemptAt\" = now() - interval '1 second'"
                        + " WHERE \"messageId\" = ?").param(msgId).update();
                worker.tick();
                seen.add(jdbc.sql("SELECT status::text || '/' || attempts || '/' || \"errorCode\" || '/' || ambiguous"
                        + " FROM \"MessageDelivery\" WHERE \"messageId\" = ?").param(msgId).query(String.class)
                        .single());
            }
            assertThat(seen).containsExactly("queued/1/rate_limited/false", "queued/2/network/true",
                    "failed/3/http_500/false");
            assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title = ?").params(adminId, "Message could not be delivered")
                    .query(Long.class).single()).isGreaterThan(0);

            // Provider switched off: the next delivery fails terminally with a reason.
            registry.unregister("whatsapp");
            String off = ok(200, "POST", "/communication/conversations/" + convId + "/messages",
                    Map.of("body", "Anyone?")).get("id").asString();
            worker.tick();
            assertThat(jdbc.sql("SELECT \"errorCode\" FROM \"MessageDelivery\" WHERE \"messageId\" = ?").param(off)
                    .query(String.class).single()).isEqualTo("provider_disabled");
        } finally {
            registry.unregister("whatsapp");
        }
    }

    @Test
    @DisplayName("recipient resolvers: permission, role, user, partner, unknown")
    void resolvers() {
        String roleName = jdbc.sql("SELECT r.name FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                + " WHERE ur.\"B\" = ? LIMIT 1").param(adminId).query(String.class).single();
        assertThat(recipients.resolve(demoOrg, "permission:communication:channel:manage", Map.of()))
                .extracting(RecipientResolverService.Recipient::userId).contains(adminId);
        assertThat(recipients.resolve(demoOrg, "role:" + roleName, Map.of()))
                .extracting(RecipientResolverService.Recipient::userId).contains(adminId);
        assertThat(recipients.resolve(demoOrg, "permission:", Map.of())).isEmpty();
        assertThat(recipients.resolve(demoOrg, "role:", Map.of())).isEmpty();
        assertThat(recipients.resolve(demoOrg, "user:cashier.id", Map.of("cashier", Map.of("id", "u1"))))
                .extracting(RecipientResolverService.Recipient::userId).containsExactly("u1");
        assertThat(recipients.resolve(demoOrg, "user", Map.of())).isEmpty();
        assertThat(recipients.resolve(demoOrg, "partner:context", Map.of("partnerId", "p1")))
                .extracting(RecipientResolverService.Recipient::partnerId).containsExactly("p1");
        assertThat(recipients.resolve(demoOrg, "carrier-pigeon", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("SSE inbox stream: query-token auth, retry preamble and a live message.created event")
    void stream() throws Exception {
        String convId = ok(200, "POST", "/communication/conversations", Map.of("kind", "group", "name", "Live",
                "visibility", "org")).get("id").asString();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/communication/stream?access_token=" + adminToken)).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        try (InputStream in = res.body()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertThat(reader.readLine()).isEqualTo("retry:5000");
            CompletableFuture<String> event = CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:") && line.contains("message.created")) {
                            return line;
                        }
                    }
                    return null;
                } catch (java.io.IOException e) {
                    return null;
                }
            });
            ok(200, "POST", "/communication/conversations/" + convId + "/messages", Map.of("body", "ping"));
            assertThat(event.get(10, TimeUnit.SECONDS)).contains(convId).contains("ping");
        }
        Res noToken = call("GET", "/communication/stream", null, (String) null);
        assertThat(noToken.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("tenant isolation and the authorization matrix (22 routes)")
    void isolationAndAuth() {
        String convId = ok(200, "POST", "/communication/conversations", Map.of("kind", "group", "name", "Mine",
                "visibility", "org")).get("id").asString();
        Res foreign = call("GET", "/communication/conversations/" + convId, null, otherToken);
        assertThat(foreign.status()).isIn(403, 404);
        assertThat(call("GET", "/communication/conversations", null, otherToken).body().toString())
                .doesNotContain(convId);
        String channelId = telegramChannel("x").get("id").asString();
        assertThat(call("GET", "/communication/channels/" + channelId + "/pairing", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/communication/channels", null, otherToken).body().toString())
                .doesNotContain(channelId);
        assertThat(assertAuthorizationMatrix(com.inventoryerp.communication.web.CommunicationController.class,
                com.inventoryerp.communication.web.TelegramWebhookController.class)).isEqualTo(22);
    }
}
