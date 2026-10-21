package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-tenant SSE fan-out (docs/CONTRACT.md §9): a snapshot on connect and on every change, a heartbeat comment
 * every 25 s. One hub per stream ({@code tables}, {@code kds}); a client key is {@code organizationId} plus an
 * optional channel (e.g. the KDS station filter).
 */
public class PosSseHub {

    private static final Logger log = LoggerFactory.getLogger(PosSseHub.class);
    private static final long HEARTBEAT_SECONDS = 25;

    /** One connected client. */
    public record Client(String organizationId, String channel, SseEmitter emitter) {}

    private final String name;
    private final JsonMapper mapper;
    private final Function<Client, Object> snapshot;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat;

    /** {@code snapshot} renders the payload for a client; it runs inside that client's tenant context. */
    public PosSseHub(String name, JsonMapper mapper, Function<Client, Object> snapshot) {
        this.name = name;
        this.mapper = mapper;
        this.snapshot = snapshot;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-" + name + "-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Open a stream for the current tenant: {@code retry: 5000} then the first snapshot. */
    public SseEmitter connect(String channel) {
        String orgId = TenantContext.organizationId();
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(orgId, channel, emitter);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));
        try {
            emitter.send(SseEmitter.event().reconnectTime(5000));
        } catch (IOException e) {
            clients.remove(client);
            return emitter;
        }
        push(client);
        return emitter;
    }

    /** Push a fresh snapshot to every client of {@code organizationId}. */
    public void changed(String organizationId) {
        if (organizationId == null) {
            return;
        }
        for (Client c : clients) {
            if (organizationId.equals(c.organizationId())) {
                push(c);
            }
        }
    }

    public int size() {
        return clients.size();
    }

    private void push(Client c) {
        String payload;
        try {
            Object data = TenantContext.run(TenantContext.TenantStore.organization(c.organizationId()),
                    () -> snapshot.apply(c));
            payload = mapper.writeValueAsString(data);
        } catch (RuntimeException e) {
            log.warn("SSE {} snapshot failed: {}", name, e.toString());
            return;
        }
        try {
            synchronized (c.emitter()) {
                c.emitter().send(SseEmitter.event().data(payload, org.springframework.http.MediaType.TEXT_PLAIN));
            }
        } catch (IOException | IllegalStateException e) {
            clients.remove(c);
        }
    }

    private void beat() {
        for (Client c : clients) {
            try {
                synchronized (c.emitter()) {
                    c.emitter().send(SseEmitter.event().comment("heartbeat"));
                }
            } catch (IOException | IllegalStateException e) {
                clients.remove(c);
            }
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
        for (Client c : clients) {
            try {
                c.emitter().complete();
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
        clients.clear();
    }

}
