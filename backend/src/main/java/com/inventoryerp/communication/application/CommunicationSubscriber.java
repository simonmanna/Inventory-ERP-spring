package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bridges domain events to the rule engine (port of {@code communication.subscriber.ts}). A FIXED catalog is
 * subscribed ({@link #RULE_EVENTABLE}); {@code CommunicationRule.eventName} is validated against it on write. Each
 * handler runs in the outbox worker without a tenant, so it re-establishes one from the payload's organizationId.
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class CommunicationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CommunicationSubscriber.class);

    /** The events a communication rule may bind to. Extend to add a trigger. */
    public static final List<String> RULE_EVENTABLE = List.of(
            // POS / sales
            "pos.sale.completed",
            "pos.order.created",
            "pos.order.invoiced",
            "pos.order.closed",
            "pos.invoice.settled",
            "pos.invoice.credited",
            // Orders lifecycle
            "order.confirmed",
            "order.completed",
            "order.cancelled",
            "fulfillment.completed",
            // Invoicing / payments
            "invoice.created",
            "invoice.posted",
            "invoice.paid",
            "payment.received",
            // Inventory
            "stock.received",
            "stock.issued",
            "stock.adjusted",
            // Cash
            "cash.session.opened",
            "cash.session.closed",
            // Procurement
            "purchase_order.approved",
            "goods_receipt.posted");

    private final EventBus events;
    private final RuleEngineService ruleEngine;
    private final JsonMapper mapper;

    public CommunicationSubscriber(EventBus events, RuleEngineService ruleEngine, JsonMapper mapper) {
        this.events = events;
        this.ruleEngine = ruleEngine;
        this.mapper = mapper;
    }

    @PostConstruct
    void subscribe() {
        for (String eventName : RULE_EVENTABLE) {
            events.subscribe(eventName, payload -> handle(eventName, payload));
        }
        log.info("subscribed to {} rule-eventable events", RULE_EVENTABLE.size());
    }

    void handle(String eventName, JsonNode node) {
        String organizationId = node.path("organizationId").isString() ? node.get("organizationId").asString() : "";
        if (organizationId.isEmpty()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.treeToValue(node, Map.class);
            TenantContext.run(TenantContext.TenantStore.organization(organizationId),
                    () -> ruleEngine.dispatch(eventName, payload));
        } catch (RuntimeException err) {
            log.warn("handling {} failed: {}", eventName, err.toString());
        }
    }
}
