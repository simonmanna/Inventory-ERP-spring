package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.providers.ChannelSecrets;
import com.inventoryerp.communication.application.providers.ProviderRegistry;
import com.inventoryerp.communication.domain.CommunicationChannel;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of CommunicationChannels (port of {@code channel.service.ts}). Channel config changes are the
 * only audited part of the communication domain. Credentials in {@code config} are sealed at rest and masked in
 * responses ({@link ChannelSecrets}).
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class ChannelService {

    private static final Set<String> VALID_PROVIDERS = Set.of("whatsapp", "telegram", "internal");

    /** Create input. */
    public record CreateInput(String providerId, String name, String transport, Map<String, Object> config) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ProviderRegistry providers;
    private final ChannelSecrets secrets;
    private final String whatsappTransport;

    public ChannelService(JdbcClient jdbc, AuditService audit, ProviderRegistry providers, ChannelSecrets secrets,
            @Value("${WHATSAPP_TRANSPORT:cloud}") String whatsappTransport) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.providers = providers;
        this.secrets = secrets;
        this.whatsappTransport = whatsappTransport;
    }

    /** All live channels for the org, each with best-effort live health from its provider. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("""
                        SELECT id, "providerId", transport, name, status, "desiredState", "pairedAt", "lastConnectedAt",
                               "lastError", "leaseHeartbeatAt", "dailySentCount"
                        FROM "CommunicationChannel" WHERE "disabledAt" IS NULL AND "organizationId" = ?
                        ORDER BY "providerId" ASC, name ASC""")
                .param(TenantContext.organizationId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String providerId = (String) r.get("providerId");
            Object health = null;
            if (providers.has(providerId)) {
                try {
                    health = providers.forId(providerId).health((String) r.get("id")).toJson();
                } catch (RuntimeException e) {
                    health = null;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("providerEnabled", providers.has(providerId));
            m.put("health", health);
            out.add(m);
        }
        return out;
    }

    /** Create a channel; the transport defaults per provider (whatsapp → WHATSAPP_TRANSPORT, telegram → bot). */
    @Transactional
    public Map<String, Object> create(CreateInput input) {
        String orgId = TenantContext.organizationId();
        if (!VALID_PROVIDERS.contains(input.providerId())) {
            throw ApiException.badRequest("Unknown provider '" + input.providerId() + "'.");
        }
        String transport = input.transport() != null ? input.transport()
                : "whatsapp".equals(input.providerId()) ? whatsappTransport
                : "telegram".equals(input.providerId()) ? "bot" : "internal";
        String id = UUID.randomUUID().toString();
        Map<String, Object> sealed = secrets.seal(input.config());
        jdbc.sql("""
                        INSERT INTO "CommunicationChannel" (id, "organizationId", "providerId", transport, name, config,
                          "createdBy", "updatedAt")
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(id, orgId, input.providerId(), transport, input.name(), secrets.toJson(sealed),
                        TenantContext.userId(), CommTime.nowTs())
                .update();
        audit.recordInTx(AuditInput.of("CommunicationChannel", id, "create", null,
                Obj.of("providerId", input.providerId(), "transport", transport, "name", input.name())));
        Map<String, Object> row = SqlRows.one(CommunicationChannel.class,
                jdbc.sql("SELECT * FROM \"CommunicationChannel\" WHERE id = ?").param(id));
        row.put("config", ChannelSecrets.mask(sealed));
        return row;
    }

    /** Connect via the provider (no-op for stateless transports). */
    @Transactional
    public Map<String, Object> connect(String id) {
        Map<String, Object> channel = require(id);
        String providerId = (String) channel.get("providerId");
        if (!providers.has(providerId)) {
            throw ApiException.badRequest("Provider '" + providerId + "' is not enabled on this server.");
        }
        providers.forId(providerId).connect(id);
        audit.record(AuditInput.of("CommunicationChannel", id, "update", null, Obj.of("action", "connect")));
        return Obj.of("ok", true);
    }

    @Transactional
    public Map<String, Object> disconnect(String id, boolean logout) {
        Map<String, Object> channel = require(id);
        String providerId = (String) channel.get("providerId");
        if (providers.has(providerId)) {
            providers.forId(providerId).disconnect(id, logout);
        } else {
            jdbc.sql("UPDATE \"CommunicationChannel\" SET \"desiredState\" = 'disconnected', status = 'disconnected',"
                            + " \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(CommTime.nowTs(), id, TenantContext.organizationId()).update();
        }
        audit.record(AuditInput.of("CommunicationChannel", id, "update", null,
                Obj.of("action", "disconnect", "logout", logout)));
        return Obj.of("ok", true);
    }

    /** The current pairing QR (raw) + status — SSE-independent fallback for the UI. */
    @Transactional(readOnly = true)
    public Map<String, Object> pairingState(String id) {
        Map<String, Object> c = require(id);
        return Obj.of("status", c.get("status"), "pairingQr", c.get("pairingQr"),
                "pairingQrExpiresAt", c.get("pairingQrExpiresAt"));
    }

    /** Soft-disable (and disconnect) a channel. */
    @Transactional
    public Map<String, Object> remove(String id) {
        require(id);
        jdbc.sql("UPDATE \"CommunicationChannel\" SET \"disabledAt\" = ?, \"desiredState\" = 'disconnected',"
                        + " status = 'disconnected', \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(CommTime.nowTs(), CommTime.nowTs(), id, TenantContext.organizationId()).update();
        audit.record(AuditInput.of("CommunicationChannel", id, "delete"));
        return Obj.of("ok", true);
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("""
                        SELECT id, "providerId", status, "pairingQr", "pairingQrExpiresAt" FROM "CommunicationChannel"
                        WHERE id = ? AND "disabledAt" IS NULL AND "organizationId" = ?""")
                .params(id, TenantContext.organizationId()));
        if (c == null) {
            throw ApiException.notFound("Channel not found.");
        }
        return c;
    }
}
