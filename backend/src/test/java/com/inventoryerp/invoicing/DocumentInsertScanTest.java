package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * ADR-S094 §8.2 (IS-2): {@code DocumentBuilderService} is the only class that inserts a {@code Document} row, so every
 * customer document carries its {@code SourceRef}. Files are read as raw bytes, because one source file holds a NUL
 * byte and plain {@code grep} silently skips it; the self-check proves the scan sees the builder's own inserts.
 */
class DocumentInsertScanTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String BUILDER = "com/inventoryerp/invoicing/application/DocumentBuilderService.java";
    private static final String REPOSITORY = "com/inventoryerp/documents/domain/DocumentRepository.java";

    /** SQL text, in a string literal ({@code \"Document\"}) or a text block ({@code "Document"}). */
    private static final Pattern SQL_INSERT = Pattern.compile(
            "(?i)insert\\s+into\\s+(?:public\\.)?\\\\?\"Document\\\\?\"");
    /** Table helpers: {@code insert("Document", …)} or {@code insert(Document.class, …)}. */
    private static final Pattern HELPER_INSERT = Pattern.compile(
            "insert\\(\\s*(?:\\\\?\"Document\\\\?\"|(?:[\\w.]+\\.)?(?<![\\w])Document\\.class)\\s*,");
    /** JPA saves through the generated repository. */
    private static final Pattern JPA = Pattern.compile("\\bDocumentRepository\\b");

    record Hit(String file, int line, String text) {}

    static List<Hit> scan(String file, String source) {
        List<Hit> hits = new ArrayList<>();
        for (Pattern p : List.of(SQL_INSERT, HELPER_INSERT, JPA)) {
            if (p == JPA && file.equals(REPOSITORY)) {
                continue;
            }
            Matcher m = p.matcher(source);
            while (m.find()) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) {
                    if (source.charAt(i) == '\n') {
                        line++;
                    }
                }
                hits.add(new Hit(file, line, m.group()));
            }
        }
        return hits;
    }

    @Test
    @DisplayName("only DocumentBuilderService inserts Document rows (self-check: its own three inserts are found)")
    void onlyTheBuilderInsertsDocuments() throws IOException {
        List<Hit> elsewhere = new ArrayList<>();
        int builder = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                List<Hit> found = scan(rel, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
                if (rel.equals(BUILDER)) {
                    builder += found.size();
                } else {
                    elsewhere.addAll(found);
                }
            }
        }
        assertThat(builder).as("self-check: createDocument, the recurring writer and the counterpart writer")
                .isEqualTo(3);
        assertThat(elsewhere).as("insert Document rows through DocumentBuilderService with a SourceRef").isEmpty();
    }

    @Test
    @DisplayName("the scan catches each way of inserting a Document row, and only those")
    void theScanCatchesEachWay() {
        assertThat(scan("x/A.java", "jdbc.sql(\"INSERT INTO \\\"Document\\\" (id) VALUES (?)\")")).hasSize(1);
        assertThat(scan("x/A.java", "\"\"\"\n    insert into \"Document\" (id, \"organizationId\")\n\"\"\"")).hasSize(1);
        assertThat(scan("x/A.java", "jdbc.sql(\"INSERT INTO public.\\\"Document\\\" (id)\")")).hasSize(1);
        assertThat(scan("x/A.java", "db.insert(\"Document\", Obj.of(\"id\", id))")).hasSize(1);
        assertThat(scan("x/A.java", "db.insert(Document.class, Obj.of(\"id\", id))")).hasSize(1);
        assertThat(scan("x/A.java", "private final DocumentRepository documents;")).hasSize(1);
        assertThat(scan("x/A.java", "jdbc.sql(\"INSERT INTO \\\"DocumentLine\\\" (id) VALUES (?)\")")).isEmpty();
        assertThat(scan("x/A.java", "db.insert(HrEmployeeDocument.class, Obj.of())")).isEmpty();
        assertThat(scan("x/A.java", "UPDATE \\\"Document\\\" SET notes = ?")).isEmpty();
    }
}
