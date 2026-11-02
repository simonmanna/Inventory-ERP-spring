package com.inventoryerp.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.reporting.application.StatementLayoutService.Layout;
import com.inventoryerp.reporting.application.StatementLayoutService.Line;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** FR-0 package internals: shipped layouts, layout validation, schedule times and the catalogue. */
class ReportingInternalsTest {

    @Test
    @DisplayName("shipped layouts are valid; validation refuses duplicates, unknown references and cycles")
    void layouts() {
        for (Layout l : StatementLayoutService.defaults()) {
            StatementLayoutService.validate(l.lines());
        }
        List<Line> pl = new ArrayList<>(StatementLayoutService.defaults().getFirst().lines());
        pl.add(pl.getFirst());
        assertThatThrownBy(() -> StatementLayoutService.validate(pl)).hasMessage("Line code REV is used twice");
        List<Line> cyc = List.of(
                new Line("A", "A", "FORMULA", null, "B + 1", "CREDIT", 0, false, false, false),
                new Line("B", "B", "FORMULA", null, "A", "CREDIT", 0, false, false, false));
        assertThatThrownBy(() -> StatementLayoutService.validate(cyc)).hasMessageContaining("refers to itself");
        List<Line> bad = List.of(new Line("A", "A", "TOTAL", null, null, "CREDIT", 0, false, false, false));
        assertThatThrownBy(() -> StatementLayoutService.validate(bad)).hasMessageContaining("lineType must be");
    }

    @Test
    @DisplayName("schedules: next run is strictly later, at the hour, on the right weekday or day of month")
    void nextRun() {
        ZoneId kampala = ZoneId.of("Africa/Kampala");
        Instant mon0800 = Instant.parse("2026-11-02T05:00:00Z");
        assertThat(ReportScheduleService.nextRun("DAILY", null, null, 7, kampala, mon0800))
                .isEqualTo(Instant.parse("2026-11-03T04:00:00Z"));
        assertThat(ReportScheduleService.nextRun("DAILY", null, null, 9, kampala, mon0800))
                .isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
        assertThat(ReportScheduleService.nextRun("WEEKLY", 5, null, 7, kampala, mon0800))
                .isEqualTo(Instant.parse("2026-11-06T04:00:00Z"));
        assertThat(ReportScheduleService.nextRun("MONTHLY", null, 1, 6, kampala, mon0800))
                .isEqualTo(Instant.parse("2026-12-01T03:00:00Z"));
    }

    @Test
    @DisplayName("catalogue links load, are links (not engine reports) and have unique ids")
    void catalogue() {
        var links = ReportRegistry.links(JsonMapper.builder().build());
        assertThat(links).hasSizeGreaterThan(80);
        assertThat(links).allMatch(d -> !d.engine() && d.apiRoute() != null && d.permission() != null);
        assertThat(links.stream().map(d -> d.id()).distinct().count()).isEqualTo(links.size());
        assertThat(ReportEngine.sha256("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
