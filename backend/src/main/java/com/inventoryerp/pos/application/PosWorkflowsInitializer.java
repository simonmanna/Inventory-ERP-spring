package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.workflow.FulfillmentRegistry;
import com.inventoryerp.kernel.workflow.MilestoneRegistry;
import com.inventoryerp.kernel.workflow.WorkflowDefinition;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Guard;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Transition;
import com.inventoryerp.kernel.workflow.WorkflowRegistry;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Registers the {@code order} workflow, its milestones and the kitchen fulfillment strategy (port of
 * {@code pos.workflows.ts}, {@code pos.milestones.ts}, {@code pos.fulfillment.ts}).
 */
@Component
public class PosWorkflowsInitializer {

    private static final Map<String, List<String>> LEGACY_ALIASES = Map.of(
            "confirmed", List.of("open"),
            "in_progress", List.of("preparing", "ready"),
            "completed", List.of("served"));

    private final WorkflowRegistry workflows;
    private final MilestoneRegistry milestones;
    private final FulfillmentRegistry fulfillment;
    private final JdbcClient jdbc;

    public PosWorkflowsInitializer(WorkflowRegistry workflows, MilestoneRegistry milestones,
            FulfillmentRegistry fulfillment, JdbcClient jdbc) {
        this.workflows = workflows;
        this.milestones = milestones;
        this.fulfillment = fulfillment;
        this.jdbc = jdbc;
    }

    private static List<Transition> withLegacyFromStates(List<Transition> transitions) {
        List<Transition> out = new ArrayList<>();
        for (Transition t : transitions) {
            out.add(t);
            for (String legacy : LEGACY_ALIASES.getOrDefault(t.from(), List.of())) {
                out.add(new Transition(legacy, t.to(), t.action(), t.permission(), t.guard(), t.sideEffect(), t.event()));
            }
        }
        return out;
    }

    private static Transition t(String from, String to, String action, String permission, Guard guard, String event) {
        return new Transition(from, to, action, permission, guard, null, event);
    }

    @PostConstruct
    void register() {
        Guard notBilled = ctx -> ctx.entity() == null || ctx.entity().get("invoiceId") == null;
        workflows.register(new WorkflowDefinition("order", "draft", withLegacyFromStates(List.of(
                t("draft", "confirmed", "confirm", "pos:checkout", null, DomainEvents.ORDER_CONFIRMED),
                t("confirmed", "in_progress", "start_fulfillment", "pos:checkout", null,
                        DomainEvents.ORDER_FULFILLMENT_STARTED),
                t("confirmed", "completed", "complete", null, null, DomainEvents.ORDER_COMPLETED),
                t("in_progress", "completed", "complete", null, null, DomainEvents.ORDER_COMPLETED),
                t("completed", "closed", "close", null, null, DomainEvents.ORDER_CLOSED),
                t("confirmed", "closed", "close", null, null, DomainEvents.ORDER_CLOSED),
                t("in_progress", "closed", "close", null, null, DomainEvents.ORDER_CLOSED),
                t("draft", "cancelled", "cancel", "pos:checkout", notBilled, DomainEvents.ORDER_CANCELLED),
                t("confirmed", "cancelled", "cancel", "pos:checkout", notBilled, DomainEvents.ORDER_CANCELLED),
                t("in_progress", "cancelled", "cancel", "pos:checkout", notBilled, DomainEvents.ORDER_CANCELLED),
                t("confirmed", "cancelled", "supersede", null, notBilled, DomainEvents.ORDER_SUPERSEDED),
                t("in_progress", "cancelled", "supersede", null, notBilled, DomainEvents.ORDER_SUPERSEDED),
                t("cancelled", "confirmed", "reopen", "pos:override", notBilled, DomainEvents.ORDER_REOPENED)))));

        java.util.function.Predicate<JsonNode> isKitchen = p -> p != null && "kitchen".equals(
                p.get("strategy") == null ? null : p.get("strategy").asString());
        milestones.registerAll(List.of(
                new MilestoneRegistry.Definition("confirmed", "Order Confirmed", "order", DomainEvents.ORDER_CONFIRMED, 10),
                new MilestoneRegistry.Definition("kitchen_started", "Kitchen Started", "order",
                        DomainEvents.FULFILLMENT_STARTED, isKitchen, "first", 20),
                new MilestoneRegistry.Definition("kitchen_ready", "Kitchen Ready", "order",
                        DomainEvents.FULFILLMENT_COMPLETED, isKitchen, "first", 30),
                new MilestoneRegistry.Definition("billed", "Billed", "order", DomainEvents.ORDER_COMPLETED, 40),
                new MilestoneRegistry.Definition("closed", "Settled & Closed", "order", DomainEvents.ORDER_CLOSED, 50)));

        fulfillment.register(new FulfillmentRegistry.Strategy("kitchen", "Kitchen", orderId -> jdbc.sql(
                        "SELECT count(*) FROM \"KitchenTicket\" WHERE \"orderId\" = ?"
                                + " AND status::text NOT IN ('ready', 'served', 'cancelled')")
                .param(orderId).query(Long.class).single() == 0));
    }
}
