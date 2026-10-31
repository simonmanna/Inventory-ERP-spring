package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineEvent;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineRef;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.PollRequest;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Built-in machine-data adapters (ADR-S015): deterministic simulator and REST pull. */
class MachineAdaptersTest {

    @Test
    @DisplayName("simulator: per elapsed hour one 6-min minor stop + a count at 90 % of 54 run minutes, 2 % scrap")
    void simulator() {
        SimulatorMachineDataAdapter sim = new SimulatorMachineDataAdapter();
        assertThat(sim.code()).isEqualTo("simulator");
        assertThat(sim.configured()).isTrue();
        assertThat(sim.pull()).isTrue();
        List<MachineEvent> ev = sim.poll(new PollRequest(List.of(new MachineRef("wc", "CR-1", new BigDecimal("100")),
                new MachineRef("wc2", "PR-1", null)), Instant.parse("2026-10-30T06:10:00Z"),
                Instant.parse("2026-10-30T09:30:00Z")));
        // 07:00-08:00 and 08:00-09:00 are complete hours for both machines → 2 × 2 × 2 events.
        assertThat(ev).hasSize(8);
        MachineEvent count = ev.stream().filter(e -> "count".equals(e.type()) && "CR-1".equals(e.machineCode()))
                .findFirst().orElseThrow();
        assertThat(count.occurredAt()).isEqualTo(Instant.parse("2026-10-30T07:00:00Z"));
        assertThat(count.goodQty().add(count.scrapQty())).isEqualByComparingTo("81.00");
        assertThat(count.scrapQty()).isEqualByComparingTo("1.62");
        MachineEvent stop = ev.stream().filter(e -> "downtime".equals(e.type())).findFirst().orElseThrow();
        assertThat(stop.reasonCode()).isEqualTo("MINOR_STOP");
        assertThat(stop.endedAt()).isEqualTo(stop.occurredAt().plusSeconds(360));
        assertThat(sim.poll(new PollRequest(List.of(new MachineRef("wc", "CR-1", null)),
                Instant.parse("2026-10-30T06:10:00Z"), Instant.parse("2026-10-30T09:30:00Z"))).get(0).externalId())
                .isEqualTo(ev.get(0).externalId());
    }

    @Test
    @DisplayName("REST: GET <base>/events?machine=…&since=…&until=…, bearer token, array or {events}; 404 = none")
    void rest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/gw/events", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String q = ex.getRequestURI().getQuery();
            if (q.contains("machine=GONE")) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            query.set(q);
            byte[] body = """
                    {"events": [{"id": "e1", "type": "count", "occurredAt": "2026-10-30T06:00:00Z",
                      "endedAt": "2026-10-30T07:00:00Z", "goodQty": 98, "scrapQty": 2},
                     {"externalId": "e2", "type": "downtime", "occurredAt": "2026-10-30T07:00:00Z",
                      "endedAt": "2026-10-30T07:05:00Z", "reasonCode": "JAM"}]}"""
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            RestMachineDataAdapter rest = new RestMachineDataAdapter("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/gw/", "tok", 2000);
            assertThat(rest.configured()).isTrue();
            List<MachineEvent> ev = rest.poll(new PollRequest(List.of(new MachineRef("wc", "CR 1", null),
                    new MachineRef("wc2", "GONE", null)), Instant.parse("2026-10-30T06:00:00Z"),
                    Instant.parse("2026-10-30T08:00:00Z")));
            assertThat(auth.get()).isEqualTo("Bearer tok");
            assertThat(query.get()).isEqualTo("machine=CR+1&since=2026-10-30T06:00:00Z&until=2026-10-30T08:00:00Z");
            assertThat(ev).hasSize(2);
            assertThat(ev.get(0).externalId()).isEqualTo("e1");
            assertThat(ev.get(0).machineCode()).isEqualTo("CR 1");
            assertThat(ev.get(0).goodQty()).isEqualByComparingTo("98");
            assertThat(ev.get(1).reasonCode()).isEqualTo("JAM");
        } finally {
            server.stop(0);
        }
        RestMachineDataAdapter off = new RestMachineDataAdapter("", "", 1000);
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.poll(new PollRequest(List.of(), null, null)))
                .hasMessageContaining("MFG_MACHINE_DATA_REST_URL");
        RestMachineDataAdapter down = new RestMachineDataAdapter("http://127.0.0.1:1", "", 600);
        assertThatThrownBy(() -> down.poll(new PollRequest(List.of(new MachineRef("w", "M", null)), null, null)))
                .hasMessageContaining("could not be reached");
    }
}
