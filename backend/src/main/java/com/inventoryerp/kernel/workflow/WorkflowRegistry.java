package com.inventoryerp.kernel.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** In-memory registry of state machines (port of {@code workflow.registry.ts}). */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, WorkflowDefinition> defs = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();

    public synchronized void register(WorkflowDefinition definition) {
        if (defs.containsKey(definition.documentType())) {
            throw new IllegalStateException("Workflow for '" + definition.documentType() + "' is already registered");
        }
        defs.put(definition.documentType(), definition);
        order.add(definition.documentType());
        log.info("Registered workflow '{}' (initial={}, {} transitions)", definition.documentType(), definition.initial(),
                definition.transitions().size());
    }

    public WorkflowDefinition get(String documentType) {
        return defs.get(documentType);
    }

    public synchronized List<WorkflowDefinition> list() {
        return order.stream().map(defs::get).toList();
    }
}
