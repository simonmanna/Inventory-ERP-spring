package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Loads line modifiers for many order / invoice lines in one statement instead of one per line. */
final class PosModifiers {

    private PosModifiers() {}

    /**
     * Puts {@code modifiers} on every line: its rows of {@code table} by {@code fk}, ordered by createdAt, id (as the
     * per-line query did); an empty list when it has none.
     */
    static void attach(JdbcClient jdbc, List<Map<String, Object>> lines, Class<?> entity, String table, String fk) {
        if (lines.isEmpty()) {
            return;
        }
        List<Object> ids = new ArrayList<>(lines.size());
        for (Map<String, Object> ln : lines) {
            ids.add(ln.get("id"));
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        Map<Object, List<Map<String, Object>>> byLine = new HashMap<>();
        for (Map<String, Object> m : SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table + "\" WHERE \"" + fk
                + "\" IN (" + marks + ") ORDER BY \"createdAt\", id").params(ids))) {
            byLine.computeIfAbsent(m.get(fk), k -> new ArrayList<>()).add(m);
        }
        for (Map<String, Object> ln : lines) {
            ln.put("modifiers", byLine.getOrDefault(ln.get("id"), new ArrayList<>()));
        }
    }
}
