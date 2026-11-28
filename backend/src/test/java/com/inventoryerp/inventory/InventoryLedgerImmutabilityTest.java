package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IPR R1 (Phase 0, task T04): the stock ledger is append-only. Besides the database trigger
 * ({@code inventory_ledger_append_only}, checked by {@code InventoryIntegrityP0IT.ledgerGuardEnabled}), no main source
 * file and no migration may contain an {@code UPDATE "InventoryLedger"} or {@code DELETE FROM "InventoryLedger"}
 * statement: every correction is a compensating row. A plain source scan (ArchUnit sees bytecode, not SQL strings),
 * so it runs in the fast loop too.
 */
class InventoryLedgerImmutabilityTest {

    /** {@code UPDATE "InventoryLedger"} / {@code DELETE FROM "InventoryLedger"}, quotes escaped or not, any spacing. */
    static final Pattern WRITE = Pattern.compile(
            "(?i)(UPDATE\\s+(ONLY\\s+)?(public\\.)?\\\\?\"InventoryLedger\\\\?\"|DELETE\\s+FROM\\s+(ONLY\\s+)?(public\\.)?"
                    + "\\\\?\"InventoryLedger\\\\?\")");

    static List<String> scan(Path root, String suffix) throws IOException {
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(suffix)).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = WRITE.matcher(lines.get(i));
                    if (m.find()) {
                        hits.add(f + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("R1: no main code or migration updates or deletes InventoryLedger rows")
    void noLedgerUpdateOrDelete() throws IOException {
        List<String> hits = new ArrayList<>(scan(Path.of("src/main/java"), ".java"));
        hits.addAll(scan(Path.of("src/main/resources/db/migration"), ".sql").stream()
                // The baseline defines the guard itself (its trigger function names the statements it refuses).
                .filter(h -> !h.contains("V1__baseline.sql")).toList());
        assertThat(Files.isDirectory(Path.of("src/main/java"))).as("run from the backend project directory").isTrue();
        assertThat(hits).as("ledger rows are append-only: post a compensating row instead").isEmpty();
    }

    @Test
    @DisplayName("R1: the scan pattern recognises the forms it must refuse")
    void patternMatches() {
        for (String sql : List.of("UPDATE \"InventoryLedger\" SET x = 1", "update public.\"InventoryLedger\" set",
                "\"DELETE FROM \\\"InventoryLedger\\\" WHERE id = ?\"", "DELETE  FROM ONLY \"InventoryLedger\"")) {
            assertThat(WRITE.matcher(sql).find()).as(sql).isTrue();
        }
        for (String sql : List.of("INSERT INTO \"InventoryLedger\"", "UPDATE \"InventoryLedgerArchive\" SET",
                "SELECT * FROM \"InventoryLedger\"")) {
            assertThat(WRITE.matcher(sql).find()).as(sql).isFalse();
        }
    }
}
