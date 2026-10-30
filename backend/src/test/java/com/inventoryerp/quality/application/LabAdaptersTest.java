package com.inventoryerp.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.quality.application.LabResultAdapter.LabResult;
import com.inventoryerp.quality.application.LabResultAdapter.LotRef;
import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import com.inventoryerp.quality.application.SpecEvaluator.Sample;
import com.inventoryerp.quality.application.SpecEvaluator.SievePoint;
import com.inventoryerp.quality.application.SpecEvaluator.SieveReading;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lab SPI adapters (ADR-S013): simulator results pass the spec; the REST adapter maps a lab's JSON. */
class LabAdaptersTest {

    static final Parameter MOIST = new Parameter("m", "MOIST", "Moisture", "numeric", "%", null, new BigDecimal("3"),
            null, null, List.of(), true);
    static final Parameter GRADING = new Parameter("g", "GRADING", "Grading", "sieve", "%", null, null, null, null,
            List.of(new SievePoint(new BigDecimal("20"), new BigDecimal("85"), new BigDecimal("100")),
                    new SievePoint(new BigDecimal("10"), new BigDecimal("0"), new BigDecimal("25"))), true);
    static final Parameter STRENGTH = new Parameter("s", "S7", "Strength 7 d", "numeric", "N/mm2", new BigDecimal("7"),
            null, null, null, List.of(), true);

    @Test
    @DisplayName("simulator: one result per sample and parameter (one row per sieve), all within the specification")
    void simulator() {
        List<LabResult> rows = new SimulatorLabResultAdapter().fetch(List.of(new LotRef("l1", "QL-1", 2,
                List.of(MOIST, GRADING, STRENGTH))));
        assertThat(rows).hasSize(2 * (1 + 2 + 1));
        for (int s = 1; s <= 2; s++) {
            int sample = s;
            BigDecimal m = rows.stream().filter(r -> r.sampleNo() == sample && "MOIST".equals(r.parameterCode()))
                    .findFirst().orElseThrow().value();
            assertThat(SpecEvaluator.check(MOIST, new Sample(sample, m, null, null))).isEmpty();
            List<SieveReading> sieve = new ArrayList<>();
            rows.stream().filter(r -> r.sampleNo() == sample && "GRADING".equals(r.parameterCode()))
                    .forEach(r -> sieve.add(new SieveReading(r.sieveMm(), r.passingPct())));
            assertThat(SpecEvaluator.validateSieve(sieve)).isNull();
            assertThat(SpecEvaluator.check(GRADING, new Sample(sample, null, null, sieve))).isEmpty();
            BigDecimal st = rows.stream().filter(r -> r.sampleNo() == sample && "S7".equals(r.parameterCode()))
                    .findFirst().orElseThrow().value();
            assertThat(st).isGreaterThanOrEqualTo(new BigDecimal("7"));
        }
    }

    @Test
    @DisplayName("REST: GET <base>/results?lot=…, bearer token, JSON array mapped; unconfigured is refused")
    void rest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/api/results", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            query.set(ex.getRequestURI().getQuery());
            byte[] body = """
                    [{"parameterCode":"MOIST","sampleNo":1,"value":2.1,"labReference":"L-9",
                      "testedAt":"2026-10-30T07:00:00Z"},
                     {"parameterCode":"GRADING","sieveMm":20,"passingPct":91}]""".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            RestLabResultAdapter rest = new RestLabResultAdapter("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/api/", "secret-token", 2000);
            assertThat(rest.configured()).isTrue();
            List<LabResult> rows = rest.fetch(List.of(new LotRef("l1", "QL-000007", 1, List.of(MOIST, GRADING))));
            assertThat(auth.get()).isEqualTo("Bearer secret-token");
            assertThat(query.get()).isEqualTo("lot=QL-000007");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).lotNumber()).isEqualTo("QL-000007");
            assertThat(rows.get(0).value()).isEqualByComparingTo("2.1");
            assertThat(rows.get(0).testedAt()).isNotNull();
            assertThat(rows.get(1).sampleNo()).isEqualTo(1);
            assertThat(rows.get(1).sieveMm()).isEqualByComparingTo("20");
        } finally {
            server.stop(0);
        }
        RestLabResultAdapter off = new RestLabResultAdapter("", "", 1000);
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.fetch(List.of())).hasMessageContaining("QUALITY_LAB_REST_URL");
    }
}
