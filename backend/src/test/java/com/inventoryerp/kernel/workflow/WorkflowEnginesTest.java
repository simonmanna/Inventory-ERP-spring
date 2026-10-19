package com.inventoryerp.kernel.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.modules.ModuleRegistry;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Transition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ports of {@code workflow.registry.spec.ts}, {@code workflow.service.spec.ts},
 * {@code milestone.service.spec.ts} and {@code module-registry.service.spec.ts}.
 */
class WorkflowEnginesTest {

    @Nested
    @DisplayName("WorkflowRegistry")
    class Registry {
        WorkflowRegistry registry = new WorkflowRegistry();

        @Test
        @DisplayName("registers and retrieves a workflow definition")
        void registers() {
            var def = new WorkflowDefinition("invoice", "draft", List.of(new Transition("draft", "posted", "post")));
            registry.register(def);
            assertThat(registry.get("invoice")).isEqualTo(def);
        }

        @Test
        @DisplayName("lists all registered workflows")
        void lists() {
            registry.register(new WorkflowDefinition("invoice", "draft", List.of()));
            registry.register(new WorkflowDefinition("payment", "posted", List.of()));
            assertThat(registry.list()).hasSize(2);
        }

        @Test
        @DisplayName("throws on duplicate registration")
        void duplicate() {
            registry.register(new WorkflowDefinition("invoice", "draft", List.of()));
            assertThatThrownBy(() -> registry.register(new WorkflowDefinition("invoice", "draft", List.of())))
                    .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName("returns undefined for unknown workflow")
        void unknown() {
            assertThat(registry.get("unknown")).isNull();
        }
    }

    @Nested
    @DisplayName("WorkflowService (lookup methods)")
    class Lookup {
        WorkflowRegistry registry = new WorkflowRegistry();
        WorkflowService svc = new WorkflowService((WorkflowService.Store) null, null, null, registry, List::of);

        @BeforeEach
        void setUp() {
            registry.register(new WorkflowDefinition("invoice", "draft", List.of(
                    new Transition("draft", "posted", "post", "invoice:post"),
                    new Transition("posted", "cancelled", "cancel", "invoice:cancel"))));
        }

        @Test
        @DisplayName("returns actions allowed by permissions")
        void allowed() {
            var actions = svc.availableActions("invoice", "draft", List.of("invoice:post"));
            assertThat(actions).hasSize(1);
            assertThat(actions.getFirst().action()).isEqualTo("post");
        }

        @Test
        @DisplayName("returns no actions if permissions do not match / unknown type")
        void none() {
            assertThat(svc.availableActions("invoice", "draft", List.of())).isEmpty();
            assertThat(svc.availableActions("unknown", "draft", List.of())).isEmpty();
        }

        @Test
        @DisplayName("returns multiple actions from the same from-state")
        void multiple() {
            registry.register(new WorkflowDefinition("partner", "active", List.of(
                    new Transition("active", "archived", "archive", "partner:delete"),
                    new Transition("active", "inactive", "deactivate", "partner:update"))));
            assertThat(svc.availableActions("partner", "active", List.of("partner:delete", "partner:update")).stream()
                    .map(Transition::action).sorted().toList()).containsExactly("archive", "deactivate");
        }

        @Test
        @DisplayName("canTransition")
        void canTransition() {
            assertThat(svc.canTransition("invoice", "draft", "post", List.of("invoice:post")).to()).isEqualTo("posted");
            assertThat(svc.canTransition("invoice", "draft", "cancel", List.of("invoice:cancel"))).isNull();
            assertThat(svc.canTransition("invoice", "draft", "post", List.of())).isNull();
        }
    }

