package com.inventoryerp.kernel.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Fulfillment strategies (port of {@code fulfillment.registry.ts}); completion is derived, never stored. */
@Component
public class FulfillmentRegistry {

    /** {@code isComplete(orderId)} runs on the caller's transaction. */
    public record Strategy(String code, String label, Predicate<String> isComplete) {}

    private final Map<String, Strategy> byCode = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();

    public synchronized void register(Strategy strategy) {
        if (byCode.containsKey(strategy.code())) {
            throw new IllegalStateException("Fulfillment strategy '" + strategy.code() + "' is already registered");
        }
        byCode.put(strategy.code(), strategy);
        order.add(strategy.code());
    }

    public Strategy get(String code) {
        return byCode.get(code);
    }

    public synchronized List<Strategy> list() {
        return order.stream().map(byCode::get).toList();
    }
}
