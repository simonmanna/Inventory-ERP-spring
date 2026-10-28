package com.inventoryerp.communication.application;

import com.inventoryerp.communication.domain.CommunicationRule;
import com.inventoryerp.communication.domain.MessageTemplate;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin CRUD for message templates and communication rules (port of
 * {@code rules/communication-config.service.ts}). Templates are versioned per {@code (key, providerId, locale)}: a
 * re-save adds a version and deactivates the prior ones.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class CommunicationConfigService {

    /** Template input. */
    public record TemplateInput(String key, String eventName, String providerId, String locale, String subject,
            String body, Boolean active) {}

    /** Rule input. */
    public record RuleInput(String eventName, String templateKey, String recipientResolver, String channelSelector,
            Map<String, Object> condition, Boolean enabled) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final JsonMapper mapper;

    public CommunicationConfigService(JdbcClient jdbc, AuditService audit, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
    }

    /* ── Templates ──────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates() {
        return SqlRows.list(MessageTemplate.class, jdbc.sql("SELECT * FROM \"MessageTemplate\" WHERE \"organizationId\" = ?"
                + " ORDER BY key ASC, version DESC").param(TenantContext.organizationId()));
    }

    @Transactional
    public Map<String, Object> upsertTemplate(TemplateInput input) {
        String orgId = TenantContext.organizationId();
        List<String> variables = TemplateRender.extractVariables(input.body());
        String locale = input.locale() == null ? "en" : input.locale();
        String providerId = input.providerId();
        Integer latest = jdbc.sql("""
                        SELECT version FROM "MessageTemplate" WHERE "organizationId" = ? AND key = ?
                          AND "providerId" IS NOT DISTINCT FROM ? AND locale = ?
                        ORDER BY version DESC LIMIT 1""")
                .params(orgId, input.key(), providerId, locale).query(Integer.class).optional().orElse(null);
        int version = (latest == null ? 0 : latest) + 1;
        // Deactivate prior versions so exactly one is active per (key, provider, locale).
        jdbc.sql("""
                        UPDATE "MessageTemplate" SET active = false, "updatedAt" = ?
                        WHERE "organizationId" = ? AND key = ? AND "providerId" IS NOT DISTINCT FROM ? AND locale = ?""")
                .params(CommTime.nowTs(), orgId, input.key(), providerId, locale).update();
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO "MessageTemplate" (id, "organizationId", key, "eventName", "providerId", locale,
                          version, subject, body, variables, active, "updatedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, orgId, input.key(), input.eventName(), providerId, locale, version, input.subject(),
                        input.body(), variables.toArray(String[]::new), input.active() == null || input.active(),
                        CommTime.nowTs())
                .update();
        audit.recordInTx(AuditInput.of("MessageTemplate", id, "create", null,
                Obj.of("key", input.key(), "version", version, "providerId", providerId, "locale", locale)));
        return SqlRows.one(MessageTemplate.class, jdbc.sql("SELECT * FROM \"MessageTemplate\" WHERE id = ?").param(id));
    }

    /* ── Rules ──────────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules() {
        return SqlRows.list(CommunicationRule.class, jdbc.sql("SELECT * FROM \"CommunicationRule\""
                + " WHERE \"organizationId\" = ? ORDER BY \"eventName\" ASC").param(TenantContext.organizationId()));
    }

    /** The events a rule may bind to (fixed catalog). */
    public List<String> eventableEvents() {
        return CommunicationSubscriber.RULE_EVENTABLE;
    }

    @Transactional
    public Map<String, Object> upsertRule(RuleInput input) {
        String orgId = TenantContext.organizationId();
        if (!CommunicationSubscriber.RULE_EVENTABLE.contains(input.eventName())) {
            throw ApiException.badRequest("Event '" + input.eventName() + "' is not rule-eventable. Allowed: "
                    + String.join(", ", CommunicationSubscriber.RULE_EVENTABLE));
        }
        String selector = input.channelSelector() == null ? "internal" : input.channelSelector();
        String condition = mapper.writeValueAsString(input.condition() == null ? Map.of() : input.condition());
        boolean enabled = input.enabled() == null || input.enabled();
        return SqlRows.one(CommunicationRule.class, jdbc.sql("""
                        INSERT INTO "CommunicationRule" (id, "organizationId", "eventName", condition, "templateKey",
                          "recipientResolver", "channelSelector", enabled, "updatedAt")
                        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                        ON CONFLICT ("organizationId", "eventName", "templateKey") DO UPDATE SET
                          "recipientResolver" = EXCLUDED."recipientResolver",
                          "channelSelector" = EXCLUDED."channelSelector",
                          condition = EXCLUDED.condition, enabled = EXCLUDED.enabled,
                          "updatedAt" = EXCLUDED."updatedAt"
                        RETURNING *""")
                .params(UUID.randomUUID().toString(), orgId, input.eventName(), condition, input.templateKey(),
                        input.recipientResolver(), selector, enabled, CommTime.nowTs()));
    }

    @Transactional
    public Map<String, Object> setRuleEnabled(String id, boolean enabled) {
        int n = jdbc.sql("UPDATE \"CommunicationRule\" SET enabled = ?, \"updatedAt\" = ? WHERE id = ?"
                        + " AND \"organizationId\" = ?")
                .params(enabled, CommTime.nowTs(), id, TenantContext.organizationId()).update();
        requireOne(n);
        return Obj.of("ok", true);
    }

    @Transactional
    public Map<String, Object> deleteRule(String id) {
        int n = jdbc.sql("DELETE FROM \"CommunicationRule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, TenantContext.organizationId()).update();
        requireOne(n);
        return Obj.of("ok", true);
    }

    /** Prisma {@code update}/{@code delete} of a missing row throws P2025, which Nest answers with a 500. */
    private static void requireOne(int n) {
        if (n == 0) {
            throw new IllegalStateException("No record was found for an update.");
        }
    }
}
