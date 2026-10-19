package com.inventoryerp.kernel.workflow;

import java.util.List;
import java.util.Map;

/**
 * Declarative state machine (port of packages/shared/src/workflow.ts, ADR-007).
 *
 * @param documentType entity type key, e.g. {@code invoice}
 */
public record WorkflowDefinition(String documentType, String initial, List<Transition> transitions) {

    /** Runtime context passed to guards and side effects. */
    public record Context(String entityType, String entityId, String organizationId, String userId,
            List<String> permissions, String action, String fromState, String toState, Map<String, Object> payload,
            Map<String, Object> entity) {}

    @FunctionalInterface
    public interface Guard {
        boolean test(Context ctx);
    }

    @FunctionalInterface
    public interface SideEffect {
        void run(Context ctx);
    }

    /** One transition; {@code event} names the typed business fact it emits (else {@code type.action}). */
    public record Transition(String from, String to, String action, String permission, Guard guard, SideEffect sideEffect,
            String event) {

        public Transition(String from, String to, String action) {
            this(from, to, action, null, null, null, null);
        }

        public Transition(String from, String to, String action, String permission) {
            this(from, to, action, permission, null, null, null);
        }

        public static Transition withEvent(String from, String to, String action, String event) {
            return new Transition(from, to, action, null, null, null, event);
        }
    }
}
