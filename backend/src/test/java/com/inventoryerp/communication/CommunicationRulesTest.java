package com.inventoryerp.communication;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.communication.application.Condition;
import com.inventoryerp.communication.application.ConversationAccessService;
import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import com.inventoryerp.communication.application.MessageDispatchService;
import com.inventoryerp.communication.application.TemplateRender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ports of {@code conversation-access.service.spec.ts}, {@code message-dispatch.service.spec.ts},
 * {@code condition.spec.ts}, {@code template-render.service.spec.ts} and {@code communication-tenancy.spec.ts}.
 */
class CommunicationRulesTest {

    private static final String READ = "communication:conversation:read";
    private static final String READ_ALL = "communication:conversation:read_all";

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Nested
    @DisplayName("ConversationAccessService.canAccess")
    class Access {

        private boolean can(String visibility, List<String> visibleTo, boolean participant, List<String> perms) {
            return ConversationAccessService.canAccess(
                    new AccessibleConversation(visibility, visibleTo), participant, perms);
        }

        @Test
        @DisplayName("allows an active participant, even on a private conversation")
        void participant() {
            assertThat(can("private", List.of(), true, List.of())).isTrue();
        }

        @Test
        @DisplayName("denies a non-participant on a private conversation — even with read_all")
        void privateDenied() {
            assertThat(can("private", List.of(), false, List.of(READ_ALL))).isFalse();
        }

        @Test
        @DisplayName("allows org-visible when the user holds conversation:read")
        void orgRead() {
            assertThat(can("org", List.of(), false, List.of(READ))).isTrue();
        }

        @Test
        @DisplayName("denies org-visible without conversation:read")
        void orgDenied() {
            assertThat(can("org", List.of(), false, List.of())).isFalse();
        }

        @Test
        @DisplayName("allows role-visible when permissions intersect visibleToPermissions")
        void roleAllowed() {
            assertThat(can("role", List.of("pos:kds"), false, List.of("pos:kds"))).isTrue();
        }

        @Test
        @DisplayName("denies role-visible when permissions do not intersect")
        void roleDenied() {
            assertThat(can("role", List.of("pos:kds"), false, List.of("pos:checkout"))).isFalse();
        }

        @Test
        @DisplayName("allows read_all to bypass org/role visibility (but not private)")
        void readAll() {
            assertThat(can("org", List.of(), false, List.of(READ_ALL))).isTrue();
            assertThat(can("role", List.of("x"), false, List.of(READ_ALL))).isTrue();
        }
    }

    @Nested
    @DisplayName("MessageDispatchService idempotency")
    class Dispatch {

        @Test
        @DisplayName("derives a stable, structured idempotency key")
        void key() {
            assertThat(MessageDispatchService.idempotencyKey("whatsapp", "chan-1", "msg-1", "+256700000000"))
                    .isEqualTo("whatsapp:chan-1:msg-1:+256700000000");
        }

        @Test
        @DisplayName("passes the opaque key through as providerRequestId for non-WhatsApp providers")
        void passthrough() {
            String key = MessageDispatchService.idempotencyKey("internal", "chan-1", "msg-1", "chan-1");
            assertThat(MessageDispatchService.providerRequestId("internal", key)).isEqualTo(key);
            assertThat(MessageDispatchService.providerRequestId("telegram", "telegram:c:m:123"))
                    .isEqualTo("telegram:c:m:123");
        }

        @Test
        @DisplayName("derives a WhatsApp-shaped id that is deterministic across retries")
        void whatsappId() {
            String key = MessageDispatchService.idempotencyKey("whatsapp", "chan-1", "msg-1", "+256700000000");
            String a = MessageDispatchService.providerRequestId("whatsapp", key);
            String b = MessageDispatchService.providerRequestId("whatsapp", key);
            assertThat(a).isEqualTo(b).matches("^[0-9A-F]{22}$");
        }

        @Test
        @DisplayName("produces different provider ids for different recipients")
        void distinctRecipients() {
            String k1 = MessageDispatchService.idempotencyKey("whatsapp", "c", "m", "+111");
            String k2 = MessageDispatchService.idempotencyKey("whatsapp", "c", "m", "+222");
            assertThat(MessageDispatchService.providerRequestId("whatsapp", k1))
                    .isNotEqualTo(MessageDispatchService.providerRequestId("whatsapp", k2));
        }
    }

    @Nested
    @DisplayName("matchesCondition")
    class Conditions {

        @Test
        @DisplayName("empty condition always matches")
        void empty() {
            assertThat(Condition.matches(Map.of(), m("anything", 1))).isTrue();
        }

        @Test
        @DisplayName("bare literal is equality")
        void literal() {
            assertThat(Condition.matches(m("status", "paid"), m("status", "paid"))).isTrue();
            assertThat(Condition.matches(m("status", "paid"), m("status", "draft"))).isFalse();
        }

