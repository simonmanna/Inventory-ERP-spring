package com.inventoryerp.kernel.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Custody lifecycle definitions (port of {@code lifecycle.registry.ts} / {@code lifecycle.types.ts}). */
@Component
public class LifecycleRegistry {

    private static final Logger log = LoggerFactory.getLogger(LifecycleRegistry.class);

    /** One guarded transition; {@code from == null} means any state. */
    public record Transition(String from, String to, String action) {}

    /** A registered lifecycle. */
    public record Definition(String key, String entityType, List<String> states, List<Transition> transitions,
            List<String> terminalStates) {}

    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    public void register(Definition def) {
        if (definitions.containsKey(def.key())) {
            log.warn("Lifecycle definition '{}' already registered — overwriting", def.key());
        }
        definitions.put(def.key(), def);
        log.info("Registered lifecycle '{}' ({} states)", def.key(), def.states().size());
    }

    public Definition get(String key) {
        return definitions.get(key);
    }

    public void assertTransition(String defKey, String from, String to, String action) {
        Definition def = definitions.get(defKey);
        if (def == null) {
            throw new IllegalStateException("Unknown lifecycle definition '" + defKey
                    + "' — register it in a module's onModuleInit");
        }
        String valid = String.join(", ", def.states());
        if (from != null && !def.states().contains(from)) {
            throw new IllegalStateException("Lifecycle '" + defKey + "': unknown from-state '" + from + "' (valid: " + valid + ")");
        }
        if (!def.states().contains(to)) {
            throw new IllegalStateException("Lifecycle '" + defKey + "': unknown to-state '" + to + "' (valid: " + valid + ")");
        }
        if (def.terminalStates().contains(from == null ? "" : from)) {
            throw new IllegalStateException("Lifecycle '" + defKey + "': state '" + from + "' is terminal, no further transitions");
        }
        boolean legal = def.transitions().stream().anyMatch(t -> t.to().equals(to) && t.action().equals(action)
                && (t.from() == null || t.from().equals(from)));
        if (!legal) {
            throw new IllegalStateException("Illegal lifecycle transition '" + defKey + "': "
                    + (from == null ? "(initial)" : from) + " --" + action + "--> " + to);
        }
    }
}
