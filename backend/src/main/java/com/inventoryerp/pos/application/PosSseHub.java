package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
 * every 25 s. One hub per stream; a client key is {@code organizationId} plus an optional channel.
 *
 * <p>Post-parity hardening (2026-11-10, no contract change):
 * <ul>
 *   <li>{@link #changed} only marks the tenant dirty and returns: it is called from the outbox worker, which must not
 *       render snapshots or write to sockets. A burst of events within {@link #DEBOUNCE_MS} yields one push.
 *   <li>The snapshot is rendered once per (tenant, channel) and written to every client of that group, like Nest
 *       (the first port rendered it once per connected client).
 *   <li>Each write runs on its own virtual thread, so one slow or half-open client cannot stall the others.
 *   <li>Streams end after {@link #STREAM_TIMEOUT_MS}; the browser reconnects ({@code retry: 5000}) and passes
 *       authentication again, so a signed-out or deactivated user stops receiving updates.
 * </ul>
 */
public class PosSseHub {

    private static final Logger log = LoggerFactory.getLogger(PosSseHub.class);
    private static final long HEARTBEAT_SECONDS = 25;
    static final long DEBOUNCE_MS = 250;
    static final long STREAM_TIMEOUT_MS = 30 * 60_000L;

    /** One connected client. */
    public record Client(String organizationId, String channel, SseEmitter emitter) {}

    private final String name;
    private final JsonMapper mapper;
    private final Function<Client, Object> snapshot;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    /** {@code snapshot} renders the payload for a client's group; it runs inside that tenant's context. */
    public PosSseHub(String name, JsonMapper mapper, Function<Client, Object> snapshot) {
        this.name = name;
        this.mapper = mapper;
        this.snapshot = snapshot;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-" + name);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Open a stream for the current tenant: {@code retry: 5000} then the first snapshot. */
    public SseEmitter connect(String channel) {
        String orgId = TenantContext.organizationId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Client client = new Client(orgId, channel, emitter);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> {
            clients.remove(client);
            emitter.complete();
        });
        emitter.onError(e -> clients.remove(client));
        try {
            emitter.send(SseEmitter.event().reconnectTime(5000));
        } catch (IOException e) {
            clients.remove(client);
            return emitter;
        }
        String payload = render(client);
        if (payload != null) {
            write(client, payload);
        }
        return emitter;
    }

    /** A change for {@code organizationId}: push one fresh snapshot to its clients shortly (debounced, async). */
    public void changed(String organizationId) {
        if (organizationId == null || clients.isEmpty()) {
            return;
        }
        if (dirty.add(organizationId)) {
            scheduler.schedule(() -> flush(organizationId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    public int size() {
        return clients.size();
    }

    private void flush(String organizationId) {
        dirty.remove(organizationId);
        Map<String, List<Client>> groups = new LinkedHashMap<>();
        for (Client c : clients) {
            if (organizationId.equals(c.organizationId())) {
                groups.computeIfAbsent(Objects.toString(c.channel(), ""), k -> new ArrayList<>()).add(c);
            }
        }
        for (List<Client> group : groups.values()) {
            writers.execute(() -> {
                String payload = render(group.getFirst());
                if (payload != null) {
                    group.forEach(c -> writers.execute(() -> write(c, payload)));
                }
            });
        }
    }

    private String render(Client c) {
        try {
            Object data = TenantContext.run(TenantContext.TenantStore.organization(c.organizationId()),
                    () -> snapshot.apply(c));
            return mapper.writeValueAsString(data);
        } catch (RuntimeException e) {
            log.warn("SSE {} snapshot failed: {}", name, e.toString());
            return null;
        }
    }

    private void write(Client c, String payload) {
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
            writers.execute(() -> {
                try {
                    synchronized (c.emitter()) {
                        c.emitter().send(SseEmitter.event().comment("heartbeat"));
                    }
                } catch (IOException | IllegalStateException e) {
                    clients.remove(c);
                }
            });
        }
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        writers.shutdownNow();
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
