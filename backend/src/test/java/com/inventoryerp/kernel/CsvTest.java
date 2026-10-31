package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.Csv;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CSV for exports. */
class CsvTest {

    static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("BOM, header, quoting, numbers and empty cells")
    void shape() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("a", "x, \"y\"");
        row.put("b", new BigDecimal("1.50"));
        row.put("c", null);
        assertThat(text(Csv.of(List.of("a", "b", "c"), List.of(row))))
                .isEqualTo("﻿a,b,c\r\n\"x, \"\"y\"\"\",1.50,\r\n");
    }

    @Test
    @DisplayName("a text cell that would run as a formula is neutralized; a negative number is not")
    void formula() {
        assertThat(text(Csv.of(List.of("a", "b"), List.of(Map.of("a", "=HYPERLINK(1)", "b", new BigDecimal("-2"))))))
                .contains("'=HYPERLINK(1),-2");
    }
}
