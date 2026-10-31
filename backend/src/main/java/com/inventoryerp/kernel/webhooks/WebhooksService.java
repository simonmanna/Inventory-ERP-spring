package com.inventoryerp.kernel.webhooks;

import com.inventoryerp.kernel.domain.WebhookEndpoint;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound webhooks (port of {@code webhooks.service.ts}): HMAC-SHA256 over {@code timestamp.body},
 * header {@code X-ERP-Signature: t=<ts>,v1=<hex>}; backoff 1m, 5m, 30m, 2h, 6h, 24h up to 6 attempts then
 * {@code dead}. As in Nest, the tick only picks {@code pending} deliveries.
 */
@Service
public class WebhooksService {

    static final int MAX_ATTEMPTS = 6;
    static final long[] BACKOFF_MS = {60_000, 5 * 60_000, 30 * 60_000, 2 * 60 * 60_000, 6 * 60 * 60_000, 24 * 60 * 60_000};

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final WebhookUrlGuard urlGuard;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebhooksService(EntityManager em, JdbcClient jdbc, JsonMapper mapper, WebhookUrlGuard urlGuard) {
        this.em = em;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.urlGuard = urlGuard;
    }

    private String newSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Transactional
    public Map<String, Object> createEndpoint(String url, List<String> events, String description) {
        urlGuard.check(url);
        WebhookEndpoint e = new WebhookEndpoint();
        e.setOrganizationId(TenantContext.organizationId());
        e.setUrl(url);
        e.setEvents(new ArrayList<>(events == null ? List.of() : events));
        e.setSigningSecret(newSecret());
        e.setDescription(description);
        e.setCreatedById(TenantContext.userId());
        em.persist(e);
        em.flush();
        em.refresh(e);
        return EntityJson.of(e);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEndpoints() {
        return EntityJson.list(em.createQuery("select e from WebhookEndpoint e where e.organizationId = :org"
                        + " and e.deletedAt is null order by e.createdAt desc", WebhookEndpoint.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    @Transactional
    public Map<String, Object> rotateSecret(String id) {
        WebhookEndpoint e = endpoint(id);
        e.setSigningSecret(newSecret());
        em.flush();
        return EntityJson.of(e);
    }

    @Transactional
    public void deleteEndpoint(String id) {
        WebhookEndpoint e = endpoint(id);
        e.setDeletedAt(Instant.now());
        e.setIsActive(false);
    }

    private WebhookEndpoint endpoint(String id) {
        var rows = em.createQuery("select e from WebhookEndpoint e where e.id = :id and e.organizationId = :org",
                        WebhookEndpoint.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        if (rows.isEmpty()) {
            // Prisma update() on a missing row: unhandled P2025 (500) in Nest.
            throw new IllegalStateException("No WebhookEndpoint found for id " + id);
        }
        return rows.getFirst();
    }

    /** Queue a delivery for every matching active endpoint of the current org. */
    public Map<String, Object> enqueue(String eventName, Map<String, Object> payload) {
        String orgId = TenantContext.organizationId();
        record Ep(String id, List<String> events) {}
        List<Ep> eps = jdbc.sql("""
                        SELECT id, events FROM "WebhookEndpoint" WHERE "organizationId" = ? AND "isActive" = true AND "deletedAt" IS NULL""")
                .param(orgId)
                .query((rs, n) -> new Ep(rs.getString("id"), com.inventoryerp.kernel.auth.UserAccounts.array(rs, "events")))
                .list();
        int queued = 0;
        for (Ep e : eps) {
            if (!e.events().isEmpty() && !e.events().contains(eventName)) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO "WebhookDelivery" (id, "organizationId", "endpointId", "eventName", payload, status, "nextAttemptAt")
                            VALUES (?, ?, ?, ?, ?::jsonb, 'pending', now())""")
                    .params(UUID.randomUUID().toString(), orgId, e.id(), eventName, mapper.writeValueAsString(payload))
                    .update();
            queued++;
        }
        return Map.of("queued", queued);
    }

    public static String signBody(String secret, String body, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Attempt due deliveries (all orgs). */
    public Map<String, Integer> tick() {
        record Due(String id, String eventName, String payload, int attempts, String url, String secret,
                boolean active) {}
        List<Due> due = jdbc.sql("""
                        SELECT d.id, d."eventName", d.payload::text AS payload, d.attempts, e.url,
                               e."signingSecret", e."isActive"
                        FROM "WebhookDelivery" d JOIN "WebhookEndpoint" e ON e.id = d."endpointId"
                        WHERE d.status = 'pending' AND d."nextAttemptAt" <= now() LIMIT 100""")
                .query((rs, n) -> new Due(rs.getString("id"), rs.getString("eventName"), rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"), rs.getString("signingSecret"),
                        rs.getBoolean("isActive")))
                .list();
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        for (Due d : due) {
            if (!d.active()) {
                continue;
            }
            attempted++;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", d.id());
            envelope.put("event", d.eventName());
            // WebhookDelivery has no createdAt column: Nest's JSON.stringify drops the undefined key.
            envelope.put("data", d.payload() == null ? null : mapper.readTree(d.payload()));
            String body = mapper.writeValueAsString(envelope);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String sig = signBody(d.secret(), body, ts);
            int attempts = d.attempts() + 1;
            String blocked = urlGuard.problem(d.url());
            if (blocked != null) {
                scheduleRetry(d.id(), MAX_ATTEMPTS, null, null, blocked);
                failed++;
                continue;
            }
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(d.url()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-ERP-Event", d.eventName())
                        .header("X-ERP-Delivery", d.id())
                        .header("X-ERP-Timestamp", ts)
                        .header("X-ERP-Signature", "t=" + ts + ",v1=" + sig)
                        .header("User-Agent", "ERP-Webhooks/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(), HttpResponse.BodyHandlers.ofString());
                String text = res.body() == null ? "" : res.body();
                text = text.length() > 2000 ? text.substring(0, 2000) : text;
                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    jdbc.sql("""
                                    UPDATE "WebhookDelivery" SET status = 'succeeded', attempts = ?, "succeededAt" = now(),
                                      "responseStatus" = ?, "responseBody" = ?, "lastAttemptAt" = now(), "nextAttemptAt" = NULL WHERE id = ?""")
                            .params(attempts, res.statusCode(), text, d.id())
                            .update();
                    succeeded++;
                } else {
                    scheduleRetry(d.id(), attempts, res.statusCode(), text, "HTTP " + res.statusCode());
                    failed++;
                }
            } catch (Exception err) {
                if (err instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                scheduleRetry(d.id(), attempts, null, null, err.toString());
                failed++;
            }
        }
        return Map.of("attempted", attempted, "succeeded", succeeded, "failed", failed);
    }

    private void scheduleRetry(String id, int attempts, Integer status, String body, String error) {
        boolean dead = attempts >= MAX_ATTEMPTS;
        long backoff = attempts - 1 < BACKOFF_MS.length ? BACKOFF_MS[attempts - 1] : 60_000;
        jdbc.sql("""
                        UPDATE "WebhookDelivery" SET attempts = ?, status = ?::"WebhookEventStatus", "responseStatus" = ?,
                          "responseBody" = ?, error = ?, "lastAttemptAt" = now(), "nextAttemptAt" = ? WHERE id = ?""")
                .params(attempts, dead ? "dead" : "failed", status, body, error,
                        dead ? null : Timestamp.from(Instant.now().plusMillis(backoff)), id)
                .update();
    }

    /** Deliveries with their endpoint, newest id first, 200 max. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeliveries(String endpointId) {
        String orgId = TenantContext.organizationId();
        var rows = em.createQuery("select d from WebhookDelivery d where d.organizationId = :org"
                        + (endpointId != null ? " and d.endpointId = :ep" : "") + " order by d.id desc",
                        com.inventoryerp.kernel.domain.WebhookDelivery.class)
                .setParameter("org", orgId)
                .setMaxResults(200);
        if (endpointId != null) {
            rows.setParameter("ep", endpointId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var d : rows.getResultList()) {
            Map<String, Object> m = EntityJson.of(d);
            m.put("endpoint", EntityJson.of(em.find(WebhookEndpoint.class, d.getEndpointId())));
            out.add(m);
        }
        return out;
    }
}
