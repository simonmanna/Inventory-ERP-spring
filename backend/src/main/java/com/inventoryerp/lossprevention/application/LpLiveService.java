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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live view and snapshots (LP1, ADR-S077 §8). The agent pushes JPEG frames out to the backend, which keeps only the
 * latest frame of each session in memory and relays it to the viewers as an MJPEG stream; nothing is stored. A viewer
 * opens the stream with a signed link that must be used within five minutes; a session ends after 30 minutes, when
 * nobody has watched for a minute, or when stopped.
 *
 * <p>Sessions live in this instance's memory: with several API instances, live view needs the agent and the viewer on
 * the same one (sticky routing).
 */
@Service
public class LpLiveService {

    private static final Logger log = LoggerFactory.getLogger(LpLiveService.class);

    static final Duration CONNECT_WITHIN = Duration.ofMinutes(5);
    static final Duration MAX_SESSION = Duration.ofMinutes(30);
    static final Duration IDLE_STOP = Duration.ofSeconds(60);
    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);
    public static final int MAX_FPS = 4;
    public static final int MAX_WIDTH = 1280;
    private static final String BOUNDARY = "lpframe";
    private static final long WAIT_MILLIS = 10_000;

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpAgentCommandService commands;
    private final LpAccessLog access;
    private final byte[] tokenKey = new byte[32];
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public LpLiveService(JdbcClient jdbc, LpScope scope, LpAgentCommandService commands, LpAccessLog access) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.commands = commands;
        this.access = access;
        new SecureRandom().nextBytes(tokenKey);
    }

    /** One live view. Guarded by its own monitor; the latest frame replaces the one before. */
    static final class Session {
        final String id;
        final String organizationId;
        final String agentId;
        final String cameraId;
        final Instant connectBy;
        final Instant endsAt;
        byte[] frame;
        long seq;
        Instant lastFrameAt;
        int viewers;
        int viewersEver;
        Instant lastViewerAt;
        volatile boolean stopped;

        Session(String id, String organizationId, String agentId, String cameraId, Instant now) {
            this.id = id;
            this.organizationId = organizationId;
            this.agentId = agentId;
            this.cameraId = cameraId;
            this.connectBy = now.plus(CONNECT_WITHIN);
            this.endsAt = now.plus(MAX_SESSION);
        }
    }

    /** A still and when the backend received it. */
    public record Snapshot(byte[] jpeg, Instant at) {}

    // ------------------------------------------------------------------ helpers

    /** The camera with its agent when the caller may see it, else 404. */
    private Map<String, Object> camera(String cameraId) {
        List<Object> params = new ArrayList<>();
        String w = scope.where("c", params) + " AND c.id = ?";
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

    private String token(Session s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            byte[] sig = mac.doFinal((s.id + "|" + s.connectBy.toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes.length <= LpVisionCatalog.MAX_IMAGE_BYTES
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    private void end(Session s, boolean tellAgent) {
        if (s.stopped) {
            return;
        }
        synchronized (s) {
            s.stopped = true;
            s.notifyAll();
        }
        if (tellAgent) {
            commands.queue(s.agentId, "live_stop", Obj.of("sessionId", s.id), Duration.ofMinutes(2));
        }
    }

    // ------------------------------------------------------------------ users

    /** Starts live view of a camera: the agent is asked to push frames, the caller gets a signed stream link. */
    @Transactional
    public Map<String, Object> start(String cameraId, String apiBase) {
        Map<String, Object> cam = camera(cameraId);
        assertReachable(cam);
        Instant now = clock.instant();
        sessions.values().removeIf(old -> old.stopped || now.isAfter(old.endsAt));
        Session s = new Session(UUID.randomUUID().toString(), LpScope.org(), (String) cam.get("agentId"), cameraId,
                now);
        sessions.put(s.id, s);
        commands.queue(s.agentId, "live_start", Obj.of("sessionId", s.id, "cameraId", cameraId, "fps", MAX_FPS,
                "maxWidth", MAX_WIDTH, "expiresAt", s.endsAt.toString()), Duration.ofMinutes(1));
        access.record(null, cameraId, null, LpAccessLog.LIVE_START, Obj.of("sessionId", s.id));
        return Obj.of("sessionId", s.id, "streamUrl", apiBase + "/loss-prevention/live/" + s.id + "/stream?token="
                + token(s), "expiresAt", s.connectBy, "endsAt", s.endsAt, "maxFps", MAX_FPS);
    }

    @Transactional
    public Map<String, Object> stop(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.organizationId.equals(LpScope.org())) {
            throw ApiException.notFound("Live view not found");
        }
        camera(s.cameraId);
        end(s, true);
        sessions.remove(sessionId);
        access.record(null, s.cameraId, null, LpAccessLog.LIVE_STOP, Obj.of("sessionId", s.id));
        return Obj.of("sessionId", s.id, "stopped", true);
    }

    /** An open stream; the caller sets the content type and writes it to the response. */
    public final class Stream {

        private final Session session;

        private Stream(Session session) {
            this.session = session;
        }

        public String contentType() {
            return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
        }

        /** Writes frames as they arrive until the session ends or the viewer leaves. */
        public void writeTo(OutputStream out) {
            Session s = session;
            long seen = -1;
            try {
                while (!s.stopped && clock.instant().isBefore(s.endsAt)) {
                    byte[] frame;
                    long seq;
                    synchronized (s) {
                        if (s.seq == seen && !s.stopped) {
                            s.wait(WAIT_MILLIS);
                        }
                        frame = s.frame;
                        seq = s.seq;
                    }
                    if (frame == null || seq == seen) {
                        continue;
                    }
                    out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length
                            + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(frame);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    seen = seq;
                }
            } catch (IOException e) {
                // The viewer closed the page.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (s) {
                    s.viewers--;
                    s.lastViewerAt = clock.instant();
                }
            }
        }
    }

    /**
     * Checks the signed link and opens the stream. Public: the token is the authorization. 404 when the link is wrong,
     * too old to connect, or the session has ended, so the reason is not disclosed.
     */
    public Stream open(String sessionId, String token) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        Instant now = clock.instant();
        if (s == null || s.stopped || token == null || !MessageDigest.isEqual(token(s).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)) || now.isAfter(s.connectBy) || now.isAfter(s.endsAt)) {
            throw ApiException.notFound("Live view not found or ended");
        }
        synchronized (s) {
            s.viewers++;
            s.viewersEver++;
            s.lastViewerAt = now;
        }
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
        Session s = sessions.get(sessionId);
        Instant now = clock.instant();
        if (s == null || !s.agentId.equals(agentId) || !s.organizationId.equals(LpScope.org()) || s.stopped
                || now.isAfter(s.endsAt)) {
            return false;
        }
        synchronized (s) {
            if (s.lastFrameAt == null || Duration.between(s.lastFrameAt, now).toMillis() >= 1000 / MAX_FPS) {
                s.frame = jpeg;
                s.seq++;
                s.lastFrameAt = now;
                s.notifyAll();
            }
        }
        return true;
    }

    /** The agent could not start the session: it ends. */
    public void agentFailed(String agentId, String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s != null && s.agentId.equals(agentId)) {
            end(s, false);
            sessions.remove(sessionId);
        }
    }

    /** Every session of a revoked agent ends; the agent is not told (its credentials no longer work). */
    public void stopAgent(String agentId) {
        sessions.values().removeIf(s -> {
            if (s.agentId.equals(agentId)) {
                end(s, false);
                return true;
            }
            return false;
        });
    }

    /**
     * Ends the sessions that are over: past their end, never watched within the connect time, or unwatched for a
     * minute; the agent is told to stop. Sessions live in this instance's memory, so every instance runs this (no
     * schedule lock). Returns how many ended.
     */
    public int sweep() {
        Instant now = clock.instant();
        int ended = 0;
        for (Session s : List.copyOf(sessions.values())) {
            boolean over;
            synchronized (s) {
                over = s.stopped || now.isAfter(s.endsAt) || (s.viewersEver == 0 && now.isAfter(s.connectBy))
                        || (s.viewersEver > 0 && s.viewers <= 0 && s.lastViewerAt != null
                                && Duration.between(s.lastViewerAt, now).compareTo(IDLE_STOP) > 0);
            }
            if (!over) {
                continue;
            }
            sessions.remove(s.id);
            ended++;
            if (!s.stopped) {
                try {
                    TenantContext.run(TenantStore.organization(s.organizationId), () -> end(s, true));
                } catch (RuntimeException e) {
                    end(s, false);
                    log.warn("live view {}: telling the agent to stop failed: {}", s.id, e.toString());
                }
            }
        }
        snapshots.entrySet().removeIf(e -> Duration.between(e.getValue().at(), now).compareTo(SNAPSHOT_TTL) > 0);
        return ended;
    }

    // ------------------------------------------------------------------ snapshots

    /** A still from the agent, kept in memory for ten minutes. */
    public void putSnapshot(String cameraId, byte[] jpeg) {
        if (!isJpeg(jpeg)) {
            throw ApiException.badRequest("A snapshot must be a JPEG image of at most "
                    + LpVisionCatalog.MAX_IMAGE_BYTES + " bytes");
        }
        snapshots.put(LpScope.org() + "|" + cameraId, new Snapshot(jpeg, clock.instant()));
    }

    /** The latest still of the camera, or 404. The caller has checked the camera is visible. */
    public Snapshot snapshot(String cameraId) {
        Snapshot s = snapshots.get(LpScope.org() + "|" + cameraId);
        if (s == null || Duration.between(s.at(), clock.instant()).compareTo(SNAPSHOT_TTL) > 0) {
            throw ApiException.notFound("No snapshot yet: ask the edge agent for one first");
        }
        return s;
    }
}
