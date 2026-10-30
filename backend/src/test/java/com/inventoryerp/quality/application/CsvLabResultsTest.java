package com.inventoryerp.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Laboratory CSV parsing (ADR-S013). */
class CsvLabResultsTest {

    @Test
    @DisplayName("header in any order, quoted cells, semicolons, sieve rows, per-row errors")
    void parses() {
        List<CsvLabResults.Row> rows = CsvLabResults.parse("""
                parameterCode,lotNumber,sampleNo,value,sieveMm,passingPct,testedAt,labReference
                moist,QL-000001,1,2.4,,,2026-10-30,"LAB-1, bay 2"
                GRADING,QL-000001,1,,20,92,2026-10-30T08:00:00Z,LAB-1
                GRADING,QL-000001,1,,10,x,,LAB-1
                ,QL-000001,1,1,,,,
                CLEAN,QL-000002,,,,,,
                """);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).result().parameterCode()).isEqualTo("MOIST");
        assertThat(rows.get(0).result().value()).isEqualByComparingTo("2.4");
        assertThat(rows.get(0).result().labReference()).isEqualTo("LAB-1, bay 2");
        assertThat(rows.get(0).result().testedAt()).isNotNull();
        assertThat(rows.get(1).result().sieveMm()).isEqualByComparingTo("20");
        assertThat(rows.get(1).result().passingPct()).isEqualByComparingTo("92");
        assertThat(rows.get(2).error()).isEqualTo("not a number");
        assertThat(rows.get(2).row()).isEqualTo(4);
        assertThat(rows.get(3).error()).isEqualTo("parameterCode is empty");
        assertThat(rows.get(4).error()).contains("needs value");

        List<CsvLabResults.Row> semi = CsvLabResults.parse("lotNumber;parameterCode;value\nQL-1;S7;7.5\n");
        assertThat(semi.getFirst().result().value()).isEqualByComparingTo("7.5");
        assertThat(semi.getFirst().result().sampleNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty file or a header without the key columns is refused")
    void refuses() {
        assertThatThrownBy(() -> CsvLabResults.parse("  \n")).hasMessage("The file is empty.");
        assertThatThrownBy(() -> CsvLabResults.parse("lot,value\n1,2")).hasMessageContaining("lotNumber");
    }
}
