package com.inventoryerp.kernel.workflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Milestone definitions — projections over the event ledger (port of {@code milestone.registry.ts}). */
@Component
public class MilestoneRegistry {

    /** {@code occurrence}: {@code first} (default) or {@code last}. */
    public record Definition(String key, String label, String entityType, String fromEvent, Predicate<JsonNode> match,
            String occurrence, int sequence) {

        public Definition(String key, String label, String entityType, String fromEvent, int sequence) {
            this(key, label, entityType, fromEvent, null, "first", sequence);
        }
    }

    private final Map<String, List<Definition>> byEntityType = new ConcurrentHashMap<>();

    public synchronized void register(Definition def) {
        List<Definition> list = new ArrayList<>(byEntityType.getOrDefault(def.entityType(), List.of()));
        if (list.stream().anyMatch(d -> d.key().equals(def.key()))) {
            throw new IllegalStateException("Milestone '" + def.key() + "' is already registered for '" + def.entityType() + "'");
        }
        list.add(def);
        list.sort(Comparator.comparingInt(Definition::sequence));
        byEntityType.put(def.entityType(), List.copyOf(list));
    }

    public void registerAll(List<Definition> defs) {
        defs.forEach(this::register);
    }

    public List<Definition> forEntityType(String entityType) {
        return byEntityType.getOrDefault(entityType, List.of());
    }
}
