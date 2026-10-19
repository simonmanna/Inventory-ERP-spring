package com.inventoryerp.kernel.workflow;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Projects an entity's recorded facts into its milestones (port of {@code milestone.service.ts}). */
@Service
public class MilestoneService {

    /** One recorded fact. */
    public record Fact(String eventName, Instant occurredAt, String actorId, JsonNode payload) {}

    /** Fact source, swappable in unit tests. */
    public interface FactSource {
        List<Fact> facts(String organizationId, String entityType, String entityId);
    }

    private final FactSource source;
    private final MilestoneRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public MilestoneService(JdbcClient jdbc, JsonMapper mapper, MilestoneRegistry registry) {
        this((org, type, id) -> jdbc.sql("""
                        SELECT "eventName", "occurredAt", "actorId", payload::text AS payload FROM "DomainEventLog"
                        WHERE "organizationId" = ? AND "entityType" = ? AND "entityId" = ? ORDER BY "occurredAt" ASC""")
                .params(org, type, id)
                .query((rs, n) -> new Fact(rs.getString("eventName"), rs.getTimestamp("occurredAt").toInstant(),
                        rs.getString("actorId"), rs.getString("payload") == null ? null : mapper.readTree(rs.getString("payload"))))
                .list(), registry);
    }

    public MilestoneService(FactSource source, MilestoneRegistry registry) {
        this.source = source;
        this.registry = registry;
    }

    public List<Map<String, Object>> forEntity(String entityType, String entityId) {
        List<MilestoneRegistry.Definition> defs = registry.forEntityType(entityType);
        if (defs.isEmpty()) {
            return List.of();
        }
        List<Fact> facts = source.facts(TenantContext.organizationId(), entityType, entityId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var def : defs) {
            List<Fact> matches = facts.stream()
                    .filter(f -> f.eventName().equals(def.fromEvent()) && (def.match() == null || def.match().test(f.payload())))
                    .toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", def.key());
            m.put("label", def.label());
            m.put("sequence", def.sequence());
            if (matches.isEmpty()) {
                m.put("reached", false);
                m.put("occurredAt", null);
                m.put("actorId", null);
                m.put("count", 0);
            } else {
                Fact chosen = "last".equals(def.occurrence()) ? matches.getLast() : matches.getFirst();
                m.put("reached", true);
                m.put("occurredAt", chosen.occurredAt());
                m.put("actorId", chosen.actorId());
                m.put("count", matches.size());
            }
            out.add(m);
        }
        return out;
    }
}
