package com.inventoryerp.kernel.events;

import com.inventoryerp.kernel.common.ContractCatalogs;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional event outbox (port of {@code event-outbox.service.ts}). {@link #publish} writes an
 * {@code EventOutbox} row (transport, shipped by {@link OutboxWorker}) and, for declared business facts
 * ({@code EVENT_SUBJECT}), an append-only {@code DomainEventLog} row, inside the caller's transaction
 * (or a fresh one). It also publishes a {@link DomainEvent} for Modulith listeners.
 */
@Service
public class EventOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxService.class);

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final ApplicationEventPublisher publisher;

    public EventOutboxService(JdbcClient jdbc, JsonMapper mapper, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.publisher = publisher;
    }

    /** Write the outbox row (+ ledger row for declared facts) in the current transaction. */
    @Transactional
    public void publish(String eventName, Map<String, Object> payload) {
        String organizationId = TenantContext.optionalOrganizationId();
        String org = organizationId == null ? "" : organizationId;
        String json = mapper.writeValueAsString(payload);
        jdbc.sql("""
                        INSERT INTO "EventOutbox" (id, "organizationId", "eventName", payload, status, attempts, "createdAt",
                          "actorId")
                        VALUES (?, ?, ?, ?::jsonb, 'pending', 0, now(), ?)""")
                .params(UUID.randomUUID().toString(), org, eventName, json, TenantContext.userId())
                .update();
        recordFact(org, eventName, payload, json);
        publisher.publishEvent(new DomainEvent(eventName, organizationId, payload));
    }

    private void recordFact(String organizationId, String eventName, Map<String, Object> payload, String json) {
        var subject = ContractCatalogs.eventSubjects().get(eventName);
        if (subject == null || organizationId.isEmpty()) {
            return;
        }
        Object entityId = payload == null ? null : payload.get(subject.idField());
        if (!(entityId instanceof String id) || id.isEmpty()) {
            log.warn("Event '{}' declares subject field '{}' but the payload has no such string — no ledger row written.",
                    eventName, subject.idField());
            return;
        }
        jdbc.sql("""
                        INSERT INTO "DomainEventLog" ("organizationId", "eventName", "entityType", "entityId", "actorId", payload, "occurredAt")
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, now())""")
                .params(organizationId, eventName, subject.entityType(), id, TenantContext.userId(), json)
                .update();
    }
}