    @Nested
    @DisplayName("WorkflowService.transition — business events")
    class Transitions {
        WorkflowRegistry registry = new WorkflowRegistry();
        AuditService audit = mock(AuditService.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        Map<String, Object> updated = new HashMap<>();
        WorkflowService svc = new WorkflowService(new WorkflowService.Store() {
            @Override
            public Map<String, Object> load(String table, String id) {
                assertThat(table).isEqualTo("Order");
                return Map.of("id", "o1", "status", "confirmed");
            }

            @Override
            public void updateStatus(String table, String id, Map<String, Object> values) {
                updated.putAll(values);
            }

            @Override
            public Set<String> columns(String table) {
                return Set.of("id", "status");
            }
        }, audit, outbox, registry, List::of);

        @BeforeEach
        void setUp() {
            registry.register(new WorkflowDefinition("order", "draft", List.of(
                    Transition.withEvent("confirmed", "completed", "complete", "order.completed"),
                    new Transition("confirmed", "in_progress", "start_fulfillment"))));
            TenantContext.set(TenantStore.user("org-1", "u1"));
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        @Test
        @DisplayName("publishes the declared typed event")
        @SuppressWarnings("unchecked")
        void typedEvent() {
            svc.transition("order", "o1", "complete", null, null);
            ArgumentCaptor<Map<String, Object>> fact = ArgumentCaptor.forClass(Map.class);
            verify(outbox).publish(eq("order.completed"), fact.capture());
            assertThat(fact.getValue()).containsEntry("organizationId", "org-1").containsEntry("orderId", "o1")
                    .containsEntry("fromState", "confirmed").containsEntry("toState", "completed");
            assertThat(updated).containsEntry("status", "completed");
            verify(audit).recordInTx(any());
        }

        @Test
        @DisplayName("falls back to an untyped entityType.action name when no event is declared")
        void untyped() {
            svc.transition("order", "o1", "start_fulfillment", null, null);
            verify(outbox).publish(eq("order.start_fulfillment"), any());
        }

        @Test
        @DisplayName("merges the transition payload into the fact")
        @SuppressWarnings("unchecked")
        void payload() {
            svc.transition("order", "o1", "complete", Map.of("invoiceId", "inv-9"), null);
            ArgumentCaptor<Map<String, Object>> fact = ArgumentCaptor.forClass(Map.class);
            verify(outbox).publish(eq("order.completed"), fact.capture());
            assertThat(fact.getValue()).containsEntry("invoiceId", "inv-9");
        }

        @Test
        @DisplayName("rejects an undeclared transition with the Nest message")
        void undeclared() {
            assertThatThrownBy(() -> svc.transition("order", "o1", "reopen", null, null))
                    .hasMessage("No 'reopen' transition from 'confirmed' for order");
        }
    }

    @Nested
    @DisplayName("MilestoneService")
    class Milestones {
        MilestoneRegistry registry = new MilestoneRegistry();
        List<MilestoneService.Fact> facts = new java.util.ArrayList<>();
        int calls;
        MilestoneService svc = new MilestoneService((org, type, id) -> {
            calls++;
            return facts;
        }, registry);
        JsonMapper mapper = JsonMapper.builder().build();

        MilestoneService.Fact fact(String name, String iso, String strategy) {
            return new MilestoneService.Fact(name, Instant.parse(iso), "u1",
                    mapper.readTree(strategy == null ? "{}" : "{\"strategy\":\"" + strategy + "\"}"));
        }

        @BeforeEach
        void setUp() {
            registry.registerAll(List.of(
                    new MilestoneRegistry.Definition("confirmed", "Confirmed", "order", "order.confirmed", 10),
                    new MilestoneRegistry.Definition("kitchen_ready", "Kitchen Ready", "order", "fulfillment.completed",
                            p -> "kitchen".equals(p.path("strategy").asString("")), "first", 20)));
            TenantContext.set(TenantStore.organization("org-1"));
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        @Test
        @DisplayName("returns every declared milestone in sequence order, reached flag set from facts")
        void order() {
            facts.add(fact("order.confirmed", "2026-01-01T10:00:00Z", null));
            var out = svc.forEntity("order", "o1");
            assertThat(out.stream().map(m -> m.get("key")).toList()).containsExactly("confirmed", "kitchen_ready");
            assertThat(out.get(0)).containsEntry("reached", true).containsEntry("count", 1)
                    .containsEntry("occurredAt", Instant.parse("2026-01-01T10:00:00Z"));
            assertThat(out.get(1)).containsEntry("reached", false).containsEntry("count", 0);
        }

        @Test
        @DisplayName("discriminates shared event names by the match predicate")
        void match() {
            facts.add(fact("fulfillment.completed", "2026-01-01T10:05:00Z", "delivery"));
            facts.add(fact("fulfillment.completed", "2026-01-01T10:06:00Z", "kitchen"));
            var ready = svc.forEntity("order", "o1").get(1);
            assertThat(ready).containsEntry("reached", true).containsEntry("count", 1)
                    .containsEntry("occurredAt", Instant.parse("2026-01-01T10:06:00Z"));
        }

        @Test
        @DisplayName("records the FIRST matching fact — a recall does not overwrite the first ready")
        void first() {
            facts.add(fact("fulfillment.completed", "2026-01-01T10:00:00Z", "kitchen"));
            facts.add(fact("fulfillment.completed", "2026-01-01T10:09:00Z", "kitchen"));
            var ready = svc.forEntity("order", "o1").get(1);
            assertThat(ready).containsEntry("occurredAt", Instant.parse("2026-01-01T10:00:00Z")).containsEntry("count", 2);
        }

        @Test
        @DisplayName("returns [] for an entity type with no registered milestones")
        void none() {
            assertThat(svc.forEntity("invoice", "i1")).isEmpty();
            assertThat(calls).isZero();
        }
    }

    @Nested
    @DisplayName("ModuleRegistry")
    class Modules {
        @Test
        @DisplayName("accepts a valid dependency graph")
        void valid() {
            var r = ModuleRegistry.empty();
            r.register(new ModuleRegistry.ErpModule("kernel", "1.0.0", List.of()));
            r.register(new ModuleRegistry.ErpModule("core", "1.0.0", List.of("kernel")));
            r.validate();
        }

        @Test
        @DisplayName("throws on a missing dependency")
        void missing() {
            var r = ModuleRegistry.empty();
            r.register(new ModuleRegistry.ErpModule("core", "1.0.0", List.of("kernel")));
            assertThatThrownBy(r::validate).hasMessageContaining("missing module 'kernel'");
        }

        @Test
        @DisplayName("throws on a dependency cycle")
        void cycle() {
            var r = ModuleRegistry.empty();
            r.register(new ModuleRegistry.ErpModule("a", "1.0.0", List.of("b")));
            r.register(new ModuleRegistry.ErpModule("b", "1.0.0", List.of("a")));
            assertThatThrownBy(r::validate).hasMessageContaining("Cyclic");
        }

        @Test
        @DisplayName("rejects duplicate module registration")
        void duplicate() {
            var r = ModuleRegistry.empty();
            r.register(new ModuleRegistry.ErpModule("core", "1.0.0", List.of()));
            assertThatThrownBy(() -> r.register(new ModuleRegistry.ErpModule("core", "2.0.0", List.of())))
                    .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("order kinds: aggregates, accepts declared and rejects unknown")
        void orderKinds() {
            var r = ModuleRegistry.empty();
            r.register(new ModuleRegistry.ErpModule("pos", "1.0.0", List.of(), List.of(new ModuleRegistry.OrderKind("sale", "Sale"))));
            r.register(new ModuleRegistry.ErpModule("rental", "1.0.0", List.of(), List.of(new ModuleRegistry.OrderKind("rental", "Rental"))));
            r.register(new ModuleRegistry.ErpModule("repair", "1.0.0", List.of()));
            assertThat(r.knownOrderKinds()).containsExactlyInAnyOrder("rental", "sale");
            assertThat(r.isKnownOrderKind("rental")).isTrue();
            assertThat(r.isKnownOrderKind("hotel")).isFalse();
            r.assertKnownOrderKind("sale");
            assertThatThrownBy(() -> r.assertKnownOrderKind("hotel")).hasMessageContaining("Unknown order kind 'hotel'");
        }
    }
}
