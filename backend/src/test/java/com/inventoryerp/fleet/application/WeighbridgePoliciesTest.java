package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.AsciiFrameParser.Frame;
import com.inventoryerp.fleet.application.WeighbridgeAdapter.DeviceSpec;
import com.inventoryerp.fleet.application.WeighbridgeAdapter.StableWeight;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Check;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Tolerance;
import com.inventoryerp.fleet.application.WeighbridgeCalculator.Weighing;
import com.inventoryerp.shared.web.ApiException;
import java.io.EOFException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fleet 1.4 weighbridge policies: the ASCII frame parser (stable / motion / overload frames, units, implied decimals,
 * custom formats), the stable-weight reader (settling, timeouts, garbage, closed source), the net / tolerance
 * calculator, stored-tare expiry, the simulator and the TCP adapter against an in-test socket server.
 */
class WeighbridgePoliciesTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static StableWeightReader.FrameSource frames(String... lines) {
        Deque<String> q = new ArrayDeque<>(List.of(lines));
        return max -> {
            String f = q.poll();
            if (f == null) {
                throw new EOFException();
            }
            return f;
        };
    }

    // ------------------------------------------------------------------ parser

    @Test
    @DisplayName("parser: stable / motion / overload frames, sign, units t / lb / g, bare weights")
    void parserDefaultFormat() {
        AsciiFrameParser p = AsciiFrameParser.of(Map.of());
        Frame st = p.parse("ST,GS,+0032150kg");
        assertThat(st.stable()).isTrue();
        assertThat(st.motion()).isFalse();
        assertThat(st.kilograms()).isEqualByComparingTo("32150");
        Frame us = p.parse("US,GS,+0032120 kg");
        assertThat(us.stable()).isFalse();
        assertThat(us.motion()).isTrue();
        assertThat(us.kilograms()).isEqualByComparingTo("32120");
        Frame ol = p.parse("OL,GS,+9999999kg");
        assertThat(ol.overload()).isTrue();
        assertThat(ol.stable()).isFalse();
        assertThat(p.parse("ST,NT,-000120kg").kilograms()).isEqualByComparingTo("-120");
        assertThat(p.parse("ST,GS,  32.15 t").kilograms()).isEqualByComparingTo("32150");
        assertThat(p.parse("1000 lb").kilograms()).isEqualByComparingTo("453.59");
        assertThat(p.parse("  0012345  ").stable()).isTrue();
        assertThat(p.parse("  0012345  ").kilograms()).isEqualByComparingTo("12345");
        assertThat(p.parse("garbage")).isNull();
        assertThat(p.parse("")).isNull();
        assertThat(p.parse(null)).isNull();
    }

    @Test
    @DisplayName("parser: configurable regex, status tokens, default unit, implied decimals, validation")
    void parserCustomFormat() {
        AsciiFrameParser p = AsciiFrameParser.of(Map.of(
                "frameRegex", "^W(?<status>[SM])(?<sign>[+-])(?<weight>\\d{7})$",
                "stableValues", "S", "motionValues", List.of("M"), "unit", "t", "decimals", 3, "stableFrames", 2));
        Frame f = p.parse("WS+0032150");
        assertThat(f.kilograms()).isEqualByComparingTo("32150");
        assertThat(f.stable()).isTrue();
        assertThat(p.parse("WM+0032150").motion()).isTrue();
        assertThat(p.stableFrames()).isEqualTo(2);
        assertThatThrownBy(() -> AsciiFrameParser.of(Map.of("frameRegex", "(")))
                .hasMessageStartingWith("The frame regex is invalid");
        assertThatThrownBy(() -> AsciiFrameParser.of(Map.of("frameRegex", "\\d+")))
                .hasMessage("The frame regex needs a named group (?<weight>...)");
        assertThatThrownBy(() -> AsciiFrameParser.of(Map.of("unit", "stone")))
                .hasMessage("Unit stone is not supported (kg, t, g, lb)");
        assertThatThrownBy(() -> AsciiFrameParser.of(Map.of("stableFrames", 0)))
                .hasMessage("Stable frames must be between 1 and 20");
    }

    // ------------------------------------------------------------------ reader

    @Test
    @DisplayName("reader: motion frames reset the run; N equal stable frames make the weight stable")
    void readerSettles() {
        AsciiFrameParser p = AsciiFrameParser.of(Map.of());
        StableWeight w = StableWeightReader.read(frames("noise", "US,GS,+0032100kg", "ST,GS,+0032140kg",
                "ST,GS,+0032150kg", "US,GS,+0032150kg", "ST,GS,+0032150kg", "ST,GS,+0032150kg", "ST,GS,+0032150kg"),
                p, Duration.ofSeconds(2), "WB1");
        assertThat(w.kilograms()).isEqualByComparingTo("32150");
        assertThat(w.stable()).isTrue();
        assertThat(w.rawFrame()).isEqualTo("ST,GS,+0032150kg");
    }

    @Test
    @DisplayName("reader: overload fails at once, closed source, never-settling and garbage time out")
    void readerFailures() {
        AsciiFrameParser p = AsciiFrameParser.of(Map.of());
        assertThatThrownBy(() -> StableWeightReader.read(frames("OL,GS,+9999999kg"), p, Duration.ofSeconds(1), "WB1"))
                .isInstanceOf(WeighbridgeReadException.class).hasMessageContaining("reports an overload")
                .extracting(e -> ((WeighbridgeReadException) e).reason()).isEqualTo("overload");
        assertThatThrownBy(() -> StableWeightReader.read(frames("ST,GS,+0032150kg"), p, Duration.ofSeconds(1), "WB1"))
                .hasMessage("Indicator WB1 closed the connection before a stable weight was read");
        StableWeightReader.FrameSource moving = max -> "US,GS,+0032150kg";
        assertThatThrownBy(() -> StableWeightReader.read(moving, p, Duration.ofMillis(200), "WB1"))
                .hasMessage("The weight on indicator WB1 did not settle within 1 s")
                .extracting(e -> ((WeighbridgeReadException) e).reason()).isEqualTo("unstable");
        StableWeightReader.FrameSource garbage = max -> "### noise";
        assertThatThrownBy(() -> StableWeightReader.read(garbage, p, Duration.ofMillis(100), "WB1"))
                .hasMessageEndingWith("do not match the configured frame format");
        StableWeightReader.FrameSource silent = max -> {
            try {
                Thread.sleep(Math.min(20, max.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> StableWeightReader.read(silent, p, Duration.ofMillis(300), "WB1"))
                .hasMessage("Indicator WB1 sent no weight within 1 s");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("line splitter: CR / LF / STX / ETX delimited frames from a byte stream")
    void lineSplitter() throws Exception {
        byte[] bytes = "\u0002ST,GS,+0001000kg\u0003\r\nUS,GS,+0001010kg\rlast".getBytes(StandardCharsets.US_ASCII);
        StableWeightReader.FrameSource src = StableWeightReader.lines(new java.io.ByteArrayInputStream(bytes), ms -> { });
        assertThat(src.next(Duration.ofSeconds(1))).isEqualTo("ST,GS,+0001000kg");
        assertThat(src.next(Duration.ofSeconds(1))).isEqualTo("US,GS,+0001010kg");
        assertThat(src.next(Duration.ofSeconds(1))).isEqualTo("last");
        assertThatThrownBy(() -> src.next(Duration.ofSeconds(1))).isInstanceOf(EOFException.class);
    }

    // ------------------------------------------------------------------ calculator

    @Test
    @DisplayName("net: gross = heavier, tare = lighter, order independent; equal weighings refused")
    void net() {
        Weighing out = WeighbridgeCalculator.net(d("12000"), d("32050"));
        assertThat(out.grossKg()).isEqualByComparingTo("32050");
        assertThat(out.tareKg()).isEqualByComparingTo("12000");
        assertThat(out.netKg()).isEqualByComparingTo("20050");
        Weighing in = WeighbridgeCalculator.net(d("27000.5"), d("12000.25"));
        assertThat(in.netKg()).isEqualByComparingTo("15000.25");
        assertThatThrownBy(() -> WeighbridgeCalculator.net(d("12000"), d("12000.00")))
                .isInstanceOf(ApiException.class).hasMessageContaining("nothing was loaded or unloaded");
    }

    @Test
    @DisplayName("tolerance: allowed = max(absolute kg, expected x %); product overrides the station per field")
    void tolerance() {
        Tolerance station = WeighbridgeCalculator.effective(null, null, d("0.5"), d("20"));
        Check ok = WeighbridgeCalculator.check(d("20050"), d("20000"), station);
        assertThat(ok.allowedKg()).isEqualByComparingTo("100");
        assertThat(ok.varianceKg()).isEqualByComparingTo("50");
        assertThat(ok.variancePct()).isEqualByComparingTo("0.25");
        assertThat(ok.within()).isTrue();
        Check edge = WeighbridgeCalculator.check(d("19900"), d("20000"), station);
        assertThat(edge.within()).isTrue();
        Check out = WeighbridgeCalculator.check(d("19899.99"), d("20000"), station);
        assertThat(out.within()).isFalse();
        assertThat(out.varianceKg()).isEqualByComparingTo("-100.01");
        // Small load: the absolute part (scale resolution) wins.
        Check small = WeighbridgeCalculator.check(d("1015"), d("1000"), station);
        assertThat(small.allowedKg()).isEqualByComparingTo("20");
        assertThat(small.within()).isTrue();
        Tolerance product = WeighbridgeCalculator.effective(d("2"), null, d("0.5"), d("20"));
        assertThat(product.pct()).isEqualByComparingTo("2");
        assertThat(product.kg()).isEqualByComparingTo("20");
        assertThat(WeighbridgeCalculator.check(d("20350"), d("20000"), product).within()).isTrue();
        Check none = WeighbridgeCalculator.check(d("20350"), null, station);
        assertThat(none.within()).isTrue();
        assertThat(none.varianceKg()).isNull();
    }

    @Test
    @DisplayName("stored tare: expires N days after weighing; superseded / revoked never usable")
    void tareExpiry() {
        Instant weighed = Instant.parse("2026-10-01T08:00:00Z");
        Instant exp = WeighbridgeCalculator.tareExpiresAt(weighed, 30);
        assertThat(exp).isEqualTo(Instant.parse("2026-10-31T08:00:00Z"));
        assertThat(WeighbridgeCalculator.tareState("active", exp, Instant.parse("2026-10-31T07:59:59Z")))
                .isEqualTo("valid");
        assertThat(WeighbridgeCalculator.tareState("active", exp, exp)).isEqualTo("expired");
        assertThat(WeighbridgeCalculator.tareUsable("active", exp, Instant.parse("2026-11-02T00:00:00Z"))).isFalse();
        assertThat(WeighbridgeCalculator.tareState("superseded", exp, weighed)).isEqualTo("superseded");
        assertThat(WeighbridgeCalculator.tareUsable("revoked", exp, weighed)).isFalse();
        assertThatThrownBy(() -> WeighbridgeCalculator.tareExpiresAt(weighed, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ adapters

    @Test
    @DisplayName("simulator: deterministic stable read after motion frames; failure modes; config validation")
    void simulator() {
        SimulatorWeighbridgeAdapter sim = new SimulatorWeighbridgeAdapter();
        StableWeight w = sim.readStable(new DeviceSpec("1", "SIM", "simulator", Map.of("weightKg", "32150",
                "motionFrames", 3)), Duration.ofSeconds(1));
        assertThat(w.kilograms()).isEqualByComparingTo("32150");
        assertThat(w.rawFrame()).isEqualTo("ST,GS,+0032150 kg");
        assertThatThrownBy(() -> sim.readStable(new DeviceSpec("1", "SIM", "simulator", Map.of("mode", "unstable")),
                Duration.ofMillis(300))).hasMessageContaining("did not settle");
        assertThatThrownBy(() -> sim.readStable(new DeviceSpec("1", "SIM", "simulator", Map.of("mode", "overload")),
                Duration.ofMillis(300))).hasMessageContaining("overload");
        assertThatThrownBy(() -> sim.readStable(new DeviceSpec("1", "SIM", "simulator", Map.of("mode", "offline")),
                Duration.ofMillis(300))).hasMessage("Indicator SIM is offline");
        assertThat(sim.health(new DeviceSpec("1", "SIM", "simulator", Map.of()), Duration.ofSeconds(1)).reachable())
                .isTrue();
        assertThatThrownBy(() -> sim.validate(Map.of("mode", "dance"))).hasMessageStartingWith("Simulator mode dance");
        assertThatThrownBy(() -> sim.validate(Map.of("weightKg", "heavy")))
                .hasMessage("The simulated weight must be a number");
    }

    @Test
    @DisplayName("ascii-tcp: reads a stable weight from an in-test indicator server; poll command; unreachable")
    void tcpAdapter() throws Exception {
        AsciiTcpWeighbridgeAdapter tcp = new AsciiTcpWeighbridgeAdapter();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread t = Thread.ofVirtual().start(() -> {
                try (Socket s = server.accept()) {
                    // Wait for the poll command, then stream frames.
                    byte[] cmd = new byte[3];
                    int n = s.getInputStream().read(cmd);
                    OutputStream out = s.getOutputStream();
                    String frames = (n == 3 && "W\r\n".equals(new String(cmd, StandardCharsets.US_ASCII)))
                            ? "US,GS,+0012340kg\r\nUS,GS,+0012345kg\r\nST,GS,+0012350kg\r\nST,GS,+0012350kg\r\n"
                                    + "ST,GS,+0012350kg\r\n" : "OL,GS,+9999999kg\r\n";
                    out.write(frames.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(200);
                } catch (Exception ignored) {
                    // test server
                }
            });
            Map<String, Object> config = Map.of("host", "127.0.0.1", "port", server.getLocalPort(), "requestCommand",
                    "W\\r\\n");
            StableWeight w = tcp.readStable(new DeviceSpec("1", "TCP1", "ascii-tcp", config), Duration.ofSeconds(3));
            assertThat(w.kilograms()).isEqualByComparingTo("12350");
            assertThat(w.rawFrame()).isEqualTo("ST,GS,+0012350kg");
            t.join(2000);
        }
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> tcp.readStable(new DeviceSpec("1", "TCP1", "ascii-tcp", Map.of("host", "127.0.0.1",
                "port", closedPort)), Duration.ofSeconds(1))).isInstanceOf(WeighbridgeReadException.class)
                .extracting(e -> ((WeighbridgeReadException) e).reason()).isEqualTo("unreachable");
        assertThat(tcp.health(new DeviceSpec("1", "TCP1", "ascii-tcp", Map.of("host", "127.0.0.1", "port", closedPort)),
                Duration.ofSeconds(1)).reachable()).isFalse();
        assertThatThrownBy(() -> tcp.validate(Map.of("host", "x"))).hasMessage(
                "The indicator port must be between 1 and 65535");
        assertThatThrownBy(() -> new AsciiSerialWeighbridgeAdapter().validate(Map.of("port", "COM1")))
                .hasMessage("The serial port must be a device path such as /dev/ttyUSB0");
    }
}
