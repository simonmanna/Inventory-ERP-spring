package com.inventoryerp.shared.events;

import java.util.Map;

/**
 * Application event published (after the outbox row is written) for every domain event, so modules can
 * react with {@code @ApplicationModuleListener}. Name from {@link DomainEvents}.
 */
public record DomainEvent(String name, String organizationId, Map<String, Object> payload) {}