        @Test
        @DisplayName("numeric operators")
        void numeric() {
            assertThat(Condition.matches(m("total", m("$gte", 100)), m("total", 100))).isTrue();
            assertThat(Condition.matches(m("total", m("$gt", 100)), m("total", 100))).isFalse();
            assertThat(Condition.matches(m("total", m("$lt", 100)), m("total", 99))).isTrue();
        }

        @Test
        @DisplayName("$field compares two payload fields")
        void field() {
            Object cond = m("quantity", m("$lt", m("$field", "minimum")));
            assertThat(Condition.matches(cond, m("quantity", 3, "minimum", 5))).isTrue();
            assertThat(Condition.matches(cond, m("quantity", 7, "minimum", 5))).isFalse();
        }

        @Test
        @DisplayName("dotted paths resolve into nested payloads")
        void dotted() {
            assertThat(Condition.matches(m("product.qty", m("$lte", 2)), m("product", m("qty", 2)))).isTrue();
        }

        @Test
        @DisplayName("multiple keys are AND-ed")
        void and() {
            Object cond = m("status", "paid", "total", m("$gte", 50));
            assertThat(Condition.matches(cond, m("status", "paid", "total", 60))).isTrue();
            assertThat(Condition.matches(cond, m("status", "paid", "total", 40))).isFalse();
        }

        @Test
        @DisplayName("unknown operator fails closed (no spam)")
        void unknownOperator() {
            assertThat(Condition.matches(m("total", m("$wat", 1)), m("total", 1))).isFalse();
        }

        @Test
        @DisplayName("$in membership")
        void in() {
            assertThat(Condition.matches(m("kind", m("$in", List.of("a", "b"))), m("kind", "b"))).isTrue();
            assertThat(Condition.matches(m("kind", m("$in", List.of("a", "b"))), m("kind", "c"))).isFalse();
        }

        @Test
        @DisplayName("$ne and $exists")
        void neExists() {
            assertThat(Condition.matches(m("s", m("$ne", "x")), m("s", "y"))).isTrue();
            assertThat(Condition.matches(m("s", m("$exists", true)), m("s", "y"))).isTrue();
            assertThat(Condition.matches(m("s", m("$exists", false)), m())).isTrue();
            assertThat(Condition.matches(m("s", m("$eq", "1")), m("s", 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("TemplateRenderService")
    class Templates {

        @Test
        @DisplayName("substitutes dotted placeholders")
        void dotted() {
            assertThat(TemplateRender.render("Stock low: {{product.name}} ({{quantity}})",
                    m("product", m("name", "Arabica"), "quantity", 3))).isEqualTo("Stock low: Arabica (3)");
        }

        @Test
        @DisplayName("tolerates whitespace in braces")
        void whitespace() {
            assertThat(TemplateRender.render("Hi {{ name }}", m("name", "Mary"))).isEqualTo("Hi Mary");
        }

        @Test
        @DisplayName("missing path renders empty, never the literal braces or \"undefined\"")
        void missing() {
            assertThat(TemplateRender.render("X={{missing}}Y", m())).isEqualTo("X=Y");
        }

        @Test
        @DisplayName("coerces numbers and booleans")
        void coerce() {
            assertThat(TemplateRender.render("{{n}}/{{b}}", m("n", 42, "b", true))).isEqualTo("42/true");
        }

        @Test
        @DisplayName("extractVariables lists distinct placeholders")
        void extract() {
            assertThat(TemplateRender.extractVariables("{{a}} {{b.c}} {{a}}")).containsExactlyInAnyOrder("a", "b.c");
        }

        @Test
        @DisplayName("array index and length resolve like JS")
        void arrays() {
            assertThat(TemplateRender.render("{{xs.0}}/{{xs.length}}/{{xs.9}}", m("xs", List.of("a", "b"))))
                    .isEqualTo("a/2/");
        }
    }

    /**
     * Guard: every communication entity MUST carry a non-null {@code organizationId} so services can filter on it. A
     * model missing it is a cross-tenant leak, not a bug.
     */
    @Nested
    @DisplayName("communication tenancy scoping")
    class Tenancy {

        @ParameterizedTest(name = "{0} is org-scoped")
        @ValueSource(strings = {"CommunicationChannel", "Conversation", "ConversationChannel", "ExternalIdentity",
                "ConversationParticipant", "Message", "MessageAttachment", "MessageDelivery", "ExternalMessage",
                "WhatsAppAuthState", "MessageTemplate", "CommunicationRule"})
        void orgScoped(String model) throws Exception {
            Class<?> type = Class.forName("com.inventoryerp.communication.domain." + model);
            assertThat(type.isAnnotationPresent(Entity.class)).isTrue();
            Field field = type.getDeclaredField("organizationId");
            Column column = field.getAnnotation(Column.class);
            assertThat(column.name()).isEqualTo("organizationId");
            assertThat(column.nullable()).isFalse();
        }
    }
}
