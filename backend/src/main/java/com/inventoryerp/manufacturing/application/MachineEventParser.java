package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validation and CSV parsing of machine-data events (ADR-S015), pure. CSV: a header row (comma or semicolon, any
 * column order, case-insensitive) with {@code machineCode, type, occurredAt} and optionally {@code endedAt, goodQty,
 * scrapQty, reasonCode, externalId, notes}; {@code weight} is accepted as a count (belt scale tonnes). A row without
 * an external id gets a stable one ({@code machine|type|occurredAt}) so re-importing the same file is harmless.
 */
public final class MachineEventParser {

    static final Set<String> TYPES = Set.of("count", "downtime", "downtime_start", "downtime_stop");
    static final int MAX_EVENTS = 5000;

    private MachineEventParser() {}

    /** A rejected row / event (1-based row number of the CSV, or the event index for a push). */
    public record RowError(int row, String message) {}

    public record Parsed(List<MachineEvent> events, List<RowError> errors) {}

    /** Normalise ({@code weight} → {@code count}, default external id) and check one event; null message = valid. */
    public static String check(MachineEvent e) {
        if (e.machineCode() == null || e.machineCode().isBlank()) {
            return "machineCode is required.";
        }
        if (e.type() == null || !TYPES.contains(e.type())) {
            return "type must be one of count, downtime, downtime_start, downtime_stop.";
        }
        if (e.occurredAt() == null) {
            return "occurredAt is required.";
        }
        if (e.endedAt() != null && e.endedAt().isBefore(e.occurredAt())) {
            return "endedAt must not be before occurredAt.";
        }
        if (negative(e.goodQty()) || negative(e.scrapQty())) {
            return "Quantities must not be negative.";
        }
        if ("count".equals(e.type()) && e.goodQty() == null && e.scrapQty() == null) {
            return "A count needs goodQty or scrapQty.";
        }
        if ("downtime".equals(e.type()) && e.endedAt() == null) {
            return "A downtime event needs endedAt (or use downtime_start / downtime_stop).";
        }
        return null;
    }

    /** {@code weight} → {@code count}, trimmed codes and a stable external id. */
    public static MachineEvent normalise(MachineEvent e) {
        String type = e.type() == null ? null : e.type().trim().toLowerCase(Locale.ROOT);
        if ("weight".equals(type)) {
            type = "count";
        }
        String machine = e.machineCode() == null ? null : e.machineCode().trim();
        String ext = e.externalId() == null || e.externalId().isBlank()
                ? machine + "|" + type + "|" + e.occurredAt() : e.externalId().trim();
        String reason = e.reasonCode() == null || e.reasonCode().isBlank() ? null : e.reasonCode().trim();
        return new MachineEvent(ext, machine, type, e.occurredAt(), e.endedAt(), e.goodQty(), e.scrapQty(), reason,
                e.notes());
    }

    public static Parsed parseCsv(String content) {
        List<MachineEvent> events = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        if (content == null || content.isBlank()) {
            errors.add(new RowError(0, "The file is empty."));
            return new Parsed(events, errors);
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        char sep = lines[0].indexOf(';') >= 0 && lines[0].indexOf(',') < 0 ? ';' : ',';
        Map<String, Integer> col = new HashMap<>();
        List<String> header = split(stripBom(lines[0]), sep);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : List.of("machinecode", "type", "occurredat")) {
            if (!col.containsKey(required)) {
                errors.add(new RowError(1, "Missing column " + required + "."));
            }
        }
        if (!errors.isEmpty()) {
            return new Parsed(events, errors);
        }
        for (int r = 1; r < lines.length; r++) {
            if (lines[r].isBlank()) {
                continue;
            }
            if (events.size() >= MAX_EVENTS) {
                errors.add(new RowError(r + 1, "More than " + MAX_EVENTS + " events; split the file."));
                break;
            }
            List<String> cells = split(lines[r], sep);
            try {
                MachineEvent e = normalise(new MachineEvent(cell(cells, col, "externalid"),
                        cell(cells, col, "machinecode"), cell(cells, col, "type"), time(cell(cells, col, "occurredat")),
                        time(cell(cells, col, "endedat")), num(cell(cells, col, "goodqty")),
                        num(cell(cells, col, "scrapqty")), cell(cells, col, "reasoncode"), cell(cells, col, "notes")));
                String problem = check(e);
                if (problem != null) {
                    errors.add(new RowError(r + 1, problem));
                } else {
                    events.add(e);
                }
            } catch (DateTimeParseException ex) {
                errors.add(new RowError(r + 1, "Invalid date / time."));
            } catch (NumberFormatException ex) {
                errors.add(new RowError(r + 1, "Invalid number."));
            }
        }
        return new Parsed(events, errors);
    }

    private static boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String v = cells.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    private static Instant time(String v) {
        return v == null ? null : EntityPatch.toInstant(v);
    }

    private static BigDecimal num(String v) {
        return v == null ? null : new BigDecimal(v);
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
