package com.inventoryerp.kernel.events;

import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fire-and-forget facade over the outbox (port of {@code event-bus.ts}). {@link #publish} writes the
 * outbox row in its own transaction, so it survives a later rollback of the caller — tolerable for a
 * notification, wrong for a recorded fact: facts must use {@link EventOutboxService#publish} inside the
 * business transaction. Consumers must be idempotent.
 */
@Service
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final EventOutboxService outbox;
    private final OutboxHandlers handlers;
    private final TransactionTemplate newTx;

    public EventBus(EventOutboxService outbox, OutboxHandlers handlers, PlatformTransactionManager txm) {
        this.outbox = outbox;
        this.handlers = handlers;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void publish(String eventName, Map<String, Object> payload) {
        try {
            newTx.executeWithoutResult(s -> outbox.publish(eventName, payload));
        } catch (RuntimeException err) {
            log.error("Failed to publish {}: {}", eventName, err.toString());
        }
    }

    /** Subscribe an in-process handler, dispatched by the outbox worker (at-least-once). */
    public void subscribe(String eventName, Consumer<JsonNode> handler) {
        handlers.subscribe(eventName, handler);
    }
}
