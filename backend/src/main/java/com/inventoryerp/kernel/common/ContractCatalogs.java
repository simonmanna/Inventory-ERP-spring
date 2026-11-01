package com.inventoryerp.kernel.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime copies of the TypeScript contract in packages/shared, generated as JSON into
 * {@code resources/contracts} by tools/contracts2java: the nested permission tree, the report catalog
 * and the event-subject map.
 */
public final class ContractCatalogs {

    /** A configurable report ({@code REPORT_CATALOG} entry); optional fields are left out when unset, as in Nest. */
    public record Report(String id, String label, String group, String permission, String route,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tab,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description) {}

    /** Ledger subject of a business event ({@code EVENT_SUBJECT} entry). */
    public record EventSubject(String entityType, String idField) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode PERMISSION_TREE = read("permission-tree.json");
    private static final List<Report> REPORTS = reports();
    private static final Map<String, EventSubject> EVENT_SUBJECTS = subjects();

    private ContractCatalogs() {}

    /** {@code PERMISSIONS} from packages/shared/src/permissions.ts, key order preserved. */
    public static JsonNode permissionTree() {
        return PERMISSION_TREE;
    }

    public static List<Report> reportCatalog() {
        return REPORTS;
    }

    public static Map<String, EventSubject> eventSubjects() {
        return EVENT_SUBJECTS;
    }

    private static JsonNode read(String name) {
        try (InputStream in = ContractCatalogs.class.getResourceAsStream("/contracts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing contracts/" + name + " (run tools/contracts2java)");
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Report> reports() {
        List<Report> out = new ArrayList<>();
        for (JsonNode r : read("report-catalog.json")) {
            out.add(new Report(text(r, "id"), text(r, "label"), text(r, "group"), text(r, "permission"),
                    text(r, "route"), text(r, "tab"), text(r, "description")));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, EventSubject> subjects() {
        Map<String, EventSubject> out = new LinkedHashMap<>();
        read("event-subjects.json").properties().forEach(e -> out.put(e.getKey(),
                new EventSubject(text(e.getValue(), "entityType"), text(e.getValue(), "idField"))));
        return Collections.unmodifiableMap(out);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
