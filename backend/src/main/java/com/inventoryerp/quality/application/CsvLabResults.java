package com.inventoryerp.quality.application;

import com.inventoryerp.quality.application.LabResultAdapter.LabResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser of laboratory CSV files (ADR-S013 §3). The first line is a header (any order, case-insensitive):
 * {@code lotNumber, parameterCode} (required), {@code value} or {@code sieveMm + passingPct}, and optional
 * {@code sampleNo, textValue, testedAt (ISO date or date-time), labReference}. Separator: comma or semicolon; double
 * quotes protect separators. One row per value; a sieve analysis is one row per sieve.
 */
public final class CsvLabResults {

    private CsvLabResults() {}

    /** A parsed row ({@code result} null when {@code error} is set); {@code row} is the 1-based file line. */
    public record Row(int row, LabResult result, String error) {}

    public static List<Row> parse(String content) {
        List<Row> out = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int headerAt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                headerAt = i;
                break;
            }
        }
        if (headerAt < 0) {
            throw new IllegalArgumentException("The file is empty.");
        }
        char sep = lines[headerAt].indexOf(';') >= 0 && lines[headerAt].indexOf(',') < 0 ? ';' : ',';
        List<String> header = split(lines[headerAt], sep);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", ""), i);
        }
        if (!col.containsKey("lotnumber") || !col.containsKey("parametercode")) {
            throw new IllegalArgumentException("The header needs the columns lotNumber and parameterCode.");
        }
        for (int i = headerAt + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> cells = split(lines[i], sep);
            try {
                String lot = req(cells, col, "lotnumber");
                String param = req(cells, col, "parametercode").toUpperCase(Locale.ROOT);
                String sample = cell(cells, col, "sampleno");
                BigDecimal value = num(cell(cells, col, "value"));
                BigDecimal sieve = num(cell(cells, col, "sievemm"));
                BigDecimal passing = num(cell(cells, col, "passingpct"));
                String text = cell(cells, col, "textvalue");
                if (value == null && text == null && (sieve == null || passing == null)) {
                    throw new IllegalArgumentException("needs value, textValue or sieveMm + passingPct");
                }
                out.add(new Row(i + 1, new LabResult(lot, param, sample == null ? 1 : Integer.parseInt(sample.trim()),
                        value, text, sieve, passing, instant(cell(cells, col, "testedat")),
                        cell(cells, col, "labreference")), null));
            } catch (RuntimeException e) {
                out.add(new Row(i + 1, null, e instanceof NumberFormatException ? "not a number" : e.getMessage()));
            }
        }
        return out;
    }

    private static String req(List<String> cells, Map<String, Integer> col, String name) {
        String v = cell(cells, col, name);
        if (v == null) {
            throw new IllegalArgumentException(name.equals("lotnumber") ? "lotNumber is empty" : "parameterCode is empty");
        }
        return v;
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String v = cells.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal num(String v) {
        return v == null ? null : new BigDecimal(v.replace(" ", ""));
    }

    private static Instant instant(String v) {
        if (v == null) {
            return null;
        }
        try {
            return v.length() <= 10 ? LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.parse(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("testedAt is not an ISO date");
        }
    }

    static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
