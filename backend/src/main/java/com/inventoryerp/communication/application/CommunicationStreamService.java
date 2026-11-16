package com.inventoryerp.communication.application;

import com.inventoryerp.communication.application.ConversationAccessService.AccessibleConversation;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-organization SSE fan-out for the unified inbox (port of {@code communication-stream.service.ts}). Every push
 * is addressed to one org's connections and filtered by the same access rule the HTTP layer uses, so a cashier's
 * stream never carries a manager's DM. A connection's org/user/permissions come from the authenticated token.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class CommunicationStreamService {

    private static final long HEARTBEAT_SECONDS = 25;

    /** One connected client. */
    record Connection(String userId, List<String> permissions, SseEmitter emitter) {}

    /** Who may receive an event about a conversation. */
    public record Audience(AccessibleConversation conversation, List<String> participantUserIds) {}

    private final JsonMapper mapper;
    private final Map<String, Set<Connection>> clients = new ConcurrentHashMap<>();
    /** Discuss presence hook (ADR-S064): told when a user's first stream opens or last stream closes. */
    private volatile PresenceListener presenceListener;

    /** Presence transitions of one user in one organization. */
    public interface PresenceListener {
        void changed(String organizationId, String userId, boolean online);
    }
    private final ScheduledExecutorService heartbeat;

    public CommunicationStreamService(JsonMapper mapper) {
        this.mapper = mapper;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-communication-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Open a stream: {@code retry: 5000}, then events as they happen. */
    public SseEmitter subscribe(String organizationId, String userId, List<String> permissions) {
        SseEmitter emitter = new SseEmitter(0L);
        Connection conn = new Connection(userId, List.copyOf(permissions), emitter);
        boolean wasOnline = isOnline(organizationId, userId);
        clients.computeIfAbsent(organizationId, k -> ConcurrentHashMap.newKeySet()).add(conn);
        Runnable remove = () -> {
            Set<Connection> s = clients.get(organizationId);
            if (s != null && s.remove(conn)) {
                if (s.isEmpty()) {
                    clients.remove(organizationId, s);
                }
                presence(organizationId, userId);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().reconnectTime(5000));
        } catch (IOException e) {
            remove.run();
        }
        if (!wasOnline && userId != null) {
            notifyPresence(organizationId, userId, true);
        }
        return emitter;
    }

    public void setPresenceListener(PresenceListener listener) {
        this.presenceListener = listener;
    }

    /** Whether the user has at least one open stream on this instance. */
    public boolean isOnline(String organizationId, String userId) {
        Set<Connection> set = clients.get(organizationId);
        return set != null && userId != null && set.stream().anyMatch(c -> userId.equals(c.userId()));
    }

    /** Users of the organization with an open stream on this instance. */
    public Set<String> onlineUserIds(String organizationId) {
        Set<Connection> set = clients.get(organizationId);
        if (set == null) {
            return Set.of();
        }
        Set<String> out = new java.util.HashSet<>();
        for (Connection c : set) {
            if (c.userId() != null) {
                out.add(c.userId());
            }
        }
        return out;
    }

    /** Push to every connection of one user (personal events: inbox, membership changes). */
    public void emitToUser(String organizationId, String userId, Map<String, Object> event) {
        Set<Connection> set = clients.get(organizationId);
        if (set == null || set.isEmpty() || userId == null) {
            return;
        }
        String payload = mapper.writeValueAsString(event);
        for (Connection conn : set) {
            if (userId.equals(conn.userId())) {
                write(organizationId, set, conn, payload);
            }
        }
    }

    /** Push to every connection of the organization (presence). */
    public void emitToOrg(String organizationId, Map<String, Object> event) {
        Set<Connection> set = clients.get(organizationId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String payload = mapper.writeValueAsString(event);
        for (Connection conn : set) {
            write(organizationId, set, conn, payload);
        }
    }

    private void presence(String organizationId, String userId) {
        if (userId != null && !isOnline(organizationId, userId)) {
            notifyPresence(organizationId, userId, false);
        }
    }

    private void notifyPresence(String organizationId, String userId, boolean online) {
        PresenceListener l = presenceListener;
        if (l != null) {
            try {
                l.changed(organizationId, userId, online);
            } catch (RuntimeException ignored) {
                // presence is best effort
            }
        }
    }

    /** Push to every connection in the org allowed to see the conversation (pure in-memory check). */
    public void emit(String organizationId, Audience audience, Map<String, Object> event) {
        Set<Connection> set = clients.get(organizationId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Set<String> participants = Set.copyOf(audience.participantUserIds());
        String payload = mapper.writeValueAsString(event);
        for (Connection conn : set) {
            if (ConversationAccessService.canAccess(audience.conversation(), participants.contains(conn.userId()),
                    conn.permissions())) {
                write(organizationId, set, conn, payload);
            }
        }
    }

    /** A lightweight event with no conversation scoping (e.g. channel status for admins). */
    public void emitToPermission(String organizationId, String requiredPermission, Map<String, Object> event) {
        Set<Connection> set = clients.get(organizationId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String payload = mapper.writeValueAsString(event);
        for (Connection conn : set) {
            if (conn.permissions().contains(requiredPermission)) {
                write(organizationId, set, conn, payload);
            }
        }
    }

    /** Open connections across all orgs. */
    public int size() {
        return clients.values().stream().mapToInt(Set::size).sum();
    }

    private void write(String organizationId, Set<Connection> set, Connection conn, String payload) {
        try {
            synchronized (conn.emitter()) {
                conn.emitter().send(SseEmitter.event().data(payload, MediaType.TEXT_PLAIN));
            }
        } catch (IOException | IllegalStateException e) {
            if (set.remove(conn)) {
                presence(organizationId, conn.userId());
            }
        }
    }

    private void beat() {
        for (Map.Entry<String, Set<Connection>> entry : clients.entrySet()) {
            Set<Connection> set = entry.getValue();
            for (Connection conn : set) {
                try {
                    synchronized (conn.emitter()) {
                        conn.emitter().send(SseEmitter.event().comment("ping"));
                    }
                } catch (IOException | IllegalStateException e) {
                    if (set.remove(conn)) {
                        presence(entry.getKey(), conn.userId());
                    }
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
        for (Set<Connection> set : clients.values()) {
            for (Connection conn : set) {
                try {
                    conn.emitter().complete();
                } catch (RuntimeException ignored) {
                    // closing anyway
                }
            }
        }
        clients.clear();
    }
}
