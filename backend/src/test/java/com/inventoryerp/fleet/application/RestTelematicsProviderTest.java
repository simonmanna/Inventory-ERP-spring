package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.TelematicsProvider.Position;
import com.inventoryerp.fleet.application.TelematicsProvider.TelematicsEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** INT-2: the REST telematics contract against a local stub, the bearer key, 404 and failure handling. */
class RestTelematicsProviderTest {

    private static void reply(com.sun.net.httpserver.HttpExchange x, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, status == 404 ? -1 : b.length);
        if (status != 404) {
            x.getResponseBody().write(b);
        }
        x.close();
    }

    @Test
    void readsPositionsMetersAndEvents() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/units/T1/positions", x -> {
            auth.set(x.getRequestHeaders().getFirst("Authorization"));
            reply(x, 200, "{\"positions\": [{\"id\": \"p1\", \"at\": \"2026-10-01T10:00:00Z\", \"lat\": 0.31,"
                    + " \"lon\": 32.58, \"speedKmh\": 42.5}]}");
        });
        server.createContext("/units/T1/meters", x -> reply(x, 200,
                "{\"odometerKm\": 1200.5, \"engineHours\": 300, \"at\": \"2026-10-01T10:00:00Z\"}"));
        server.createContext("/units/NONE/meters", x -> reply(x, 404, ""));
        server.createContext("/events", x -> reply(x, 200, "[{\"unitId\": \"T1\", \"type\": \"speeding\","
                + " \"at\": \"2026-10-01T10:05:00Z\", \"data\": {\"speedKmh\": 91, \"zone\": \"A\"}}]"));
        server.start();
        try {
            RestTelematicsProvider rest = new RestTelematicsProvider("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/", "secret", 2000);
            assertThat(rest.type()).isEqualTo("rest");
            Instant from = Instant.parse("2026-10-01T09:00:00Z");
            List<Position> fixes = rest.positions("T1", from, from.plusSeconds(7200));
            assertThat(fixes).hasSize(1);
            assertThat(fixes.get(0).externalId()).isEqualTo("p1");
            assertThat(fixes.get(0).lat()).isEqualTo(0.31);
            assertThat(fixes.get(0).speedKmh()).isEqualByComparingTo("42.5");
            assertThat(auth.get()).isEqualTo("Bearer secret");
            assertThat(rest.meters("T1").orElseThrow().odometerKm()).isEqualByComparingTo("1200.5");
            assertThat(rest.meters("NONE")).isEmpty();
            List<TelematicsEvent> events = rest.events(from);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).externalId()).isEqualTo("T1:speeding:" + events.get(0).at().getEpochSecond());
            assertThat(events.get(0).data()).containsEntry("zone", "A");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failuresNameNoSecret() {
        RestTelematicsProvider off = new RestTelematicsProvider("", "", 1000);
        assertThatThrownBy(() -> off.events(Instant.now())).hasMessageContaining("FLEET_TELEMATICS_URL");
        RestTelematicsProvider down = new RestTelematicsProvider("http://127.0.0.1:1", "secret", 600);
        assertThatThrownBy(() -> down.meters("T1")).hasMessageNotContaining("secret");
    }
}
