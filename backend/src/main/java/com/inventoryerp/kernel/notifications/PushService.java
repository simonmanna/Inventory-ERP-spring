package com.inventoryerp.kernel.notifications;

import com.inventoryerp.kernel.domain.PushSubscription;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push (VAPID) delivery (port of {@code push.service.ts}). With VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY
 * set, pushes are really sent; otherwise an ephemeral keypair is generated at boot so the subscription
 * API still works, and delivery is logged instead. 404/410 from a push service revokes the
 * subscription.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final String configuredPublic;
    private final String configuredPrivate;
    private String subject;
    private String publicKey = "";
    private String privateKey = "";
    private boolean configured;
    private nl.martijndwars.webpush.PushService sender;

    public PushService(EntityManager em, JdbcClient jdbc, JsonMapper mapper,
            @Value("${VAPID_PUBLIC_KEY:}") String publicKey,
            @Value("${VAPID_PRIVATE_KEY:}") String privateKey,
            @Value("${VAPID_SUBJECT:mailto:admin@cafe-pos.local}") String subject) {
        this.em = em;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.configuredPublic = publicKey;
        this.configuredPrivate = privateKey;
        this.subject = subject;
    }

    @PostConstruct
    void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (!configuredPublic.isEmpty() && !configuredPrivate.isEmpty()) {
            try {
                sender = new nl.martijndwars.webpush.PushService(configuredPublic, configuredPrivate, subject);
                publicKey = configuredPublic;
                privateKey = configuredPrivate;
                configured = true;
                log.info("Web Push enabled (subject={})", subject);
                return;
            } catch (Exception err) {
                log.warn("Invalid VAPID keys, falling back to ephemeral: {}", err.toString());
            }
        } else {
            log.warn("VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY not set — using ephemeral keypair. Push delivery will be logged, not sent.");
        }
        generateEphemeral();
    }

    private void generateEphemeral() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            byte[] x = unsigned32(pub.getW().getAffineX().toByteArray());
            byte[] y = unsigned32(pub.getW().getAffineY().toByteArray());
            byte[] point = new byte[65];
            point[0] = 0x04;
            System.arraycopy(x, 0, point, 1, 32);
            System.arraycopy(y, 0, point, 33, 32);
            publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
            privateKey = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(unsigned32(((ECPrivateKey) kp.getPrivate()).getS().toByteArray()));
        } catch (Exception ignored) {
            // push delivery stays a no-op
        }
    }

    private static byte[] unsigned32(byte[] v) {
        byte[] out = new byte[32];
        int copy = Math.min(32, v.length);
        System.arraycopy(v, v.length - copy, out, 32 - copy, copy);
        return out;
    }

    public String getPublicKey() {
        return publicKey;
    }

    /** Upsert by endpoint; returns {@code {id}}. */
    @Transactional
    public Map<String, Object> subscribe(String organizationId, String userId, String endpoint, String p256dh,
            String auth, String userAgent) {
        List<PushSubscription> rows = em.createQuery("select s from PushSubscription s where s.endpoint = :e",
                        PushSubscription.class)
                .setParameter("e", endpoint)
                .getResultList();
        PushSubscription row = rows.isEmpty() ? new PushSubscription() : rows.getFirst();
        row.setOrganizationId(organizationId);
        row.setUserId(userId);
        row.setP256dh(p256dh);
        row.setAuth(auth);
        row.setUserAgent(userAgent);
        if (rows.isEmpty()) {
            row.setEndpoint(endpoint);
            em.persist(row);
        } else {
            row.setLastSeenAt(Instant.now());
            row.setRevokedAt(null);
        }
        em.flush();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        return out;
    }

    public void unsubscribe(String endpoint) {
        jdbc.sql("UPDATE \"PushSubscription\" SET \"revokedAt\" = now() WHERE endpoint = ?").param(endpoint).update();
    }

    public void unsubscribeById(String id) {
        jdbc.sql("UPDATE \"PushSubscription\" SET \"revokedAt\" = now() WHERE id = ?").param(id).update();
    }

    /** Deliver to every active subscription of the user; best-effort. */
    public Map<String, Object> sendToUser(String userId, String title, String body, String href, String tag) {
        record Sub(String id, String endpoint, String p256dh, String auth) {}
        List<Sub> subs = jdbc.sql("""
                        SELECT id, endpoint, p256dh, auth FROM "PushSubscription" WHERE "userId" = ? AND "revokedAt" IS NULL""")
                .param(userId)
                .query((rs, n) -> new Sub(rs.getString("id"), rs.getString("endpoint"), rs.getString("p256dh"),
                        rs.getString("auth")))
                .list();
        Map<String, Object> result = new LinkedHashMap<>();
        if (subs.isEmpty()) {
            result.put("delivered", 0);
            return result;
        }
        if (!configured || sender == null) {
            log.debug("[DEV-PUSH] user={} title={} body={}", userId, title, body);
            result.put("delivered", 0);
            result.put("reason", "no-vapid");
            return result;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if (href != null) {
            payload.put("href", href);
        }
        if (tag != null) {
            payload.put("tag", tag);
        }
        byte[] json = mapper.writeValueAsBytes(payload);
        int delivered = 0;
        int failed = 0;
        for (Sub s : subs) {
            try {
                var notification = new nl.martijndwars.webpush.Notification(s.endpoint(), s.p256dh(), s.auth(), json,
                        3600);
                // send() returns an Apache HttpResponse (runtime-only dependency of web-push): read it reflectively.
                Object response = sender.getClass().getMethod("send", nl.martijndwars.webpush.Notification.class)
                        .invoke(sender, notification);
                Object statusLine = response.getClass().getMethod("getStatusLine").invoke(response);
                int status = (Integer) statusLine.getClass().getMethod("getStatusCode").invoke(statusLine);
                if (status >= 200 && status < 300) {
                    delivered++;
                } else {
                    if (status == 404 || status == 410) {
                        unsubscribeById(s.id());
                    } else {
                        log.warn("Push delivery to {} failed: HTTP {}", s.endpoint().substring(0, Math.min(60, s.endpoint().length())), status);
                    }
                    failed++;
                }
            } catch (Exception err) {
                log.warn("Push delivery failed: {}", err.toString());
                failed++;
            }
        }
        result.put("delivered", delivered);
        result.put("failed", failed);
        return result;
    }
}
