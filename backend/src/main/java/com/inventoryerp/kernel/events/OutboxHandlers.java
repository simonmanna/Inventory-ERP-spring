package com.inventoryerp.kernel.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** In-process subscribers of outbox events ({@code EventOutboxService.on/dispatch} in Nest). */
@Component
public class OutboxHandlers {

    private static final Logger log = LoggerFactory.getLogger(OutboxHandlers.class);

    private final Map<String, List<Consumer<JsonNode>>> handlers = new ConcurrentHashMap<>();

    public void subscribe(String eventName, Consumer<JsonNode> handler) {
        handlers.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Run every handler for the row; a failing handler fails the dispatch (row retried). */
    public void dispatch(String eventName, JsonNode payload) {
        for (Consumer<JsonNode> h : handlers.getOrDefault(eventName, List.of())) {
            try {
                h.accept(payload);
            } catch (RuntimeException err) {
                log.error("Handler for {} failed: {}", eventName, err.toString());
                throw err;
            }
        }
    }
}
