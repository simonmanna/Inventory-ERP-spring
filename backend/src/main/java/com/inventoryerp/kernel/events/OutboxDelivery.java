package com.inventoryerp.kernel.events;

import tools.jackson.databind.JsonNode;

/**
 * One delivery of an outbox row to an in-process subscriber: the row id (stable across retries, so a subscriber can
 * claim it once), the event name, the user whose request published it ({@code null} for jobs and system work) and the
 * payload.
 */
public record OutboxDelivery(String id, String eventName, String actorId, JsonNode payload) {}
