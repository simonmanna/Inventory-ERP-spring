package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.MachineEventParser.Parsed;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Machine-data CSV parsing and event checks (ADR-S015). */
class MachineEventParserTest {

    @Test
    @DisplayName("CSV: any column order, weight = count, stable default ids, row errors with row numbers")
    void csv() {
        Parsed p = MachineEventParser.parseCsv("""
                ﻿Type,MachineCode,OccurredAt,EndedAt,GoodQty,ScrapQty,ReasonCode,Notes
                weight,CR-1,2026-10-30T06:00:00Z,2026-10-30T07:00:00Z,152.5,,,"belt, scale"
                downtime,CR-1,2026-10-30T07:10:00Z,2026-10-30T07:25:00Z,,,BLOCKAGE,
                downtime_start,CR-1,2026-10-30T08:00:00Z,,,,,
                downtime_stop,CR-1,2026-10-30T08:12:00Z,,,,,

                count,CR-1,2026-10-30T09:00:00Z,,,,,
                count,,2026-10-30T09:00:00Z,,1,,,
                spin,CR-1,2026-10-30T09:00:00Z,,1,,,
                count,CR-1,yesterday,,1,,,
                count,CR-1,2026-10-30T09:00:00Z,,x,,,
                downtime,CR-1,2026-10-30T09:00:00Z,,,,,
                downtime,CR-1,2026-10-30T09:00:00Z,2026-10-30T08:00:00Z,,,,
                count,CR-1,2026-10-30T09:00:00Z,,-1,,,
                """);
        assertThat(p.events()).hasSize(4);
        MachineEvent w = p.events().get(0);
        assertThat(w.type()).isEqualTo("count");
        assertThat(w.goodQty()).isEqualByComparingTo("152.5");
        assertThat(w.notes()).isEqualTo("belt, scale");
        assertThat(w.externalId()).isEqualTo("CR-1|count|2026-10-30T06:00:00Z");
        assertThat(p.events().get(1).reasonCode()).isEqualTo("BLOCKAGE");
        assertThat(p.errors()).extracting(MachineEventParser.RowError::row).containsExactly(7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(p.errors().get(0).message()).isEqualTo("A count needs goodQty or scrapQty.");
        assertThat(p.errors().get(1).message()).isEqualTo("machineCode is required.");
        assertThat(p.errors().get(3).message()).isEqualTo("Invalid date / time.");
        assertThat(p.errors().get(4).message()).isEqualTo("Invalid number.");
        assertThat(p.errors().get(7).message()).isEqualTo("Quantities must not be negative.");
    }

    @Test
    @DisplayName("CSV: semicolons, missing columns, empty file")
    void shapes() {
        Parsed semi = MachineEventParser.parseCsv("machineCode;type;occurredAt;goodQty;externalId\r\n"
                + "P-1;count;2026-10-30;12;ext-1\r\n");
        assertThat(semi.events()).hasSize(1);
        assertThat(semi.events().getFirst().externalId()).isEqualTo("ext-1");
        assertThat(semi.events().getFirst().occurredAt()).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(MachineEventParser.parseCsv("machine,when\nP-1,now").errors()).hasSize(3);
        assertThat(MachineEventParser.parseCsv(" ").errors().getFirst().message()).isEqualTo("The file is empty.");
    }

    @Test
    @DisplayName("check / normalise single events")
    void check() {
        MachineEvent e = MachineEventParser.normalise(new MachineEvent(" ", " M-1 ", "WEIGHT",
                Instant.parse("2026-10-30T06:00:00Z"), null, BigDecimal.ONE, null, " ", null));
        assertThat(e.machineCode()).isEqualTo("M-1");
        assertThat(e.type()).isEqualTo("count");
        assertThat(e.reasonCode()).isNull();
        assertThat(MachineEventParser.check(e)).isNull();
        assertThat(MachineEventParser.check(new MachineEvent("x", "M", "count", null, null, BigDecimal.ONE, null,
                null, null))).isEqualTo("occurredAt is required.");
        assertThat(MachineEventParser.check(new MachineEvent("x", "M", null, Instant.now(), null, null, null, null,
                null))).startsWith("type must be one of");
    }
}
