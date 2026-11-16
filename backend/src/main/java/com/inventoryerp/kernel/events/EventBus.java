package com.inventoryerp.kernel.events;

import com.inventoryerp.kernel.common.DetachedWrites;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fire-and-forget facade over the outbox (port of {@code event-bus.ts}). {@link #publish} writes the
 * outbox row in its own transaction, so it survives a later rollback of the caller — tolerable for a
 * notification, wrong for a recorded fact: facts must use {@link EventOutboxService#publish} inside the
 * business transaction. Consumers must be idempotent.
 *
 * <p>Nest does {@code void this.outbox.publish(...)}: the caller never waits. Inside a transaction the write is
 * deferred until that transaction completes ({@link DetachedWrites}), so it never holds two pooled connections.
 */
@Service
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final EventOutboxService outbox;
    private final OutboxHandlers handlers;
    private final DetachedWrites detached;
    private final TransactionTemplate newTx;

    public EventBus(EventOutboxService outbox, OutboxHandlers handlers, DetachedWrites detached,
            PlatformTransactionManager txm) {
        this.outbox = outbox;
        this.handlers = handlers;
        this.detached = detached;
        // REQUIRED: a detached write never runs inside the caller's transaction (DetachedWrites), but joins a batch flush.
        this.newTx = new TransactionTemplate(txm);
    }

    public void publish(String eventName, Map<String, Object> payload) {
        detached.submit(() -> {
            try {
                newTx.executeWithoutResult(s -> outbox.publish(eventName, payload));
            } catch (RuntimeException err) {
                log.error("Failed to publish {}: {}", eventName, err.toString());
            }
        });
    }

    /**
     * Like {@link #publish}, but only once the caller's transaction commits (dropped on rollback, and when the
     * {@link com.inventoryerp.kernel.common.Savepoints} block it was published in rolls back): for events that
     * announce a fact of that transaction, such as a stock movement. Outside a transaction it publishes at once.
     */
    public void publishOnCommit(String eventName, Map<String, Object> payload) {
        detached.submitOnCommit(() -> {
            try {
                newTx.executeWithoutResult(s -> outbox.publish(eventName, payload));
            } catch (RuntimeException err) {
                log.error("Failed to publish {}: {}", eventName, err.toString());
            }
        });
    }

    /** Subscribe an in-process handler, dispatched by the outbox worker (at-least-once). */
    public void subscribe(String eventName, Consumer<JsonNode> handler) {
        handlers.subscribe(eventName, handler);
    }
}
