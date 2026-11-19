package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live view and snapshots (LP1, ADR-S077 §8). The agent pushes JPEG frames out to the backend, which keeps only the
 * latest frame of each session and relays it to the viewers as an MJPEG stream. A viewer opens the stream with a signed
 * link that must be used within five minutes; a session ends after 30 minutes, when nobody has watched for a minute, or
 * when stopped.
 *
 * <p>{@code app.lossprevention.live.store}: {@code memory} (default) keeps sessions and stills in this instance, so
 * behind several API instances the agent and the viewer must reach the same one (sticky routing); {@code database}
 * keeps them, encrypted, in UNLOGGED tables that every instance reads (V577). The link signature is derived from the
 * evidence key, so any instance of the deployment can check it.
 */
@Service
public class LpLiveService {

    private static final Logger log = LoggerFactory.getLogger(LpLiveService.class);

    static final Duration CONNECT_WITHIN = Duration.ofMinutes(5);
    static final Duration MAX_SESSION = Duration.ofMinutes(30);
    static final Duration IDLE_STOP = Duration.ofSeconds(60);
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);
    /** How often an open stream tells the store it is still watched. */
    static final Duration VIEWER_REFRESH = Duration.ofSeconds(10);
    public static final int MAX_FPS = 4;
    public static final int MAX_WIDTH = 1280;
    private static final String BOUNDARY = "lpframe";
    private static final long WAIT_MILLIS = 10_000;

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpAgentCommandService commands;
    private final LpAccessLog access;
    private final LpLiveStore store;
    private final byte[] tokenKey;
    private final Clock clock = Clock.systemUTC();

    public LpLiveService(JdbcClient jdbc, LpScope scope, LpAgentCommandService commands, LpAccessLog access,
            LpEvidenceCipher cipher, @Value("${app.lossprevention.live.store:memory}") String storeKind) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.commands = commands;
        this.access = access;
        this.tokenKey = cipher.subkey("lp-live-token-v1");
        this.store = switch (storeKind == null ? "memory" : storeKind.trim()) {
            case "", "memory" -> new LpLiveStore.Memory();
            case "database" -> new LpLiveJdbcStore(jdbc, cipher);
            default -> throw new IllegalStateException("app.lossprevention.live.store must be memory or database, not '"
                    + storeKind + "'");
        };
    }

    /** Whether sessions are shared by every API instance. */
    public boolean shared() {
        return store instanceof LpLiveJdbcStore;
    }

    /** A still and when the backend received it. */
    public record Snapshot(byte[] jpeg, Instant at) {}

    // ------------------------------------------------------------------ helpers

    /** The camera with its agent when the caller may see it, else 404. */
    private Map<String, Object> camera(String cameraId) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("c", params) + " AND c.id = ? AND c.\"deletedAt\" IS NULL";
        params.add(cameraId);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT c.id, c.\"agentId\", c.enabled, a.status AS"
                + " \"agentStatus\", a.health AS \"agentHealth\" FROM \"LpCamera\" c JOIN \"LpAgent\" a ON a.id ="
                + " c.\"agentId\"" + w).params(params));
        if (row == null) {
            throw ApiException.notFound("Camera not found");
        }
        return row;
    }

    /** 409 unless the camera is on and its agent is enrolled and reporting. */
    static void assertReachable(Map<String, Object> camera) {
        if (!Boolean.TRUE.equals(camera.get("enabled"))) {
            throw ApiException.conflict("The camera is switched off");
        }
        if (!"active".equals(camera.get("agentStatus")) || !("online".equals(camera.get("agentHealth"))
                || "degraded".equals(camera.get("agentHealth")))) {
            throw ApiException.conflict("The edge agent of this camera is offline");
        }
    }

    private String token(LpLiveStore.Session s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            byte[] sig = mac.doFinal((s.id() + "|" + s.connectBy().toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes.length <= LpVisionCatalog.MAX_IMAGE_BYTES
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    /** Stops the session; the agent is told once, by whichever instance stopped it. */
    private void end(LpLiveStore.Session s, boolean tellAgent) {
        if (store.stop(s.id()) && tellAgent) {
            commands.queue(s.agentId(), "live_stop", Obj.of("sessionId", s.id()), Duration.ofMinutes(2));
        }
    }

    // ------------------------------------------------------------------ users

    /** Starts live view of a camera: the agent is asked to push frames, the caller gets a signed stream link. */
    @Transactional
    public Map<String, Object> start(String cameraId, String apiBase) {
        Map<String, Object> cam = camera(cameraId);
        assertReachable(cam);
        Instant now = clock.instant();
        LpLiveStore.Session s = new LpLiveStore.Session(UUID.randomUUID().toString(), LpScope.org(),
                (String) cam.get("agentId"), cameraId, now.plus(CONNECT_WITHIN), now.plus(MAX_SESSION), false, false,
                null);
        store.create(s);
        commands.queue(s.agentId(), "live_start", Obj.of("sessionId", s.id(), "cameraId", cameraId, "fps", MAX_FPS,
                "maxWidth", MAX_WIDTH, "expiresAt", s.endsAt().toString()), Duration.ofMinutes(1));
        access.record(null, cameraId, null, LpAccessLog.LIVE_START, Obj.of("sessionId", s.id()));
        return Obj.of("sessionId", s.id(), "streamUrl", apiBase + "/loss-prevention/live/" + s.id() + "/stream?token="
                + token(s), "expiresAt", s.connectBy(), "endsAt", s.endsAt(), "maxFps", MAX_FPS);
    }

    @Transactional
    public Map<String, Object> stop(String sessionId) {
        LpLiveStore.Session s = store.find(sessionId);
        if (s == null || !s.organizationId().equals(LpScope.org())) {
            throw ApiException.notFound("Live view not found");
        }
        camera(s.cameraId());
        end(s, true);
        store.remove(sessionId);
        access.record(null, s.cameraId(), null, LpAccessLog.LIVE_STOP, Obj.of("sessionId", s.id()));
        return Obj.of("sessionId", s.id(), "stopped", true);
    }

    /** An open stream; the caller sets the content type and writes it to the response. */
    public final class Stream {

        private final LpLiveStore.Session session;

        private Stream(LpLiveStore.Session session) {
            this.session = session;
        }

        public String contentType() {
            return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
        }

        /** Writes frames as they arrive until the session ends or the viewer leaves. */
        public void writeTo(OutputStream out) {
            LpLiveStore.Session s = session;
            long seen = -1;
            Instant refreshed = clock.instant();
            try {
                while (clock.instant().isBefore(s.endsAt())) {
                    LpLiveStore.Frame frame = store.next(s.id(), seen, WAIT_MILLIS);
                    Instant now = clock.instant();
                    if (Duration.between(refreshed, now).compareTo(VIEWER_REFRESH) >= 0) {
                        store.viewer(s.id(), now);
                        refreshed = now;
                    }
                    if (frame == null) {
                        continue;
                    }
                    if (frame.stopped()) {
                        return;
                    }
                    out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.jpeg().length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(frame.jpeg());
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    seen = frame.seq();
                }
            } catch (IOException e) {
                // The viewer closed the page.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("live view {}: stream ended: {}", s.id(), e.toString());
            } finally {
                try {
                    store.viewer(s.id(), clock.instant());
                } catch (RuntimeException e) {
                    // The session is gone.
                }
            }
        }
    }

    /**
     * Checks the signed link and opens the stream. Public: the token is the authorization. 404 when the link is wrong,
     * too old to connect, or the session has ended, so the reason is not disclosed.
     */
    public Stream open(String sessionId, String token) {
        LpLiveStore.Session s = sessionId == null ? null : store.find(sessionId);
        Instant now = clock.instant();
        if (s == null || s.stopped() || token == null || !MessageDigest.isEqual(token(s).getBytes(
                StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)) || now.isAfter(s.connectBy())
                || now.isAfter(s.endsAt())) {
            throw ApiException.notFound("Live view not found or ended");
        }
        store.viewer(s.id(), now);
        return new Stream(s);
    }

    // ------------------------------------------------------------------ the box

    /**
     * A frame from the agent. Answers whether to keep sending: false when the session is unknown, belongs to another
     * agent, was stopped or has ended. Frames faster than {@link #MAX_FPS} are dropped.
     */
    public boolean frame(String agentId, String sessionId, byte[] jpeg) {
        if (!isJpeg(jpeg)) {
            throw ApiException.badRequest("A frame must be a JPEG image of at most "
                    + LpVisionCatalog.MAX_IMAGE_BYTES + " bytes");
        }
        return sessionId != null && store.putFrame(sessionId, LpScope.org(), agentId, jpeg, clock.instant(),
                Duration.ofMillis(1000 / MAX_FPS));
    }

    /** The agent could not start the session: it ends. */
    public void agentFailed(String agentId, String sessionId) {
        LpLiveStore.Session s = sessionId == null ? null : store.find(sessionId);
        if (s != null && s.agentId().equals(agentId)) {
            end(s, false);
            store.remove(sessionId);
        }
    }

    /** Every session of a revoked agent ends; the agent is not told (its credentials no longer work). */
    public void stopAgent(String agentId) {
        for (LpLiveStore.Session s : store.all()) {
            if (s.agentId().equals(agentId)) {
                end(s, false);
                store.remove(s.id());
            }
        }
    }

    /** A deleted camera: its live views end (the agent is told) and its still is dropped. */
    public void stopCamera(String cameraId) {
        for (LpLiveStore.Session s : store.all()) {
            if (s.cameraId().equals(cameraId) && s.organizationId().equals(LpScope.org())) {
                end(s, true);
                store.remove(s.id());
            }
        }
        store.removeSnapshot(LpScope.org(), cameraId);
    }

    /** Whether a session is over: past its end, never watched within the connect time, or unwatched for a minute. */
    static boolean over(LpLiveStore.Session s, Instant now) {
        return s.stopped() || now.isAfter(s.endsAt()) || (!s.watched() && now.isAfter(s.connectBy()))
                || (s.watched() && s.lastViewerAt() != null
                        && Duration.between(s.lastViewerAt(), now).compareTo(IDLE_STOP) > 0);
    }

    /**
     * Ends the sessions that are over and tells their agents to stop. Every instance runs this (no schedule lock): with
     * the memory store each ends its own sessions; with the database store the first instance to stop a session tells
     * the agent. Returns how many ended.
     */
    public int sweep() {
        Instant now = clock.instant();
        int ended = 0;
        for (LpLiveStore.Session s : store.all()) {
            if (!over(s, now)) {
                continue;
            }
            ended++;
            if (!s.stopped()) {
                try {
                    TenantContext.run(TenantStore.organization(s.organizationId()), () -> end(s, true));
                } catch (RuntimeException e) {
                    store.stop(s.id());
                    log.warn("live view {}: telling the agent to stop failed: {}", s.id(), e.toString());
                }
            }
            store.remove(s.id());
        }
        store.pruneSnapshots(now.minus(SNAPSHOT_TTL));
        return ended;
    }

    // ------------------------------------------------------------------ snapshots

    /** A still from the agent, kept for ten minutes. */
    public void putSnapshot(String cameraId, byte[] jpeg) {
        if (!isJpeg(jpeg)) {
            throw ApiException.badRequest("A snapshot must be a JPEG image of at most "
                    + LpVisionCatalog.MAX_IMAGE_BYTES + " bytes");
        }
        store.putSnapshot(LpScope.org(), cameraId, jpeg, clock.instant());
    }

    /** The latest still of the camera, or 404. The caller has checked the camera is visible. */
    public Snapshot snapshot(String cameraId) {
        Snapshot s = store.snapshot(LpScope.org(), cameraId);
        if (s == null || Duration.between(s.at(), clock.instant()).compareTo(SNAPSHOT_TTL) > 0) {
            throw ApiException.notFound("No snapshot yet: ask the edge agent for one first");
        }
        return s;
    }
}
