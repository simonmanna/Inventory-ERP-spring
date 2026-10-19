package com.inventoryerp.kernel.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Collects module manifests and validates the dependency graph at boot (port of
 * {@code module-registry.service.ts}, ADR-005): missing dependencies and cycles fail; the resolved
 * load order is logged. Also aggregates {@code Order.transactionKind} codes contributed by modules.
 */
@Component
public class ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    /** An order kind a module owns. */
    public record OrderKind(String code, String label) {}

    /** Manifest every pluggable module registers. */
    public record ErpModule(String name, String version, List<String> dependencies, List<OrderKind> orderKinds) {
        public ErpModule(String name, String version, List<String> dependencies) {
            this(name, version, dependencies, List.of());
        }
    }

    private final Map<String, ErpModule> modules = new LinkedHashMap<>();

    public ModuleRegistry() {
        register(new ErpModule("kernel", "1.5.0", List.of()));
    }

    /** A registry without the built-in kernel manifest (tests). */
    public static ModuleRegistry empty() {
        ModuleRegistry r = new ModuleRegistry();
        r.modules.clear();
        return r;
    }

    public synchronized void register(ErpModule manifest) {
        if (modules.containsKey(manifest.name())) {
            throw new IllegalStateException("Duplicate module registration: '" + manifest.name() + "'");
        }
        modules.put(manifest.name(), manifest);
    }

    public synchronized List<ErpModule> list() {
        return List.copyOf(modules.values());
    }

    public synchronized boolean has(String name) {
        return modules.containsKey(name);
    }

    public synchronized Set<String> knownOrderKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        for (ErpModule m : modules.values()) {
            for (OrderKind k : m.orderKinds()) {
                kinds.add(k.code());
            }
        }
        return kinds;
    }

    public boolean isKnownOrderKind(String code) {
        return knownOrderKinds().contains(code);
    }

    public void assertKnownOrderKind(String code) {
        if (!isKnownOrderKind(code)) {
            String known = String.join(", ", new TreeSet<>(knownOrderKinds()));
            throw new IllegalStateException("Unknown order kind '" + code + "'. Registered kinds: "
                    + (known.isEmpty() ? "(none registered)" : known) + ".");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        validate();
    }

    public synchronized void validate() {
        for (ErpModule mod : modules.values()) {
            for (String dep : mod.dependencies()) {
                if (!modules.containsKey(dep)) {
                    throw new IllegalStateException(
                            "Module '" + mod.name() + "' depends on missing module '" + dep + "'");
                }
            }
        }
        List<String> order = topologicalSort();
        log.info("Loaded {} module(s): {}", modules.size(), String.join(" -> ", order));
    }

    private List<String> topologicalSort() {
        Map<String, String> state = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (String name : modules.keySet()) {
            visit(name, new ArrayList<>(), state, order);
        }
        return order;
    }

    private void visit(String name, List<String> stack, Map<String, String> state, List<String> order) {
        String current = state.get(name);
        if ("done".equals(current)) {
            return;
        }
        if ("visiting".equals(current)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(name);
            throw new IllegalStateException("Cyclic module dependency detected: " + String.join(" -> ", cycle));
        }
        state.put(name, "visiting");
        for (String dep : modules.get(name).dependencies()) {
            List<String> next = new ArrayList<>(stack);
            next.add(name);
            visit(dep, next, state, order);
        }
        state.put(name, "done");
        order.add(name);
    }
}
